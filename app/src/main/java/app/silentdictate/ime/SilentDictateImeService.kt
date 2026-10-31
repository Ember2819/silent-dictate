package app.silentdictate.ime

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import app.silentdictate.Prefs
import app.silentdictate.lips.DictationController
import app.silentdictate.util.Palette
import kotlin.math.min

/**
 * The input method. Two pages in one frame:
 *   - [KeyboardView]: regular typing; swipe left → dictation
 *   - [MicPanel]: hold to read lips; swipe right → typing
 */
class SilentDictateImeService : InputMethodService() {

    private enum class Mode { Letters, Mic }

    private lateinit var prefs: Prefs
    private var controller: DictationController? = null

    private var root: FrameLayout? = null
    private var keyboardView: KeyboardView? = null
    private var micPanel: MicPanel? = null
    private var mode = Mode.Letters

    /** The field a dictation was started in; results only ever go back there. */
    private var dictationTarget: String? = null
    private var lastSpaceAt = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        controller = DictationController(
            this, prefs, ::textBeforeCursorForCleanup,
            object : DictationController.Listener {
                override fun onDictationState(
                    state: DictationController.State, message: String?, isError: Boolean,
                ) {
                    micPanel?.setState(
                        when (state) {
                            DictationController.State.IDLE -> MicPanel.State.IDLE
                            DictationController.State.HOLDING -> MicPanel.State.HOLDING
                            DictationController.State.THINKING -> MicPanel.State.THINKING
                        },
                        message, isError
                    )
                }

                override fun onDictationResult(text: String) = commitDictation(text)

                override fun onFacePresence(visible: Boolean) {
                    micPanel?.setFaceVisible(visible)
                }
            }
        )
    }

    override fun onDestroy() {
        controller?.onDestroy()
        controller = null
        super.onDestroy()
    }

    // The extract UI would cover the whole screen in landscape; keep the keys compact.
    override fun onEvaluateFullscreenMode(): Boolean = false

    // ------------------------------------------------------------------ view

    override fun onCreateInputView(): View {
        val keys = KeyboardView(this)
        val mic = MicPanel(this)
        keyboardView = keys
        micPanel = mic
        wireKeyboard(keys)
        wireMic(mic)

        // The IME window wraps its content, so MATCH_PARENT pages would take the
        // whole screen: give the pages an explicit height instead.
        val dm = resources.displayMetrics
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val wanted = (if (landscape) 200f else 252f) * dm.density
        val heightPx = min(wanted, dm.heightPixels * 0.55f).toInt()

        val frame = FrameLayout(this).apply {
            setBackgroundColor(Palette.bg)
            addView(keys, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx))
            addView(mic, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx))
        }
        root = frame

        // paint the system navigation area in the keyboard's colour
        window?.window?.navigationBarColor = Palette.bg

        showMode(Mode.Letters, animate = false)
        keys.showSwipeHint(!prefs.swipeHintDone)
        return frame
    }

    private fun wireKeyboard(keys: KeyboardView) {
        keys.setListener(object : KeyboardView.Listener {
            override fun onCommit(text: String) {
                if (text == " " && doubleSpacePeriod()) return
                lastSpaceAt = if (text == " ") SystemClock.uptimeMillis() else 0L
                currentInputConnection?.commitText(text, 1)
                if (!prefs.swipeHintDone) {
                    prefs.swipeHintDone = true
                    keys.swipeHintDone()
                }
            }

            override fun onBackspace() {
                sendKey(KeyEvent.KEYCODE_DEL)
            }

            override fun onEnter() {
                val ic = currentInputConnection ?: return
                val info = currentInputEditorInfo
                if (enterPerformsAction(info)) {
                    ic.performEditorAction(info!!.imeOptions and EditorInfo.IME_MASK_ACTION)
                } else {
                    sendKey(KeyEvent.KEYCODE_ENTER)
                }
            }

            override fun onSwipeLeft() {
                prefs.swipeHintDone = true
                keys.swipeHintDone()
                showMode(Mode.Mic, animate = true)
            }

            override fun onSpaceLongPress() {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }

            override fun needCapsRefresh() {
                refreshCaps()
            }
        })
    }

    private fun wireMic(mic: MicPanel) {
        mic.setListener(object : MicPanel.Listener {
            override fun onHoldStart() {
                dictationTarget = editorKey()
                controller?.beginHold()
            }

            override fun onHoldEnd() {
                controller?.endHold()
            }

            override fun onHoldCancel() {
                controller?.cancelHold()
            }

            override fun onBack() {
                showMode(Mode.Letters, animate = true)
            }
        })
    }

    // ------------------------------------------------------------------ editor

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val keys = keyboardView ?: return
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        keys.setSymbols(
            cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
                cls == InputType.TYPE_CLASS_DATETIME
        )
        keys.resetShift()
        // icon and behaviour come from the same rule, so ↵ never sends a message
        keys.setEnterIsAction(enterPerformsAction(info))
        // nothing a person mouths should ever land in a password field
        val dictation = !isPasswordField(info)
        keys.setDictationEnabled(dictation)
        if (!dictation && mode == Mode.Mic) showMode(Mode.Letters, animate = false)
        refreshCaps()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // the camera only runs while the dictation page is visible
        controller?.deactivate()
        if (mode == Mode.Mic) showMode(Mode.Letters, animate = false)
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        refreshCaps()
    }

    private fun refreshCaps() {
        keyboardView?.refreshCaps(currentInputConnection, currentInputEditorInfo)
    }

    private fun enterPerformsAction(info: EditorInfo?): Boolean {
        val opts = info?.imeOptions ?: return false
        val action = opts and EditorInfo.IME_MASK_ACTION
        return opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 &&
            action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
    }

    /** Identifies the focused field (app + view id + type). */
    private fun editorKey(): String? {
        val info = currentInputEditorInfo ?: return null
        return "${info.packageName}/${info.fieldId}/${info.inputType}"
    }

    /** "word␣" + space within a second → "word.␣", as on most keyboards. Plain-text fields only. */
    private fun doubleSpacePeriod(): Boolean {
        val info = currentInputEditorInfo ?: return false
        if (SystemClock.uptimeMillis() - lastSpaceAt > 1000L) return false
        if (info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        if (variation == InputType.TYPE_TEXT_VARIATION_URI ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            isPasswordField(info)
        ) return false
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(2, 0) ?: return false
        if (before.length < 2 || before[1] != ' ' || !before[0].isLetterOrDigit()) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(1, 0)
        ic.commitText(". ", 1)
        ic.endBatchEdit()
        lastSpaceAt = 0L
        return true
    }

    private fun sendKey(code: Int) {
        // timestamps + soft-keyboard flags; hand-built KeyEvents get dropped by some editors
        sendDownUpKeyEvents(code)
    }

    private fun isPasswordField(info: EditorInfo?): Boolean {
        val t = info?.inputType ?: return false
        val cls = t and InputType.TYPE_MASK_CLASS
        val variation = t and InputType.TYPE_MASK_VARIATION
        return when (cls) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** Context for the cleanup pass; never read out of a password field. */
    private fun textBeforeCursorForCleanup(): String {
        if (isPasswordField(currentInputEditorInfo)) return ""
        return try {
            currentInputConnection?.getTextBeforeCursor(240, 0)?.toString().orEmpty()
        } catch (t: Throwable) {
            ""
        }
    }

    private fun commitDictation(text: String) {
        // a slow cleanup can finish after the user moved on; never type into another field
        // (or another app, or a password box) than the one they dictated for
        val target = dictationTarget
        dictationTarget = null
        if (target == null || target != editorKey() || !isInputViewShown ||
            isPasswordField(currentInputEditorInfo)
        ) {
            micPanel?.setState(MicPanel.State.IDLE, "The text field changed — nothing was inserted", true)
            return
        }
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)
        val lead = if (!before.isNullOrEmpty() && !before.last().isWhitespace()) " " else ""
        ic.commitText(lead + text, 1)
        prefs.swipeHintDone = true
    }

    // ------------------------------------------------------------------ pages

    private fun showMode(target: Mode, animate: Boolean) {
        val keys = keyboardView ?: return
        val mic = micPanel ?: return
        val width = (root?.width ?: 0).takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val shift = width * 0.16f
        val changed = mode != target
        mode = target

        if (target == Mode.Mic) {
            mic.setState(MicPanel.State.IDLE, null)
            controller?.activate() // may report a missing model / permission into the panel
        } else {
            controller?.deactivate()
        }

        val incoming = if (target == Mode.Mic) mic else keys
        val outgoing = if (target == Mode.Mic) keys else mic
        // incoming enters from the side the user is swiping toward
        val enterFrom = if (target == Mode.Mic) shift else -shift

        incoming.animate().cancel()
        outgoing.animate().cancel()

        if (!animate || !changed) {
            incoming.visibility = View.VISIBLE
            incoming.alpha = 1f
            incoming.translationX = 0f
            outgoing.visibility = View.INVISIBLE
            outgoing.alpha = 0f
            outgoing.translationX = 0f
            return
        }

        incoming.visibility = View.VISIBLE
        incoming.alpha = 0f
        incoming.translationX = enterFrom
        incoming.animate()
            .alpha(1f).translationX(0f)
            .setDuration(220L).setInterpolator(DecelerateInterpolator(1.6f))
            .start()
        outgoing.animate()
            .alpha(0f).translationX(-enterFrom)
            .setDuration(160L).setInterpolator(DecelerateInterpolator())
            .withEndAction { if (mode == target) outgoing.visibility = View.INVISIBLE }
            .start()
    }
}
