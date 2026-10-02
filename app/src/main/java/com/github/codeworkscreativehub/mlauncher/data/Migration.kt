package com.github.codeworkscreativehub.mlauncher.data

import android.content.Context
import androidx.core.content.edit
import com.github.codeworkscreativehub.common.AppLogger
import com.github.codeworkscreativehub.mlauncher.BuildConfig
import java.io.File

class Migration(val context: Context) {
    fun migratePreferencesOnVersionUpdate(prefs: Prefs) {
        val currentVersionCode = BuildConfig.VERSION_CODE
        val savedVersionCode = prefs.appVersion

        AppLogger.d("PrefsMigration", "Starting migration: savedVersion=$savedVersionCode, currentVersion=$currentVersionCode")

        // Map of version code -> preferences to clear (wildcards allowed)
        val versionCleanupMap = mapOf(
            171 to listOf(
                "APP_DARK_COLORS",
                "APP_LIGHT_COLORS",
                "HOME_FOLLOW_ACCENT",
                "ALL_APPS_TEXT",
            ),
            172 to listOf(
                "TIME_ALIGNMENT",
                "SHOW_TIME",
                "SHOW_TIME_FORMAT",
                "TIME_COLOR",
            ),
            175 to listOf(
                "CLICK_APP_USAGE",
            ),
            10803 to listOf(
                "SHOW_EDGE_PANEL",
                "EDGE_APPS_NUM",
            ),
            10812 to listOf(
                "LOCK_MODE",
            ),
            1100504 to listOf(
                "SHOW_AZSIDEBAR",
            ),
            1100508 to listOf(
                "EXPERIMENTAL_OPTIONS",
            ),
            1100709 to listOf(
                "APP_TIMER",
                "SHORT_SWIPE_THRESHOLD",
                "LONG_SWIPE_THRESHOLD",
            ),
            1110303 to listOf(
                "APP_ALIAS_*"
            ),
            // Drawer list moved to the left with the A-Z sidebar on the right
            1120103 to listOf(
                "DRAWER_ALIGNMENT",
            ),
        )

        var totalRemoved = 0

        // Build 49: the home list goes centred (icons kept inline) and sits centred between the
        // date and the bottom gap. Only moves people off the old right-aligned default.
        if (savedVersionCode in 1..1120148 && currentVersionCode >= 1120149) {
            if (prefs.homeAlignment == Constants.Gravity.Right) {
                prefs.homeAlignment = Constants.Gravity.Center
                prefs.homeAlignmentBottom = false
            }
        }

        // Build 51: right-aligned after all (vertically centred stays). Undo the build 49 flip.
        if (savedVersionCode in 1..1120150 && currentVersionCode >= 1120151) {
            if (prefs.homeAlignment == Constants.Gravity.Center) prefs.homeAlignment = Constants.Gravity.Right
            prefs.homeAlignmentBottom = false
        }

        for ((version, keys) in versionCleanupMap) {
            // Only versions newer than the one last run; an inclusive lower bound re-ran them every launch
            if (version > savedVersionCode && version <= currentVersionCode) {
                val allKeys = prefs.prefsNormal.all.keys
                val removedThisVersion = mutableListOf<String>()

                prefs.prefsNormal.edit {
                    keys.forEach { keyPattern ->
                        if (keyPattern.contains("*")) {
                            val prefix = keyPattern.removeSuffix("*")
                            val matchedKeys = allKeys.filter { it.startsWith(prefix) }
                            matchedKeys.forEach { key ->
                                remove(key)
                                removedThisVersion.add(key)
                                totalRemoved++
                            }
                            if (matchedKeys.isNotEmpty()) {
                                AppLogger.d(
                                    "PrefsMigration",
                                    "Version $version wildcard pattern '$keyPattern' removed: ${matchedKeys.joinToString()}"
                                )
                            }
                        } else {
                            remove(keyPattern)
                            removedThisVersion.add(keyPattern)
                            totalRemoved++
                            AppLogger.d(
                                "PrefsMigration",
                                "Version $version removed key: $keyPattern"
                            )
                        }
                    }
                }

                if (removedThisVersion.isEmpty()) {
                    AppLogger.d("PrefsMigration", "Version $version had no keys to remove.")
                }
            }
        }

        prefs.appVersion = currentVersionCode
        AppLogger.d(
            "PrefsMigration",
            "Migration completed: updated app version to $currentVersionCode, total keys removed: $totalRemoved"
        )
    }

    /** Drop stored data of features this build no longer has (notes, weather, usage stats...). */
    fun removeDeletedFeatureData(prefs: Prefs) {
        val stale = prefs.prefsNormal.all.keys.filter { key -> REMOVED_KEY_PREFIXES.any { key.startsWith(it) } }
        if (stale.isEmpty()) return
        prefs.prefsNormal.edit { stale.forEach { remove(it) } }
        AppLogger.d("PrefsMigration", "Removed ${stale.size} keys of deleted features")
    }

    fun deleteOldCacheFiles(appContext: Context) {
        // References to the old files in filesDir
        val oldAppsCacheFile = File(appContext.filesDir, "apps_cache.json")
        val oldContactsCacheFile = File(appContext.filesDir, "contacts_cache.json")

        // Delete them if they exist
        if (oldAppsCacheFile.exists()) {
            oldAppsCacheFile.delete()
            AppLogger.d("CacheCleanup", "apps_cache.json deleted")
        }

        if (oldContactsCacheFile.exists()) {
            oldContactsCacheFile.delete()
            AppLogger.d("CacheCleanup", "contacts_cache.json deleted")
        }

        // Contacts search was removed; its cache lives in cacheDir
        File(appContext.cacheDir, "contacts_cache.json").delete()
        // The widgets page was removed; drop its database
        appContext.deleteDatabase("widget_database")
    }
}