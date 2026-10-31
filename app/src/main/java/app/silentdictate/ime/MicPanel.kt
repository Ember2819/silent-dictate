package app.silentdictate.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextUtils
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import app.silentdictate.util.Palette
import kotlin.math.abs

/**
 * The single-button dictation panel. Hold the circle to record your lips;
 * release to transcribe. Swipe right (or tap the back chip) to return.
 */
class MicPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onHoldStart()
        fun onHoldEnd()
        fun onHoldCancel()
        fun onBack()
    }

    fun setListener(l: Listener?) {
        listener = l
    }

    enum class State { IDLE, HOLDING, THINKING }

    fun setState(state: State, message: String?) {
        this.state = state
        this.message = message
        holdStartedAt = if (state == State.IDLE) 0L else SystemClock.elapsedRealtime()
        invalidate()
    }

    private var listener: Listener? = null
    private var state = State.IDLE
    private var message: String? = null

    private var holdStartedAt = 0L
    private var animating = false

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Palette.textPrimary
    }
    private val captionPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.textSecondary
        textAlign = Paint.Align.CENTER
    }

    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.textSecondary
        textAlign = Paint.Align.LEFT
    }

    private val tick = object : Runnable {
        override fun run() {
            if (state == State.IDLE) {
                animating = false
                return
            }
            invalidate()
            postInvalidateOnAnimation()
            postDelayed(this, 16L)
        }
    }

    private fun startTick() {
        if (animating) return
        animating = true
        postInvalidateOnAnimation()
        post(tick)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Palette.bg)

        val cx = width / 2f
        val cy = height * 0.34f
        val r = dp(46f)
        val elapsed = if (holdStartedAt > 0) SystemClock.elapsedRealtime() - holdStartedAt else 0L

        when (state) {
            State.IDLE -> drawIdle(canvas, cx, cy, r)
            State.HOLDING -> drawHolding(canvas, cx, cy, r, elapsed)
            State.THINKING -> drawThinking(canvas, cx, cy, r, elapsed)
        }
        if (state == State.HOLDING || state == State.THINKING) startTick()

        drawCaption(canvas, cx, cy, r)
        drawBackChip(canvas)
    }

    private fun drawIdle(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        circlePaint.color = Palette.keyBg
        canvas.drawCircle(cx, cy, r, circlePaint)
        ringPaint.strokeWidth = dp(2f)
        ringPaint.color = Palette.textPrimary
        canvas.drawCircle(cx, cy, r, ringPaint)
        drawMicIcon(canvas, cx, cy, r, Palette.textPrimary)
    }

    private fun drawHolding(canvas: Canvas, cx: Float, cy: Float, r: Float, elapsed: Long) {
        circlePaint.color = Palette.keyPressed
        canvas.drawCircle(cx, cy, r, circlePaint)
        ringPaint.strokeWidth = dp(2f)
        ringPaint.color = Palette.textPrimary
        canvas.drawCircle(cx, cy, r, ringPaint)

        val period = 1400f
        for (i in 0 until 2) {
            val p = (((elapsed + i * period.toLong() / 2L) % period.toLong()).toFloat() / period)
            ringPaint.strokeWidth = dp(1.6f)
            ringPaint.color = tintAlpha(Palette.textPrimary, (1 - p) * 0.45f)
            canvas.drawCircle(cx, cy, r * (1f + p * 0.9f), ringPaint)
        }
        drawMicIcon(canvas, cx, cy, r, Palette.textPrimary)
    }

    private fun drawThinking(canvas: Canvas, cx: Float, cy: Float, r: Float, elapsed: Long) {
        circlePaint.color = Palette.keyBg
        canvas.drawCircle(cx, cy, r, circlePaint)
        ringPaint.strokeWidth = dp(2f)
        ringPaint.color = Palette.keyBgSpecial
        canvas.drawCircle(cx, cy, r, ringPaint)

        val rot = (elapsed % 1400L).toFloat() / 1400f * 360f - 90f
        ringPaint.strokeWidth = dp(2.5f)
        ringPaint.color = Palette.textPrimary
        canvas.drawArc(
            RectF(cx - r, cy - r, cx + r, cy + r),
            rot, 70f, false, ringPaint
        )
        drawMicIcon(canvas, cx, cy, r, Palette.textSecondary)
    }

    private fun drawMicIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        iconPaint.color = color
        iconPaint.strokeWidth = r * 0.10f
        val bodyTop = cy - r * 0.32f
        val bodyBottom = cy + r * 0.16f
        val bodyHalfW = r * 0.155f
        canvas.drawRoundRect(
            RectF(cx - bodyHalfW, bodyTop, cx + bodyHalfW, bodyBottom),
            bodyHalfW, bodyHalfW, iconPaint
        )
        // lower arc bracket
        val arcR = r * 0.30f
        canvas.drawArc(
            RectF(cx - arcR, cy - arcR, cx + arcR, cy + arcR),
            0f, 180f, false, iconPaint
        )
        // stem
        canvas.drawLine(cx, cy + arcR, cx, cy + r * 0.46f, iconPaint)
    }

    private fun drawCaption(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        captionPaint.textSize = dp(14f)
        val color = when {
            state == State.HOLDING -> Palette.textPrimary
            state == State.THINKING -> Palette.textSecondary
            isError(message) -> Palette.danger
            else -> Palette.textSecondary
        }
        captionPaint.color = color
        val text = when {
            state == State.HOLDING -> "reading your lips…"
            state == State.THINKING -> "piecing the words together…"
            message != null -> message!!
            else -> "hold to dictate silently"
        }
        val y = cy + r + dp(38f)
        val ellipsized = TextUtils.ellipsize(
            text, captionPaint, width * 0.92f, TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(ellipsized, cx, y, captionPaint)

        if (state == State.IDLE) {
            captionPaint.color = Palette.textFaint
            captionPaint.textSize = dp(11f)
            canvas.drawText("‹ swipe right to type", cx, y + dp(18f), captionPaint)
        }
    }

    private fun isError(msg: String?): Boolean =
        msg != null && (msg.startsWith("Couldn") || msg.startsWith("No ") ||
            msg.startsWith("Camera") || msg.startsWith("Too short"))

    private fun drawBackChip(canvas: Canvas) {
        backPaint.textSize = dp(16f)
        backPaint.color = Palette.textSecondary
        canvas.drawText("‹ type", dp(18f), dp(30f), backPaint)
    }

    private fun insideBack(p: Float, q: Float): Boolean =
        p < dp(80f) && q < dp(44f)

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var holding = false
    private var downOnBack = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                downOnBack = insideBack(event.x, event.y)
                if (!downOnBack) {
                    holding = true
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    listener?.onHoldStart()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - touchDownX
                if (downOnBack && dx < -dp(28f)) {
                    downOnBack = false
                }
                if (dx > dp(64f) && abs(dx) > abs(event.y - touchDownY) * 2f) {
                    if (holding) {
                        holding = false
                        listener?.onHoldCancel()
                    }
                    listener?.onBack()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (holding) {
                    holding = false
                    listener?.onHoldCancel()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (downOnBack && insideBack(event.x, event.y) &&
                    abs(event.y - touchDownY) < dp(24f) && abs(event.x - touchDownX) < dp(24f)
                ) {
                    listener?.onBack()
                    return true
                }
                if (holding) {
                    holding = false
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    listener?.onHoldEnd()
                }
                return true
            }
            else -> return super.onTouchEvent(event)
        }
    }

    private fun tintAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return android.graphics.Color.argb(
            a,
            android.graphics.Color.red(color),
            android.graphics.Color.green(color),
            android.graphics.Color.blue(color),
        )
    }
}
