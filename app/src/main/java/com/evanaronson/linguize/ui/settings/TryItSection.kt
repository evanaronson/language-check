package com.evanaronson.linguize.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.evanaronson.linguize.data.MenuEntry
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.card.ResultCard
import com.evanaronson.linguize.ui.components.LanguagePicker
import com.evanaronson.linguize.ui.components.SectionTitle

/** A text box and the same card the selection menu shows, so checks can be tried here. */
@Composable
internal fun TryItSection(check: CheckViewModel) {
    val context = LocalContext.current
    var sample by rememberSaveable { mutableStateOf(SAMPLE) }

    fun applyAndClose() {
        check.workingText?.let { sample = it }
        check.dismiss()
    }

    SectionTitle("Try it")
    OutlinedTextField(
        value = sample,
        onValueChange = {
            sample = it
            // The card's suggestions are for the old text.
            check.dismiss()
        },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )
    var entry by rememberSaveable { mutableStateOf(MenuEntry.Auto) }
    // Picking a language from the menu runs the check, as in the accessibility overlay.
    LanguagePicker(entry, onSelect = {
        entry = it
        sample.trim().takeIf { text -> text.isNotEmpty() }?.let { text -> check.check(text, it.language, Origin.Tester) }
    })

    check.state?.let { state ->
        ResultCard(
            state = state,
            actions = CardActions.of(check, context, onClose = ::applyAndClose, onOpenSettings = null),
            modifier = Modifier.fillMaxWidth(),
            scrollable = false,
        )
    }
}

private const val SAMPLE = "Bon dia! Com estas amb la pluja?"
