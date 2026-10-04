package com.evanaronson.languagecheck

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.evanaronson.languagecheck.ui.AppTheme
import com.evanaronson.languagecheck.ui.card.CardActions
import com.evanaronson.languagecheck.ui.card.CheckViewModel
import com.evanaronson.languagecheck.ui.card.FloatingCard
import com.evanaronson.languagecheck.ui.card.ReviewActions
import com.evanaronson.languagecheck.ui.copyToClipboard

/**
 * The "Check" entry in the text-selection menu (ACTION_PROCESS_TEXT). Floats
 * the result card over the app the text came from, and hands back the text
 * with any accepted changes when it closes.
 */
class CheckActivity : ComponentActivity() {
    private val check: CheckViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            finish()
            return
        }
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        // Start the request before the first frame is drawn.
        if (check.state == null) check.check(text)

        val actions = CardActions(
            onCopy = {
                copyToClipboard(it)
                finish()
            },
            review = if (readOnly) {
                null
            } else {
                ReviewActions(
                    onAccept = check::accept,
                    onAcceptAll = { if (check.acceptAll(it)) finish() },
                    onUndo = check::undo,
                    onDone = ::finish,
                )
            },
            onRetry = check::retry,
            onOpenSettings = {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            },
        )
        setContent {
            AppTheme {
                check.state?.let { FloatingCard(it, actions, onDismiss = ::finish) }
            }
        }
    }

    /** However the card closes (Done, back, tapping outside, Copy), accepted changes go back to the app. */
    override fun finish() {
        check.workingText?.let { setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, it)) }
        super.finish()
    }
}
