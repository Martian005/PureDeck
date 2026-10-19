package com.puredeck.launcher

import android.graphics.drawable.Drawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** What a settings page is made of. Rows are rebuilt on every visit so values are live. */
sealed interface Row

class Header(val text: CharSequence) : Row

/** A row. With no [onClick] it is an info row: still focusable, so D-pad can scroll to it. */
class Item(
    val label: CharSequence,
    val icon: Drawable?,
    val subtitle: CharSequence? = null,
    val value: CharSequence? = null,
    val chevron: Boolean = false,
    /** False for app icons, which must keep their own colours. */
    val tintIcon: Boolean = true,
    val onClick: (() -> Unit)? = null
) : Row

/** Adjusted with ◀ ▶ while focused. */
class Slider(
    val label: CharSequence,
    val icon: Drawable?,
    val max: Int,
    val get: () -> Int,
    val set: (Int) -> Unit
) : Row

/** [title] null marks the root page, which shows the profile header instead. */
class Page(val title: CharSequence?, val rows: () -> List<Row>)

class RowRenderer(private val inflater: LayoutInflater) {

    fun render(list: LinearLayout, rows: List<Row>) {
        list.removeAllViews()
        rows.forEach { row ->
            list.addView(
                when (row) {
                    is Header -> header(list, row)
                    is Item -> item(list, row)
                    is Slider -> slider(list, row)
                }
            )
        }
    }

    private fun header(parent: ViewGroup, h: Header): View =
        (inflater.inflate(R.layout.item_quick_section, parent, false) as TextView).apply { text = h.text }

    private fun item(parent: ViewGroup, r: Item): View {
        val v = inflater.inflate(R.layout.item_quick, parent, false)
        v.findViewById<ImageView>(R.id.quickIcon).apply {
            if (r.icon == null) {
                visibility = View.GONE
            } else {
                setImageDrawable(r.icon)
                if (!r.tintIcon) imageTintList = null
            }
        }
        v.findViewById<TextView>(R.id.quickLabel).text = r.label
        v.findViewById<TextView>(R.id.quickSubtitle).showOrHide(r.subtitle)
        v.findViewById<TextView>(R.id.quickValue).showOrHide(r.value)
        v.findViewById<View>(R.id.quickChevron).visibility = if (r.chevron) View.VISIBLE else View.GONE
        r.onClick?.let { action -> v.setOnClickListener { action() } }
        return v
    }

    private fun slider(parent: ViewGroup, s: Slider): View {
        val v = inflater.inflate(R.layout.item_slider, parent, false)
        v.findViewById<ImageView>(R.id.sliderIcon).setImageDrawable(s.icon)
        v.findViewById<TextView>(R.id.sliderLabel).text = s.label
        val bar = v.findViewById<ProgressBar>(R.id.sliderBar).apply { max = s.max }
        val value = v.findViewById<TextView>(R.id.sliderValue)

        fun show(animate: Boolean) {
            val current = s.get()
            bar.setProgress(current, animate)
            value.text = v.context.getString(R.string.percent, current * 100 / s.max.coerceAtLeast(1))
        }
        show(animate = false)

        // Left/Right adjust instead of moving focus out of the row.
        v.setOnKeyListener { _, keyCode, event ->
            val delta = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> return@setOnKeyListener false
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                s.set((s.get() + delta).coerceIn(0, s.max))
                show(animate = true)
            }
            true
        }
        return v
    }

    private fun TextView.showOrHide(content: CharSequence?) {
        text = content
        visibility = if (content == null) View.GONE else View.VISIBLE
    }
}
