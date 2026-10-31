package app.silentdictate.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.SparseIntArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import app.silentdictate.util.Palette
import kotlin.math.abs

/**
 * Minimal monochrome QWERTY, fully custom-drawn.
 * Swipe left anywhere on the keys to hand over to the dictation panel.
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onCommit(text: String)
        fun onBackspace()
        fun onEnter()
        fun onSwipeLeft()
        fun onSpaceLongPress()

        /** Ask the host to re-evaluate auto-caps from the editor state. */
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

    /** Dictation is turned off in password fields. */
    fun setDictationEnabled(enabled: Boolean) {
        dictationEnabled = enabled
        invalidate()
    }

    /** True: enter performs an editor action (→). False: it inserts a newline (↵). */
    fun setEnterIsAction(action: Boolean) {
        if (enterIsAction != action) {
            enterIsAction = action
            invalidate()
        }
    }

    fun setSymbols(on: Boolean) {
        if (symbolMode == on) return
        symbolMode = on
        symbolPage = 1
        if (width > 0) buildLayout(width.toFloat(), height.toFloat())
        invalidate()
    }

    fun resetShift() {
        shift = 0
        invalidate()
    }

    /** Recompute auto-capitalisation from the current editor state. */
    fun refreshCaps(ic: InputConnection?, info: EditorInfo?) {
        val wanted = if (ic == null || info == null) {
            false
        } else {
            val isText = (info.inputType and 0x0000000F) == 0x00000001 // TYPE_CLASS_TEXT
            val capFlags = info.inputType and 0x0000F000
            if (!isText || capFlags == 0) {
                false
            } else try {
                ic.getCursorCapsMode(info.inputType) != 0
            } catch (t: Throwable) {
                false
            }
        }
        if (wanted != autoCapsOn) {
            autoCapsOn = wanted
            invalidate()
        }
    }

    private enum class KeyType { TEXT, SHIFT, BACKSPACE, ENTER, SPACE, SYMBOL_TOGGLE }

    private class Key(
        val rect: RectF,
        val type: KeyType,
        val glyph: Char? = null,
        /** Typed on long-press (top-row letters → digits). */
        val alt: Char? = null,
    ) {
        /** 1 while held, fades to 0 after release. */
        var press = 0f
    }

    private var listener: Listener? = null

    private var keys: List<Key> = emptyList()
    private var spaceKey: Key? = null

    private var symbolMode = false
    private var symbolPage = 1 // 1: ?123, 2: =\<
    private var shift = 0 // 0 off, 1 one-shot, 2 locked
    private var autoCapsOn = false
    private var lastShiftTap = 0L
    private val shiftActive: Boolean get() = shift > 0 || autoCapsOn

    private var swipeHintEnabled = false
    private var dictationEnabled = true
    private var enterIsAction = false

    // pointerId → key index
    private val held = SparseIntArray()

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    private val handler = Handler(Looper.getMainLooper())
    private var repeatFired = false
    private val repeatRunnable = object : Runnable {
        override fun run() {
            repeatFired = true
            listener?.onBackspace()
            handler.postDelayed(this, 45L)
        }
    }
    private var longPressFired = false
    private val longPressRunnable = Runnable {
        longPressFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onSpaceLongPress()
    }

    private var altKey: Key? = null
    private var altFired = false
    private val altRunnable = Runnable {
        val k = altKey ?: return@Runnable
        val a = k.alt ?: return@Runnable
        altFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onCommit(a.toString())
        listener?.needCapsRefresh()
    }

    private var downX = 0f
    private var downY = 0f
    private var swipeCandidate = false
    private var swipeFired = false
    private var lastFrame = 0L

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    // ---------------------------------------------------------------- layout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) buildLayout(w.toFloat(), h.toFloat())
    }

    private fun buildLayout(w: Float, h: Float) {
        val margin = dp(4f)
        val gap = dp(5f)
        val rowGap = dp(7f)
        val top = dp(22f)       // page-indicator strip
        val bottom = dp(6f)
        val keyH = (h - top - bottom - 3 * rowGap) / 4f

        val inner = w - margin * 2
        // widest row is the 10-key top row; the third row (shift + 7 + backspace) is the next.
        val u = minOf((inner - 9 * gap) / 10f, (inner - 8 * gap) / 9.8f)

        val list = ArrayList<Key>(40)
        fun rowY(row: Int) = top + row * (keyH + rowGap)

        fun glyphRow(chars: String, row: Int, startX: Float, alts: String? = null) {
            var x = startX
            val y = rowY(row)
            for ((k, c) in chars.withIndex()) {
                list.add(Key(RectF(x, y, x + u, y + keyH), KeyType.TEXT, c, alts?.getOrNull(k)))
                x += u + gap
            }
        }

        val page2 = symbolMode && symbolPage == 2

        fun rowWidth(n: Int) = n * u + (n - 1) * gap

        // Row 1
        val r1 = when {
            page2 -> "[]{}#%^*+="
            symbolMode -> "1234567890"
            else -> "qwertyuiop"
        }
        glyphRow(r1, 0, margin + (inner - rowWidth(10)) / 2f, if (symbolMode) null else "1234567890")

        // Row 2
        val r2 = when {
            page2 -> "_\\|~<>€£¥"
            symbolMode -> "@#\$%&-+()"
            else -> "asdfghjkl"
        }
        glyphRow(r2, 1, margin + (inner - rowWidth(9)) / 2f)

        // Row 3: shift + 7 + backspace
        run {
            val y = rowY(2)
            val wide = 1.4f * u
            val letters = when {
                page2 -> "`•°©±¶§"
                symbolMode -> "/\"':;!?"
                else -> "zxcvbnm"
            }
            val total = wide * 2 + letters.length * u + (letters.length + 1) * gap
            var x = margin + (inner - total) / 2f
            // in symbol mode the shift slot flips between the two symbol pages
            list.add(Key(RectF(x, y, x + wide, y + keyH), KeyType.SHIFT))
            x += wide + gap
            for (c in letters) {
                list.add(Key(RectF(x, y, x + u, y + keyH), KeyType.TEXT, c))
                x += u + gap
            }
            list.add(Key(RectF(x, y, x + wide, y + keyH), KeyType.BACKSPACE))
        }

        // Row 4: toggle · , · space · . · enter
        run {
            val y = rowY(3)
            val wide = 1.5f * u
            var x = margin
            list.add(Key(RectF(x, y, x + wide, y + keyH), KeyType.SYMBOL_TOGGLE))
            x += wide + gap
            list.add(Key(RectF(x, y, x + u, y + keyH), KeyType.TEXT, ','))
            x += u + gap
            val spaceW = inner - (wide + gap + u + gap + gap + u + gap + wide)
            val space = Key(RectF(x, y, x + spaceW, y + keyH), KeyType.SPACE)
            list.add(space)
            spaceKey = space
            x += spaceW + gap
            list.add(Key(RectF(x, y, x + u, y + keyH), KeyType.TEXT, '.'))
            x += u + gap
            list.add(Key(RectF(x, y, x + wide, y + keyH), KeyType.ENTER))
        }

        keys = list
        held.clear()
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Palette.bg)
        drawPageDots(canvas)

        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(1L, 40L)
        lastFrame = now
        var animating = false

        for ((i, key) in keys.withIndex()) {
            if (isHeld(i)) {
                key.press = 1f
            } else if (key.press > 0f) {
                key.press = (key.press - dt / 130f).coerceAtLeast(0f)
                if (key.press > 0f) animating = true
            }
            drawKey(canvas, key)
        }
        drawSpaceHint(canvas)

        if (animating) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private fun drawPageDots(canvas: Canvas) {
        if (!dictationEnabled) return
        val cy = dp(11f)
        val cx = width / 2f
        fillPaint.color = Palette.textSecondary
        canvas.drawCircle(cx - dp(6f), cy, dp(2.4f), fillPaint)
        fillPaint.color = Palette.textFaint
        canvas.drawCircle(cx + dp(6f), cy, dp(2.4f), fillPaint)
    }

    private fun drawKey(canvas: Canvas, key: Key) {
        val r = key.rect
        val base = when (key.type) {
            KeyType.TEXT -> Palette.keyBg
            KeyType.SPACE -> Palette.keyBg
            KeyType.ENTER -> Palette.textPrimary
            else -> Palette.keyBgSpecial
        }
        val pressed = if (key.type == KeyType.ENTER) 0xFFA9ADB4.toInt() else Palette.keyPressed
        keyPaint.color = Palette.mix(base, pressed, key.press)
        val radius = r.height() * 0.22f
        canvas.drawRoundRect(r, radius, radius, keyPaint)

        when (key.type) {
            KeyType.TEXT -> {
                drawGlyph(canvas, r, key.glyph ?: ' ')
                key.alt?.let { drawAlt(canvas, r, it) }
            }
            KeyType.SYMBOL_TOGGLE -> drawLabel(canvas, r, if (symbolMode) "abc" else "?123")
            KeyType.SPACE -> Unit
            KeyType.BACKSPACE -> drawBackspace(canvas, r)
            KeyType.SHIFT -> if (symbolMode) drawLabel(canvas, r, if (symbolPage == 1) "=\\<" else "?123")
            else drawShift(canvas, r)
            KeyType.ENTER -> drawEnter(canvas, r)
        }
    }

    private fun drawGlyph(canvas: Canvas, r: RectF, c: Char) {
        textPaint.color = Palette.textPrimary
        textPaint.textSize = r.height() * 0.42f
        val out = if (c.isLetter() && shiftActive) c.uppercaseChar() else c
        val cy = r.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(out.toString(), r.centerX(), cy, textPaint)
    }

    private fun drawAlt(canvas: Canvas, r: RectF, c: Char) {
        textPaint.color = Palette.textFaint
        textPaint.textSize = r.height() * 0.2f
        canvas.drawText(c.toString(), r.right - r.width() * 0.2f, r.top + r.height() * 0.26f, textPaint)
    }

    private fun drawLabel(canvas: Canvas, r: RectF, label: String) {
        textPaint.color = Palette.textSecondary
        textPaint.textSize = r.height() * 0.30f
        val cy = r.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(label, r.centerX(), cy, textPaint)
    }

    private fun drawSpaceHint(canvas: Canvas) {
        val sk = spaceKey ?: return
        if (!swipeHintEnabled || !dictationEnabled) return
        textPaint.color = Palette.textFaint
        textPaint.textSize = dp(12f)
        val cy = sk.rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText("‹  swipe left to dictate", sk.rect.centerX(), cy, textPaint)
    }

    private fun drawBackspace(canvas: Canvas, r: RectF) {
        strokePaint.color = Palette.textPrimary
        strokePaint.strokeWidth = dp(1.8f)
        val cx = r.centerX()
        val cy = r.centerY()
        val hw = r.height() * 0.30f
        val hh = r.height() * 0.19f
        val tip = hh * 0.95f
        path.reset()
        path.moveTo(cx - hw, cy)
        path.lineTo(cx - hw + tip, cy - hh)
        path.lineTo(cx + hw, cy - hh)
        path.lineTo(cx + hw, cy + hh)
        path.lineTo(cx - hw + tip, cy + hh)
        path.close()
        canvas.drawPath(path, strokePaint)
        val xc = cx + tip * 0.3f
        val xs = hh * 0.42f
        canvas.drawLine(xc - xs, cy - xs, xc + xs, cy + xs, strokePaint)
        canvas.drawLine(xc - xs, cy + xs, xc + xs, cy - xs, strokePaint)
    }

    private fun drawShift(canvas: Canvas, r: RectF) {
        val on = shiftActive
        val color = if (on) Palette.textPrimary else Palette.textSecondary
        strokePaint.color = color
        strokePaint.strokeWidth = dp(1.8f)
        fillPaint.color = color
        val cx = r.centerX()
        val cy = r.centerY() - if (shift == 2) dp(1.5f) else 0f
        val s = r.height() * 0.25f
        path.reset()
        path.moveTo(cx, cy - s)
        path.lineTo(cx + s * 0.95f, cy + s * 0.05f)
        path.lineTo(cx + s * 0.4f, cy + s * 0.05f)
        path.lineTo(cx + s * 0.4f, cy + s * 0.8f)
        path.lineTo(cx - s * 0.4f, cy + s * 0.8f)
        path.lineTo(cx - s * 0.4f, cy + s * 0.05f)
        path.lineTo(cx - s * 0.95f, cy + s * 0.05f)
        path.close()
        if (on) canvas.drawPath(path, fillPaint) else canvas.drawPath(path, strokePaint)
        if (shift == 2) {
            canvas.drawLine(cx - s * 0.4f, cy + s * 1.2f, cx + s * 0.4f, cy + s * 1.2f, strokePaint)
        }
    }

    private fun drawEnter(canvas: Canvas, r: RectF) {
        strokePaint.color = Palette.bg
        strokePaint.strokeWidth = dp(2f)
        val cx = r.centerX()
        val cy = r.centerY()
        val s = r.height() * 0.17f
        path.reset()
        if (enterIsAction) {
            // →
            path.moveTo(cx - s * 1.2f, cy)
            path.lineTo(cx + s * 1.2f, cy)
            path.moveTo(cx + s * 0.35f, cy - s * 0.85f)
            path.lineTo(cx + s * 1.2f, cy)
            path.lineTo(cx + s * 0.35f, cy + s * 0.85f)
        } else {
            // ↵
            path.moveTo(cx + s * 1.2f, cy - s * 1.05f)
            path.lineTo(cx + s * 1.2f, cy + s * 0.35f)
            path.lineTo(cx - s * 1.2f, cy + s * 0.35f)
            path.moveTo(cx - s * 0.35f, cy - s * 0.5f)
            path.lineTo(cx - s * 1.2f, cy + s * 0.35f)
            path.lineTo(cx - s * 0.35f, cy + s * 1.2f)
        }
        canvas.drawPath(path, strokePaint)
    }

    // ---------------------------------------------------------------- touch

    private fun isHeld(index: Int): Boolean {
        for (i in 0 until held.size()) if (held.valueAt(i) == index) return true
        return false
    }

    private fun keyAt(x: Float, y: Float): Int {
        // Keys are 4–5dp apart; snap to the nearest key so taps in the gutters still land.
        var best = -1
        var bestD = Float.MAX_VALUE
        val slop = dp(4f)
        for ((i, key) in keys.withIndex()) {
            val r = key.rect
            if (x < r.left - slop || x > r.right + slop || y < r.top - slop || y > r.bottom + slop) continue
            val dx = maxOf(r.left - x, 0f, x - r.right)
            val dy = maxOf(r.top - y, 0f, y - r.bottom)
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                held.clear()
                swipeFired = false
                swipeCandidate = dictationEnabled
                downX = event.x
                downY = event.y
                press(event.getPointerId(0), event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                swipeCandidate = false
                val i = event.actionIndex
                press(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                if (swipeFired) return true
                if (swipeCandidate && event.pointerCount == 1 && isSwipeLeft(event)) {
                    fireSwipe()
                    return true
                }
                for (p in 0 until event.pointerCount) {
                    val id = event.getPointerId(p)
                    val idx = keyAt(event.getX(p), event.getY(p))
                    val old = held.get(id, -1)
                    if (idx != old) {
                        if (old >= 0 && keys[old].type == KeyType.BACKSPACE) stopRepeat()
                        if (old >= 0 && keys[old].type == KeyType.SPACE) stopLongPress()
                        if (old >= 0 && keys[old] === altKey) handler.removeCallbacks(altRunnable)
                        if (idx < 0) held.delete(id) else held.put(id, idx)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                // a quick flick can end past the threshold without a MOVE having crossed it
                if (!swipeFired && swipeCandidate && event.actionMasked == MotionEvent.ACTION_UP &&
                    isSwipeLeft(event)
                ) {
                    fireSwipe()
                    return true
                }
                val i = event.actionIndex
                val id = event.getPointerId(i)
                val pressed = held.get(id, -1)
                val idx = keyAt(event.getX(i), event.getY(i))
                held.delete(id)
                if (!swipeFired && pressed >= 0 && idx == pressed) release(keys[pressed])
                else if (pressed >= 0) stopTimers(keys[pressed])
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP) held.clear()
            }
            MotionEvent.ACTION_CANCEL -> cancelAll()
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    private fun isSwipeLeft(e: MotionEvent): Boolean {
        val dx = e.x - downX
        val dy = e.y - downY
        return dx < -dp(72f) && abs(dx) > abs(dy) * 1.6f
    }

    private fun fireSwipe() {
        swipeFired = true
        cancelAll()
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        listener?.onSwipeLeft()
    }

    private fun press(id: Int, x: Float, y: Float) {
        val idx = keyAt(x, y)
        if (idx < 0) return
        held.put(id, idx)
        val key = keys[idx]
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when (key.type) {
            KeyType.BACKSPACE -> {
                repeatFired = false
                handler.postDelayed(repeatRunnable, 380L)
            }
            KeyType.SPACE -> {
                longPressFired = false
                handler.postDelayed(longPressRunnable, 520L)
            }
            KeyType.TEXT -> if (key.alt != null) {
                handler.removeCallbacks(altRunnable)
                altKey = key
                altFired = false
                handler.postDelayed(altRunnable, 380L)
            }
            else -> Unit
        }
        invalidate()
    }

    private fun stopRepeat() = handler.removeCallbacks(repeatRunnable)
    private fun stopLongPress() = handler.removeCallbacks(longPressRunnable)

    private fun stopTimers(key: Key) {
        when (key.type) {
            KeyType.BACKSPACE -> stopRepeat()
            KeyType.SPACE -> stopLongPress()
            KeyType.TEXT -> if (altKey === key) handler.removeCallbacks(altRunnable)
            else -> Unit
        }
    }

    private fun cancelAll() {
        stopRepeat()
        stopLongPress()
        handler.removeCallbacks(altRunnable)
        held.clear()
        invalidate()
    }

    private fun release(key: Key) {
        stopTimers(key)
        when (key.type) {
            KeyType.BACKSPACE -> if (!repeatFired) listener?.onBackspace()
            KeyType.SPACE -> if (!longPressFired) {
                listener?.onCommit(" ")
                listener?.needCapsRefresh()
            }
            KeyType.TEXT -> if (!(altKey === key && altFired)) fireKey(key)
            else -> fireKey(key)
        }
    }

    private fun fireKey(key: Key) {
        when (key.type) {
            KeyType.TEXT -> {
                val c = key.glyph ?: return
                val out = if (c.isLetter() && shiftActive) c.uppercaseChar() else c
                listener?.onCommit(out.toString())
                if (shift == 1) shift = 0
                // the editor hasn't applied the text yet; the host re-checks caps
                autoCapsOn = false
                invalidate()
                listener?.needCapsRefresh()
            }
            KeyType.SHIFT -> {
                if (symbolMode) {
                    symbolPage = if (symbolPage == 1) 2 else 1
                    buildLayout(width.toFloat(), height.toFloat())
                    invalidate()
                    return
                }
                val now = SystemClock.uptimeMillis()
                val doubleTap = now - lastShiftTap < 350L
                shift = when {
                    // a quick second tap is caps lock, whatever the first tap did
                    // (at the start of a sentence the first tap only turned auto-caps off)
                    doubleTap && shift != 2 -> { autoCapsOn = false; 2 }
                    autoCapsOn && shift == 0 -> { autoCapsOn = false; 0 }
                    shift == 0 -> 1
                    else -> 0
                }
                lastShiftTap = if (doubleTap) 0L else now
                invalidate()
            }
            KeyType.ENTER -> listener?.onEnter()
            KeyType.SYMBOL_TOGGLE -> setSymbols(!symbolMode)
            else -> Unit
        }
    }

    override fun onDetachedFromWindow() {
        stopRepeat()
        stopLongPress()
        handler.removeCallbacks(altRunnable)
        super.onDetachedFromWindow()
    }
}
