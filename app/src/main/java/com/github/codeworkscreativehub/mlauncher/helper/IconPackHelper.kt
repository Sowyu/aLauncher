package com.github.codeworkscreativehub.mlauncher.helper

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.Xml
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import com.github.codeworkscreativehub.common.AppLogger
import com.github.codeworkscreativehub.mlauncher.R
import com.github.codeworkscreativehub.mlauncher.data.Constants
import com.github.codeworkscreativehub.mlauncher.data.Prefs
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.ConcurrentHashMap

enum class IconCacheTarget {
    APP_LIST,
    HOME
}

/**
 * The one icon resolver for home, drawer and widget: icon pack -> generated monochrome glyph
 * (custom pack only, optional) -> system icon.
 *
 * Why icons used to "change back" to colourful ones: two preloads ran at start-up at the same
 * time (app init and MainActivity), each clearing and refilling the same unsynchronised map, and
 * anything drawn meanwhile fell back to the system icon and stayed cached in the view. The pack
 * index also only covered apps installed at preload time and was never refreshed when the pack
 * updated. Now the index is built lazily under a lock, covers every entry in the pack, is keyed
 * by the pack's update time (so a pack update re-reads it), and a failed load is retried on the
 * next request instead of being remembered.
 */
object IconPackHelper {

    private class PackIndex(
        val pack: String,
        val updated: Long,
        /** "pkg/cls" and "pkg" -> drawable name */
        val names: Map<String, String>,
    )

    @Volatile
    private var index: PackIndex? = null
    private val indexLock = Any()

    /** pack:drawableName -> constant state, so each caller gets its own drawable instance. */
    private val drawableCache = ConcurrentHashMap<String, Drawable.ConstantState>()

    /** Bumped whenever cached icons may be stale; screens compare it to know when to redraw. */
    @Volatile
    var generation: Int = 0
        private set

    /** Build the pack index ahead of time. Safe to call from any thread, any number of times. */
    fun preloadIcons(context: Context, iconPackPackage: String, @Suppress("UNUSED_PARAMETER") target: IconCacheTarget) {
        if (iconPackPackage.isNotEmpty()) indexFor(context, iconPackPackage)
    }

    /** A package was installed, updated or removed. Drop anything that may depend on it. */
    fun onPackageChanged(packageName: String?) {
        val current = index
        if (packageName == null || current?.pack == packageName) {
            synchronized(indexLock) { index = null }
            drawableCache.clear()
        }
        MonochromeIcons.forget(packageName)
        generation++
    }

    private fun packUpdateTime(context: Context, pack: String): Long? = try {
        context.packageManager.getPackageInfo(pack, 0).lastUpdateTime
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun indexFor(context: Context, pack: String): PackIndex? {
        val updated = packUpdateTime(context, pack) ?: return null
        index?.let { if (it.pack == pack && it.updated == updated) return it }
        synchronized(indexLock) {
            index?.let { if (it.pack == pack && it.updated == updated) return it }
            val built = parsePack(context, pack, updated) ?: return null // retried next time
            if (index?.pack != pack || index?.updated != updated) drawableCache.clear()
            index = built
            return built
        }
    }

    private fun parsePack(context: Context, pack: String, updated: Long): PackIndex? {
        return try {
            val packContext = context.createPackageContext(pack, 0)
            val stream = listOf("appfilter.xml", "appmap.xml", "drawable.xml").firstNotNullOfOrNull { name ->
                runCatching { packContext.assets.open(name) }.getOrNull()
            }
            val parser: XmlPullParser = if (stream != null) {
                Xml.newPullParser().apply { setInput(stream, null) }
            } else {
                @SuppressLint("DiscouragedApi")
                val resId = packContext.resources.getIdentifier("appfilter", "xml", pack)
                if (resId == 0) return null
                packContext.resources.getXml(resId)
            }

            val names = HashMap<String, String>()
            val regex = Regex("""ComponentInfo\{([^/}]+)(?:/([^}]*))?\}""")
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "item") {
                    val component = parser.getAttributeValue(null, "component")
                    val drawable = parser.getAttributeValue(null, "drawable")
                        ?.removePrefix("@drawable/")?.removePrefix("drawable/")
                    val match = component?.let { regex.find(it) }
                    if (match != null && !drawable.isNullOrEmpty()) {
                        val pkg = match.groupValues[1]
                        var cls = match.groupValues[2]
                        if (cls.startsWith(".")) cls = pkg + cls
                        if (cls.isNotEmpty()) names.putIfAbsent("$pkg/$cls", drawable)
                        names.putIfAbsent(pkg, drawable)
                    }
                }
                event = parser.next()
            }
            stream?.close()
            AppLogger.d("IconPack", "Indexed ${names.size} entries from $pack")
            PackIndex(pack, updated, names)
        } catch (e: Exception) {
            AppLogger.e("IconPack", "Could not read icon pack $pack", e)
            null
        }
    }

    /** Drawable from the pack for this app, or null if the pack has none. */
    fun packIcon(context: Context, pack: String, packageName: String, activityClass: String?): Drawable? {
        val idx = indexFor(context, pack) ?: return null
        val name = activityClass?.takeIf { it.isNotEmpty() }?.let { idx.names["$packageName/$it"] }
            ?: idx.names[packageName]
            ?: return null
        val key = "$pack:$name"
        drawableCache[key]?.let { return it.newDrawable().mutate() }
        return try {
            val packContext = context.createPackageContext(pack, 0)
            @SuppressLint("DiscouragedApi")
            val resId = packContext.resources.getIdentifier(name, "drawable", pack)
            if (resId == 0) return null
            val d = ResourcesCompat.getDrawable(packContext.resources, resId, packContext.theme) ?: return null
            d.constantState?.let { drawableCache[key] = it }
            d.mutate()
        } catch (e: Exception) {
            AppLogger.e("IconPack", "Could not load $name from $pack", e)
            null
        }
    }

    // Prefs reads go straight to SharedPreferences, so one instance stays current
    @Volatile
    private var prefsInstance: Prefs? = null
    private fun prefs(context: Context): Prefs =
        prefsInstance ?: Prefs(context.applicationContext).also { prefsInstance = it }

    private fun systemIcon(context: Context, packageName: String): Drawable? = try {
        context.packageManager.getApplicationIcon(packageName)
    } catch (_: Exception) {
        null
    }

    /**
     * The icon to show for an app. With a custom pack: pack entry, else (if enabled) a generated
     * white glyph, else the system icon. Each call returns its own drawable instance, so home and
     * drawer can size theirs independently.
     */
    fun getSafeAppIcon(
        context: Context,
        packageName: String,
        useIconPack: Boolean,
        iconPackTarget: IconCacheTarget,
        activityClass: String? = null,
    ): Drawable {
        val prefs = prefs(context)
        val pack = when (iconPackTarget) {
            IconCacheTarget.HOME -> prefs.customIconPackHome
            IconCacheTarget.APP_LIST -> prefs.customIconPackAppList
        }
        val icon = try {
            if (useIconPack && pack.isNotEmpty()) {
                packIcon(context, pack, packageName, activityClass)
                    ?: (if (prefs.monochromeIconFallback) MonochromeIcons.get(context, packageName, activityClass, pack) else null)
                    ?: systemIcon(context, packageName)
            } else {
                systemIcon(context, packageName)
            }
        } catch (e: Exception) {
            AppLogger.e("IconHelper", "Failed to get icon for package: $packageName", e)
            null
        }

        return icon
            ?: ContextCompat.getDrawable(context, R.drawable.ic_default_app)
            ?: ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)
            ?: Color.TRANSPARENT.toDrawable()
    }

    /** True when [target] is set to a custom pack. */
    fun usesCustomPack(prefs: Prefs, target: IconCacheTarget): Boolean = when (target) {
        IconCacheTarget.HOME -> prefs.iconPackHome == Constants.IconPacks.Custom && prefs.customIconPackHome.isNotEmpty()
        IconCacheTarget.APP_LIST -> prefs.iconPackAppList == Constants.IconPacks.Custom && prefs.customIconPackAppList.isNotEmpty()
    }
}
