package com.evanaronson.languagecheck

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.evanaronson.languagecheck.check.CheckFailure
import com.evanaronson.languagecheck.ui.AppTheme
import com.evanaronson.languagecheck.ui.CardActions
import com.evanaronson.languagecheck.ui.CardState
import com.evanaronson.languagecheck.ui.ResultCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Launcher screen: language, model and API key, plus a way to try a check. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as App
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    Settings(app)
                }
            }
        }
    }
}

/** Result of trying the chosen model with a short check. */
private sealed interface ModelTest {
    data object Testing : ModelTest
    data class Works(val millis: Long) : ModelTest
    data class Failed(val message: String) : ModelTest
}

/** Models offered for a provider: still loading, loaded, or failed to load. */
private sealed interface ModelList {
    data object Loading : ModelList
    data class Loaded(val models: List<String>) : ModelList
    data object Failed : ModelList
}

@Composable
private fun Settings(app: App) {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf<Language?>(null) }
    var punctuation by remember { mutableStateOf(Punctuation.Moderate) }
    var checks by remember { mutableStateOf(Checks.Both) }
    var modelTest by remember { mutableStateOf<ModelTest?>(null) }
    var provider by remember { mutableStateOf(Provider.Gemini) }
    var keyStatus by remember { mutableStateOf(emptyMap<Provider, KeyStatus>()) }
    val savedKeys = keyStatus.filterValues { it is KeyStatus.Saved }.keys
    var model by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<ModelList>(ModelList.Loading) }
    var key by remember { mutableStateOf("") }
    var sample by remember { mutableStateOf("Bon dia! Com estas amb la pluja?") }
    var result by remember { mutableStateOf<CardState?>(null) }
    var checked by remember { mutableStateOf("") }

    // Keystore and preference reads are slow enough to keep off the main thread.
    LaunchedEffect(Unit) {
        val state = withContext(Dispatchers.IO) {
            Triple(
                app.settings.language,
                app.settings.provider,
                Provider.entries.associateWith { app.keys.status(it) },
            )
        }
        language = state.first
        provider = state.second
        keyStatus = state.third
        punctuation = withContext(Dispatchers.IO) { app.settings.punctuation }
        checks = withContext(Dispatchers.IO) { app.settings.checks }
        loaded = true
    }

    // Reload the provider's live model list whenever the provider or its key changes.
    LaunchedEffect(loaded, provider, provider in savedKeys) {
        if (!loaded) return@LaunchedEffect
        model = withContext(Dispatchers.IO) { app.settings.model(provider) }
        modelTest = null
        if (provider !in savedKeys) {
            models = ModelList.Loaded(emptyList())
            return@LaunchedEffect
        }
        models = ModelList.Loading
        models = try {
            val apiKey = withContext(Dispatchers.IO) { app.keys.key(provider) }.orEmpty()
            ModelList.Loaded(
                when (provider) {
                    Provider.Gemini -> app.catalog.gemini(apiKey)
                    Provider.OpenAI -> app.catalog.openAI(apiKey)
                },
            )
        } catch (_: CheckFailure) {
            ModelList.Failed
        }
    }

    fun saveKey(value: String) {
        val target = provider
        scope.launch {
            val status = withContext(Dispatchers.IO) {
                app.keys.setKey(target, value)
                app.keys.status(target)
            }
            keyStatus = keyStatus + (target to status)
            key = ""
        }
    }

    fun testModel(target: Provider, chosen: String) {
        modelTest = ModelTest.Testing
        scope.launch {
            modelTest = try {
                ModelTest.Works(app.testModel(target, chosen))
            } catch (failure: CheckFailure) {
                ModelTest.Failed(failure.detail ?: failure.reason.name)
            }
        }
    }

    fun runCheck() {
        val text = sample.trim()
        if (text.isEmpty()) return
        checked = text
        result = CardState.Loading
        scope.launch {
            result = try {
                CardState.Done(app.check(text))
            } catch (failure: CheckFailure) {
                CardState.Failed(failure.reason, failure.detail)
            }
        }
    }

    val context = LocalContext.current

    Column(
        Modifier
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Linguize", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Select text you wrote in any app, then tap Check in the selection menu (it may be under ⋮).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Section("Checking")
        Dropdown(
            label = "Check text as",
            selected = language?.name ?: "Auto-detect",
            options = listOf<Language?>(null) + Language.all,
            optionLabel = { it?.name ?: "Auto-detect" },
            onSelect = { choice ->
                language = choice
                scope.launch(Dispatchers.IO) { app.settings.language = choice }
            },
        )

        Dropdown(
            label = "Punctuation",
            selected = punctuation.label,
            options = Punctuation.entries,
            optionLabel = { it.label },
            onSelect = { choice ->
                punctuation = choice
                scope.launch(Dispatchers.IO) { app.settings.punctuation = choice }
            },
        )
        Text(
            punctuation.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.selectableGroup()) {
            Checks.entries.forEach { option ->
                RadioRow(
                    label = option.label,
                    selected = option == checks,
                    onClick = {
                        checks = option
                        scope.launch(Dispatchers.IO) { app.settings.checks = option }
                    },
                )
            }
        }

        Section("Model")
        Column(Modifier.selectableGroup()) {
            Provider.entries.forEach { option ->
                RadioRow(
                    label = option.label,
                    selected = option == provider,
                    onClick = {
                        provider = option
                        key = ""
                        scope.launch(Dispatchers.IO) { app.settings.provider = option }
                    },
                ) {
                    when (val status = keyStatus[option]) {
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
                        else -> {}
                    }
                }
            }
        }

        val recommended = "${provider.recommendedModel} (recommended)"
        val available = (models as? ModelList.Loaded)?.models.orEmpty().filter { it != provider.recommendedModel }
        Dropdown(
            label = when (models) {
                ModelList.Loading -> "Model · loading list…"
                ModelList.Failed -> "Model · couldn't load list"
                is ModelList.Loaded -> if (provider in savedKeys) "Model" else "Model · save a key to see all"
            },
            selected = model ?: recommended,
            options = listOf<String?>(null) + available,
            optionLabel = { it ?: recommended },
            onSelect = { choice ->
                model = choice
                val target = provider
                scope.launch(Dispatchers.IO) { app.settings.setModel(target, choice) }
                if (target in savedKeys) testModel(target, choice ?: target.recommendedModel)
            },
        )
        if (provider in savedKeys) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (val test = modelTest) {
                        null -> "Not tested yet"
                        ModelTest.Testing -> "Testing…"
                        is ModelTest.Works -> "✓ Works · %.1f s".format(test.millis / 1000.0)
                        is ModelTest.Failed -> "✗ ${test.message}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (modelTest is ModelTest.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = modelTest != ModelTest.Testing,
                    onClick = { testModel(provider, model ?: provider.recommendedModel) },
                ) { Text("Test") }
            }
        }

        when (val status = keyStatus[provider]) {
            is KeyStatus.Saved -> SavedKey(provider, status.lastFour, onRemove = { saveKey("") })
            else -> {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("${provider.label} API key") },
                    placeholder = { Text("Paste key") },
                    isError = status == KeyStatus.Unreadable,
                    supportingText = {
                        if (status == KeyStatus.Unreadable) Text("The saved key can't be read. Paste it again.")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = key.isNotBlank(), onClick = { saveKey(key) }) { Text("Save") }
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.keyUrl)))
                    }) { Text("Get a key") }
                }
            }
        }

        Section("Try it")
        OutlinedTextField(
            value = sample,
            onValueChange = { sample = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Button(onClick = ::runCheck) { Text("Check") }

        result?.let { state ->
            ResultCard(
                original = checked,
                state = state,
                actions = CardActions(
                    onCopy = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Linguize", it))
                    },
                    onWorkingText = { sample = it },
                    onDone = { result = null },
                    onRetry = ::runCheck,
                    onOpenSettings = {},
                ),
                modifier = Modifier.fillMaxWidth(),
                scrollable = false,
            )
        }
    }
}

/** Shown instead of the key field once a key is saved. */
@Composable
private fun SavedKey(provider: Provider, lastFour: String, onRemove: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("✓", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${provider.label} key saved",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "Ends in $lastFour",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Dropdown(
    label: String,
    selected: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}
