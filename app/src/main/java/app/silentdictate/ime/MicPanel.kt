package app.silentdictate.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import app.silentdictate.util.Palette
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The single-button dictation panel. Hold the circle to record your lips;
 * release to transcribe. Swipe right anywhere to return to the keys.
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

    enum class State { IDLE, HOLDING, THINKING }

    fun setListener(l: Listener?) {
        listener = l
    }

    fun setState(state: State, message: String?, isError: Boolean = false) {
        this.state = state
        this.message = message
        this.messageIsError = isError
        stateSince = SystemClock.elapsedRealtime()
        removeCallbacks(clearMessage)
        if (state == State.IDLE && message != null) postDelayed(clearMessage, 4000L)
        invalidate()
    }

    /** False when the camera can't see a face while holding. */
    fun setFaceVisible(visible: Boolean) {
        if (faceVisible != visible) {
            faceVisible = visible
            invalidate()
        }
    }

    private var listener: Listener? = null
    private var state = State.IDLE
    private var message: String? = null
    private var messageIsError = false
    private var faceVisible = true
    private var stateSince = 0L

    /** 0 idle → 1 held, eased per frame. */
    private var hold = 0f
    private var lastFrame = 0L

    private val clearMessage = Runnable {
        if (state == State.IDLE) {
            message = null
            invalidate()
        }
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val captionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    // ---------------------------------------------------------------- geometry

    private val radius get() = dp(46f)
    private val cx get() = width / 2f
    private val cy: Float
        get() {
            val top = dp(22f)
            val region = height - top - dp(6f)
            val block = radius * 2 + dp(66f)
            return top + (region - block) / 2f + radius
        }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Palette.bg)

        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(1L, 40L)
        lastFrame = now
        val target = if (state == State.HOLDING) 1f else 0f
        hold += (target - hold) * (1f - Math.exp(-dt / 55.0).toFloat())
        if (abs(target - hold) < 0.004f) hold = target
        val elapsed = now - stateSince

        drawPageDots(canvas)
        drawButton(canvas, elapsed)
        drawCaption(canvas)

        if (state != State.IDLE || hold != target) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private fun drawPageDots(canvas: Canvas) {
        val y = dp(11f)
        fillPaint.color = Palette.textFaint
        canvas.drawCircle(cx - dp(6f), y, dp(2.4f), fillPaint)
        fillPaint.color = Palette.textSecondary
        canvas.drawCircle(cx + dp(6f), y, dp(2.4f), fillPaint)
    }

    private fun drawButton(canvas: Canvas, elapsed: Long) {
        val x = cx
        val y = cy
        val r = radius * (1f + 0.07f * hold)

        // ripples while holding
        if (state == State.HOLDING) {
            val period = 1600f
            for (i in 0 until 2) {
                val p = ((elapsed + i * (period / 2f)) % period) / period
                ringPaint.strokeWidth = dp(1.4f)
                ringPaint.color = Palette.withAlpha(Palette.textPrimary, (1f - p) * 0.32f * hold)
                canvas.drawCircle(x, y, r * (1.05f + p * 0.75f), ringPaint)
            }
        }

        fillPaint.color = Palette.mix(Palette.keyBg, Palette.textPrimary, hold)
        canvas.drawCircle(x, y, r, fillPaint)

        ringPaint.strokeWidth = dp(1.5f)
        ringPaint.color = Palette.mix(Palette.keyPressed, Palette.textPrimary, hold)
        canvas.drawCircle(x, y, r - dp(0.75f), ringPaint)

        if (state == State.THINKING) {
            val sweep = 80f
            val start = (elapsed % 1100L) / 1100f * 360f - 90f
            ringPaint.strokeWidth = dp(2.2f)
            ringPaint.color = Palette.textPrimary
            rect.set(x - r + dp(0.75f), y - r + dp(0.75f), x + r - dp(0.75f), y + r - dp(0.75f))
            canvas.drawArc(rect, start, sweep, false, ringPaint)
        }

        val iconColor = when (state) {
            State.THINKING -> Palette.textSecondary
            else -> Palette.mix(Palette.textPrimary, Palette.bg, hold)
        }
        drawMicIcon(canvas, x, y, radius, iconColor)
    }

    private fun drawMicIcon(canvas: Canvas, x: Float, y: Float, r: Float, color: Int) {
        iconPaint.color = color
        iconPaint.strokeWidth = r * 0.085f
        val bodyHalfW = r * 0.16f
        val bodyTop = y - r * 0.42f
        val bodyBottom = y + r * 0.06f
        rect.set(x - bodyHalfW, bodyTop, x + bodyHalfW, bodyBottom)
        canvas.drawRoundRect(rect, bodyHalfW, bodyHalfW, iconPaint)
        // cradle
        val arcR = r * 0.31f
        val arcCy = y - r * 0.04f
        rect.set(x - arcR, arcCy - arcR, x + arcR, arcCy + arcR)
        canvas.drawArc(rect, 20f, 140f, false, iconPaint)
        // stem
        canvas.drawLine(x, arcCy + arcR, x, y + r * 0.50f, iconPaint)
        canvas.drawLine(x - r * 0.14f, y + r * 0.50f, x + r * 0.14f, y + r * 0.50f, iconPaint)
    }

    private fun drawCaption(canvas: Canvas) {
        val y = cy + radius + dp(48f)
        captionPaint.textSize = dp(14f)
        val text: String
        when {
            state == State.HOLDING -> {
                text = if (faceVisible) "reading your lips…" else "can’t see your face"
                captionPaint.color = if (faceVisible) Palette.textPrimary else Palette.danger
            }
            state == State.THINKING -> {
                text = message ?: "piecing the words together…"
                captionPaint.color = Palette.textSecondary
            }
            message != null -> {
                text = message!!
                captionPaint.color = if (messageIsError) Palette.danger else Palette.textSecondary
            }
            else -> {
                text = "hold to dictate silently"
                captionPaint.color = Palette.textSecondary
            }
        }
        canvas.drawText(
            TextUtils.ellipsize(text, captionPaint, width * 0.9f, TextUtils.TruncateAt.END).toString(),
            cx, y, captionPaint
        )
        if (state == State.IDLE && message == null) {
            captionPaint.textSize = dp(12f)
            captionPaint.color = Palette.textFaint
            canvas.drawText("swipe right to type  ›", cx, y + dp(22f), captionPaint)
        }
    }

    // ---------------------------------------------------------------- touch

    private var downX = 0f
    private var downY = 0f
    private var holding = false

    private fun onButton(x: Float, y: Float): Boolean =
        hypot(x - cx, y - cy) <= radius + dp(44f)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                if (onButton(event.x, event.y)) {
                    holding = true
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    listener?.onHoldStart()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                if (dx > dp(72f) && dx > abs(event.y - downY) * 1.6f) {
                    if (holding) {
                        holding = false
                        listener?.onHoldCancel()
                    }
                    downX = Float.MAX_VALUE // fire once
                    listener?.onBack()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (holding) {
                    holding = false
                    listener?.onHoldCancel()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (holding) {
                    holding = false
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    listener?.onHoldEnd()
                }
            }
            else -> return super.onTouchEvent(event)
        }
        return true
    }
}
