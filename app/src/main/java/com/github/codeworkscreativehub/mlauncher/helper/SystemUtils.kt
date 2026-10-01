package com.github.codeworkscreativehub.mlauncher.helper

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.UiModeManager
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.format.DateFormat
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.View
import android.view.Window
import android.view.WindowInsets
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.withSave
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.github.codeworkscreativehub.common.AppLogger
import com.github.codeworkscreativehub.common.ColorIconsExtensions
import com.github.codeworkscreativehub.common.CrashHandler
import com.github.codeworkscreativehub.common.getLocalizedString
import com.github.codeworkscreativehub.common.getLocalizedStringArray
import com.github.codeworkscreativehub.common.openAccessibilitySettings
import com.github.codeworkscreativehub.common.showLongToast
import com.github.codeworkscreativehub.mlauncher.BuildConfig
import com.github.codeworkscreativehub.mlauncher.R
import com.github.codeworkscreativehub.mlauncher.data.Constants
import com.github.codeworkscreativehub.mlauncher.data.Prefs
import com.github.codeworkscreativehub.mlauncher.helper.utils.packageNames
import com.github.codeworkscreativehub.mlauncher.services.ActionService
import com.github.codeworkscreativehub.mlauncher.ui.widgets.home.HomeAppsWidgetProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.system.exitProcess

fun emptyString(): String {
    return ""
}

val iconPackActions = listOf(
    "app.mlauncher.THEME",
    "org.adw.launcher.THEMES",
    "com.gau.go.launcherex.theme",
    "com.novalauncher.THEME",
    "com.anddoes.launcher.THEME",
    "com.teslacoilsw.launcher.THEME",
    "app.lawnchair.icons.THEMED_ICON"
)

val iconPackBlacklist = listOf(
    "ginlemon.iconpackstudio"
)

fun getUserHandleFromString(context: Context, userHandleString: String): UserHandle {
    val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
    for (userHandle in userManager.userProfiles) {
        if (userHandle.toString() == userHandleString) {
            return userHandle
        }
    }
    return Process.myUserHandle()
}

fun ismlauncherDefault(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
    } else {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo =
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val defaultLauncherPackage = resolveInfo?.activityInfo?.packageName
        return context.packageName == defaultLauncherPackage
    }
}

fun reloadLauncher() {
    android.os.Handler(Looper.getMainLooper()).postDelayed({
        exitProcess(0)
    }, 100)
}

fun openAppInfo(context: Context, userHandle: UserHandle, packageName: String) {
    val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    val intent: Intent? = context.packageManager.getLaunchIntentForPackage(packageName)
    intent?.let {
        launcher.startAppDetailsActivity(intent.component, userHandle, null, null)
    } ?: context.showLongToast("Unable to to open app info")
}

fun initActionService(context: Context): ActionService? {
    val actionService = ActionService.instance()
    if (actionService != null) {
        return actionService
    } else {
        context.openAccessibilitySettings()
    }

    return null
}

fun showStatusBar(window: Window) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val insetsController = window.insetsController
        if (insetsController != null) {
            insetsController.show(WindowInsets.Type.statusBars())
        } else {
            // Postpone if insetsController not ready yet
            window.decorView.post {
                window.insetsController?.show(WindowInsets.Type.statusBars())
            }
        }
    } else {
        @Suppress("DEPRECATION", "InlinedApi")
        window.decorView.let { decorView ->
            val flags = decorView.systemUiVisibility and
                    (View.SYSTEM_UI_FLAG_FULLSCREEN.inv()) // clear fullscreen flag
            decorView.systemUiVisibility =
                flags or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }
}

fun hideStatusBar(window: Window) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val insetsController = window.insetsController
        if (insetsController != null) {
            insetsController.hide(WindowInsets.Type.statusBars())
        } else {
            window.decorView.post {
                window.insetsController?.hide(WindowInsets.Type.statusBars())
            }
        }
    } else {
        @Suppress("DEPRECATION")
        (window.decorView.let { decorView ->
            val flags = decorView.systemUiVisibility or
                    View.SYSTEM_UI_FLAG_IMMERSIVE or
                    View.SYSTEM_UI_FLAG_FULLSCREEN
            decorView.systemUiVisibility = flags
        })
    }
}

fun showNavigationBar(window: Window) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val insetsController = window.insetsController
        if (insetsController != null) {
            insetsController.show(WindowInsets.Type.navigationBars())
        } else {
            window.decorView.post {
                window.insetsController?.show(WindowInsets.Type.navigationBars())
            }
        }
    } else {
        @Suppress("DEPRECATION", "InlinedApi")
        (window.decorView.let { decorView ->
            val flags = decorView.systemUiVisibility and
                    (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION.inv()) // clear hide nav flag
            decorView.systemUiVisibility =
                flags or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        })
    }
}

fun hideNavigationBar(window: Window) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val insetsController = window.insetsController
        if (insetsController != null) {
            insetsController.hide(WindowInsets.Type.navigationBars())
        } else {
            window.decorView.post {
                window.insetsController?.hide(WindowInsets.Type.navigationBars())
            }
        }
    } else {
        @Suppress("DEPRECATION")
        (window.decorView.let { decorView ->
            val flags = decorView.systemUiVisibility or
                    View.SYSTEM_UI_FLAG_IMMERSIVE or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN
            decorView.systemUiVisibility = flags
        })
    }
}

fun dp2px(resources: Resources, dp: Int): Int {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp.toFloat(),
        resources.displayMetrics
    ).toInt()
}

fun sp2px(resources: Resources, sp: Float): Float {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        sp,
        resources.displayMetrics
    )
}

fun getHexForOpacity(prefs: Prefs): Int {
    // Convert the opacity percentage (0-100) to a reversed decimal (0.0-1.0)
    val setOpacity = ((100 - prefs.opacityNum.coerceIn(
        0,
        100
    )) / 100.0).toFloat() // Reverse the opacity, (0% = full opacity, 100% = transparent)

    val backgroundColor = prefs.backgroundColor // This is already an Int

    // Extract RGB from background color
    val red = android.graphics.Color.red(backgroundColor)
    val green = android.graphics.Color.green(backgroundColor)
    val blue = android.graphics.Color.blue(backgroundColor)

    // Combine opacity with RGB and return final color
    return if (prefs.showBackground) {
        // Apply a minimum opacity constant for the background
        android.graphics.Color.argb((Constants.MIN_OPACITY * 255), red, green, blue)
    } else {
        // Use the reversed opacity as a percentage (0-100%) converted to a float (0.0-1.0)
        android.graphics.Color.argb((setOpacity * 255).toInt(), red, green, blue)
    }
}

fun isSystemInDarkMode(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
    return uiModeManager.nightMode == UiModeManager.MODE_NIGHT_YES
}

fun setThemeMode(context: Context, isDark: Boolean, view: View) {
    // Retrieve background color based on the theme
    val backgroundAttr = if (isDark) R.attr.backgroundDark else R.attr.backgroundLight

    val typedValue = TypedValue()
    val theme: Resources.Theme = context.theme
    theme.resolveAttribute(backgroundAttr, typedValue, true)

    // Apply the background color from styles.xml
    view.setBackgroundResource(typedValue.resourceId)
}

fun parseBlacklistXML(context: Context): List<String> {
    // Obtain an XmlPullParser for the blacklist.xml file
    context.resources.getXml(R.xml.blacklist).use { parser ->
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "app") {
                val packageName = parser.getAttributeValue(null, "packageName")
                packageNames.add(packageName)
            }
            parser.next()
        }
    }

    return packageNames
}

fun getTrueSystemFont(): Typeface {
    val possibleSystemFonts = listOf(
        "/system/fonts/Roboto-Regular.ttf",      // Stock Android (Pixel, AOSP)
        "/system/fonts/NotoSans-Regular.ttf",    // Some Android One devices
        "/system/fonts/SamsungOne-Regular.ttf",  // Samsung
        "/system/fonts/MiSans-Regular.ttf",      // Xiaomi MIUI
        "/system/fonts/OPSans-Regular.ttf"       // OnePlus
    )

    for (fontPath in possibleSystemFonts) {
        val fontFile = File(fontPath)
        if (fontFile.exists()) {
            return Typeface.createFromFile(fontFile)
        }
    }

    // Fallback to Roboto as a default if no system font is found
    return Typeface.DEFAULT
}

fun formatLongToCalendar(longTimestamp: Long): String {
    // Create a Calendar instance and set it's time to the given timestamp (in milliseconds)
    val calendar = Calendar.getInstance().apply {
        timeInMillis = longTimestamp
    }

    // Format the calendar object to a readable string
    val dateFormat = SimpleDateFormat(
        "MMMM dd, yyyy, HH:mm:ss",
        Locale.getDefault()
    ) // You can modify the format
    return dateFormat.format(calendar.time) // Return the formatted date string
}

fun logActivitiesFromPackage(context: Context, packageName: String) {
    val packageManager = context.packageManager

    try {
        val packageInfo: PackageInfo = packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_ACTIVITIES
        )

        val activities: Array<ActivityInfo>? = packageInfo.activities

        activities?.forEach { activityInfo ->
            val componentInfoString =
                "ComponentInfo{${activityInfo.packageName}/${activityInfo.name}}"
            AppLogger.d("ComponentInfoLog", componentInfoString)
        } ?: AppLogger.d("ComponentInfoLog", "No activities found in package $packageName")

    } catch (e: PackageManager.NameNotFoundException) {
        AppLogger.e("ComponentInfoLog", "Package not found: $packageName", e)
    }
}

fun getDeviceInfo(context: Context): String {
    return try {
        val packageManager = context.packageManager
        val installSource = getInstallSource(packageManager, context.packageName)

        """
            Manufacturer: ${Build.MANUFACTURER}
            Model: ${Build.MODEL}
            Brand: ${Build.BRAND}
            Device: ${Build.DEVICE}
            Product: ${Build.PRODUCT}
            Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
            ABI: ${Build.SUPPORTED_ABIS.joinToString()}
            App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
            Locale: ${Locale.getDefault()}
            Timezone: ${TimeZone.getDefault().id}
            Installed From: $installSource
            """.trimIndent()
    } catch (e: Exception) {
        "Device Info Unavailable: ${e.message}"
    }
}

// 1. Define the data class
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val brand: String,
    val device: String,
    val product: String,
    val androidVersion: String,
    val sdkInt: Int,
    val abi: String,
    val appVersionName: String,
    val appVersionCode: Int,
    val locale: String,
    val timezone: String,
    val installedFrom: String
)

// 2. Function to get DeviceInfo object
fun getDeviceInfoObject(context: Context): DeviceInfo {
    val packageManager = context.packageManager
    val installSource = getInstallSource(packageManager, context.packageName)

    return DeviceInfo(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        brand = Build.BRAND,
        device = Build.DEVICE,
        product = Build.PRODUCT,
        androidVersion = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        abi = Build.SUPPORTED_ABIS.joinToString(),
        appVersionName = BuildConfig.VERSION_NAME,
        appVersionCode = BuildConfig.VERSION_CODE,
        locale = Locale.getDefault().toString(),
        timezone = TimeZone.getDefault().id,
        installedFrom = installSource
    )
}

// 3. Function to convert DeviceInfo to JSON using Moshi
fun getDeviceInfoJson(context: Context): String {
    return try {
        val deviceInfo = getDeviceInfoObject(context)

        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        val jsonAdapter = moshi.adapter(DeviceInfo::class.java).indent("    ") // pretty print

        jsonAdapter.toJson(deviceInfo)
    } catch (e: Exception) {
        """{"error": "Device Info Unavailable: ${e.message}"}"""
    }
}

fun getInstallSource(
    packageManager: PackageManager,
    packageName: String
): String {
    return try {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager
                .getInstallSourceInfo(packageName)
                .installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(packageName)
        }

        when (installer) {
            "com.android.vending" ->
                "Google Play Store"

            "com.android.shell" ->
                "ADB / Android Studio"

            "org.fdroid.fdroid" ->
                "F-Droid"

            "dev.imranr.obtainium" ->
                "Obtainium"

            "com.amazon.venezia" ->
                "Amazon Appstore"

            "com.sec.android.app.samsungapps" ->
                "Samsung Galaxy Store"

            "com.huawei.appmarket" ->
                "Huawei AppGallery"

            "com.xiaomi.market" ->
                "Xiaomi GetApps"

            "com.oppo.market" ->
                "OPPO App Market"

            "com.vivo.appstore" ->
                "Vivo App Store"

            else ->
                installer ?: "Unknown"
        }

    } catch (_: Exception) {
        "Unknown"
    }
}

fun getSystemIcons(
    context: Context,
    prefs: Prefs,
    target: IconCacheTarget,
    nonNullDrawable: Drawable
): Drawable? {
    return when (target) {
        IconCacheTarget.APP_LIST -> getAppListIcons(context, prefs, nonNullDrawable)
        IconCacheTarget.HOME -> getHomeIcons(context, prefs, nonNullDrawable)
    }
}

fun updateAllWidgets(context: Context) {
    updateHomeWidget(context)
    updateFabWidget(context)
}

fun updateHomeWidget(context: Context) {
    val appWidgetManager = AppWidgetManager.getInstance(context)
    val componentName = ComponentName(context, HomeAppsWidgetProvider::class.java)
    val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

    val intent = Intent(context, HomeAppsWidgetProvider::class.java).apply {
        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
    }
    context.sendBroadcast(intent)
}

fun updateFabWidget(context: Context) {
    val appWidgetManager = AppWidgetManager.getInstance(context)
    val componentName = ComponentName(
        context,
        HomeAppsWidgetProvider::class.java
    ) // Replace with your FAB widget class
    val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

    val intent = Intent(context, HomeAppsWidgetProvider::class.java).apply {
        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
    }

    context.sendBroadcast(intent)
}

private fun getAppListIcons(context: Context, prefs: Prefs, nonNullDrawable: Drawable): Drawable? {
    return when (prefs.iconPackAppList) {
        Constants.IconPacks.CloudDots -> {
            val newIcon = ContextCompat.getDrawable(
                context,
                R.drawable.cloud_dots_icon
            )!!
            val dominantColor = ColorIconsExtensions.getDominantColor(nonNullDrawable)
            ColorIconsExtensions.recolorDrawable(newIcon, dominantColor)
        }

        Constants.IconPacks.LauncherDots -> {
            val newIcon = ContextCompat.getDrawable(
                context,
                R.drawable.launcher_dot_icon
            )!!
            val dominantColor = ColorIconsExtensions.getDominantColor(nonNullDrawable)
            ColorIconsExtensions.recolorDrawable(newIcon, dominantColor)
        }

        Constants.IconPacks.NiagaraDots -> {
            val newIcon = ContextCompat.getDrawable(
                context,
                R.drawable.niagara_dot_icon
            )!!
            val dominantColor = ColorIconsExtensions.getDominantColor(nonNullDrawable)
            ColorIconsExtensions.recolorDrawable(newIcon, dominantColor)
        }

        Constants.IconPacks.SpinnerDots -> {
            val newIcon = ContextCompat.getDrawable(
                context,
                R.drawable.spinner_dots_icon
            )!!
            val dominantColor = ColorIconsExtensions.getDominantColor(nonNullDrawable)
            ColorIconsExtensions.recolorDrawable(newIcon, dominantColor)
        }

        else -> {
            null
        }
    }
}

private fun getHomeIcons(context: Context, prefs: Prefs, nonNullDrawable: Drawable): Drawable? {
    return when (prefs.iconPackHome) {
        Constants.IconPacks.CloudDots -> {
            val newIcon = ContextCompat.getDrawable(
                context,
                R.drawable.cloud_dots_icon
            )!!
            val dominantColor = ColorIconsExtensions.getDominantColor(nonNullDrawable)
            ColorIconsExtensions.recolorDrawable(newIcon, dominantColor)
        }

        Constants.IconPacks.LauncherDots -> {
            val newIcon = ContextCompat.getDrawable(
                context,
                R.drawable.launcher_dot_icon
            )!!
            val dominantColor = ColorIconsExtensions.getDominantColor(nonNullDrawable)
            ColorIconsExtensions.recolorDrawable(newIcon, dominantColor)
        }

        Constants.IconPacks.NiagaraDots -> {
            val newIcon = ContextCompat.getDrawable(
                context,
                R.drawable.niagara_dot_icon
            )!!
            val dominantColor = ColorIconsExtensions.getDominantColor(nonNullDrawable)
            ColorIconsExtensions.recolorDrawable(newIcon, dominantColor)
        }

        Constants.IconPacks.SpinnerDots -> {
            val newIcon = ContextCompat.getDrawable(
                context,
                R.drawable.spinner_dots_icon
            )!!
            val dominantColor = ColorIconsExtensions.getDominantColor(nonNullDrawable)
            ColorIconsExtensions.recolorDrawable(newIcon, dominantColor)
        }

        else -> {
            null
        }
    }
}

fun setTopPadding(view: View, isSettings: Boolean = false) {
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->

        val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val displayCutout = insets.displayCutout

        // Start with the top inset from status bar / notch area
        val topInset = systemBarsInsets.top

        // If there's a cutout (notch), its safeInsetTop should already be included in systemBarsInsets.top,
        // but to be safe, you could add it explicitly if needed
        val cutoutTopInset = displayCutout?.safeInsetTop ?: 0

        // Combine topInset and cutout if you want to make sure notch space is accounted for:
        val totalTopInset = maxOf(topInset, cutoutTopInset)

        // Apply your fallback & settings logic
        val finalTopPadding = when {
            totalTopInset == 0 -> (24 * v.resources.displayMetrics.density).toInt()
            isSettings -> totalTopInset
            else -> totalTopInset / 2
        }

        v.updatePadding(top = finalTopPadding)

        insets
    }

    ViewCompat.requestApplyInsets(view)
}

