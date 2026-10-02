package com.github.codeworkscreativehub.mlauncher.ui.adapter

import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.github.codeworkscreativehub.mlauncher.R
import com.github.codeworkscreativehub.mlauncher.data.AppListItem
import com.github.creativecodecat.components.views.FontAppCompatTextView
import kotlin.math.roundToInt

/**
 * Header for the drawer list: one row of up to [MAX] pinned app icons with tiny labels, then a
 * hairline. Zero items when nothing is pinned or a search is active, so the list starts as usual.
 */
class PinnedAppsAdapter(
    private val onClick: (AppListItem) -> Unit,
    private val onLongClick: (AppListItem, View) -> Unit,
    private val bindIcon: (AppListItem, ImageView) -> Unit,
    private val labelOf: (AppListItem) -> String,
) : RecyclerView.Adapter<PinnedAppsAdapter.Holder>() {

    companion object {
        const val MAX = 6
    }

    var apps: List<AppListItem> = emptyList()
        private set
    private var hidden = false

    override fun getItemCount() = if (!hidden && apps.isNotEmpty()) 1 else 0

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
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Six fixed slots, so one or two pins sit where they would in a full row
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = MAX.toFloat()
            setPadding(dp(8), dp(4), dp(8), dp(12))
        }
        root.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val hairline = View(ctx).apply {
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.drawer_pinned_divider))
        }
        root.addView(hairline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
            bottomMargin = dp(8)
        })
        return Holder(root, row)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(apps)

    inner class Holder(root: View, private val row: LinearLayout) : RecyclerView.ViewHolder(root) {

        fun bind(apps: List<AppListItem>) {
            while (row.childCount < apps.size) row.addView(makeSlot())
            for (i in 0 until row.childCount) {
                val slot = row.getChildAt(i) as LinearLayout
                val app = apps.getOrNull(i)
                slot.visibility = if (app == null) View.GONE else View.VISIBLE
                if (app == null) continue
                val icon = slot.getChildAt(0) as ImageView
                val label = slot.getChildAt(1) as FontAppCompatTextView
                label.text = labelOf(app)
                slot.contentDescription = label.text
                bindIcon(app, icon)
                slot.setOnClickListener { onClick(app) }
                slot.setOnLongClickListener {
                    onLongClick(app, it)
                    true
                }
            }
        }

        private fun makeSlot(): LinearLayout {
            val ctx = row.context
            val density = ctx.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).roundToInt()
            val ripple = ContextCompat.getColor(ctx, R.color.app_menu_ripple)
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                minimumHeight = dp(56)
                setPadding(dp(2), dp(4), dp(2), dp(4))
                isClickable = true
                isLongClickable = true
                isFocusable = true
                // Borderless ripple: no shape drawn at rest
                foreground = RippleDrawable(ColorStateList.valueOf(ripple), null, null)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                addView(ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

                addView(FontAppCompatTextView(ctx).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(ContextCompat.getColor(ctx, R.color.drawer_pinned_label))
                    gravity = Gravity.CENTER_HORIZONTAL
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                })
            }
        }
    }
}
