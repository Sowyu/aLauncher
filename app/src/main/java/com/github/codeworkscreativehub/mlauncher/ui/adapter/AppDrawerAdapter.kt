/**
 * Prepare the data for the app drawer, which is the list of all the installed applications.
 */

package com.github.codeworkscreativehub.mlauncher.ui.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Filter
import android.widget.Filterable
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.biometric.BiometricPrompt
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.github.codeworkscreativehub.common.AppLogger
import com.github.codeworkscreativehub.common.getLocalizedString
import com.github.codeworkscreativehub.common.isSystemApp
import com.github.codeworkscreativehub.common.showKeyboard
import com.github.codeworkscreativehub.fuzzywuzzy.AppSearch
import com.github.codeworkscreativehub.fuzzywuzzy.FuzzyFinder
import com.github.codeworkscreativehub.fuzzywuzzy.FuzzyFinder.filterItems
import com.github.codeworkscreativehub.mlauncher.R
import com.github.codeworkscreativehub.mlauncher.data.AppListItem
import com.github.codeworkscreativehub.mlauncher.data.Constants
import com.github.codeworkscreativehub.mlauncher.data.Constants.AppDrawerFlag
import com.github.codeworkscreativehub.mlauncher.data.Prefs
import com.github.codeworkscreativehub.mlauncher.databinding.AdapterAppDrawerBinding
import com.github.codeworkscreativehub.mlauncher.helper.IconCacheTarget
import com.github.codeworkscreativehub.mlauncher.helper.IconPackHelper.getSafeAppIcon
import com.github.codeworkscreativehub.mlauncher.helper.dp2px
import com.github.codeworkscreativehub.mlauncher.helper.emptyString
import com.github.codeworkscreativehub.mlauncher.helper.getSystemIcons
import com.github.codeworkscreativehub.mlauncher.helper.utils.BiometricHelper
import com.github.codeworkscreativehub.mlauncher.ui.AppDrawerFragment
import com.github.codeworkscreativehub.mlauncher.ui.components.AppContextMenu
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class AppDrawerAdapter(
    private val context: Context,
    private val fragment: Fragment,
    internal var flag: AppDrawerFlag,
    private val gravity: Int,
    private val appClickListener: (AppListItem) -> Unit,
    private val appDeleteListener: (AppListItem) -> Unit,
    private val appRenameListener: (String, String) -> Unit,
    private val appTagListener: (String, String, UserHandle) -> Unit,
    private val appHideListener: (AppDrawerFlag, AppListItem) -> Unit,
    private val appInfoListener: (AppListItem) -> Unit
) : RecyclerView.Adapter<AppDrawerAdapter.ViewHolder>(), Filterable {

    private val prefs = Prefs(context)
    private var appFilter = createAppFilter()
    private var contextMenu: AppContextMenu? = null

    /** Row showing the inline rename/tag field, closed again when the list scrolls. */
    private var editingPosition = RecyclerView.NO_POSITION
    var appsList: MutableList<AppListItem> = mutableListOf()
    var appFilteredList: MutableList<AppListItem> = mutableListOf()
    private lateinit var binding: AdapterAppDrawerBinding
    private val biometricHelper by lazy { BiometricHelper(fragment.requireActivity()) }

    // Add icon cache
    private val iconCache = ConcurrentHashMap<String, Drawable>()
    private val iconLoadingScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var isBangSearch = false

    /** True while the list shows search results: rows then always carry an icon. */
    private var searching = false
    private var lastQuery = ""

    /** Normalised labels for [AppSearch], rebuilt when the app list changes. */
    private class SearchIndex(val source: List<AppListItem>, val items: List<AppListItem>, val keys: List<AppSearch.SearchKey>)

    @Volatile
    private var searchIndex: SearchIndex? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        binding = AdapterAppDrawerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val fontColor = prefs.appColor
        binding.appTitle.setTextColor(fontColor)

        binding.appTitle.textSize = prefs.appSize.toFloat()
        val padding: Int = prefs.textPaddingSize
        binding.appTitle.setPadding(0, padding, 0, padding)
        return ViewHolder(binding)
    }

    fun getItemAt(position: Int): AppListItem? {
        return if (position in appsList.indices) appsList[position] else null
    }

    @SuppressLint("RecyclerView")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (appFilteredList.isEmpty() || position !in appFilteredList.indices) {
            AppLogger.d("AppListDebug", "⚠️ onBindViewHolder called but appFilteredList is empty or position out of bounds")
            return
        }

        val appModel = appFilteredList[holder.bindingAdapterPosition]
        AppLogger.d("AppListDebug", "🔧 Binding position=$position, label=${appModel.activityLabel}, package=${appModel.activityPackage}")

        // Pass icon cache and loading scope to bind
        holder.bind(gravity, appModel, iconCache, iconLoadingScope, prefs, searching)

        holder.appSaveRename.setOnClickListener {
            val currentText = holder.appRenameEdit.text.toString().trim()

            when {
                currentText.isEmpty() -> { // Reset state
                    AppLogger.d("AppListDebug", "✏️ Resetting ${appModel.activityPackage} to default")
                    appRenameListener(appModel.activityPackage, emptyString()) // empty string = default
                }

                currentText != prefs.getAppAlias(appModel.activityPackage) -> { // Rename state
                    AppLogger.d("AppListDebug", "✏️ Renaming ${appModel.activityPackage} to $currentText")
                    appRenameListener(appModel.activityPackage, currentText)
                }
            }

            notifyItemChanged(holder.bindingAdapterPosition)
            AppLogger.d("AppListDebug", "🔁 notifyItemChanged at ${holder.bindingAdapterPosition}")
        }

        holder.appSaveCancel.setOnClickListener {
            AppLogger.d("AppListDebug", "✏️ Cancel rename for ${appModel.activityPackage}")

            notifyItemChanged(holder.bindingAdapterPosition)
            AppLogger.d("AppListDebug", "🔁 notifyItemChanged at ${holder.bindingAdapterPosition}")
        }

        holder.appSaveTag.setOnClickListener {
            val name = holder.appTagEdit.text.toString().trim()
            AppLogger.d("AppListDebug", "✏️ Tagging ${appModel.activityPackage} to $name")
            appModel.customTag = name
            notifyItemChanged(holder.bindingAdapterPosition)
            AppLogger.d("AppListDebug", "🔁 notifyItemChanged at ${holder.bindingAdapterPosition}")
            appTagListener(appModel.activityPackage, appModel.customTag, appModel.user)
        }

        autoLaunch(position)
    }

    override fun getItemCount(): Int = appFilteredList.size

    override fun getFilter(): Filter = this.appFilter

    /** Filters the list for [query]; remembered so a list reload keeps the results. */
    fun search(query: String) {
        lastQuery = query
        appFilter.filter(query)
    }

    private fun createAppFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(charSearch: CharSequence?): FilterResults {
                val searchChars = charSearch.toString().trim().lowercase()
                val isTagSearch = searchChars.startsWith("#")
                val query = if (isTagSearch) searchChars.substringAfter("#") else searchChars
                val prefs = Prefs(context)

                val filtered = if (isTagSearch) {
                    filterItems(
                        itemsList = appsList.toList(),
                        query = query,
                        prefs = prefs,
                        scoreProvider = { app, q -> FuzzyFinder.scoreString(app.tag, q, Constants.MAX_FILTER_STRENGTH) },
                        labelProvider = { app -> app.tag },
                        loggerTag = "appScore"
                    )
                } else if (query.isEmpty()) {
                    appsList.toMutableList()
                } else {
                    val index = searchIndexFor(prefs)
                    val threshold = if (prefs.enableFilterStrength) {
                        prefs.filterStrength.toFloat() / Constants.MAX_FILTER_STRENGTH
                    } else null
                    AppSearch.rank(index.keys, query, threshold, prefs.searchFromStart)
                        .mapTo(mutableListOf()) { index.items[it] }
                }

                return FilterResults().apply { values = filtered }
            }

            @SuppressLint("NotifyDataSetChanged")
            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                if (results?.values is MutableList<*>) {
                    searching = !constraint.isNullOrBlank()
                    appFilteredList = results.values as MutableList<AppListItem>
                    notifyDataSetChanged()
                } else {
                    return
                }
            }
        }
    }

    private fun autoLaunch(position: Int) {
        val lastMatch = itemCount == 1
        val openApp = flag == AppDrawerFlag.LaunchApp
        val autoOpenApp = prefs.autoOpenApp
        if (lastMatch && openApp && autoOpenApp) {
            try { // Automatically open the app when there's only one search result
                if (isBangSearch.not()) appClickListener(appFilteredList[position])
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** Builds the search keys once per app list (the list is also edited in place when an app is hidden). */
    private fun searchIndexFor(prefs: Prefs): SearchIndex {
        val source = appsList
        searchIndex?.let { if (it.source === source && it.items.size == source.size) return it }
        val items = source.toList()
        val keys = items.map { app ->
            val label = prefs.getAppAlias(app.activityPackage).takeIf { it.isNotBlank() } ?: app.activityLabel
            AppSearch.SearchKey(label, app.activityPackage)
        }
        return SearchIndex(source, items, keys).also { searchIndex = it }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setAppList(appsList: MutableList<AppListItem>) {
        this.appsList = appsList
        searchIndex = null
        if (lastQuery.isNotBlank()) {
            // Keep showing results for the current query instead of the full list
            appFilter.filter(lastQuery)
            return
        }
        this.appFilteredList = appsList
        searching = false
        notifyDataSetChanged()
    }

    fun launchFirstInList() {
        if (appFilteredList.isNotEmpty())
            appClickListener(appFilteredList[0])
    }

    fun getFirstInList(): String? {
        if (appFilteredList.isNotEmpty())
            return appFilteredList[0].activityLabel
        return null
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        // Optionally clear icon to avoid wrong icons on recycled views
        holder.clearIcon()
    }

    inner class ViewHolder(
        itemView: AdapterAppDrawerBinding
    ) : RecyclerView.ViewHolder(itemView.root) {
        val appRenameEdit: EditText = itemView.appRenameEdit
        val appSaveRename: ImageView = itemView.appSaveRename
        val appSaveCancel: ImageView = itemView.appSaveCancel
        val appTagEdit: EditText = itemView.appTagEdit
        val appSaveTag: TextView = itemView.appSaveTag

        private val appRenameLayout: LinearLayout = itemView.appRenameLayout
        private val appTagLayout: LinearLayout = itemView.appTagLayout
        private val appTitle: TextView = itemView.appTitle
        private val appTitleFrame: FrameLayout = itemView.appTitleFrame

        @SuppressLint("RtlHardcoded", "NewApi")
        fun bind(
            appLabelGravity: Int,
            appListItem: AppListItem,
            iconCache: ConcurrentHashMap<String, Drawable>,
            iconLoadingScope: CoroutineScope,
            prefs: Prefs,
            searching: Boolean
        ) = with(itemView) {

            appRenameLayout.isVisible = false
            appTagLayout.isVisible = false

            val packageName = appListItem.activityPackage

            appRenameEdit.apply {
                val activityLabel = prefs.getAppAlias(appListItem.activityPackage).takeIf { it.isNotBlank() }
                    ?: appListItem.activityLabel

                text = Editable.Factory.getInstance().newEditable(activityLabel)
            }

            appTagEdit.apply {
                text = Editable.Factory.getInstance().newEditable(appListItem.customTag)
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable) {}
                    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                        appSaveTag.text = if (text.toString() == appListItem.customTag) getLocalizedString(R.string.cancel)
                        else getLocalizedString(R.string.tag)
                    }
                })
            }

            // ----------------------------
            // 5️⃣ App title
            appTitle.text = prefs.getAppAlias(appListItem.activityPackage).takeIf { it.isNotBlank() } ?: appListItem.activityLabel
            val params = appTitle.layoutParams as FrameLayout.LayoutParams
            params.gravity = appLabelGravity
            appTitle.layoutParams = params
            val padding = dp2px(resources, 24)
            appTitle.updatePadding(left = padding, right = padding)

            // ----------------------------
            // 6️⃣ Icon loading off main thread
            // Search results always show icons, even with app list icons turned off
            val placeholderIcon = AppCompatResources.getDrawable(context, R.drawable.ic_default_app)
            val cachedIcon = iconCache[packageName]
            // Tag the view with the package name so a late load for a recycled row is ignored
            appTitle.tag = packageName
            setAppTitleIcon(appTitle, cachedIcon ?: placeholderIcon, prefs, searching)

            if (cachedIcon == null && packageName.isNotBlank() &&
                (searching || prefs.iconPackAppList != Constants.IconPacks.Disabled)
            ) {

                iconLoadingScope.launch {
                    val icon = loadIcon(appListItem)

                    // 2. Update cache (Ensure iconCache is thread-safe, e.g., ConcurrentHashMap)
                    iconCache[packageName] = icon

                    // 3. ONLY update the UI if the view is still intended for THIS package
                    // This prevents the wrong icon from appearing after scrolling
                    if (appTitle.tag == packageName) {
                        setAppTitleIcon(appTitle, icon, prefs, this@AppDrawerAdapter.searching)
                    }
                }
            }

            // ----------------------------
            // 7️⃣ Click listeners
            appTitleFrame.apply {
                setOnClickListener { appClickListener(appListItem) }
                setOnLongClickListener {
                    if (flag == AppDrawerFlag.LaunchApp || flag == AppDrawerFlag.HiddenApps) {
                        showContextMenu(appListItem, appTitle, gravity, this@ViewHolder)
                    }
                    true
                }
            }
        }

        fun openInlineEditor(layout: View, edit: EditText) {
            layout.isVisible = true
            editingPosition = bindingAdapterPosition
            edit.imeOptions = EditorInfo.IME_ACTION_DONE
            edit.showKeyboard()
        }

        fun openRename(app: AppListItem) {
            appRenameEdit.hint = app.activityLabel
            openInlineEditor(appRenameLayout, appRenameEdit)
        }

        fun openTag(app: AppListItem) {
            appTagEdit.hint = app.activityLabel
            openInlineEditor(appTagLayout, appTagEdit)
        }

        // Helper to set icon on appTitle with correct size and alignment
        private fun setAppTitleIcon(appTitle: TextView, icon: Drawable?, prefs: Prefs, force: Boolean = false) {
            if (icon == null || (!force && prefs.iconPackAppList == Constants.IconPacks.Disabled)) {
                appTitle.setCompoundDrawables(null, null, null, null)
                return
            }
            val iconSize = (prefs.appSize * 1.4).toInt()
            val iconPadding = (iconSize / 1.2).toInt()
            icon.setBounds(
                0,
                0,
                ((iconSize * 1.6).toInt()),
                ((iconSize * 1.6).toInt())
            )
            when (prefs.drawerAlignment) {
                Constants.Gravity.Left -> {
                    appTitle.setCompoundDrawables(icon, null, null, null)
                    appTitle.compoundDrawablePadding = iconPadding
                }

                Constants.Gravity.Right -> {
                    appTitle.setCompoundDrawables(null, null, icon, null)
                    appTitle.compoundDrawablePadding = iconPadding
                }

                else -> if (force) {
                    appTitle.setCompoundDrawables(icon, null, null, null)
                    appTitle.compoundDrawablePadding = iconPadding
                } else {
                    appTitle.setCompoundDrawables(null, null, null, null)
                }
            }
        }

        // Clear icon when view is recycled
        fun clearIcon() {
            appTitle.setCompoundDrawables(null, null, null, null)
        }
    }

    /**
     * The frosted long-press pill for [app], anchored to [anchor]. [row] is the list row when the
     * press came from the list; rename and tag edit inline in that row, so they need it.
     */
    fun showContextMenu(app: AppListItem, anchor: View, anchorGravity: Int, row: ViewHolder?) {
        contextMenu?.dismiss(animate = false)
        val drawer = fragment as? AppDrawerFragment ?: return
        val host = drawer.contextMenuHost() ?: return
        val packageName = app.activityPackage
        // Index 0 was mLauncher's pin toggle; the pinned row's Pin is always offered instead
        val defaults = "0011111"
        val flags = prefs.getMenuFlags("CONTEXT_MENU_FLAGS", defaults)
        fun enabled(i: Int) = flags.getOrElse(i) { defaults[i] == '1' }

        val isLocked = prefs.lockedApps.contains(packageName)
        val isHidden = flag == AppDrawerFlag.HiddenApps
        val actions = buildList {
            if (flag == AppDrawerFlag.LaunchApp) {
                val isPinned = drawer.isPinned(app)
                add(
                    AppContextMenu.Action(
                        if (isPinned) R.drawable.pin_off else R.drawable.pin,
                        getLocalizedString(if (isPinned) R.string.unpin else R.string.pin)
                    ) { drawer.togglePin(app) })
            }
            if (enabled(1)) add(
                AppContextMenu.Action(
                    if (isLocked) R.drawable.padlock else R.drawable.padlock_off,
                    getLocalizedString(if (isLocked) R.string.unlock else R.string.lock)
                ) { toggleLock(app) })
            if (enabled(2)) add(
                AppContextMenu.Action(
                    if (isHidden) R.drawable.visibility else R.drawable.visibility_off,
                    getLocalizedString(if (isHidden) R.string.show else R.string.hide)
                ) { hideApp(app) })
            if (row != null && enabled(3)) add(AppContextMenu.Action(R.drawable.ic_rename, getLocalizedString(R.string.rename)) {
                row.openRename(app)
            })
            if (row != null && enabled(4)) add(AppContextMenu.Action(R.drawable.ic_tag, getLocalizedString(R.string.tag)) {
                row.openTag(app)
            })
            if (enabled(5)) add(AppContextMenu.Action(R.drawable.ic_info, getLocalizedString(R.string.info)) {
                appInfoListener(app)
            })
            if (enabled(6)) add(
                AppContextMenu.Action(
                    R.drawable.ic_delete,
                    getLocalizedString(R.string.app_menu_uninstall),
                    destructive = true,
                    dimmed = context.isSystemApp(packageName)
                ) { appDeleteListener(app) })
        }
        if (actions.isEmpty()) return

        val menu = AppContextMenu(host, anchor, anchorGravity, actions) { contextMenu = null }
        contextMenu = menu
        menu.show()
    }

    private fun hideApp(app: AppListItem) {
        AppLogger.d("AppListDebug", "❌ Hide clicked for ${app.activityLabel} (${app.activityPackage})")
        val pos = appFilteredList.indexOf(app)
        if (pos >= 0) {
            appFilteredList.removeAt(pos)
            notifyItemRemoved(pos)
        }
        appsList.remove(app)
        appHideListener(flag, app)
    }

    private fun toggleLock(app: AppListItem) {
        val packageName = app.activityPackage
        val locked = prefs.lockedApps.toMutableSet()
        if (!locked.contains(packageName)) {
            locked.add(packageName)
            prefs.lockedApps = locked
            return
        }
        // Unlocking needs the user to authenticate first
        biometricHelper.startBiometricAuth(app, object : BiometricHelper.CallbackApp {
            override fun onAuthenticationSucceeded(appListItem: AppListItem) {
                prefs.lockedApps = prefs.lockedApps.toMutableSet().apply { remove(packageName) }
            }

            override fun onAuthenticationFailed() {
                AppLogger.e("Authentication", getLocalizedString(R.string.text_authentication_failed))
            }

            override fun onAuthenticationError(errorCode: Int, errorMessage: CharSequence?) {
                val msg = when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED -> getLocalizedString(R.string.text_authentication_cancel)
                    else -> getLocalizedString(R.string.text_authentication_error).format(errorMessage, errorCode)
                }
                AppLogger.e("Authentication", msg)
            }
        })
    }

    /** Same icon as the list row (icon pack, then generated, then system), sharing its cache. */
    fun bindIcon(app: AppListItem, target: ImageView) {
        val key = app.activityPackage
        target.tag = key
        // A drawable can only have one set of bounds; the list row's TextView owns the cached one
        fun show(icon: Drawable) = target.setImageDrawable(icon.constantState?.newDrawable(context.resources)?.mutate() ?: icon)
        iconCache[key]?.let { show(it); return }
        target.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_default_app))
        iconLoadingScope.launch {
            val icon = loadIcon(app)
            iconCache[key] = icon
            if (target.tag == key) show(icon)
        }
    }

    fun closeOpenedMenu() {
        contextMenu?.dismiss()
        if (editingPosition != RecyclerView.NO_POSITION) {
            val pos = editingPosition
            editingPosition = RecyclerView.NO_POSITION
            notifyItemChanged(pos)
        }
    }

    private suspend fun loadIcon(app: AppListItem): Drawable = withContext(Dispatchers.IO) {
        val icon = getSafeAppIcon(
            context = context,
            packageName = app.activityPackage,
            useIconPack = prefs.customIconPackAppList.isNotEmpty() &&
                    prefs.iconPackAppList == Constants.IconPacks.Custom,
            iconPackTarget = IconCacheTarget.APP_LIST,
            activityClass = app.activityClass
        )
        getSystemIcons(context, prefs, IconCacheTarget.APP_LIST, icon) ?: icon
    }
}
