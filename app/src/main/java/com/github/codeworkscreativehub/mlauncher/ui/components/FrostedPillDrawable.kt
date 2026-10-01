package com.github.codeworkscreativehub.mlauncher.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView

/**
 * Background for the drawer's search pill: the part of an extra-blurred backdrop that sits
 * behind the pill, plus a light tint, in a fully rounded shape. Flat: no border, no highlight.
 *
 * The backdrop never moves while the pill does (drawer slide, keyboard), so the pill's position
 * relative to [backdrop] is recomputed on every draw; call [View.invalidate] on the pill when it
 * moves without relayout (e.g. a translation change).
 */
class FrostedPillDrawable(
    private val pill: View,
    private val backdrop: ImageView,
    private val tintWithImage: Int,
    private val tintWithoutImage: Int,
) : Drawable() {

    private var frosted: Bitmap? = null
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaderMatrix = Matrix()
    private val rect = RectF()
    private val pillLoc = IntArray(2)
    private val backdropLoc = IntArray(2)

    /** Extra-blurred bitmap with the same size and mapping as the backdrop's bitmap, or null. */
    fun setFrosted(bitmap: Bitmap?) {
        frosted = bitmap
        imagePaint.shader = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        val radius = rect.height() / 2f
        val shader = imagePaint.shader
        if (frosted != null && shader != null && backdrop.isLaidOut) {
            // Map backdrop-bitmap pixels into the pill's coordinates:
            // bitmap -> backdrop view (its image matrix) -> window -> pill
            pill.getLocationInWindow(pillLoc)
            backdrop.getLocationInWindow(backdropLoc)
            shaderMatrix.set(backdrop.imageMatrix)
            shaderMatrix.postTranslate(
                (backdropLoc[0] - pillLoc[0]).toFloat(),
                (backdropLoc[1] - pillLoc[1]).toFloat()
            )
            shader.setLocalMatrix(shaderMatrix)
            canvas.drawRoundRect(rect, radius, radius, imagePaint)
            tintPaint.color = tintWithImage
        } else {
            tintPaint.color = tintWithoutImage
        }
        canvas.drawRoundRect(rect, radius, radius, tintPaint)
    }

    override fun setAlpha(alpha: Int) {
        imagePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        imagePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
