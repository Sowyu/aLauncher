package com.github.codeworkscreativehub.mlauncher.helper

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.createBitmap
import com.github.codeworkscreativehub.common.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.sqrt

/**
 * White-on-transparent glyphs for apps a monochrome icon pack doesn't cover, so every app
 * matches the pack. Source, in order: the app's themed (monochrome) icon layer on Android 13+,
 * the alpha of its adaptive foreground when that has transparency, or the icon with its dominant
 * background colour keyed out. The glyph is trimmed and fitted to [FILL] of the box like the
 * pack's own glyphs. Results are cached on disk per app version and pack, so they're stable.
 */
object MonochromeIcons {
    private const val SIZE = 192
    private const val FILL = 0.86f
    private const val FORMAT_VERSION = 1
    private const val TAG = "MonochromeIcons"

    private val memory = ConcurrentHashMap<String, Bitmap>()

    private fun dir(context: Context) = File(context.filesDir, "mono_icons").apply { mkdirs() }

    fun get(context: Context, packageName: String, activityClass: String?, pack: String): Drawable? {
        val version = try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else 0L
        } catch (_: Exception) {
            return null
        }
        val key = "$packageName|${activityClass.orEmpty()}|$version|$pack|v$FORMAT_VERSION"
        memory[key]?.let { return BitmapDrawable(context.resources, it) }

        val file = File(dir(context), sha1(key) + ".png")
        if (file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath)?.let {
                memory[key] = it
                return BitmapDrawable(context.resources, it)
            }
        }

        val glyph = try {
            generate(context, packageName, activityClass)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Could not make a glyph for $packageName", t)
            null
        } ?: return null

        try {
            val tmp = File(file.path + ".tmp")
            FileOutputStream(tmp).use { glyph.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(file)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Could not cache glyph for $packageName", t)
        }
        memory[key] = glyph
        return BitmapDrawable(context.resources, glyph)
    }

    /** Forget in-memory glyphs for a package (disk entries are keyed by version, so they age out). */
    fun forget(packageName: String?) {
        if (packageName == null) memory.clear() else memory.keys.removeAll { it.startsWith("$packageName|") }
    }

    private fun generate(context: Context, packageName: String, activityClass: String?): Bitmap? {
        val pm = context.packageManager
        val icon = activityClass?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { pm.getActivityIcon(ComponentName(packageName, it)) }.getOrNull() }
            ?: pm.getApplicationIcon(packageName)

        val mask: IntArray = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                icon is AdaptiveIconDrawable && icon.monochrome != null ->
                alphaOf(render(icon.monochrome!!))

            icon is AdaptiveIconDrawable && icon.foreground != null -> {
                val fg = render(icon.foreground)
                val alpha = alphaOf(fg)
                if (coverage(alpha) in 0.02f..0.80f) alpha else keyOut(render(icon))
            }

            else -> keyOut(render(icon))
        }
        if (coverage(mask) < 0.01f) return null
        return compose(mask)
    }

    private fun render(d: Drawable): Bitmap {
        val bmp = createBitmap(SIZE, SIZE)
        val c = Canvas(bmp)
        val old = d.copyBounds()
        d.setBounds(0, 0, SIZE, SIZE)
        d.draw(c)
        d.bounds = old
        return bmp
    }

    private fun alphaOf(bmp: Bitmap): IntArray {
        val px = IntArray(SIZE * SIZE)
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        for (i in px.indices) px[i] = px[i] ushr 24
        return px
    }

    private fun coverage(alpha: IntArray): Float = alpha.count { it > 96 }.toFloat() / alpha.size

    /** Find the most common opaque colour (the plate) and keep what differs from it. */
    private fun keyOut(bmp: Bitmap): IntArray {
        val px = IntArray(SIZE * SIZE)
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        val counts = HashMap<Int, Int>()
        for (p in px) {
            if (p ushr 24 < 200) continue
            val q = ((p shr 19 and 0x1F) shl 10) or ((p shr 11 and 0x1F) shl 5) or (p shr 3 and 0x1F)
            counts[q] = (counts[q] ?: 0) + 1
        }
        val dominant = counts.maxByOrNull { it.value }?.key ?: return alphaOf(bmp)
        val dr = ((dominant shr 10) and 0x1F) * 8 + 4
        val dg = ((dominant shr 5) and 0x1F) * 8 + 4
        val db = (dominant and 0x1F) * 8 + 4

        val out = IntArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            val a = p ushr 24
            if (a == 0) continue
            val r = (p shr 16 and 0xFF) - dr
            val g = (p shr 8 and 0xFF) - dg
            val b = (p and 0xFF) - db
            val d = sqrt((r * r + g * g + b * b).toFloat())
            // 0 near the plate colour, 1 once clearly different; smooth in between
            val t = ((d - 40f) / 70f).coerceIn(0f, 1f)
            out[i] = (t * t * (3 - 2 * t) * a).toInt()
        }
        // Nothing stood out (single-colour icon): fall back to its outline
        val cov = coverage(out)
        return if (cov < 0.02f || cov > 0.85f) alphaOf(bmp) else out
    }

    /** Trim to the glyph, fit it into FILL of the box, centred, white. */
    private fun compose(alpha: IntArray): Bitmap? {
        var minX = SIZE; var minY = SIZE; var maxX = -1; var maxY = -1
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            if (alpha[y * SIZE + x] > 24) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < minX || maxY < minY) return null
        val w = maxX - minX + 1
        val h = maxY - minY + 1
        val glyph = createBitmap(w, h)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            px[y * w + x] = (alpha[(y + minY) * SIZE + x + minX] shl 24) or 0xFFFFFF
        }
        glyph.setPixels(px, 0, w, 0, 0, w, h)

        val out = createBitmap(SIZE, SIZE)
        val scale = SIZE * FILL / max(w, h)
        val dw = w * scale
        val dh = h * scale
        val left = (SIZE - dw) / 2f
        val top = (SIZE - dh) / 2f
        Canvas(out).drawBitmap(glyph, null, RectF(left, top, left + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        glyph.recycle()
        return out
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
