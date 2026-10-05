package com.evanaronson.linguize.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.data.MenuEntry
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.components.Dropdown
import com.evanaronson.linguize.ui.components.MultiSelectDropdown
import com.evanaronson.linguize.ui.components.RadioRow
import com.evanaronson.linguize.ui.components.SectionTitle

/** The launcher screen: how checks work, which model runs them, and a place to try one. */
@Composable
fun SettingsScreen(settings: SettingsViewModel, check: CheckViewModel) {
    Column(
        Modifier
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painterResource(R.drawable.wordmark),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).height(40.dp),
        )
        Text(
            "Select text you wrote in any app, then tap Linguize in the selection menu (it may be under ⋮).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The saved settings load in a moment; showing defaults first would flash the wrong choices.
        val state = settings.state ?: return@Column
        CheckingSection(state, settings)
        ModelSection(state, settings)
        OtherAppsSection()
        TryItSection(check)
    }
}

@Composable
private fun CheckingSection(state: SettingsState, settings: SettingsViewModel) {
    SectionTitle("Checking")
    val labels = menuLabels()
    MultiSelectDropdown(
        label = "In the selection menu",
        options = MenuEntry.entries,
        selected = state.menu,
        optionLabel = { entry -> "${labels.getValue(entry)} (${entry.language?.name ?: "auto-detect"})" },
        onToggle = settings::toggleMenuEntry,
    )
    Dropdown(
        label = "Punctuation",
        selected = state.punctuation.label,
        options = Punctuation.entries,
        optionLabel = { it.label },
        onSelect = settings::setPunctuation,
    )
    Text(
        state.punctuation.description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(Modifier.selectableGroup()) {
        Judgments.entries.forEach { option ->
            RadioRow(option.label, selected = option == state.judgments, onClick = { settings.setJudgments(option) })
        }
    }
}

@Composable
private fun menuLabels(): Map<MenuEntry, String> = MenuEntry.entries.associateWith { stringResource(it.label) }
