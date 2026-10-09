package com.evanaronson.linguize.ui.settings

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.data.KeyStatus
import com.evanaronson.linguize.data.MenuEntry
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.ui.card.title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate

data class SettingsState(
    /** The entries shown in the text-selection menu; never empty. */
    val menu: Set<MenuEntry> = setOf(MenuEntry.Auto),
    val punctuation: Punctuation = Punctuation.Moderate,
    val judgments: Judgments = Judgments.Both,
    val provider: Provider = Provider.Gemini,
    val keys: Map<Provider, KeyStatus> = emptyMap(),
    /** The chosen model, or null for the provider's recommended one. */
    val model: String? = null,
    val models: ModelList = ModelList.Loaded(emptyList()),
    val modelTest: ModelTest? = null,
    /** Whether checks are kept in history. */
    val historyEnabled: Boolean = true,
) {
    val hasKey get() = keys[provider] is KeyStatus.Saved
}

/** The provider's models: loading, loaded, or failed to load. */
sealed interface ModelList {
    data object Loading : ModelList
    data class Loaded(val models: List<String>) : ModelList
    data object Failed : ModelList
}

/** How much history is kept: [count] checks, the oldest from [since]. */
data class KeptHistory(val count: Int, val since: Long?)

/** A finished export, waiting for the screen to offer it. */
sealed interface Export {
    /** Written; [uri] is a shareable link to the file. */
    data class Ready(val uri: Uri) : Export

    data object Failed : Export
}

/** The result of trying the chosen model with a short check. */
sealed interface ModelTest {
    data object Testing : ModelTest
    data class Works(val millis: Long) : ModelTest
    data class Failed(val message: String) : ModelTest
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private var modelsJob: Job? = null
    private var testJob: Job? = null

    /** Null until the saved settings have been read. */
    var state by mutableStateOf<SettingsState?>(null)
        private set

    /** Null until first counted. */
    val history: StateFlow<KeptHistory?> = combine(app.history.count(), app.history.since()) { count, since -> KeptHistory(count, since) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** An export is being written. */
    var exporting by mutableStateOf(false)
        private set

    /**
     * The export just finished, until the screen showing settings has offered it and called
     * [exportOffered]. State rather than a callback, so the share sheet opens from the
     * activity on screen even when the one that asked was recreated meanwhile.
     */
    var exported by mutableStateOf<Export?>(null)
        private set

    init {
        viewModelScope.launch {
            state = withContext(Dispatchers.IO) {
                val settings = app.settings
                SettingsState(
                    menu = app.menu.enabled().ifEmpty { setOf(MenuEntry.Auto) },
                    punctuation = settings.punctuation,
                    judgments = settings.judgments,
                    provider = settings.provider,
                    keys = Provider.entries.associateWith { app.keys.status(it) },
                    model = settings.model(settings.provider),
                    historyEnabled = settings.historyEnabled,
                )
            }
            loadModels()
        }
    }

    /** Adds or removes a selection-menu entry; the last one can't be removed. */
    fun toggleMenuEntry(entry: MenuEntry) {
        val menu = state?.menu ?: return
        val on = entry !in menu
        if (!on && menu.size == 1) return
        change { it.copy(menu = if (on) it.menu + entry else it.menu - entry) }
        // A call to the package manager, so off the main thread.
        viewModelScope.launch(Dispatchers.IO) { app.menu.setEnabled(entry, on) }
    }

    fun setPunctuation(punctuation: Punctuation) = change { state ->
        app.settings.punctuation = punctuation
        state.copy(punctuation = punctuation)
    }

    fun setJudgments(judgments: Judgments) = change { state ->
        app.settings.judgments = judgments
        state.copy(judgments = judgments)
    }

    fun selectProvider(provider: Provider) {
        change { state ->
            app.settings.provider = provider
            state.copy(provider = provider, model = app.settings.model(provider), modelTest = null)
        }
        loadModels()
    }

    /** Null selects the recommended model. Picking a model tests it straight away. */
    fun selectModel(model: String?) {
        val current = change { state ->
            app.settings.setModel(state.provider, model)
            state.copy(model = model)
        }
        if (current?.hasKey == true) testModel()
    }

    fun testModel() {
        val current = state ?: return
        val provider = current.provider
        val model = current.model ?: provider.recommendedModel
        testJob?.cancel()
        change { it.copy(modelTest = ModelTest.Testing) }
        testJob = viewModelScope.launch {
            val result = try {
                ModelTest.Works(app.checker.testModel(provider, model))
            } catch (failure: CheckFailure) {
                ModelTest.Failed(failure.detail ?: failure.reason.title)
            }
            change { if (it.provider == provider) it.copy(modelTest = result) else it }
        }
    }

    /** Saves the key for the current provider; a blank value removes it. */
    fun saveKey(value: String) {
        val provider = state?.provider ?: return
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                app.keys.set(provider, value)
                app.keys.status(provider)
            }
            change { it.copy(keys = it.keys + (provider to status), modelTest = null) }
            if (state?.provider == provider) loadModels()
        }
    }

    fun removeKey() = saveKey("")

    /** Off stops recording; it doesn't delete what's kept. */
    fun setHistoryEnabled(enabled: Boolean) = change { state ->
        app.settings.historyEnabled = enabled
        state.copy(historyEnabled = enabled)
    }

    /** Deletes all history. On the app's scope, so leaving settings doesn't stop it halfway. */
    fun clearHistory() {
        app.appScope.launch { app.history.clear() }
    }

    /**
     * Writes all history to a JSON Lines file in the cache, replacing earlier exports, and
     * sets [exported] to a shareable link to it, or to [Export.Failed].
     */
    fun exportHistory() {
        if (exporting) return
        exporting = true
        exported = null
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                try {
                    val folder = File(app.cacheDir, EXPORTS).apply { mkdirs() }
                    folder.listFiles()?.forEach { it.delete() }
                    val file = File(folder, "linguize-history-${LocalDate.now()}.jsonl")
                    val written = file.outputStream().buffered().use { app.history.export(it) }
                    // A partial file isn't worth sharing.
                    if (written) FileProvider.getUriForFile(app, "${app.packageName}.files", file) else null
                } catch (_: IOException) {
                    null
                } catch (_: IllegalArgumentException) {
                    // FileProvider: the folder isn't one res/xml/file_paths.xml shares.
                    null
                }
            }
            exporting = false
            exported = uri?.let { Export.Ready(it) } ?: Export.Failed
        }
    }

    /** The screen has offered [exported]; it isn't offered again. */
    fun exportOffered() {
        exported = null
    }

    private fun loadModels() {
        val current = state ?: return
        val provider = current.provider
        modelsJob?.cancel()
        if (!current.hasKey) {
            change { it.copy(models = ModelList.Loaded(emptyList())) }
            return
        }
        change { it.copy(models = ModelList.Loading) }
        modelsJob = viewModelScope.launch {
            val models = try {
                ModelList.Loaded(app.checker.models(provider))
            } catch (_: CheckFailure) {
                ModelList.Failed
            }
            change { if (it.provider == provider) it.copy(models = models) else it }
        }
    }

    /** Applies [update] once the settings have loaded; returns the new state. Preference writes are asynchronous. */
    private fun change(update: (SettingsState) -> SettingsState): SettingsState? {
        val current = state ?: return null
        return update(current).also { state = it }
    }

    private companion object {
        /** The cache folder res/xml/file_paths.xml shares. */
        const val EXPORTS = "exports"
    }
}
