package com.evanaronson.languagecheck.accessibility

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.evanaronson.languagecheck.App
import com.evanaronson.languagecheck.MainActivity
import com.evanaronson.languagecheck.MenuEntry
import com.evanaronson.languagecheck.ui.AppTheme
import com.evanaronson.languagecheck.ui.card.CardActions
import com.evanaronson.languagecheck.ui.card.CheckViewModel
import com.evanaronson.languagecheck.ui.card.FloatingCard
import com.evanaronson.languagecheck.ui.card.ReviewActions
import com.evanaronson.languagecheck.ui.copyToClipboard

/**
 * Checks text in apps whose selection menu doesn't show Linguize, such as
 * Telegram. Tapping the accessibility button reads the focused text field (the
 * selection, or all of it), floats the result card over the app, and writes the
 * accepted changes back into the field when the card closes. Nothing is read
 * except when the button is tapped.
 */
class LinguizeAccessibilityService :
    AccessibilityService(),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private val windowManager get() = getSystemService(WindowManager::class.java)
    private var overlay: ComposeView? = null

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onServiceConnected() {
        accessibilityButtonController.registerAccessibilityButtonCallback(
            object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) = open()
            },
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        close(field = null, fieldText = null, check = null)
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        super.onDestroy()
    }

    private fun open() {
        if (overlay != null) return
        val field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable && !it.isPassword && !it.isShowingHintText }
        val fieldText = field?.let { f ->
            f.text?.toString()?.let { FieldText.of(it, f.textSelectionStart, f.textSelectionEnd) }
        }
        if (field == null || fieldText == null || fieldText.text.isEmpty()) {
            Toast.makeText(this, "Type something in a text field first", Toast.LENGTH_SHORT).show()
            return
        }

        val app = application as App
        val entries = MenuEntry.entries.filter { it in app.menu.enabled() }.ifEmpty { listOf(MenuEntry.Auto) }
        val check = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[CheckViewModel::class.java]
        var entry by mutableStateOf(entries.first())
        check.check(fieldText.text, entry.language)

        val done = { close(field, fieldText, check) }
        val actions = CardActions(
            onCopy = {
                copyToClipboard(it)
                close(field = null, fieldText = null, check = check)
            },
            review = ReviewActions(
                onAccept = check::accept,
                onAcceptAll = { if (check.acceptAll(it)) done() },
                onUndo = check::undo,
                onDone = done,
            ),
            onRetry = check::retry,
            onSettle = check::settle,
            onOpenSettings = {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                close(field = null, fieldText = null, check = check)
            },
        )

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@LinguizeAccessibilityService)
            setViewTreeSavedStateRegistryOwner(this@LinguizeAccessibilityService)
            setViewTreeViewModelStoreOwner(this@LinguizeAccessibilityService)
            setContent {
                AppTheme {
                    check.state?.let { state ->
                        // At the top, clear of the keyboard that's open for the field.
                        FloatingCard(state, actions, onDismiss = done, alignment = Alignment.TopCenter, topPadding = 32.dp) {
                            if (entries.size > 1) {
                                LanguageChips(entries, entry) {
                                    entry = it
                                    check.check(fieldText.text, it.language)
                                }
                            }
                        }
                    }
                }
            }
        }
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        windowManager.addView(
            view,
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Not focusable, so the app keeps its keyboard and text field.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ),
        )
        overlay = view
    }

    /** Removes the card, writing accepted changes back into [field] when there is one. */
    private fun close(field: AccessibilityNodeInfo?, fieldText: FieldText?, check: CheckViewModel?) {
        val accepted = check?.workingText
        if (field != null && fieldText != null && accepted != null) writeBack(field, fieldText, accepted)
        overlay?.let { windowManager.removeView(it) }
        overlay = null
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        // A fresh check, and view model, for the next tap.
        viewModelStore.clear()
    }

    private fun writeBack(field: AccessibilityNodeInfo, fieldText: FieldText, replacement: String) {
        field.refresh()
        val text = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, fieldText.with(replacement))
        }
        if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text)) {
            val cursor = fieldText.cursorAfter(replacement)
            val selection = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            }
            field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
        } else {
            copyToClipboard(replacement)
            Toast.makeText(this, "This app didn't accept the change; it's copied instead", Toast.LENGTH_LONG).show()
        }
    }
}

/** Picks which language to check as, among the entries enabled for the selection menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageChips(entries: List<MenuEntry>, selected: MenuEntry, onSelect: (MenuEntry) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEach { entry ->
            FilterChip(
                selected = entry == selected,
                onClick = { onSelect(entry) },
                label = { Text(stringResource(entry.label)) },
            )
        }
    }
}
