package com.puredeck.launcher

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.animation.PathInterpolator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Default RecyclerView scrolling only nudges a focused tile until its unscaled edge is on screen, so a scaled-up focused tile in the bottom row ends up cut off. */
class FocusScrollLayoutManager(
    context: Context,
    columns: Int,
    private val isTopRow: (position: Int) -> Boolean
) : GridLayoutManager(context, columns) {

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean
    ): Boolean {
        val dy = if (isTopRow(getPosition(child))) {
            -parent.computeVerticalScrollOffset()
        } else {
            (child.top + child.height / 2) - parent.height / 2
        }
        if (dy == 0) return false
        // RecyclerView's default smooth scroll is short and linear-ish; a longer
        // ease-out reads as the grid gliding rather than jumping.
        if (immediate) parent.scrollBy(0, dy) else parent.smoothScrollBy(0, dy, GLIDE, GLIDE_MS)
        return true
    }

    private companion object {
        val GLIDE = PathInterpolator(0.2f, 0f, 0f, 1f)
        const val GLIDE_MS = 450
    }
}
