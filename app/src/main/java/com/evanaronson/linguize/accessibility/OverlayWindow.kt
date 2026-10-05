package com.evanaronson.linguize.accessibility

import android.content.Context
import android.graphics.PixelFormat
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
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
 * A full-screen accessibility overlay showing Compose content above every app.
 * Compose needs a lifecycle, saved state and view models, which a service doesn't
 * have, so the window provides its own: each window starts with fresh view models
 * and clears them when it's removed.
 */
internal class OverlayWindow(private val context: Context) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val params = LayoutParams(
        LayoutParams.MATCH_PARENT,
        LayoutParams.MATCH_PARENT,
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Not focusable, so the app keeps its keyboard and text field.
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )
    private var view: ComposeView? = null

    init {
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun show(content: @Composable () -> Unit) {
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayWindow)
            setViewTreeSavedStateRegistryOwner(this@OverlayWindow)
            setViewTreeViewModelStoreOwner(this@OverlayWindow)
            setContent(content)
        }
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        windowManager.addView(view, params)
        this.view = view
    }

    /** Hidden, the window is invisible and lets touches through, but keeps its content. */
    var hidden = false
        set(value) {
            field = value
            val view = view ?: return
            params.alpha = if (value) 0f else 1f
            params.flags = if (value) {
                params.flags or LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                params.flags and LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            windowManager.updateViewLayout(view, params)
        }

    fun remove() {
        view?.let(windowManager::removeView)
        view = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
