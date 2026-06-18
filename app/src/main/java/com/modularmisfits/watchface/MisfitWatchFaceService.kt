package com.modularmisfits.watchface

import android.content.Context
import android.graphics.*
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

    private var detailsUntilMillis = 0L
    private var health = HealthSnapshot()

    // Colors
    private val cyberBlue  = Color.rgb(86, 154, 190)
    private val teal       = Color.rgb(70, 210, 190)
    private val logoRed    = Color.rgb(210, 70, 70)   // matches top dot on logo
    private val logoBlue   = Color.rgb(70, 110, 180)  // matches left dot on logo
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
        val showDetails = !ambient && System.currentTimeMillis() < detailsUntilMillis

        canvas.drawColor(Color.BLACK)
        if (!ambient) drawRadialBackground(canvas, cx, cy, r)
        else          drawAmbientBackground(canvas, cx, cy, r)

        if (showDetails) drawDetailsView(canvas, bounds, zonedDateTime)
        else             drawClockView(canvas, bounds, zonedDateTime, ambient)
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

        // Logo — 783×724 art, nearly square; scale to ~65% of diameter
        val logoW = if (ambient) r * 1.20f else r * 1.30f
        val logoH = logoW * (724f / 783f)
        val logoOffsetY = r * 0.05f
        val dst = RectF(cx - logoW / 2, cy - logoH / 2 - logoOffsetY,
                        cx + logoW / 2, cy + logoH / 2 - logoOffsetY)
        paint.alpha = if (ambient) 92 else 235
        canvas.drawBitmap(if (ambient) ambientLogo else logo, null, dst, paint)
        paint.alpha = 255

        if (!ambient) {
            drawStressArc(canvas, cx, cy, r)
            drawHeartRateDot(canvas, cx, cy, r)
            drawStatsRow(canvas, cx, cy, r)
        }

        // Date inside the teal dot at 3 o'clock position
        val dotAngle = Math.toRadians(0.0)  // 3 o'clock = 0°
        val dotDist  = r * 0.78f
        val dotX     = cx + cos(dotAngle).toFloat() * dotDist
        val dotY     = cy + sin(dotAngle).toFloat() * dotDist
        val dotR     = r * 0.115f
        drawDateDot(canvas, dotX, dotY, dotR, time, ambient)

        drawHands(canvas, cx, cy, r, time, ambient)
    }

    // ── Details view (tap) ───────────────────────────────────────────────────

    private fun drawDetailsView(canvas: Canvas, bounds: Rect, time: ZonedDateTime) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val r  = min(bounds.width(), bounds.height()) / 2f

        val logoW = r * 1.10f
        val logoH = logoW * (724f / 783f)
        val dst = RectF(cx - logoW / 2, cy - logoH / 2 - r * 0.12f,
                        cx + logoW / 2, cy + logoH / 2 - r * 0.12f)
        paint.alpha = 200
        canvas.drawBitmap(logo, null, dst, paint)
        paint.alpha = 255

        // Stats panel below logo
        val panelTop = cy + r * 0.44f
        val panel = RectF(cx - r * 0.78f, panelTop, cx + r * 0.78f, panelTop + r * 0.42f)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f; paint.color = teal
        canvas.drawRoundRect(panel, 20f, 20f, paint)
        paint.style = Paint.Style.FILL

        textPaint.textSize = r * 0.042f; textPaint.color = teal
        canvas.drawText("SAMSUNG HEALTH", cx, panel.top + r * 0.075f, textPaint)

        textPaint.color = Color.WHITE; textPaint.textSize = r * 0.075f
        canvas.drawText(formatSteps(health.steps), cx - r * 0.38f, panel.centerY() + r * 0.04f, textPaint)
        canvas.drawText("${health.activeMinutes}m",           cx,             panel.centerY() + r * 0.04f, textPaint)
        canvas.drawText("${health.heartRate} bpm",            cx + r * 0.38f, panel.centerY() + r * 0.04f, textPaint)

        textPaint.color = Color.rgb(160, 185, 200); textPaint.textSize = r * 0.038f
        canvas.drawText("Steps",  cx - r * 0.38f, panel.bottom - r * 0.055f, textPaint)
        canvas.drawText("Active", cx,             panel.bottom - r * 0.055f, textPaint)
        canvas.drawText("HR",     cx + r * 0.38f, panel.bottom - r * 0.055f, textPaint)
    }

    // ── Stress arc (left side, 150°–210° sweep) ──────────────────────────────

    private fun drawStressArc(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val arcR = r * 0.88f
        val oval = RectF(cx - arcR, cy - arcR, cx + arcR, cy + arcR)

        // Arc spans 120° centered on the 9 o'clock position (180°)
        val startAngle = 150f
        val sweepTotal = -120f   // counter-clockwise from bottom-left to top-left

        // Background track
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.045f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(30, 45, 55)
        canvas.drawArc(oval, startAngle, sweepTotal, false, paint)

        // Filled portion — energyScore 0–100 maps to 0–120° of the arc
        val filled = (health.energyScore / 100f).coerceIn(0f, 1f) * abs(sweepTotal)
        val fillColor = energyColor(health.energyScore)
        paint.color = fillColor
        canvas.drawArc(oval, startAngle, -filled, false, paint)

        // Tick marks along the arc (5 ticks: 0, 25, 50, 75, 100%)
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

        // Energy label
        textPaint.textSize = r * 0.038f; textPaint.color = Color.rgb(120, 160, 180)
        canvas.drawText("${health.energyScore}", cx - r * 0.82f, cy + r * 0.04f, textPaint)

        paint.style = Paint.Style.FILL
    }

    private fun energyColor(score: Int): Int {
        return when {
            score >= 70 -> Color.rgb(70, 210, 130)   // green = high energy
            score >= 40 -> Color.rgb(230, 180, 50)   // amber = moderate
            else        -> Color.rgb(210, 70, 70)    // red = low/stressed
        }
    }

    // ── Heart rate — displayed inside the red top dot region ─────────────────

    private fun drawHeartRateDot(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // The logo's red dot sits at roughly 12 o'clock, ~38% up from center
        // Draw HR text just above center where the red dot lives
        val hrY = cy - r * 0.50f
        textPaint.textSize = r * 0.068f; textPaint.color = Color.WHITE
        val hrText = if (health.heartRate > 0) "${health.heartRate}" else "--"
        canvas.drawText(hrText, cx, hrY, textPaint)
        textPaint.textSize = r * 0.032f; textPaint.color = logoRed
        canvas.drawText("BPM", cx, hrY + r * 0.065f, textPaint)
    }

    // ── Steps + active minutes below the logo ────────────────────────────────

    private fun drawStatsRow(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val baseY = cy + r * 0.60f

        textPaint.textSize = r * 0.072f; textPaint.color = Color.WHITE
        canvas.drawText(formatSteps(health.steps), cx - r * 0.30f, baseY, textPaint)
        canvas.drawText("${health.activeMinutes}m", cx + r * 0.30f, baseY, textPaint)

        textPaint.textSize = r * 0.036f; textPaint.color = Color.rgb(140, 170, 190)
        canvas.drawText("steps", cx - r * 0.30f, baseY + r * 0.07f, textPaint)
        canvas.drawText("active", cx + r * 0.30f, baseY + r * 0.07f, textPaint)
    }

    // ── Date inside the teal 3-o'clock dot ───────────────────────────────────

    private fun drawDateDot(canvas: Canvas, x: Float, y: Float, rad: Float,
                             t: ZonedDateTime, ambient: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (ambient) Color.rgb(18, 18, 18) else Color.rgb(10, 35, 40)
        canvas.drawCircle(x, y, rad, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = if (ambient) Color.GRAY else teal
        canvas.drawCircle(x, y, rad, paint)

        paint.style = Paint.Style.FILL
        textPaint.color = Color.WHITE
        textPaint.textSize = rad * 0.80f
        canvas.drawText(t.dayOfMonth.toString(), x, y + rad * 0.28f, textPaint)
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
            // White outline behind orange hands
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
        canvas.drawLine(cx, cy,
                        cx + cos(a).toFloat() * len,
                        cy + sin(a).toFloat() * len, paint)
        paint.style = Paint.Style.FILL
    }

    // ── Backgrounds ──────────────────────────────────────────────────────────

    private fun drawRadialBackground(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val shader = RadialGradient(cx, cy, r,
                                    Color.rgb(8, 18, 22), Color.BLACK,
                                    Shader.TileMode.CLAMP)
        paint.shader = shader
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

    // ── Tap ──────────────────────────────────────────────────────────────────

    override fun onTapEvent(tapType: Int, tapEvent: TapEvent, complicationSlot: ComplicationSlot?) {
        if (tapType == TapType.UP) {
            detailsUntilMillis = System.currentTimeMillis() + 8000L
            invalidate()
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun formatSteps(steps: Int): String =
        if (steps >= 1000) "${steps / 1000},${"%03d".format(steps % 1000)}"
        else steps.toString()
}
