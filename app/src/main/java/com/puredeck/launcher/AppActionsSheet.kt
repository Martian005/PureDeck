package com.puredeck.launcher

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/** Small glass card of actions for one app: open, pin, info, uninstall. */
class AppActionsSheet(
    private val activity: Activity,
    private val root: View,
    private val onOpened: () -> Unit,
    private val onClosed: () -> Unit
) {
    private val card: View = root.findViewById(R.id.actionsCard)
    private val scrim: View = root.findViewById(R.id.actionsScrim)
    private val icon: ImageView = root.findViewById(R.id.actionsIcon)
    private val name: TextView = root.findViewById(R.id.actionsName)
    private val list: LinearLayout = root.findViewById(R.id.actionsList)
    private val renderer = RowRenderer(LayoutInflater.from(activity))
    private val popX = pop(DynamicAnimation.SCALE_X)
    private val popY = pop(DynamicAnimation.SCALE_Y)

    var isOpen = false
        private set

    fun open(entry: AppEntry, rows: List<Row>) {
        if (isOpen) return
        isOpen = true
        name.text = entry.label
        // The square icon, not the wide banner: a banner squeezed into this slot is unreadable.
        icon.setImageDrawable(entry.icon.fresh(activity.resources))
        renderer.render(list, rows)
        root.visibility = View.VISIBLE

        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(SCRIM_MS).setInterpolator(EASE).start()
        popX.cancel()
        popY.cancel()
        card.scaleX = START_SCALE
        card.scaleY = START_SCALE
        card.alpha = 0f
        card.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(EASE).withLayer().start()
        popX.animateToFinalPosition(1f)
        popY.animateToFinalPosition(1f)

        list.getChildAt(0)?.requestFocus()
        onOpened()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        scrim.animate().alpha(0f).setDuration(CLOSE_MS).setInterpolator(EASE).start()
        popX.animateToFinalPosition(END_SCALE)
        popY.animateToFinalPosition(END_SCALE)
        card.animate().alpha(0f).setDuration(CLOSE_MS).setInterpolator(EASE).withLayer()
            .withEndAction { if (!isOpen) root.visibility = View.INVISIBLE }
            .start()
        onClosed()
    }

    private fun pop(property: DynamicAnimation.ViewProperty) =
        SpringAnimation(card, property).setSpring(
            SpringForce(1f).setStiffness(STIFFNESS).setDampingRatio(DAMPING)
        )

    private companion object {
        const val START_SCALE = 0.9f
        const val END_SCALE = 0.95f
        const val STIFFNESS = 380f
        const val DAMPING = 0.78f
        const val SCRIM_MS = 220L
        const val FADE_MS = 180L
        const val CLOSE_MS = 160L
        val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
