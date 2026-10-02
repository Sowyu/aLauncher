package com.github.creativecodecat.components.views

import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.TextClock
import com.github.codeworkscreativehub.mlauncher.helper.CustomFontView
import com.github.codeworkscreativehub.mlauncher.helper.FontManager
import kotlin.math.ceil

/**
 * TextClock in the launcher font. Stays on one line: when the text at the size set in code is
 * wider than the room it gets, it draws smaller until it fits, and grows back when it fits again.
 */
class FontTextClock @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : TextClock(context, attrs), CustomFontView {

    /** Size asked for in px; 0 until set in code, then the XML size is used. */
    private var requestedPx = 0f
    private var fitting = false

    init {
        FontManager.register(this)
    }

    override fun applyFont(typeface: Typeface?) {
        this.typeface = typeface
    }

    override fun setTextSize(unit: Int, size: Float) {
        super.setTextSize(unit, size)
        if (!fitting) requestedPx = textSize
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (requestedPx == 0f) requestedPx = textSize
        val room = MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight
        val target = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED || room <= 0 || text.isNullOrEmpty()) {
            requestedPx
        } else {
            fitSize(room)
        }
        if (target != textSize) {
            fitting = true
            super.setTextSize(TypedValue.COMPLEX_UNIT_PX, target)
            fitting = false
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Largest size up to [requestedPx] whose text is no wider than [room] px. */
    private fun fitSize(room: Int): Float {
        val probe = TextPaint(paint)
        fun widthAt(px: Float): Float {
            probe.textSize = px
            return ceil(Layout.getDesiredWidth(text, probe))
        }
        val full = widthAt(requestedPx)
        if (full <= room) return requestedPx
        // Width scales almost linearly with size; step down a pixel if hinting rounds it over
        var size = (requestedPx * room / full).toInt().toFloat()
        while (size > 1f && widthAt(size) > room) size -= 1f
        return size
    }
}
