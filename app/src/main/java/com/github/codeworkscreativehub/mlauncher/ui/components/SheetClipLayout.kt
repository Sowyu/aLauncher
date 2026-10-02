package com.github.codeworkscreativehub.mlauncher.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Draws its children only inside a sheet shape: everything below [sheetTop], with the top
 * corners rounded by [cornerRadius]. Children stay put in screen coordinates (the blurred
 * wallpaper must line up with the real one); only the visible window moves.
 */
class SheetClipLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val path = Path()
    private val rect = RectF()
    private var top = 0f
    private var radius = 0f

    fun setSheet(sheetTop: Float, cornerRadius: Float) {
        if (sheetTop == top && cornerRadius == radius) return
        top = sheetTop
        radius = cornerRadius
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (top <= 0f && radius <= 0f) {
            super.dispatchDraw(canvas)
            return
        }
        path.reset()
        rect.set(0f, top, width.toFloat(), height.toFloat() + radius)
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(path)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }
}
