package com.github.codeworkscreativehub.mlauncher.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.annotation.RequiresApi
import android.os.Build
import android.graphics.RuntimeShader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.createBitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
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
        it.getDefaultSensor(Sensor.TYPE_GRAVITY)
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

    // Movement on top of tilt: twisting (gyro) spins the foil, moving the phone (linear accel) pushes the light
    private val gyro: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val linear: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private var spin = 0f          // radians, accumulated twist, eases back to 0
    private var pushX = 0f         // light offset from movement, in tilt-units (-1..1), springs back
    private var pushY = 0f
    private var pushVX = 0f
    private var pushVY = 0f
    private var energy = 0f        // 0..1, recent motion; boosts sparkle and glare
    private var lastNs = 0L

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
            gyro?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            linear?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            lastNs = 0L
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
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> { onGyro(event); return }
            Sensor.TYPE_LINEAR_ACCELERATION -> { onMove(event); return }
        }
        // Gravity in the device frame: x = left/right tilt, y = toward/away tilt. No gimbal flips when upright.
        val g = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
        if (g < 1f) return
        val roll = Math.toDegrees(asin((event.values[0] / g).coerceIn(-1f, 1f).toDouble())).toFloat()
        val pitch = Math.toDegrees(asin((event.values[1] / g).coerceIn(-1f, 1f).toDouble())).toFloat()
        // Re-centre on the holding angle very slowly (~20s), so a held tilt stays tilted
        if (baseX.isNaN()) {
            baseX = roll
            baseY = pitch
        } else {
            baseX += (roll - baseX) * 0.001f
            baseY += (pitch - baseY) * 0.001f
        }
        val tx = (roll - baseX).coerceIn(-TILT_RANGE, TILT_RANGE)
        val ty = (pitch - baseY).coerceIn(-TILT_RANGE, TILT_RANGE)
        tiltX += (tx - tiltX) * 0.25f
        tiltY += (ty - tiltY) * 0.25f
        if (abs(tiltX - lastDrawX) > 0.05f || abs(tiltY - lastDrawY) > 0.05f) {
            lastDrawX = tiltX
            lastDrawY = tiltY
            postInvalidateOnAnimation()
        }
    }

    private fun dt(event: SensorEvent): Float {
        val d = if (lastNs == 0L) 0.02f else ((event.timestamp - lastNs) / 1e9f).coerceIn(0.001f, 0.05f)
        lastNs = event.timestamp
        return d
    }

    private fun onGyro(event: SensorEvent) {
        val d = dt(event)
        val (wx, wy, wz) = Triple(event.values[0], event.values[1], event.values[2])
        // Twisting around the screen axis spins the rainbow; it drifts back so it never gets stuck
        spin = (spin + wz * d * 1.5f) * 0.985f
        val rate = sqrt(wx * wx + wy * wy + wz * wz)
        energy = maxOf(energy * 0.94f, (rate / 3f).coerceAtMost(1f))
        stepPush(d)
        postInvalidateOnAnimation()
    }

    private fun onMove(event: SensorEvent) {
        val d = dt(event)
        // Moving the phone shoves the light point the other way (inertia), then a spring brings it back
        pushVX -= event.values[0] * d * 0.9f
        pushVY += event.values[1] * d * 0.9f
        val a = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
        energy = maxOf(energy, (a / 6f).coerceAtMost(1f))
        stepPush(d)
        postInvalidateOnAnimation()
    }

    private fun stepPush(d: Float) {
        // Damped spring toward 0
        pushVX += (-pushX * 40f - pushVX * 9f) * d
        pushVY += (-pushY * 40f - pushVY * 9f) * d
        pushX = (pushX + pushVX * d).coerceIn(-1f, 1f)
        pushY = (pushY + pushVY * d).coerceIn(-1f, 1f)
        energy *= 0.98f
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
        val tx = (tiltX / TILT_RANGE + pushX).coerceIn(-1.2f, 1.2f)
        val ty = (tiltY / TILT_RANGE + pushY).coerceIn(-1.2f, 1.2f)
        val motion = maxOf(sqrt(tx * tx + ty * ty).coerceAtMost(1f), energy)
        // ~50% at rest, ~85% fully tilted, at the default intensity of 0.8
        val strength = ((0.22f + 0.5f * motion) * holoIntensity / 0.8f).coerceIn(0f, 1f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && foilShader != null) {
            drawFoilShader(canvas, art, ring, left, top, size, tx, ty, motion, strength)
        } else {
            drawFoilLegacy(canvas, bmp, art, ring, left, top, size, tx, ty, motion, strength)
        }
    }

    /**
     * Physically-motivated holo foil (API 33+): the foil's structure is fixed to the sticker,
     * like a real card. Tilt only changes the view/light angle, which changes the colour each
     * point diffracts, which etched lines glint, and which glitter flakes catch the light.
     * The specular sheen is the one thing that moves, because a reflection does move.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawFoilShader(
        canvas: Canvas, art: Bitmap, ring: Bitmap, left: Float, top: Float, size: Float,
        tx: Float, ty: Float, motion: Float, strength: Float,
    ) {
        val sh = foilShader as RuntimeShader
        sh.setFloatUniform("origin", left, top)
        sh.setFloatUniform("size", size)
        sh.setFloatUniform("tilt", tx, ty)
        sh.setFloatUniform("spin", spin)
        sh.setFloatUniform("strength", strength)
        foilPaint.shader = sh
        val foil = { canvas.drawRect(bounds, foilPaint) }
        foilLayer(canvas, art, left, top, screenPaint, 0.9f, foil)
        foilLayer(canvas, art, left, top, overlayPaint, 0.35f, foil)
        // Holo edge on the die-cut border, a little weaker
        foilLayer(canvas, ring, left, top, overPaint, 0.65f, foil)
    }

    private val foilPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val foilShader: Any? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try { RuntimeShader(FOIL_AGSL) } catch (e: Exception) {
                android.util.Log.e("HoloSticker", "foil shader failed to compile", e); null
            }
        } else null
    }

    private fun drawFoilLegacy(
        canvas: Canvas, bmp: Bitmap, art: Bitmap, ring: Bitmap, left: Float, top: Float, size: Float,
        tx: Float, ty: Float, motion: Float, strength: Float,
    ) {
        // Older Android: static etch/glitter (never translated), only colour + sheen respond to tilt
        val period = size / 2.4f
        rainbowPaint.shader = LinearGradient(left, top, left + period * 0.5f, top + period * 0.866f, rainbow, null, Shader.TileMode.REPEAT).also { g ->
            shaderMatrix.setTranslate((tx * 0.5f + ty * 0.866f) * period, 0f)
            g.setLocalMatrix(shaderMatrix)
        }
        foilLayer(canvas, art, left, top, screenPaint, 0.6f * strength) { canvas.drawRect(bounds, rainbowPaint) }
        foilLayer(canvas, ring, left, top, overPaint, 0.5f * strength) { canvas.drawRect(bounds, rainbowPaint) }
        shaderMatrix.reset()
        etch.setLocalMatrix(shaderMatrix)
        etchPaint.shader = etch
        foilLayer(canvas, bmp, left, top, overlayPaint, (0.1f + 0.35f * motion) * holoIntensity) { canvas.drawRect(bounds, etchPaint) }
        glitter.setLocalMatrix(shaderMatrix)
        glitterPaint.shader = glitter
        foilLayer(canvas, bmp, left, top, screenPaint, (0.05f + 0.5f * motion) * holoIntensity) { canvas.drawRect(bounds, glitterPaint) }
        val gx = left + size * (0.5f + tx * 0.7f)
        val gy = top + size * (0.5f - ty * 0.7f)
        glarePaint.shader = RadialGradient(gx, gy, size * 0.55f,
            intArrayOf(Color.WHITE, Color.argb(120, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        foilLayer(canvas, bmp, left, top, screenPaint, (0.1f + 0.5f * motion) * holoIntensity / 0.8f) { canvas.drawRect(bounds, glarePaint) }
    }

    private companion object {
        const val TILT_RANGE = 15f

        // Foil model: a diffraction foil whose grating direction is fixed per point (gently
        // warped across the card). The diffracted colour at a point depends on the angle
        // between the view/light direction and that grating, so tilting sweeps colours
        // through a pattern that itself never moves. Etched lines and glitter flakes are
        // fixed to the card and only brighten when the angle suits their facet.
        const val FOIL_AGSL = """
            uniform float2 origin;
            uniform float size;
            uniform float2 tilt;
            uniform float spin;
            uniform float strength;

            float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
            float noise(float2 p) {
                float2 i = floor(p); float2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
                return mix(mix(hash(i), hash(i + float2(1, 0)), f.x),
                           mix(hash(i + float2(0, 1)), hash(i + float2(1, 1)), f.x), f.y);
            }
            half3 spectrum(float h) {
                h = fract(h);
                return half3(clamp(abs(h * 6.0 - 3.0) - 1.0, 0.0, 1.0),
                             clamp(2.0 - abs(h * 6.0 - 2.0), 0.0, 1.0),
                             clamp(2.0 - abs(h * 6.0 - 4.0), 0.0, 1.0));
            }

            half4 main(float2 p) {
                float2 uv = (p - origin) / size;
                float c = cos(spin); float s = sin(spin);
                // View/light direction from tilt; twisting the phone rotates it
                float2 L = float2(c * tilt.x - s * tilt.y, s * tilt.x + c * tilt.y);
                float lAmt = clamp(length(L), 0.0, 1.0);

                // Grating direction fixed to the card, gently warped
                float ang = 1.05 + (noise(uv * 2.3) - 0.5) * 1.6;
                float2 g = float2(cos(ang), sin(ang));
                // Diffracted colour: structure term (fixed) + angle term (tilt)
                float phase = dot(uv, g) * 1.8 + dot(L, g) * 1.5 + noise(uv * 7.0) * 0.18;
                half3 rb = mix(spectrum(phase), half3(1.0), 0.18);

                // Specular sheen: the reflection of the light moves across the static foil
                float2 hc = float2(0.5, 0.5) + float2(L.x, -L.y) * 0.75;
                float2 dh = uv - hc;
                float sheen = exp(-dot(dh, dh) * 4.0);
                // Foil only lights up near the reflection, like real cards (dull away from it)
                float bright = 0.22 + 0.78 * sheen;

                // Etched lines fixed to the card; they glint when the angle crosses them
                float ln = abs(sin(dot(uv, float2(0.866, -0.5)) * 220.0));
                float etch = smoothstep(0.85, 1.0, ln) * (0.2 + 0.8 * clamp(abs(dot(L, float2(0.5, 0.866))), 0.0, 1.0));

                // Glitter flakes fixed in place, each with its own facet; a flake flashes when
                // the tilt matches its facet, then goes dark again. They never move.
                float2 cell = floor(uv * 48.0);
                float2 f = fract(uv * 48.0) - 0.5;
                float r = hash(cell);
                float2 facet = float2(hash(cell + 3.1), hash(cell + 7.7)) * 2.2 - 1.1;
                float match = clamp(1.0 - length(L - facet) * 1.6, 0.0, 1.0);
                float flake = step(0.86, r) * smoothstep(0.42, 0.05, length(f)) * pow(match, 3.0);

                float a = strength * bright;
                half3 col = rb * bright + half3(etch * 0.35 * bright) + half3(flake * 1.4);
                float alpha = clamp(a + flake * 0.9 * clamp(strength * 1.5, 0.0, 1.0), 0.0, 1.0);
                return half4(col * alpha, alpha);
            }
        """
    }
}
