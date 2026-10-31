package app.silentdictate.ime

import android.annotation.SuppressLint
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import app.silentdictate.Prefs
import app.silentdictate.lips.DictationController
import app.silentdictate.util.Palette
import kotlin.math.min

/**
 * The input method. Hosts two stacked views:
 *   - [KeyboardView]: regular typing; swipe left → dictation
 *   - [MicPanel]: hold to read lips; swipe right → typing
 */
class SilentDictateImeService : InputMethodService() {

    private val main = Handler(Looper.getMainLooper())

    private var prefs: Prefs? = null
    private var controller: DictationController? = null

    private val root = FrameLayout(this)
    private val keyboardView = KeyboardView(this)
    private val micPanel = MicPanel(this)

    private var mode = Mode.Letters

    private enum class Mode { Letters, Mic }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        val p = prefs!!
        val textCtx = {
            try {
                currentInputConnection?.getTextBeforeCursor(240, 0)?.toString() ?: ""
            } catch (t: Throwable) {
                ""
            }
        }
        controller = DictationController(
            this, p, textCtx,
            object : DictationController.Listener {
                override fun onDictationState(state: DictationController.State, message: String?) {
                    main.post {
                        micPanel.setState(
                            when (state) {
                                DictationController.State.IDLE -> MicPanel.State.IDLE
                                DictationController.State.HOLDING -> MicPanel.State.HOLDING
                                DictationController.State.THINKING -> MicPanel.State.THINKING
                            },
                            message
                        )
                    }
                }

                override fun onDictationResult(text: String) {
                    main.post {
                        val ic = currentInputConnection ?: return@post
                        ic.commitText(text, 1)
                        storeRecent(text)
                        p.swipeHintDone = true
                    }
                }
            }
        )
        controller?.onCreate()

        keyboardView.setListener(object : KeyboardView.Listener {
            override fun onCommit(text: String) {
                val ic = currentInputConnection
                ic?.commitText(text, 1)
                storeRecent(text)
                val p = prefs ?: return
                if (!p.swipeHintDone) {
                    p.swipeHintDone = true
                    keyboardView.swipeHintDone()
                }
            }

            override fun onBackspace() {
                val ic = currentInputConnection
                ic?.deleteSurroundingText(1, 0)
            }

            override fun onEnter() {
                val info = currentInputEditorInfo
                val ic = currentInputConnection
                if (ic == null) return
                val action = info?.actionId ?: 0
                if (action != 0 && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                    ic.performEditorAction(action)
                } else {
                    ic.commitText("\n", 1)
                }
            }

            override fun onSwipeLeft() {
                switchMode()
            }

            override fun needCapsRefresh() {
                refreshCaps()
            }
        })

        micPanel.setListener(object : MicPanel.Listener {
            override fun onHoldStart() {
                controller?.beginHold()
            }

            override fun onHoldEnd() {
                controller?.endHold()
            }

            override fun onHoldCancel() {
                controller?.cancelHold()
            }

            override fun onBack() {
                switchMode()
            }
        })
    }

    private fun storeRecent(text: String) {
        val p = prefs ?: return
        p.recentText = ((p.recentText + " " + text).trim().takeLast(240))
    }

    override fun onDestroy() {
        controller?.onDestroy()
        controller = null
        super.onDestroy()
    }

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        root.removeAllViews()
        root.setBackgroundColor(Palette.bg)
        root.addView(
            keyboardView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            micPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).also { it.topMargin = 0 }
        )
        micPanel.visibility = View.INVISIBLE
        mode = Mode.Letters
        keyboardView.showSwipeHint(!(prefs?.swipeHintDone ?: true))

        root.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                insets.systemWindowInsetBottom
            }
            v.setPadding(0, 0, 0, bottom)
            insets
        }

        val heightPx = min(
            (resources.displayMetrics.heightPixels * 0.44f).toInt(),
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 380f, resources.displayMetrics
            ).toInt()
        ).coerceAtLeast(
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 260f, resources.displayMetrics
            ).toInt()
        )
        root.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, heightPx
        )
        return root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        controller?.onInputShown()
        refreshCaps()
        if (mode != Mode.Letters) switchModeAnimated(Mode.Letters)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        controller?.onInputHidden()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        refreshCaps()
    }

    private fun refreshCaps() {
        keyboardView.refreshCaps(currentInputConnection, currentInputEditorInfo)
    }

    private fun switchMode() {
        switchModeAnimated(if (mode == Mode.Letters) Mode.Mic else Mode.Letters)
    }

    private fun switchModeAnimated(target: Mode) {
        if (mode == target) return
        mode = target
        // Static cross-fade with a slight slide; keep it calm and minimal.
        keyboardView.animate().cancel()
        micPanel.animate().cancel()
        if (target == Mode.Mic) {
            micPanel.visibility = View.VISIBLE
            micPanel.alpha = 0f
            micPanel.translationX = -root.width * 0.12f
            keyboardView.animate()
                .alpha(0f)
                .translationX(root.width * 0.12f)
                .setDuration(170L)
                .start()
            micPanel.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(170L)
                .withEndAction { keyboardView.visibility = View.INVISIBLE }
                .start()
            // clear any leftover dictation state
            micPanel.setState(MicPanel.State.IDLE, null)
        } else {
            keyboardView.visibility = View.VISIBLE
            keyboardView.alpha = 0f
            keyboardView.translationX = -root.width * 0.12f
            micPanel.animate()
                .alpha(0f)
                .translationX(root.width * 0.12f)
                .setDuration(170L)
                .start()
            keyboardView.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(170L)
                .withEndAction { micPanel.visibility = View.INVISIBLE }
                .start()
            keyboardView.showSwipeHint(!(prefs?.swipeHintDone ?: true))
        }
    }
}
