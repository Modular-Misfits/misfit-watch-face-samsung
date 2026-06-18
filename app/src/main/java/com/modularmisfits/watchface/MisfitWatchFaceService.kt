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

    // Logo dot positions as fractions of the 783×724 source image — measured by pixel scan
    // Red   (top):    center (386,  75) → (0.4930, 0.1036)
    // Teal  (right):  center (695, 316) → (0.8876, 0.4365)
    // Blue  (left):   center ( 79, 334) → (0.1009, 0.4613)
    // Blue  (bottom): center (383, 557) → (0.4891, 0.7693)
    private val DOT_RED_FX    = 0.4930f;  private val DOT_RED_FY    = 0.1036f
    private val DOT_TEAL_FX   = 0.8876f;  private val DOT_TEAL_FY   = 0.4613f  // matched to left dot Y
    private val DOT_LEFT_FX   = 0.1009f;  private val DOT_LEFT_FY   = 0.4613f
    private val DOT_BOTTOM_FX = 0.4866f;  private val DOT_BOTTOM_FY = 0.8600f  // pushed below M body

    // Colors
    private val cyberBlue  = Color.rgb(86, 154, 190)
    private val teal       = Color.rgb(70, 210, 190)
    private val logoRed    = Color.rgb(210, 70, 70)
    private val logoBlue   = Color.rgb(70, 110, 180)
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

    // Multi-tap tracking
    private var tapCount = 0
    private var lastTapMs = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private val MULTI_TAP_MS = 400L

    override fun onDestroy() {
        healthProvider.stop()
        super.onDestroy()
    }

    init {
        healthProvider.start()
    }

    // ── Render dispatch ──────────────────────────────────────────────────────

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

    // ── Clock view ───────────────────────────────────────────────────────────

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
            drawStressArc(canvas, cx, cy, r)
            drawStatsRow(canvas, cx, cy, r)
        }

        // Hands drawn BEFORE dots so dots float on top
        drawHands(canvas, cx, cy, r, time, ambient)

        // All four info dots drawn last — always on top of hands
        val dotR = logoW * 0.092f   // uniform radius sized to visually fill the logo dots

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

    // ── Generic info dot (number + label) ───────────────────────────────────

    private fun drawInfoDot(canvas: Canvas, x: Float, y: Float, rad: Float,
                             accentColor: Int, value: String, label: String, ambient: Boolean) {
        // Dark fill
        paint.style = Paint.Style.FILL
        paint.color = if (ambient) Color.rgb(15, 15, 15) else Color.rgb(8, 18, 30)
        canvas.drawCircle(x, y, rad, paint)

        // Accent ring
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = rad * 0.12f
        paint.color = if (ambient) Color.DKGRAY else accentColor
        canvas.drawCircle(x, y, rad, paint)
        paint.style = Paint.Style.FILL

        // Value text — vertically centered, shifted up slightly to leave room for label
        textPaint.textSize = rad * 0.80f
        textPaint.color = if (ambient) Color.GRAY else Color.WHITE
        val numY = y - (textPaint.ascent() + textPaint.descent()) / 2 - rad * 0.15f
        canvas.drawText(value, x, numY, textPaint)

        // Label text below value
        textPaint.textSize = rad * 0.34f
        textPaint.color = if (ambient) Color.DKGRAY else accentColor
        canvas.drawText(label, x, numY + rad * 0.52f, textPaint)
    }

    // ── Date dot (no label — date number fills the dot) ──────────────────────

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

    // ── Stress arc (left side) ────────────────────────────────────────────────

    private fun drawStressArc(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val arcR = r * 0.88f
        val oval = RectF(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        val startAngle = 150f
        val sweepTotal = -120f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.045f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(30, 45, 55)
        canvas.drawArc(oval, startAngle, sweepTotal, false, paint)

        val filled = (health.energyScore / 100f).coerceIn(0f, 1f) * abs(sweepTotal)
        paint.color = energyColor(health.energyScore)
        canvas.drawArc(oval, startAngle, -filled, false, paint)

        paint.strokeWidth = r * 0.018f
        paint.color = Color.rgb(60, 80, 90)
        for (i in 0..4) {
            val pct = i / 4f
            val tickAngle = Math.toRadians((startAngle - pct * abs(sweepTotal)).toDouble())
            val innerR = arcR - r * 0.055f
            val outerR = arcR + r * 0.055f
            canvas.drawLine(
                cx + cos(tickAngle).toFloat() * innerR, cy + sin(tickAngle).toFloat() * innerR,
                cx + cos(tickAngle).toFloat() * outerR, cy + sin(tickAngle).toFloat() * outerR,
                paint
            )
        }

        textPaint.textSize = r * 0.038f
        textPaint.color = Color.rgb(120, 160, 180)
        canvas.drawText("${health.energyScore}", cx - r * 0.82f, cy + r * 0.04f, textPaint)
        paint.style = Paint.Style.FILL
    }

    private fun energyColor(score: Int): Int = when {
        score >= 70 -> Color.rgb(70, 210, 130)
        score >= 40 -> Color.rgb(230, 180, 50)
        else        -> Color.rgb(210, 70, 70)
    }

    // ── Steps + active minutes row ────────────────────────────────────────────

    private fun drawStatsRow(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val baseY = cy + r * 0.60f
        textPaint.textSize = r * 0.072f; textPaint.color = Color.WHITE
        canvas.drawText(formatSteps(health.steps), cx - r * 0.30f, baseY, textPaint)
        canvas.drawText("${health.activeMinutes}m", cx + r * 0.30f, baseY, textPaint)
        textPaint.textSize = r * 0.036f; textPaint.color = Color.rgb(140, 170, 190)
        canvas.drawText("steps",  cx - r * 0.30f, baseY + r * 0.07f, textPaint)
        canvas.drawText("active", cx + r * 0.30f, baseY + r * 0.07f, textPaint)
    }

    // ── Tick marks ───────────────────────────────────────────────────────────

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

    // ── Hands ────────────────────────────────────────────────────────────────

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

    // ── Backgrounds ──────────────────────────────────────────────────────────

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

    // ── Tap / gesture handling ───────────────────────────────────────────────

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
            2 -> {
                tapCount = 0
                launchIntent(buildAlarmIntent())
            }
            3 -> {
                tapCount = 0
                launchIntent(buildWalletIntent())
            }
        }

        invalidate()
    }

    private fun handleSingleTap(x: Float, y: Float) {
        // Recompute dot positions from the current screen size
        val bounds = screenBounds
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val r  = min(bounds.width(), bounds.height()) / 2f
        val logoW = r * 1.3775f
        val logoH = logoW * (724f / 783f)
        val logoLeft = cx - logoW / 2
        val logoTop  = cy - logoH / 2 - r * 0.05f
        val dotR = logoW * 0.092f

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

        // Also handle stats row taps (steps / active minutes)
        val stepsX = cx - r * 0.30f
        val activeX = cx + r * 0.30f
        val rowY = cy + r * 0.60f
        val rowHitR = r * 0.18f
        when {
            hypot(x - stepsX, y - rowY) <= rowHitR -> launchIntent(buildStepsIntent())
            hypot(x - activeX, y - rowY) <= rowHitR -> launchIntent(buildWorkoutIntent())
        }
    }

    private fun launchIntent(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            // App not installed — silently ignore
        }
    }

    // Samsung Health deep-link intents
    private fun buildHeartRateIntent() = Intent(Intent.ACTION_VIEW,
        Uri.parse("shealth://com.sec.android.app.shealth/HeartRate"))

    private fun buildCaloriesIntent() = Intent(Intent.ACTION_VIEW,
        Uri.parse("shealth://com.sec.android.app.shealth/Dashboard"))

    private fun buildSleepIntent() = Intent(Intent.ACTION_VIEW,
        Uri.parse("shealth://com.sec.android.app.shealth/Sleep"))

    private fun buildStepsIntent() = Intent(Intent.ACTION_VIEW,
        Uri.parse("shealth://com.sec.android.app.shealth/StepCount"))

    private fun buildWorkoutIntent() = Intent(Intent.ACTION_VIEW,
        Uri.parse("shealth://com.sec.android.app.shealth/ExerciseSelection"))

    private fun buildCalendarIntent(): Intent {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_APP_CALENDAR)
        return intent
    }

    private fun buildAlarmIntent(): Intent {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_APP_CALENDAR)
        // Galaxy Watch alarm screen
        return Intent("com.samsung.android.alarmclock.action.SET_ALARM").apply {
            `package` = "com.sec.android.app.clockpackage"
        }
    }

    private fun buildWalletIntent() = Intent(Intent.ACTION_VIEW,
        Uri.parse("samsungpay://com.samsung.android.spay"))

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun formatSteps(steps: Int): String =
        if (steps >= 1000) "${steps / 1000},${"%03d".format(steps % 1000)}"
        else steps.toString()
}
