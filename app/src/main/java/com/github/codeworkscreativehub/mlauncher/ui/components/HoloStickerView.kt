package com.github.codeworkscreativehub.mlauncher.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.createBitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A sprite drawn as a physical sticker: crisp pixel art, a white die-cut outline that follows
 * its shape, and an optional holographic foil (rainbow band, specular sweep, faint sparkle)
 * that moves with how the phone is tilted. The foil only listens to sensors while [setActive]
 * is true, so it costs nothing when home isn't on screen.
 */
class HoloStickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), SensorEventListener {

    private val density = resources.displayMetrics.density
    private val outlinePx = (3 * density).roundToInt()
    private val pad = outlinePx + (2 * density).roundToInt()

    /** Sprite + outline, sized to the view. */
    private var base: Bitmap? = null
    private var sprite: Bitmap? = null
    private var builtFor = 0

    var holoEnabled = true
        set(value) {
            field = value
            updateSensor()
            invalidate()
        }

    private val basePaint = Paint() // no filtering: the outline is pre-rendered at view size
    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val overlayLayer = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
        alpha = (0.45f * 255).roundToInt()
    }
    private val screenLayer = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val rainbowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparklePaint = Paint()
    private val shaderMatrix = Matrix()
    private val bounds = RectF()

    // ------------------------------------------------------------------ tilt

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sensorManager?.let {
        it.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: it.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: it.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }
    private var active = false
    private var listening = false
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)

    /** Smoothed tilt relative to how the phone is being held, in degrees, clamped to ±25. */
    private var tiltX = 0f
    private var tiltY = 0f
    private var baseX = Float.NaN
    private var baseY = Float.NaN
    private var lastDrawX = 0f
    private var lastDrawY = 0f

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setSprite(bitmap: Bitmap?) {
        sprite = bitmap
        builtFor = 0
        base = null
        invalidate()
    }

    /** Home is resumed, visible and not covered by the drawer. */
    fun setActive(value: Boolean) {
        active = value
        updateSensor()
    }

    private fun updateSensor() {
        val want = active && holoEnabled && sprite != null && isAttachedToWindow && sensor != null
        if (want == listening) return
        listening = want
        if (want) {
            baseX = Float.NaN
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        } else {
            sensorManager?.unregisterListener(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSensor()
    }

    override fun onDetachedFromWindow() {
        listening = false
        sensorManager?.unregisterListener(this)
        super.onDetachedFromWindow()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val pitch: Float
        val roll: Float
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
            pitch = Math.toDegrees(atan2(y.toDouble(), sqrt((x * x + z * z).toDouble()))).toFloat()
            roll = Math.toDegrees(atan2(-x.toDouble(), z.toDouble())).toFloat()
        } else {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            SensorManager.getOrientation(rotation, orientation)
            pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
            roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
        }
        // Follow the holding angle slowly, so the foil reacts to tilting rather than to posture
        if (baseX.isNaN()) {
            baseX = roll
            baseY = pitch
        } else {
            baseX += (roll - baseX) * 0.01f
            baseY += (pitch - baseY) * 0.01f
        }
        val tx = (roll - baseX).coerceIn(-25f, 25f)
        val ty = (pitch - baseY).coerceIn(-25f, 25f)
        tiltX += (tx - tiltX) * 0.18f
        tiltY += (ty - tiltY) * 0.18f
        if (abs(tiltX - lastDrawX) > 0.15f || abs(tiltY - lastDrawY) > 0.15f) {
            lastDrawX = tiltX
            lastDrawY = tiltY
            postInvalidateOnAnimation()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ------------------------------------------------------------------ drawing

    private fun ensureBase(): Bitmap? {
        val src = sprite ?: return null
        val size = width.coerceAtMost(height)
        if (size <= 0) return null
        if (base != null && builtFor == size) return base
        builtFor = size

        // 1. Sprite scaled nearest-neighbour into the box, leaving room for the outline
        val art = createBitmap(size, size)
        val inner = size - 2 * pad
        val s = inner.toFloat() / maxOf(src.width, src.height)
        val dw = src.width * s
        val dh = src.height * s
        val dst = RectF((size - dw) / 2f, (size - dh) / 2f, (size + dw) / 2f, (size + dh) / 2f)
        Canvas(art).drawBitmap(src, Rect(0, 0, src.width, src.height), dst, Paint()) // filter off

        // 2. Die-cut outline: the sprite's alpha stamped in a ring around itself, in white
        val out = createBitmap(size, size)
        val c = Canvas(out)
        val stamp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        }
        val steps = 24
        for (r in listOf(outlinePx * 0.5f, outlinePx.toFloat())) {
            for (i in 0 until steps) {
                val a = 2 * PI * i / steps
                c.drawBitmap(art, (cos(a) * r).toFloat(), (sin(a) * r).toFloat(), stamp)
            }
        }
        c.drawBitmap(art, 0f, 0f, Paint())
        art.recycle()
        base = out
        return out
    }

    private val rainbow = intArrayOf(
        0xFFFF6B9A.toInt(), 0xFFFFD36B.toInt(), 0xFF8BFFB0.toInt(), 0xFF6BE4FF.toInt(),
        0xFF9C8BFF.toInt(), 0xFFFF8BE8.toInt(), 0xFFFF6B9A.toInt(),
    )

    private val sparkle: BitmapShader by lazy {
        val n = 48
        val bmp = createBitmap(n, n)
        val rnd = Random(7)
        repeat(n * n / 18) {
            val v = rnd.nextInt(120, 255)
            bmp.setPixel(rnd.nextInt(n), rnd.nextInt(n), Color.argb(v, 255, 255, 255))
        }
        BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = ensureBase() ?: return
        val left = (width - bmp.width) / 2f
        val top = (height - bmp.height) / 2f
        canvas.drawBitmap(bmp, left, top, basePaint)
        if (!holoEnabled) return

        val size = bmp.width.toFloat()
        bounds.set(left, top, left + size, top + size)
        val tx = tiltX / 25f // -1..1
        val ty = tiltY / 25f

        // (a) Iridescent band at 45°, sliding with tilt, overlaid so the sprite keeps its colours
        val band = size * 0.9f
        rainbowPaint.shader = LinearGradient(0f, 0f, band, band, rainbow, null, Shader.TileMode.MIRROR)
        shaderMatrix.setTranslate(left + (tx + ty) * size * 0.6f, top + (ty - tx) * size * 0.6f)
        rainbowPaint.shader.setLocalMatrix(shaderMatrix)
        var save = canvas.saveLayer(bounds, overlayLayer)
        canvas.drawRect(bounds, rainbowPaint)
        canvas.drawBitmap(bmp, left, top, maskPaint)
        canvas.restoreToCount(save)

        // (b) Specular stripe + (c) sparkle, screened on top
        val sweep = (tx - ty) * 0.5f // -1..1
        val cx = left + size * (0.5f + sweep * 0.7f)
        val cy = top + size * (0.5f - sweep * 0.7f)
        val w = size * 0.18f
        specularPaint.shader = LinearGradient(
            cx - w, cy - w, cx + w, cy + w,
            intArrayOf(Color.TRANSPARENT, Color.argb(64, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        val sparkleAlpha = (40 + 60 * (abs(tx) + abs(ty)) / 2f).roundToInt().coerceIn(0, 255)
        sparklePaint.shader = sparkle
        sparklePaint.alpha = sparkleAlpha
        shaderMatrix.setTranslate(tx * 6 * density, ty * 6 * density)
        sparkle.setLocalMatrix(shaderMatrix)
        save = canvas.saveLayer(bounds, screenLayer)
        canvas.drawRect(bounds, specularPaint)
        canvas.drawRect(bounds, sparklePaint)
        canvas.drawBitmap(bmp, left, top, maskPaint)
        canvas.restoreToCount(save)
    }
}
