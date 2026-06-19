package com.modularmisfits.watchface

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.view.SurfaceHolder
import androidx.wear.watchface.*
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyleSchema
import java.time.ZonedDateTime
import kotlin.math.*

class MisfitWatchFaceService : WatchFaceService() {
    override fun createUserStyleSchema() = UserStyleSchema(emptyList())

    override fun createComplicationSlotsManager(currentUserStyleRepository: CurrentUserStyleRepository) =
        ComplicationSlotsManager(emptyList(), currentUserStyleRepository)

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository
    ): WatchFace {
        val renderer = MisfitRenderer(surfaceHolder, currentUserStyleRepository, watchState, applicationContext)
        return WatchFace(WatchFaceType.ANALOG, renderer).setTapListener(renderer)
    }
}

class MisfitRenderer(
    surfaceHolder: SurfaceHolder,
    currentUserStyleRepository: CurrentUserStyleRepository,
    watchState: WatchState,
    private val context: Context
) : Renderer.CanvasRenderer(
    surfaceHolder,
    currentUserStyleRepository,
    watchState,
    CanvasType.HARDWARE,
    16L
), WatchFace.TapListener {

    private val logo by lazy { BitmapFactory.decodeResource(context.resources, R.drawable.misfit_logo_clean) }
    private val ambientLogo by lazy { BitmapFactory.decodeResource(context.resources, R.drawable.misfit_logo_ambient) }

    private var health = HealthSnapshot()
    private var weather = WeatherSnapshot()

    // Logo dot positions as fractions of the 783×724 source image
    private val DOT_RED_FX    = 0.4930f;  private val DOT_RED_FY    = 0.1036f
    private val DOT_TEAL_FX   = 0.8876f;  private val DOT_TEAL_FY   = 0.4613f
    private val DOT_LEFT_FX   = 0.1009f;  private val DOT_LEFT_FY   = 0.4613f
    private val DOT_BOTTOM_FX = 0.4866f;  private val DOT_BOTTOM_FY = 0.8600f

    // Colors
    private val cyberBlue  = Color.rgb(86, 154, 190)
    private val teal       = Color.rgb(70, 210, 190)
    private val logoRed    = Color.rgb(210, 70, 70)
    private val logoBlue   = Color.rgb(70, 110, 180)
    private val stepGreen  = Color.rgb(70, 210, 130)
    private val activeOrange = Color.rgb(255, 160, 0)
    private val handOrange = Color.rgb(255, 140, 0)
    private val secondRed  = Color.rgb(228, 64, 72)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val healthProvider = HealthDataProvider(context) { snapshot ->
        health = snapshot
        invalidate()
    }
    private val weatherProvider = WeatherProvider(context) { snapshot ->
        weather = snapshot
        invalidate()
    }

    // Arc hit regions (set during draw, used in tap detection)
    private var leftArcCx = 0f; private var leftArcCy = 0f; private var leftArcR = 0f
    private var rightArcCx = 0f; private var rightArcCy = 0f; private var rightArcR = 0f

    // Multi-tap tracking
    private var tapCount = 0
    private var lastTapMs = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private val MULTI_TAP_MS = 400L

    init {
        healthProvider.start()
        weatherProvider.start()
    }

    override fun onDestroy() {
        healthProvider.stop()
        weatherProvider.stop()
        super.onDestroy()
    }

    // ── Render dispatch ──────────────────────────────────────────────────────────

    override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val r  = min(bounds.width(), bounds.height()) / 2f
        val ambient = renderParameters.drawMode == DrawMode.AMBIENT

        canvas.drawColor(Color.BLACK)
        if (!ambient) drawRadialBackground(canvas, cx, cy, r)
        else          drawAmbientBackground(canvas, cx, cy, r)

        drawClockView(canvas, bounds, zonedDateTime, ambient)
    }

    override fun renderHighlightLayer(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime) {
        canvas.drawColor(renderParameters.highlightLayer!!.backgroundTint)
    }

    // ── Clock view ───────────────────────────────────────────────────────────────

    private fun drawClockView(canvas: Canvas, bounds: Rect, time: ZonedDateTime, ambient: Boolean) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val r  = min(bounds.width(), bounds.height()) / 2f

        drawTicks(canvas, cx, cy, r, ambient)

        // Logo
        val logoW = if (ambient) r * 1.235f else r * 1.3775f
        val logoH = logoW * (724f / 783f)
        val logoLeft = cx - logoW / 2
        val logoTop  = cy - logoH / 2 - r * 0.05f
        val dst = RectF(logoLeft, logoTop, logoLeft + logoW, logoTop + logoH)
        paint.alpha = if (ambient) 92 else 235
        canvas.drawBitmap(if (ambient) ambientLogo else logo, null, dst, paint)
        paint.alpha = 255

        if (!ambient) {
            drawStepsArc(canvas, cx, cy, r)
            drawActiveMinutesArc(canvas, cx, cy, r)
            drawWeatherRow(canvas, cx, cy, r)
        }

        drawHands(canvas, cx, cy, r, time, ambient)

        // Info dots — always on top
        val dotR = logoW * 0.092f

        // Red dot (top) — heart rate
        val hrX = logoLeft + DOT_RED_FX * logoW
        val hrY = logoTop  + DOT_RED_FY * logoH
        drawInfoDot(canvas, hrX, hrY, dotR, logoRed,
            if (health.heartRate > 0) "${health.heartRate}" else "--", "BPM", ambient)

        // Teal dot (right) — date
        val dateX = logoLeft + DOT_TEAL_FX * logoW
        val dateY = logoTop  + DOT_TEAL_FY * logoH
        drawDateDot(canvas, dateX, dateY, dotR, time, ambient)

        if (!ambient) {
            // Blue dot (left) — calories
            val calX = logoLeft + DOT_LEFT_FX * logoW
            val calY = logoTop  + DOT_LEFT_FY * logoH
            val calText = if (health.calories > 0) "${health.calories}" else "--"
            drawInfoDot(canvas, calX, calY, dotR, logoBlue, calText, "cal", ambient)

            // Blue dot (bottom) — sleep score
            val sleepX = logoLeft + DOT_BOTTOM_FX * logoW
            val sleepY = logoTop  + DOT_BOTTOM_FY * logoH
            val sleepText = if (health.sleepScore > 0) "${health.sleepScore}" else "--"
            drawInfoDot(canvas, sleepX, sleepY, dotR, logoBlue, sleepText, "sleep", ambient)
        }
    }

    // ── Left arc: steps toward 10k goal ─────────────────────────────────────────

    private fun drawStepsArc(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val arcR = r * 0.88f
        val startAngle = 150f
        val sweepTotal = -120f   // sweeps counter-clockwise from lower-left

        // Cache hit region for tap detection
        leftArcCx = cx; leftArcCy = cy; leftArcR = arcR

        val oval = RectF(cx - arcR, cy - arcR, cx + arcR, cy + arcR)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.045f
        paint.strokeCap = Paint.Cap.ROUND

        // Track background
        paint.color = Color.rgb(30, 45, 35)
        canvas.drawArc(oval, startAngle, sweepTotal, false, paint)

        // Filled portion
        val pct = (health.steps / STEP_GOAL.toFloat()).coerceIn(0f, 1f)
        val filled = pct * abs(sweepTotal)
        paint.color = stepColor(pct)
        canvas.drawArc(oval, startAngle, -filled, false, paint)

        // Tick marks at 25%, 50%, 75%, 100%
        paint.strokeWidth = r * 0.018f
        paint.color = Color.rgb(50, 80, 60)
        for (i in 0..4) {
            val tickPct = i / 4f
            val tickAngle = Math.toRadians((startAngle - tickPct * abs(sweepTotal)).toDouble())
            val innerR = arcR - r * 0.055f
            val outerR = arcR + r * 0.055f
            canvas.drawLine(
                cx + cos(tickAngle).toFloat() * innerR, cy + sin(tickAngle).toFloat() * innerR,
                cx + cos(tickAngle).toFloat() * outerR, cy + sin(tickAngle).toFloat() * outerR,
                paint
            )
        }

        // Label
        textPaint.textSize = r * 0.038f
        textPaint.color = Color.rgb(120, 200, 140)
        canvas.drawText("steps", cx - r * 0.82f, cy + r * 0.04f, textPaint)
        paint.style = Paint.Style.FILL
    }

    private fun stepColor(pct: Float) = when {
        pct >= 1.0f -> stepGreen
        pct >= 0.5f -> Color.rgb(140, 210, 80)
        pct >= 0.25f -> Color.rgb(230, 180, 50)
        else -> Color.rgb(180, 100, 50)
    }

    // ── Right arc: active minutes toward 60-min goal ─────────────────────────────

    private fun drawActiveMinutesArc(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val arcR = r * 0.88f
        // Mirror of left arc — starts at 30°, sweeps clockwise to 150°
        val startAngle = 30f
        val sweepTotal = 120f

        rightArcCx = cx; rightArcCy = cy; rightArcR = arcR

        val oval = RectF(cx - arcR, cy - arcR, cx + arcR, cy + arcR)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.045f
        paint.strokeCap = Paint.Cap.ROUND

        paint.color = Color.rgb(45, 35, 20)
        canvas.drawArc(oval, startAngle, sweepTotal, false, paint)

        val pct = (health.activeMinutes / ACTIVE_MIN_GOAL.toFloat()).coerceIn(0f, 1f)
        val filled = pct * abs(sweepTotal)
        paint.color = activeMinuteColor(pct)
        canvas.drawArc(oval, startAngle, filled, false, paint)

        paint.strokeWidth = r * 0.018f
        paint.color = Color.rgb(80, 60, 30)
        for (i in 0..4) {
            val tickPct = i / 4f
            val tickAngle = Math.toRadians((startAngle + tickPct * abs(sweepTotal)).toDouble())
            val innerR = arcR - r * 0.055f
            val outerR = arcR + r * 0.055f
            canvas.drawLine(
                cx + cos(tickAngle).toFloat() * innerR, cy + sin(tickAngle).toFloat() * innerR,
                cx + cos(tickAngle).toFloat() * outerR, cy + sin(tickAngle).toFloat() * outerR,
                paint
            )
        }

        textPaint.textSize = r * 0.038f
        textPaint.color = Color.rgb(200, 160, 80)
        canvas.drawText("active", cx + r * 0.82f, cy + r * 0.04f, textPaint)
        paint.style = Paint.Style.FILL
    }

    private fun activeMinuteColor(pct: Float) = when {
        pct >= 1.0f -> activeOrange
        pct >= 0.5f -> Color.rgb(230, 140, 40)
        else -> Color.rgb(180, 100, 40)
    }

    // ── Weather row (bottom center, replaces steps+active text) ──────────────────

    private fun drawWeatherRow(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val baseY = cy + r * 0.60f

        if (!weather.hasData) {
            textPaint.textSize = r * 0.052f
            textPaint.color = Color.rgb(80, 100, 110)
            canvas.drawText("-- °C", cx, baseY, textPaint)
            return
        }

        val tempStr = "${weather.tempC.toInt()}°C"
        val symbol = weather.conditionSymbol()
        val hiloStr = "H:${weather.highC.toInt()}° L:${weather.lowC.toInt()}°"

        // Condition symbol (emoji-like)
        textPaint.textSize = r * 0.085f
        textPaint.color = Color.WHITE
        canvas.drawText(symbol, cx - r * 0.28f, baseY, textPaint)

        // Temperature
        textPaint.textSize = r * 0.082f
        textPaint.color = Color.WHITE
        canvas.drawText(tempStr, cx + r * 0.15f, baseY, textPaint)

        // High/Low below
        textPaint.textSize = r * 0.036f
        textPaint.color = Color.rgb(140, 170, 190)
        canvas.drawText(hiloStr, cx, baseY + r * 0.09f, textPaint)
    }

    // ── Generic info dot ─────────────────────────────────────────────────────────

    private fun drawInfoDot(canvas: Canvas, x: Float, y: Float, rad: Float,
                             accentColor: Int, value: String, label: String, ambient: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (ambient) Color.rgb(15, 15, 15) else Color.rgb(8, 18, 30)
        canvas.drawCircle(x, y, rad, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = rad * 0.12f
        paint.color = if (ambient) Color.DKGRAY else accentColor
        canvas.drawCircle(x, y, rad, paint)
        paint.style = Paint.Style.FILL

        textPaint.textSize = rad * 0.80f
        textPaint.color = if (ambient) Color.GRAY else Color.WHITE
        val numY = y - (textPaint.ascent() + textPaint.descent()) / 2 - rad * 0.15f
        canvas.drawText(value, x, numY, textPaint)

        textPaint.textSize = rad * 0.34f
        textPaint.color = if (ambient) Color.DKGRAY else accentColor
        canvas.drawText(label, x, numY + rad * 0.52f, textPaint)
    }

    // ── Date dot ─────────────────────────────────────────────────────────────────

    private fun drawDateDot(canvas: Canvas, x: Float, y: Float, rad: Float,
                             t: ZonedDateTime, ambient: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (ambient) Color.rgb(15, 15, 15) else Color.rgb(8, 30, 28)
        canvas.drawCircle(x, y, rad, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = rad * 0.12f
        paint.color = if (ambient) Color.DKGRAY else teal
        canvas.drawCircle(x, y, rad, paint)
        paint.style = Paint.Style.FILL

        textPaint.textSize = rad * 0.95f
        textPaint.color = if (ambient) Color.GRAY else Color.WHITE
        val dateY = y - (textPaint.ascent() + textPaint.descent()) / 2
        canvas.drawText(t.dayOfMonth.toString(), x, dateY, textPaint)
    }

    // ── Tick marks ───────────────────────────────────────────────────────────────

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, r: Float, ambient: Boolean) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.SQUARE
        for (i in 0 until 60) {
            val a = Math.toRadians((i * 6 - 90).toDouble())
            val major = i % 5 == 0
            paint.strokeWidth = if (major) 4f else 1.4f
            paint.color = when {
                ambient -> Color.rgb(75, 75, 75)
                major   -> cyberBlue
                else    -> Color.rgb(130, 150, 160)
            }
            val inner = r * if (major) 0.79f else 0.85f
            val outer = r * 0.90f
            canvas.drawLine(
                cx + cos(a).toFloat() * inner, cy + sin(a).toFloat() * inner,
                cx + cos(a).toFloat() * outer, cy + sin(a).toFloat() * outer,
                paint
            )
        }
        paint.style = Paint.Style.FILL
    }

    // ── Hands ────────────────────────────────────────────────────────────────────

    private fun drawHands(canvas: Canvas, cx: Float, cy: Float, r: Float,
                           t: ZonedDateTime, ambient: Boolean) {
        val sec  = t.second + t.nano / 1_000_000_000f
        val min  = t.minute + sec / 60f
        val hour = (t.hour % 12) + min / 60f

        if (!ambient) {
            drawHand(canvas, cx, cy, r * 0.48f, hour * 30f - 90f, Color.WHITE, 13f)
            drawHand(canvas, cx, cy, r * 0.70f, min  *  6f - 90f, Color.WHITE, 10f)
        }
        drawHand(canvas, cx, cy, r * 0.48f, hour * 30f - 90f,
                 if (ambient) Color.GRAY else handOrange, 9f)
        drawHand(canvas, cx, cy, r * 0.70f, min  *  6f - 90f,
                 if (ambient) Color.GRAY else handOrange, 6f)
        if (!ambient)
            drawHand(canvas, cx, cy, r * 0.73f, sec * 6f - 90f, secondRed, 2f)

        paint.color = if (ambient) Color.GRAY else secondRed
        canvas.drawCircle(cx, cy, r * 0.035f, paint)
    }

    private fun drawHand(canvas: Canvas, cx: Float, cy: Float, len: Float,
                          deg: Float, color: Int, width: Float) {
        val a = Math.toRadians(deg.toDouble())
        paint.color = color
        paint.strokeWidth = width
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(cx, cy, cx + cos(a).toFloat() * len, cy + sin(a).toFloat() * len, paint)
        paint.style = Paint.Style.FILL
    }

    // ── Backgrounds ──────────────────────────────────────────────────────────────

    private fun drawRadialBackground(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.shader = RadialGradient(cx, cy, r,
            Color.rgb(8, 18, 22), Color.BLACK, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
    }

    private fun drawAmbientBackground(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.rgb(35, 35, 35)
        canvas.drawCircle(cx, cy, r * 0.92f, paint)
        paint.style = Paint.Style.FILL
    }

    // ── Tap / gesture handling ────────────────────────────────────────────────────

    override fun onTapEvent(tapType: Int, tapEvent: TapEvent, complicationSlot: ComplicationSlot?) {
        if (tapType != TapType.UP) return

        val now = System.currentTimeMillis()
        val x = tapEvent.xPos.toFloat()
        val y = tapEvent.yPos.toFloat()

        if (now - lastTapMs < MULTI_TAP_MS) {
            tapCount++
        } else {
            tapCount = 1
            lastTapX = x
            lastTapY = y
        }
        lastTapMs = now

        when (tapCount) {
            1 -> handleSingleTap(x, y)
            2 -> { tapCount = 0; launchIntent(buildAlarmIntent()) }
            3 -> { tapCount = 0; launchIntent(buildWalletIntent()) }
        }
        invalidate()
    }

    private fun handleSingleTap(x: Float, y: Float) {
        val bounds = screenBounds
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val r  = min(bounds.width(), bounds.height()) / 2f
        val logoW = r * 1.3775f
        val logoH = logoW * (724f / 783f)
        val logoLeft = cx - logoW / 2
        val logoTop  = cy - logoH / 2 - r * 0.05f
        val dotR = logoW * 0.092f

        // Left arc (steps) — annular hit zone at arcR ± strokeWidth
        val arcR = r * 0.88f
        val distFromCenter = hypot(x - cx, y - cy)
        val leftArcAngle = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
            .let { if (it < 0) it + 360f else it }
        // Left arc spans 150° → 30° going counter-clockwise = 150° → 270° clockwise dead zone
        // Easier: arc covers 150°–270° in standard (clockwise from right). Check angle in [150,270].
        val inLeftArcAngle = leftArcAngle in 150f..270f
        if (inLeftArcAngle && distFromCenter in (arcR - r * 0.08f)..(arcR + r * 0.08f)) {
            launchIntent(buildStepsIntent())
            return
        }

        // Info dots
        data class Dot(val fx: Float, val fy: Float, val intent: Intent?)
        val dots = listOf(
            Dot(DOT_RED_FX,    DOT_RED_FY,    buildHeartRateIntent()),
            Dot(DOT_TEAL_FX,   DOT_TEAL_FY,   buildCalendarIntent()),
            Dot(DOT_LEFT_FX,   DOT_LEFT_FY,   buildCaloriesIntent()),
            Dot(DOT_BOTTOM_FX, DOT_BOTTOM_FY, buildSleepIntent()),
        )
        for (dot in dots) {
            val dx = logoLeft + dot.fx * logoW
            val dy = logoTop  + dot.fy * logoH
            if (hypot(x - dx, y - dy) <= dotR * 1.4f) {
                dot.intent?.let { launchIntent(it) }
                return
            }
        }
    }

    private fun launchIntent(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun buildHeartRateIntent() = Intent(Intent.ACTION_VIEW,
        Uri.parse("shealth://com.sec.android.app.shealth/HeartRate"))
    private fun buildCaloriesIntent() = Intent(Intent.ACTION_VIEW,
        Uri.parse("shealth://com.sec.android.app.shealth/Dashboard"))
    private fun buildSleepIntent() = Intent(Intent.ACTION_VIEW,
        Uri.parse("shealth://com.sec.android.app.shealth/Sleep"))
    private fun buildStepsIntent() = Intent(Intent.ACTION_VIEW,
        Uri.parse("shealth://com.sec.android.app.shealth/StepCount"))
    private fun buildCalendarIntent(): Intent =
        Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_CALENDAR) }
    private fun buildAlarmIntent() = Intent("com.samsung.android.alarmclock.action.SET_ALARM").apply {
        `package` = "com.sec.android.app.clockpackage"
    }
    private fun buildWalletIntent() = Intent(Intent.ACTION_VIEW,
        Uri.parse("samsungpay://com.samsung.android.spay"))
}
