package com.github.codeworkscreativehub.mlauncher.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.github.codeworkscreativehub.mlauncher.R
import com.github.codeworkscreativehub.mlauncher.helper.FontManager
import kotlin.math.roundToInt

/**
 * Floating long-press menu for an app row. Drawn in an overlay on the activity's decor view:
 * a light scrim with a rounded cut-out that keeps the pressed row visible and highlighted,
 * and a card placed just below the row (above it when there is no room).
 * Tap outside, back, a config change, or the row leaving the window dismisses it.
 */
class AppContextMenu(
    private val activity: FragmentActivity,
    private val anchor: View,
    private val labelGravity: Int,
    private val title: String,
    private val subtitle: String,
    private val actions: List<Action>,
    private val onDismissed: () -> Unit,
) {
    class Action(
        val iconRes: Int,
        val label: String,
        val destructive: Boolean = false,
        /** Shown dimmed; still clickable so the action can explain why it can't run. */
        val dimmed: Boolean = false,
        val onClick: () -> Unit,
    )

    private val decor = activity.window.decorView as ViewGroup
    private val overlay = Overlay(activity)
    private val card: LinearLayout
    private val iconView: ImageView
    private var dismissed = false

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = dismiss()
    }

    private val detachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) = dismiss(animate = false)
    }

    init {
        val inflater = LayoutInflater.from(activity)
        card = inflater.inflate(R.layout.popup_app_menu, overlay, false) as LinearLayout
        iconView = card.findViewById(R.id.appMenuIcon)
        card.findViewById<TextView>(R.id.appMenuName).apply {
            text = title
            typeface = Typeface.create(FontManager.getTypeface(activity) ?: Typeface.DEFAULT, 500, false)
        }
        card.findViewById<TextView>(R.id.appMenuSubtitle).apply {
            text = subtitle
            visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        }

        val list = card.findViewById<LinearLayout>(R.id.appMenuActions)
        val accent = ContextCompat.getColor(activity, R.color.app_menu_accent)
        val danger = ContextCompat.getColor(activity, R.color.app_menu_danger)
        val text = ContextCompat.getColor(activity, R.color.app_menu_text)
        actions.forEachIndexed { i, action ->
            if (action.destructive && i > 0) list.addView(divider())
            val row = inflater.inflate(R.layout.item_app_menu_action, list, false)
            row.findViewById<ImageView>(R.id.appMenuActionIcon).apply {
                setImageResource(action.iconRes)
                imageTintList = ColorStateList.valueOf(if (action.destructive) danger else accent)
            }
            row.findViewById<TextView>(R.id.appMenuActionLabel).apply {
                this.text = action.label
                setTextColor(if (action.destructive) danger else text)
            }
            if (action.dimmed) row.alpha = 0.38f
            row.setOnClickListener {
                dismiss()
                action.onClick()
            }
            list.addView(row)
        }

        overlay.setOnClickListener { dismiss() }
        overlay.addView(card)
    }

    fun setIcon(icon: Drawable?) {
        iconView.setImageDrawable(icon)
    }

    fun show() {
        decor.addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        position()
        activity.onBackPressedDispatcher.addCallback(backCallback)
        anchor.addOnAttachStateChangeListener(detachListener)

        val interpolator = DecelerateInterpolator()
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(ENTER_MS).setInterpolator(interpolator).start()
        card.scaleX = 0.92f
        card.scaleY = 0.92f
        card.animate().scaleX(1f).scaleY(1f).setDuration(ENTER_MS).setInterpolator(interpolator).start()
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
            card.animate().cancel()
            overlay.animate().alpha(0f).setDuration(EXIT_MS).withEndAction { remove() }.start()
        } else {
            remove()
        }
        onDismissed()
    }

    private fun position() {
        val res = activity.resources
        fun dp(v: Int) = (v * res.displayMetrics.density).roundToInt()

        // Everything in decor-view coordinates
        val decorLoc = IntArray(2).also { decor.getLocationInWindow(it) }
        val loc = IntArray(2).also { anchor.getLocationInWindow(it) }
        val left = loc[0] - decorLoc[0]
        val top = loc[1] - decorLoc[1]

        // Hug the label: its 24dp side padding shrinks to 12dp around the text and icon
        val inset = (minOf(anchor.paddingLeft, anchor.paddingRight) - dp(12)).coerceAtLeast(0)
        val highlight = RectF(
            (left + inset).toFloat(), top.toFloat(),
            (left + anchor.width - inset).toFloat(), (top + anchor.height).toFloat()
        )
        overlay.highlight = highlight
        overlay.highlightRadius = dp(16).toFloat()

        // Usable area: excludes system bars and the keyboard
        val visible = Rect().also { decor.getWindowVisibleDisplayFrame(it) }
        val screenLoc = IntArray(2).also { decor.getLocationOnScreen(it) }
        visible.offset(-screenLoc[0], -screenLoc[1])
        val margin = dp(12)
        val gap = dp(8)

        val maxWidth = visible.width() - 2 * margin
        card.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val w = card.measuredWidth
        val h = card.measuredHeight

        val horizontal = labelGravity and Gravity.HORIZONTAL_GRAVITY_MASK
        val rtl = anchor.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val alignRight = horizontal == Gravity.RIGHT || (horizontal == Gravity.END && !rtl) ||
                (horizontal == Gravity.START && rtl)
        val centered = horizontal == Gravity.CENTER_HORIZONTAL
        val x = when {
            centered -> highlight.centerX().roundToInt() - w / 2
            alignRight -> highlight.right.roundToInt() - w
            else -> highlight.left.roundToInt()
        }.coerceIn(visible.left + margin, (visible.right - margin - w).coerceAtLeast(visible.left + margin))

        val below = highlight.bottom.roundToInt() + gap
        val above = highlight.top.roundToInt() - gap - h
        val placeBelow = below + h <= visible.bottom - margin || above < visible.top + margin
        val y = (if (placeBelow) below else above)
            .coerceIn(visible.top + margin, (visible.bottom - margin - h).coerceAtLeast(visible.top + margin))

        card.layoutParams = FrameLayout.LayoutParams(w, h).apply {
            leftMargin = x
            topMargin = y
        }
        // Grow out of the row: pivot on the edge and side nearest to it
        card.pivotX = when {
            centered -> w / 2f
            alignRight -> w.toFloat()
            else -> 0f
        }
        card.pivotY = if (y >= highlight.bottom) 0f else h.toFloat()
    }

    private fun divider() = View(activity).apply {
        setBackgroundColor(ContextCompat.getColor(activity, R.color.app_menu_divider))
        val d = resources.displayMetrics.density
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, d.roundToInt().coerceAtLeast(1)).apply {
            val m = (20 * d).roundToInt()
            setMargins(m, (4 * d).roundToInt(), m, (4 * d).roundToInt())
        }
    }

    /** Full-window scrim with a highlighted cut-out over the pressed row. */
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
