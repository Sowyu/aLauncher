package com.github.codeworkscreativehub.mlauncher.ui.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.codeworkscreativehub.mlauncher.R
import com.github.codeworkscreativehub.mlauncher.data.AppListItem
import com.github.codeworkscreativehub.mlauncher.data.Prefs
import com.github.codeworkscreativehub.mlauncher.helper.utils.AppDiffCallback

// Adapter to display Home Apps
class FavoriteAdapter(
    private val apps: MutableList<AppListItem>, // List of AppListItem objects
    private val onItemMoved: (fromPosition: Int, toPosition: Int) -> Unit,
    private val prefs: Prefs,
    private val onItemClick: ((position: Int) -> Unit)? = null // optional click callback
) : RecyclerView.Adapter<FavoriteAdapter.AppViewHolder>() {

    class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appTextView: TextView = itemView.findViewById(R.id.homeAppLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_favorite_app, parent, false)
        return AppViewHolder(view)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val appItem = apps[position]

        // An empty slot shows the "Choose an app" hint; look comes from item_favorite_app.xml
        holder.appTextView.text = if (appItem.activityPackage.isEmpty()) "" else {
            prefs.getAppAlias(appItem.activityPackage).takeIf { it.isNotBlank() } ?: appItem.activityLabel
        }

        // Click -> delegate to callback if provided
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onItemClick?.invoke(pos)
        }
    }


    override fun getItemCount(): Int = apps.size

    // Notify when an item is moved
    fun moveItem(fromPosition: Int, toPosition: Int) {
        val temp = apps[fromPosition]
        apps[fromPosition] = apps[toPosition]
        apps[toPosition] = temp
        notifyItemMoved(fromPosition, toPosition)
        onItemMoved(fromPosition, toPosition)  // Notify the view model of the change
    }

    // Update the list when the data changes
    fun updateList(newList: List<AppListItem>) {
        val diffCallback = AppDiffCallback(apps, newList)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        apps.clear()
        apps.addAll(newList)
        diffResult.dispatchUpdatesTo(this)
    }
}
