package com.github.codeworkscreativehub.mlauncher.ui

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Context.VIBRATOR_SERVICE
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Vibrator
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.format.DateFormat
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.SuperscriptSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.PathInterpolator
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.biometric.BiometricPrompt
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.github.codeworkscreativehub.common.AppLogger
import com.github.codeworkscreativehub.common.ColorIconsExtensions
import com.github.codeworkscreativehub.common.CrashHandler
import com.github.codeworkscreativehub.common.attachGestureManager
import com.github.codeworkscreativehub.common.getLocalizedString
import com.github.codeworkscreativehub.common.isGestureNavigationEnabled
import com.github.codeworkscreativehub.common.launchCalendar
import com.github.codeworkscreativehub.common.openAlarmApp
import com.github.codeworkscreativehub.common.openCameraApp
import com.github.codeworkscreativehub.common.openDeviceSettings
import com.github.codeworkscreativehub.common.openDialerApp
import com.github.codeworkscreativehub.common.openPhotosApp
import com.github.codeworkscreativehub.common.openTextMessagesApp
import com.github.codeworkscreativehub.common.openWebBrowser
import com.github.codeworkscreativehub.common.showShortToast
import com.github.codeworkscreativehub.mlauncher.MainViewModel
import com.github.codeworkscreativehub.mlauncher.R
import com.github.codeworkscreativehub.mlauncher.data.Constants
import com.github.codeworkscreativehub.mlauncher.data.Constants.Action
import com.github.codeworkscreativehub.mlauncher.data.Constants.AppDrawerFlag
import com.github.codeworkscreativehub.mlauncher.data.Prefs
import com.github.codeworkscreativehub.mlauncher.databinding.FragmentHomeBinding
import com.github.codeworkscreativehub.mlauncher.helper.ClockSticker
import com.github.codeworkscreativehub.mlauncher.helper.FontManager
import com.github.codeworkscreativehub.mlauncher.helper.IconCacheTarget
import com.github.codeworkscreativehub.mlauncher.helper.IconPackHelper
import com.github.codeworkscreativehub.mlauncher.helper.IconPackHelper.getSafeAppIcon
import com.github.codeworkscreativehub.mlauncher.helper.getHexForOpacity
import com.github.codeworkscreativehub.mlauncher.helper.getSystemIcons
import com.github.codeworkscreativehub.mlauncher.helper.initActionService
import com.github.codeworkscreativehub.mlauncher.helper.ismlauncherDefault
import com.github.codeworkscreativehub.mlauncher.helper.receivers.DeviceAdmin
import com.github.codeworkscreativehub.mlauncher.helper.setTopPadding
import com.github.codeworkscreativehub.mlauncher.helper.updateHomeWidget
import com.github.codeworkscreativehub.mlauncher.helper.utils.AppReloader
import com.github.codeworkscreativehub.mlauncher.helper.utils.BiometricHelper
import com.github.codeworkscreativehub.mlauncher.listener.GestureAdapter
import com.github.codeworkscreativehub.mlauncher.listener.GestureManager
import com.github.codeworkscreativehub.mlauncher.listener.NotificationDotManager
import com.github.codeworkscreativehub.mlauncher.services.ActionService
import com.github.codeworkscreativehub.mlauncher.ui.components.VerticalDragLayout
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HomeFragment : BaseFragment(), View.OnClickListener, View.OnLongClickListener, DrawerHost {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager
    private lateinit var biometricHelper: BiometricHelper
    private lateinit var vibrator: Vibrator

    private var longPressToSelectApp: Int = 0
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)

        val view = binding.root
        prefs = Prefs(requireContext())

        longPressToSelectApp = if (prefs.homeLocked) {
            R.string.long_press_to_select_app_locked
        } else {
            R.string.long_press_to_select_app
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        biometricHelper = BiometricHelper(this.requireActivity())

        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        viewModel.ismlauncherDefault()

        deviceManager =
            context?.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        @Suppress("DEPRECATION")
        vibrator = context?.getSystemService(VIBRATOR_SERVICE) as Vibrator

        FontManager.reloadFont(requireContext())

        initAppObservers()
        initClickListeners()
        initSwipeTouchListener()
        initObservers()

        // Update view appearance/settings based on prefs
        updateUIFromPreferences()

        setupDrawer(savedInstanceState)
        setupEditMode()
    }

    override fun onResume() {
        super.onResume()
        updateStickerActive()
    }

    override fun onPause() {
        _binding?.clockSticker?.setActive(false)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_DRAWER_OPEN, drawerTargetOpen && drawerProgress > 0f)
    }

    override fun onDestroyView() {
        _binding?.homeAppsLayout?.exitEditMode(animate = false)
        drawerAnimator?.cancel()
        drawerAnimator = null
        super.onDestroyView()
    }

    override fun onStart() {
        super.onStart()

        // Handle status bar once per view creation
        setTopPadding(binding.mainLayout)

        // Update dynamic UI elements
        updateTimeAndInfo()
        binding.clock.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionSticker() }
        updateClockSticker()
        refreshIconsIfStale()
    }

    private var iconGeneration = IconPackHelper.generation

    /** An app or the icon pack changed since the rows were drawn: redraw home and the drawer. */
    private fun refreshIconsIfStale() {
        val b = _binding ?: return
        if (iconGeneration == IconPackHelper.generation) return
        iconGeneration = IconPackHelper.generation
        if (homeEditing()) exitEditMode(animate = false)
        b.homeAppsLayout.removeAllViews()
        updateAppCount(prefs.homeAppsNum)
        viewModel.homeAppsAlignment.value?.let { (gravity, _) ->
            b.homeAppsLayout.children.forEach { (it as? TextView)?.gravity = gravity.value() }
        }
        if (drawerProgress == 0f) attachFreshDrawer()
    }

    private var stickerStamp = -1L
    private var stickerSizeDp = -1

    /**
     * Optional sticker (Settings > Clock & date) slapped onto the bottom-right of the clock.
     * The clock layout is untouched; the sticker floats on top, anchored to the clock's bounds.
     */
    private fun updateClockSticker() {
        val b = _binding ?: return
        val ctx = context ?: return
        if (!prefs.showClock) {
            b.clockSticker.isVisible = false
            return
        }
        val stamp = ClockSticker.stamp(ctx)
        val sizeDp = prefs.clockStickerSize
        b.clockSticker.holoEnabled = prefs.clockStickerHolo
        if (stamp == stickerStamp && sizeDp == stickerSizeDp && (stamp == 0L) == !b.clockSticker.isVisible) return
        stickerStamp = stamp
        stickerSizeDp = sizeDp

        val bitmap = if (stamp != 0L) ClockSticker.load(ctx) else null
        b.clockSticker.setSprite(bitmap)
        if (bitmap == null) {
            b.clockSticker.isVisible = false
            return
        }
        val px = (sizeDp * resources.displayMetrics.density).toInt()
        b.clockSticker.layoutParams = b.clockSticker.layoutParams.apply {
            width = px
            height = px
        }
        b.clockSticker.isVisible = true
        b.clock.doOnLayout { positionSticker() }
    }

    /**
     * Centre at ~90% across the clock and ~60% down its digits, so it overlaps the last digit and
     * pokes out to the right. Fractions of the clock size, so it follows size and alignment.
     * Clamped to stay on screen.
     */
    private fun positionSticker() {
        val b = _binding ?: return
        val sticker = b.clockSticker
        if (!sticker.isVisible || !b.clock.isShown) return
        val clock = b.clock
        val loc = IntArray(2)
        val parentLoc = IntArray(2)
        clock.getLocationInWindow(loc)
        b.mainLayout.getLocationInWindow(parentLoc)
        val clockLeft = (loc[0] - parentLoc[0]).toFloat()
        val clockTop = (loc[1] - parentLoc[1]).toFloat()

        val digits = Rect()
        clock.paint.getTextBounds("0", 0, 1, digits)
        val digitsTop = clockTop + clock.baseline + digits.top
        val digitsHeight = digits.height().toFloat()

        val size = sticker.layoutParams.width.toFloat()
        val cx = clockLeft + clock.width * 0.90f
        val cy = digitsTop + digitsHeight * 0.60f
        val margin = 8 * resources.displayMetrics.density
        val maxX = b.mainLayout.width - size - margin
        sticker.translationX = (cx - size / 2f).coerceIn(margin, maxOf(margin, maxX))
        sticker.translationY = (cy - size / 2f).coerceAtLeast(margin)
    }

    private fun updateStickerActive() {
        val b = _binding ?: return
        b.clockSticker.setActive(isResumed && drawerProgress == 0f)
    }

    private fun updateUIFromPreferences() {
        val locale = prefs.appLanguage.locale()
        val is24HourFormat = DateFormat.is24HourFormat(requireContext())

        binding.apply {
            val best12Raw = DateFormat.getBestDateTimePattern(locale, "hm") // 12-hour with AM/PM
            val best12 = if (prefs.showClockFormat) {
                best12Raw // keep AM/PM
            } else {
                best12Raw.replace("a", "").trim() // strip AM/PM
            }

            val best24 = DateFormat.getBestDateTimePattern(locale, "Hm") // 24-hour

            val timePattern = if (is24HourFormat) best24 else best12

            clock.format12Hour = timePattern
            clock.format24Hour = timePattern

            // Date format
            val datePattern = DateFormat.getBestDateTimePattern(locale, "EEEddMMM")
            date.format12Hour = datePattern
            date.format24Hour = datePattern

            // Static UI setup
            date.textSize = prefs.dateSize.toFloat()
            clock.textSize = prefs.clockSize.toFloat()
            homeScreenPager.textSize = prefs.appSize.toFloat()

            mainLayout.setBackgroundColor(getHexForOpacity(prefs))

            date.setTextColor(prefs.dateColor)
            clock.setTextColor(prefs.clockColor)
            setDefaultLauncher.setTextColor(prefs.appColor)

            val fabList = listOf(fabPhone, fabMessages, fabCamera, fabPhotos, fabBrowser, fabSettings, fabAction)
            val fabFlags = prefs.getMenuFlags("HOME_BUTTON_FLAGS", "0000011") // Might return list of wrong size
            for (i in fabList.indices) {
                val fab = fabList[i]
                fab.isVisible = fabFlags.getOrElse(i) { false }
                // The logo keeps its own colours
                if (fab != fabAction) fab.setColorFilter(prefs.shortcutIconsColor)
            }
        }
    }

    private fun updateTimeAndInfo() {
        val locale = prefs.appLanguage.locale()
        val is24HourFormat = DateFormat.is24HourFormat(requireContext())

        binding.apply {

            val best12Raw = DateFormat.getBestDateTimePattern(locale, "hm") // 12-hour with AM/PM
            val best12 = if (prefs.showClockFormat) {
                best12Raw // keep AM/PM
            } else {
                best12Raw.replace("a", "").trim() // strip AM/PM
            }

            val best24 = DateFormat.getBestDateTimePattern(locale, "Hm") // 24-hour

            val timePattern = if (is24HourFormat) best24 else best12

            clock.format12Hour = timePattern
            clock.format24Hour = timePattern

            // Date format
            // val datePattern = DateFormat.getBestDateTimePattern(locale, "EEEddMMM")
            // date.format12Hour = datePattern
            // date.format24Hour = datePattern

            val basePattern = DateFormat.getBestDateTimePattern(locale, "EEEddMMM")

            // Day of year as a quoted literal, refreshed whenever the home screen starts
            val finalPattern = if (prefs.showDayOfYear) {
                val cal = java.util.Calendar.getInstance()
                val day = cal.get(java.util.Calendar.DAY_OF_YEAR)
                val max = cal.getActualMaximum(java.util.Calendar.DAY_OF_YEAR)
                "$basePattern   '[$day/$max]'"
            } else {
                basePattern
            }

            date.format12Hour = finalPattern
            date.format24Hour = finalPattern
        }
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.clock -> {
                when (val action = prefs.clickClockAction) {
                    Action.OpenApp -> openClickClockApp()
                    else -> handleOtherAction(action)
                }
                CrashHandler.logUserAction("Clock Clicked")
            }

            R.id.date -> {
                when (val action = prefs.clickDateAction) {
                    Action.OpenApp -> openClickDateApp()
                    else -> handleOtherAction(action)
                }
                CrashHandler.logUserAction("Date Clicked")
            }


            R.id.setDefaultLauncher -> {
                viewModel.resetDefaultLauncherApp(requireContext())
                CrashHandler.logUserAction("SetDefaultLauncher Clicked")
            }



            R.id.fabPhone -> {
                context?.openDialerApp()
                CrashHandler.logUserAction("fabPhone Clicked")
            }

            R.id.fabMessages -> {
                context?.openTextMessagesApp()
                CrashHandler.logUserAction("fabMessages Clicked")
            }

            R.id.fabCamera -> {
                context?.openCameraApp()
                CrashHandler.logUserAction("fabCamera Clicked")
            }

            R.id.fabPhotos -> {
                context?.openPhotosApp()
                CrashHandler.logUserAction("fabPhotos Clicked")
            }

            R.id.fabBrowser -> {
                context?.openWebBrowser()
                CrashHandler.logUserAction("fabBrowser Clicked")
            }

            R.id.fabSettings -> {
                trySettings()
                CrashHandler.logUserAction("fabSettings Clicked")
            }

            R.id.fabAction -> {
                when (val action = prefs.clickFloatingAction) {
                    Action.OpenApp -> openFabActionApp()
                    else -> handleOtherAction(action)
                }
                CrashHandler.logUserAction("fabAction Clicked")
            }

            else -> {
                try { // Launch app
                    val appLocation = view.id
                    homeAppClicked(appLocation)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onLongClick(view: View): Boolean {
        if (prefs.homeLocked) return true

        val n = view.id
        showAppList(AppDrawerFlag.SetHomeApp, includeHiddenApps = true, n = n)
        CrashHandler.logUserAction("Show App List")
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initSwipeTouchListener() {
        binding.touchArea.getHomeScreenGestureListener()
    }

    private fun initClickListeners() {
        binding.apply {
            clock.setOnClickListener(this@HomeFragment)
            date.setOnClickListener(this@HomeFragment)
            setDefaultLauncher.setOnClickListener(this@HomeFragment)

            fabPhone.setOnClickListener(this@HomeFragment)
            fabMessages.setOnClickListener(this@HomeFragment)
            fabCamera.setOnClickListener(this@HomeFragment)
            fabPhotos.setOnClickListener(this@HomeFragment)
            fabBrowser.setOnClickListener(this@HomeFragment)
            fabAction.setOnClickListener(this@HomeFragment)
            fabSettings.setOnClickListener(this@HomeFragment)
        }
    }

    private fun initAppObservers() {
        binding.apply {
            firstRunTips.isVisible = prefs.firstSettingsOpen

            setDefaultLauncher.isVisible = !ismlauncherDefault(requireContext())

            val changeLauncherText = if (ismlauncherDefault(requireContext())) {
                R.string.advanced_settings_change_default_launcher
            } else {
                R.string.advanced_settings_set_as_default_launcher
            }

            setDefaultLauncher.text = getLocalizedString(changeLauncherText)
        }

        with(viewModel) {
            homeAppsNum.observe(viewLifecycleOwner) {
                updateAppCount(it)
            }
            launcherDefault.observe(viewLifecycleOwner) {
                binding.setDefaultLauncher.isVisible = it
            }
        }
    }

    private fun initObservers() {
        with(viewModel) {
            showDate.observe(viewLifecycleOwner) {
                binding.date.isVisible = it
            }
            showDayOfYear.observe(viewLifecycleOwner) {
                updateTimeAndInfo()
            }
            showClock.observe(viewLifecycleOwner) {
                binding.clockRow.isVisible = it
                // The sticker belongs to the clock
                stickerStamp = -1L
                if (it) updateClockSticker() else binding.clockSticker.isVisible = false
            }

            clockAlignment.observe(viewLifecycleOwner) { clockGravity ->
                binding.clock.gravity = clockGravity.value()

                // Align the clock row (clock + optional sticker) within the parent LinearLayout
                binding.clockRow.layoutParams =
                    (binding.clockRow.layoutParams as LinearLayout.LayoutParams).apply {
                        gravity = clockGravity.value()
                    }
            }

            dateAlignment.observe(viewLifecycleOwner) { dateGravity ->
                binding.date.gravity = dateGravity.value()

                // Set layout_gravity to align the TextClock (date) within the parent (LinearLayout)
                binding.date.layoutParams =
                    (binding.date.layoutParams as LinearLayout.LayoutParams).apply {
                        gravity = dateGravity.value()
                    }
            }



            homeAppsAlignment.observe(viewLifecycleOwner) { (homeAppsGravity, onBottom) ->
                val horizontalAlignment = if (onBottom) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
                binding.homeAppsLayout.gravity = homeAppsGravity.value() or horizontalAlignment

                binding.homeAppsLayout.children.forEach { view ->
                    (view as? TextView)?.gravity = homeAppsGravity.value()
                }
            }
        }
    }

    private fun homeAppClicked(location: Int) {
        CrashHandler.logUserAction("Clicked Home App: $location")
        if (prefs.getAppName(location).isEmpty()) showLongPressToast()
        else viewModel.launchApp(prefs.getHomeAppModel(location), this)
    }

    private fun showAppList(flag: AppDrawerFlag, includeHiddenApps: Boolean = false, n: Int = 0) {
        if (flag == AppDrawerFlag.LaunchApp && drawerFragment != null) {
            openDrawer()
            return
        }
        viewModel.getAppList(includeHiddenApps)
        CrashHandler.logUserAction("Display App List")
        try {
            if (findNavController().currentDestination?.id == R.id.mainFragment) {
                findNavController().navigate(
                    R.id.action_mainFragment_to_appListFragment,
                    Bundle().apply {
                        putString("flag", flag.toString())
                        putInt("n", n)
                        putString("profileType", "SYSTEM")
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("PrivateApi")
    private fun expandNotificationDrawer(context: Context) {
        try {
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel")
                .invoke(context.getSystemService("statusbar"))
        } catch (exception: Exception) {
            initActionService(requireContext())?.openNotifications()
            exception.printStackTrace()
        }
        CrashHandler.logUserAction("Expand Notification Drawer")
    }

    @SuppressLint("PrivateApi")
    private fun expandQuickSettings(context: Context) {
        try {
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandSettingsPanel")
                .invoke(context.getSystemService("statusbar"))
        } catch (exception: Exception) {
            initActionService(requireContext())?.openQuickSettings()
            exception.printStackTrace()
        }
        CrashHandler.logUserAction("Expand Quick Settings")
    }

    private fun openSwipeUpApp() {
        CrashHandler.logUserAction("Open Swipe Up App")
        if (prefs.appShortSwipeUp.activityPackage.isNotEmpty())
            viewModel.launchApp(prefs.appShortSwipeUp, this)
        else
            requireContext().openDeviceSettings()
    }

    private fun openSwipeDownApp() {
        CrashHandler.logUserAction("Open Swipe Down App")
        if (prefs.appShortSwipeDown.activityPackage.isNotEmpty())
            viewModel.launchApp(prefs.appShortSwipeDown, this)
        else
            requireContext().openDialerApp()
    }

    private fun openSwipeLeftApp() {
        CrashHandler.logUserAction("Open Swipe Left App")
        if (prefs.appShortSwipeLeft.activityPackage.isNotEmpty())
            viewModel.launchApp(prefs.appShortSwipeLeft, this)
        else
            requireContext().openDeviceSettings()
    }

    private fun openSwipeRightApp() {
        CrashHandler.logUserAction("Open Swipe Right App")
        if (prefs.appShortSwipeRight.activityPackage.isNotEmpty())
            viewModel.launchApp(prefs.appShortSwipeRight, this)
        else
            requireContext().openDialerApp()
    }

    private fun openLongSwipeUpApp() {
        CrashHandler.logUserAction("Open Swipe Long Up App")
        if (prefs.appLongSwipeUp.activityPackage.isNotEmpty())
            viewModel.launchApp(prefs.appLongSwipeUp, this)
        else
            requireContext().openDeviceSettings()
    }

    private fun openLongSwipeDownApp() {
        CrashHandler.logUserAction("Open Swipe Long Down App")
        if (prefs.appLongSwipeDown.activityPackage.isNotEmpty())
            viewModel.launchApp(prefs.appLongSwipeDown, this)
        else
            requireContext().openDialerApp()
    }

    private fun openLongSwipeLeftApp() {
        CrashHandler.logUserAction("Open Swipe Long Left App")
        if (prefs.appLongSwipeLeft.activityPackage.isNotEmpty())
            viewModel.launchApp(prefs.appLongSwipeLeft, this)
        else
            requireContext().openDeviceSettings()
    }

    private fun openLongSwipeRightApp() {
        CrashHandler.logUserAction("Open Swipe Long Right App")
        if (prefs.appLongSwipeRight.activityPackage.isNotEmpty())
            viewModel.launchApp(prefs.appLongSwipeRight, this)
        else
            requireContext().openDialerApp()
    }

    private fun openClickClockApp() {
        CrashHandler.logUserAction("Open Clock App")
        if (prefs.appClickClock.activityPackage.isNotEmpty())
            viewModel.launchApp(prefs.appClickClock, this)
        else
            requireContext().openAlarmApp()
    }

    private fun openClickDateApp() {
        CrashHandler.logUserAction("Open Date App")
        if (prefs.appClickDate.activityPackage.isNotEmpty())
            viewModel.launchApp(prefs.appClickDate, this)
        else
            requireContext().launchCalendar()
    }

    private fun openDoubleTapApp() {
        CrashHandler.logUserAction("Open Double Tap App")
        if (prefs.appDoubleTap.activityPackage.isNotEmpty())
            viewModel.launchApp(prefs.appDoubleTap, this)
        else
            AppReloader.restartApp(requireContext())
    }

    private fun openFabActionApp() {
        CrashHandler.logUserAction("Open Fab App")
        if (prefs.appFloating.activityPackage.isNotEmpty())
            viewModel.launchApp(prefs.appFloating, this)
        else
            showAppList(AppDrawerFlag.LaunchApp)
    }

    // This function handles all swipe actions that an independent of the actual swipe direction
    @SuppressLint("NewApi")
    private fun handleOtherAction(action: Action) {
        when (action) {
            Action.ShowNotification -> expandNotificationDrawer(requireContext())
            Action.LockScreen -> lockPhone()
            Action.ShowAppList -> showAppList(AppDrawerFlag.LaunchApp, includeHiddenApps = false)
            Action.OpenQuickSettings -> expandQuickSettings(requireContext())
            Action.ShowRecents -> initActionService(requireContext())?.showRecents()
            Action.OpenPowerDialog -> initActionService(requireContext())?.openPowerDialog()
            Action.TakeScreenShot -> initActionService(requireContext())?.takeScreenShot()
            Action.PreviousPage -> navigateToPreviousPage()
            Action.NextPage -> navigateToNextPage()
            Action.RestartApp -> AppReloader.restartApp(requireContext())
            Action.OpenApp -> {
                // this should be handled in the respective onSwipe[Up,Down,Right,Left] functions
            }

            Action.Disabled -> {
                // Do nothing
            }

        }
    }

    private fun lockPhone() {
        val context = requireContext()
        val deviceAdmin = ComponentName(context, DeviceAdmin::class.java)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val actionService = ActionService.instance()

        when {
            // Use Device Admin if active
            dpm.isAdminActive(deviceAdmin) -> {
                dpm.lockNow()
                CrashHandler.logUserAction("Lock Screen via Device Admin")
            }
            // Fallback to ActionService if available
            actionService != null -> {
                actionService.lockScreen()
                CrashHandler.logUserAction("Lock Screen via ActionService")
            }
            // Otherwise prompt the user to enable Device Admin
            else -> {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdmin)
                }
                startActivity(intent)
            }
        }
    }

    private fun showLongPressToast() = showShortToast(getLocalizedString(longPressToSelectApp))

    private fun textOnClick(view: View) = onClick(view)

    private fun textOnLongClick(view: View) = onLongClick(view)

    private fun adjustTextViewMargins() {
        binding.apply {


            val views = listOf(
                setDefaultLauncher,
                homeScreenPager,
                fabLayout,
                homeAppsLayout
            )

            // Check if device is using gesture navigation or 3-button navigation
            val isGestureNav = isGestureNavigationEnabled(requireContext())

            val numOfElements = 4
            val incrementBy = 35
            // Set margins based on navigation mode
            val margins = if (isGestureNav) {
                val startAt = resources.getDimensionPixelSize(R.dimen.bottom_margin_gesture_nav)
                List(numOfElements) { index -> startAt + (index * incrementBy) } // Adjusted margins for gesture navigation
            } else {
                val startAt = resources.getDimensionPixelSize(R.dimen.bottom_margin_3_button_nav)
                List(numOfElements) { index -> startAt + (index * incrementBy) } // Adjusted margins for 3-button navigation
            }

            val visibleViews = views.filter { it.isVisible }
            val visibleMargins =
                margins.take(visibleViews.size) // Trim margins list to match visible views

            // Reset margins for all views
            views.forEach { view ->
                val params = view.layoutParams as ViewGroup.MarginLayoutParams
                params.bottomMargin = 0
                view.layoutParams = params
            }

            // Apply correct spacing for visible views
            visibleViews.forEachIndexed { index, view ->
                val params = view.layoutParams as ViewGroup.MarginLayoutParams
                var bottomMargin = visibleMargins.getOrElse(index) { 0 }

                // Add extra space above fabLayout if it's visible
                if (prefs.homeAlignmentBottom) {
                    if (visibleViews.contains(fabLayout)) {
                        if (view == homeAppsLayout) {
                            bottomMargin += 65
                        }
                    }
                }

                if (view == homeScreenPager) {
                    bottomMargin += 10
                }

                params.bottomMargin = bottomMargin
                view.layoutParams = params
            }
        }
    }

    @SuppressLint("InflateParams", "DiscouragedApi", "UseCompatLoadingForDrawables", "ClickableViewAccessibility")
    private fun updateAppCount(newAppsNum: Int) {
        val oldAppsNum = binding.homeAppsLayout.childCount // current number of apps
        val diff = newAppsNum - oldAppsNum

        if (diff > 0) {
            // Add new apps
            for (i in oldAppsNum until newAppsNum) {
                val homeAppLabel = layoutInflater.inflate(R.layout.home_app_button, null) as TextView
                homeAppLabel.apply {
                    val homeApp = prefs.getHomeAppModel(i)
                    textSize = prefs.appSize.toFloat()
                    id = i
                    text = if (homeApp.activityPackage.isBlank()) {
                        getLocalizedString(R.string.select_app)
                    } else {
                        prefs.getAppAlias(homeApp.activityPackage).takeIf { it.isNotBlank() } ?: homeApp.activityLabel
                    }

                    getHomeAppsGestureListener()
                    setOnClickListener(this@HomeFragment)

                    if (!prefs.extendHomeAppsArea) {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }

                    gravity = prefs.homeAlignment.value()
                    isFocusable = true
                    isFocusableInTouchMode = true

                    val padding: Int = prefs.textPaddingSize
                    setPadding(0, padding, 0, padding)
                    setTextColor(prefs.appColor)
                    val appModel = prefs.getHomeAppModel(i)
                    val packageName = appModel.activityPackage

                    if (packageName.isNotBlank() && prefs.iconPackHome != Constants.IconPacks.Disabled) {
                        val iconPackPackage = prefs.customIconPackHome
                        // Try to get app icon, possibly using icon pack, with graceful fallback
                        val nonNullDrawable: Drawable = getSafeAppIcon(
                            context = context,
                            packageName = packageName,
                            useIconPack = (iconPackPackage.isNotEmpty() && prefs.iconPackHome == Constants.IconPacks.Custom),
                            iconPackTarget = IconCacheTarget.HOME,
                            activityClass = appModel.activityClass
                        )

                        // Use the drawable
                        val recoloredDrawable: Drawable? = getSystemIcons(
                            context,
                            prefs,
                            IconCacheTarget.HOME,
                            nonNullDrawable
                        )

                        val drawableToUse = recoloredDrawable ?: nonNullDrawable

                        // Set the icon size to match text size and add padding
                        var iconSize = (prefs.appSize * 1.4f).toInt()
                        if (prefs.iconPackHome == Constants.IconPacks.System || prefs.iconPackHome == Constants.IconPacks.Custom) {
                            iconSize *= 2
                        }
                        val iconPadding = (iconSize / 1.2f).toInt() // padding next to icon

                        drawableToUse.setBounds(0, 0, iconSize, iconSize)

                        // Set drawable position based on alignment
                        when (prefs.homeAlignment) {
                            Constants.Gravity.Left -> {
                                setCompoundDrawables(
                                    drawableToUse,
                                    null,
                                    null,
                                    null
                                )
                                // Add padding between text and icon if an icon is set
                                compoundDrawablePadding = iconPadding
                            }

                            Constants.Gravity.Right -> {
                                setCompoundDrawables(
                                    null,
                                    null,
                                    drawableToUse,
                                    null
                                )
                                // Add padding between text and icon if an icon is set
                                compoundDrawablePadding = iconPadding
                            }

                            else -> setCompoundDrawables(null, null, null, null)
                        }

                        val nm = NotificationManagerCompat.getEnabledListenerPackages(context)

                        if (nm.contains(context.packageName)) {
                            fun getCircledDigit(number: Int): String {
                                return when {
                                    number in 1..9 -> ('\u278A' + (number - 1)).toString() // ➊…➒
                                    number >= 10 -> '\u2789'.toString() // always ➓ for 10 or more
                                    else -> "" // no badge if 0 or invalid
                                }
                            }

                            val listener: (Map<String, Int>) -> Unit = { counts ->
                                val count = counts[packageName] ?: 0

                                this.text = if (count > 0) {
                                    val circledNumber = getCircledDigit(count)
                                    val newText = "${appModel.activityLabel} $circledNumber"
                                    val spannable = SpannableString(newText)

                                    val start = newText.indexOf(circledNumber)
                                    val end = start + circledNumber.length

                                    // Change size of the circled digit
                                    spannable.setSpan(
                                        AbsoluteSizeSpan((this.textSize * 0.8f).toInt(), false),
                                        start, end,
                                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                                    )

                                    // Set color
                                    val customColor = ColorIconsExtensions.getDominantColor(nonNullDrawable)
                                    spannable.setSpan(
                                        ForegroundColorSpan(customColor),
                                        start, end,
                                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                                    )

                                    // Move emoji up slightly
                                    spannable.setSpan(
                                        SuperscriptSpan(),
                                        start, end,
                                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                                    )

                                    spannable
                                } else {
                                    appModel.activityLabel
                                }

                                AppLogger.d("HomeFragment", "Notification count updated for $packageName: $count")
                            }

                            // Register listener for this TextView
                            NotificationDotManager.registerListener(listener)

                            // Make sure we update immediately based on current counts
                            listener(NotificationDotManager.getAllCounts())

                            // Unregister when detached
                            this.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                                override fun onViewAttachedToWindow(v: View) {}
                                override fun onViewDetachedFromWindow(v: View) {
                                    NotificationDotManager.unregisterListener(listener)
                                }
                            })

                        } else {
                            AppLogger.d("HomeFragment", "Notification listener permission not enabled for ${context.packageName}")
                        }
                    }
                }
                // Add the view to the layout
                binding.homeAppsLayout.addView(homeAppLabel)
            }
        } else if (diff < 0) {
            // Remove extra apps
            binding.homeAppsLayout.removeViews(oldAppsNum + diff, -diff)
        }

        // Update the total number of pages and calculate maximum apps per page
        updatePagesAndAppsPerPage(prefs.homeAppsNum, prefs.homePagesNum)
        adjustTextViewMargins()
    }

    private val homeScreenPager = "HomeScreenPager"

    private var currentPage = 0
    private lateinit var pageRanges: List<IntRange>

    private fun updatePagesAndAppsPerPage(totalApps: Int, totalPages: Int) {
        AppLogger.d(homeScreenPager, "updatePagesAndAppsPerPage: totalApps=$totalApps, totalPages=$totalPages")

        if (totalPages <= 0) {
            pageRanges = emptyList()
            AppLogger.d(homeScreenPager, "No pages to show. pageRanges cleared.")
            return
        }

        val baseAppsPerPage = totalApps / totalPages
        val extraApps = totalApps % totalPages
        AppLogger.d(homeScreenPager, "Base apps per page: $baseAppsPerPage, extra apps: $extraApps")

        var startIdx = 0
        pageRanges = List(totalPages) { page ->
            val appsThisPage = baseAppsPerPage + if (page < extraApps) 1 else 0
            val endIdx = startIdx + appsThisPage
            val range = startIdx until endIdx
            AppLogger.d(homeScreenPager, "Page $page → range $range (appsThisPage=$appsThisPage)")
            startIdx = endIdx
            range
        }

        if (currentPage >= pageRanges.size) {
            currentPage = 0
            AppLogger.d(homeScreenPager, "Current page reset to 0 as it was out of bounds")
        }

        updateAppsVisibility()
    }

    private fun updateAppsVisibility() {
        if (pageRanges.isEmpty() || currentPage !in pageRanges.indices) {
            AppLogger.d(homeScreenPager, "updateAppsVisibility: Invalid currentPage=$currentPage or empty pageRanges")
            return
        }

        val visibleRange = pageRanges[currentPage]
        AppLogger.d(homeScreenPager, "Showing apps for currentPage=$currentPage, visibleRange=$visibleRange")

        for (i in 0 until getTotalAppsCount()) {
            val view = binding.homeAppsLayout.getChildAt(i)
            view.isVisible = i in visibleRange
        }

        // Update page selector icons
        val totalPages = pageRanges.size
        val pageSelectorIcons = MutableList(totalPages) { R.drawable.ic_new_page }
        pageSelectorIcons[currentPage] = R.drawable.ic_current_page

        val spannable = SpannableStringBuilder()
        pageSelectorIcons.forEach { drawableRes ->
            val drawable = ContextCompat.getDrawable(requireContext(), drawableRes)?.apply {
                setBounds(0, 0, intrinsicWidth, intrinsicHeight)
                colorFilter = PorterDuffColorFilter(prefs.appColor, PorterDuff.Mode.SRC_IN)
            }
            val imageSpan = drawable?.let { ImageSpan(it, ImageSpan.ALIGN_BASELINE) }

            val placeholder = SpannableString(" ") // Placeholder
            imageSpan?.let { placeholder.setSpan(it, 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }

            spannable.append(placeholder)
            spannable.append(" ") // Space
        }

        binding.homeScreenPager.text = spannable
        if (prefs.homePagesNum > 1 && prefs.homePager) binding.homeScreenPager.isVisible = true
        if (prefs.showFloating) binding.fabLayout.isVisible = true
    }

    private fun navigateToPreviousPage() {
        val totalPages = pageRanges.size
        if (totalPages <= 0) {
            AppLogger.d(homeScreenPager, "handleSwipeLeft: No pages to swipe")
            return
        }

        currentPage = if (currentPage == 0) {
            totalPages - 1
        } else {
            currentPage - 1
        }

        AppLogger.d(homeScreenPager, "handleSwipeLeft: currentPage now $currentPage")
        updateAppsVisibility()
    }

    private fun navigateToNextPage() {
        val totalPages = pageRanges.size
        if (totalPages <= 0) {
            AppLogger.d(homeScreenPager, "handleSwipeRight: No pages to swipe")
            return
        }

        currentPage = if (currentPage == totalPages - 1) {
            0
        } else {
            currentPage + 1
        }

        AppLogger.d(homeScreenPager, "handleSwipeRight: currentPage now $currentPage")
        updateAppsVisibility()
    }

    private fun getTotalAppsCount(): Int {
        val count = binding.homeAppsLayout.childCount
        AppLogger.d(homeScreenPager, "getTotalAppsCount: $count")
        return count
    }

    private fun trySettings() {
        lifecycleScope.launch(Dispatchers.Main) {
            if (prefs.settingsLocked) {
                biometricHelper.startBiometricSettingsAuth(object :
                    BiometricHelper.CallbackSettings {
                    override fun onAuthenticationSucceeded() {
                        sendToSettingFragment()
                    }

                    override fun onAuthenticationFailed() {
                        AppLogger.e(
                            "Authentication",
                            getLocalizedString(R.string.text_authentication_failed)
                        )
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errorMessage: CharSequence?
                    ) {
                        when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED -> AppLogger.e(
                                "Authentication",
                                getLocalizedString(R.string.text_authentication_cancel)
                            )

                            else ->
                                AppLogger.e(
                                    "Authentication",
                                    getLocalizedString(R.string.text_authentication_error).format(
                                        errorMessage,
                                        errorCode
                                    )
                                )
                        }
                    }
                })
            } else {
                sendToSettingFragment()
            }
        }
    }

    private fun sendToSettingFragment() {
        try {
            findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
            viewModel.firstOpen(false)
        } catch (e: java.lang.Exception) {
            AppLogger.d("onLongClick", e.toString())
        }
    }

    private fun View.getHomeScreenGestureListener() {
        this.attachGestureManager(requireContext(), object : GestureAdapter() {
            override fun onSingleTap() {
                if (homeEditing()) exitEditMode()
            }

            override fun onShortSwipeLeft() {
                if (homeEditing()) {
                    exitEditMode()
                    return
                }
                when (val action = prefs.shortSwipeLeftAction) {
                    Action.OpenApp -> openSwipeLeftApp()
                    else -> handleOtherAction(action)
                }
                CrashHandler.logUserAction("SwipeLeft Short Gesture")
            }

            override fun onLongSwipeLeft() {
                if (homeEditing()) {
                    exitEditMode()
                    return
                }
                when (val action = prefs.longSwipeLeftAction) {
                    Action.OpenApp -> openLongSwipeLeftApp()
                    else -> handleOtherAction(action)
                }
                CrashHandler.logUserAction("SwipeLeft Long Gesture")
            }

            override fun onShortSwipeRight() {
                if (homeEditing()) {
                    exitEditMode()
                    return
                }
                when (val action = prefs.shortSwipeRightAction) {
                    Action.OpenApp -> openSwipeRightApp()
                    else -> handleOtherAction(action)
                }
                CrashHandler.logUserAction("SwipeRight Short Gesture")
            }

            override fun onLongSwipeRight() {
                if (homeEditing()) {
                    exitEditMode()
                    return
                }
                when (val action = prefs.longSwipeRightAction) {
                    Action.OpenApp -> openLongSwipeRightApp()
                    else -> handleOtherAction(action)
                }
                CrashHandler.logUserAction("SwipeRight Long Gesture")
            }

            override fun onShortSwipeUp() {
                if (homeEditing()) {
                    exitEditMode()
                    return
                }
                when (val action = prefs.shortSwipeUpAction) {
                    Action.OpenApp -> openSwipeUpApp()
                    else -> handleOtherAction(action)
                }
                CrashHandler.logUserAction("SwipeUp Short Gesture")
            }

            override fun onLongSwipeUp() {
                if (homeEditing()) {
                    exitEditMode()
                    return
                }
                when (val action = prefs.longSwipeUpAction) {
                    Action.OpenApp -> openLongSwipeUpApp()
                    else -> handleOtherAction(action)
                }
                CrashHandler.logUserAction("SwipeUp Long Gesture")
            }

            override fun onShortSwipeDown() {
                if (homeEditing()) {
                    exitEditMode()
                    return
                }
                when (val action = prefs.shortSwipeDownAction) {
                    Action.OpenApp -> openSwipeDownApp()
                    else -> handleOtherAction(action)
                }
                CrashHandler.logUserAction("SwipeDown Short Gesture")
            }

            override fun onLongSwipeDown() {
                if (homeEditing()) {
                    exitEditMode()
                    return
                }
                when (val action = prefs.longSwipeDownAction) {
                    Action.OpenApp -> openLongSwipeDownApp()
                    else -> handleOtherAction(action)
                }
                CrashHandler.logUserAction("SwipeDown Long Gesture")
            }

            override fun onLongPress() {
                if (homeEditing()) return
                CrashHandler.logUserAction("LongPress Gesture")
                // Long-press on empty space edits the home list; Settings lives in the edit bar
                enterEditMode()
            }

            override fun onDoubleTap() {
                if (homeEditing()) {
                    exitEditMode()
                    return
                }
                when (val action = prefs.doubleTapAction) {
                    Action.OpenApp -> openDoubleTapApp()
                    else -> handleOtherAction(action)
                }
                CrashHandler.logUserAction("DoubleTap Gesture")
            }
        })
    }

    /** True if [x] (row coordinates) falls on the label or its icon, with a little slack. */
    private fun isOnLabel(row: View, x: Float): Boolean {
        val tv = row as? TextView ?: return true
        val textW = tv.paint.measureText(tv.text?.toString() ?: "")
        val icons = tv.compoundDrawables.filterNotNull().sumOf { it.bounds.width() } +
            (if (tv.compoundDrawables.any { it != null }) tv.compoundDrawablePadding else 0)
        val content = textW + icons
        val slack = 16 * resources.displayMetrics.density
        val w = tv.width.toFloat()
        val (start, end) = when (tv.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
            Gravity.RIGHT, Gravity.END -> (w - tv.paddingRight - content) to (w - tv.paddingRight)
            Gravity.CENTER_HORIZONTAL -> ((w - content) / 2f) to ((w + content) / 2f)
            else -> tv.paddingLeft.toFloat() to (tv.paddingLeft + content)
        }
        return x >= start - slack && x <= end + slack
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.getHomeAppsGestureListener() {
        val row = this
        var downX = 0f
        val manager = GestureManager(requireContext(), object : GestureAdapter() {

            override fun onLongPress() {
                // Rows span the full width for easy tapping; holding the empty part edits the list
                if (isOnLabel(row, downX)) textOnLongClick(row) else enterEditMode()
            }

            override fun onSingleTap() {
                textOnClick(this@getHomeAppsGestureListener)
            }

            override fun onShortSwipeLeft() {
                when (val action = prefs.shortSwipeLeftAction) {
                    Action.OpenApp -> openSwipeLeftApp()
                    else -> handleOtherAction(action)
                }
                CrashHandler.logUserAction("SwipeLeft Short Gesture")
            }

            override fun onLongSwipeLeft() {
                when (val action = prefs.longSwipeLeftAction) {
                    Action.OpenApp -> openLongSwipeLeftApp()
                    else -> handleOtherAction(action)
                }
                CrashHandler.logUserAction("SwipeLeft Long Gesture")
            }

            override fun onShortSwipeRight() {
                when (val action = prefs.shortSwipeRightAction) {
                    Action.OpenApp -> openSwipeRightApp()
                    else -> handleOtherAction(action)
                }
                CrashHandler.logUserAction("SwipeRight Short Gesture")
            }

            override fun onLongSwipeRight() {
                when (val action = prefs.longSwipeRightAction) {
                    Action.OpenApp -> openLongSwipeRightApp()
                    else -> handleOtherAction(action)
                }
                CrashHandler.logUserAction("SwipeRight Long Gesture")
            }

            override fun onShortSwipeUp() {
                when (val action = prefs.shortSwipeUpAction) {
                    Action.OpenApp -> openSwipeUpApp()
                    else -> handleOtherAction(action)
                }
                CrashHandler.logUserAction("SwipeUp Short Gesture")
            }

            override fun onLongSwipeUp() {
                when (val action = prefs.longSwipeUpAction) {
                    Action.OpenApp -> openLongSwipeUpApp()
                    else -> handleOtherAction(action)
                }
                CrashHandler.logUserAction("SwipeUp Long Gesture")
            }

            override fun onShortSwipeDown() {
                when (val action = prefs.shortSwipeDownAction) {
                    Action.OpenApp -> openSwipeDownApp()
                    else -> handleOtherAction(action)
                }
                CrashHandler.logUserAction("SwipeDown Short Gesture")
            }

            override fun onLongSwipeDown() {
                when (val action = prefs.longSwipeDownAction) {
                    Action.OpenApp -> openLongSwipeDownApp()
                    else -> handleOtherAction(action)
                }
                CrashHandler.logUserAction("SwipeDown Long Gesture")
            }
        })
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) downX = event.x
            manager.onTouchEvent(event)
        }
    }


    // ------------------------------------------------------------------ home edit mode
    //
    // Long-press on empty space: rows jiggle and can be dragged into a new order. Taps on rows
    // don't launch while editing. Tap empty space, back, home or Done to leave.

    private var editBackCallback: OnBackPressedCallback? = null

    private fun homeEditing(): Boolean = _binding?.homeAppsLayout?.editMode == true

    private fun setupEditMode() {
        val b = binding
        b.homeAppsLayout.onReorder = { from, to -> reorderHomeApps(from, to) }
        b.editDone.setOnClickListener { exitEditMode() }
        b.editSettings.setOnClickListener {
            exitEditMode(animate = false)
            trySettings()
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.editBar) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            (v.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin =
                nav + (24 * resources.displayMetrics.density).toInt()
            v.requestLayout()
            insets
        }
        val cb = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = exitEditMode()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, cb)
        editBackCallback = cb
    }

    private fun enterEditMode() {
        val b = _binding ?: return
        if (drawerProgress > 0f || homeEditing()) return
        b.homeAppsLayout.enterEditMode()
        b.editBar.animate().cancel()
        b.editBar.visibility = View.VISIBLE
        b.editBar.animate().alpha(1f).setDuration(180).start()
        editBackCallback?.isEnabled = true
    }

    fun exitEditMode(animate: Boolean = true) {
        val b = _binding ?: return
        if (!homeEditing()) return
        b.homeAppsLayout.exitEditMode(animate)
        editBackCallback?.isEnabled = false
        b.editBar.animate().cancel()
        if (animate) {
            b.editBar.animate().alpha(0f).setDuration(160)
                .withEndAction { _binding?.editBar?.visibility = View.GONE }.start()
        } else {
            b.editBar.alpha = 0f
            b.editBar.visibility = View.GONE
        }
    }

    /** Persist a drag: move slot [from] to [to], shifting the slots in between, then rebuild rows. */
    private fun reorderHomeApps(from: Int, to: Int) {
        val count = prefs.homeAppsNum
        if (from !in 0 until count || to !in 0 until count || from == to) return
        val apps = (0 until count).map { prefs.getHomeAppModel(it) }.toMutableList()
        apps.add(to, apps.removeAt(from))
        apps.forEachIndexed { i, app -> prefs.setHomeAppModel(i, app) }
        // The views were already moved by the list itself; nothing to rebuild
        context?.let { updateHomeWidget(it) }
    }

    // ------------------------------------------------------------------ app drawer overlay
    //
    // The drawer lives in drawerContainer as a child fragment and is revealed by a progress value
    // (0 = hidden, 1 = open). Dragging maps finger travel 1:1 to progress; release settles with a
    // short decelerating animation. Home content fades and shrinks a little as the drawer arrives.

    private var drawerProgress = 0f
    private var drawerTargetOpen = false
    private var dragStartProgress = 0f
    private var drawerAnimator: ValueAnimator? = null
    private var drawerBackCallback: OnBackPressedCallback? = null

    private val drawerFragment: AppDrawerFragment?
        get() = if (isAdded) childFragmentManager.findFragmentById(R.id.drawerContainer) as? AppDrawerFragment else null

    /** Swipe up opens the drawer interactively only when that's what the gesture is set to do. */
    private fun interactiveDrawerEnabled() = prefs.shortSwipeUpAction == Action.ShowAppList

    private fun setupDrawer(savedInstanceState: Bundle?) {
        // A fresh drawer for every home view. Reusing the old child across a view rebuild (coming
        // back from settings, especially after the activity saved its state for a picker) left
        // its list attached to a dead hierarchy: rows never laid out until the process restarted.
        attachFreshDrawer()

        binding.homeRoot.callback = object : VerticalDragLayout.Callback {
            override fun shouldStartDrag(downX: Float, downY: Float, dy: Float): Boolean =
                dy < 0 && drawerProgress == 0f && !homeEditing() && interactiveDrawerEnabled() && drawerFragment != null

            override fun onDragStart() = onDrawerDragStart()
            override fun onDrag(dy: Float) = onDrawerDrag(dy)
            override fun onDragEnd(velocityY: Float) = onDrawerDragEnd(velocityY)
        }

        val callback = object : OnBackPressedCallback(false) {
            private var backStartProgress = 1f

            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                drawerAnimator?.cancel()
                backStartProgress = drawerProgress
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                // Predictive back: the drawer sinks a little while the gesture is in progress
                applyDrawerProgress(backStartProgress * (1f - 0.2f * backEvent.progress))
            }

            override fun handleOnBackCancelled() = animateDrawerTo(open = true)

            override fun handleOnBackPressed() = animateDrawerTo(open = false)
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        drawerBackCallback = callback

        val reopen = savedInstanceState?.getBoolean(STATE_DRAWER_OPEN) == true
        drawerTargetOpen = reopen
        // Layout isn't done yet; apply once sizes are known
        binding.homeRoot.post { if (_binding != null) applyDrawerProgress(if (reopen) 1f else 0f) }
    }

    private fun attachFreshDrawer() {
        childFragmentManager.beginTransaction()
            .replace(R.id.drawerContainer, AppDrawerFragment.newEmbedded())
            .commitNowAllowingStateLoss()
    }

    /** Guard: if the drawer's list is detached or stuck without rows, swap in a new drawer. */
    private fun ensureDrawerHealthy() {
        if (!isAdded || _binding == null) return
        val drawer = drawerFragment
        if (drawer == null || !drawer.isListHealthy()) {
            AppLogger.w("HomeFragment", "Drawer list was not healthy; rebuilding it")
            attachFreshDrawer()
        }
    }

    /** Open the drawer with an animation, optionally pre-filling the search field. */
    fun openDrawer(query: String? = null) {
        ensureDrawerHealthy()
        val drawer = drawerFragment ?: return
        drawer.onDrawerOpening()
        if (query != null) drawer.setSearchQuery(query)
        animateDrawerTo(open = true)
    }

    override fun closeDrawer(animate: Boolean) {
        if (drawerProgress == 0f && !drawerTargetOpen) return
        if (animate) {
            animateDrawerTo(open = false)
        } else {
            drawerAnimator?.cancel()
            drawerTargetOpen = false
            drawerFragment?.onDrawerClosing()
            applyDrawerProgress(0f)
            drawerFragment?.onDrawerClosed()
        }
    }

    override fun isDrawerFullyOpen(): Boolean = drawerTargetOpen && drawerProgress >= 1f

    override fun onDrawerDragStart() {
        drawerAnimator?.cancel()
        dragStartProgress = drawerProgress
        if (drawerProgress == 0f) {
            ensureDrawerHealthy()
            drawerFragment?.onDrawerOpening()
        }
    }

    override fun onDrawerDrag(dy: Float) {
        applyDrawerProgress(dragStartProgress - dy / drawerTravel())
    }

    override fun onDrawerDragEnd(velocityY: Float) {
        val fling = FLING_DP_PER_S * resources.displayMetrics.density
        val opening = dragStartProgress < 0.5f
        animateDrawerTo(shouldSettleOpen(velocityY, drawerProgress, fling, opening), velocityY)
    }

    private fun drawerTravel(): Float {
        val h = _binding?.drawerContainer?.height ?: 0
        return (if (h > 0) h else resources.displayMetrics.heightPixels).toFloat()
    }

    private fun applyDrawerProgress(p: Float) {
        val b = _binding ?: return
        drawerProgress = p.coerceIn(0f, 1f)
        val visible = drawerProgress > 0f
        if (b.drawerContainer.isVisible != visible) {
            b.drawerContainer.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            // No foil animation behind the drawer
            b.clockSticker.setActive(!visible && isResumed)
        }
        drawerFragment?.applyReveal(drawerProgress, drawerTravel().toInt())

        // Home fades out over the first part of the travel and shrinks slightly
        b.mainLayout.alpha = (1f - drawerProgress * 1.5f).coerceIn(0f, 1f)
        val scale = 1f - 0.05f * drawerProgress
        b.mainLayout.scaleX = scale
        b.mainLayout.scaleY = scale
        val moving = drawerProgress > 0f && drawerProgress < 1f
        val layer = if (moving) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        if (b.mainLayout.layerType != layer) b.mainLayout.setLayerType(layer, null)

        drawerBackCallback?.isEnabled = visible
    }

    private fun animateDrawerTo(open: Boolean, velocityY: Float = 0f) {
        if (_binding == null) return
        drawerAnimator?.cancel()
        drawerTargetOpen = open
        if (!open) drawerFragment?.onDrawerClosing()

        val target = if (open) 1f else 0f
        val distance = abs(target - drawerProgress)
        if (distance < 0.001f) {
            applyDrawerProgress(target)
            onDrawerSettled(open)
            return
        }

        // ~280ms for a full open, less for a short hop; a fast fling shortens it further
        val travelPx = distance * drawerTravel()
        var duration = (280f * distance).coerceIn(160f, 280f)
        val speed = abs(velocityY)
        if (speed > 0f) duration = duration.coerceAtMost((travelPx / speed * 1000f * 2.2f).coerceAtLeast(160f))

        var cancelled = false
        drawerAnimator = ValueAnimator.ofFloat(drawerProgress, target).apply {
            this.duration = duration.toLong()
            interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f) // emphasized decelerate
            addUpdateListener { applyDrawerProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) onDrawerSettled(open)
                }
            })
            start()
        }
    }

    private fun onDrawerSettled(open: Boolean) {
        updateStickerActive()
        val drawer = drawerFragment ?: return
        if (open) drawer.onDrawerOpened() else drawer.onDrawerClosed()
    }

    companion object {
        private const val STATE_DRAWER_OPEN = "drawerOpen"
        private const val FLING_DP_PER_S = 1000f

        /**
         * A fling (|v| above [flingPx], negative = up) decides by direction. Otherwise the drawer
         * favours the direction of the gesture: pulling it up only needs 35% of the way, pushing
         * it down needs to get below 65%.
         */
        internal fun shouldSettleOpen(velocityY: Float, progress: Float, flingPx: Float, opening: Boolean): Boolean = when {
            velocityY < -flingPx -> true
            velocityY > flingPx -> false
            opening -> progress > 0.35f
            else -> progress > 0.65f
        }
    }
}
