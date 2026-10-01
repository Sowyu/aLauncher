package com.github.codeworkscreativehub.mlauncher.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Process
import android.os.UserManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.github.codeworkscreativehub.common.LauncherLocaleManager
import com.github.codeworkscreativehub.common.LocalizedResources
import com.github.codeworkscreativehub.common.getLocalizedString
import com.github.codeworkscreativehub.common.isBiometricEnabled
import com.github.codeworkscreativehub.common.showShortToast
import com.github.codeworkscreativehub.mlauncher.BuildConfig
import com.github.codeworkscreativehub.mlauncher.MainActivity
import com.github.codeworkscreativehub.mlauncher.MainViewModel
import com.github.codeworkscreativehub.mlauncher.R
import com.github.codeworkscreativehub.mlauncher.data.AppCategory
import com.github.codeworkscreativehub.mlauncher.data.AppListItem
import com.github.codeworkscreativehub.mlauncher.data.Constants
import com.github.codeworkscreativehub.mlauncher.data.Constants.Action
import com.github.codeworkscreativehub.mlauncher.data.Constants.AppDrawerFlag
import com.github.codeworkscreativehub.mlauncher.data.Prefs
import com.github.codeworkscreativehub.mlauncher.databinding.FragmentSettingsBinding
import com.github.codeworkscreativehub.mlauncher.helper.DrawerBackground
import com.github.codeworkscreativehub.mlauncher.helper.IconCacheTarget
import com.github.codeworkscreativehub.mlauncher.helper.emptyString
import com.github.codeworkscreativehub.mlauncher.helper.hideNavigationBar
import com.github.codeworkscreativehub.mlauncher.helper.hideStatusBar
import com.github.codeworkscreativehub.mlauncher.helper.isSystemInDarkMode
import com.github.codeworkscreativehub.mlauncher.helper.ismlauncherDefault
import com.github.codeworkscreativehub.mlauncher.helper.openAppInfo
import com.github.codeworkscreativehub.mlauncher.helper.reloadLauncher
import com.github.codeworkscreativehub.mlauncher.helper.showNavigationBar
import com.github.codeworkscreativehub.mlauncher.helper.showStatusBar
import com.github.codeworkscreativehub.mlauncher.helper.updateHomeWidget
import com.github.codeworkscreativehub.mlauncher.helper.utils.AppReloader
import com.github.codeworkscreativehub.mlauncher.style.SettingsTheme
import com.github.codeworkscreativehub.mlauncher.style.SettingsType
import com.github.codeworkscreativehub.mlauncher.ui.compose.ActionsDialog
import com.github.codeworkscreativehub.mlauncher.ui.compose.CardGap
import com.github.codeworkscreativehub.mlauncher.ui.compose.CardNote
import com.github.codeworkscreativehub.mlauncher.ui.compose.CategoryRow
import com.github.codeworkscreativehub.mlauncher.ui.compose.ColorPickerDialog
import com.github.codeworkscreativehub.mlauncher.ui.compose.ColorRow
import com.github.codeworkscreativehub.mlauncher.ui.compose.ConfirmDialog
import com.github.codeworkscreativehub.mlauncher.ui.compose.DialogAction
import com.github.codeworkscreativehub.mlauncher.ui.compose.FlagsDialog
import com.github.codeworkscreativehub.mlauncher.ui.compose.OptionsDialog
import com.github.codeworkscreativehub.mlauncher.ui.compose.SearchField
import com.github.codeworkscreativehub.mlauncher.ui.compose.SectionHeader
import com.github.codeworkscreativehub.mlauncher.ui.compose.SelectRow
import com.github.codeworkscreativehub.mlauncher.ui.compose.SettingsCard
import com.github.codeworkscreativehub.mlauncher.ui.compose.SettingsScaffold
import com.github.codeworkscreativehub.mlauncher.ui.compose.SliderRow
import com.github.codeworkscreativehub.mlauncher.ui.compose.SwitchRow
import com.github.codeworkscreativehub.mlauncher.ui.iconpack.CustomIconSelectionActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val MAIN = "main"

/** Actions for features that are not part of this build. Compared by name so this file compiles with or without them. */
private val removedActionNames = setOf("TogglePrivateSpace", "ShowWidgetPage", "ShowNotesManager", "ShowDigitalWellbeing")

/**
 * Settings categories. [searchTerms] are the row titles on that screen; the search box on the
 * main screen looks through them. Keep them in step with the rows in the matching *Page() function.
 */
private enum class Page(
    @StringRes val title: Int,
    @StringRes val subtitle: Int,
    @DrawableRes val icon: Int,
    val searchTerms: List<Int>,
) {
    Home(
        R.string.st_cat_home, R.string.st_cat_home_sub, R.drawable.ic_order_apps, listOf(
            R.string.st_reorder_apps, R.string.st_home_apps, R.string.st_home_pages, R.string.st_home_pager,
            R.string.st_lock_home, R.string.st_home_alignment, R.string.st_align_bottom, R.string.st_app_text_size,
            R.string.st_padding, R.string.st_extend_area, R.string.st_home_icons, R.string.st_app_colour,
            R.string.st_show_shortcuts, R.string.st_shortcut_buttons, R.string.st_shortcut_colour,
        )
    ),
    Clock(
        R.string.st_cat_clock, R.string.st_cat_clock_sub, R.drawable.ic_alarm_clock, listOf(
            R.string.st_show_clock, R.string.st_show_ampm, R.string.st_clock_size, R.string.st_clock_colour,
            R.string.st_clock_alignment, R.string.st_show_date, R.string.st_show_day_of_year, R.string.st_date_size,
            R.string.st_date_colour, R.string.st_date_alignment, R.string.st_clock_tap, R.string.st_date_tap,
        )
    ),
    Look(
        R.string.st_cat_look, R.string.st_cat_look_sub, R.drawable.ic_look_feel, listOf(
            R.string.st_theme_mode, R.string.st_font, R.string.st_background_colour,
            R.string.st_transparent_background, R.string.st_background_transparency,
            R.string.st_status_bar, R.string.st_nav_bar,
        )
    ),
    Drawer(
        R.string.st_cat_drawer, R.string.st_cat_drawer_sub, R.drawable.ic_search, listOf(
            R.string.st_hide_search, R.string.st_auto_keyboard, R.string.st_search_engine,
            R.string.st_web_search_button, R.string.st_fuzzy, R.string.st_search_from_start,
            R.string.st_fuzzy_strength, R.string.st_auto_open, R.string.st_open_on_enter, R.string.st_az_sidebar,
            R.string.st_drawer_alignment, R.string.st_drawer_icons, R.string.st_long_press_menu,
            R.string.st_hidden_apps, R.string.st_drawer_background, R.string.st_blur,
        )
    ),
    Gestures(
        R.string.st_cat_gestures, R.string.st_cat_gestures_sub, R.drawable.ic_gestures, listOf(
            R.string.st_double_tap, R.string.st_logo_tap, R.string.st_swipe_up, R.string.st_swipe_down,
            R.string.st_swipe_left, R.string.st_swipe_right, R.string.st_long_swipe_up, R.string.st_long_swipe_down,
            R.string.st_long_swipe_left, R.string.st_long_swipe_right, R.string.st_short_threshold,
            R.string.st_long_threshold,
        )
    ),
    Backup(
        R.string.st_cat_backup, R.string.st_cat_backup_sub, R.drawable.ic_backup_restore, listOf(
            R.string.st_backup_full, R.string.st_restore_full, R.string.st_export_colours,
            R.string.st_import_colours, R.string.st_reset_all,
        )
    ),
    About(
        R.string.st_cat_about, R.string.st_cat_about_sub, R.drawable.ic_advanced, listOf(
            R.string.st_version, R.string.st_app_info, R.string.st_source_code, R.string.st_set_default,
            R.string.st_change_default, R.string.st_restart, R.string.st_exit, R.string.st_language,
            R.string.st_lock_orientation, R.string.st_force_wallpaper, R.string.st_lock_settings, R.string.st_haptic,
        )
    ),
}

/** Cards on the main screen. */
private val mainGroups = listOf(
    listOf(Page.Home, Page.Clock, Page.Look, Page.Drawer),
    listOf(Page.Gestures, Page.Backup),
    listOf(Page.About),
)

private class SearchHit(val title: String, val page: Page)

/** One gesture setting: which pref holds the action and which holds the app to open. */
private class Gesture(
    @StringRes val title: Int,
    val flag: AppDrawerFlag,
    val action: (Prefs) -> Action,
    val app: (Prefs) -> AppListItem,
)

class SettingsFragment : BaseFragment() {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    /** Dialog shown on top of settings. Plain state, so it closes on rotation. */
    private var dialog by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var themeMode by mutableStateOf(Constants.Theme.System)

    /** Bumped in onResume so values changed by other activities (icon pack picker) are re-read. */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        prefs = Prefs(requireContext())
        themeMode = prefs.appTheme
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (prefs.firstSettingsOpen) prefs.firstSettingsOpen = false

        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        viewModel.ismlauncherDefault()

        binding.settingsView.setContent { Root() }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dialog = null
        _binding = null
    }

    // -----------------------------------------------------------------------------------------
    // Structure
    // -----------------------------------------------------------------------------------------

    @Composable
    private fun Root() {
        val context = LocalContext.current
        val isDark = when (themeMode) {
            Constants.Theme.Light -> false
            Constants.Theme.Dark -> true
            Constants.Theme.System -> isSystemInDarkMode(context)
        }

        SettingsTheme(isDark) {
            var screen by rememberSaveable { mutableStateOf(MAIN) }
            // Keeps each screen's saveable state (search text, scroll position) while another is shown.
            val stateHolder = rememberSaveableStateHolder()

            Box(
                Modifier
                    .fillMaxSize()
                    .background(SettingsTheme.palette.background)
            ) {
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        val forward = targetState != MAIN
                        val dir = if (forward) 1 else -1
                        (slideInHorizontally(tween(250)) { dir * it / 4 } + fadeIn(tween(250)))
                            .togetherWith(slideOutHorizontally(tween(250)) { -dir * it / 4 } + fadeOut(tween(200)))
                    },
                    label = "settingsScreen"
                ) { target ->
                    stateHolder.SaveableStateProvider(target) {
                        val page = Page.entries.firstOrNull { it.name == target }
                        if (page == null) {
                            MainScreen { screen = it.name }
                        } else {
                            BackHandler { screen = MAIN }
                            SettingsScaffold(title = getLocalizedString(page.title), onBack = { screen = MAIN }) {
                                when (page) {
                                    Page.Home -> HomePage()
                                    Page.Clock -> ClockPage()
                                    Page.Look -> LookPage()
                                    Page.Drawer -> DrawerPage()
                                    Page.Gestures -> GesturesPage()
                                    Page.Backup -> BackupPage()
                                    Page.About -> AboutPage()
                                }
                            }
                        }
                    }
                }
            }

            dialog?.invoke()
        }
    }

    @Composable
    private fun MainScreen(open: (Page) -> Unit) {
        var query by rememberSaveable { mutableStateOf("") }
        val palette = SettingsTheme.palette

        SettingsScaffold(title = getLocalizedString(R.string.st_settings), onBack = null) {
            SearchField(query) { query = it }
            Spacer(Modifier.height(20.dp))

            val q = query.trim()
            if (q.isEmpty()) {
                mainGroups.forEachIndexed { i, group ->
                    if (i > 0) CardGap()
                    SettingsCard {
                        group.forEach { page ->
                            CategoryRow(
                                icon = page.icon,
                                title = getLocalizedString(page.title),
                                subtitle = getLocalizedString(page.subtitle),
                                onClick = { open(page) }
                            )
                        }
                    }
                }
            } else {
                val hits = remember(q) { search(q) }
                if (hits.isEmpty()) {
                    Text(
                        getLocalizedString(R.string.st_no_results),
                        style = SettingsType.rowTitle,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp)
                    )
                } else {
                    SettingsCard {
                        hits.forEach { hit ->
                            SelectRow(
                                title = hit.title,
                                subtitle = getLocalizedString(hit.page.title),
                                showChevron = true,
                                onClick = { open(hit.page) }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun search(query: String): List<SearchHit> =
        Page.entries.flatMap { page ->
            (listOf(page.title) + page.searchTerms).map { SearchHit(getLocalizedString(it), page) }
        }
            .filter { it.title.contains(query, ignoreCase = true) }
            .distinctBy { it.title to it.page }

    // -----------------------------------------------------------------------------------------
    // Home screen
    // -----------------------------------------------------------------------------------------

    @Composable
    private fun HomePage() {
        val context = LocalContext.current
        var appsNum by remember { mutableIntStateOf(prefs.homeAppsNum) }
        var pagesNum by remember { mutableIntStateOf(prefs.homePagesNum) }
        var pager by remember { mutableStateOf(prefs.homePager) }
        var locked by remember { mutableStateOf(prefs.homeLocked) }
        var alignment by remember { mutableStateOf(prefs.homeAlignment) }
        var alignBottom by remember { mutableStateOf(prefs.homeAlignmentBottom) }
        var appSize by remember { mutableIntStateOf(prefs.appSize) }
        var padding by remember { mutableIntStateOf(prefs.textPaddingSize) }
        var extendArea by remember { mutableStateOf(prefs.extendHomeAppsArea) }
        var iconPack by remember(resumeTick) { mutableStateOf(prefs.iconPackHome) }
        var appColor by remember { mutableIntStateOf(prefs.appColor) }
        var showShortcuts by remember { mutableStateOf(prefs.showFloating) }
        var shortcutColor by remember { mutableIntStateOf(prefs.shortcutIconsColor) }

        val maxApps = remember(pagesNum) {
            Constants.updateMaxAppsBasedOnPages(context)
            Constants.MAX_HOME_APPS
        }
        val maxPages = appsNum.coerceIn(Constants.MIN_HOME_PAGES, 10)
        val gravityLabels = Constants.Gravity.entries.map { it.string() }

        SectionHeader(getLocalizedString(R.string.st_sec_apps))
        SettingsCard {
            SelectRow(
                title = getLocalizedString(R.string.st_reorder_apps),
                subtitle = getLocalizedString(R.string.st_reorder_apps_sub),
                showChevron = true,
                onClick = { showFavoriteApps() }
            )
            SliderRow(
                title = getLocalizedString(R.string.st_home_apps),
                value = appsNum.toFloat(),
                range = Constants.MIN_HOME_APPS.toFloat()..maxApps.toFloat(),
                format = { it.roundToInt().toString() },
                onCommit = { v ->
                    val newNum = v.roundToInt()
                    if (newNum != appsNum) {
                        setHomeAppsNum(context, oldNum = appsNum, newNum = newNum, pagesNum = pagesNum)
                        appsNum = newNum
                        pagesNum = prefs.homePagesNum
                    }
                }
            )
            SliderRow(
                title = getLocalizedString(R.string.st_home_pages),
                value = pagesNum.toFloat(),
                range = Constants.MIN_HOME_PAGES.toFloat()..maxPages.toFloat(),
                format = { it.roundToInt().toString() },
                onCommit = { v ->
                    pagesNum = v.roundToInt()
                    prefs.homePagesNum = pagesNum
                    viewModel.homePagesNum.value = pagesNum
                }
            )
            if (pagesNum > 1) {
                SwitchRow(
                    title = getLocalizedString(R.string.st_home_pager),
                    subtitle = getLocalizedString(R.string.st_home_pager_sub),
                    checked = pager
                ) {
                    pager = it
                    prefs.homePager = it
                }
            }
            SwitchRow(
                title = getLocalizedString(R.string.st_lock_home),
                subtitle = getLocalizedString(R.string.st_lock_home_sub),
                checked = locked
            ) {
                locked = it
                prefs.homeLocked = it
            }
        }

        SectionHeader(getLocalizedString(R.string.st_sec_layout))
        SettingsCard {
            SelectRow(
                title = getLocalizedString(R.string.st_home_alignment),
                value = alignment.string()
            ) {
                showOptions(
                    getLocalizedString(R.string.st_home_alignment),
                    gravityLabels,
                    Constants.Gravity.entries.indexOf(alignment)
                ) { i ->
                    alignment = Constants.Gravity.entries[i]
                    prefs.homeAlignment = alignment
                    viewModel.updateHomeAppsAlignment(prefs.homeAlignment, prefs.homeAlignmentBottom)
                }
            }
            SwitchRow(title = getLocalizedString(R.string.st_align_bottom), checked = alignBottom) {
                alignBottom = it
                prefs.homeAlignmentBottom = it
                viewModel.updateHomeAppsAlignment(prefs.homeAlignment, prefs.homeAlignmentBottom)
            }
            SliderRow(
                title = getLocalizedString(R.string.st_app_text_size),
                value = appSize.toFloat(),
                range = Constants.MIN_TEXT_SIZE.toFloat()..Constants.MAX_TEXT_SIZE.toFloat(),
                format = { it.roundToInt().toString() },
                onCommit = {
                    appSize = it.roundToInt()
                    prefs.appSize = appSize
                }
            )
            SliderRow(
                title = getLocalizedString(R.string.st_padding),
                value = padding.toFloat(),
                range = Constants.MIN_TEXT_PADDING.toFloat()..Constants.MAX_TEXT_PADDING.toFloat(),
                format = { it.roundToInt().toString() },
                onCommit = {
                    padding = it.roundToInt()
                    prefs.textPaddingSize = padding
                }
            )
            SwitchRow(
                title = getLocalizedString(R.string.st_extend_area),
                subtitle = getLocalizedString(R.string.st_extend_area_sub),
                checked = extendArea
            ) {
                extendArea = it
                prefs.extendHomeAppsArea = it
            }
        }

        SectionHeader(getLocalizedString(R.string.st_sec_appearance))
        SettingsCard {
            SelectRow(
                title = getLocalizedString(R.string.st_home_icons),
                value = iconPack.getString(IconCacheTarget.HOME.name)
            ) {
                pickIconPack(IconCacheTarget.HOME, getLocalizedString(R.string.st_home_icons), iconPack) { iconPack = it }
            }
            ColorRow(getLocalizedString(R.string.st_app_colour), appColor) {
                showColorPicker(getLocalizedString(R.string.st_app_colour), appColor) { c ->
                    appColor = c
                    prefs.appColor = c
                    updateHomeWidget(context)
                }
            }
        }

        SectionHeader(getLocalizedString(R.string.st_sec_shortcuts))
        SettingsCard {
            SwitchRow(title = getLocalizedString(R.string.st_show_shortcuts), checked = showShortcuts) {
                showShortcuts = it
                prefs.showFloating = it
            }
            FlagsRow(
                title = getLocalizedString(R.string.st_shortcut_buttons),
                key = "HOME_BUTTON_FLAGS",
                default = "0000011",
                labels = listOf(
                    R.string.home_button_phone, R.string.home_button_messages, R.string.home_button_camera,
                    R.string.home_button_photos, R.string.home_button_web, R.string.home_button_settings,
                    R.string.home_button_logo,
                ).map { getLocalizedString(it) }
            )
            ColorRow(getLocalizedString(R.string.st_shortcut_colour), shortcutColor) {
                showColorPicker(getLocalizedString(R.string.st_shortcut_colour), shortcutColor) { c ->
                    shortcutColor = c
                    prefs.shortcutIconsColor = c
                }
            }
        }
    }

    /** Same as before the redesign: clear the slots above the new count and refresh the widget. */
    private fun setHomeAppsNum(context: Context, oldNum: Int, newNum: Int, pagesNum: Int) {
        prefs.homeAppsNum = newNum
        viewModel.homeAppsNum.value = newNum

        if (newNum in 1..<pagesNum) {
            prefs.homePagesNum = newNum
            viewModel.homePagesNum.value = newNum
        }

        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        val clearApp = AppListItem(
            activityLabel = "Clear",
            activityPackage = emptyString(),
            activityClass = emptyString(),
            user = userManager.userProfiles[0],
            profileType = "SYSTEM",
            customTag = emptyString(),
            category = AppCategory.REGULAR
        )
        for (n in newNum..oldNum + 1) {
            prefs.setHomeAppModel(n, clearApp)
        }

        updateHomeWidget(context)
    }

    // -----------------------------------------------------------------------------------------
    // Clock & date
    // -----------------------------------------------------------------------------------------

    @Composable
    private fun ClockPage() {
        var showClock by remember { mutableStateOf(prefs.showClock) }
        var ampm by remember { mutableStateOf(prefs.showClockFormat) }
        var clockSize by remember { mutableIntStateOf(prefs.clockSize) }
        var clockColor by remember { mutableIntStateOf(prefs.clockColor) }
        var clockAlignment by remember { mutableStateOf(prefs.clockAlignment) }
        var showDate by remember { mutableStateOf(prefs.showDate) }
        var dayOfYear by remember { mutableStateOf(prefs.showDayOfYear) }
        var dateSize by remember { mutableIntStateOf(prefs.dateSize) }
        var dateColor by remember { mutableIntStateOf(prefs.dateColor) }
        var dateAlignment by remember { mutableStateOf(prefs.dateAlignment) }
        val gravityLabels = Constants.Gravity.entries.map { it.string() }
        val sizeRange = Constants.MIN_CLOCK_DATE_SIZE.toFloat()..Constants.MAX_CLOCK_DATE_SIZE.toFloat()

        SectionHeader(getLocalizedString(R.string.st_sec_clock))
        SettingsCard {
            SwitchRow(title = getLocalizedString(R.string.st_show_clock), checked = showClock) {
                showClock = it
                prefs.showClock = it
                viewModel.setShowClock(it)
            }
            SwitchRow(title = getLocalizedString(R.string.st_show_ampm), checked = ampm) {
                ampm = it
                prefs.showClockFormat = it
            }
            SliderRow(
                title = getLocalizedString(R.string.st_clock_size),
                value = clockSize.toFloat(),
                range = sizeRange,
                format = { it.roundToInt().toString() },
                onCommit = {
                    clockSize = it.roundToInt()
                    prefs.clockSize = clockSize
                }
            )
            ColorRow(getLocalizedString(R.string.st_clock_colour), clockColor) {
                showColorPicker(getLocalizedString(R.string.st_clock_colour), clockColor) { c ->
                    clockColor = c
                    prefs.clockColor = c
                }
            }
            SelectRow(title = getLocalizedString(R.string.st_clock_alignment), value = clockAlignment.string()) {
                showOptions(
                    getLocalizedString(R.string.st_clock_alignment),
                    gravityLabels,
                    Constants.Gravity.entries.indexOf(clockAlignment)
                ) { i ->
                    clockAlignment = Constants.Gravity.entries[i]
                    prefs.clockAlignment = clockAlignment
                    viewModel.updateClockAlignment(clockAlignment)
                }
            }
        }

        SectionHeader(getLocalizedString(R.string.st_sec_date))
        SettingsCard {
            SwitchRow(title = getLocalizedString(R.string.st_show_date), checked = showDate) {
                showDate = it
                prefs.showDate = it
                viewModel.setShowDate(it)
            }
            SwitchRow(title = getLocalizedString(R.string.st_show_day_of_year), checked = dayOfYear) {
                dayOfYear = it
                prefs.showDayOfYear = it
                viewModel.setShowDayOfYear(it)
            }
            SliderRow(
                title = getLocalizedString(R.string.st_date_size),
                value = dateSize.toFloat(),
                range = sizeRange,
                format = { it.roundToInt().toString() },
                onCommit = {
                    dateSize = it.roundToInt()
                    prefs.dateSize = dateSize
                }
            )
            ColorRow(getLocalizedString(R.string.st_date_colour), dateColor) {
                showColorPicker(getLocalizedString(R.string.st_date_colour), dateColor) { c ->
                    dateColor = c
                    prefs.dateColor = c
                }
            }
            SelectRow(title = getLocalizedString(R.string.st_date_alignment), value = dateAlignment.string()) {
                showOptions(
                    getLocalizedString(R.string.st_date_alignment),
                    gravityLabels,
                    Constants.Gravity.entries.indexOf(dateAlignment)
                ) { i ->
                    dateAlignment = Constants.Gravity.entries[i]
                    prefs.dateAlignment = dateAlignment
                    viewModel.updateDateAlignment(dateAlignment)
                }
            }
        }

        SectionHeader(getLocalizedString(R.string.st_sec_tap))
        SettingsCard {
            GestureRow(Gesture(R.string.st_clock_tap, AppDrawerFlag.SetClickClock, { it.clickClockAction }, { it.appClickClock }))
            GestureRow(Gesture(R.string.st_date_tap, AppDrawerFlag.SetClickDate, { it.clickDateAction }, { it.appClickDate }))
        }
    }

    // -----------------------------------------------------------------------------------------
    // Look
    // -----------------------------------------------------------------------------------------

    @Composable
    private fun LookPage() {
        val context = LocalContext.current
        var font by remember(resumeTick) { mutableStateOf(prefs.fontFamily) }
        var bgColor by remember { mutableIntStateOf(prefs.backgroundColor) }
        var transparent by remember { mutableStateOf(prefs.showBackground) }
        var opacity by remember { mutableIntStateOf(prefs.opacityNum) }
        var statusBar by remember { mutableStateOf(prefs.showStatusBar) }
        var navBar by remember { mutableStateOf(prefs.showNavigationBar) }

        SectionHeader(getLocalizedString(R.string.st_sec_theme))
        SettingsCard {
            SelectRow(title = getLocalizedString(R.string.st_theme_mode), value = themeMode.getString()) {
                val themes = Constants.Theme.entries
                showOptions(
                    getLocalizedString(R.string.st_theme_mode),
                    themes.map { it.getString() },
                    themes.indexOf(themeMode)
                ) { i ->
                    prefs.appTheme = themes[i]
                    themeMode = themes[i]
                }
            }
            SelectRow(
                title = getLocalizedString(R.string.st_font),
                subtitle = getLocalizedString(R.string.st_font_sub),
                value = font.getString()
            ) {
                val fonts = Constants.FontFamily.entries
                showOptions(
                    title = getLocalizedString(R.string.st_font),
                    options = fonts.map { it.getString() },
                    selected = fonts.indexOf(font),
                    fonts = fonts.map { f -> f.getFont(context)?.let { FontFamily(it) } }
                ) { i ->
                    val picked = fonts[i]
                    if (picked == Constants.FontFamily.Custom) {
                        (activity as? MainActivity)?.pickCustomFont()
                    } else if (picked != font) {
                        font = picked
                        prefs.fontFamily = picked
                        AppReloader.restartApp(context)
                    }
                }
            }
        }

        SectionHeader(getLocalizedString(R.string.st_sec_background))
        SettingsCard {
            ColorRow(getLocalizedString(R.string.st_background_colour), bgColor) {
                showColorPicker(getLocalizedString(R.string.st_background_colour), bgColor) { c ->
                    bgColor = c
                    prefs.backgroundColor = c
                }
            }
            SwitchRow(
                title = getLocalizedString(R.string.st_transparent_background),
                subtitle = getLocalizedString(R.string.st_transparent_background_sub),
                checked = transparent
            ) {
                transparent = it
                prefs.showBackground = it
            }
            if (!transparent) {
                SliderRow(
                    title = getLocalizedString(R.string.st_background_transparency),
                    value = opacity.toFloat(),
                    range = Constants.MIN_OPACITY.toFloat()..Constants.MAX_OPACITY.toFloat(),
                    format = { "${it.roundToInt()}%" },
                    onCommit = {
                        opacity = it.roundToInt()
                        prefs.opacityNum = opacity
                        viewModel.opacityNum.value = opacity
                    }
                )
            }
        }

        SectionHeader(getLocalizedString(R.string.st_sec_system_bars))
        SettingsCard {
            SwitchRow(title = getLocalizedString(R.string.st_status_bar), checked = statusBar) {
                statusBar = it
                prefs.showStatusBar = it
                activity?.window?.let { w -> if (it) showStatusBar(w) else hideStatusBar(w) }
            }
            SwitchRow(title = getLocalizedString(R.string.st_nav_bar), checked = navBar) {
                navBar = it
                prefs.showNavigationBar = it
                activity?.window?.let { w -> if (it) showNavigationBar(w) else hideNavigationBar(w) }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // App drawer
    // -----------------------------------------------------------------------------------------

    @Composable
    private fun DrawerPage() {
        val appContext = LocalContext.current.applicationContext
        var hideSearch by remember { mutableStateOf(prefs.hideSearchView) }
        var autoKeyboard by remember { mutableStateOf(prefs.autoShowKeyboard) }
        var engine by remember { mutableStateOf(prefs.searchEngines) }
        var webButton by remember {
            mutableStateOf(prefs.getMenuFlags("APPLIST_BUTTON_FLAGS", "00").getOrElse(0) { false })
        }
        var fuzzy by remember { mutableStateOf(prefs.enableFilterStrength) }
        var fromStart by remember { mutableStateOf(prefs.searchFromStart) }
        var strength by remember { mutableIntStateOf(prefs.filterStrength) }
        var autoOpen by remember { mutableStateOf(prefs.autoOpenApp) }
        var openOnEnter by remember { mutableStateOf(prefs.openAppOnEnter) }
        var sidebar by remember { mutableStateOf(prefs.showAZSidebar) }
        var alignment by remember { mutableStateOf(prefs.drawerAlignment) }
        var iconPack by remember(resumeTick) { mutableStateOf(prefs.iconPackAppList) }
        var blur by remember { mutableIntStateOf(prefs.drawerBlurRadius) }
        val gravityLabels = Constants.Gravity.entries.map { it.string() }

        var bgRefresh by remember { mutableIntStateOf(0) }
        var bgSource by remember { mutableStateOf<DrawerBackground.Source?>(null) }
        LaunchedEffect(bgRefresh) {
            // The first call may decode the system wallpaper, so stay off the main thread.
            bgSource = withContext(Dispatchers.IO) { DrawerBackground.currentSource(appContext) }
        }

        SectionHeader(getLocalizedString(R.string.st_sec_search))
        SettingsCard {
            SwitchRow(title = getLocalizedString(R.string.st_hide_search), checked = hideSearch) {
                hideSearch = it
                prefs.hideSearchView = it
                if (it) {
                    autoKeyboard = false
                    prefs.autoShowKeyboard = false
                }
            }
            if (!hideSearch) {
                SwitchRow(title = getLocalizedString(R.string.st_auto_keyboard), checked = autoKeyboard) {
                    autoKeyboard = it
                    prefs.autoShowKeyboard = it
                }
            }
            SelectRow(title = getLocalizedString(R.string.st_search_engine), value = engine.getString()) {
                val engines = Constants.SearchEngines.entries
                showOptions(
                    getLocalizedString(R.string.st_search_engine),
                    engines.map { it.getString() },
                    engines.indexOf(engine)
                ) { i ->
                    engine = engines[i]
                    prefs.searchEngines = engine
                }
            }
            SwitchRow(
                title = getLocalizedString(R.string.st_web_search_button),
                subtitle = getLocalizedString(R.string.st_web_search_button_sub),
                checked = webButton
            ) {
                webButton = it
                // Index 1 used to be the contacts tab, which this build does not have.
                prefs.saveMenuFlags("APPLIST_BUTTON_FLAGS", listOf(it, false))
            }
            SwitchRow(
                title = getLocalizedString(R.string.st_fuzzy),
                subtitle = getLocalizedString(R.string.st_fuzzy_sub),
                checked = fuzzy
            ) {
                fuzzy = it
                prefs.enableFilterStrength = it
            }
            if (fuzzy) {
                SwitchRow(title = getLocalizedString(R.string.st_search_from_start), checked = fromStart) {
                    fromStart = it
                    prefs.searchFromStart = it
                }
                SliderRow(
                    title = getLocalizedString(R.string.st_fuzzy_strength),
                    value = strength.toFloat(),
                    range = Constants.MIN_FILTER_STRENGTH.toFloat()..Constants.MAX_FILTER_STRENGTH.toFloat(),
                    format = { it.roundToInt().toString() },
                    onCommit = {
                        strength = it.roundToInt()
                        prefs.filterStrength = strength
                        viewModel.filterStrength.value = strength
                    }
                )
            }
            SwitchRow(
                title = getLocalizedString(R.string.st_auto_open),
                subtitle = getLocalizedString(R.string.st_auto_open_sub),
                checked = autoOpen
            ) {
                autoOpen = it
                prefs.autoOpenApp = it
            }
            SwitchRow(
                title = getLocalizedString(R.string.st_open_on_enter),
                subtitle = getLocalizedString(R.string.st_open_on_enter_sub),
                checked = openOnEnter
            ) {
                openOnEnter = it
                prefs.openAppOnEnter = it
            }
        }

        SectionHeader(getLocalizedString(R.string.st_sec_list))
        SettingsCard {
            SwitchRow(title = getLocalizedString(R.string.st_az_sidebar), checked = sidebar) {
                sidebar = it
                prefs.showAZSidebar = it
            }
            SelectRow(title = getLocalizedString(R.string.st_drawer_alignment), value = alignment.string()) {
                val gravities = Constants.Gravity.entries
                showOptions(
                    getLocalizedString(R.string.st_drawer_alignment),
                    gravityLabels,
                    gravities.indexOf(alignment)
                ) { i ->
                    alignment = gravities[i]
                    prefs.drawerAlignment = alignment
                    viewModel.updateDrawerAlignment(alignment)
                }
            }
            SelectRow(
                title = getLocalizedString(R.string.st_drawer_icons),
                value = iconPack.getString(IconCacheTarget.APP_LIST.name)
            ) {
                pickIconPack(IconCacheTarget.APP_LIST, getLocalizedString(R.string.st_drawer_icons), iconPack) { iconPack = it }
            }
            FlagsRow(
                title = getLocalizedString(R.string.st_long_press_menu),
                key = "CONTEXT_MENU_FLAGS",
                default = "0011111",
                labels = listOf(
                    R.string.pin, R.string.lock, R.string.hide, R.string.rename,
                    R.string.tag, R.string.info, R.string.delete,
                ).map { getLocalizedString(it) }
            )
            SelectRow(
                title = getLocalizedString(R.string.st_hidden_apps),
                subtitle = getLocalizedString(R.string.st_hidden_apps_sub),
                showChevron = true,
                onClick = { showHiddenApps() }
            )
        }

        SectionHeader(getLocalizedString(R.string.st_sec_background))
        SettingsCard {
            SelectRow(
                title = getLocalizedString(R.string.st_drawer_background),
                subtitle = when (bgSource) {
                    DrawerBackground.Source.CustomImage -> getLocalizedString(R.string.st_drawer_bg_custom)
                    DrawerBackground.Source.SystemWallpaper -> getLocalizedString(R.string.st_drawer_bg_wallpaper)
                    DrawerBackground.Source.None -> getLocalizedString(R.string.st_drawer_bg_none)
                    null -> " "
                }
            ) {
                val actions = buildList {
                    add(DialogAction(getLocalizedString(R.string.st_drawer_bg_choose)) {
                        (activity as? MainActivity)?.pickDrawerBackground { bgRefresh++ }
                    })
                    if (DrawerBackground.hasCustomImage(appContext)) {
                        add(DialogAction(getLocalizedString(R.string.st_drawer_bg_remove)) {
                            DrawerBackground.clearCustomImage(appContext)
                            bgRefresh++
                        })
                    }
                }
                dialog = {
                    ActionsDialog(
                        title = getLocalizedString(R.string.st_drawer_background),
                        actions = actions,
                        onDismiss = { dialog = null }
                    )
                }
            }
            CardNote(getLocalizedString(R.string.st_drawer_bg_explainer))
            SliderRow(
                title = getLocalizedString(R.string.st_blur),
                value = blur.toFloat(),
                range = 0f..100f,
                format = { "${it.roundToInt()} px" },
                onCommit = {
                    blur = it.roundToInt()
                    prefs.drawerBlurRadius = blur
                }
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // Gestures
    // -----------------------------------------------------------------------------------------

    @Composable
    private fun GesturesPage() {
        var shortThreshold by remember { mutableFloatStateOf(prefs.shortSwipeThreshold) }
        var longThreshold by remember { mutableFloatStateOf(prefs.longSwipeThreshold) }
        val percent: (Float) -> String = { "${(it * 100).roundToInt()}%" }

        SectionHeader(getLocalizedString(R.string.st_sec_taps))
        SettingsCard {
            GestureRow(Gesture(R.string.st_double_tap, AppDrawerFlag.SetDoubleTap, { it.doubleTapAction }, { it.appDoubleTap }))
            GestureRow(Gesture(R.string.st_logo_tap, AppDrawerFlag.SetFloating, { it.clickFloatingAction }, { it.appFloating }))
        }

        SectionHeader(getLocalizedString(R.string.st_sec_short_swipes))
        SettingsCard {
            GestureRow(Gesture(R.string.st_swipe_up, AppDrawerFlag.SetShortSwipeUp, { it.shortSwipeUpAction }, { it.appShortSwipeUp }))
            GestureRow(Gesture(R.string.st_swipe_down, AppDrawerFlag.SetShortSwipeDown, { it.shortSwipeDownAction }, { it.appShortSwipeDown }))
            GestureRow(Gesture(R.string.st_swipe_left, AppDrawerFlag.SetShortSwipeLeft, { it.shortSwipeLeftAction }, { it.appShortSwipeLeft }))
            GestureRow(Gesture(R.string.st_swipe_right, AppDrawerFlag.SetShortSwipeRight, { it.shortSwipeRightAction }, { it.appShortSwipeRight }))
        }

        SectionHeader(getLocalizedString(R.string.st_sec_long_swipes))
        SettingsCard {
            GestureRow(Gesture(R.string.st_long_swipe_up, AppDrawerFlag.SetLongSwipeUp, { it.longSwipeUpAction }, { it.appLongSwipeUp }))
            GestureRow(Gesture(R.string.st_long_swipe_down, AppDrawerFlag.SetLongSwipeDown, { it.longSwipeDownAction }, { it.appLongSwipeDown }))
            GestureRow(Gesture(R.string.st_long_swipe_left, AppDrawerFlag.SetLongSwipeLeft, { it.longSwipeLeftAction }, { it.appLongSwipeLeft }))
            GestureRow(Gesture(R.string.st_long_swipe_right, AppDrawerFlag.SetLongSwipeRight, { it.longSwipeRightAction }, { it.appLongSwipeRight }))
        }

        SectionHeader(getLocalizedString(R.string.st_sec_sensitivity))
        SettingsCard {
            // A short swipe can never be longer than a long one.
            SliderRow(
                title = getLocalizedString(R.string.st_short_threshold),
                subtitle = getLocalizedString(R.string.st_threshold_sub),
                value = shortThreshold,
                range = Constants.MIN_THRESHOLD..longThreshold,
                format = percent,
                onCommit = {
                    shortThreshold = it
                    prefs.shortSwipeThreshold = it
                }
            )
            SliderRow(
                title = getLocalizedString(R.string.st_long_threshold),
                value = longThreshold,
                range = shortThreshold..Constants.MAX_THRESHOLD,
                format = percent,
                onCommit = {
                    longThreshold = it
                    prefs.longSwipeThreshold = it
                }
            )
        }
    }

    @Composable
    private fun GestureRow(gesture: Gesture) {
        var action by remember { mutableStateOf(gesture.action(prefs)) }
        val appLabel = remember { gesture.app(prefs).activityLabel }
        val title = getLocalizedString(gesture.title)

        SelectRow(
            title = title,
            value = if (action == Action.OpenApp && appLabel.isNotEmpty()) {
                getLocalizedString(R.string.st_open_app, appLabel)
            } else {
                action.getString()
            }
        ) {
            val actions = Action.entries.filter { it.name !in removedActionNames }
            showOptions(title, actions.map { it.getString() }, actions.indexOf(action)) { i ->
                action = actions[i]
                setGesture(gesture.flag, actions[i])
            }
        }
    }

    private fun setGesture(flag: AppDrawerFlag, action: Action) {
        when (flag) {
            AppDrawerFlag.SetShortSwipeUp -> prefs.shortSwipeUpAction = action
            AppDrawerFlag.SetShortSwipeDown -> prefs.shortSwipeDownAction = action
            AppDrawerFlag.SetShortSwipeLeft -> prefs.shortSwipeLeftAction = action
            AppDrawerFlag.SetShortSwipeRight -> prefs.shortSwipeRightAction = action
            AppDrawerFlag.SetLongSwipeUp -> prefs.longSwipeUpAction = action
            AppDrawerFlag.SetLongSwipeDown -> prefs.longSwipeDownAction = action
            AppDrawerFlag.SetLongSwipeLeft -> prefs.longSwipeLeftAction = action
            AppDrawerFlag.SetLongSwipeRight -> prefs.longSwipeRightAction = action
            AppDrawerFlag.SetClickClock -> prefs.clickClockAction = action
            AppDrawerFlag.SetClickDate -> prefs.clickDateAction = action
            AppDrawerFlag.SetDoubleTap -> prefs.doubleTapAction = action
            AppDrawerFlag.SetFloating -> prefs.clickFloatingAction = action
            else -> Unit
        }

        if (action == Action.OpenApp) {
            viewModel.getAppList(true)
            navigateFromSettings(R.id.action_settingsFragment_to_appListFragment, flag)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Backup & restore
    // -----------------------------------------------------------------------------------------

    @Composable
    private fun BackupPage() {
        val context = LocalContext.current

        SectionHeader(getLocalizedString(R.string.st_sec_everything))
        SettingsCard {
            SelectRow(
                title = getLocalizedString(R.string.st_backup_full),
                subtitle = getLocalizedString(R.string.st_backup_full_sub)
            ) { (activity as? MainActivity)?.createFullBackup() }
            SelectRow(
                title = getLocalizedString(R.string.st_restore_full),
                subtitle = getLocalizedString(R.string.st_restore_full_sub)
            ) { (activity as? MainActivity)?.restoreFullBackup() }
        }

        SectionHeader(getLocalizedString(R.string.st_sec_colours))
        SettingsCard {
            SelectRow(
                title = getLocalizedString(R.string.st_export_colours),
                subtitle = getLocalizedString(R.string.st_export_colours_sub)
            ) { (activity as? MainActivity)?.createThemeBackup() }
            SelectRow(
                title = getLocalizedString(R.string.st_import_colours),
                subtitle = getLocalizedString(R.string.st_import_colours_sub)
            ) { (activity as? MainActivity)?.restoreThemeBackup() }
        }

        Spacer(Modifier.height(24.dp))
        SettingsCard {
            SelectRow(
                title = getLocalizedString(R.string.st_reset_all),
                subtitle = getLocalizedString(R.string.st_reset_all_sub)
            ) {
                dialog = {
                    ConfirmDialog(
                        title = getLocalizedString(R.string.st_reset_all),
                        message = getLocalizedString(R.string.st_reset_all_confirm),
                        confirmLabel = getLocalizedString(R.string.st_reset),
                        onDismiss = { dialog = null }
                    ) {
                        prefs.clear()
                        AppReloader.restartApp(context)
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // About & advanced
    // -----------------------------------------------------------------------------------------

    @Composable
    private fun AboutPage() {
        val context = LocalContext.current
        var language by remember { mutableStateOf(prefs.appLanguage) }
        var lockOrientation by remember { mutableStateOf(prefs.lockOrientation) }
        var forceWallpaper by remember { mutableStateOf(prefs.forceWallpaper) }
        var settingsLocked by remember { mutableStateOf(prefs.settingsLocked) }
        var haptic by remember { mutableStateOf(prefs.hapticFeedback) }
        val isDefault = remember(resumeTick) { ismlauncherDefault(context) }
        val canLock = remember { context.isBiometricEnabled() }

        SectionHeader(getLocalizedString(R.string.st_sec_about))
        SettingsCard {
            SelectRow(
                title = getLocalizedString(R.string.st_version),
                value = getLocalizedString(R.string.app_version),
                onClick = null
            )
            SelectRow(
                title = getLocalizedString(R.string.st_app_info),
                subtitle = getLocalizedString(R.string.st_app_info_sub),
                showChevron = true
            ) { openAppInfo(context, Process.myUserHandle(), BuildConfig.APPLICATION_ID) }
            SelectRow(
                title = getLocalizedString(R.string.st_source_code),
                value = "GitHub",
                showChevron = true
            ) { openSourceCode(context) }
        }

        SectionHeader(getLocalizedString(R.string.st_sec_launcher))
        SettingsCard {
            SelectRow(
                title = getLocalizedString(if (isDefault) R.string.st_change_default else R.string.st_set_default),
                showChevron = true
            ) { viewModel.resetDefaultLauncherApp(context) }
            SelectRow(title = getLocalizedString(R.string.st_restart)) { AppReloader.restartApp(context) }
            SelectRow(
                title = getLocalizedString(R.string.st_exit),
                subtitle = getLocalizedString(R.string.st_exit_sub)
            ) { exitLauncher(context) }
        }

        SectionHeader(getLocalizedString(R.string.st_sec_behaviour))
        SettingsCard {
            SelectRow(title = getLocalizedString(R.string.st_language), value = language.getString()) {
                val languages = Constants.Language.entries
                showOptions(
                    getLocalizedString(R.string.st_language),
                    languages.map { it.getString() },
                    languages.indexOf(language)
                ) { i ->
                    language = languages[i]
                    prefs.appLanguage = language
                    LauncherLocaleManager.updateLanguage(context, language)
                    LocalizedResources.invalidate()
                    reloadLauncher()
                }
            }
            SwitchRow(
                title = getLocalizedString(R.string.st_lock_orientation),
                subtitle = getLocalizedString(R.string.st_lock_orientation_sub),
                checked = lockOrientation
            ) {
                lockOrientation = it
                prefs.lockOrientation = it
                prefs.lockOrientationPortrait =
                    context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                AppReloader.restartApp(context)
            }
            SwitchRow(
                title = getLocalizedString(R.string.st_force_wallpaper),
                subtitle = getLocalizedString(R.string.st_force_wallpaper_sub),
                checked = forceWallpaper
            ) {
                forceWallpaper = it
                prefs.forceWallpaper = it
            }
            if (canLock) {
                SwitchRow(
                    title = getLocalizedString(R.string.st_lock_settings),
                    subtitle = getLocalizedString(R.string.st_lock_settings_sub),
                    checked = settingsLocked
                ) {
                    settingsLocked = it
                    prefs.settingsLocked = it
                }
            }
            SwitchRow(title = getLocalizedString(R.string.st_haptic), checked = haptic) {
                haptic = it
                prefs.hapticFeedback = it
            }
        }
    }

    private fun openSourceCode(context: Context) {
        // github_link is an HTML anchor; pull the URL out of it.
        val html = getLocalizedString(R.string.github_link)
        val url = Regex("href=\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    private fun exitLauncher(context: Context) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        val launchers = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .filter {
                val pkg = it.activityInfo.packageName
                it.activityInfo.enabled &&
                        pkg != BuildConfig.APPLICATION_ID &&
                        !pkg.contains("settings", ignoreCase = true)
            }

        if (launchers.isEmpty()) {
            showShortToast(getLocalizedString(R.string.st_no_launchers))
            return
        }

        val iconSize = (36 * context.resources.displayMetrics.density).roundToInt()
        val actions = launchers.map { info ->
            val icon = runCatching { info.loadIcon(pm).toBitmap(iconSize, iconSize).asImageBitmap() }.getOrNull()
            DialogAction(info.loadLabel(pm).toString(), icon) {
                val launch = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                runCatching { context.startActivity(launch) }
            }
        }

        dialog = {
            ActionsDialog(
                title = getLocalizedString(R.string.st_exit_dialog),
                actions = actions,
                onDismiss = { dialog = null }
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // Shared pieces
    // -----------------------------------------------------------------------------------------

    /** Row showing which of several on/off flags are set, with a checkbox dialog to change them. */
    @Composable
    private fun FlagsRow(title: String, key: String, default: String, labels: List<String>) {
        val flags = remember {
            mutableStateListOf<Boolean>().apply {
                val saved = prefs.getMenuFlags(key, default)
                addAll(List(labels.size) { saved.getOrElse(it) { false } })
            }
        }
        val summary = labels.zip(flags)
            .filter { it.second }
            .joinToString(", ") { it.first }
            .ifEmpty { getLocalizedString(R.string.st_none) }

        SelectRow(title = title, subtitle = summary) {
            dialog = {
                FlagsDialog(
                    title = title,
                    labels = labels,
                    checked = flags,
                    onDismiss = { dialog = null }
                ) { index, value ->
                    flags[index] = value
                    prefs.saveMenuFlags(key, flags.toList())
                }
            }
        }
    }

    private fun showOptions(
        title: String,
        options: List<String>,
        selected: Int,
        fonts: List<FontFamily?>? = null,
        onSelect: (Int) -> Unit,
    ) {
        if (options.isEmpty()) return
        dialog = {
            OptionsDialog(
                title = title,
                options = options,
                selected = selected,
                fonts = fonts,
                onDismiss = { dialog = null },
                onSelect = { i -> if (i in options.indices) onSelect(i) }
            )
        }
    }

    private fun showColorPicker(title: String, initial: Int, onPick: (Int) -> Unit) {
        dialog = {
            ColorPickerDialog(title = title, initial = initial, onDismiss = { dialog = null }, onPick = onPick)
        }
    }

    private fun pickIconPack(
        target: IconCacheTarget,
        title: String,
        current: Constants.IconPacks,
        onChanged: (Constants.IconPacks) -> Unit,
    ) {
        val packs = Constants.IconPacks.entries
        showOptions(title, packs.map { it.getString(emptyString()) }, packs.indexOf(current)) { i ->
            val pack = packs[i]
            if (pack == Constants.IconPacks.Custom) {
                openCustomIconSelection(target)
                return@showOptions
            }
            if (target == IconCacheTarget.HOME) {
                prefs.customIconPackHome = emptyString()
                prefs.iconPackHome = pack
                viewModel.iconPackHome.value = pack
                context?.let { updateHomeWidget(it) }
            } else {
                prefs.customIconPackAppList = emptyString()
                prefs.iconPackAppList = pack
                viewModel.iconPackAppList.value = pack
            }
            onChanged(pack)
        }
    }

    private fun openCustomIconSelection(target: IconCacheTarget) {
        val host = activity ?: return
        startActivity(Intent(host, CustomIconSelectionActivity::class.java).putExtra("IconCacheTarget", "$target"))
    }

    private fun showHiddenApps() {
        viewModel.getHiddenApps()
        navigateFromSettings(R.id.action_settingsFragment_to_appListFragment, AppDrawerFlag.HiddenApps)
    }

    private fun showFavoriteApps() {
        navigateFromSettings(R.id.action_settingsFragment_to_appFavoriteFragment, AppDrawerFlag.SetHomeApp)
    }

    /** Navigate only while settings is still the current screen (ignores double taps). */
    private fun navigateFromSettings(actionId: Int, flag: AppDrawerFlag) {
        if (!isAdded) return
        val nav = findNavController()
        if (nav.currentDestination?.id != R.id.settingsFragment) return
        nav.navigate(actionId, Bundle().apply { putString("flag", flag.toString()) })
    }
}
