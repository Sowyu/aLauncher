package com.github.codeworkscreativehub.mlauncher.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import com.github.codeworkscreativehub.common.AppLogger
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** A small image (e.g. a pixel-art sprite) shown next to the home clock. Stored as PNG to keep transparency. */
object ClockSticker {
    private const val FILE_NAME = "clock_sticker.png"
    private const val MAX_SIDE = 1024

    fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun has(context: Context) = file(context).let { it.exists() && it.length() > 0 }

    /** Copy a picked/shared image into app storage. Pixel art is kept at its native size. Blocking. */
    fun import(context: Context, uri: Uri): Boolean = try {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_SIDE) {
                val s = MAX_SIDE.toFloat() / longest
                d.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
            }
        }
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        tmp.renameTo(file(context))
    } catch (t: Throwable) {
        AppLogger.e("ClockSticker", "Could not import sticker", t)
        false
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    /** The sticker bitmap, or null. Small enough to decode on the main thread. */
    fun load(context: Context): Bitmap? {
        val f = file(context)
        if (!f.exists()) return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (t: Throwable) {
            null
        }
    }

    fun stamp(context: Context): Long = file(context).let { if (it.exists()) it.lastModified() else 0L }
}
