package com.github.codeworkscreativehub.mlauncher.helper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import com.github.codeworkscreativehub.common.AppLogger
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Background for the app drawer.
 *
 * Cross-window blur is off on some devices (ro.surface_flinger.supports_background_blur=0),
 * so the drawer draws its own blurred copy of the wallpaper. Source order:
 *  1. an image the user picked in settings (copied into app storage),
 *  2. the system wallpaper, if this app is allowed to read it (Android 13+ usually says no),
 *  3. nothing: the caller falls back to a solid scrim.
 *
 * The blurred bitmap is rendered once and cached in memory; the drawer shows it in a plain ImageView.
 */
object DrawerBackground {
    private const val TAG = "DrawerBackground"
    private const val FILE_NAME = "drawer_background.jpg"

    /** Blur is rendered at this fraction of screen size, then scaled up by the ImageView. */
    private const val RENDER_SCALE = 0.5f

    /**
     * RenderEffect turns a radius r into a Gaussian sigma of about 0.58r, so the raw setting
     * reads weak. Scale it so the default 40 gives a sigma of ~35px at full screen size:
     * clearly frosted on a 1080x2392 photo, not just softened.
     */
    private const val STRENGTH = 1.5f

    enum class Source { CustomImage, SystemWallpaper, None }

    private data class Key(val w: Int, val h: Int, val radius: Int, val stamp: Long)

    @Volatile
    private var cacheKey: Key? = null

    @Volatile
    private var cacheBitmap: Bitmap? = null

    @Volatile
    private var wallpaperReadable: Boolean? = null

    fun customImageFile(context: Context) = File(context.filesDir, FILE_NAME)

    fun hasCustomImage(context: Context) = customImageFile(context).let { it.exists() && it.length() > 0 }

    /** Which source the drawer will use right now. */
    fun currentSource(context: Context): Source = when {
        hasCustomImage(context) -> Source.CustomImage
        canReadWallpaper(context) -> Source.SystemWallpaper
        else -> Source.None
    }

    fun invalidate() {
        cacheKey = null
        cacheBitmap = null
    }

    /** Last rendered bitmap if it matches the request, without doing any work. */
    fun cached(context: Context, radius: Int): Bitmap? {
        val (w, h) = screenSize(context)
        return if (cacheKey == Key(w, h, radius, sourceStamp(context))) cacheBitmap else null
    }

    /**
     * Copy a user-picked image into app storage, scaled down to the screen size.
     * Blocking; call off the main thread.
     */
    fun importImage(context: Context, uri: Uri): Boolean {
        return try {
            val (w, h) = screenSize(context)
            val target = max(w, h)
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > target * 1.5f) {
                    val s = target * 1.5f / longest
                    decoder.setTargetSize(
                        (info.size.width * s).roundToInt().coerceAtLeast(1),
                        (info.size.height * s).roundToInt().coerceAtLeast(1)
                    )
                }
            }
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bitmap.recycle()
            val ok = tmp.renameTo(customImageFile(context))
            invalidate()
            ok
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Could not import drawer background", t)
            false
        }
    }

    /** Small preview of the saved image for the settings row, or null. Blocking. */
    fun loadThumbnail(context: Context, sizePx: Int): Bitmap? {
        val file = customImageFile(context)
        if (!file.exists()) return null
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = sizePx.toFloat() / minOf(info.size.width, info.size.height)
                if (s < 1f) {
                    d.setTargetSize(
                        (info.size.width * s).roundToInt().coerceAtLeast(1),
                        (info.size.height * s).roundToInt().coerceAtLeast(1)
                    )
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun clearCustomImage(context: Context) {
        customImageFile(context).delete()
        invalidate()
    }

    /**
     * Blurred, centre-cropped background sized for the current screen, or null when there is no source.
     * Blocking; call off the main thread.
     */
    fun loadBlurred(context: Context, radius: Int): Bitmap? {
        val (w, h) = screenSize(context)
        val key = Key(w, h, radius, sourceStamp(context))
        if (key == cacheKey) cacheBitmap?.let { return it }

        val result = try {
            val src = loadSourceDrawable(context) ?: return null
            val rw = (w * RENDER_SCALE).roundToInt().coerceAtLeast(1)
            val rh = (h * RENDER_SCALE).roundToInt().coerceAtLeast(1)
            // Radius is in screen px; the bitmap is RENDER_SCALE of the screen, so scale it with it
            val r = radius * STRENGTH * RENDER_SCALE
            // Blur an oversized crop and keep its middle: edge pixels then come from inside the
            // photo instead of being smeared outward (a bright lamp at the edge used to glow).
            val pad = if (r < 1f) 0 else (r * 2f).roundToInt()
            val oversized = centerCrop(src, rw + 2 * pad, rh + 2 * pad)
            val blurred = when {
                r < 1f -> oversized
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    runCatching { blurOnGpu(oversized, r) }.getOrNull() ?: cheapBlur(oversized, r)

                else -> cheapBlur(oversized, r)
            }
            if (blurred !== oversized) oversized.recycle()
            finish(blurred, pad, rw, rh).also { blurred.recycle() }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Could not build drawer background", t)
            null
        }

        cacheKey = key
        cacheBitmap = result
        return result
    }

    /** Crop the padding off and darken the bottom ~15% so nothing glows under the search pill. */
    private fun finish(blurred: Bitmap, pad: Int, w: Int, h: Int): Bitmap {
        val out = createBitmap(w, h)
        val canvas = Canvas(out)
        canvas.drawBitmap(blurred, -pad.toFloat(), -pad.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        val top = h * 0.85f
        val shade = Paint().apply {
            shader = LinearGradient(0f, top, 0f, h.toFloat(), 0x00000000, 0x59000000, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, top, w.toFloat(), h.toFloat(), shade)
        return out
    }

    private fun sourceStamp(context: Context): Long {
        val f = customImageFile(context)
        return if (f.exists()) f.lastModified() else -1L
    }

    private fun canReadWallpaper(context: Context): Boolean {
        wallpaperReadable?.let { return it }
        val readable = readWallpaper(context) != null
        wallpaperReadable = readable
        return readable
    }

    private fun readWallpaper(context: Context): Drawable? = try {
        // Needs READ_WALLPAPER_INTERNAL on Android 13+, which third-party apps can't hold.
        WallpaperManager.getInstance(context).drawable
    } catch (_: SecurityException) {
        null
    } catch (t: Throwable) {
        AppLogger.e(TAG, "Wallpaper read failed", t)
        null
    }

    private fun loadSourceDrawable(context: Context): Drawable? {
        val file = customImageFile(context)
        if (file.exists()) {
            try {
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, _, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                return BitmapDrawable(context.resources, bmp)
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Saved drawer background is unreadable", t)
            }
        }
        return readWallpaper(context).also { wallpaperReadable = it != null }
    }

    private fun centerCrop(src: Drawable, w: Int, h: Int): Bitmap {
        val out = createBitmap(w, h)
        val canvas = Canvas(out)
        val sw = src.intrinsicWidth.takeIf { it > 0 } ?: w
        val sh = src.intrinsicHeight.takeIf { it > 0 } ?: h
        val scale = max(w.toFloat() / sw, h.toFloat() / sh)
        val dw = sw * scale
        val dh = sh * scale
        val left = (w - dw) / 2f
        val top = (h - dh) / 2f
        if (src is BitmapDrawable && src.bitmap != null) {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            canvas.drawBitmap(src.bitmap, null, RectF(left, top, left + dw, top + dh), paint)
        } else {
            src.setBounds(left.roundToInt(), top.roundToInt(), (left + dw).roundToInt(), (top + dh).roundToInt())
            src.draw(canvas)
        }
        return out
    }

    /** Render the blur once on the GPU and read it back as a normal bitmap. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun blurOnGpu(src: Bitmap, radius: Float): Bitmap? {
        val w = src.width
        val h = src.height
        val reader = ImageReader.newInstance(
            w, h, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val node = RenderNode("drawerBlur")
        val renderer = android.graphics.HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            node.setPosition(0, 0, w, h)
            node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
            val canvas = node.beginRecording()
            canvas.drawBitmap(src, 0f, 0f, null)
            node.endRecording()
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage() ?: return null
            image.use {
                val buffer = it.hardwareBuffer ?: return null
                buffer.use { hb ->
                    val hw = Bitmap.wrapHardwareBuffer(hb, null) ?: return null
                    val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                    hw.recycle()
                    return soft
                }
            }
        } finally {
            renderer.destroy()
            node.discardDisplayList()
            reader.close()
        }
    }

    /** Fallback: shrink hard and let bilinear upscaling smear it. Good enough below Android 12. */
    private fun cheapBlur(src: Bitmap, radius: Float): Bitmap {
        val factor = (radius / 2f).coerceIn(2f, 24f)
        val sw = (src.width / factor).roundToInt().coerceAtLeast(1)
        val sh = (src.height / factor).roundToInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        return Bitmap.createScaledBitmap(small, src.width, src.height, true).also {
            if (small !== it) small.recycle()
        }
    }

    private fun screenSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = context.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }
}
