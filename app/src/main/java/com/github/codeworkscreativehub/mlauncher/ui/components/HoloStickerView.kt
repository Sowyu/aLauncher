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
 * its shape, and an optional Sword & Shield V-card style holo foil: saturated diagonal rainbow
 * streaks, etched lines, glitter and a wide glare band, all moving with how the phone is tilted. The foil only listens to sensors while [setActive]
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

    /** Just the sprite (no outline), and just the outline ring: foil is weighted differently on each. */
    private var artMask: Bitmap? = null
    private var borderMask: Bitmap? = null
    private var sprite: Bitmap? = null
    private var builtFor = 0

    /** 0..1, how strong the foil is. */
    var holoIntensity = 0.8f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var holoEnabled = true
        set(value) {
            field = value
            updateSensor()
            invalidate()
        }

    private val basePaint = Paint() // no filtering: the outline is pre-rendered at view size
    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val rainbowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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

    /** Smoothed tilt relative to how the phone is being held, in degrees, clamped to ±[TILT_RANGE]. */
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
        val tx = (roll - baseX).coerceIn(-TILT_RANGE, TILT_RANGE)
        val ty = (pitch - baseY).coerceIn(-TILT_RANGE, TILT_RANGE)
        tiltX += (tx - tiltX) * 0.18f
        tiltY += (ty - tiltY) * 0.18f
        if (abs(tiltX - lastDrawX) > 0.1f || abs(tiltY - lastDrawY) > 0.1f) {
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
        // Outline-only mask before the sprite goes on top
        val ring = out.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(ring).drawBitmap(art, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        c.drawBitmap(art, 0f, 0f, Paint())
        artMask?.recycle()
        borderMask?.recycle()
        artMask = art
        borderMask = ring
        base = out
        return out
    }

    // Saturated foil colours, cyan -> magenta -> yellow -> green, like Sword & Shield V cards
    private val rainbow = intArrayOf(
        0xFF00E5FF.toInt(), 0xFFFF2BD6.toInt(), 0xFFFFE600.toInt(), 0xFF2BFF6A.toInt(), 0xFF00E5FF.toInt(),
    )

    private val screenPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
    private val overlayPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY) }
    private val overPaint = Paint()
    private val ridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val glarePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val etchPaint = Paint()
    private val glitterPaint = Paint()

    /** Etched diagonal lines, the ridged texture of card foil. */
    private val etch: BitmapShader by lazy {
        val n = (24 * density).roundToInt().coerceAtLeast(16)
        val bmp = createBitmap(n, n)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = density * 0.8f; color = Color.WHITE }
        val step = n / 4f
        // Lines at ~60°, wrapping across the tile so the pattern repeats seamlessly
        for (k in -4..8) {
            val x = k * step
            p.alpha = if (k % 2 == 0) 200 else 110
            c.drawLine(x, n.toFloat(), x + n * 0.577f, 0f, p)
        }
        BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    /** Glitter specks that light up as the angle changes. */
    private val glitter: BitmapShader by lazy {
        val n = (40 * density).roundToInt().coerceAtLeast(24)
        val bmp = createBitmap(n, n)
        val rnd = Random(11)
        repeat(n * n / 40) {
            val v = rnd.nextInt(140, 256)
            bmp.setPixel(rnd.nextInt(n), rnd.nextInt(n), Color.argb(v, 255, 255, 255))
        }
        BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    /** Draw [content] into a layer masked to [mask], composited with [layerPaint] at [alpha] (0..1). */
    private inline fun foilLayer(
        canvas: Canvas, mask: Bitmap, left: Float, top: Float, layerPaint: Paint, alpha: Float,
        content: () -> Unit,
    ) {
        val a = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        if (a <= 0) return
        layerPaint.alpha = a
        val save = canvas.saveLayer(bounds, layerPaint)
        content()
        canvas.drawBitmap(mask, left, top, maskPaint)
        canvas.restoreToCount(save)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = ensureBase() ?: return
        val left = (width - bmp.width) / 2f
        val top = (height - bmp.height) / 2f
        canvas.drawBitmap(bmp, left, top, basePaint)
        val art = artMask ?: return
        val ring = borderMask ?: return
        if (!holoEnabled || holoIntensity <= 0f) return

        val size = bmp.width.toFloat()
        bounds.set(left, top, left + size, top + size)
        val tx = tiltX / TILT_RANGE // -1..1
        val ty = tiltY / TILT_RANGE
        val motion = sqrt(tx * tx + ty * ty).coerceAtMost(1f)
        // ~50% at rest, ~85% fully tilted, at the default intensity of 0.8
        val strength = ((0.5f + 0.35f * motion) * holoIntensity / 0.8f).coerceIn(0f, 1f)

        // Band direction ~60°; tilt slides the bands ~2 sticker widths over the full range
        val angle = Math.toRadians(60.0)
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        val period = size / 2.6f
        val shift = (tx * 0.8f + ty * 0.6f) * size * 2f

        // (1) Rainbow foil in ridged diagonal streaks
        rainbowPaint.shader = LinearGradient(0f, 0f, dx * period, dy * period, rainbow, null, Shader.TileMode.REPEAT)
        shaderMatrix.setTranslate(left + dx * shift, top + dy * shift)
        rainbowPaint.shader.setLocalMatrix(shaderMatrix)
        val ridge = period / 3.5f
        ridgePaint.shader = LinearGradient(
            0f, 0f, dx * ridge, dy * ridge,
            intArrayOf(Color.argb(255, 0, 0, 0), Color.argb(90, 0, 0, 0), Color.argb(255, 0, 0, 0)),
            null, Shader.TileMode.REPEAT
        ).also { g ->
            shaderMatrix.setTranslate(left + dx * shift * 1.3f, top + dy * shift * 1.3f)
            g.setLocalMatrix(shaderMatrix)
        }
        val foil = {
            canvas.drawRect(bounds, rainbowPaint)
            canvas.drawRect(bounds, ridgePaint)
        }
        foilLayer(canvas, art, left, top, screenPaint, 0.85f * strength, foil)
        foilLayer(canvas, art, left, top, overlayPaint, 0.55f * strength, foil)
        // Holo edge: the white outline takes the colour directly, a little weaker
        foilLayer(canvas, ring, left, top, overPaint, 0.6f * strength, foil)

        // (2) Etched lines in overlay, (3) glitter catching the light
        shaderMatrix.setTranslate(tx * 4 * density, ty * 4 * density)
        etch.setLocalMatrix(shaderMatrix)
        etchPaint.shader = etch
        foilLayer(canvas, bmp, left, top, overlayPaint, (0.35f + 0.4f * motion) * holoIntensity) {
            canvas.drawRect(bounds, etchPaint)
        }
        shaderMatrix.setTranslate(tx * 14 * density, -ty * 14 * density)
        glitter.setLocalMatrix(shaderMatrix)
        glitterPaint.shader = glitter
        foilLayer(canvas, bmp, left, top, screenPaint, (0.25f + 0.65f * motion) * holoIntensity) {
            canvas.drawRect(bounds, glitterPaint)
        }

        // (4) Wide glare band sweeping across, near white at its core when lined up
        // Rests off-centre (catching a corner); tilting sweeps it over the sprite and off again
        val sweep = (0.55f + (tx - ty)).coerceIn(-1.6f, 1.6f)
        val nx = -dy // glare runs along the bands, travels across them
        val ny = dx
        val cx = left + size / 2f + nx * sweep * size
        val cy = top + size / 2f + ny * sweep * size
        val half = size * 0.3f
        glarePaint.shader = LinearGradient(
            cx - nx * half, cy - ny * half, cx + nx * half, cy + ny * half,
            intArrayOf(Color.TRANSPARENT, Color.argb(130, 255, 255, 255), Color.WHITE, Color.argb(130, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
        foilLayer(canvas, bmp, left, top, screenPaint, (0.45f + 0.3f * motion) * holoIntensity / 0.8f) {
            canvas.drawRect(bounds, glarePaint)
        }
    }

    private companion object {
        const val TILT_RANGE = 15f
    }
}
