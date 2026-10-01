/**
 * The view for the list of all the installed applications.
 */

package com.github.codeworkscreativehub.mlauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
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
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.SearchView
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.github.codeworkscreativehub.common.AppLogger
import com.github.codeworkscreativehub.common.getLocalizedString
import com.github.codeworkscreativehub.common.hasSoftKeyboard
import com.github.codeworkscreativehub.common.isGestureNavigationEnabled
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
import com.github.codeworkscreativehub.mlauncher.helper.emptyString
import com.github.codeworkscreativehub.mlauncher.helper.getHexForOpacity
import com.github.codeworkscreativehub.mlauncher.helper.openAppInfo
import com.github.codeworkscreativehub.mlauncher.ui.adapter.AppDrawerAdapter

class AppDrawerFragment : BaseFragment() {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var appsAdapter: AppDrawerAdapter

    private var _binding: FragmentAppDrawerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppDrawerBinding.inflate(inflater, container, false)
        prefs = Prefs(requireContext())
        return binding.root
    }


    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    @SuppressLint("RtlHardcoded")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (prefs.firstSettingsOpen) {
            prefs.firstSettingsOpen = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainLayout) { _, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())

            // Adjust menuView & sidebarContainer
            val menuParams = binding.menuView.layoutParams as ViewGroup.MarginLayoutParams
            menuParams.bottomMargin = resources.getDimensionPixelSize(R.dimen.bottom_margin_3_button_nav) + imeInsets.bottom
            binding.menuView.layoutParams = menuParams

            insets
        }

        // Check if device is using gesture navigation or 3-button navigation
        val isGestureNav = isGestureNavigationEnabled(requireContext())

        binding.apply {
            val params = menuView.layoutParams as ViewGroup.MarginLayoutParams
            if (isGestureNav) {
                params.bottomMargin = resources.getDimensionPixelSize(R.dimen.bottom_margin_gesture_nav) // or just in px
            } else {
                params.bottomMargin = resources.getDimensionPixelSize(R.dimen.bottom_margin_3_button_nav) // or just in px
            }
            menuView.layoutParams = params

            val layoutParams = sidebarContainer.layoutParams as RelativeLayout.LayoutParams

            // Clear old alignment rules
            layoutParams.removeRule(RelativeLayout.ALIGN_PARENT_START)
            layoutParams.removeRule(RelativeLayout.ALIGN_PARENT_END)

            // Apply new alignment based on prefs
            when (prefs.drawerAlignment) {
                Constants.Gravity.Left -> layoutParams.addRule(RelativeLayout.ALIGN_PARENT_END)
                Constants.Gravity.Center,
                Constants.Gravity.Right -> layoutParams.addRule(RelativeLayout.ALIGN_PARENT_START)
            }

            sidebarContainer.layoutParams = layoutParams

            mainLayout.setOnClickListener {
                appsAdapter.closeOpenedMenu()
            }
        }

        // Retrieve the letter key code from arguments
        val letterKeyCode = arguments?.getInt("letterKeyCode", -1)
        if (letterKeyCode != null && letterKeyCode != -1) {
            val letterToChar = convertKeyCodeToLetter(letterKeyCode)
            val searchTextView = binding.search.findViewById<TextView>(R.id.search_src_text)
            searchTextView.text = letterToChar.toString()
        }

        val backgroundColor = getHexForOpacity(prefs)
        binding.mainLayout.setBackgroundColor(backgroundColor)

        val flagString = arguments?.getString("flag", AppDrawerFlag.LaunchApp.toString())
            ?: AppDrawerFlag.LaunchApp.toString()
        val flag = AppDrawerFlag.valueOf(flagString)
        val n = arguments?.getInt("n", 0) ?: 0

        val profileType: String = arguments?.getString("profileType", "SYSTEM") ?: "SYSTEM"

        when (flag) {
            AppDrawerFlag.SetDoubleTap,
            AppDrawerFlag.SetShortSwipeRight,
            AppDrawerFlag.SetShortSwipeLeft,
            AppDrawerFlag.SetShortSwipeUp,
            AppDrawerFlag.SetShortSwipeDown,
            AppDrawerFlag.SetLongSwipeRight,
            AppDrawerFlag.SetLongSwipeLeft,
            AppDrawerFlag.SetLongSwipeUp,
            AppDrawerFlag.SetLongSwipeDown,
            AppDrawerFlag.SetClickClock,
            AppDrawerFlag.SetClickDate,
            AppDrawerFlag.SetFloating -> {
            }

            AppDrawerFlag.SetHomeApp -> setupClearHomeButton(n)


            else -> {}
        }

        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        viewModel.appScrollMap.observe(viewLifecycleOwner) { appMap ->
            binding.azSidebar.onLetterSelected = { section ->
                appMap[section]?.let { index -> jumpToSection(binding.appsRecyclerView, index) }
            }
        }

        val gravity = when (Prefs(requireContext()).drawerAlignment) {
            Constants.Gravity.Left -> Gravity.LEFT
            Constants.Gravity.Center -> Gravity.CENTER
            Constants.Gravity.Right -> Gravity.RIGHT
        }

        val appAdapter = context?.let {
            parentFragment?.let { fragment ->
                AppDrawerAdapter(
                    it,
                    fragment,
                    flag,
                    gravity,
                    appClickListener(viewModel, flag, n),
                    appDeleteListener(),
                    this.appRenameListener(),
                    this.appTagListener(),
                    appShowHideListener(),
                    appInfoListener()
                )
            }
        }

        appAdapter?.let { appsAdapter = it }

        val searchTextView = binding.search.findViewById<TextView>(R.id.search_src_text)

        val textSize = prefs.appSize.toFloat()
        searchTextView.textSize = textSize

        if (appAdapter != null) {
            initViewModel(flag, viewModel, appAdapter, profileType)
        }

        binding.appsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.appsRecyclerView.apply {
            // match_parent in both directions, so content changes never resize the view
            setHasFixedSize(true)
            // No cross-fade when a row changes (menu open/close, rename)
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            adapter = appAdapter
        }

        var lastSectionLetter: String? = null

        binding.appsRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            var onTop = false

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val itemCount = layoutManager.itemCount
                if (itemCount == 0) return

                val firstVisible = layoutManager.findFirstVisibleItemPosition()
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION) return

                val position = when {
                    firstVisible <= 1 -> firstVisible
                    lastVisible >= itemCount - 2 -> lastVisible
                    else -> (firstVisible + lastVisible) / 2
                }.coerceIn(0, itemCount - 1)

                val item = appAdapter?.getItemAt(position) ?: return

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
                appAdapter?.closeOpenedMenu()
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        onTop = !recyclerView.canScrollVertically(-1)
                        if (onTop) {
                            if (requireContext().hasSoftKeyboard()) {
                                binding.search.hideKeyboard()
                            }
                        }
                        if (onTop && !recyclerView.canScrollVertically(1)) {
                            findNavController().popBackStack()
                        }
                    }

                    RecyclerView.SCROLL_STATE_IDLE -> {
                        if (!recyclerView.canScrollVertically(1)) {
                            binding.search.hideKeyboard()
                        } else if (!recyclerView.canScrollVertically(-1)) {
                            if (onTop) {
                                findNavController().popBackStack()
                            } else {
                                if (requireContext().hasSoftKeyboard()) {
                                    binding.search.showKeyboard()
                                }
                            }
                        }
                    }
                }
            }
        })

        if (prefs.hideSearchView) {
            binding.search.isVisible = false
        } else {
            val appListButtonFlags = prefs.getMenuFlags("APPLIST_BUTTON_FLAGS", "00")
            when (flag) {
                AppDrawerFlag.LaunchApp -> {
                    setupProfileButtons(flag, viewModel, appAdapter, profileType)

                    binding.internetSearch.apply {
                        isVisible = appListButtonFlags[0]
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

                    val firstItem = appAdapter?.getFirstInList()
                    if (firstItem.equals(searchQuery, ignoreCase = true) || prefs.openAppOnEnter) {
                        appAdapter?.launchFirstInList()
                    } else {
                        requireContext().searchOnPlayStore(searchQuery)
                    }

                    return true
                }

                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (flag == AppDrawerFlag.SetHomeApp) {
                    binding.clearHomeButton.apply {
                        isVisible = newText.isNullOrEmpty()
                    }
                }

                newText?.let { appAdapter?.filter?.filter(it.trim()) }
                return false
            }
        })
    }

    private fun setupProfileButtons(
        flag: AppDrawerFlag,
        viewModel: MainViewModel,
        appAdapter: AppDrawerAdapter?,
        profileType: String
    ) {
        var currentProfileType = profileType

        fun updateProfileUI(profileType: String) {
            currentProfileType = profileType

            val isWorkProfileAvailable = prefs.getProfileCounter("WORK") > 0 && profileType != "WORK"
            val isSystemProfileAvailable = prefs.getProfileCounter("SYSTEM") > 0 && profileType != "SYSTEM"

            binding.workApps.isVisible = isWorkProfileAvailable
            binding.systemApps.isVisible = isSystemProfileAvailable

            binding.search.queryHint = when (profileType) {
                "WORK" -> getLocalizedString(R.string.show_work_apps)
                else -> getLocalizedString(R.string.show_apps)
            }
        }

        fun onProfileClicked(newType: String) {
            if (appAdapter != null) {
                initViewModel(flag, viewModel, appAdapter, newType)
            }
            binding.search.setQuery("", false)
            updateProfileUI(newType)
        }

        // Initial setup
        updateProfileUI(currentProfileType)

        // Button listeners
        binding.workApps.setOnClickListener {
            onProfileClicked("WORK")
        }
        binding.systemApps.setOnClickListener {
            onProfileClicked("SYSTEM")
        }
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

    private fun convertKeyCodeToLetter(keyCode: Int): Char {
        return when (keyCode) {
            KeyEvent.KEYCODE_A -> 'A'
            KeyEvent.KEYCODE_B -> 'B'
            KeyEvent.KEYCODE_C -> 'C'
            KeyEvent.KEYCODE_D -> 'D'
            KeyEvent.KEYCODE_E -> 'E'
            KeyEvent.KEYCODE_F -> 'F'
            KeyEvent.KEYCODE_G -> 'G'
            KeyEvent.KEYCODE_H -> 'H'
            KeyEvent.KEYCODE_I -> 'I'
            KeyEvent.KEYCODE_J -> 'J'
            KeyEvent.KEYCODE_K -> 'K'
            KeyEvent.KEYCODE_L -> 'L'
            KeyEvent.KEYCODE_M -> 'M'
            KeyEvent.KEYCODE_N -> 'N'
            KeyEvent.KEYCODE_O -> 'O'
            KeyEvent.KEYCODE_P -> 'P'
            KeyEvent.KEYCODE_Q -> 'Q'
            KeyEvent.KEYCODE_R -> 'R'
            KeyEvent.KEYCODE_S -> 'S'
            KeyEvent.KEYCODE_T -> 'T'
            KeyEvent.KEYCODE_U -> 'U'
            KeyEvent.KEYCODE_V -> 'V'
            KeyEvent.KEYCODE_W -> 'W'
            KeyEvent.KEYCODE_X -> 'X'
            KeyEvent.KEYCODE_Y -> 'Y'
            KeyEvent.KEYCODE_Z -> 'Z'
            else -> throw IllegalArgumentException("Invalid key code: $keyCode")
        }
    }

    private fun initViewModel(
        flag: AppDrawerFlag,
        viewModel: MainViewModel,
        appAdapter: AppDrawerAdapter,
        profileFilter: String? = null // "WORK", "SYSTEM", "USER", or null for all
    ) {
        fun <T> observeList(
            liveData: LiveData<List<T>?>,
            currentList: List<T>,
            onPopulate: (List<T>) -> Unit,
            skipCondition: () -> Boolean = { false }
        ) {
            liveData.observe(viewLifecycleOwner) { newList ->
                if (skipCondition() || newList == currentList) return@observe
                newList?.let {
                    binding.listEmptyHint.isVisible = it.isEmpty()
                    binding.sidebarContainer.isVisible = prefs.showAZSidebar
                    onPopulate(it)
                }
            }
        }

        // 🔹 Observe hidden apps
        observeList(
            viewModel.hiddenApps, appAdapter.appsList,
            onPopulate = { populateAppList(it, appAdapter) },
            skipCondition = { flag != AppDrawerFlag.HiddenApps }
        )

        // 🔹 Observe apps
        viewModel.appList.observe(viewLifecycleOwner) { rawAppList ->
            if (flag == AppDrawerFlag.HiddenApps) return@observe
            if (rawAppList == appAdapter.appsList) return@observe

            AppLogger.d("Apps", "Loaded ${rawAppList?.size ?: 0} raw apps")
            rawAppList?.let { list ->
                val appsByProfile = list.groupBy { it.profileType }
                val allProfiles = listOf("SYSTEM", "WORK", "USER")

                // Update prefs counters
                allProfiles.forEach { profile ->
                    prefs.setProfileCounter(profile, appsByProfile[profile]?.size ?: 0)
                }

                // Merge apps based on filter
                val mergedList = allProfiles.flatMap { profile ->
                    val apps = appsByProfile[profile].orEmpty()
                    if (apps.isNotEmpty() && (profileFilter == null || profileFilter.equals(profile, true))) {
                        AppLogger.d("AppMerge", "Adding ${apps.size} $profile apps")
                        apps
                    } else emptyList()
                }

                AppLogger.d("AppMerge", "Final merged list (${mergedList.size} apps)")

                binding.listEmptyHint.isVisible = mergedList.isEmpty()
                binding.sidebarContainer.isVisible = prefs.showAZSidebar
                populateAppList(mergedList, appAdapter)
            }
        }

        // 🔹 Observe first open
        viewModel.firstOpen.observe(viewLifecycleOwner) {
            binding.appDrawerTip.isVisible = it
        }
    }

    override fun onResume() {
        super.onResume()
        if (requireContext().hasSoftKeyboard()) {
            // Wait out the 280ms drawer_enter animation so the IME resize doesn't jolt it
            binding.search.showKeyboard(delayMs = 300)
        }
    }

    override fun onStop() {
        super.onStop()
        if (requireContext().hasSoftKeyboard()) {
            binding.search.hideKeyboard()
        }
    }


    private fun View.showKeyboard(delayMs: Long = 100) {
        val prefs = Prefs(requireContext())
        if (!prefs.autoShowKeyboard) return
        if (prefs.hideSearchView) return

        val searchTextView = binding.search.findViewById<TextView>(R.id.search_src_text)
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        searchTextView.postDelayed({
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
        if (flag == AppDrawerFlag.LaunchApp || flag == AppDrawerFlag.HiddenApps)
            findNavController().popBackStack(R.id.mainFragment, false)
        else
            findNavController().popBackStack()
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
        findNavController().popBackStack()
    }

    private fun appTagListener(): (appPackage: String, appTag: String, appUser: UserHandle) -> Unit = { appPackage, appTag, appUser ->
        val prefs = Prefs(requireContext())
        prefs.setAppTag(appPackage, appTag, appUser)
        findNavController().popBackStack()
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

        if (newSet.isEmpty()) findNavController().popBackStack()
    }

    private fun appInfoListener(): (appListItem: AppListItem) -> Unit = { appModel ->
        openAppInfo(
            requireContext(),
            appModel.user,
            appModel.activityPackage
        )
        findNavController().popBackStack(R.id.mainFragment, false)
    }

    /** Instant jump that puts the section's first row at the top of the list. */
    private fun jumpToSection(recyclerView: RecyclerView, index: Int) {
        val count = recyclerView.adapter?.itemCount ?: return
        if (index !in 0 until count) return
        recyclerView.stopScroll()
        // Both drawer lists use a plain top-down LinearLayoutManager (not reversed)
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

    private fun setupClearHomeButton(position: Int) {
        val currentApp = prefs.getHomeAppModel(position)
        val hasCurrentApp =
            currentApp.activityPackage.isNotEmpty() && currentApp.activityClass.isNotEmpty()

        binding.clearHomeButton.apply {
            isVisible = hasCurrentApp
            if (hasCurrentApp) {
                text = getLocalizedString(R.string.clear_home_app)
                setTextColor(prefs.appColor)
                textSize = prefs.appSize.toFloat()
                setOnClickListener {
                    prefs.setHomeAppModel(position, createClearApp())
                    findNavController().popBackStack()
                }
            }
        }
    }
}