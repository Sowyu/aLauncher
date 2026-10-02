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
    /** 0..1. Higher = less tilt needed for the full effect and a snappier response. */
    var holoSensitivity = 0.7f
        set(value) {
            field = value.coerceIn(0f, 1f)
            tiltRange = 26f - 21f * field          // 26° at 0%, ~11° at 70%, 5° at 100%
            follow = 0.08f + 0.32f * field         // smoothing per sensor tick
        }
    private var tiltRange = 11.3f
    private var follow = 0.32f

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

    /** Smoothed tilt relative to how the phone is being held, in degrees, clamped to ±[tiltRange]. */
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
            gyro?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            linear?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
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
        val tx = (roll - baseX).coerceIn(-tiltRange, tiltRange)
        val ty = (pitch - baseY).coerceIn(-tiltRange, tiltRange)
        tiltX += (tx - tiltX) * follow
        tiltY += (ty - tiltY) * follow
        if (abs(tiltX - lastDrawX) > 0.03f || abs(tiltY - lastDrawY) > 0.03f) {
            lastDrawX = tiltX
            lastDrawY = tiltY
            scheduleFrame()
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
        spin = (spin + wz * d * 0.3f) * 0.97f
        val rate = sqrt(wx * wx + wy * wy + wz * wz)
        energy = maxOf(energy * 0.94f, (rate / 3f).coerceAtMost(1f))
        stepPush(d)
        requestFoilFrame()
    }

    private fun onMove(event: SensorEvent) {
        val d = dt(event)
        // Moving the phone shoves the light point the other way (inertia), then a spring brings it back
        pushVX -= event.values[0] * d * 0.25f
        pushVY += event.values[1] * d * 0.25f
        val a = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
        energy = maxOf(energy, (a / 6f).coerceAtMost(1f))
        stepPush(d)
        requestFoilFrame()
    }

    private var lastSpin = 0f
    private var lastPushX = 0f
    private var lastPushY = 0f
    private var lastEnergy = 0f
    private var framePending = false

    /** One redraw per vsync at most, and only when something visible changed. */
    private fun requestFoilFrame() {
        if (abs(spin - lastSpin) < 0.003f && abs(pushX - lastPushX) < 0.004f &&
            abs(pushY - lastPushY) < 0.004f && abs(energy - lastEnergy) < 0.01f) return
        lastSpin = spin; lastPushX = pushX; lastPushY = pushY; lastEnergy = energy
        scheduleFrame()
    }

    private fun scheduleFrame() {
        if (framePending) return
        framePending = true
        postOnAnimation { framePending = false; invalidate() }
    }

    private fun stepPush(d: Float) {
        // Damped spring toward 0
        pushVX += (-pushX * 12f - pushVX * 6f) * d
        pushVY += (-pushY * 12f - pushVY * 6f) * d
        pushX = (pushX + pushVX * d).coerceIn(-1f, 1f)
        pushY = (pushY + pushVY * d).coerceIn(-1f, 1f)
        energy *= 0.995f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ------------------------------------------------------------------ drawing

    private fun ensureBase(): Bitmap? {
        val src = sprite ?: return null
        val size = width.coerceAtMost(height)
        if (size <= 0) return null
        if (base != null && builtFor == size) return base
        builtFor = size
        childFor = null

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
        val art = artMask
        val ring = borderMask
        val size = bmp.width.toFloat()
        bounds.set(left, top, left + size, top + size)
        if (!holoEnabled || holoIntensity <= 0f || art == null || ring == null) {
            canvas.drawBitmap(bmp, left, top, basePaint)
            return
        }
        val tx = (tiltX / tiltRange + pushX).coerceIn(-1.2f, 1.2f)
        val ty = (tiltY / tiltRange + pushY).coerceIn(-1.2f, 1.2f)
        val motion = maxOf(sqrt(tx * tx + ty * ty).coerceAtMost(1f), energy)
        // Slider: 0..80% scales up to the default look, 80..100% pushes up to ~1.8x (very strong)
        val scale = if (holoIntensity <= 0.8f) holoIntensity / 0.8f else 1f + (holoIntensity - 0.8f) * 4f
        val strength = ((0.7f + 0.15f * motion) * scale).coerceIn(0f, 2f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && foilShader != null) {
            drawFoilShader(canvas, bmp, art, ring, left, top, size, tx, ty, strength)
        } else {
            canvas.drawBitmap(bmp, left, top, basePaint)
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
        canvas: Canvas, bmp: Bitmap, art: Bitmap, ring: Bitmap, left: Float, top: Float, size: Float,
        tx: Float, ty: Float, strength: Float,
    ) {
        val sh = foilShader as RuntimeShader
        if (childFor !== bmp) {
            childFor = bmp
            fun child(b: Bitmap) = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                .apply { filterMode = BitmapShader.FILTER_MODE_NEAREST }
            sh.setInputShader("sticker", child(bmp))
            sh.setInputShader("artMask", child(art))
            sh.setInputShader("ringMask", child(ring))
        }
        sh.setFloatUniform("origin", left, top)
        sh.setFloatUniform("size", size)
        sh.setFloatUniform("tilt", tx, ty)
        sh.setFloatUniform("spin", spin)
        sh.setFloatUniform("strength", strength)
        foilPaint.shader = sh
        canvas.drawRect(bounds, foilPaint)
    }

    private var childFor: Bitmap? = null

    private val foilPaint = Paint()

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
        

        // Foil model: a diffraction foil whose grating direction is fixed per point (gently
        // warped across the card). The diffracted colour at a point depends on the angle
        // between the view/light direction and that grating, so tilting sweeps colours
        // through a pattern that itself never moves. Etched lines and glitter flakes are
        // fixed to the card and only brighten when the angle suits their facet.
        const val FOIL_AGSL = """
            uniform shader sticker;
            uniform shader artMask;
            uniform shader ringMask;
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
            // Purple and blue only: periwinkle -> blue -> violet -> lavender, looping
            half3 coolSpectrum(float h) {
                h = fract(h) * 4.0;
                half3 c0 = half3(0.45, 0.55, 1.0);   // periwinkle
                half3 c1 = half3(0.30, 0.42, 1.0);   // blue
                half3 c2 = half3(0.62, 0.38, 1.0);   // violet
                half3 c3 = half3(0.80, 0.62, 1.0);   // lavender
                if (h < 1.0) return mix(c0, c1, h);
                if (h < 2.0) return mix(c1, c2, h - 1.0);
                if (h < 3.0) return mix(c2, c3, h - 2.0);
                return mix(c3, c0, h - 3.0);
            }
            half3 spectrum(float h) {
                h = fract(h);
                return half3(clamp(abs(h * 6.0 - 3.0) - 1.0, 0.0, 1.0),
                             clamp(2.0 - abs(h * 6.0 - 2.0), 0.0, 1.0),
                             clamp(2.0 - abs(h * 6.0 - 4.0), 0.0, 1.0));
            }

            half4 main(float2 p) {
                float2 local = p - origin;
                half4 base = sticker.eval(local);
                if (base.a < 0.004) return half4(0);
                float artW = artMask.eval(local).a;
                float ringW = ringMask.eval(local).a;
                float2 uv = local / size;

                float c = cos(spin); float s = sin(spin);
                float2 L = float2(c * tilt.x - s * tilt.y, s * tilt.x + c * tilt.y);

                // Cracked-ice mosaic (like the reverse-holo card): irregular shards fixed to
                // the sticker, each reflecting a slightly different shade.
                float2 q = uv * 6.5;
                float2 qi = floor(q);
                // Voronoi: nearest and second-nearest seed give clean polygon shards with
                // straight borders (the border is where both distances are equal)
                float best = 9.0; float second = 9.0;
                float2 bestCell = float2(0.0); float2 bestPt = float2(0.0);
                for (int yy = -2; yy <= 2; yy++) {
                    for (int xx = -2; xx <= 2; xx++) {
                        float2 cell = qi + float2(float(xx), float(yy));
                        float2 pt = cell + 0.15 + float2(hash(cell), hash(cell + 5.3)) * 0.7;
                        float d = length(q - pt);
                        if (d < best) { second = best; best = d; bestCell = cell; bestPt = pt; }
                        else if (d < second) { second = d; }
                    }
                }
                // Everything below is per shard (from its seed), so each shard is one flat piece
                float shardHue = (hash(bestCell + 2.7) - 0.5) * 0.3;
                float shardLum = 0.92 + 0.25 * hash(bestCell + 9.1);
                float2 facet = float2(hash(bestCell + 1.3), hash(bestCell + 6.1)) * 2.0 - 1.0;
                float align = smoothstep(1.05, 0.25, length(L - facet * 0.8));
                float facing = 0.7 + 0.9 * align;
                float2 seedUv = bestPt / 6.5;
                float wash = dot(seedUv, normalize(float2(0.8, 1.0))) * 0.45 + dot(L, float2(0.55, 0.35)) * 0.9;
                half3 col = mix(coolSpectrum(wash + shardHue), half3(1.0), 0.3) * shardLum * facing;
                // Thin crisp seam between shards (anti-aliased over ~1px)
                float edgeDist = (second - best) * size / 6.5 * 0.5;
                float seam = 1.0 - smoothstep(0.4, 1.4, edgeDist);
                col = mix(col, col * 0.75, seam * 0.6);

                // Hairline striations, barely there
                float stria = 0.96 + 0.04 * sin(local.y * 1.6);
                col *= stria;

                // Tilt reveals the foil; faint at rest
                float reveal = 0.5 + 0.5 * smoothstep(0.05, 0.8, length(L));
                float k = clamp(strength * reveal * 1.15, 0.0, 1.0) * (artW * 0.9 + ringW * 0.75);
                half3 rgb = base.rgb / max(base.a, 0.001);
                half3 scr = 1.0 - (1.0 - rgb) * (1.0 - col * k);
                half3 outRgb = clamp(mix(scr, scr * col * 1.4, 0.35 * k), 0.0, 1.0);
                return half4(outRgb * base.a, base.a);
            }
        """
    }
}
