package app.silentdictate.lips

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import app.silentdictate.Prefs
import app.silentdictate.ai.OpenRouterClient
import app.silentdictate.lips.model.LipReaderEngine
import app.silentdictate.lips.model.ModelManager
import app.silentdictate.util.SimpleLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns everything dictation: model readiness, the CameraX binding, the frame
 * buffer, engine inference and the optional OpenRouter cleanup.
 *
 * The camera is only open while the dictation panel is on screen
 * ([activate] / [deactivate]); frames are only kept while the button is held.
 */
class DictationController(
    context: Context,
    private val prefs: Prefs,
    private val textContextProvider: () -> String,
    private val listener: Listener,
) {
    interface Listener {
        fun onDictationState(state: State, message: String?, isError: Boolean)
        fun onDictationResult(text: String)
        fun onFacePresence(visible: Boolean)
    }

    enum class State { IDLE, HOLDING, THINKING }

    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val owner = SimpleLifecycleOwner().also { it.emit(Lifecycle.Event.ON_CREATE) }

    private val engine = LipReaderEngine(this.context)
    private val modelManager = ModelManager(this.context)
    private val client = OpenRouterClient()

    private var loadedStamp = -1L
    private val engineLock = Mutex()
    private var loadError: String? = null

    private var analyzer: MouthAnalyzer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraBound = false
    private var active = false
    private val analyzerExecutor = Executors.newSingleThreadExecutor()

    private val collecting = AtomicBoolean(false)
    private val frames = ArrayList<Frames.Timed>()
    private var faceVisible = true

    private var inFlight = false

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** Something the user has to fix before dictation can work, or null. */
    fun readinessProblem(): String? = when {
        !hasCameraPermission() -> "Camera access needed — open Silent Dictate"
        !modelManager.status().installed -> "No lip model yet — open Silent Dictate"
        else -> loadError
    }

    /** The dictation panel is on screen: load the model and warm the camera. */
    fun activate() {
        if (active) return
        active = true
        owner.emit(Lifecycle.Event.ON_START)
        readinessProblem()?.let { listener.onDictationState(State.IDLE, it, true) }
        scope.launch {
            if (ensureEngine()) {
                bindCamera()
            } else {
                readinessProblem()?.let { listener.onDictationState(State.IDLE, it, true) }
            }
        }
    }

    /** The panel left the screen: the camera must not stay on. */
    fun deactivate() {
        if (!active) return
        active = false
        collecting.set(false)
        synchronized(frames) { frames.clear() }
        try {
            cameraProvider?.unbindAll()
        } catch (_: Throwable) {
        }
        cameraProvider = null
        cameraBound = false
        analyzer?.release()
        analyzer = null
        owner.emit(Lifecycle.Event.ON_STOP)
        releaseEngineIfIdle()
    }

    /** The model holds hundreds of MB; don't keep it resident while the keys are showing. */
    private fun releaseEngineIfIdle() {
        if (active || inFlight) return
        scope.launch {
            engineLock.withLock {
                if (!active && !inFlight) {
                    engine.close()
                    loadedStamp = -1L
                }
            }
        }
    }

    fun onDestroy() {
        deactivate()
        owner.emit(Lifecycle.Event.ON_DESTROY)
        engine.close()
        analyzerExecutor.shutdown()
        scope.cancel()
    }

    /** (Re)loads the model if it was installed or replaced since last time. */
    private suspend fun ensureEngine(): Boolean = engineLock.withLock {
        // one load at a time: two overlapping prepare() calls would close each other's session
        val stamp = modelManager.stamp()
        if (stamp == 0L) return@withLock false
        if (engine.ready() && stamp == loadedStamp) return@withLock true
        val result = withContext(Dispatchers.IO) { engine.prepare() }
        when (result) {
            is LipReaderEngine.Ready.Yes -> {
                loadError = null
                if (!active && !inFlight) {
                    // the page closed while we were loading: don't leave ~300 MB resident
                    engine.close()
                    loadedStamp = -1L
                    return@withLock false
                }
                loadedStamp = stamp
                true
            }
            is LipReaderEngine.Ready.No -> {
                loadedStamp = -1L
                loadError = result.message
                false
            }
        }
    }

    private suspend fun bindCamera() {
        if (cameraBound || !active || !hasCameraPermission() || !engine.ready()) return
        cameraBound = true
        try {
            val provider = withContext(Dispatchers.IO) {
                ProcessCameraProvider.getInstance(context).get()
            }
            val spec = engine.currentSpec()
            if (!active || spec == null) {
                cameraBound = false
                return
            }
            val useCase = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val a = MouthAnalyzer(
                outW = spec.width,
                outH = spec.height,
                mean = spec.mean,
                std = spec.std,
                onFrame = { frame, timestampNs ->
                    if (collecting.get()) {
                        synchronized(frames) {
                            if (frames.size < MAX_FRAMES) {
                                frames.add(Frames.Timed(timestampNs, frame))
                                // at the cap, stop and transcribe instead of silently dropping words
                                if (frames.size == MAX_FRAMES) main.post { endHold() }
                            }
                        }
                    }
                },
                onFacePresence = { visible ->
                    main.post {
                        if (visible != faceVisible) {
                            faceVisible = visible
                            listener.onFacePresence(visible)
                        }
                    }
                },
            )
            useCase.setAnalyzer(analyzerExecutor, a)
            // selfie camera when there is one; otherwise whatever camera the device has
            val selector = when {
                provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                else -> error("no camera on this device")
            }
            provider.unbindAll()
            provider.bindToLifecycle(owner, selector, useCase)
            analyzer = a
            cameraProvider = provider
        } catch (t: Throwable) {
            Log.w(TAG, "camera bind failed", t)
            cameraBound = false
            listener.onDictationState(State.IDLE, "Couldn’t open the camera", true)
        }
    }

    /** User pressed the hold button. */
    fun beginHold() {
        readinessProblem()?.let {
            listener.onDictationState(State.IDLE, it, true)
            return
        }
        if (inFlight) return
        if (!engine.ready() || !cameraBound) {
            // still loading the model / opening the camera: say so instead of recording nothing
            scope.launch { if (ensureEngine()) bindCamera() }
            if (!engine.ready()) {
                listener.onDictationState(State.IDLE, "Getting the lip reader ready… try again in a moment", false)
                return
            }
        }
        synchronized(frames) { frames.clear() }
        faceVisible = true
        listener.onFacePresence(true)
        collecting.set(true)
        listener.onDictationState(State.HOLDING, null, false)
    }

    /** User released the hold button. */
    fun endHold() {
        if (!collecting.getAndSet(false)) return
        val raw = synchronized(frames) { frames.toList().also { frames.clear() } }
        val captured = Frames.resample(raw)
        if (captured.size < MIN_FRAMES) {
            val msg = if (analyzer?.isFaceTracked() == true && faceVisible) "Too short — hold a little longer"
            else "Couldn’t see your face — look at the camera"
            listener.onDictationState(State.IDLE, msg, true)
            return
        }
        listener.onDictationState(State.THINKING, null, false)
        runTranscription(captured)
    }

    fun cancelHold() {
        if (collecting.getAndSet(false)) {
            synchronized(frames) { frames.clear() }
            listener.onDictationState(State.IDLE, null, false)
        }
    }

    private fun runTranscription(captured: List<FloatArray>) {
        inFlight = true
        scope.launch {
            try {
                // the model writes capitals only; work with normal text from here on
                val raw = try {
                    // long holds are read in pieces cut at pauses, so memory stays flat
                    val pieces = Chunker.split(captured)
                    val parts = ArrayList<String>(pieces.size)
                    for ((i, piece) in pieces.withIndex()) {
                        if (pieces.size > 1) {
                            listener.onDictationState(
                                State.THINKING, "reading part ${i + 1} of ${pieces.size}…", false
                            )
                        }
                        parts.add(engine.transcribe(piece))
                    }
                    parts.joinToString(" ").replace(Regex("\\s+"), " ").trim().lowercase()
                } catch (t: Throwable) {
                    Log.w(TAG, "transcribe failed", t)
                    ""
                }
                if (raw.isBlank()) {
                    listener.onDictationState(
                        State.IDLE, "Couldn’t read your lips — face the camera in good light", true
                    )
                    return@launch
                }

                var finalText = Frames.sentenceCase(raw)
                var note: String? = null
                if (prefs.useOpenRouter) {
                    if (prefs.openRouterKey.isBlank()) {
                        note = "No OpenRouter key — inserted raw text"
                    } else {
                        when (val outcome = client.cleanUp(
                            key = prefs.openRouterKey,
                            model = prefs.openRouterModel,
                            rawLipRead = raw,
                            contextBefore = textContextProvider(),
                            // live progress during rate-limit waits / fallbacks
                            onStatus = { msg ->
                                main.post {
                                    listener.onDictationState(State.THINKING, msg, false)
                                }
                            },
                        )) {
                            is OpenRouterClient.Result.Ok -> finalText = outcome.text
                            is OpenRouterClient.Result.Error ->
                                note = "${outcome.userMessage} — inserted raw text"
                        }
                    }
                }
                listener.onDictationResult(finalText)
                listener.onDictationState(State.IDLE, note, note != null)
            } finally {
                inFlight = false
                releaseEngineIfIdle() // the page may have been closed while we were thinking
            }
        }
    }

    companion object {
        private const val TAG = "SilentDictate"
        private const val MIN_FRAMES = 12   // ~0.5 s at 25 fps
        // Long holds are split into ≤7 s pieces (see Chunker), so the model's memory
        // no longer limits length. This cap only bounds the frame buffer: 60 s at
        // 30 fps ≈ 56 MB of crops.
        private const val MAX_FRAMES = 1800
    }
}
