package com.evanaronson.languagecheck.ui.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.evanaronson.languagecheck.App
import com.evanaronson.languagecheck.llm.CheckFailure
import com.evanaronson.languagecheck.llm.Provider
import com.evanaronson.languagecheck.review.Judgments
import com.evanaronson.languagecheck.review.Language
import com.evanaronson.languagecheck.review.Punctuation
import com.evanaronson.languagecheck.settings.KeyStatus
import com.evanaronson.languagecheck.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsState(
    val language: Language? = null,
    val punctuation: Punctuation = Punctuation.Moderate,
    val judgments: Judgments = Judgments.Both,
    val provider: Provider = Provider.Gemini,
    val keys: Map<Provider, KeyStatus> = emptyMap(),
    /** The chosen model, or null for the provider's recommended one. */
    val model: String? = null,
    val models: ModelList = ModelList.Loaded(emptyList()),
    val modelTest: ModelTest? = null,
) {
    val hasKey get() = keys[provider] is KeyStatus.Saved
}

/** The provider's models: loading, loaded, or failed to load. */
sealed interface ModelList {
    data object Loading : ModelList
    data class Loaded(val models: List<String>) : ModelList
    data object Failed : ModelList
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

    var state by mutableStateOf(SettingsState())
        private set

    init {
        viewModelScope.launch {
            state = withContext(Dispatchers.IO) {
                val settings = app.settings
                SettingsState(
                    language = settings.language,
                    punctuation = settings.punctuation,
                    judgments = settings.judgments,
                    provider = settings.provider,
                    keys = Provider.entries.associateWith { app.keys.status(it) },
                    model = settings.model(settings.provider),
                )
            }
            loadModels()
        }
    }

    fun setLanguage(language: Language?) {
        state = state.copy(language = language)
        save { this.language = language }
    }

    fun setPunctuation(punctuation: Punctuation) {
        state = state.copy(punctuation = punctuation)
        save { this.punctuation = punctuation }
    }

    fun setJudgments(judgments: Judgments) {
        state = state.copy(judgments = judgments)
        save { this.judgments = judgments }
    }

    fun selectProvider(provider: Provider) {
        state = state.copy(provider = provider, model = null, modelTest = null)
        viewModelScope.launch {
            val model = withContext(Dispatchers.IO) {
                app.settings.provider = provider
                app.settings.model(provider)
            }
            state = state.copy(model = model)
            loadModels()
        }
    }

    /** Null selects the recommended model. Picking a model tests it straight away. */
    fun selectModel(model: String?) {
        val provider = state.provider
        state = state.copy(model = model)
        save { setModel(provider, model) }
        if (state.hasKey) testModel()
    }

    fun testModel() {
        val provider = state.provider
        val model = state.model ?: provider.recommendedModel
        testJob?.cancel()
        state = state.copy(modelTest = ModelTest.Testing)
        testJob = viewModelScope.launch {
            val result = try {
                ModelTest.Works(app.checks.testModel(provider, model))
            } catch (failure: CheckFailure) {
                ModelTest.Failed(failure.detail ?: failure.reason.name)
            }
            if (state.provider == provider) state = state.copy(modelTest = result)
        }
    }

    /** Saves the key for the current provider; a blank value removes it. */
    fun saveKey(value: String) {
        val provider = state.provider
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                app.keys.set(provider, value)
                app.keys.status(provider)
            }
            state = state.copy(keys = state.keys + (provider to status), modelTest = null)
            if (state.provider == provider) loadModels()
        }
    }

    fun removeKey() = saveKey("")

    private fun loadModels() {
        val provider = state.provider
        modelsJob?.cancel()
        if (!state.hasKey) {
            state = state.copy(models = ModelList.Loaded(emptyList()))
            return
        }
        state = state.copy(models = ModelList.Loading)
        modelsJob = viewModelScope.launch {
            val models = try {
                ModelList.Loaded(app.checks.models(provider))
            } catch (_: CheckFailure) {
                ModelList.Failed
            }
            if (state.provider == provider) state = state.copy(models = models)
        }
    }

    private fun save(write: Settings.() -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { app.settings.write() }
    }
}
