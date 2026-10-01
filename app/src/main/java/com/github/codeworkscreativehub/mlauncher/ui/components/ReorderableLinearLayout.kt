package com.github.codeworkscreativehub.mlauncher.ui.components

import android.animation.TimeAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Vertical list of home app rows with an iOS-style edit mode: rows jiggle, and any row can be
 * dragged to a new position straight away. Outside edit mode it is a plain LinearLayout and
 * touches go to the rows as usual.
 */
class ReorderableLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** Called on drop with child indices, after the views are already in their new order. Persist only. */
    var onReorder: ((from: Int, to: Int) -> Unit)? = null

    var editMode = false
        private set

    private val density = resources.displayMetrics.density
    private val jiggleDegrees = 1.2f
    private val jiggleShift = 1f * density
    private val jigglePeriodMs = 260.0
    private val phases = HashMap<View, Double>()

    private val jiggle = TimeAnimator().apply {
        setTimeListener { _, total, _ -> applyJiggle(total) }
    }

    private var dragged: View? = null
    private var dragFrom = -1
    private var dragTarget = -1
    private var downY = 0f
    private var rowStep = 0f

    init {
        isChildrenDrawingOrderEnabled = true
    }

    fun enterEditMode() {
        if (editMode) return
        editMode = true
        phases.clear()
        visibleRows().forEach { phases[it] = Random.nextDouble(0.0, 2 * PI) }
        jiggle.start()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun exitEditMode(animate: Boolean = true) {
        if (!editMode) return
        editMode = false
        jiggle.cancel()
        cancelDrag()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (animate) {
                c.animate().rotation(0f).translationX(0f).translationY(0f)
                    .scaleX(1f).scaleY(1f).alpha(1f).setDuration(160).start()
            } else {
                c.animate().cancel()
                c.rotation = 0f
                c.translationX = 0f
                c.translationY = 0f
                c.scaleX = 1f
                c.scaleY = 1f
                c.alpha = 1f
            }
        }
    }

    /** Rows were rebuilt (e.g. after a reorder): give the new ones a phase and keep jiggling. */
    fun onRowsRebuilt() {
        if (!editMode) return
        phases.clear()
        visibleRows().forEach { phases[it] = Random.nextDouble(0.0, 2 * PI) }
    }

    private fun visibleRows(): List<View> =
        (0 until childCount).map { getChildAt(it) }.filter { it.visibility == View.VISIBLE }

    private fun applyJiggle(totalMs: Long) {
        val t = totalMs / jigglePeriodMs * 2 * PI
        for (row in visibleRows()) {
            if (row === dragged) continue
            val phase = phases.getOrPut(row) { Random.nextDouble(0.0, 2 * PI) }
            pivotOnText(row)
            row.rotation = (sin(t + phase) * jiggleDegrees).toFloat()
            row.translationX = (sin(t + phase + PI / 2) * jiggleShift).toFloat()
        }
    }

    /** Rotate around the label, not the middle of a full-width row, so the text barely moves. */
    private fun pivotOnText(row: View) {
        val tv = row as? TextView ?: return
        val textW = tv.paint.measureText(tv.text?.toString() ?: "") +
            tv.compoundPaddingLeft + tv.compoundPaddingRight
        val w = row.width.toFloat()
        val gravity = tv.gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK
        row.pivotX = when (gravity) {
            android.view.Gravity.RIGHT, android.view.Gravity.END -> w - textW / 2f
            android.view.Gravity.LEFT, android.view.Gravity.START -> textW / 2f
            else -> w / 2f
        }.coerceIn(0f, w)
        row.pivotY = row.height / 2f
    }

    // ------------------------------------------------------------------ drag to reorder

    private fun rowIndexAt(y: Float): Int {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.VISIBLE && y >= c.top && y <= c.bottom) return i
        }
        return -1
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!editMode) return false
        // In edit mode rows never launch; we own every touch that starts on a row
        return ev.actionMasked == MotionEvent.ACTION_DOWN && rowIndexAt(ev.y) >= 0
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!editMode) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val i = rowIndexAt(ev.y)
                if (i < 0) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                startDrag(i, ev.y)
            }

            MotionEvent.ACTION_MOVE -> dragTo(ev.y)
            MotionEvent.ACTION_UP -> drop()
            MotionEvent.ACTION_CANCEL -> cancelDrag()
        }
        return true
    }

    private fun startDrag(index: Int, y: Float) {
        val row = getChildAt(index)
        dragged = row
        dragFrom = index
        dragTarget = index
        downY = y
        // Distance between consecutive rows, margins included
        val next = (index + 1 until childCount).map { getChildAt(it) }.firstOrNull { it.visibility == View.VISIBLE }
        val prev = (index - 1 downTo 0).map { getChildAt(it) }.firstOrNull { it.visibility == View.VISIBLE }
        rowStep = when {
            next != null -> (next.top - row.top).toFloat()
            prev != null -> (row.top - prev.top).toFloat()
            else -> row.height.toFloat()
        }
        row.rotation = 0f
        row.translationX = 0f
        row.animate().scaleX(1.05f).scaleY(1.05f).alpha(0.95f).setDuration(120).start()
        invalidate()
    }

    private fun dragTo(y: Float) {
        val row = dragged ?: return
        val dy = y - downY
        row.translationY = dy

        // Where would the dragged row's centre land?
        val center = row.top + row.height / 2f + dy
        var target = dragFrom
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c === row || c.visibility != View.VISIBLE) continue
            val mid = c.top + c.height / 2f
            if (i < dragFrom && center < mid) target = minOf(target, i)
            if (i > dragFrom && center > mid) target = maxOf(target, i)
        }
        if (target != dragTarget) {
            dragTarget = target
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c === row) continue
                val shift = when {
                    target > dragFrom && i in (dragFrom + 1)..target -> -rowStep
                    target < dragFrom && i in target until dragFrom -> rowStep
                    else -> 0f
                }
                if (abs(c.translationY - shift) > 0.5f) c.animate().translationY(shift).setDuration(180).start()
            }
        }
    }

    private fun drop() {
        val row = dragged ?: return
        val from = dragFrom
        val to = dragTarget
        if (from == to) {
            row.animate().translationY(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(160).start()
            clearDragState()
            return
        }
        // Commit at once: move the view into its new slot and keep it visually where the finger
        // left it, then let it glide home. The next drag can start immediately.
        val visualOffset = row.translationY - (to - from) * rowStep
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.animate().cancel()
            c.translationY = 0f
        }
        clearDragState()
        removeViewAt(from)
        addView(row, to)
        // Rows are identified by slot index (the host launches/edits by view id)
        for (i in 0 until childCount) getChildAt(i).id = i
        row.translationY = visualOffset
        row.animate().translationY(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(160).start()
        onReorder?.invoke(from, to)
    }

    private fun cancelDrag() {
        dragged?.animate()?.translationY(0f)?.scaleX(1f)?.scaleY(1f)?.alpha(1f)?.setDuration(160)?.start()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c !== dragged) c.animate().translationY(0f).setDuration(160).start()
        }
        clearDragState()
    }

    private fun clearDragState() {
        dragged = null
        dragFrom = -1
        dragTarget = -1
        invalidate()
    }

    /** Draw the lifted row last so it passes over the others. */
    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int {
        val d = dragged ?: return drawingPosition
        val di = indexOfChild(d)
        if (di < 0) return drawingPosition
        return when {
            drawingPosition == childCount - 1 -> di
            drawingPosition >= di -> drawingPosition + 1
            else -> drawingPosition
        }
    }

    override fun onDetachedFromWindow() {
        jiggle.cancel()
        super.onDetachedFromWindow()
    }
}
