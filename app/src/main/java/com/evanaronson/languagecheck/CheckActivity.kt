package com.evanaronson.languagecheck

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.evanaronson.languagecheck.check.CheckFailure
import com.evanaronson.languagecheck.ui.AppTheme
import com.evanaronson.languagecheck.ui.CardActions
import com.evanaronson.languagecheck.ui.CardState
import com.evanaronson.languagecheck.ui.ResultCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The "Check" entry in the text-selection menu. Shows a floating card over
 * the app the text came from; never looks like a separate app.
 */
class CheckActivity : ComponentActivity() {
    private var state by mutableStateOf<CardState>(CardState.Loading)
    private var job: Job? = null
    private lateinit var text: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            finish()
            return
        }
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        // Start the request before the first frame is drawn.
        startCheck()

        val actions = CardActions(
            onCopy = ::copy,
            onReplace = if (readOnly) null else ::replace,
            onRetry = ::startCheck,
            onOpenSettings = {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            },
        )
        setContent {
            AppTheme {
                BoxWithConstraints(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = ::finish,
                        )
                        .safeDrawingPadding()
                        .padding(16.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    ResultCard(
                        original = text,
                        state = state,
                        actions = actions,
                        modifier = Modifier
                            .widthIn(max = 520.dp)
                            .fillMaxWidth()
                            // Long text scrolls inside the card; keep some of the app visible to tap away.
                            .heightIn(max = maxHeight * 0.85f)
                            // Taps on the card itself shouldn't dismiss it.
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                            ),
                    )
                }
            }
        }
    }

    private fun startCheck() {
        job?.cancel()
        state = CardState.Loading
        val app = application as App
        job = lifecycleScope.launch {
            state = try {
                CardState.Done(app.check(text))
            } catch (failure: CheckFailure) {
                CardState.Failed(failure.reason, failure.detail)
            }
        }
    }

    private fun copy(value: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Linguize", value))
        // Android 13+ shows its own clipboard confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun replace(value: String) {
        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, value))
        finish()
    }
}
