package com.github.codeworkscreativehub.mlauncher.data

import android.content.Context
import android.content.SharedPreferences
import android.os.UserHandle
import androidx.core.content.ContextCompat.getColor
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import com.github.codeworkscreativehub.common.AppLogger
import com.github.codeworkscreativehub.common.showLongToast
import com.github.codeworkscreativehub.mlauncher.R
import com.github.codeworkscreativehub.mlauncher.data.Constants.Gravity
import com.github.codeworkscreativehub.mlauncher.helper.emptyString
import com.github.codeworkscreativehub.mlauncher.helper.getUserHandleFromString
import com.github.codeworkscreativehub.mlauncher.helper.isSystemInDarkMode
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/** Keys of features removed from this build. Restoring an old backup skips them. */
internal val REMOVED_KEY_PREFIXES = listOf(
    "NOTES_", "BUBBLE_", "INPUT_MESSAGE", "WEATHER_", "WORD_LIST", "SHOW_WEATHER", "GPS_LOCATION",
    "TEMP_UNIT", "SHOW_BATTERY", "BATTERY_", "SHOW_ALARM", "ALARM_", "SHOW_DAILY_WORD", "DAILY_WORD_",
    "RECENT_", "APP_USAGE_STATS", "CLICK_APP_USAGE_ACTION", "SHOW_PRIVATE_SPACES", "HIDDEN_CONTACTS",
    "PINNED_CONTACTS", "ICON_RAINBOW_COLORS", "AUTO_EXPAND_NOTES", "CLICK_EDIT_DELETE",
)

class Prefs(val context: Context) {
    // Build Moshi instance once (ideally a singleton)
    val moshi: Moshi = Moshi.Builder().build()

    internal val prefsNormal: SharedPreferences = context.getSharedPreferences(PREFS_FILENAME, Context.MODE_PRIVATE)
    internal val prefsOnboarding: SharedPreferences = context.getSharedPreferences(PREFS_ONBOARDING_FILENAME, Context.MODE_PRIVATE)
    internal val pinnedAppsKey = PINNED_APPS

    fun saveToString(): String {
        val allPreferences = HashMap<String, Any?>(prefsNormal.all)

        val moshi = Moshi.Builder().build()

        val type = Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            Any::class.java
        )

        val adapter = moshi.adapter<Map<String, Any?>>(type).indent("  ") // Pretty-print

        return adapter.toJson(allPreferences)
    }

    /** Load a full backup. Returns false (and leaves prefs untouched) if the JSON can't be read. */
    fun loadFromString(json: String, clearFirst: Boolean = false): Boolean {
        val moshi = Moshi.Builder().build()

        val type = Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            Any::class.java
        )

        val adapter = moshi.adapter<Map<String, Any?>>(type)

        val all = try {
            adapter.fromJson(json)
        } catch (e: Exception) {
            AppLogger.e("backup error", "Backup is not valid JSON", e)
            null
        } ?: return false

        prefsNormal.edit {
            if (clearFirst) clear()
            for ((key, value) in all) {
                // Features removed from this build: drop their data instead of carrying it around
                if (REMOVED_KEY_PREFIXES.any { key.startsWith(it) }) continue
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Double -> {
                        if (value % 1 == 0.0) {
                            putInt(key, value.toInt())
                        } else {
                            putFloat(key, value.toFloat())
                        }
                    }

                    is List<*> -> {
                        // Moshi deserializes sets as lists
                        val stringSet = value.filterIsInstance<String>().toSet()
                        putStringSet(key, stringSet)
                    }

                    else -> {
                        AppLogger.d("backup error", "Unsupported type for key '$key': $value")
                    }
                }
            }
        }
        return true
    }

    fun saveToTheme(colorNames: List<String>): String {
        val allPrefs = prefsNormal.all
        val filteredPrefs = mutableMapOf<String, String>()

        for (colorName in colorNames) {
            if (allPrefs.containsKey(colorName)) {
                val colorInt = allPrefs[colorName] as? Int
                if (colorInt != null) {
                    val hexColor = String.format("#%08X", colorInt)
                    filteredPrefs[colorName] = hexColor
                }
            }
        }

        val moshi = Moshi.Builder().build()

        val type = Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            String::class.java
        )
        val adapter = moshi.adapter<Map<String, String>>(type).indent("  ") // pretty-print

        return adapter.toJson(filteredPrefs)
    }

    fun loadFromTheme(json: String) {
        val moshi = Moshi.Builder().build()

        val type = Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            Any::class.java
        )

        val adapter = moshi.adapter<Map<String, Any?>>(type)

        val all = try {
            adapter.fromJson(json)
        } catch (e: Exception) {
            AppLogger.e("Theme Import", "Failed to parse JSON", e)
            context.showLongToast("Failed to parse theme JSON.")
            return
        } ?: emptyMap()

        prefsNormal.edit {
            for ((key, value) in all) {
                try {
                    when (value) {
                        is String -> {
                            if (value.matches(Regex("^#([A-Fa-f0-9]{8})$"))) {
                                try {
                                    putInt(key, value.toColorInt())
                                } catch (e: IllegalArgumentException) {
                                    context.showLongToast("Invalid color format for key: $key, value: $value")
                                    AppLogger.e("Theme Import", "Invalid color format for key: $key, value: $value", e)
                                    continue
                                }
                            } else {
                                context.showLongToast("Unsupported HEX format for key: $key, value: $value")
                                AppLogger.e("Theme Import", "Unsupported HEX format for key: $key, value: $value")
                            }
                        }

                        null -> {
                            context.showLongToast("Null value found for key: $key")
                            AppLogger.e("Theme Import", "Null value found for key: $key")
                            continue
                        }

                        else -> {
                            context.showLongToast("Unsupported value type for key: $key, value: $value")
                            AppLogger.e("Theme Import", "Unsupported value type for key: $key, value: $value")
                            continue
                        }
                    }
                } catch (e: Exception) {
                    context.showLongToast("Error processing key: $key, value: $value")
                    AppLogger.e("Theme Import", "Error processing key: $key, value: $value", e)
                }
            }
        }
    }

    var appVersion: Int
        get() = prefsNormal.getInt(APP_VERSION, -1)
        set(value) = prefsNormal.edit { putInt(APP_VERSION, value) }

    var firstOpen: Boolean
        get() = prefsNormal.getBoolean(FIRST_OPEN, true)
        set(value) = prefsNormal.edit { putBoolean(FIRST_OPEN, value) }

    var firstSettingsOpen: Boolean
        get() = prefsNormal.getBoolean(FIRST_SETTINGS_OPEN, true)
        set(value) = prefsNormal.edit { putBoolean(FIRST_SETTINGS_OPEN, value) }

    var autoOpenApp: Boolean
        get() = getSetting(AUTO_OPEN_APP, false)
        set(value) = prefsNormal.edit { putBoolean(AUTO_OPEN_APP, value) }

    var forceWallpaper: Boolean
        get() = getSetting(FORCE_COLORED_WALLPAPER, false)
        set(value) = prefsNormal.edit { putBoolean(FORCE_COLORED_WALLPAPER, value) }

    var openAppOnEnter: Boolean
        get() = getSetting(OPEN_APP_ON_ENTER, false)
        set(value) = prefsNormal.edit { putBoolean(OPEN_APP_ON_ENTER, value) }

    var homePager: Boolean
        get() = getSetting(HOME_PAGES_PAGER, false)
        set(value) = prefsNormal.edit { putBoolean(HOME_PAGES_PAGER, value) }

    var enableFilterStrength: Boolean
        get() = getSetting(ENABLE_FILTER_STRENGTH, true)
        set(value) = prefsNormal.edit { putBoolean(ENABLE_FILTER_STRENGTH, value) }

    var filterStrength: Int
        get() = getSetting(FILTER_STRENGTH, 25)
        set(value) = prefsNormal.edit { putInt(FILTER_STRENGTH, value) }

    var shortSwipeThreshold: Float
        get() = getSetting(SHORT_SWIPE_THRESHOLD, 0.15f)
        set(value) = prefsNormal.edit { putFloat(SHORT_SWIPE_THRESHOLD, value) }

    var longSwipeThreshold: Float
        get() = getSetting(LONG_SWIPE_THRESHOLD, 0.4f)
        set(value) = prefsNormal.edit { putFloat(LONG_SWIPE_THRESHOLD, value) }

    var searchFromStart: Boolean
        get() = getSetting(SEARCH_START, false)
        set(value) = prefsNormal.edit { putBoolean(SEARCH_START, value) }

    var autoShowKeyboard: Boolean
        get() = getSetting(AUTO_SHOW_KEYBOARD, true)
        set(value) = prefsNormal.edit { putBoolean(AUTO_SHOW_KEYBOARD, value) }

    var homeAppsNum: Int
        get() = getSetting(HOME_APPS_NUM, 4)
        set(value) = prefsNormal.edit { putInt(HOME_APPS_NUM, value) }

    var homePagesNum: Int
        get() = getSetting(HOME_PAGES_NUM, 1)
        set(value) = prefsNormal.edit { putInt(HOME_PAGES_NUM, value) }

    var backgroundColor: Int
        get() = getSetting(BACKGROUND_COLOR, getColor(context, getColorInt("bg")))
        set(value) = prefsNormal.edit { putInt(BACKGROUND_COLOR, value) }

    var appColor: Int
        get() = getSetting(APP_COLOR, getColor(context, getColorInt("txt")))
        set(value) = prefsNormal.edit { putInt(APP_COLOR, value) }

    var dateColor: Int
        get() = getSetting(DATE_COLOR, getColor(context, getColorInt("txt")))
        set(value) = prefsNormal.edit { putInt(DATE_COLOR, value) }

    var clockColor: Int
        get() = getSetting(CLOCK_COLOR, getColor(context, getColorInt("txt")))
        set(value) = prefsNormal.edit { putInt(CLOCK_COLOR, value) }

    var shortcutIconsColor: Int
        get() = getSetting(SHORTCUT_ICONS_COLOR, getColor(context, getColorInt("txt")))
        set(value) = prefsNormal.edit { putInt(SHORTCUT_ICONS_COLOR, value) }

    var opacityNum: Int
        get() = getSetting(APP_OPACITY, 15)
        set(value) = prefsNormal.edit { putInt(APP_OPACITY, value) }

    var homeAlignment: Gravity
        get() {
            return getEnumSetting(HOME_ALIGNMENT, Gravity.Left)
        }
        set(value) = prefsNormal.edit { putString(HOME_ALIGNMENT, value.toString()) }

    var homeAlignmentBottom: Boolean
        get() = getSetting(HOME_ALIGNMENT_BOTTOM, true)
        set(value) = prefsNormal.edit { putBoolean(HOME_ALIGNMENT_BOTTOM, value) }

    var extendHomeAppsArea: Boolean
        get() = getSetting(HOME_CLICK_AREA, false)
        set(value) = prefsNormal.edit { putBoolean(HOME_CLICK_AREA, value) }

    var clockAlignment: Gravity
        get() {
            return getEnumSetting(CLOCK_ALIGNMENT, Gravity.Left)
        }
        set(value) = prefsNormal.edit { putString(CLOCK_ALIGNMENT, value.toString()) }

    var dateAlignment: Gravity
        get() {
            return getEnumSetting(DATE_ALIGNMENT, Gravity.Left)
        }
        set(value) = prefsNormal.edit { putString(DATE_ALIGNMENT, value.toString()) }

    var drawerAlignment: Gravity
        get() {
            return getEnumSetting(DRAWER_ALIGNMENT, Gravity.Left)
        }
        set(value) = prefsNormal.edit { putString(DRAWER_ALIGNMENT, value.name) }

    var showBackground: Boolean
        get() = getSetting(SHOW_BACKGROUND, false)
        set(value) = prefsNormal.edit { putBoolean(SHOW_BACKGROUND, value) }

    var showStatusBar: Boolean
        get() = getSetting(STATUS_BAR, true)
        set(value) = prefsNormal.edit { putBoolean(STATUS_BAR, value) }

    var showNavigationBar: Boolean
        get() = getSetting(NAVIGATION_BAR, true)
        set(value) = prefsNormal.edit { putBoolean(NAVIGATION_BAR, value) }

    var showDate: Boolean
        get() = getSetting(SHOW_DATE, true)
        set(value) = prefsNormal.edit { putBoolean(SHOW_DATE, value) }
    
    var showDayOfYear: Boolean
        get() = getSetting(SHOW_DAY_OF_YEAR, false)
        set(value) = prefsNormal.edit { putBoolean(SHOW_DAY_OF_YEAR, value) }

    var showClock: Boolean
        get() = getSetting(SHOW_CLOCK, true)
        set(value) = prefsNormal.edit { putBoolean(SHOW_CLOCK, value) }

    var showClockFormat: Boolean
        get() = getSetting(SHOW_CLOCK_FORMAT, true)
        set(value) = prefsNormal.edit { putBoolean(SHOW_CLOCK_FORMAT, value) }

    var showFloating: Boolean
        get() = getSetting(SHOW_FLOATING, true)
        set(value) = prefsNormal.edit { putBoolean(SHOW_FLOATING, value) }

    var lockOrientation: Boolean
        get() = getSetting(LOCK_ORIENTATION, false)
        set(value) = prefsNormal.edit { putBoolean(LOCK_ORIENTATION, value) }

    var lockOrientationPortrait: Boolean
        get() = getSetting(LOCK_ORIENTATION_PORTRAIT, true)
        set(value) = prefsNormal.edit { putBoolean(LOCK_ORIENTATION_PORTRAIT, value) }

    var hapticFeedback: Boolean
        get() = getSetting(HAPTIC_FEEDBACK, true)
        set(value) = prefsNormal.edit { putBoolean(HAPTIC_FEEDBACK, value) }

    var showAZSidebar: Boolean
        get() = getSetting(SHOW_AZSIDEBAR, false)
        set(value) = prefsNormal.edit { putBoolean(SHOW_AZSIDEBAR, value) }

    var iconPackHome: Constants.IconPacks
        get() {
            return getEnumSetting(ICON_PACK_HOME, Constants.IconPacks.Disabled)
        }
        set(value) = prefsNormal.edit { putString(ICON_PACK_HOME, value.name) }

    var customIconPackHome: String
        get() = prefsNormal.getString(CUSTOM_ICON_PACK_HOME, emptyString()).toString()
        set(value) = prefsNormal.edit { putString(CUSTOM_ICON_PACK_HOME, value) }

    var iconPackAppList: Constants.IconPacks
        get() {
            return getEnumSetting(ICON_PACK_APP_LIST, Constants.IconPacks.Disabled)
        }
        set(value) = prefsNormal.edit { putString(ICON_PACK_APP_LIST, value.name) }

    var customIconPackAppList: String
        get() = prefsNormal.getString(CUSTOM_ICON_PACK_APP_LIST, emptyString()).toString()
        set(value) = prefsNormal.edit { putString(CUSTOM_ICON_PACK_APP_LIST, value) }

    var homeLocked: Boolean
        get() = getSetting(HOME_LOCKED, false)
        set(value) = prefsNormal.edit { putBoolean(HOME_LOCKED, value) }

    var settingsLocked: Boolean
        get() = getSetting(SETTINGS_LOCKED, false)
        set(value) = prefsNormal.edit { putBoolean(SETTINGS_LOCKED, value) }

    /** Blur radius in px for the app drawer background (0 = no blur). */
    var drawerBlurRadius: Int
        get() = getSetting(DRAWER_BLUR_RADIUS, 40).coerceIn(0, 100)
        set(value) = prefsNormal.edit { putInt(DRAWER_BLUR_RADIUS, value.coerceIn(0, 100)) }

    /** Size of the image next to the clock, in dp. */
    var clockStickerSize: Int
        get() = getSetting(CLOCK_STICKER_SIZE, 96).coerceIn(32, 240)
        set(value) = prefsNormal.edit { putInt(CLOCK_STICKER_SIZE, value.coerceIn(32, 240)) }

    /** Holographic foil on the clock sticker that follows the phone's tilt. */
    var clockStickerHolo: Boolean
        get() = getSetting(CLOCK_STICKER_HOLO, true)
        set(value) = prefsNormal.edit { putBoolean(CLOCK_STICKER_HOLO, value) }

    /** Holo foil strength, 0..100. */
    var clockStickerHoloIntensity: Int
        get() = getSetting(CLOCK_STICKER_HOLO_INTENSITY, 80).coerceIn(0, 100)
        set(value) = prefsNormal.edit { putInt(CLOCK_STICKER_HOLO_INTENSITY, value.coerceIn(0, 100)) }

    var clockStickerHoloSensitivity: Int
        get() = getSetting(CLOCK_STICKER_HOLO_SENSITIVITY, 70).coerceIn(0, 100)
        set(value) = prefsNormal.edit { putInt(CLOCK_STICKER_HOLO_SENSITIVITY, value.coerceIn(0, 100)) }

    /** With a custom icon pack, draw white glyphs for apps the pack doesn't cover. */
    var monochromeIconFallback: Boolean
        get() = getSetting(MONO_ICON_FALLBACK, true)
        set(value) = prefsNormal.edit { putBoolean(MONO_ICON_FALLBACK, value) }

    var hideSearchView: Boolean
        get() = getSetting(HIDE_SEARCH_VIEW, false)
        set(value) = prefsNormal.edit { putBoolean(HIDE_SEARCH_VIEW, value) }

    var shortSwipeUpAction: Constants.Action
        get() {
            return getEnumSetting(SWIPE_UP_ACTION, Constants.Action.ShowAppList)
        }
        set(value) = prefsNormal.edit { putString(SWIPE_UP_ACTION, value.name) }

    var shortSwipeDownAction: Constants.Action
        get() {
            return getEnumSetting(SWIPE_DOWN_ACTION, Constants.Action.ShowNotification)
        }
        set(value) = prefsNormal.edit { putString(SWIPE_DOWN_ACTION, value.name) }

    var shortSwipeLeftAction: Constants.Action
        get() {
            return getEnumSetting(SWIPE_LEFT_ACTION, Constants.Action.OpenApp)
        }
        set(value) = prefsNormal.edit { putString(SWIPE_LEFT_ACTION, value.name) }

    var shortSwipeRightAction: Constants.Action
        get() {
            return getEnumSetting(SWIPE_RIGHT_ACTION, Constants.Action.OpenApp)
        }
        set(value) = prefsNormal.edit { putString(SWIPE_RIGHT_ACTION, value.name) }

    var longSwipeUpAction: Constants.Action
        get() {
            return getEnumSetting(LONG_SWIPE_UP_ACTION, Constants.Action.ShowAppList)
        }
        set(value) = prefsNormal.edit { putString(LONG_SWIPE_UP_ACTION, value.name) }

    var longSwipeDownAction: Constants.Action
        get() {
            return getEnumSetting(LONG_SWIPE_DOWN_ACTION, Constants.Action.ShowNotification)
        }
        set(value) = prefsNormal.edit { putString(LONG_SWIPE_DOWN_ACTION, value.name) }

    var longSwipeLeftAction: Constants.Action
        get() {
            return getEnumSetting(LONG_SWIPE_LEFT_ACTION, Constants.Action.PreviousPage)
        }
        set(value) = prefsNormal.edit { putString(LONG_SWIPE_LEFT_ACTION, value.name) }

    var longSwipeRightAction: Constants.Action
        get() {
            return getEnumSetting(LONG_SWIPE_RIGHT_ACTION, Constants.Action.NextPage)
        }
        set(value) = prefsNormal.edit { putString(LONG_SWIPE_RIGHT_ACTION, value.name) }

    var clickClockAction: Constants.Action
        get() {
            return getEnumSetting(CLICK_CLOCK_ACTION, Constants.Action.OpenApp)
        }
        set(value) = prefsNormal.edit { putString(CLICK_CLOCK_ACTION, value.name) }

    var clickFloatingAction: Constants.Action
        get() {
            return getEnumSetting(CLICK_FLOATING_ACTION, Constants.Action.ShowAppList)
        }
        set(value) = prefsNormal.edit { putString(CLICK_FLOATING_ACTION, value.name) }

    var clickDateAction: Constants.Action
        get() {
            return getEnumSetting(CLICK_DATE_ACTION, Constants.Action.OpenApp)
        }
        set(value) = prefsNormal.edit { putString(CLICK_DATE_ACTION, value.name) }

    var doubleTapAction: Constants.Action
        get() {
            return getEnumSetting(DOUBLE_TAP_ACTION, Constants.Action.LockScreen)
        }
        set(value) = prefsNormal.edit { putString(DOUBLE_TAP_ACTION, value.name) }

    var appTheme: Constants.Theme
        get() {
            return getEnumSetting(APP_THEME, Constants.Theme.System)
        }
        set(value) = prefsNormal.edit { putString(APP_THEME, value.name) }

    var appLanguage: Constants.Language
        get() {
            return getEnumSetting(APP_LANGUAGE, Constants.Language.System)
        }
        set(value) = prefsNormal.edit { putString(APP_LANGUAGE, value.name) }

    var searchEngines: Constants.SearchEngines
        get() {
            return getEnumSetting(SEARCH_ENGINE, Constants.SearchEngines.Google)
        }
        set(value) = prefsNormal.edit { putString(SEARCH_ENGINE, value.name) }

    var fontFamily: Constants.FontFamily
        get() {
            return getEnumSetting(LAUNCHER_FONT, Constants.FontFamily.GoogleSansFlex)
        }
        set(value) = prefsNormal.edit { putString(LAUNCHER_FONT, value.name) }

    var hiddenApps: MutableSet<String>
        get() = prefsNormal.getStringSet(HIDDEN_APPS, mutableSetOf()) as MutableSet<String>
        set(value) = prefsNormal.edit { putStringSet(HIDDEN_APPS, value) }

    var lockedApps: MutableSet<String>
        get() = prefsNormal.getStringSet(LOCKED_APPS, mutableSetOf()) as MutableSet<String>
        set(value) = prefsNormal.edit { putStringSet(LOCKED_APPS, value) }

    var pinnedApps: Set<String>
        get() = prefsNormal.getStringSet(PINNED_APPS, emptySet()) as Set<String>
        set(value) = prefsNormal.edit { putStringSet(PINNED_APPS, value) }

    /** The drawer's pinned icon row, in pin order. Keys are "package|class|userHash" ([AppListItem.pinKey]). */
    var pinnedRow: List<String>
        get() = prefsNormal.getString(PINNED_ROW, null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = prefsNormal.edit { putString(PINNED_ROW, value.joinToString("\n")) }

    /** False until the pinned row is first written; the old package-only pins migrate once. */
    val hasPinnedRow: Boolean get() = prefsNormal.contains(PINNED_ROW)

    var enableExpertOptions: Boolean
        get() = getSetting(EXPERT_OPTIONS, false)
        set(value) = prefsNormal.edit { putBoolean(EXPERT_OPTIONS, value) }

    /**
     * By the number in home app list, get the list item.
     * TODO why not just save it as a list?
     */
    fun getHomeAppModel(i: Int): AppListItem {
        return loadApp("$i")
    }

    fun setHomeAppModel(i: Int, appListItem: AppListItem) {
        storeApp("$i", appListItem)
    }

    var appShortSwipeUp: AppListItem
        get() = loadApp(SHORT_SWIPE_UP)
        set(appModel) = storeApp(SHORT_SWIPE_UP, appModel)
    var appShortSwipeDown: AppListItem
        get() = loadApp(SHORT_SWIPE_DOWN)
        set(appModel) = storeApp(SHORT_SWIPE_DOWN, appModel)
    var appShortSwipeLeft: AppListItem
        get() = loadApp(SHORT_SWIPE_LEFT)
        set(appModel) = storeApp(SHORT_SWIPE_LEFT, appModel)
    var appShortSwipeRight: AppListItem
        get() = loadApp(SHORT_SWIPE_RIGHT)
        set(appModel) = storeApp(SHORT_SWIPE_RIGHT, appModel)

    var appLongSwipeUp: AppListItem
        get() = loadApp(LONG_SWIPE_UP)
        set(appModel) = storeApp(LONG_SWIPE_UP, appModel)
    var appLongSwipeDown: AppListItem
        get() = loadApp(LONG_SWIPE_DOWN)
        set(appModel) = storeApp(LONG_SWIPE_DOWN, appModel)
    var appLongSwipeLeft: AppListItem
        get() = loadApp(LONG_SWIPE_LEFT)
        set(appModel) = storeApp(LONG_SWIPE_LEFT, appModel)
    var appLongSwipeRight: AppListItem
        get() = loadApp(LONG_SWIPE_RIGHT)
        set(appModel) = storeApp(LONG_SWIPE_RIGHT, appModel)

    var appClickClock: AppListItem
        get() = loadApp(CLICK_CLOCK)
        set(appModel) = storeApp(CLICK_CLOCK, appModel)
    var appFloating: AppListItem
        get() = loadApp(CLICK_FLOATING)
        set(appModel) = storeApp(CLICK_FLOATING, appModel)
    var appClickDate: AppListItem
        get() = loadApp(CLICK_DATE)
        set(appModel) = storeApp(CLICK_DATE, appModel)
    var appDoubleTap: AppListItem
        get() = loadApp(DOUBLE_TAP)
        set(appModel) = storeApp(DOUBLE_TAP, appModel)

    /**
     *  Restore an `AppListItem` from preferences.
     *
     *  We store not only application name, but everything needed to start the item.
     *  Because thus we save time to query the system about it?
     *
     *  TODO store with protobuf instead of serializing manually.
     */
    private fun loadApp(id: String): AppListItem {
        val appName = prefsNormal.getString("${APP_NAME}_$id", emptyString()).toString()
        val appPackage = prefsNormal.getString("${APP_PACKAGE}_$id", emptyString()).toString()
        val appActivityName = prefsNormal.getString("${APP_ACTIVITY}_$id", emptyString()).toString()

        val userHandleString = try {
            prefsNormal.getString("${APP_USER}_$id", emptyString()).toString()
        } catch (_: Exception) {
            emptyString()
        }
        val userHandle: UserHandle = getUserHandleFromString(context, userHandleString)

        return AppListItem(
            activityLabel = appName,
            activityPackage = appPackage,
            customTag = emptyString(),
            activityClass = appActivityName,
            user = userHandle,
        )
    }

    private fun storeApp(id: String, app: AppListItem) {
        prefsNormal.edit {
            if (app.activityPackage.isNotEmpty() && app.activityClass.isNotEmpty()) {
                putString("${APP_NAME}_$id", app.activityLabel)
                putString("${APP_PACKAGE}_$id", app.activityPackage)
                putString("${APP_ACTIVITY}_$id", app.activityClass)
                putString("${APP_USER}_$id", app.user.toString())
            } else {
                remove("${APP_NAME}_$id")
                remove("${APP_PACKAGE}_$id")
                remove("${APP_ACTIVITY}_$id")
                remove("${APP_USER}_$id")
            }
        }
    }

    var appSize: Int
        get() {
            return getSetting(APP_SIZE_TEXT, 18)
        }
        set(value) = prefsNormal.edit { putInt(APP_SIZE_TEXT, value) }

    var dateSize: Int
        get() {
            return getSetting(DATE_SIZE_TEXT, 22)
        }
        set(value) = prefsNormal.edit { putInt(DATE_SIZE_TEXT, value) }

    var clockSize: Int
        get() {
            return getSetting(CLOCK_SIZE_TEXT, 42)
        }
        set(value) = prefsNormal.edit { putInt(CLOCK_SIZE_TEXT, value) }

    var textPaddingSize: Int
        get() {
            return getSetting(TEXT_PADDING_SIZE, 10)
        }
        set(value) = prefsNormal.edit { putInt(TEXT_PADDING_SIZE, value) }

    // Save flags as a string of 6 bits
    fun saveMenuFlags(settingFlags: String, flags: List<Boolean>) {
        val flagString = flags.joinToString("") { if (it) "1" else "0" }
        prefsNormal.edit { putString(settingFlags, flagString) }
    }

    // Get flags as list of booleans
    fun getMenuFlags(settingFlags: String, default: String = "0"): List<Boolean> {
        val flagString = prefsNormal.getString(settingFlags, default) ?: default
        return flagString.map { it == '1' }
    }

    private fun getColorInt(type: String): Int {
        val isDarkMode = isSystemInDarkMode(context)

        val lightModeColors = mapOf(
            "bg" to R.color.white,
        )

        val darkModeColors = mapOf(
            "bg" to R.color.black,
        )

        val defaultLight = R.color.black
        val defaultDark = R.color.white

        return when (appTheme) {
            Constants.Theme.System -> {
                if (isDarkMode) darkModeColors[type] ?: defaultDark
                else lightModeColors[type] ?: defaultLight
            }

            Constants.Theme.Dark -> darkModeColors[type] ?: defaultDark
            Constants.Theme.Light -> lightModeColors[type] ?: defaultLight
        }
    }

    // return app label
    fun getAppName(location: Int): String {
        return getHomeAppModel(location).activityLabel
    }

    fun getAppAlias(appPackage: String): String {
        return prefsNormal.getString("${appPackage}_ALIAS", emptyString()).toString()
    }

    fun setAppAlias(appPackage: String, appAlias: String) {
        prefsNormal.edit { putString("${appPackage}_ALIAS", appAlias) }
    }

    fun setProfileCounter(profile: String, counter: Int) {
        prefsNormal.edit { putInt(profile, counter) }
    }

    fun getProfileCounter(profile: String): Int {
        return prefsNormal.getInt(profile, 0)
    }

    fun getAppTag(appPackage: String, userHandle: UserHandle? = null): String {
        val baseKey = "${appPackage}_TAG"
        val userKey = userHandle?.let { "${baseKey}_${it.hashCode()}" }

        return prefsNormal.getString(
            userKey ?: baseKey, ""  // Try the user-specific key first if available
        )?.also { value ->
            // Migrate base key to user-specific key if needed
            if (userHandle != null && prefsNormal.contains(baseKey)) {
                prefsNormal.edit {
                    remove(baseKey)           // Remove old base key
                    putString(userKey, value) // Save under user-specific key
                }
            }
        } ?: ""
    }

    fun setAppTag(appPackage: String, appTag: String, userHandle: UserHandle? = null) {
        prefsNormal.edit {
            // Remove base key
            remove("${appPackage}_TAG")

            userHandle?.let {
                // Remove key using the UserHandle object itself
                remove("${appPackage}_TAG_${it}")

                // Set new TAG with hashCode()
                putString("${appPackage}_TAG_${it.hashCode()}", appTag)
            }
        }
    }

    fun remove(prefName: String) {
        prefsNormal.edit { remove(prefName) }
    }

    fun clear() {
        prefsNormal.edit { clear() }
    }

    // Function to fetch enum value from SharedPreferences
    // Unknown names (e.g. actions removed in this build) and wrong types fall back to the default
    private inline fun <reified T : Enum<T>> getEnumSetting(key: String, defaultValue: T): T {
        return try {
            val enumName = prefsNormal.getString(key, defaultValue.name)
            enumValueOf<T>(enumName ?: defaultValue.name)
        } catch (_: Exception) {
            defaultValue
        }
    }

    // A restored backup can store a value with the wrong type (JSON has one number type,
    // so 1.0f comes back as an Int). Coerce numbers, otherwise fall back to the default.
    private inline fun <reified T> getSetting(key: String, defaultValue: T): T {
        val result: Any? = try {
            when (defaultValue) {
                is Int -> prefsNormal.getInt(key, defaultValue)
                is Boolean -> prefsNormal.getBoolean(key, defaultValue)
                is String -> prefsNormal.getString(key, defaultValue) ?: defaultValue
                is Float -> prefsNormal.getFloat(key, defaultValue)
                else -> throw IllegalArgumentException("Unsupported type")
            }
        } catch (_: ClassCastException) {
            val raw = prefsNormal.all[key]
            when {
                defaultValue is Int && raw is Number -> raw.toInt()
                defaultValue is Float && raw is Number -> raw.toFloat()
                else -> defaultValue
            }
        }

        return result as T
    }

    fun isOnboardingCompleted(): Boolean {
        return prefsOnboarding.getBoolean(ONBOARDING_COMPLETED, false)
    }

    // Function to mark onboarding as completed
    fun setOnboardingCompleted(isCompleted: Boolean) {
        prefsOnboarding.edit { putBoolean(ONBOARDING_COMPLETED, isCompleted) }
    }
}
