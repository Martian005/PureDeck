package com.puredeck.launcher

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/** Paints the frosted panel behind the top row only. */
class DockDecoration(
    private val glass: Drawable,
    private val pad: Int,
    /** Extra space above and below each grid row (half each side). */
    private val rowGap: Int,
    /** Extra space between the glass row and the first grid row. */
    private val dockGap: Int,
    private val columns: Int,
    private val topRowSize: () -> Int
) : RecyclerView.ItemDecoration() {

    // Grid rows get room for the 1.15x focus scale; the glass row keeps its tight fit.
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val pos = parent.getChildAdapterPosition(view)
        val rowSize = topRowSize()
        if (pos == RecyclerView.NO_POSITION || pos < rowSize) return
        outRect.top = rowGap / 2 + if (pos < rowSize + columns) dockGap else 0
        outRect.bottom = rowGap / 2
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val rowSize = topRowSize()
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION || pos >= rowSize) continue
            left = minOf(left, child.left)
            top = minOf(top, child.top)
            right = maxOf(right, child.right)
            bottom = maxOf(bottom, child.bottom)
        }

        // Top row scrolled off screen: nothing to draw.
        if (left > right) return
        glass.setBounds(left - pad, top - pad, right + pad, bottom + pad)
        glass.draw(c)
    }
}
