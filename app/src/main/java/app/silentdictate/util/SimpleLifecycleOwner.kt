package app.silentdictate.util

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/** A plain lifecycle owner to drive CameraX inside an InputMethodService. */
class SimpleLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    fun emit(event: Lifecycle.Event) = registry.handleLifecycleEvent(event)
}
