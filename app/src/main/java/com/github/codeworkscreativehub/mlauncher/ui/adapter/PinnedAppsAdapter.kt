package com.github.codeworkscreativehub.mlauncher.ui.adapter

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.github.codeworkscreativehub.mlauncher.R
import com.github.codeworkscreativehub.mlauncher.data.AppListItem
import com.github.codeworkscreativehub.mlauncher.helper.FontManager
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Header for the drawer list: a "Pinned" label over a rounded card holding a grid of up to [MAX]
 * pinned apps. The grid fits as many ~56dp columns as the width allows and wraps to a second row.
 *
 * Tap launches. Long-press opens the app menu; keep the finger down and move it more than 12dp
 * and the menu closes and the icon follows the finger, the others sliding out of its way. Lifting
 * the finger saves the new order through [onReorder].
 *
 * The item stays in the list (zero height) while nothing is pinned, so the first pin and the last
 * unpin can grow and shrink the section instead of popping it. Zero items while searching.
 */
class PinnedAppsAdapter(
    /** Absolute LEFT, CENTER or RIGHT, following the drawer list alignment. */
    private val gravity: Int,
    private val onClick: (AppListItem) -> Unit,
    private val onLongClick: (AppListItem, View) -> Unit,
    /** The finger started dragging a long-pressed icon: close its menu. */
    private val onDragStart: () -> Unit,
    private val onReorder: (List<AppListItem>) -> Unit,
    private val bindIcon: (AppListItem, ImageView) -> Unit,
    private val labelOf: (AppListItem) -> String,
) : RecyclerView.Adapter<PinnedAppsAdapter.Holder>() {

    companion object {
        const val MAX = 10
        private const val MOVE_MS = 160L
        private const val RESIZE_MS = 220L
    }

    var apps: List<AppListItem> = emptyList()
        private set
    private var hidden = false

    override fun getItemCount() = if (hidden) 0 else 1

    fun submit(apps: List<AppListItem>) = update { this.apps = apps.take(MAX) }

    fun setHidden(hidden: Boolean) = update { this.hidden = hidden }

    private fun update(change: () -> Unit) {
        val before = itemCount
        change()
        when {
            before == 0 && itemCount == 1 -> notifyItemInserted(0)
            before == 1 && itemCount == 0 -> notifyItemRemoved(0)
            itemCount == 1 -> notifyItemChanged(0)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Everything lives in [content]; hiding it collapses the item to zero height
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(16))
        }
        root.addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val grid = PinnedGrid(ctx, gravity).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        content.addView(grid, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return Holder(root, content, grid)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(apps)

    private fun mediumTypeface(ctx: Context): Typeface =
        Typeface.create(FontManager.getTypeface(ctx) ?: Typeface.DEFAULT, 500, false)

    @SuppressLint("ClickableViewAccessibility")
    inner class Holder(
        root: View,
        private val content: View,
        private val grid: PinnedGrid,
    ) : RecyclerView.ViewHolder(root) {

        private val density = root.resources.displayMetrics.density
        private val dragSlop = 12 * density
        private var shown: List<AppListItem> = emptyList()
        private var resize: ValueAnimator? = null
        private val moveInterpolator = DecelerateInterpolator()

        // Drag state. [armed] is the cell whose long-press fired during the current gesture.
        private var armed: View? = null
        private var downX = 0f
        private var downY = 0f
        private var dragFrom = -1
        private var dragTo = -1
        private var settling = false
        private var rebindAfterDrag = false

        fun bind(apps: List<AppListItem>) {
            if (dragFrom >= 0 || settling) {
                rebindAfterDrag = true
                return
            }
            val wasEmpty = shown.isEmpty()
            shown = apps
            if (apps.isNotEmpty()) fillCells(apps)
            val animate = itemView.isAttachedToWindow && itemView.isLaidOut && wasEmpty != apps.isEmpty()
            if (animate) animateResize(expand = apps.isNotEmpty()) else setExpanded(apps.isNotEmpty())
        }

        private fun fillCells(apps: List<AppListItem>) {
            while (grid.childCount < apps.size) grid.addView(makeCell())
            while (grid.childCount > apps.size) grid.removeViewAt(grid.childCount - 1)
            apps.forEachIndexed { i, app ->
                val cell = grid.getChildAt(i) as LinearLayout
                cell.tag = app
                val label = cell.getChildAt(1) as AppCompatTextView
                label.text = labelOf(app)
                cell.contentDescription = label.text
                bindIcon(app, cell.getChildAt(0) as ImageView)
                bindMoveActions(cell, i, apps.size)
            }
        }

        // ---------------------------------------------------------------- size

        private fun setExpanded(expanded: Boolean) {
            resize?.cancel()
            content.visibility = if (expanded) View.VISIBLE else View.GONE
            content.alpha = 1f
            itemView.layoutParams = itemView.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        }

        private fun animateResize(expand: Boolean) {
            resize?.cancel()
            val from = itemView.height
            val to = if (expand) {
                content.visibility = View.VISIBLE
                itemView.measure(
                    View.MeasureSpec.makeMeasureSpec(itemView.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                itemView.measuredHeight
            } else 0
            resize = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = RESIZE_MS
                interpolator = moveInterpolator
                addUpdateListener {
                    val f = it.animatedValue as Float
                    itemView.layoutParams = itemView.layoutParams.apply { height = (from + (to - from) * f).roundToInt() }
                    content.alpha = if (expand) f else 1f - f
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) { cancelled = true }
                    override fun onAnimationEnd(animation: Animator) {
                        if (cancelled) return
                        resize = null
                        setExpanded(expand)
                    }
                })
                start()
            }
        }

        // ---------------------------------------------------------------- cells

        private fun makeCell(): LinearLayout {
            val ctx = grid.context
            fun dp(v: Int) = (v * density).roundToInt()
            val mask = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFFFFFFFF.toInt())
            }
            val ripple = ContextCompat.getColor(ctx, R.color.app_menu_ripple)
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(2), dp(8), dp(2), dp(8))
                isClickable = true
                isLongClickable = true
                isFocusable = true
                foreground = RippleDrawable(ColorStateList.valueOf(ripple), null, mask)

                addView(ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(36), dp(36)))

                addView(AppCompatTextView(ctx).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    typeface = mediumTypeface(ctx)
                    setTextColor(ContextCompat.getColor(ctx, R.color.drawer_pinned_label))
                    gravity = Gravity.CENTER_HORIZONTAL
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                })

                setOnClickListener { v -> (v.tag as? AppListItem)?.let(onClick) }
                setOnLongClickListener { v ->
                    val app = v.tag as? AppListItem ?: return@setOnLongClickListener false
                    armed = v
                    onLongClick(app, v)
                    true
                }
                setOnTouchListener { v, ev -> onCellTouch(v, ev) }
            }
        }

        /** TalkBack can't drag, so each cell also offers Move left / Move right. */
        private fun bindMoveActions(cell: View, index: Int, count: Int) {
            @Suppress("UNCHECKED_CAST")
            (cell.getTag(R.id.pinned_move_actions) as? List<Int>)?.forEach { ViewCompat.removeAccessibilityAction(cell, it) }
            val ids = mutableListOf<Int>()
            if (index > 0) ids += ViewCompat.addAccessibilityAction(cell, cell.context.getString(R.string.pinned_move_left)) { _, _ ->
                commitMove(index, index - 1); true
            }
            if (index < count - 1) ids += ViewCompat.addAccessibilityAction(cell, cell.context.getString(R.string.pinned_move_right)) { _, _ ->
                commitMove(index, index + 1); true
            }
            cell.setTag(R.id.pinned_move_actions, ids)
        }

        // ---------------------------------------------------------------- drag

        private fun onCellTouch(cell: View, ev: MotionEvent): Boolean {
            if (settling) return true
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    armed = null
                    downX = ev.rawX
                    downY = ev.rawY
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (dragFrom < 0 && armed === cell && hypot(dx, dy) > dragSlop) startDrag(cell)
                    if (dragFrom >= 0) {
                        dragTo(cell, dx, dy)
                        return true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    armed = null
                    if (dragFrom >= 0) {
                        drop(cell)
                        return true
                    }
                }
            }
            return false
        }

        private fun startDrag(cell: View) {
            onDragStart()
            grid.parent?.requestDisallowInterceptTouchEvent(true)
            cell.isPressed = false
            dragFrom = grid.indexOfChild(cell)
            dragTo = dragFrom
            // Drawn above its neighbours; no outline, so no shadow on the flat theme
            cell.translationZ = 1f
            cell.animate().scaleX(1.12f).scaleY(1.12f).alpha(0.9f).setDuration(MOVE_MS).setInterpolator(moveInterpolator).start()
        }

        private fun dragTo(cell: View, dx: Float, dy: Float) {
            cell.translationX = dx
            cell.translationY = dy
            val target = grid.nearestSlot(grid.slotCenterX(dragFrom) + dx, grid.slotCenterY(dragFrom) + dy)
            if (target == dragTo) return
            dragTo = target
            grid.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            for (i in 0 until grid.childCount) {
                if (i == dragFrom) continue
                val slot = shiftedIndex(i, dragFrom, dragTo)
                grid.getChildAt(i).animate()
                    .translationX(grid.slotX(slot) - grid.slotX(i).toFloat())
                    .translationY(grid.slotY(slot) - grid.slotY(i).toFloat())
                    .setDuration(MOVE_MS).setInterpolator(moveInterpolator).start()
            }
        }

        private fun drop(cell: View) {
            val from = dragFrom
            val to = dragTo
            dragFrom = -1
            settling = true
            cell.animate()
                .translationX(grid.slotX(to) - grid.slotX(from).toFloat())
                .translationY(grid.slotY(to) - grid.slotY(from).toFloat())
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(MOVE_MS).setInterpolator(moveInterpolator)
                .withEndAction {
                    settling = false
                    for (i in 0 until grid.childCount) grid.getChildAt(i).apply {
                        animate().cancel()
                        translationX = 0f
                        translationY = 0f
                        translationZ = 0f
                    }
                    if (from != to) commitMove(from, to)
                    if (rebindAfterDrag) {
                        rebindAfterDrag = false
                        bind(this@PinnedAppsAdapter.apps)
                    }
                }
                .start()
        }

        private fun commitMove(from: Int, to: Int) {
            val ordered = shown.toMutableList().apply { add(to, removeAt(from)) }
            this@PinnedAppsAdapter.apps = ordered
            shown = ordered
            fillCells(ordered)
            onReorder(ordered)
        }
    }

    /** Where item [i] sits while the item at [from] is held over slot [to]. */
    private fun shiftedIndex(i: Int, from: Int, to: Int) = when {
        from < to && i in (from + 1)..to -> i - 1
        from > to && i in to until from -> i + 1
        else -> i
    }

    /**
     * Equal cells, as many ~56dp columns as fit (4 to 8). A row with fewer apps than columns sits
     * left, centred or right to match the list below.
     */
    class PinnedGrid(context: Context, private val gravity: Int) : ViewGroup(context) {
        private val minCell = (56 * context.resources.displayMetrics.density).roundToInt()
        private var cols = 1
        private var cellW = 0
        private var cellH = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val avail = (width - paddingLeft - paddingRight).coerceAtLeast(0)
            cols = (avail / minCell).coerceIn(4, 8)
            cellW = avail / cols
            cellH = 0
            val childW = MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY)
            val childH = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.measure(childW, childH)
                cellH = maxOf(cellH, child.measuredHeight)
            }
            val rows = (childCount + cols - 1) / cols
            setMeasuredDimension(width, paddingTop + paddingBottom + rows * cellH)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (i in 0 until childCount) {
                val x = slotX(i)
                val y = slotY(i)
                getChildAt(i).layout(x, y, x + cellW, y + cellH)
            }
        }

        fun slotX(i: Int): Int {
            val row = i / cols
            val inRow = min(cols, childCount - row * cols)
            val spare = (cols - inRow) * cellW
            val offset = when (gravity) {
                Gravity.RIGHT -> spare
                Gravity.CENTER -> spare / 2
                else -> 0
            }
            val ltr = paddingLeft + offset + (i % cols) * cellW
            return if (layoutDirection == LAYOUT_DIRECTION_RTL) width - ltr - cellW else ltr
        }

        fun slotY(i: Int) = paddingTop + (i / cols) * cellH

        fun slotCenterX(i: Int) = slotX(i) + cellW / 2f
        fun slotCenterY(i: Int) = slotY(i) + cellH / 2f

        fun nearestSlot(x: Float, y: Float): Int =
            (0 until childCount).minBy { hypot(slotCenterX(it) - x, slotCenterY(it) - y) }
    }
}
