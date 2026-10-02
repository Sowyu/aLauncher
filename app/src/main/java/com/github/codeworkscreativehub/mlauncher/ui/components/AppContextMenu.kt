package com.github.codeworkscreativehub.mlauncher.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.core.content.ContextCompat
import com.github.codeworkscreativehub.mlauncher.R
import kotlin.math.roundToInt

/**
 * Long-press menu for an app row: a frosted, fully rounded pill with the actions side by side,
 * floating just above the row (below it when there is no room).
 *
 * It lives in the drawer's own view tree ([Host.container]) so it is drawn above the list and
 * gets touches first, and it samples the drawer's blurred backdrop the same way the search pill
 * does. A light scrim with a rounded cut-out keeps the pressed row visible and highlighted.
 * Tap outside, back, the row leaving the window, or a config change dismisses it.
 */
class AppContextMenu(
    private val host: Host,
    private val anchor: View,
    private val labelGravity: Int,
    private val actions: List<Action>,
    private val onDismissed: () -> Unit,
) {
    /** Where the menu draws: a full-size container over the list, plus the backdrop to frost. */
    class Host(
        val container: ViewGroup,
        val backdrop: ImageView,
        val frosted: Bitmap?,
        val backDispatcher: OnBackPressedDispatcher,
    )

    class Action(
        val iconRes: Int,
        val label: String,
        val destructive: Boolean = false,
        /** Shown dimmed; still clickable so the action can explain why it can't run. */
        val dimmed: Boolean = false,
        val onClick: () -> Unit,
    )

    private val context: Context = host.container.context
    private val overlay = Overlay(context)
    private val pill = LinearLayout(context)
    private val labels = mutableListOf<TextView>()
    private var dismissed = false

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = dismiss()
    }

    private val detachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) = dismiss(animate = false)
    }

    init {
        val density = context.resources.displayMetrics.density
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.isClickable = true // taps between actions don't fall through to the scrim
        val pad = (8 * density).roundToInt()
        pill.setPadding(pad, 0, pad, 0)
        pill.background = FrostedPillDrawable(
            pill = pill,
            backdrop = host.backdrop,
            tintWithImage = ContextCompat.getColor(context, R.color.drawer_search_tint),
            tintWithoutImage = ContextCompat.getColor(context, R.color.drawer_search_bg),
        ).apply { setFrosted(host.frosted) }
        // Fully rounded, so ripples and children stay inside the capsule
        pill.clipToOutline = true
        pill.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }

        val inflater = LayoutInflater.from(context)
        val accent = ContextCompat.getColor(context, R.color.app_menu_accent)
        val danger = ContextCompat.getColor(context, R.color.app_menu_danger)
        val text = ContextCompat.getColor(context, R.color.app_menu_text)
        actions.forEach { action ->
            val item = inflater.inflate(R.layout.item_app_menu_pill_action, pill, false)
            item.findViewById<ImageView>(R.id.appMenuActionIcon).apply {
                setImageResource(action.iconRes)
                imageTintList = ColorStateList.valueOf(if (action.destructive) danger else accent)
            }
            labels += item.findViewById<TextView>(R.id.appMenuActionLabel).apply {
                this.text = action.label
                setTextColor(if (action.destructive) danger else text)
            }
            item.contentDescription = action.label
            if (action.dimmed) item.alpha = 0.38f
            item.setOnClickListener {
                dismiss()
                action.onClick()
            }
            pill.addView(item)
        }

        overlay.setOnClickListener { dismiss() }
        overlay.addView(pill)
    }

    fun show() {
        // The finger that long-pressed is still down: keep the list, the drawer's drag-to-close
        // and the home swipe from turning its last few pixels of movement into a scroll or drag,
        // which would dismiss the menu the moment it appears.
        anchor.parent?.requestDisallowInterceptTouchEvent(true)

        host.container.addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        position()
        host.backDispatcher.addCallback(backCallback)
        anchor.addOnAttachStateChangeListener(detachListener)

        val interpolator = DecelerateInterpolator()
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(ENTER_MS).setInterpolator(interpolator).start()
        pill.scaleX = 0.9f
        pill.scaleY = 0.9f
        pill.animate().scaleX(1f).scaleY(1f).setDuration(ENTER_MS).setInterpolator(interpolator)
            // The frost is sampled at the pill's on-screen position, which moves while it scales
            .setUpdateListener { pill.invalidate() }
            .start()
    }

    fun dismiss(animate: Boolean = true) {
        if (dismissed) return
        dismissed = true
        backCallback.remove()
        anchor.removeOnAttachStateChangeListener(detachListener)
        overlay.isClickable = false
        val remove = { (overlay.parent as? ViewGroup)?.removeView(overlay) }
        if (animate && overlay.isAttachedToWindow) {
            overlay.animate().cancel()
            pill.animate().cancel()
            overlay.animate().alpha(0f).setDuration(EXIT_MS).withEndAction { remove() }.start()
        } else {
            remove()
        }
        onDismissed()
    }

    private fun position() {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()
        val container = host.container

        // Everything in container coordinates
        val containerLoc = IntArray(2).also { container.getLocationInWindow(it) }
        val loc = IntArray(2).also { anchor.getLocationInWindow(it) }
        val left = loc[0] - containerLoc[0]
        val top = loc[1] - containerLoc[1]

        // Hug the label: its 24dp side padding shrinks to 12dp around the text and icon
        val inset = (minOf(anchor.paddingLeft, anchor.paddingRight) - dp(12)).coerceAtLeast(0)
        val highlight = RectF(
            (left + inset).toFloat(), top.toFloat(),
            (left + anchor.width - inset).toFloat(), (top + anchor.height).toFloat()
        )
        overlay.highlight = highlight
        overlay.highlightRadius = dp(16).toFloat()

        // Usable area: excludes system bars and the keyboard
        val visible = Rect().also { container.getWindowVisibleDisplayFrame(it) }
        val screenLoc = IntArray(2).also { container.getLocationOnScreen(it) }
        visible.offset(-screenLoc[0], -screenLoc[1])
        visible.intersect(0, 0, container.width, container.height)
        val margin = dp(16)
        val gap = dp(8)
        val height = dp(64)

        // Labels under the icons when they fit, icons only when they don't
        val maxWidth = visible.width() - 2 * margin
        var width = measureWidth()
        if (width > maxWidth) {
            labels.forEach { it.visibility = View.GONE }
            // Holding an icon still names it
            for (i in 0 until pill.childCount) pill.getChildAt(i).tooltipText = actions[i].label
            width = measureWidth().coerceAtMost(maxWidth)
        }

        val horizontal = labelGravity and Gravity.HORIZONTAL_GRAVITY_MASK
        val rtl = anchor.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val alignRight = horizontal == Gravity.RIGHT || (horizontal == Gravity.END && !rtl) ||
                (horizontal == Gravity.START && rtl)
        val anchorX = when {
            horizontal == Gravity.CENTER_HORIZONTAL -> highlight.centerX()
            alignRight -> highlight.right
            else -> highlight.left
        }.roundToInt()
        val x = when {
            horizontal == Gravity.CENTER_HORIZONTAL -> anchorX - width / 2
            alignRight -> anchorX - width
            else -> anchorX
        }.coerceIn(visible.left + margin, (visible.right - margin - width).coerceAtLeast(visible.left + margin))

        val above = highlight.top.roundToInt() - gap - height
        val below = highlight.bottom.roundToInt() + gap
        val placeAbove = above >= visible.top + margin || below + height > visible.bottom - margin
        val y = (if (placeAbove) above else below)
            .coerceIn(visible.top + margin, (visible.bottom - margin - height).coerceAtLeast(visible.top + margin))

        pill.layoutParams = FrameLayout.LayoutParams(width, height).apply {
            leftMargin = x
            topMargin = y
        }
        // Grow out of the row
        pill.pivotX = (anchorX - x).toFloat().coerceIn(0f, width.toFloat())
        pill.pivotY = if (y < highlight.top) height.toFloat() else 0f
    }

    private fun measureWidth(): Int {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        pill.measure(unspecified, View.MeasureSpec.makeMeasureSpec((64 * context.resources.displayMetrics.density).roundToInt(), View.MeasureSpec.EXACTLY))
        return pill.measuredWidth
    }

    /** Full-size scrim with a highlighted cut-out over the pressed row. */
    @SuppressLint("ViewConstructor")
    private inner class Overlay(context: Context) : FrameLayout(context) {
        var highlight: RectF? = null
        var highlightRadius = 0f
        private val scrimPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.app_menu_scrim) }
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.app_menu_highlight)
        }
        private val path = Path()

        init {
            setWillNotDraw(false)
            isClickable = true
            // Lets TalkBack users leave the menu without the back gesture
            contentDescription = context.getString(R.string.close)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            // The drawer root and home root intercept vertical drags; while the menu is up,
            // every gesture belongs to it.
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
            return super.dispatchTouchEvent(ev)
        }

        override fun onDraw(canvas: Canvas) {
            val hl = highlight
            path.reset()
            path.fillType = Path.FillType.EVEN_ODD
            path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            if (hl != null) path.addRoundRect(hl, highlightRadius, highlightRadius, Path.Direction.CW)
            canvas.drawPath(path, scrimPaint)
            if (hl != null) canvas.drawRoundRect(hl, highlightRadius, highlightRadius, highlightPaint)
        }

        override fun onConfigurationChanged(newConfig: Configuration?) {
            super.onConfigurationChanged(newConfig)
            dismiss(animate = false)
        }
    }

    private companion object {
        const val ENTER_MS = 160L
        const val EXIT_MS = 120L
    }
}
