/**
 * The view for the list of all the installed applications.
 */

package com.github.codeworkscreativehub.mlauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.UserHandle
import android.os.UserManager
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.codeworkscreativehub.common.AppLogger
import com.github.codeworkscreativehub.common.getLocalizedString
import com.github.codeworkscreativehub.common.hasSoftKeyboard
import com.github.codeworkscreativehub.common.isSystemApp
import com.github.codeworkscreativehub.common.searchCustomSearchEngine
import com.github.codeworkscreativehub.common.searchOnPlayStore
import com.github.codeworkscreativehub.common.showShortToast
import com.github.codeworkscreativehub.mlauncher.MainViewModel
import com.github.codeworkscreativehub.mlauncher.R
import com.github.codeworkscreativehub.mlauncher.data.AppCategory
import com.github.codeworkscreativehub.mlauncher.data.AppListItem
import com.github.codeworkscreativehub.mlauncher.data.Constants
import com.github.codeworkscreativehub.mlauncher.data.Constants.AppDrawerFlag
import com.github.codeworkscreativehub.mlauncher.data.Prefs
import com.github.codeworkscreativehub.mlauncher.databinding.FragmentAppDrawerBinding
import com.github.codeworkscreativehub.mlauncher.helper.ChineseSortHelper
import com.github.codeworkscreativehub.mlauncher.helper.DrawerBackground
import com.github.codeworkscreativehub.mlauncher.helper.emptyString
import com.github.codeworkscreativehub.mlauncher.helper.openAppInfo
import com.github.codeworkscreativehub.mlauncher.ui.adapter.AppDrawerAdapter
import com.github.codeworkscreativehub.mlauncher.ui.adapter.PinnedAppsAdapter
import com.github.codeworkscreativehub.mlauncher.ui.components.AppContextMenu
import com.github.codeworkscreativehub.mlauncher.ui.components.FrostedPillDrawable
import com.github.codeworkscreativehub.mlauncher.ui.components.VerticalDragLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Implemented by a parent that hosts the drawer as an overlay (the home screen).
 * Without a host the drawer runs as a normal navigation destination (app pickers, hidden apps).
 */
interface DrawerHost {
    fun closeDrawer(animate: Boolean = true)
    fun isDrawerFullyOpen(): Boolean
    fun onDrawerDragStart()
    fun onDrawerDrag(dy: Float)
    fun onDrawerDragEnd(velocityY: Float)
}

class AppDrawerFragment : BaseFragment() {

    companion object {
        /** The always-alive drawer that the home screen slides up. */
        fun newEmbedded() = AppDrawerFragment().apply {
            arguments = Bundle().apply {
                putString("flag", AppDrawerFlag.LaunchApp.toString())
                putString("profileType", "SYSTEM")
            }
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var appsAdapter: AppDrawerAdapter
    private lateinit var pinnedAdapter: PinnedAppsAdapter

    /** Rows above the first app: the pinned row when it shows. */
    private val headerCount: Int get() = if (::pinnedAdapter.isInitialized) pinnedAdapter.itemCount else 0

    private var _binding: FragmentAppDrawerBinding? = null
    private val binding get() = _binding!!

    private val host: DrawerHost? get() = parentFragment as? DrawerHost
    private val isEmbedded: Boolean get() = host != null

    private var flag = AppDrawerFlag.LaunchApp
    private var profileFilter: String? = "SYSTEM"
    private var forceListRefresh = false
    private var backgroundJob: Job? = null
    private var shownBackground: Bitmap? = null
    private var pillBackground: FrostedPillDrawable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppDrawerBinding.inflate(inflater, container, false)
        prefs = Prefs(requireContext())
        return binding.root
    }

    @SuppressLint("RtlHardcoded")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (prefs.firstSettingsOpen) {
            prefs.firstSettingsOpen = false
        }

        val flagString = arguments?.getString("flag", AppDrawerFlag.LaunchApp.toString())
            ?: AppDrawerFlag.LaunchApp.toString()
        flag = runCatching { AppDrawerFlag.valueOf(flagString) }.getOrDefault(AppDrawerFlag.LaunchApp)
        val n = arguments?.getInt("n", 0) ?: 0
        profileFilter = arguments?.getString("profileType", "SYSTEM") ?: "SYSTEM"

        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        setupInsets()
        setupFrostedPill()
        setupSidebarSide()
        setupDragToClose()

        binding.mainLayout.setOnClickListener {
            if (::appsAdapter.isInitialized) appsAdapter.closeOpenedMenu()
        }

        // Retrieve the letter key code from arguments
        val letterKeyCode = arguments?.getInt("letterKeyCode", -1) ?: -1
        if (letterKeyCode != -1) {
            convertKeyCodeToLetter(letterKeyCode)?.let { setSearchQuery(it.toString()) }
        }

        if (flag == AppDrawerFlag.SetHomeApp) setupClearHomeButton(n)
        setupPickerTitle()

        viewModel.appScrollMap.observe(viewLifecycleOwner) { appMap ->
            binding.azSidebar.onLetterSelected = { section ->
                appMap[section]?.let { index -> jumpToSection(binding.appsRecyclerView, index + headerCount) }
            }
        }

        val gravity = when (prefs.drawerAlignment) {
            Constants.Gravity.Left -> Gravity.LEFT
            Constants.Gravity.Center -> Gravity.CENTER
            Constants.Gravity.Right -> Gravity.RIGHT
        }

        val appAdapter = AppDrawerAdapter(
            requireContext(),
            this,
            flag,
            gravity,
            appClickListener(viewModel, flag, n),
            appDeleteListener(),
            this.appRenameListener(),
            this.appTagListener(),
            appShowHideListener(),
            appInfoListener()
        )
        appsAdapter = appAdapter

        pinnedAdapter = PinnedAppsAdapter(
            onClick = appClickListener(viewModel, flag, n),
            onLongClick = { app, slot -> appAdapter.showContextMenu(app, slot, Gravity.CENTER_HORIZONTAL, null) },
            bindIcon = appAdapter::bindIcon,
            labelOf = { app -> prefs.getAppAlias(app.activityPackage).takeIf { it.isNotBlank() } ?: app.activityLabel },
        )

        val searchTextView = binding.search.findViewById<TextView>(R.id.search_src_text)
        // The pill is 56dp tall; very large list text sizes don't fit in it
        searchTextView.textSize = prefs.appSize.toFloat().coerceAtMost(20f)
        searchTextView.setTextColor(prefs.appColor)
        searchTextView.setHintTextColor(ColorUtils.setAlphaComponent(prefs.appColor, 0x99))

        initViewModel(viewModel, appAdapter)

        val layoutManager = LinearLayoutManager(requireContext())
        binding.appsRecyclerView.apply {
            this.layoutManager = layoutManager
            // No item animations: rows must never be left mid-fade while the drawer is hidden
            itemAnimator = null
            // Rows fade out exactly where they meet the status bar and the search pill
            edgeFadeLength = (28 * resources.displayMetrics.density).toInt()
            // The pinned row is a header that scrolls with the list
            adapter = ConcatAdapter(pinnedAdapter, appAdapter)
        }

        // Search results read top-down from the top of the list, best match first
        appAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                val b = _binding ?: return
                if (!b.search.query.isNullOrBlank()) b.appsRecyclerView.scrollToPosition(0)
            }
        })

        var lastSectionLetter: String? = null

        binding.appsRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val itemCount = lm.itemCount
                if (itemCount == 0) return

                val firstVisible = lm.findFirstVisibleItemPosition()
                val lastVisible = lm.findLastVisibleItemPosition()
                if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION) return

                val position = when {
                    firstVisible <= 1 -> firstVisible
                    lastVisible >= itemCount - 2 -> lastVisible
                    else -> (firstVisible + lastVisible) / 2
                }.coerceIn(0, itemCount - 1)

                val item = appAdapter.getItemAt((position - headerCount).coerceAtLeast(0)) ?: return

                val sectionLetter = when (item.category) {
                    AppCategory.PINNED -> "★"
                    else -> {
                        ChineseSortHelper.sectionKey(item.activityLabel, prefs.appLanguage)
                            ?: item.activityLabel.firstOrNull()?.uppercaseChar()?.toString()
                            ?: return
                    }
                }

                // Skip redundant updates
                if (sectionLetter == lastSectionLetter) return
                lastSectionLetter = sectionLetter

                binding.azSidebar.setSelectedLetter(sectionLetter)
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                // Only a real drag closes it; settling and idle are not the user touching the list
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) appAdapter.closeOpenedMenu()
                // Scrolling the list means browsing, not typing
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && requireContext().hasSoftKeyboard()) {
                    binding.search.hideKeyboard()
                }
            }
        })

        if (prefs.hideSearchView) {
            binding.searchContainer.isVisible = false
        } else {
            val appListButtonFlags = prefs.getMenuFlags("APPLIST_BUTTON_FLAGS", "00")
            when (flag) {
                AppDrawerFlag.LaunchApp -> {
                    setupProfileButtons(appAdapter)

                    binding.internetSearch.apply {
                        isVisible = appListButtonFlags.getOrElse(0) { false }
                        setOnClickListener {
                            val query = binding.search.query.toString().trim()
                            if (query.isEmpty()) return@setOnClickListener
                            requireContext().searchCustomSearchEngine(query, prefs)
                        }
                    }
                }

                AppDrawerFlag.HiddenApps -> {
                    binding.search.queryHint = getLocalizedString(R.string.hidden_apps)
                }

                AppDrawerFlag.SetHomeApp -> {
                    binding.search.queryHint = getLocalizedString(R.string.please_select_app)
                }

                else -> {}
            }
        }

        binding.listEmptyHint.text = applyTextColor(getLocalizedString(R.string.drawer_list_empty_hint), prefs.appColor)

        binding.search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                val searchQuery = query?.trim()

                if (!searchQuery.isNullOrEmpty()) {

                    // Hashtag shortcut
                    if (searchQuery.startsWith("#")) return true

                    val firstItem = appAdapter.getFirstInList()
                    if (firstItem.equals(searchQuery, ignoreCase = true) || prefs.openAppOnEnter) {
                        appAdapter.launchFirstInList()
                    } else {
                        requireContext().searchOnPlayStore(searchQuery)
                    }
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (flag == AppDrawerFlag.SetHomeApp) {
                    binding.clearHomeButton.isVisible = newText.isNullOrEmpty() && hasHomeAppToClear
                }

                val searching = !newText.isNullOrBlank()
                // Section letters only make sense for the full, alphabetical list
                binding.sidebarContainer.isVisible = prefs.showAZSidebar && !searching
                showingPinned { pinnedAdapter.setHidden(searching) }

                newText?.let { appAdapter.search(it.trim()) }
                return false
            }
        })

        refreshBackground()
    }

    // ---------------------------------------------------------------- layout

    /**
     * The list fills the panel and scrolls under the status bar and the search pill. Paddings
     * keep its first/last rows clear of them; the pill rides on the nav bar or the keyboard,
     * following the IME animation frame by frame.
     */
    private fun setupInsets() {
        val panel = binding.mainLayout
        val density = resources.displayMetrics.density
        val gap = (8 * density).toInt()
        val pillMargin = (12 * density).toInt()
        val pillHeight = (56 * density).toInt()
        var statusTop = 0
        var imeAnimating = false

        fun applyTop() {
            val b = _binding ?: return
            b.drawerHeader.updatePadding(top = statusTop)
            val headerBottom = if (b.appDrawerTip.isVisible || b.pickerTitle.isVisible || b.clearHomeButton.isVisible) b.drawerHeader.height else statusTop
            val top = maxOf(statusTop, headerBottom) + gap
            if (b.appsRecyclerView.paddingTop != top) b.appsRecyclerView.updatePadding(top = top)
            (b.sidebarContainer.layoutParams as ViewGroup.MarginLayoutParams).let {
                if (it.topMargin != top) {
                    it.topMargin = top
                    b.sidebarContainer.layoutParams = it
                }
            }
        }

        fun applyBottom(insets: WindowInsetsCompat) {
            val b = _binding ?: return
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val inset = maxOf(ime, nav)
            val listBottom = if (b.searchContainer.isVisible) {
                val params = b.searchContainer.layoutParams as ViewGroup.MarginLayoutParams
                if (params.bottomMargin != inset + pillMargin) {
                    params.bottomMargin = inset + pillMargin
                    b.searchContainer.layoutParams = params
                }
                inset + pillMargin + pillHeight + gap
            } else {
                inset + gap
            }
            if (b.appsRecyclerView.paddingBottom != listBottom) b.appsRecyclerView.updatePadding(bottom = listBottom)
            (b.sidebarContainer.layoutParams as ViewGroup.MarginLayoutParams).let {
                if (it.bottomMargin != listBottom) {
                    it.bottomMargin = listBottom
                    b.sidebarContainer.layoutParams = it
                }
            }
        }

        binding.drawerHeader.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyTop() }

        ViewCompat.setOnApplyWindowInsetsListener(panel) { _, insets ->
            statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
            applyTop()
            if (!imeAnimating) applyBottom(insets)
            insets
        }

        ViewCompat.setWindowInsetsAnimationCallback(
            panel,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) imeAnimating = true
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    applyBottom(insets)
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        imeAnimating = false
                        ViewCompat.requestApplyInsets(panel)
                    }
                }
            }
        )
        ViewCompat.requestApplyInsets(panel)
    }

    /** The search pill shows a stronger blur of the backdrop behind it, lightly tinted. */
    private fun setupFrostedPill() {
        val ctx = requireContext()
        val drawable = FrostedPillDrawable(
            pill = binding.searchContainer,
            backdrop = binding.drawerBlur,
            tintWithImage = ContextCompat.getColor(ctx, R.color.drawer_search_tint),
            tintWithoutImage = ContextCompat.getColor(ctx, R.color.drawer_search_bg),
        )
        pillBackground = drawable
        binding.searchContainer.background = drawable
        // Keyboard pushes it up, panel slides: resample what's behind it
        binding.searchContainer.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.invalidate() }
    }

    /** Sidebar goes on the side opposite the text, and the list keeps clear of it. */
    private fun setupSidebarSide() {
        val params = binding.sidebarContainer.layoutParams as FrameLayout.LayoutParams
        val sidebarOnEnd = prefs.drawerAlignment == Constants.Gravity.Left
        params.gravity = if (sidebarOnEnd) Gravity.END else Gravity.START
        binding.sidebarContainer.layoutParams = params

        if (prefs.showAZSidebar) {
            val gap = (56 * resources.displayMetrics.density).toInt()
            binding.appsRecyclerView.updatePadding(
                left = if (sidebarOnEnd) 0 else gap,
                right = if (sidebarOnEnd) gap else 0,
            )
        }
    }

    /**
     * Pulling down while the list is at the top closes the drawer, following the finger when the
     * drawer is an overlay. As a navigation destination it just goes back.
     */
    private fun setupDragToClose() {
        binding.drawerRoot.callback = object : VerticalDragLayout.Callback {
            override fun shouldStartDrag(downX: Float, downY: Float, dy: Float): Boolean {
                if (dy <= 0) return false
                if (binding.appsRecyclerView.canScrollVertically(-1)) return false
                if (isInside(binding.sidebarContainer, downX, downY)) return false
                val h = host
                if (h == null) {
                    findNavController().popBackStack()
                    return false
                }
                return h.isDrawerFullyOpen()
            }

            override fun onDragStart() {
                if (requireContext().hasSoftKeyboard()) binding.search.hideKeyboard()
                if (::appsAdapter.isInitialized) appsAdapter.closeOpenedMenu()
                host?.onDrawerDragStart()
            }

            override fun onDrag(dy: Float) {
                host?.onDrawerDrag(dy)
            }

            override fun onDragEnd(velocityY: Float) {
                host?.onDrawerDragEnd(velocityY)
            }
        }
    }

    private fun isInside(v: View, x: Float, y: Float): Boolean {
        if (!v.isVisible) return false
        val loc = IntArray(2)
        val rootLoc = IntArray(2)
        v.getLocationInWindow(loc)
        binding.drawerRoot.getLocationInWindow(rootLoc)
        val left = loc[0] - rootLoc[0]
        val top = loc[1] - rootLoc[1]
        return x >= left && x <= left + v.width && y >= top && y <= top + v.height
    }

    // ---------------------------------------------------------- overlay hooks

    /** Called by the host every frame: [progress] 0 = hidden, 1 = fully open. */
    fun applyReveal(progress: Float, travel: Int) {
        val b = _binding ?: return
        b.mainLayout.translationY = (1f - progress) * travel
        b.drawerBackdrop.alpha = progress
        // The pill moved relative to the fixed backdrop it samples
        b.searchContainer.invalidate()
        // The backdrop is static: cache it in a GPU layer while it fades so frames stay cheap
        val layer = if (progress > 0f && progress < 1f) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        if (b.drawerBackdrop.layerType != layer) b.drawerBackdrop.setLayerType(layer, null)
    }

    /** False when the list view is detached, or has data but laid out no rows. */
    fun isListHealthy(): Boolean {
        val rv = _binding?.appsRecyclerView ?: return false
        if (!rv.isAttachedToWindow || rv.adapter == null) return false
        val items = rv.adapter?.itemCount ?: 0
        return items == 0 || rv.childCount > 0 || rv.isLayoutRequested
    }

    @SuppressLint("NotifyDataSetChanged")
    fun onDrawerOpening() {
        if (::viewModel.isInitialized) viewModel.getAppList()
        refreshPinnedRow()
        val rv = _binding?.appsRecyclerView ?: return
        // The list was filled while the drawer was hidden; make sure its rows are laid out now
        if (::appsAdapter.isInitialized && appsAdapter.itemCount > 0 && rv.childCount == 0) {
            appsAdapter.notifyDataSetChanged()
        }
        rv.requestLayout()
    }

    fun onDrawerOpened() {
        val b = _binding ?: return
        if (requireContext().hasSoftKeyboard()) b.search.showKeyboard(delayMs = 0)
    }

    fun onDrawerClosing() {
        val b = _binding ?: return
        if (requireContext().hasSoftKeyboard()) b.search.hideKeyboard()
        if (::appsAdapter.isInitialized) appsAdapter.closeOpenedMenu()
    }

    /** Reset to a fresh drawer: empty search, list at the top. */
    fun onDrawerClosed() {
        val b = _binding ?: return
        b.search.hideKeyboard()
        if (b.search.query.isNotEmpty()) b.search.setQuery("", false)
        b.appsRecyclerView.stopScroll()
        b.appsRecyclerView.scrollToPosition(0)
    }

    fun setSearchQuery(text: String) {
        val b = _binding ?: return
        if (prefs.hideSearchView) return
        b.search.setQuery(text, false)
        b.search.findViewById<TextView>(R.id.search_src_text)?.let {
            it.requestFocus()
            (it as? android.widget.EditText)?.setSelection(it.text.length)
        }
    }

    /** Where the long-press menu draws: over the whole drawer, frosting the same backdrop as the search pill. */
    fun contextMenuHost(): AppContextMenu.Host? {
        val b = _binding ?: return null
        return AppContextMenu.Host(
            container = b.drawerRoot,
            backdrop = b.drawerBlur,
            frosted = DrawerBackground.frostedFor(shownBackground),
            backDispatcher = requireActivity().onBackPressedDispatcher,
        )
    }

    // ------------------------------------------------------------ pinned row

    fun isPinned(app: AppListItem) = app.pinKey in prefs.pinnedRow

    fun togglePin(app: AppListItem) {
        val keys = prefs.pinnedRow.toMutableList()
        if (!keys.remove(app.pinKey)) {
            if (::pinnedAdapter.isInitialized && pinnedAdapter.apps.size >= PinnedAppsAdapter.MAX) {
                showShortToast(getLocalizedString(R.string.pinned_row_full))
                return
            }
            keys.add(app.pinKey)
        }
        prefs.pinnedRow = keys
        refreshPinnedRow()
    }

    /** Re-reads the pins against the installed apps. Only the main launcher drawer has the row. */
    private fun refreshPinnedRow() {
        if (_binding == null || !::pinnedAdapter.isInitialized || flag != AppDrawerFlag.LaunchApp) return
        val raw = viewModel.appList.value.orEmpty()
        if (raw.isEmpty()) return
        migrateOldPins(raw)
        val hidden = prefs.hiddenApps
        val byKey = raw.filterNot { isHidden(it, hidden) }.associateBy { it.pinKey }
        val pinned = prefs.pinnedRow.mapNotNull { byKey[it] }
        showingPinned { pinnedAdapter.submit(pinned) }
    }

    /** mLauncher pinned by package name into a ★ section; those pins move to the row once. */
    private fun migrateOldPins(raw: List<AppListItem>) {
        if (prefs.hasPinnedRow) return
        val old = prefs.pinnedApps
        prefs.pinnedRow = old.mapNotNull { pkg -> raw.firstOrNull { it.activityPackage == pkg }?.pinKey }
            .distinct().take(PinnedAppsAdapter.MAX)
        if (old.isNotEmpty()) prefs.pinnedApps = emptySet()
    }

    /** A header inserted above a list sitting at the top would land off-screen; keep it in view. */
    private fun showingPinned(change: () -> Unit) {
        val rv = _binding?.appsRecyclerView
        val atTop = rv != null && !rv.canScrollVertically(-1)
        change()
        if (atTop) rv?.scrollToPosition(0)
    }

    // ------------------------------------------------------------ background

    /** Blurred wallpaper copy behind the drawer; rendered once, then reused from cache. */
    private fun refreshBackground() {
        val b = _binding ?: return
        val radius = prefs.drawerBlurRadius
        val cached = DrawerBackground.cached(requireContext(), radius)
        if (cached != null) {
            showBackground(cached)
            return
        }
        if (backgroundJob?.isActive == true) return
        val appContext = requireContext().applicationContext
        val activityContext = requireActivity()
        backgroundJob = viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                DrawerBackground.loadBlurred(activityContext, radius)
            }
            if (_binding == null) return@launch
            showBackground(bmp)
            AppLogger.d("AppDrawer", "Drawer background: ${DrawerBackground.currentSource(appContext)}")
        }
        // Until the blur is ready, a solid scrim keeps the text readable
        if (shownBackground == null) showBackground(null)
    }

    private fun showBackground(bmp: Bitmap?) {
        val b = _binding ?: return
        if (bmp != null && bmp === shownBackground) return
        shownBackground = bmp
        b.drawerBlur.setImageBitmap(bmp)
        b.drawerBlur.isVisible = bmp != null
        pillBackground?.setFrosted(DrawerBackground.frostedFor(bmp))
        b.drawerScrim.setBackgroundResource(if (bmp != null) R.color.drawer_scrim else R.color.drawer_scrim_solid)
    }

    private fun setupProfileButtons(appAdapter: AppDrawerAdapter) {
        fun updateProfileUI() {
            val current = profileFilter ?: "SYSTEM"
            binding.workApps.isVisible = prefs.getProfileCounter("WORK") > 0 && current != "WORK"
            binding.systemApps.isVisible = prefs.getProfileCounter("SYSTEM") > 0 && current != "SYSTEM"
            binding.search.queryHint = when (current) {
                "WORK" -> getLocalizedString(R.string.show_work_apps)
                else -> getLocalizedString(R.string.show_apps)
            }
        }

        fun onProfileClicked(newType: String) {
            profileFilter = newType
            binding.search.setQuery("", false)
            viewModel.appList.value?.let { populateFromRaw(it, appAdapter, force = true) }
            updateProfileUI()
        }

        updateProfileUI()
        binding.workApps.setOnClickListener { onProfileClicked("WORK") }
        binding.systemApps.setOnClickListener { onProfileClicked("SYSTEM") }
    }

    private fun applyTextColor(text: String, color: Int): SpannableString {
        val spannableString = SpannableString(text)
        spannableString.setSpan(
            ForegroundColorSpan(color),
            0,
            text.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannableString
    }

    private fun convertKeyCodeToLetter(keyCode: Int): Char? =
        if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) 'A' + (keyCode - KeyEvent.KEYCODE_A) else null

    private fun initViewModel(viewModel: MainViewModel, appAdapter: AppDrawerAdapter) {
        // Hidden apps screen
        viewModel.hiddenApps.observe(viewLifecycleOwner) { list ->
            if (flag != AppDrawerFlag.HiddenApps || list == null || list == appAdapter.appsList) return@observe
            binding.listEmptyHint.isVisible = list.isEmpty()
            binding.sidebarContainer.isVisible = prefs.showAZSidebar
            populateAppList(list, appAdapter)
        }

        // Everything else
        viewModel.appList.observe(viewLifecycleOwner) { rawAppList ->
            if (flag == AppDrawerFlag.HiddenApps || rawAppList == null) return@observe
            refreshPinnedRow()
            populateFromRaw(rawAppList, appAdapter, force = false)
        }

        viewModel.firstOpen.observe(viewLifecycleOwner) {
            binding.appDrawerTip.isVisible = it
        }
    }

    private fun populateFromRaw(rawAppList: List<AppListItem>, appAdapter: AppDrawerAdapter, force: Boolean) {
        val appsByProfile = rawAppList.groupBy { it.profileType }
        val allProfiles = listOf("SYSTEM", "WORK", "USER")
        allProfiles.forEach { profile ->
            prefs.setProfileCounter(profile, appsByProfile[profile]?.size ?: 0)
        }

        // The view model's list may include hidden apps (it is shared with the app pickers)
        val hidden = prefs.hiddenApps
        val filter = profileFilter
        val mergedList = allProfiles.flatMap { profile ->
            if (filter == null || filter.equals(profile, true)) appsByProfile[profile].orEmpty() else emptyList()
        }.filter { flag != AppDrawerFlag.LaunchApp || !isHidden(it, hidden) }

        if (!force && !forceListRefresh && mergedList == appAdapter.appsList) return
        forceListRefresh = false

        AppLogger.d("AppMerge", "Showing ${mergedList.size} apps")
        binding.listEmptyHint.isVisible = mergedList.isEmpty()
        binding.sidebarContainer.isVisible = prefs.showAZSidebar && binding.search.query.isNullOrBlank()
        populateAppList(mergedList, appAdapter)
    }

    private fun isHidden(app: AppListItem, hidden: Set<String>): Boolean {
        if (hidden.isEmpty()) return false
        val key = "${app.activityPackage}|${app.activityClass}|${app.user.hashCode()}"
        return app.activityPackage in hidden || key in hidden || "${app.activityPackage}|${key.hashCode()}" in hidden
    }

    override fun onResume() {
        super.onResume()
        refreshBackground()
        // As an overlay the host decides when the keyboard shows (once the drawer is open)
        if (!isEmbedded && requireContext().hasSoftKeyboard()) {
            // Wait out the drawer_enter animation so the IME doesn't jolt it
            binding.search.showKeyboard(delayMs = 300)
        }
    }

    override fun onStop() {
        super.onStop()
        if (requireContext().hasSoftKeyboard()) {
            binding.search.hideKeyboard()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        backgroundJob?.cancel()
        shownBackground = null
        pillBackground = null
        _binding = null
    }

    private fun View.showKeyboard(delayMs: Long = 100) {
        if (!prefs.autoShowKeyboard) return
        if (prefs.hideSearchView) return

        val searchTextView = binding.search.findViewById<TextView>(R.id.search_src_text)
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        searchTextView.postDelayed({
            if (_binding == null) return@postDelayed
            searchTextView.requestFocus()
            imm.showSoftInput(searchTextView, 0)
        }, delayMs)
    }

    private fun View.hideKeyboard() {
        val imm: InputMethodManager? =
            context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        imm?.hideSoftInputFromWindow(windowToken, 0)
        this.clearFocus()
    }

    /** Leave the drawer: slide the overlay away, or pop the navigation destination. */
    private fun dismiss(toHome: Boolean, animate: Boolean = true) {
        val h = host
        if (h != null) {
            h.closeDrawer(animate)
            return
        }
        if (!isAdded) return
        if (toHome) findNavController().popBackStack(R.id.mainFragment, false)
        else findNavController().popBackStack()
    }

    private fun populateAppList(apps: List<AppListItem>, appAdapter: AppDrawerAdapter) {
        appAdapter.setAppList(apps.toMutableList())

        // ✅ ENABLE dynamic AZ letters
        updateAZSidebarForApps(apps)
    }

    private fun appClickListener(
        viewModel: MainViewModel,
        flag: AppDrawerFlag,
        n: Int = 0
    ): (appListItem: AppListItem) -> Unit = { appModel ->
        viewModel.selectedApp(this, appModel, flag, n)
        when {
            // The launched app covers the drawer; MainActivity resets it once we're in the background
            isEmbedded -> {}
            flag == AppDrawerFlag.LaunchApp || flag == AppDrawerFlag.HiddenApps -> dismiss(toHome = true)
            else -> dismiss(toHome = false)
        }
    }

    private fun appDeleteListener(): (appListItem: AppListItem) -> Unit = { appModel ->
        if (requireContext().isSystemApp(appModel.activityPackage))
            showShortToast(getLocalizedString(R.string.can_not_delete_system_apps))
        else {
            val appPackage = appModel.activityPackage
            val intent = Intent(Intent.ACTION_DELETE)
            intent.data = "package:$appPackage".toUri()
            requireContext().startActivity(intent)
        }

    }

    private fun appRenameListener(): (appPackage: String, appAlias: String) -> Unit = { appPackage, appAlias ->
        val prefs = Prefs(requireContext())
        prefs.setAppAlias(appPackage, appAlias)
        // Re-sort with the new name next time the list is shown
        forceListRefresh = true
        viewModel.getAppList()
        dismiss(toHome = false)
    }

    private fun appTagListener(): (appPackage: String, appTag: String, appUser: UserHandle) -> Unit = { appPackage, appTag, appUser ->
        val prefs = Prefs(requireContext())
        prefs.setAppTag(appPackage, appTag, appUser)
        forceListRefresh = true
        viewModel.getAppList()
        dismiss(toHome = false)
    }

    private fun appShowHideListener(): (flag: AppDrawerFlag, appListItem: AppListItem) -> Unit = { flag, appModel ->
        val prefs = Prefs(requireContext())
        val newSet = mutableSetOf<String>()
        newSet.addAll(prefs.hiddenApps)

        if (flag == AppDrawerFlag.HiddenApps) {
            newSet.remove(appModel.activityPackage) // for backward compatibility
            newSet.remove(appModel.activityPackage + "|" + appModel.user.hashCode()) // for backward compatibility
            newSet.remove(appModel.activityPackage + "|" + appModel.activityClass + "|" + appModel.user.hashCode())
        } else {
            newSet.add(appModel.activityPackage + "|" + appModel.activityClass + "|" + appModel.user.hashCode())
        }

        prefs.hiddenApps = newSet
        refreshPinnedRow()

        if (newSet.isEmpty() && flag == AppDrawerFlag.HiddenApps) dismiss(toHome = false)
    }

    private fun appInfoListener(): (appListItem: AppListItem) -> Unit = { appModel ->
        openAppInfo(
            requireContext(),
            appModel.user,
            appModel.activityPackage
        )
        if (!isEmbedded) dismiss(toHome = true)
    }

    /** Instant jump that puts the section's first row at the top of the list. */
    private fun jumpToSection(recyclerView: RecyclerView, index: Int) {
        val count = recyclerView.adapter?.itemCount ?: return
        if (index !in 0 until count) return
        recyclerView.stopScroll()
        // The sidebar is only shown for the full list, which is laid out top-down
        (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(index, 0)
            ?: recyclerView.scrollToPosition(index)
    }

    private fun updateAZSidebarForApps(apps: List<AppListItem>) {
        val letters = mutableSetOf<String>()

        apps.forEach { item ->
            when (item.category) {
                AppCategory.PINNED -> letters.add("★")
                else -> {
                    val sectionLetter = ChineseSortHelper.sectionKey(item.activityLabel, prefs.appLanguage)
                        ?: item.activityLabel.firstOrNull()?.uppercaseChar()?.toString()
                    sectionLetter?.let { letters.add(it) }
                }
            }
        }

        binding.azSidebar.setAvailableLetters(letters)
    }

    private fun createClearApp(): AppListItem {
        val userManager = requireContext().getSystemService(Context.USER_SERVICE) as UserManager
        return AppListItem(
            activityLabel = "Clear",
            activityPackage = emptyString(),
            activityClass = emptyString(),
            user = userManager.userProfiles[0],
            profileType = "SYSTEM",
            customTag = emptyString(),
            category = AppCategory.REGULAR
        )
    }

    private var hasHomeAppToClear = false

    private fun setupClearHomeButton(position: Int) {
        val currentApp = prefs.getHomeAppModel(position)
        val hasCurrentApp =
            currentApp.activityPackage.isNotEmpty() && currentApp.activityClass.isNotEmpty()
        hasHomeAppToClear = hasCurrentApp

        binding.clearHomeButton.apply {
            isVisible = hasCurrentApp
            if (hasCurrentApp) {
                text = getLocalizedString(R.string.picker_clear_slot)
                setOnClickListener {
                    prefs.setHomeAppModel(position, createClearApp())
                    dismiss(toHome = false)
                }
            }
        }
    }

    /** Picker modes get a large title, like a settings screen; the normal drawer has none. */
    private fun setupPickerTitle() {
        val title = when (flag) {
            AppDrawerFlag.LaunchApp, AppDrawerFlag.None -> null
            AppDrawerFlag.HiddenApps -> getLocalizedString(R.string.st_hidden_apps)
            else -> getLocalizedString(R.string.picker_title)
        }
        binding.pickerTitle.apply {
            isVisible = title != null
            text = title
            if (com.github.codeworkscreativehub.mlauncher.helper.FontManager.isBundled(context)) {
                androidx.core.content.res.ResourcesCompat.getFont(context, R.font.google_sans_flex_medium)?.let { typeface = it }
            }
        }
    }
}
