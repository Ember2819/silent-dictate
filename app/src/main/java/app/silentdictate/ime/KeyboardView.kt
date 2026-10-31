package app.silentdictate.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlin.math.abs
import app.silentdictate.util.Palette

/**
 * Minimal monochrome QWERTY, fully custom-drawn.
 * Swipe left anywhere on the keys to hand over to the dictation panel.
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        /** Called with the committed glyph or control string. */
        fun onCommit(text: String)

        /** Called once per logical delete (batched by repeat). */
        fun onBackspace()

        fun onEnter()

        fun onSwipeLeft()

        /** Ask the host to re-evaluate caps from the editor state. */
        fun needCapsRefresh()
    }

    fun setListener(l: Listener?) {
        listener = l
    }

    fun showSwipeHint(show: Boolean) {
        swipeHintEnabled = show
        invalidate()
    }

    fun swipeHintDone() {
        if (swipeHintEnabled) {
            swipeHintEnabled = false
            invalidate()
        }
    }

    private enum class KeyType { TEXT, SHIFT, BACKSPACE, ENTER, SPACE, SYMBOL_TOGGLE }

    private class Key(val rect: RectF, val type: KeyType, val glyph: Char? = null)

    private var listener: Listener? = null

    private var keys: List<Key> = emptyList()
    private var spaceKey: Key? = null

    private var symbolMode = false
    private var shift = 0 // 0 off, 1 single-shot on, 2 locked
    private var autoCapsOn = false
    private val shiftActive: Boolean get() = shift > 0 || autoCapsOn

    private var pressedIndex = -1
    private var swipeHintEnabled = false

    private var measuredW = 0f
    private var keyH = 0f
    private var keyU = 0f

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.textPrimary
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.textFaint
        textAlign = Paint.Align.CENTER
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Palette.textPrimary
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val repeatHandler =
        android.os.Handler(android.os.Looper.getMainLooper())
    private var repeatingBackspace = false
    private val repeatRunnable = object : Runnable {
        override fun run() {
            repeatingBackspace = true
            listener?.onBackspace()
            repeatHandler.postDelayed(this, 42L)
        }
    }

    private var downX = 0f
    private var downY = 0f
    private var swipeFired = false

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        measuredW = w.toFloat()
        buildLayout(measuredW, h.toFloat())
    }

    private fun buildLayout(w: Float, h: Float) {
        val margin = dp(4f)
        val gap = dp(5f)
        val vPadTop = dp(7f)
        val rowGap = dp(6f)
        keyH = (h - vPadTop - 3 * rowGap) / 4f

        val inner = w - margin * 2
        // row 1: shift(1.45u) + gap + 9(u + gap each) + gap + backspace(1.45u)
        val uByShiftRow = (inner - 11 * gap) / 11.9f
        val uByTopRow = (inner - 9 * gap) / 10f
        keyU = minOf(uByShiftRow, uByTopRow)
        val u = keyU

        val list = ArrayList<Key>(38)

        fun centerX(rowWidthItems: Float): Float = margin + (inner - rowWidthItems) / 2f

        fun addGlyphRow(letters: String, row: Int, startX: Float) {
            val y = vPadTop + row * (keyH + rowGap)
            var x = startX
            letters.forEach { c ->
                list.add(Key(RectF(x, y, x + u, y + keyH), KeyType.TEXT, c))
                x += u + gap
            }
        }

        // Row 0
        val lettersTop = if (symbolMode) "1234567890" else "qwertyuiop"
        val row0W = 10 * u + 9 * gap
        addGlyphRow(lettersTop, 0, centerX(row0W))

        // Row 1: shift + 9 glyphs + backspace
        val y1 = vPadTop + (keyH + rowGap)
        run {
            var x = margin
            val specialsW = 1.45f * u
            list.add(Key(RectF(x, y1, x + specialsW, y1 + keyH), KeyType.SHIFT))
            x += specialsW + gap
            val lettersMid = if (symbolMode) "@#\$%&*()+" else "asdfghjkl"
            lettersMid.forEach { c ->
                list.add(Key(RectF(x, y1, x + u, y1 + keyH), KeyType.TEXT, c))
                x += u + gap
            }
            x += gap
            list.add(Key(RectF(x, y1, x + specialsW, y1 + keyH), KeyType.BACKSPACE))
        }

        // Row 2: 7 glyphs centered
        val midLetters = if (symbolMode) "'\";:!?/" else "zxcvbnm"
        run {
            val row2W = midLetters.length * u + (midLetters.length - 1) * gap
            addGlyphRow(midLetters, 2, centerX(row2W))
        }

        // Row 3: toggle · comma · space · period · enter
        val y3 = vPadTop + 3 * (keyH + rowGap)
        run {
            var x = margin
            val toggleW = 1.3f * u
            val punctW = u
            val enterW = 1.55f * u
            list.add(Key(RectF(x, y3, x + toggleW, y3 + keyH), KeyType.SYMBOL_TOGGLE))
            x += toggleW + gap
            list.add(Key(RectF(x, y3, x + punctW, y3 + keyH), KeyType.TEXT, ','))
            x += punctW + gap
            val spaceW = inner - ((x - margin) + punctW + gap + enterW)
            list.add(Key(RectF(x, y3, x + spaceW, y3 + keyH), KeyType.SPACE))
            spaceKey = list.last()
            x += spaceW + gap
            list.add(Key(RectF(x, y3, x + punctW, y3 + keyH), KeyType.TEXT, '.'))
            x += punctW + gap
            list.add(Key(RectF(x, y3, x + enterW, y3 + keyH), KeyType.ENTER))
        }

        keys = list
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Palette.bg)
        for ((i, key) in keys.withIndex()) {
            val r = key.rect
            val pressed = i == pressedIndex
            bgKey(canvas, r, key.type, pressed)
            when (key.type) {
                KeyType.TEXT -> drawGlyph(canvas, r, key.glyph ?: ' ')
                KeyType.SYMBOL_TOGGLE -> drawLabel(canvas, r, if (symbolMode) "abc" else "123")
                KeyType.SPACE -> {
                    fillPaint.color = Palette.textFaint
                    canvas.drawCircle(r.centerX(), r.centerY(), dp(1.6f), fillPaint)
                }
                KeyType.BACKSPACE -> drawBackspace(canvas, r)
                KeyType.SHIFT -> drawShift(canvas, r)
                KeyType.ENTER -> drawEnter(canvas, r)
            }
        }
        val sk = spaceKey ?: return
        if (swipeHintEnabled) {
            hintPaint.textSize = sk.rect.height() * 0.26f
            val cy = sk.rect.centerY() - (hintPaint.ascent() + hintPaint.descent()) / 2f
            canvas.drawText("‹  swipe left to read lips", sk.rect.centerX(), cy, hintPaint)
        }
    }

    private fun bgKey(canvas: Canvas, r: RectF, type: KeyType, pressed: Boolean) {
        keyPaint.color = when {
            pressed -> Palette.keyPressed
            type == KeyType.SPACE -> Palette.keyBgSpecial
            type == KeyType.TEXT -> Palette.keyBg
            else -> Palette.keyBgSpecial
        }
        val radius = r.height() * 0.20f
        canvas.drawRoundRect(r, radius, radius, keyPaint)
    }

    private fun drawGlyph(canvas: Canvas, r: RectF, c: Char) {
        textPaint.color = Palette.textPrimary
        textPaint.textSize = r.height() * 0.40f
        val out = if (c.isLetter() && shiftActive) c.uppercaseChar() else c
        val cy = r.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(out.toString(), r.centerX(), cy, textPaint)
    }

    private fun drawLabel(canvas: Canvas, r: RectF, label: String) {
        textPaint.color = Palette.textSecondary
        textPaint.textSize = r.height() * 0.26f
        val cy = r.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(label, r.centerX(), cy, textPaint)
    }

    private fun drawBackspace(canvas: Canvas, r: RectF) {
        strokePaint.color = Palette.textPrimary
        strokePaint.strokeWidth = r.height() * 0.07f
        val pad = r.width() * 0.32f
        val iy = r.height() * 0.18f
        canvas.drawLine(r.left + pad, r.top + iy, r.right - pad, r.bottom - iy, strokePaint)
        canvas.drawLine(r.right - pad, r.top + iy, r.left + pad, r.bottom - iy, strokePaint)
    }

    private fun drawShift(canvas: Canvas, r: RectF) {
        val locked = shift == 2
        strokePaint.color =
            if (shiftActive) Palette.textPrimary else Palette.textSecondary
        strokePaint.strokeWidth = r.height() * 0.065f
        val cx = r.centerX()
        val cy = r.centerY()
        val s = r.height() * 0.26f
        val path = Path()
        path.moveTo(cx, cy - s * 0.95f)
        path.lineTo(cx + s * 0.8f, cy + s * 0.05f)
        path.lineTo(cx + s * 0.36f, cy + s * 0.05f)
        path.lineTo(cx + s * 0.36f, cy + s * 0.85f)
        path.lineTo(cx - s * 0.36f, cy + s * 0.85f)
        path.lineTo(cx - s * 0.36f, cy + s * 0.05f)
        path.lineTo(cx - s * 0.8f, cy + s * 0.05f)
        path.close()
        if (locked) {
            fillPaint.color = strokePaint.color
            canvas.drawPath(path, fillPaint)
        } else {
            canvas.drawPath(path, strokePaint)
        }
    }

    private fun drawEnter(canvas: Canvas, r: RectF) {
        strokePaint.color = Palette.textPrimary
        strokePaint.strokeWidth = r.height() * 0.07f
        val pad = r.width() * 0.30f
        val midY = r.centerY()
        canvas.drawLine(r.left + pad, midY, r.right - pad, midY, strokePaint)
        canvas.drawLine(
            r.left + pad, midY,
            r.left + pad * 1.9f, midY - r.height() * 0.13f, strokePaint
        )
        canvas.drawLine(
            r.left + pad, midY,
            r.left + pad * 1.9f, midY + r.height() * 0.13f, strokePaint
        )
    }

    private fun keyAt(x: Float, y: Float): Int {
        for ((i, key) in keys.withIndex()) {
            if (key.rect.contains(x, y)) return i
        }
        return -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                swipeFired = false
                pressedIndex = keyAt(event.x, event.y)
                maybeStartBackspaceRepeat(pressedIndex)
                performHapticFeedbackSafe()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (swipeFired) return true
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx < -dp(88f) && abs(dx) > abs(dy) * 2f) {
                    swipeFired = true
                    clearPress()
                    performHapticFeedbackSafe()
                    listener?.onSwipeLeft()
                    return true
                }
                val idx = keyAt(event.x, event.y)
                if (idx != pressedIndex) {
                    stopBackspaceRepeat()
                    pressedIndex = idx
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                val wasRepeating = repeatingBackspace
                stopBackspaceRepeat()
                if (swipeFired) {
                    clearPress()
                    return true
                }
                val idx = keyAt(event.x, event.y)
                val pressed = pressedIndex
                clearPress()
                if (event.actionMasked == MotionEvent.ACTION_UP &&
                    !wasRepeating && idx >= 0 && idx == pressed
                ) {
                    performHapticFeedbackSafe()
                    fireKey(keys[idx])
                }
                return true
            }
            else -> return super.onTouchEvent(event)
        }
    }

    private fun clearPress() {
        pressedIndex = -1
        repeatingBackspace = false
        invalidate()
    }

    private fun maybeStartBackspaceRepeat(index: Int) {
        if (index < 0 || keys[index].type != KeyType.BACKSPACE) return
        repeatHandler.postDelayed(repeatRunnable, 350L)
    }

    private fun stopBackspaceRepeat() {
        repeatHandler.removeCallbacks(repeatRunnable)
        repeatingBackspace = false
    }

    private fun performHapticFeedbackSafe() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun fireKey(key: Key) {
        when (key.type) {
            KeyType.TEXT -> {
                val c = key.glyph ?: return
                val out = if (c.isLetter() && shiftActive) c.uppercaseChar() else c
                listener?.onCommit(out.toString())
                if (shift == 1) {
                    shift = 0
                    invalidate()
                }
                listener?.needCapsRefresh()
            }
            KeyType.SHIFT -> {
                shift = when (shift) {
                    0 -> 1
                    1 -> 2
                    else -> 0
                }
                invalidate()
            }
            KeyType.BACKSPACE -> listener?.onBackspace()
            KeyType.ENTER -> listener?.onEnter()
            KeyType.SYMBOL_TOGGLE -> {
                symbolMode = !symbolMode
                buildLayout(measuredW, height.toFloat())
                invalidate()
            }
            KeyType.SPACE -> listener?.onCommit(" ")
        }
    }

    /** Recompute accent capitals from the current editor state. */
    fun refreshCaps(ic: InputConnection?, info: EditorInfo?) {
        if (ic == null || info == null) {
            autoCapsOn = false
            invalidate()
            return
        }
        val classification = info.inputType and 0x0000000F
        val capsFlags = info.inputType and 0x0000F000
        val isTextField = classification == 0x00000001 // TYPE_CLASS_TEXT
        if (!isTextField || capsFlags == 0) {
            // fields without capitalization flags (URLs, codes, etc.)
            autoCapsOn = false
            invalidate()
            return
        }
        autoCapsOn = try {
            ic.getCursorCapsMode(info.inputType) != 0
        } catch (t: Throwable) {
            false
        }
        // caps style depends on shiftActive; redraw if visual changed is cheap
        invalidate()
    }
}
