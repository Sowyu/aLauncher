package com.github.codeworkscreativehub.mlauncher.helper

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.github.codeworkscreativehub.mlauncher.R
import com.github.codeworkscreativehub.common.AppLogger
import com.github.codeworkscreativehub.mlauncher.data.Constants
import com.github.codeworkscreativehub.mlauncher.data.Prefs
import java.io.File
import java.lang.ref.WeakReference

interface CustomFontView {
    fun applyFont(typeface: Typeface?)
}

object FontManager {

    private var cachedTypeface: Typeface? = null
    private val registeredViews = mutableListOf<WeakReference<CustomFontView>>()

    fun getTypeface(context: Context): Typeface? {
        if (cachedTypeface != null) return cachedTypeface

        return try {
            val prefs = Prefs(context)
            val fontFamily = prefs.fontFamily

            cachedTypeface = when (fontFamily) {
                Constants.FontFamily.Custom -> {
                    val file = File(context.filesDir, "CustomFont.ttf")
                    if (file.exists()) Typeface.createFromFile(file) else null
                }

                else -> fontFamily.getFont(context)
            } ?: bundledTypeface(context)

            cachedTypeface
        } catch (e: Exception) {
            AppLogger.e("FontManager", "Error loading typeface", e)
            bundledTypeface(context)
        }
    }

    /** Bundled Google Sans Flex (rounded); used whenever no other font resolves. */
    fun bundledTypeface(context: Context): Typeface? =
        try {
            ResourcesCompat.getFont(context, R.font.google_sans_flex)
        } catch (e: Exception) {
            AppLogger.e("FontManager", "Error loading bundled typeface", e)
            null
        }

    /** True when the active typeface is the bundled multi-weight family (Compose can use real weights). */
    fun isBundled(context: Context): Boolean {
        val family = Prefs(context).fontFamily
        return family == Constants.FontFamily.GoogleSansFlex ||
                (family == Constants.FontFamily.Custom && !File(context.filesDir, "CustomFont.ttf").exists())
    }

    fun register(view: CustomFontView) {
        registeredViews.add(WeakReference(view))
        view.applyFont(cachedTypeface)
    }

    fun reloadFont(context: Context) {
        cachedTypeface = null
        cachedTypeface = getTypeface(context)
        AppLogger.i("FontManager", "Reloading font and notifying views")

        val iterator = registeredViews.iterator()
        while (iterator.hasNext()) {
            val viewRef = iterator.next()
            val view = viewRef.get()
            if (view != null) {
                view.applyFont(cachedTypeface)
            } else {
                iterator.remove() // Clean up dead references
            }
        }
    }
}