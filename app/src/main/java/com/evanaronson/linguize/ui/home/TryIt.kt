package com.evanaronson.linguize.ui.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.card.ResultCard
import com.evanaronson.linguize.ui.components.LanguagePicker

/** A text box and the same card the selection menu shows, so checks can be tried here. */
@Composable
internal fun TryIt(check: CheckViewModel, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var sample by rememberSaveable { mutableStateOf(SAMPLE) }

    fun applyAndClose() {
        check.workingText?.let { sample = it }
        check.dismiss()
    }

    OutlinedTextField(
        value = sample,
        onValueChange = {
            sample = it
            // The card's suggestions are for the old text, and nothing of them was applied.
            check.dismiss(applied = false)
        },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )
    // Saved as the language's code (null for auto-detect).
    var code by rememberSaveable { mutableStateOf<String?>(null) }
    // Picking a language from the menu runs the check. Checks made here are never recorded
    // in history (Origin.Tester isn't recorded): only text from other apps is.
    LanguagePicker(Language.forCode(code), onSelect = {
        code = it?.code
        sample.trim().takeIf { text -> text.isNotEmpty() }?.let { text -> check.check(text, it, Origin.Tester) }
    })

    check.state?.let { state ->
        ResultCard(
            state = state,
            actions = CardActions.of(check, context, onClose = ::applyAndClose, onOpenSettings = onOpenSettings),
            modifier = Modifier.fillMaxWidth(),
            scrollable = false,
        )
    }
}

private const val SAMPLE = "Bon dia! Com estas amb la pluja?"
