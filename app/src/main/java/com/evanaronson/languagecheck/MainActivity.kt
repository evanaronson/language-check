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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.evanaronson.languagecheck.check.CheckFailure
import com.evanaronson.languagecheck.check.interpret
import com.evanaronson.languagecheck.ui.AppTheme
import com.evanaronson.languagecheck.ui.CardActions
import com.evanaronson.languagecheck.ui.CardState
import com.evanaronson.languagecheck.ui.ResultCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Launcher screen: set the API key once and try a check without leaving the app. */
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

@Composable
private fun Settings(app: App) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(false) }
    var sample by remember { mutableStateOf("Bon dia! Com estas amb la pluja?") }
    var result by remember { mutableStateOf<CardState?>(null) }
    var checked by remember { mutableStateOf("") }

    // Keystore access is slow enough to keep off the main thread.
    LaunchedEffect(Unit) {
        hasKey = withContext(Dispatchers.IO) { app.keys.geminiKey() != null }
    }

    fun runCheck() {
        val text = sample.trim()
        if (text.isEmpty()) return
        checked = text
        result = CardState.Loading
        scope.launch {
            result = try {
                CardState.Done(interpret(text, withContext(Dispatchers.IO) { app.checker().check(text) }))
            } catch (failure: CheckFailure) {
                CardState.Failed(failure.reason)
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
        Text("Language Check", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Select Catalan or Spanish text in any app, then tap Check in the selection menu " +
                "(it may be under ⋮).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        Text("Gemini API key", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(if (hasKey) "Saved — paste to replace" else "Paste key") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = key.isNotBlank(),
                onClick = {
                    val value = key
                    scope.launch {
                        withContext(Dispatchers.IO) { app.keys.setGeminiKey(value) }
                        hasKey = true
                        key = ""
                    }
                },
            ) { Text("Save") }
            TextButton(onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")),
                )
            }) { Text("Get a key") }
        }

        Spacer(Modifier.height(8.dp))
        Text("Try it", style = MaterialTheme.typography.titleMedium)
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
                            .setPrimaryClip(ClipData.newPlainText("Language Check", it))
                    },
                    onReplace = { sample = it },
                    onRetry = ::runCheck,
                    onOpenSettings = {},
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
