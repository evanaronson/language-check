package com.evanaronson.linguize.accessibility

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Selection
import com.evanaronson.linguize.data.MenuEntry
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.card.FloatingCard
import com.evanaronson.linguize.ui.components.LanguagePicker
import com.evanaronson.linguize.ui.copyToClipboard
import com.evanaronson.linguize.ui.settings.SettingsActivity
import com.evanaronson.linguize.ui.theme.AppTheme
import kotlinx.coroutines.flow.drop

/**
 * Checks text in apps whose selection menu doesn't show Linguize, such as
 * Telegram. Tapping the accessibility button reads the focused text field (the
 * selection, or all of it), floats the result card over the app, and writes the
 * accepted changes back into the field when the card closes. Nothing is read
 * except when the button is tapped.
 */
class LinguizeAccessibilityService : AccessibilityService() {
    /** The card on screen, if any. */
    private var window: OverlayWindow? = null

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
        window?.remove()
        window = null
        super.onDestroy()
    }

    private fun open() {
        // Tapping the button while the card waits behind settings brings it back.
        window?.let {
            it.hidden = false
            return
        }
        val field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable && !it.isPassword && !it.isShowingHintText }
        val selection = field?.text?.toString()?.let { Selection.of(it, field.textSelectionStart, field.textSelectionEnd) }
        if (field == null || selection == null || selection.text.isEmpty()) {
            Toast.makeText(this, "Type something in a text field first", Toast.LENGTH_SHORT).show()
            return
        }

        val app = application as App
        val window = OverlayWindow(this)
        val check = ViewModelProvider(window, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[CheckViewModel::class.java]
        var entry by mutableStateOf(MenuEntry.Auto)
        check.check(selection.text, entry.language)

        val close: () -> Unit = {
            check.workingText?.let { writeBack(field, selection, it) }
            window.remove()
            this.window = null
        }

        // Settings opens with the card hidden behind it (an overlay would cover it). Leaving
        // settings brings the card back, checked again if it failed or the settings changed.
        var settingsBefore: String? = null
        val openSettings: () -> Unit = {
            settingsBefore = app.settings.snapshot
            window.hidden = true
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val backFromSettings: () -> Unit = {
            val before = settingsBefore
            if (before != null) {
                settingsBefore = null
                window.hidden = false
                if (app.settings.snapshot != before) {
                    check.recheck()
                } else if (check.state is CardState.Failed) {
                    check.retry()
                }
            }
        }

        val actions = CardActions.of(check, context = this, onClose = close, onOpenSettings = openSettings)
        window.show {
            AppTheme {
                LaunchedEffect(Unit) {
                    app.settingsLeft.drop(1).collect { backFromSettings() }
                }
                check.state?.let { state ->
                    // At the top, clear of the keyboard that's open for the field.
                    FloatingCard(state, actions, onDismiss = close, alignment = Alignment.TopCenter, topPadding = 32.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LanguagePicker(entry, onSelect = {
                                entry = it
                                check.check(selection.text, it.language)
                            })
                            FilledTonalIconButton(onClick = openSettings) {
                                Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                            }
                        }
                    }
                }
            }
        }
        this.window = window
    }

    /**
     * Puts the corrected text back in place of the selection. If the field changed or
     * went away while the card was open, it isn't overwritten: the text is copied instead.
     */
    private fun writeBack(field: AccessibilityNodeInfo, selection: Selection, replacement: String) {
        val unchanged = field.refresh() && field.text?.toString() == selection.full
        val text = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, selection.with(replacement))
        }
        if (unchanged && field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text)) {
            val cursor = selection.cursorAfter(replacement)
            val cursorAt = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            }
            field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorAt)
        } else {
            copyToClipboard(replacement)
            val why = if (unchanged) "This app didn't accept the change" else "The text changed meanwhile"
            Toast.makeText(this, "$why; the corrected text is copied instead", Toast.LENGTH_LONG).show()
        }
    }
}
