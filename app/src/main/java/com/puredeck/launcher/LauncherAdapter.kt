package com.puredeck.launcher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

/**
 * One list, two sections: pinned apps (the glass top row), then every other app.
 * A filler cell pads a short top row so the grid always starts on a fresh row.
 */
class LauncherAdapter(
    private val columns: Int,
    private val listener: Listener
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Listener {
        fun onAppFocused(entry: AppEntry, pinned: Boolean)
        fun onAppSelected(entry: AppEntry, view: View)
        fun onAppLongPressed(entry: AppEntry)
    }

    val pinned = mutableListOf<AppEntry>()
    private val others = mutableListOf<AppEntry>()

    /** App being rearranged; its tile wiggles. */
    var movingPackage: String? = null

    /** App holding focus. Every other tile is dimmed; null dims nothing. */
    var focusedPackage: String? = null

    private val fillerSpan get() = if (pinned.size < columns) columns - pinned.size else 0

    /** Adapter positions that make up the glass top row, filler included. */
    val topRowSize get() = pinned.size + if (fillerSpan > 0) 1 else 0

    fun submit(pinnedApps: List<AppEntry>, otherApps: List<AppEntry>) {
        pinned.clear(); pinned.addAll(pinnedApps)
        others.clear(); others.addAll(otherApps)
        notifyDataSetChanged()
    }

    private fun entryAt(position: Int): AppEntry? = when {
        position < pinned.size -> pinned[position]
        position >= topRowSize -> others.getOrNull(position - topRowSize)
        else -> null
    }

    fun positionOf(pkg: String): Int {
        val p = pinned.indexOfFirst { it.packageName == pkg }
        if (p >= 0) return p
        val o = others.indexOfFirst { it.packageName == pkg }
        return if (o >= 0) topRowSize + o else RecyclerView.NO_POSITION
    }

    fun isPinned(pkg: String) = pinned.any { it.packageName == pkg }

    fun allApps(): List<AppEntry> = (pinned + others).sortedBy { it.label.lowercase() }

    fun spanSize(position: Int) = if (position == pinned.size && fillerSpan > 0) fillerSpan else 1

    /** Swaps a pinned app with its neighbour. False at either end of the row. */
    fun shiftPinned(pkg: String, delta: Int): Boolean {
        val from = pinned.indexOfFirst { it.packageName == pkg }
        val to = from + delta
        if (from < 0 || to !in pinned.indices) return false
        Collections.swap(pinned, from, to)
        notifyItemMoved(from, to)
        return true
    }

    /** False when the top row is already full. */
    fun pin(pkg: String): Boolean {
        val idx = others.indexOfFirst { it.packageName == pkg }
        if (pinned.size >= columns || idx < 0) return false
        pinned.add(others.removeAt(idx))
        notifyDataSetChanged()
        return true
    }

    fun unpin(pkg: String) {
        val idx = pinned.indexOfFirst { it.packageName == pkg }
        if (idx < 0) return
        val entry = pinned.removeAt(idx)
        // Back into the alphabetical grid at its natural place.
        val at = others.indexOfFirst { it.label.lowercase() > entry.label.lowercase() }
        others.add(if (at < 0) others.size else at, entry)
        notifyDataSetChanged()
    }

    override fun getItemCount() = topRowSize + others.size

    override fun getItemViewType(position: Int) = if (entryAt(position) == null) FILLER else APP

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == APP) {
            AppVH(inflater.inflate(R.layout.item_app, parent, false))
        } else {
            FillerVH(inflater.inflate(R.layout.item_filler, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is AppVH -> entryAt(position)?.let(holder::bind)
            is FillerVH -> holder.bind(showHint = pinned.isEmpty())
        }
    }

    /** Re-applies dim, blur and wiggle to on-screen tiles. Rebinding would reset animations. */
    fun refreshTileStates(rv: RecyclerView) {
        for (i in 0 until rv.childCount) {
            (rv.getChildViewHolder(rv.getChildAt(i)) as? AppVH)?.applyState()
        }
    }

    inner class AppVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tile: View = itemView.findViewById(R.id.tile)
        private val icon: ImageView = itemView.findViewById(R.id.appIcon)
        private val gloss: View = itemView.findViewById(R.id.gloss)
        private val label: TextView = itemView.findViewById(R.id.appLabel)
        private val density = itemView.resources.displayMetrics.density
        private val iconPad = (ICON_PAD_DP * density).toInt()
        private val labelRoom = (LABEL_ROOM_DP * density).toInt()
        private var entry: AppEntry? = null
        private var wiggle: ObjectAnimator? = null
        /** Last applied dim state; null until first bind. Lets refreshes skip unchanged tiles. */
        private var dimmed: Boolean? = null

        // Springs rather than timed tweens: when focus moves on mid-animation the tile
        // carries its current velocity into the new target instead of restarting.
        private val springX = spring(DynamicAnimation.SCALE_X, 1f)
        private val springY = spring(DynamicAnimation.SCALE_Y, 1f)
        private val springLift = spring(DynamicAnimation.TRANSLATION_Y, 0f)

        private fun spring(property: DynamicAnimation.ViewProperty, rest: Float) =
            SpringAnimation(itemView, property).setSpring(
                SpringForce(rest).setStiffness(STIFFNESS).setDampingRatio(DAMPING)
            )

        init {
            tile.clipToOutline = true
            tile.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, TILE_RADIUS_DP * density)
                }
            }
            tile.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> view.invalidateOutline() }
            itemView.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) FOCUSED_SCALE else 1f
                springX.animateToFinalPosition(scale)
                springY.animateToFinalPosition(scale)
                springLift.animateToFinalPosition(if (hasFocus) -LIFT_DP * density else 0f)
                gloss.animate().alpha(if (hasFocus) 1f else 0f)
                    .setDuration(GLOSS_MS).setInterpolator(EASE_OUT).start()
                // Lifts the focused tile above neighbours it now overlaps.
                v.z = if (hasFocus) FOCUSED_Z else 0f
                if (hasFocus) entry?.let { listener.onAppFocused(it, isPinned(it.packageName)) }
            }
            itemView.setOnClickListener { v -> entry?.let { listener.onAppSelected(it, v) } }
            itemView.setOnLongClickListener {
                entry?.let(listener::onAppLongPressed)
                true
            }
        }

        fun bind(e: AppEntry) {
            entry = e
            // The wide TV banner fills the tile edge to edge, like tvOS app art.
            // Apps without one get their square icon centred on the dark tile.
            val banner = e.banner
            val res = itemView.resources
            if (banner != null) {
                icon.setImageDrawable(banner.fresh(res))
                icon.scaleType = ImageView.ScaleType.CENTER_CROP
                icon.setPadding(0, 0, 0, 0)
                label.visibility = View.GONE
            } else {
                icon.setImageDrawable(e.icon.fresh(res))
                icon.scaleType = ImageView.ScaleType.FIT_CENTER
                // Bottom padding leaves room for the name under the icon.
                icon.setPadding(iconPad, iconPad / 2, iconPad, labelRoom)
                label.text = e.label
                label.visibility = View.VISIBLE
            }
            itemView.contentDescription = e.label
            applyState()
        }

        fun applyState() {
            val e = entry ?: return
            val dim = focusedPackage != null && focusedPackage != e.packageName
            // Only the tiles whose state actually flipped do any work. Re-setting a
            // RenderEffect on every tile per D-pad press was a visible stutter.
            if (dim != dimmed) {
                dimmed = dim
                tile.animate().alpha(if (dim) DIM_ALPHA else 1f)
                    .setDuration(DIM_MS).setInterpolator(EASE_OUT).start()
                // Real blur needs API 31; older sets (the API 26 W800D) get the dim alone.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    tile.setRenderEffect(if (dim) Blur.effect else null)
                }
            }
            setWiggling(movingPackage == e.packageName)
        }

        private fun setWiggling(on: Boolean) {
            if (on == (wiggle != null)) return
            if (on) {
                wiggle = ObjectAnimator.ofFloat(tile, View.ROTATION, -WIGGLE_DEG, WIGGLE_DEG).apply {
                    duration = WIGGLE_MS
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            } else {
                wiggle?.cancel()
                wiggle = null
                tile.rotation = 0f
            }
        }
    }

    class FillerVH(view: View) : RecyclerView.ViewHolder(view) {
        private val hint: TextView = view.findViewById(R.id.fillerHint)

        fun bind(showHint: Boolean) {
            hint.visibility = if (showHint) View.VISIBLE else View.INVISIBLE
        }
    }

    /** One shared effect: RenderEffect is immutable, so there's no need for one per tile. */
    @RequiresApi(Build.VERSION_CODES.S)
    private object Blur {
        val effect: RenderEffect = RenderEffect.createBlurEffect(BLUR_PX, BLUR_PX, Shader.TileMode.CLAMP)
    }

    private companion object {
        const val APP = 0
        const val FILLER = 1
        const val FOCUSED_SCALE = 1.15f
        const val FOCUSED_Z = 24f
        const val LIFT_DP = 4f
        // Roughly a 0.35 s response with a small settle, close to tvOS focus feel.
        const val STIFFNESS = 320f
        const val DAMPING = 0.72f
        const val GLOSS_MS = 220L
        const val DIM_MS = 260L
        val EASE_OUT = PathInterpolator(0.2f, 0f, 0f, 1f)
        const val DIM_ALPHA = 0.88f
        const val BLUR_PX = 1.2f
        const val ICON_PAD_DP = 12
        const val LABEL_ROOM_DP = 24
        const val TILE_RADIUS_DP = 18f
        const val WIGGLE_DEG = 2.5f
        const val WIGGLE_MS = 120L
    }
}
