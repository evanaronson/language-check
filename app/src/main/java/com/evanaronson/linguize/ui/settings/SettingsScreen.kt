package com.evanaronson.linguize.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.data.MenuEntry
import com.evanaronson.linguize.ui.components.BackButton
import com.evanaronson.linguize.ui.components.Dropdown
import com.evanaronson.linguize.ui.components.MultiSelectDropdown
import com.evanaronson.linguize.ui.components.RadioRow
import com.evanaronson.linguize.ui.components.SectionTitle

/** How checks work, which model runs them, what's kept, and the accessibility button. Opened from Home's gear. */
@Composable
fun SettingsScreen(settings: SettingsViewModel, onBack: () -> Unit) {
    Column(
        Modifier
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackButton(onBack)
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        // The saved settings load in a moment; showing defaults first would flash the wrong choices.
        val state = settings.state ?: return@Column
        CheckingSection(state, settings)
        ModelSection(state, settings)
        HistorySection(state, settings)
        OtherAppsSection()
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
