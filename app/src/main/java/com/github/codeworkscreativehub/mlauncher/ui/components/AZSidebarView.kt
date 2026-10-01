package com.github.codeworkscreativehub.mlauncher.ui.components

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import com.github.codeworkscreativehub.mlauncher.helper.CustomFontView
import com.github.codeworkscreativehub.mlauncher.helper.FontManager
import com.github.codeworkscreativehub.mlauncher.helper.sp2px
import kotlin.math.exp

/**
 * A-Z fast scroller. While the finger is down, letters around it swell and lean toward
 * the screen centre (Gaussian falloff), and a bubble shows the current letter.
 * The wave and bubble draw outside this narrow view, so its parents must not clip children.
 */
class AZSidebarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), CustomFontView {

    var onTouchStart: (() -> Unit)? = null
    var onTouchEnd: (() -> Unit)? = null
    var onLetterSelected: ((String) -> Unit)? = null

    private val allLetters = listOf('★') + ('A'..'Z')
    private var letters: List<Char> = allLetters

    private val density = resources.displayMetrics.density
    private val baseTextSize = sp2px(resources, 20f)

    // Wave shape
    private val maxScale = 1.9f
    private val sigma = 1.3f                 // in letters; ~0 by 3-4 letters away
    private val maxShift = 26f * density     // horizontal lean toward the screen centre
    private val verticalPush = 0.5f          // in letter heights, keeps swollen letters apart

    // Bubble
    private val bubbleRadius = 32f * density
    private val bubbleOffset = 84f * density // sidebar centre to bubble centre

    private val accent = Color.parseColor("#E4B9E2")
    private val bubbleTextColor = Color.parseColor("#3B1830")
    private val idleColor = Color.GRAY

    private var baseTypeface: Typeface? = null
    private var boldTypeface: Typeface? = null

    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = baseTextSize
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.FILL
    }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bubbleTextColor
        textAlign = Paint.Align.CENTER
        textSize = sp2px(resources, 32f)
    }

    private var itemHeight = 0f

    /** Letter highlighted from list scroll position (idle state). */
    private var selectedLetter: Char? = null

    /** Letter under the finger; kept through the release animation so the bubble fades with it. */
    private var touchLetter: Char? = null
    private var touchY = 0f
    private var touching = false

    /** +1 when the sidebar sits on the left half of the screen (wave leans right), else -1. */
    private var direction = -1f

    /** 0 = idle, 1 = fully expanded wave + bubble. */
    private var progress = 0f
    private val animator = ValueAnimator().apply {
        duration = 180L
        interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f) // FastOutSlowIn
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setTypefaces(FontManager.getTypeface(context))
        FontManager.register(this)
    }

    val topBottomPaddingPx: Float
        get() = 180f * density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Spacing is sized for the full alphabet, so a shorter list keeps the same rhythm and stays centred
        val available = h - topBottomPaddingPx - (allLetters.size - 1) * density
        itemHeight = (available / allLetters.size).coerceAtLeast(0f)
    }

    private fun startY(): Float = (height - itemHeight * letters.size) / 2f

    private fun centerYOf(index: Int): Float = startY() + itemHeight * (index + 0.5f)

    private fun indexAt(y: Float): Int =
        ((y - startY()) / itemHeight).toInt().coerceIn(0, letters.size - 1)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (itemHeight <= 0f || letters.isEmpty()) return

        val p = progress
        val cx = width / 2f
        val fingerLetter = touchLetter
        val activeLetter = if (p > 0f && fingerLetter != null) fingerLetter else selectedLetter

        letters.forEachIndexed { index, letter ->
            val baseY = centerYOf(index)
            // Distance from the finger in letters; raw Y keeps the wave gliding between letters
            val d = if (p > 0f) (baseY - touchY) / itemHeight else 0f
            val w = if (p > 0f) exp(-(d * d) / (2f * sigma * sigma)) * p else 0f

            val scale = 1f + (maxScale - 1f) * w
            val x = cx + direction * maxShift * w
            val y = baseY + d * w * verticalPush * itemHeight

            val isActive = letter == activeLetter
            letterPaint.typeface = if (isActive) boldTypeface else baseTypeface
            letterPaint.textSize = baseTextSize * scale
            letterPaint.color = when {
                isActive && fingerLetter != null && p > 0f ->
                    ColorUtils.blendARGB(Color.WHITE, accent, p)
                isActive -> Color.WHITE
                else -> ColorUtils.blendARGB(idleColor, Color.WHITE, w)
            }

            val baseline = y - (letterPaint.descent() + letterPaint.ascent()) / 2f
            canvas.drawText(letter.toString(), x, baseline, letterPaint)
        }

        if (p > 0f && fingerLetter != null) drawBubble(canvas, fingerLetter, cx, p)
    }

    private fun drawBubble(canvas: Canvas, letter: Char, cx: Float, p: Float) {
        val bx = cx + direction * bubbleOffset
        val by = touchY.coerceIn(bubbleRadius, (height - bubbleRadius).coerceAtLeast(bubbleRadius))
        val alpha = (255 * p).toInt().coerceIn(0, 255)

        canvas.save()
        val s = 0.6f + 0.4f * p
        canvas.scale(s, s, bx, by)

        bubblePaint.alpha = alpha
        canvas.drawCircle(bx, by, bubbleRadius, bubblePaint)

        bubbleTextPaint.alpha = alpha
        val baseline = by - (bubbleTextPaint.descent() + bubbleTextPaint.ascent()) / 2f
        canvas.drawText(letter.toString(), bx, baseline, bubbleTextPaint)
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (itemHeight <= 0f || letters.isEmpty()) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                direction = computeDirection()
                touching = true
                touchLetter = null // a fresh press always fires, even on the last letter
                touchY = event.y
                onTouchStart?.invoke()
                selectFromTouch(letters[indexAt(event.y)])
                animateTo(1f)
            }

            MotionEvent.ACTION_MOVE -> {
                touchY = event.y
                selectFromTouch(letters[indexAt(event.y)])
                invalidate()
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                touching = false
                // Leave the last jumped-to letter highlighted once the wave settles
                touchLetter?.let { selectedLetter = it }
                animateTo(0f)
                onTouchEnd?.invoke()
            }
        }
        return true
    }

    private fun computeDirection(): Float {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val screenWidth = rootView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        return if (loc[0] + width / 2f < screenWidth / 2f) 1f else -1f
    }

    private fun animateTo(target: Float) {
        animator.cancel()
        animator.setFloatValues(progress, target)
        animator.start()
    }

    private fun selectFromTouch(letter: Char) {
        if (letter == touchLetter) return
        touchLetter = letter
        performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
            else HapticFeedbackConstants.CLOCK_TICK
        )
        val text = letter.toString()
        onLetterSelected?.invoke(text)
        announceLetterForAccessibility(text)
        invalidate()
    }

    /** Highlight from list scrolling. Ignored while the finger is down so the jump can't fight it. */
    fun setSelectedLetter(letter: String) {
        if (touching) return
        val c = letters.firstOrNull { it.toString() == letter } ?: return
        if (c != selectedLetter) {
            selectedLetter = c
            invalidate()
        }
    }

    /**
     * Update sidebar letters based on available app sections.
     *
     * Example input: setOf("★", "A", "C", "D", "M")
     */
    fun setAvailableLetters(available: Set<String>) {
        letters = allLetters.filter { it.toString() in available }
        if (selectedLetter !in letters) selectedLetter = null
        if (!touching) touchLetter = null
        invalidate()
    }

    /** Called by FontManager to update font */
    override fun applyFont(typeface: Typeface?) {
        setTypefaces(typeface)
        invalidate()
    }

    private fun setTypefaces(typeface: Typeface?) {
        val base = typeface ?: Typeface.DEFAULT
        baseTypeface = base
        // Picks the real 600/700 face from the bundled family; synthesises for single-file fonts
        boldTypeface = Typeface.create(base, 600, false)
        bubbleTextPaint.typeface = boldTypeface
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        progress = 0f
        touching = false
        super.onDetachedFromWindow()
    }

    private fun announceLetterForAccessibility(text: String) {
        contentDescription = text
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
    }
}
