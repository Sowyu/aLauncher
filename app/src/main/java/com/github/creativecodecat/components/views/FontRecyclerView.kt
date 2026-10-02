package com.github.creativecodecat.components.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.codeworkscreativehub.common.AppLogger
import com.github.codeworkscreativehub.mlauncher.helper.CustomFontView
import com.github.codeworkscreativehub.mlauncher.helper.FontManager

class FontRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RecyclerView(context, attrs), CustomFontView {

    init {
        try {
            FontManager.register(this)
            applyFont(FontManager.getTypeface(context)) // Apply initial font
        } catch (e: Exception) {
            AppLogger.e("FontRecyclerView", "Initialization failed", e)
        }
    }

    /**
     * Optional transparency fade at the edges of the *content area* (inside the padding), so rows
     * dissolve where they meet the status bar and a bottom overlay instead of at the view edge.
     * The built-in fading edge sits at the view bounds, which is wrong with clipToPadding=false.
     * 0 = off.
     */
    var edgeFadeLength: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    /** Where the top fade sits (px from the top). -1 = at paddingTop. Lets a collapsing header move it. */
    var fadeTopAt: Int = -1
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private var topShader: LinearGradient? = null
    private var bottomShader: LinearGradient? = null
    private var shaderKey = 0L

    override fun draw(canvas: Canvas) {
        val fade = edgeFadeLength
        if (fade <= 0 || width == 0 || height == 0) {
            super.draw(canvas)
            return
        }
        val w = width.toFloat()
        val topPx = if (fadeTopAt >= 0) fadeTopAt else paddingTop
        val top = topPx.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        val key = (topPx.toLong() shl 40) or (paddingBottom.toLong() shl 20) or fade.toLong()
        if (key != shaderKey) {
            shaderKey = key
            // Erase nothing at the content edge, everything one fade length past it
            topShader = LinearGradient(0f, top, 0f, top - fade, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
            bottomShader = LinearGradient(0f, bottom, 0f, bottom + fade, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        }

        val save = canvas.saveLayer(0f, 0f, w, height.toFloat(), null)
        super.draw(canvas)
        if (top > 0f) {
            fadePaint.shader = topShader
            canvas.drawRect(0f, 0f, w, top, fadePaint)
        }
        if (bottom < height) {
            fadePaint.shader = bottomShader
            canvas.drawRect(0f, bottom, w, height.toFloat(), fadePaint)
        }
        canvas.restoreToCount(save)
    }

    override fun applyFont(typeface: Typeface?) {
        try {
            // Propagate font to all visible child TextViews
            for (i in 0 until childCount) {
                val holder = getChildAt(i)
                applyFontRecursively(holder, typeface)
            }
        } catch (e: Exception) {
            AppLogger.e("FontRecyclerView", "Font application failed", e)
        }
    }

    private fun applyFontRecursively(view: View?, typeface: Typeface?) {
        if (view == null || typeface == null) return

        when (view) {
            is android.widget.TextView -> {
                // force override material theming
                view.setTypeface(typeface, Typeface.NORMAL)
            }

            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    applyFontRecursively(view.getChildAt(i), typeface)
                }
            }
        }
    }

}