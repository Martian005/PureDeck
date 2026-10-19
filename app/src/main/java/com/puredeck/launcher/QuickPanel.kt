package com.puredeck.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.core.content.ContextCompat

/** The floating glass card behind the profile pill. */
class QuickPanel(
    private val activity: Activity,
    private val root: View,
    private val anchor: View,
    apps: SettingsPages.AppActions,
    private val onOpened: () -> Unit,
    private val onClosed: () -> Unit
) : SettingsPages.Navigator {

    private val panel: ViewGroup = root.findViewById(R.id.panel)
    private val scrim: View = root.findViewById(R.id.panelScrim)
    private val list: LinearLayout = root.findViewById(R.id.quickList)
    private val avatar: ImageView = root.findViewById(R.id.panelAvatar)
    private val title: TextView = root.findViewById(R.id.panelName)
    private val clock: TextView = root.findViewById(R.id.panelClock)

    private val pages = SettingsPages(activity, this, apps)
    private val renderer = RowRenderer(LayoutInflater.from(activity))
    private val density = activity.resources.displayMetrics.density

    private class Entry(val page: Page, var focusIndex: Int = -1)
    private val stack = ArrayList<Entry>()

    private val popX = pop(DynamicAnimation.SCALE_X)
    private val popY = pop(DynamicAnimation.SCALE_Y)
    private var listeningForVolume = false
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isOpen || intent.action != VOLUME_CHANGED_ACTION) return
            if (intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC) == AudioManager.STREAM_MUSIC) {
                refresh()
            }
        }
    }

    var isOpen = false
        private set

    fun setTime(text: CharSequence) {
        clock.text = text
    }

    /** [atSettings]: start on the Settings page (Back still leads up to the root card). */
    fun open(atSettings: Boolean = false) {
        if (isOpen) return
        isOpen = true
        startListeningForVolume()
        stack.clear()
        stack += Entry(pages.root())
        if (atSettings) stack += Entry(pages.home())
        showHeader(stack.last().page)
        renderer.render(list, stack.last().page.rows())
        root.visibility = View.VISIBLE

        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(SCRIM_MS).setInterpolator(EASE).start()
        // The card covers the pill, so it takes the pill's place.
        anchor.animate().alpha(0f).setDuration(FADE_MS).start()

        // Grow from the top-right corner, where the pill is.
        panel.pivotX = panel.width.toFloat()
        panel.pivotY = 0f
        popX.cancel()
        popY.cancel()
        panel.scaleX = START_SCALE
        panel.scaleY = START_SCALE
        panel.alpha = 0f
        panel.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(EASE).withLayer().start()
        popX.animateToFinalPosition(1f)
        popY.animateToFinalPosition(1f)

        rippleRowsIn()
        focusAt(-1)
        onOpened()
    }

    /** Back: one page up, or close from the root page. */
    fun back() {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            navigate(forward = false)
        } else {
            close()
        }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        stopListeningForVolume()
        scrim.animate().alpha(0f).setDuration(CLOSE_MS).setInterpolator(EASE).start()
        anchor.animate().alpha(1f).setDuration(CLOSE_MS).start()
        popX.animateToFinalPosition(END_SCALE)
        popY.animateToFinalPosition(END_SCALE)
        panel.animate().alpha(0f).setDuration(CLOSE_MS).setInterpolator(EASE).withLayer()
            .withEndAction { if (!isOpen) root.visibility = View.INVISIBLE }
            .start()
        onClosed()
    }

    override fun push(page: Page) {
        stack.last().focusIndex = list.indexOfChild(list.focusedChild)
        stack += Entry(page)
        navigate(forward = true)
    }

    override fun refresh() {
        val index = list.indexOfChild(list.focusedChild)
        renderer.render(list, stack.last().page.rows())
        focusAt(index)
    }

    override fun launch(intent: Intent) {
        close()
        try {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.qs_unavailable, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            // Somnambulator can resolve yet be unexported on some builds.
            Toast.makeText(activity, R.string.qs_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /** Old page slides out one way, new page slides in from the other, card resizes between. */
    private fun navigate(forward: Boolean) {
        val entry = stack.last()
        val dir = if (forward) 1f else -1f
        val shift = SLIDE_DP * density
        list.animate().cancel()
        list.animate().alpha(0f).translationX(-dir * shift)
            .setDuration(OUT_MS).setInterpolator(EASE).withLayer()
            .withEndAction {
                TransitionManager.beginDelayedTransition(
                    root as ViewGroup,
                    ChangeBounds().addTarget(panel).setDuration(RESIZE_MS).setInterpolator(EASE)
                )
                showHeader(entry.page)
                renderer.render(list, entry.page.rows())
                list.translationX = dir * shift
                list.animate().alpha(1f).translationX(0f)
                    .setDuration(IN_MS).setInterpolator(EASE).withLayer().start()
                focusAt(if (forward) -1 else entry.focusIndex)
            }
            .start()
    }

    private fun showHeader(page: Page) {
        if (page.title == null) {
            avatar.setImageResource(R.drawable.ic_qs_person)
            title.text = pages.deviceName
        } else {
            avatar.setImageResource(R.drawable.ic_qs_back)
            title.text = page.title
        }
    }

    private fun rippleRowsIn() {
        val shift = ROW_SHIFT_DP * density
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            row.alpha = 0f
            row.translationX = shift
            row.animate().alpha(1f).translationX(0f)
                .setStartDelay(ROW_DELAY_START + i * ROW_STAGGER)
                .setDuration(ROW_MS).setInterpolator(EASE).start()
        }
    }

    /** Focuses row [index] if it is focusable, otherwise the first focusable row. */
    private fun focusAt(index: Int) {
        val target = list.getChildAt(index)?.takeIf { it.isFocusable }
            ?: (0 until list.childCount).map(list::getChildAt).firstOrNull { it.isFocusable }
        target?.requestFocus()
    }

    /** Android does not provide a listener on AudioManager for stream-volume changes.
     *  SystemUI sends this protected broadcast whenever the TV remote changes volume. */
    private fun startListeningForVolume() {
        if (listeningForVolume) return
        ContextCompat.registerReceiver(
            activity,
            volumeReceiver,
            IntentFilter(VOLUME_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        listeningForVolume = true
    }

    private fun stopListeningForVolume() {
        if (!listeningForVolume) return
        activity.unregisterReceiver(volumeReceiver)
        listeningForVolume = false
    }

    private fun pop(property: DynamicAnimation.ViewProperty) =
        SpringAnimation(panel, property).setSpring(
            SpringForce(1f).setStiffness(STIFFNESS).setDampingRatio(DAMPING)
        )

    private companion object {
        const val START_SCALE = 0.86f
        const val END_SCALE = 0.94f
        const val STIFFNESS = 380f
        const val DAMPING = 0.78f
        const val SCRIM_MS = 260L
        const val FADE_MS = 180L
        const val CLOSE_MS = 170L
        const val OUT_MS = 120L
        const val IN_MS = 260L
        const val RESIZE_MS = 300L
        const val SLIDE_DP = 36f
        const val ROW_MS = 280L
        const val ROW_DELAY_START = 40L
        const val ROW_STAGGER = 28L
        const val ROW_SHIFT_DP = 18f
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
