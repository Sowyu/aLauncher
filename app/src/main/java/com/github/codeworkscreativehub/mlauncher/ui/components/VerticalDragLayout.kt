package com.github.codeworkscreativehub.mlauncher.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * FrameLayout that can take over a vertical drag from its children once it passes touch slop.
 * The [callback] decides per gesture whether to take it; children get ACTION_CANCEL when it does.
 * Used to pull the app drawer up from the home screen and down from the top of the list.
 */
class VerticalDragLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface Callback {
        /** [dy] < 0 means the finger moved up. Return true to own the rest of the gesture. */
        fun shouldStartDrag(downX: Float, downY: Float, dy: Float): Boolean
        fun onDragStart()

        /** [dy] is the total finger travel since the drag started (negative = up). */
        fun onDrag(dy: Float)

        /** [velocityY] in px/s, negative = up. Also called with 0 when the gesture is cancelled. */
        fun onDragEnd(velocityY: Float)
    }

    var callback: Callback? = null

    /** A tap that no child took (down and up without moving), in this view's coordinates. */
    var onTap: ((x: Float, y: Float) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()

    private var downX = 0f
    private var downY = 0f
    private var startY = 0f
    private var decided = false
    private var dragging = false
    private var tracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (callback == null) return false
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> reset(ev)
            MotionEvent.ACTION_MOVE -> maybeStartDrag(ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> clear()
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val cb = callback ?: return super.onTouchEvent(ev)
        track(ev)
        when (ev.actionMasked) {
            // No child wanted the touch: watch the gesture ourselves
            MotionEvent.ACTION_DOWN -> reset(ev)
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) maybeStartDrag(ev)
                if (dragging) cb.onDrag(ev.y - startY)
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    val vt = tracker
                    vt?.computeCurrentVelocity(1000, maxFling)
                    cb.onDragEnd(vt?.yVelocity ?: 0f)
                } else if (abs(ev.x - downX) < touchSlop && abs(ev.y - downY) < touchSlop) {
                    onTap?.invoke(ev.x, ev.y)
                }
                clear()
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) cb.onDragEnd(0f)
                clear()
            }
        }
        return true
    }

    private fun maybeStartDrag(ev: MotionEvent) {
        if (decided) return
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.2f) {
            decided = true
            if (callback?.shouldStartDrag(downX, downY, dy) == true) {
                dragging = true
                startY = ev.y
                parent?.requestDisallowInterceptTouchEvent(true)
                callback?.onDragStart()
            }
        } else if (abs(dx) > touchSlop) {
            // Horizontal gesture: leave it to the children
            decided = true
        }
    }

    private fun reset(ev: MotionEvent) {
        downX = ev.x
        downY = ev.y
        decided = false
        dragging = false
    }

    private fun track(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            tracker?.recycle()
            tracker = VelocityTracker.obtain()
        }
        tracker?.addMovement(ev)
    }

    private fun clear() {
        dragging = false
        decided = false
        tracker?.recycle()
        tracker = null
    }
}
