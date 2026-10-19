package com.puredeck.launcher

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * Wraps its content until it would run off screen, then scrolls. ScrollView has no
 * maxHeight attribute, and long pages (the app list) must not outgrow the TV.
 */
class MaxHeightScrollView(context: Context, attrs: AttributeSet?) : ScrollView(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = (resources.displayMetrics.heightPixels * MAX_FRACTION).toInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
    }

    private companion object {
        const val MAX_FRACTION = 0.74f
    }
}
