package com.evanaronson.linguize.accessibility

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.SystemClock
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
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.card.FloatingCard
import com.evanaronson.linguize.ui.components.LanguagePicker
import com.evanaronson.linguize.ui.copyToClipboard
import com.evanaronson.linguize.ui.home.HomeActivity
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
    /** The card on screen, or waiting hidden behind settings; null when there's none. */
    private var card: Card? = null

    /**
     * An open card: its [window], the [field] it will write back to, and [discard], which
     * closes it without writing anything back.
     */
    private class Card(val window: OverlayWindow, val field: AccessibilityNodeInfo, val discard: () -> Unit)

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
        card?.let { it.window.remove() }
        card = null
        super.onDestroy()
    }

    private fun open() {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        card?.let { current ->
            // Tapping the button while the card waits behind settings brings it back, unless
            // it was left there: it waited too long, or the tap is for another app's field.
            if (!current.window.hidden || !current.leftBehind(focused)) {
                current.window.hidden = false
                return
            }
            current.discard()
        }

        val field = focused?.takeIf { it.isEditable }
        if (field != null && (field.isPassword || PrivateField.matches(field.inputType, field.hintText, field.viewIdResourceName))) {
            Toast.makeText(this, "Linguize doesn't read fields for passwords, codes, numbers or email addresses", Toast.LENGTH_LONG).show()
            return
        }
        val selection = field?.takeUnless { it.isShowingHintText }?.text?.toString()
            ?.let { Selection.of(it, field.textSelectionStart, field.textSelectionEnd) }
        if (field == null || selection == null || selection.text.isEmpty()) {
            Toast.makeText(this, "Type something in a text field first", Toast.LENGTH_SHORT).show()
            return
        }

        val app = application as App
        // Back closes the card as a tap outside it does; [close] is set just below.
        var onBack: () -> Unit = {}
        val window = OverlayWindow(this, onBack = { onBack() })
        val check = ViewModelProvider(window, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[CheckViewModel::class.java]
        var entry by mutableStateOf(MenuEntry.Auto)
        check.check(selection.text, entry.language, Origin.Button, field.packageName?.toString())

        var closed = false
        // Ends this card, once: Back can arrive both as a key and as a back callback.
        fun end(applied: () -> Boolean) {
            if (closed) return
            closed = true
            check.dismiss(applied = applied())
            window.remove()
            if (card?.window === window) card = null
        }
        val close: () -> Unit = {
            // Applied only if the text really went into the field. When it's copied instead,
            // nothing reached the app, and history has no "copied" for the whole text.
            end { check.workingText?.let { writeBack(field, selection, it) } == true }
        }
        onBack = close

        // Settings opens with the card hidden behind it (an overlay would cover it). Leaving
        // settings brings the card back, checked again if it failed or the settings changed.
        var settingsBefore: String? = null
        val openSettings: () -> Unit = {
            settingsBefore = app.settings.snapshot
            window.hidden = true
            startActivity(HomeActivity.settingsIntent(this))
        }
        val backFromSettings: () -> Unit = {
            val before = settingsBefore
            if (before != null) {
                settingsBefore = null
                window.hidden = false
                if (app.settings.snapshot != before || check.state is CardState.Failed) check.recheck()
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
                                check.check(selection.text, it.language, Origin.Button, field.packageName?.toString())
                            })
                            FilledTonalIconButton(onClick = openSettings) {
                                Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                            }
                        }
                    }
                }
            }
        }
        card = Card(window, field, discard = { end { false } })
    }

    /**
     * Whether this card, hidden behind settings, was left there: the button is now tapped for
     * a field in another app, or the card has waited longer than anyone comes back for.
     * Linguize's own fields (settings') don't count, so the card still comes back from there.
     */
    private fun Card.leftBehind(focused: AccessibilityNodeInfo?): Boolean {
        val since = window.hiddenSince ?: return false
        if (SystemClock.elapsedRealtime() - since > MAX_HIDDEN_MILLIS) return true
        if (focused == null || focused.packageName?.toString() == packageName) return false
        return focused != field
    }

    /**
     * Puts the corrected text back in place of the selection. If the field changed or
     * went away while the card was open, it isn't overwritten: the text is copied instead.
     * Returns true when the text went into the field, false when it was copied.
     */
    private fun writeBack(field: AccessibilityNodeInfo, selection: Selection, replacement: String): Boolean {
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
            return true
        }
        copyToClipboard(replacement)
        val why = if (unchanged) "This app didn't accept the change" else "The text changed meanwhile"
        Toast.makeText(this, "$why; the corrected text is copied instead", Toast.LENGTH_LONG).show()
        return false
    }

    private companion object {
        /** How long a card hidden behind settings waits to be brought back. */
        const val MAX_HIDDEN_MILLIS = 15 * 60_000L
    }
}
