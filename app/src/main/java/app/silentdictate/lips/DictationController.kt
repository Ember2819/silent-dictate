package app.silentdictate.lips

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import app.silentdictate.Prefs
import app.silentdictate.ai.OpenRouterClient
import app.silentdictate.lips.model.LipReaderEngine
import app.silentdictate.util.SimpleLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns everything dictation: readiness of the model, the CameraX binding,
 * the frame ring buffer, engine inference and (optional) OpenRouter cleanup.
 */
class DictationController(
    private val context: Context,
    private val prefs: Prefs,
    private val textContextProvider: () -> String,
    private val listener: Listener,
) {
    interface Listener {
        /** State for the hold button UI. */
        fun onDictationState(state: State, message: String?)

        /** Final text to commit into the field. */
        fun onDictationResult(text: String)
    }

    enum class State { IDLE, HOLDING, THINKING }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val owner = SimpleLifecycleOwner()

    private val engine = LipReaderEngine(context)
    private val modelManager = app.silentdictate.lips.model.ModelManager(context)
    private val client = OpenRouterClient()

    private var analyzer: MouthAnalyzer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraBound = false
    private val analyzerExecutor = Executors.newSingleThreadExecutor()

    private val collecting = AtomicBoolean(false)
    private val frames = ArrayList<FloatArray>()

    private var inFlight = false

    fun onCreate() {
        scope.launch {
            val ready = withContext(Dispatchers.IO) { engine.prepare() }
            when (ready) {
                is LipReaderEngine.Ready.Yes -> bindCameraIfPossible()
                is LipReaderEngine.Ready.No -> { /* reported lazily on hold */ }
            }
        }
    }

    fun onInputShown() {
        owner.emit(androidx.lifecycle.Lifecycle.Event.ON_START)
        scope.launch { bindCameraIfPossible() }
    }

    fun onInputHidden() {
        owner.emit(androidx.lifecycle.Lifecycle.Event.ON_STOP)
        collecting.set(false)
        synchronized(frames) { frames.clear() }
    }

    fun onDestroy() {
        collecting.set(false)
        analyzer?.release()
        analyzer = null
        engine.close()
        scope.cancel()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private suspend fun bindCameraIfPossible() {
        if (!hasCameraPermission() || !engine.ready() || cameraBound || !owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            return
        }
        try {
            cameraBound = true
            val provider = withContext(Dispatchers.IO) {
                ProcessCameraProvider.getInstance(context).get()
            }
            cameraProvider = provider

            val spec = engine.currentSpec()!!
            val analysisUseCase = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analyzer = MouthAnalyzer(
                outW = spec.width,
                outH = spec.height,
                mean = spec.mean,
                std = spec.std,
                onFrame = { frame ->
                    if (collecting.get()) {
                        synchronized(frames) {
                            frames.add(frame)
                            if (frames.size > spec.fixedT.coerceAtLeast(60)) {
                                frames.removeAt(0)
                            }
                        }
                    }
                },
                onFacePresence = { },
            )
            analysisUseCase.setAnalyzer(analyzerExecutor, analyzer!!)

            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysisUseCase)
        } catch (t: Throwable) {
            cameraBound = false
            analysisUseCaseRef = null
        }
    }

    private var analysisUseCaseRef: ImageAnalysis? = null

    /** User pressed the hold button. */
    fun beginHold() {
        if (!hasCameraPermission()) {
            listener.onDictationState(State.IDLE, "Camera permission needed — open Silent Dictate")
            return
        }
        if (!engine.ready()) {
            listener.onDictationState(State.IDLE, "Lip model not installed — open Silent Dictate")
            return
        }
        synchronized(frames) { frames.clear() }
        collecting.set(true)
        listener.onDictationState(State.HOLDING, null)
    }

    /** User released the hold button. */
    fun endHold() {
        collecting.set(false)
        if (inFlight) return
        val captured = synchronized(frames) {
            frames.toList().also { frames.clear() }
        }
        if (captured.size < MIN_FRAMES) {
            listener.onDictationState(State.IDLE, "Too short — hold a little longer")
            return
        }
        listener.onDictationState(State.THINKING, null)
        runTranscription(captured)
    }

    fun cancelHold() {
        collecting.set(false)
        synchronized(frames) { frames.clear() }
    }

    private fun runTranscription(captured: List<FloatArray>) {
        inFlight = true
        scope.launch {
            var raw = ""
            try {
                raw = engine.transcribe(captured)
            } catch (t: Throwable) {
                // fall through to empty handling below
            }
            if (raw.isBlank()) {
                inFlight = false
                listener.onDictationState(
                    State.IDLE,
                    "Couldn't read lips — hold near a light source, face the screen"
                )
                return@launch
            }
            var finalText = raw
            var postMessage: String? = null
            if (prefs.useOpenRouter) {
                if (prefs.openRouterKey.isBlank()) {
                    postMessage = "No OpenRouter key — inserted raw output"
                    listener.onDictationState(State.THINKING, postMessage)
                } else {
                    val outcome = client.cleanUp(
                        key = prefs.openRouterKey,
                        model = prefs.openRouterModel,
                        rawLipRead = raw,
                        contextBefore = textContextProvider(),
                    )
                    when (outcome) {
                        is OpenRouterClient.Result.Ok -> finalText = outcome.text
                        is OpenRouterClient.Result.Error -> {
                            finalText = raw
                            postMessage = "${outcome.userMessage} — inserted raw output"
                        }
                    }
                }
            }
            inFlight = false
            if (finalText.isBlank()) {
                listener.onDictationState(State.IDLE, "Couldn't make out words")
            } else {
                listener.onDictationResult(finalText)
                listener.onDictationState(State.IDLE, null)
            }
        }
    }

    fun modelStatus() = modelManager.status()

    companion object {
        private const val MIN_FRAMES = 8
    }
}
