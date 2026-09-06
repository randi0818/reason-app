package me.excuse.app.overlay

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 把 Compose 挂到 WindowManager 上必须自己提供 LifecycleOwner /
 * ViewModelStoreOwner / SavedStateRegistryOwner 三件套，否则 Compose runtime 直接崩。
 */
class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this).apply { performAttach() }
    private val store = ViewModelStore()

    private var started = false
    private var stopped = false

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun attachToView(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }

    /** 幂等的 —— 重复调用安全，不会触发 SavedStateRegistry 的二次 restore 崩溃 */
    fun start() {
        if (started || stopped) return
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        started = true
    }

    /** 幂等的 —— 一旦 stop 过就锁死，不能再 start */
    fun stop() {
        if (stopped) return
        if (started) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
        store.clear()
        stopped = true
    }
}
