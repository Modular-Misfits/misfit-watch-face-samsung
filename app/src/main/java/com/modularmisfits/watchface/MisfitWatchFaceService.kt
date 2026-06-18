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

    private val cyberBlue = Color.rgb(86, 154, 190)
    private val teal = Color.rgb(70, 210, 190)
    private val red = Color.rgb(228, 64, 72)
    private val handOrange = Color.rgb(255, 140, 0)
    private val secondColor = Color.rgb(228, 64, 72)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val r = min(w, h) / 2f
        val ambient = renderParameters.drawMode == DrawMode.AMBIENT
        val now = System.currentTimeMillis()
        val showDetails = !ambient && now < detailsUntilMillis

        canvas.drawColor(Color.BLACK)
        if (!ambient) drawRadialBackground(canvas, cx, cy, r)
        else drawAmbientBackground(canvas, cx, cy, r)

        if (showDetails) {
            drawDetailsView(canvas, bounds, zonedDateTime)
        } else {
            drawClockView(canvas, bounds, zonedDateTime, ambient)
        }
    }

    override fun renderHighlightLayer(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime) {
        canvas.drawColor(renderParameters.highlightLayer!!.backgroundTint)
    }

    private fun drawClockView(canvas: Canvas, bounds: Rect, time: ZonedDateTime, ambient: Boolean) {
        val cx = bounds.exactCenterX(); val cy = bounds.exactCenterY(); val r = min(bounds.width(), bounds.height()) / 2f
        drawTicks(canvas, cx, cy, r, ambient)
        // Logo image is 783x724 (1.08:1), fully transparent background.
        // Scale so it fills most of the dial without clipping the circle edge.
        val logoW = if (ambient) r * 1.20f else r * 1.30f
        val logoH = logoW * (724f / 783f)
        val logoOffsetY = r * 0.05f
        val dst = RectF(cx - logoW / 2, cy - logoH / 2 - logoOffsetY, cx + logoW / 2, cy + logoH / 2 - logoOffsetY)
        paint.alpha = if (ambient) 92 else 235
        canvas.drawBitmap(if (ambient) ambientLogo else logo, null, dst, paint)
        paint.alpha = 255
        drawDateBubble(canvas, cx + r * .63f, cy, r * .145f, time, ambient)
        drawHands(canvas, cx, cy, r, time, ambient)
    }

    private fun drawDetailsView(canvas: Canvas, bounds: Rect, time: ZonedDateTime) {
        val cx = bounds.exactCenterX(); val cy = bounds.exactCenterY(); val r = min(bounds.width(), bounds.height()) / 2f
        textPaint.color = Color.WHITE; textPaint.textSize = r * .10f
        canvas.drawText("☁  72°", cx, cy - r * .70f, textPaint)
        textPaint.textSize = r * .055f; textPaint.color = Color.rgb(190, 210, 220)
        canvas.drawText("Weather complication", cx, cy - r * .58f, textPaint)
        val logoSize = r * 1.05f
        val dst = RectF(cx - logoSize / 2, cy - r*.37f, cx + logoSize / 2, cy - r*.37f + logoSize)
        paint.alpha = 225; canvas.drawBitmap(logo, null, dst, paint); paint.alpha = 255
        val panel = RectF(cx-r*.78f, cy+r*.43f, cx+r*.78f, cy+r*.78f)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = teal
        canvas.drawRoundRect(panel, 28f, 28f, paint)
        paint.style = Paint.Style.FILL
        textPaint.color = teal; textPaint.textSize = r*.045f; canvas.drawText("SAMSUNG HEALTH", cx, panel.top + r*.08f, textPaint)
        textPaint.color = Color.WHITE; textPaint.textSize = r*.07f
        canvas.drawText("8,742", cx-r*.38f, panel.centerY()+r*.06f, textPaint)
        canvas.drawText("68", cx, panel.centerY()+r*.06f, textPaint)
        canvas.drawText("563", cx+r*.38f, panel.centerY()+r*.06f, textPaint)
        textPaint.color = Color.rgb(180,195,205); textPaint.textSize = r*.04f
        canvas.drawText("Steps", cx-r*.38f, panel.bottom-r*.06f, textPaint)
        canvas.drawText("Active", cx, panel.bottom-r*.06f, textPaint)
        canvas.drawText("Cal", cx+r*.38f, panel.bottom-r*.06f, textPaint)
    }

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, r: Float, ambient: Boolean) {
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.SQUARE
        for (i in 0 until 60) {
            val a = Math.toRadians((i * 6 - 90).toDouble())
            val major = i % 5 == 0
            paint.strokeWidth = if (major) 4f else 1.4f
            paint.color = if (ambient) Color.rgb(75,75,75) else if (major) cyberBlue else Color.rgb(130,150,160)
            val inner = r * if (major) .79f else .85f
            val outer = r * .90f
            canvas.drawLine(cx + cos(a).toFloat()*inner, cy + sin(a).toFloat()*inner, cx + cos(a).toFloat()*outer, cy + sin(a).toFloat()*outer, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawHands(canvas: Canvas, cx: Float, cy: Float, r: Float, t: ZonedDateTime, ambient: Boolean) {
        val sec = t.second + t.nano / 1_000_000_000f
        val min = t.minute + sec/60f
        val hour = (t.hour % 12) + min/60f
        if (!ambient) {
            drawHand(canvas, cx, cy, r*.48f, hour*30f-90f, Color.WHITE, 13f)
            drawHand(canvas, cx, cy, r*.70f, min*6f-90f, Color.WHITE, 10f)
        }
        drawHand(canvas, cx, cy, r*.48f, hour*30f-90f, if (ambient) Color.GRAY else handOrange, 9f)
        drawHand(canvas, cx, cy, r*.70f, min*6f-90f, if (ambient) Color.GRAY else handOrange, 6f)
        if (!ambient) drawHand(canvas, cx, cy, r*.73f, sec*6f-90f, secondColor, 2f)
        paint.color = if (ambient) Color.GRAY else secondColor; canvas.drawCircle(cx, cy, r*.035f, paint)
    }

    private fun drawHand(canvas: Canvas, cx: Float, cy: Float, len: Float, deg: Float, color: Int, width: Float) {
        val a = Math.toRadians(deg.toDouble())
        paint.color = color; paint.strokeWidth = width; paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(cx, cy, cx + cos(a).toFloat()*len, cy + sin(a).toFloat()*len, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawDateBubble(canvas: Canvas, x: Float, y: Float, rad: Float, t: ZonedDateTime, ambient: Boolean) {
        paint.style = Paint.Style.FILL; paint.color = if (ambient) Color.rgb(18,18,18) else Color.rgb(15,40,45); canvas.drawCircle(x,y,rad,paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f; paint.color = if (ambient) Color.GRAY else teal; canvas.drawCircle(x,y,rad,paint)
        paint.style = Paint.Style.FILL; textPaint.color = Color.WHITE; textPaint.textSize = rad*.75f
        canvas.drawText(t.dayOfMonth.toString(), x, y + rad*.27f, textPaint)
    }

    private fun drawRadialBackground(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val shader = RadialGradient(cx, cy, r, Color.rgb(8,18,22), Color.BLACK, Shader.TileMode.CLAMP)
        paint.shader = shader; canvas.drawCircle(cx, cy, r, paint); paint.shader = null
    }

    private fun drawAmbientBackground(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = Color.rgb(35,35,35); canvas.drawCircle(cx, cy, r*.92f, paint); paint.style = Paint.Style.FILL
    }

    override fun onTapEvent(tapType: Int, tapEvent: TapEvent, complicationSlot: ComplicationSlot?) {
        if (tapType == TapType.UP) {
            detailsUntilMillis = System.currentTimeMillis() + 8000L
            invalidate()
        }
    }
}
