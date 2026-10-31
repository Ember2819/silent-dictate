package app.silentdictate.lips.model

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Runs model installs at app scope, so a 200 MB download survives the settings
 * screen being rotated, recreated or closed. The screen just observes [state].
 */
object ModelInstaller {

    sealed class State {
        object Idle : State()
        data class Working(val label: String, val progress: Float?) : State()
        data class Failed(val message: String) : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    val busy: Boolean get() = _state.value is State.Working

    fun downloadRecommended(context: Context) {
        if (busy) return
        val app = context.applicationContext
        _state.value = State.Working("Downloading… 0%", 0f)
        scope.launch {
            val result = ModelManager(app).installRecommended { p ->
                _state.value = State.Working(
                    if (p >= 0.99f) "Checking the model…" else "Downloading… ${(p * 100).toInt()}%", p
                )
            }
            finish(result)
        }
    }

    fun importZip(context: Context, uri: Uri) {
        if (busy) return
        val app = context.applicationContext
        _state.value = State.Working("Installing…", null)
        scope.launch {
            val result = try {
                app.contentResolver.openInputStream(uri)?.use { ModelManager(app).install(it) }
                    ?: ModelManager.InstallResult.Error("Couldn’t open that file")
            } catch (e: Exception) {
                ModelManager.InstallResult.Error(e.message ?: "Couldn’t read that file")
            }
            finish(result)
        }
    }

    private fun finish(result: ModelManager.InstallResult) {
        _state.value = when (result) {
            is ModelManager.InstallResult.Ok -> State.Idle
            is ModelManager.InstallResult.Error -> State.Failed(result.message)
        }
    }

    /** Forget a shown error (e.g. after Remove). */
    fun clearError() {
        if (_state.value is State.Failed) _state.value = State.Idle
    }
}
