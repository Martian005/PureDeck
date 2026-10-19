package com.puredeck.launcher

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** The full-screen backdrop behind the dock. */
class HeroController(
    private val upper: ImageView,
    private val lower: ImageView,
    private val tint: ImageView,
    private val dimmer: View,
    private val titleBlock: View,
    private val title: TextView,
    /** The show's own title logo; replaces [title] whenever one is available. */
    private val logoView: ImageView,
    private val subtitle: TextView,
    private val trending: TrendingSource,
    private val scope: CoroutineScope
) {
    private var job: Job? = null
    private var shownPackage: String? = null
    private var currentShow: Show? = null
    /** True while focus is down in the app grid, where the hero title must stay hidden. */
    private var dimmed = false
    /** True while the all-apps grid is focused and landscape photos replace the app artwork. */
    private var inScenery = false
    private val sceneryQueue = ArrayDeque<Int>()
    private var lastScenery = 0
    private val kenBurns = HashMap<View, ObjectAnimator>()

    fun show(entry: AppEntry) {
        if (entry.packageName == shownPackage) return
        inScenery = false
        shownPackage = entry.packageName
        job?.cancel()
        titleBlock.animate().cancel()
        titleBlock.animate().alpha(0f).setDuration(TITLE_HIDE_MS)
            .setInterpolator(EASE).withLayer().start()
        job = scope.launch {
            delay(FETCH_DEBOUNCE_MS)
            // Already ordered by TrendingSource: titles shown recently come last.
            val shows = trending.forPackage(entry.packageName, entry.label.toString())
            var i = 0
            var misses = 0
            var displayedArt = false
            while (isActive && shows.isNotEmpty() && misses < shows.size) {
                val show = shows[i % shows.size]
                // Not a child of this job: if the logo is slow we move on without it, and it
                // still finishes in the background so the next rotation finds it cached.
                val logoLookup = scope.async { trending.logo(show) }
                val art = trending.backdrop(show)
                if (art == null) {
                    i++
                    misses++
                    continue
                }
                val logo = withTimeoutOrNull(LOGO_WAIT_MS) { logoLookup.await() }
                if (logo == null) {
                    // Slow link (the 2016 TV): show the name now, and swap the logo in if it arrives
                    // while this title is still on screen.
                    scope.launch {
                        val late = logoLookup.await() ?: return@launch
                        if (currentShow == show && shownPackage == entry.packageName) {
                            setHeading(show.title, subtitleFor(show, entry), late)
                        }
                    }
                }
                currentShow = show
                misses = 0
                trending.markShown(entry.packageName, show)
                crossfade(BitmapDrawable(upper.resources, art))
                tint.setImageDrawable(null)
                setHeading(show.title, subtitleFor(show, entry), logo)
                fadeInTitleBlock()
                displayedArt = true
                delay(ROTATE_MS)
                i++
            }

            if (isActive && !displayedArt) {
                val branded = brandBackdrop(entry)
                val fallback = branded ?: entry.banner?.let(::softened)
                if (fallback != null) {
                    crossfade(fallback)
                    if (branded == null) wash(fallbackAccent(entry)) else tint.setImageDrawable(null)
                    setHeading(entry.label, null, null)
                    fadeInTitleBlock()
                }
            }
        }
    }

    private fun fadeInTitleBlock() {
        titleBlock.animate().cancel()
        titleBlock.animate().alpha(if (dimmed) 0f else 1f).setDuration(TITLE_SHOW_MS)
            .setInterpolator(EASE).withLayer().start()
    }

    private fun subtitleFor(show: Show, entry: AppEntry): String = upper.context.getString(
        if (show.tag == "trending") R.string.trending_on else R.string.recently_added_on, entry.label
    )

    /**
     * The all-apps grid isn't tied to one app's artwork, so it gets landscape photos instead,
     * changing every [SCENERY_MS]. They're bundled: instant, offline, no load on the slow TV.
     */
    fun showScenery() {
        if (inScenery) return
        inScenery = true
        // Forget the app so returning to the same dock tile shows its hero again.
        shownPackage = null
        currentShow = null
        job?.cancel()
        titleBlock.animate().cancel()
        titleBlock.animate().alpha(0f).setDuration(TITLE_HIDE_MS).setInterpolator(EASE).withLayer().start()
        tint.setImageDrawable(null)
        job = scope.launch {
            while (isActive) {
                crossfade(BitmapDrawable(upper.resources, loadScenery(nextScenery())))
                delay(SCENERY_MS)
            }
        }
    }

    /** Shuffled without repeats; a new cycle never opens on the photo that just closed the last one. */
    private fun nextScenery(): Int {
        if (sceneryQueue.isEmpty()) {
            val all = SCENERY.toMutableList().apply { shuffle() }
            if (all.first() == lastScenery) all.add(all.removeAt(0))
            sceneryQueue.addAll(all)
        }
        return sceneryQueue.removeFirst().also { lastScenery = it }
    }

    private suspend fun loadScenery(id: Int): Bitmap = withContext(Dispatchers.IO) {
        // RGB_565 halves memory, which matters on the 2 GB TV; photos hide the banding fine.
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        BitmapFactory.decodeResource(upper.resources, id, options)
    }

    /** Dims the hero when focus drops into the grid below the dock. */
    fun setDimmed(dim: Boolean) {
        dimmed = dim
        dimmer.animate().alpha(if (dim) DIM_ALPHA else 0f).setDuration(DIM_MS)
            .setInterpolator(EASE).withLayer().start()
        titleBlock.animate().alpha(if (dim || inScenery) 0f else 1f).setDuration(DIM_MS)
            .setInterpolator(EASE).withLayer().start()
    }

    private fun crossfade(d: Drawable?) {
        if (d == null) return
        upper.animate().cancel()
        lower.animate().cancel()
        val outgoing = when {
            upper.alpha >= lower.alpha && upper.alpha > 0.01f -> upper
            lower.alpha > 0.01f -> lower
            else -> null
        }
        val incoming = if (outgoing === upper) lower else upper
        incoming.animate().cancel()
        incoming.setImageDrawable(d)
        incoming.scaleX = 1f
        incoming.scaleY = 1f
        incoming.alpha = 0f
        startKenBurns(incoming)
        incoming.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(EASE).withLayer().start()
        outgoing?.animate()?.alpha(0f)?.setDuration(FADE_MS)?.setInterpolator(EASE)?.withLayer()?.start()
    }

    private fun startKenBurns(v: View) {
        kenBurns[v]?.cancel()
        kenBurns[v] = ObjectAnimator.ofPropertyValuesHolder(
            v,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, KEN_BURNS_SCALE),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, KEN_BURNS_SCALE)
        ).apply {
            duration = ROTATE_MS + FADE_MS
            interpolator = LinearInterpolator()
            start()
        }
    }

    /** Slides the old heading out and the new one in: the show's logo if there is one, else its name. */
    private fun setHeading(main: CharSequence, sub: CharSequence?, logo: Logo?) {
        title.animate().cancel()
        logoView.animate().cancel()
        val outgoing: View = if (logoView.isVisible) logoView else title
        outgoing.animate().alpha(0f).translationX(-TEXT_SHIFT).setDuration(TEXT_OUT_MS)
            .setInterpolator(EASE).withLayer().withEndAction {
                subtitle.text = sub
                subtitle.visibility = if (sub == null) View.GONE else View.VISIBLE
                val incoming: View
                if (logo != null) {
                    logoView.setImageBitmap(logo.bitmap)
                    logoView.colorFilter = if (logo.needsLightening) LogoWhitenFilter else null
                    logoView.contentDescription = main
                    incoming = logoView
                    title.visibility = View.GONE
                } else {
                    title.text = main
                    incoming = title
                    logoView.visibility = View.GONE
                    // An app's own art carries the title; only the small line above is shown.
                    if (main.isEmpty()) title.visibility = View.GONE
                }
                if (main.isNotEmpty() || logo != null) incoming.visibility = View.VISIBLE
                incoming.alpha = 0f
                incoming.translationX = TEXT_SHIFT
                incoming.animate().alpha(1f).translationX(0f).setDuration(TEXT_IN_MS)
                    .setInterpolator(EASE).withLayer().start()
            }.start()
    }

    private fun brandBackdrop(entry: AppEntry): Drawable? {
        val identity = (entry.label.toString() + " " + entry.packageName).lowercase(Locale.ROOT)
        val colors = when {
            "youtube music" in identity -> intArrayOf(Color.rgb(35, 0, 8), Color.rgb(190, 0, 35), Color.rgb(255, 30, 70), Color.rgb(45, 0, 15))
            "youtube" in identity -> intArrayOf(Color.rgb(20, 0, 0), Color.rgb(190, 0, 0), Color.rgb(255, 35, 35), Color.rgb(35, 0, 0))
            "google" in identity && "google tv" !in identity -> intArrayOf(
                Color.rgb(12, 30, 70), Color.rgb(25, 95, 190), Color.rgb(70, 55, 145), Color.rgb(8, 15, 35)
            )
            else -> return null
        }
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
    }

    private fun fallbackAccent(entry: AppEntry): Int {
        val identity = (entry.label.toString() + " " + entry.packageName).lowercase(Locale.ROOT)
        return when {
            "youtube music" in identity -> Color.rgb(255, 0, 40)
            "youtube" in identity -> Color.rgb(255, 0, 0)
            "hotstar" in identity -> Color.rgb(35, 105, 235)
            "prime video" in identity || "amazonvideo" in identity -> Color.rgb(0, 105, 210)
            "crunchyroll" in identity -> Color.rgb(245, 95, 0)
            else -> entry.accent
        }
    }

    /** Accent wash for when there is no artwork at all, otherwise the screen would be black. */
    private fun wash(accent: Int) {
        tint.setImageDrawable(
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    ColorUtils.setAlphaComponent(accent, 145),
                    ColorUtils.setAlphaComponent(accent, 105),
                    ColorUtils.setAlphaComponent(accent, 58),
                    Color.TRANSPARENT
                )
            )
        )
    }

    /** Banners are 320x180 and look awful at 1080p. */
    private fun softened(d: Drawable): Drawable {
        val bmp = Bitmap.createBitmap(BLUR_W, BLUR_H, Bitmap.Config.ARGB_8888)
        // Draw a copy: this banner instance is also on screen in its tile, and
        // setBounds on the shared one would shrink it to 16x9 px there.
        val copy = d.constantState?.newDrawable(upper.resources)?.mutate() ?: return d
        copy.setBounds(0, 0, BLUR_W, BLUR_H)
        copy.draw(Canvas(bmp))
        return BitmapDrawable(upper.resources, bmp).apply { isFilterBitmap = true }
    }

    private companion object {
        const val FETCH_DEBOUNCE_MS = 200L
        const val LOGO_WAIT_MS = 2_500L
        const val ROTATE_MS = 5_000L
        const val FADE_MS = 520L
        const val TITLE_HIDE_MS = 120L
        const val TITLE_SHOW_MS = 360L
        const val TEXT_OUT_MS = 160L
        const val TEXT_IN_MS = 320L
        val EASE = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        const val TEXT_SHIFT = 24f
        const val KEN_BURNS_SCALE = 1.08f
        // Light: tiles are glass over a photo, and the bottom scrim already darkens under them.
        const val DIM_ALPHA = 0.28f
        const val SCENERY_MS = 14_000L
        val SCENERY = intArrayOf(
            R.drawable.scenery_1, R.drawable.scenery_2, R.drawable.scenery_3, R.drawable.scenery_4,
            R.drawable.scenery_5, R.drawable.scenery_6, R.drawable.scenery_7, R.drawable.scenery_8,
            R.drawable.scenery_9, R.drawable.scenery_10
        )
        const val DIM_MS = 300L
        const val BLUR_W = 16
        const val BLUR_H = 9
    }
}
