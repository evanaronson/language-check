package com.evanaronson.languagecheck.ui.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanaronson.languagecheck.llm.CheckFailure.Reason
import com.evanaronson.languagecheck.review.CheckResult

/** The check result: loading, what was found, or why it failed. */
@Composable
fun ResultCard(
    state: CardState,
    actions: CardActions,
    modifier: Modifier = Modifier,
    /** Scroll inside the card; false when the card already sits in a scrolling screen. */
    scrollable: Boolean = true,
) {
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        val scroll = rememberScrollState()
        Box {
            Column(
                Modifier
                    .then(if (scrollable) Modifier.verticalScroll(scroll) else Modifier)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                when (state) {
                    is CardState.Loading -> Loading(state.text)
                    is CardState.Failed -> Failure(state.reason, state.detail, actions)
                    is CardState.Done -> when (val result = state.result) {
                        is CheckResult.Reviewed -> ReviewContent(result, actions)
                        CheckResult.Unclear -> Verdict(Mark.Unsure, "Can't tell what this means")
                        is CheckResult.WrongLanguage ->
                            Verdict(Mark.Unsure, "Not ${result.expected}", "Change the language in settings")
                    }
                }
            }
            // Fades out the bottom edge while there's more to scroll to.
            if (scrollable && scroll.canScrollForward) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(32.dp)
                        .background(Brush.verticalGradient(listOf(container.copy(alpha = 0f), container))),
                )
            }
        }
    }
}

@Composable
private fun Loading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(14.dp))
    LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun Failure(reason: Reason, providerMessage: String?, actions: CardActions) {
    val (title, hint) = when (reason) {
        Reason.NoKey -> "Add an API key" to null
        Reason.BadKey -> "API key rejected" to "Check it in settings"
        Reason.BadModel -> "This model can't be used" to "Pick another in settings"
        Reason.Offline -> "No connection" to null
        Reason.Timeout -> "Took too long" to null
        Reason.RateLimited -> "Rate limited" to "Try again in a moment"
        Reason.Server -> "The model isn't responding" to null
        Reason.BadResponse -> "Couldn't read the answer" to null
        Reason.TooLong -> "Selection too long" to "Select up to about a page of text"
    }
    Verdict(Mark.Problem, title, hint)
    if (providerMessage != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            providerMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.height(12.dp))
    when (reason) {
        Reason.NoKey, Reason.BadKey, Reason.BadModel ->
            FilledTonalButton(onClick = actions.onOpenSettings) { Text("Open settings") }
        Reason.TooLong -> Unit
        else -> FilledTonalButton(onClick = actions.onRetry) { Text("Retry") }
    }
}
