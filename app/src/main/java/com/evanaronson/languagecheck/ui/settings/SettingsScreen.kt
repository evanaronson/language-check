package com.evanaronson.languagecheck.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanaronson.languagecheck.MenuEntry
import com.evanaronson.languagecheck.R
import com.evanaronson.languagecheck.llm.Provider
import com.evanaronson.languagecheck.review.Judgments
import com.evanaronson.languagecheck.review.Punctuation
import com.evanaronson.languagecheck.settings.KeyStatus
import com.evanaronson.languagecheck.ui.card.CardActions
import com.evanaronson.languagecheck.ui.card.CheckViewModel
import com.evanaronson.languagecheck.ui.card.ResultCard
import com.evanaronson.languagecheck.ui.card.ReviewActions
import com.evanaronson.languagecheck.ui.components.Dropdown
import com.evanaronson.languagecheck.ui.components.LanguagePicker
import com.evanaronson.languagecheck.ui.components.MultiSelectDropdown
import com.evanaronson.languagecheck.ui.components.RadioRow
import com.evanaronson.languagecheck.ui.components.SectionTitle
import com.evanaronson.languagecheck.ui.copyToClipboard

/** The launcher screen: how checks work, which model runs them, and a place to try one. */
@Composable
fun SettingsScreen(settings: SettingsViewModel, check: CheckViewModel) {
    val state = settings.state
    Column(
        Modifier
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painterResource(R.drawable.wordmark),
            contentDescription = "Linguize",
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).height(40.dp),
        )
        Text(
            "Select text you wrote in any app, then tap Linguize in the selection menu (it may be under ⋮).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
private fun ModelSection(state: SettingsState, settings: SettingsViewModel) {
    SectionTitle("Model")
    Column(Modifier.selectableGroup()) {
        Provider.entries.forEach { option ->
            RadioRow(option.label, selected = option == state.provider, onClick = { settings.selectProvider(option) }) {
                KeyBadge(state.keys[option])
            }
        }
    }

    val provider = state.provider
    val recommended = "${provider.recommendedModel} (recommended)"
    val available = (state.models as? ModelList.Loaded)?.models.orEmpty().filter { it != provider.recommendedModel }
    Dropdown(
        label = when (state.models) {
            ModelList.Loading -> "Model · loading list…"
            ModelList.Failed -> "Model · couldn't load list"
            is ModelList.Loaded -> if (state.hasKey) "Model" else "Model · save a key to see all"
        },
        selected = state.model ?: recommended,
        options = listOf<String?>(null) + available,
        optionLabel = { it ?: recommended },
        onSelect = settings::selectModel,
    )
    if (state.hasKey) ModelTestRow(state.modelTest, onTest = settings::testModel)

    when (val status = state.keys[provider]) {
        is KeyStatus.Saved -> SavedKey(provider, status.lastFour, onRemove = settings::removeKey)
        else -> KeyEntry(provider, unreadable = status == KeyStatus.Unreadable, onSave = settings::saveKey)
    }
}

@Composable
private fun KeyBadge(status: KeyStatus?) {
    when (status) {
        is KeyStatus.Saved -> Text(
            "Key ••••${status.lastFour}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        KeyStatus.Unreadable -> Text(
            "Key unreadable",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
        KeyStatus.None, null -> Unit
    }
}

@Composable
private fun ModelTestRow(test: ModelTest?, onTest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (test) {
                null -> "Not tested yet"
                ModelTest.Testing -> "Testing…"
                is ModelTest.Works -> "✓ Works · %.1f s".format(test.millis / 1000.0)
                is ModelTest.Failed -> "✗ ${test.message}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (test is ModelTest.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(enabled = test != ModelTest.Testing, onClick = onTest) { Text("Test") }
    }
}

@Composable
private fun KeyEntry(provider: Provider, unreadable: Boolean, onSave: (String) -> Unit) {
    val context = LocalContext.current
    var key by rememberSaveable(provider) { mutableStateOf("") }
    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("${provider.label} API key") },
        placeholder = { Text("Paste key") },
        isError = unreadable,
        supportingText = { if (unreadable) Text("The saved key can't be read. Paste it again.") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = key.isNotBlank(),
            onClick = {
                onSave(key)
                key = ""
            },
        ) { Text("Save") }
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.keyUrl))) }) {
            Text("Get a key")
        }
    }
}

/** Shown instead of the key field once a key is saved. */
@Composable
private fun SavedKey(provider: Provider, lastFour: String, onRemove: () -> Unit) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("✓", style = MaterialTheme.typography.titleMedium, color = onContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${provider.label} key saved", style = MaterialTheme.typography.titleSmall, color = onContainer)
                Text("Ends in $lastFour", style = MaterialTheme.typography.bodySmall, color = onContainer)
            }
            TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

/** How to turn on the accessibility button, for apps whose selection menu doesn't show Linguize. */
@Composable
private fun OtherAppsSection() {
    val context = LocalContext.current
    SectionTitle("Apps without the menu")
    Text(
        "Some apps, like Telegram, don't show Linguize in their selection menu. Turn on the Linguize " +
            "accessibility button instead, then tap it while typing to check that text field.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "1. App info → ⋮ → Allow restricted settings (needed once, because the app wasn't installed from the Play Store).\n" +
            "2. Accessibility → Linguize → turn it on, with its shortcut.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
            )
        }) { Text("App info") }
        OutlinedButton(onClick = {
            context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) { Text("Accessibility") }
    }
}

/** A text box and the same card the selection menu shows, so checks can be tried here. */
@Composable
private fun TryItSection(check: CheckViewModel) {
    val context = LocalContext.current
    var sample by rememberSaveable { mutableStateOf(SAMPLE) }

    fun applyAndClose() {
        check.workingText?.let { sample = it }
        check.dismiss()
    }

    SectionTitle("Try it")
    OutlinedTextField(value = sample, onValueChange = { sample = it }, modifier = Modifier.fillMaxWidth(), minLines = 2)
    var entry by rememberSaveable { mutableStateOf(MenuEntry.Auto) }
    // Picking a language from the menu runs the check, as in the accessibility overlay.
    LanguagePicker(entry, onSelect = {
        entry = it
        sample.trim().takeIf { text -> text.isNotEmpty() }?.let { text -> check.check(text, it.language) }
    })

    check.state?.let { state ->
        ResultCard(
            state = state,
            actions = CardActions(
                onCopy = context::copyToClipboard,
                review = ReviewActions(
                    onAccept = check::accept,
                    onAcceptAll = { if (check.acceptAll(it)) applyAndClose() },
                    onUndo = check::undo,
                    onDone = ::applyAndClose,
                ),
                onRetry = check::retry,
                onSettle = check::settle,
                onOpenSettings = {},
            ),
            modifier = Modifier.fillMaxWidth(),
            scrollable = false,
        )
    }
}

@Composable
private fun menuLabels(): Map<MenuEntry, String> = MenuEntry.entries.associateWith { stringResource(it.label) }

private const val SAMPLE = "Bon dia! Com estas amb la pluja?"
