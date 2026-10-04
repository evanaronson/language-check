package com.evanaronson.languagecheck.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanaronson.languagecheck.check.CheckFailure
import com.evanaronson.languagecheck.check.CheckResult
import com.evanaronson.languagecheck.check.Suggestion

sealed interface CardState {
    data object Loading : CardState
    data class Done(val result: CheckResult) : CardState
    data class Failed(val reason: CheckFailure.Reason) : CardState
}

class CardActions(
    val onCopy: (String) -> Unit,
    /** Null when the selected text can't be replaced (read-only selection). */
    val onReplace: ((String) -> Unit)?,
    val onRetry: () -> Unit,
    val onOpenSettings: () -> Unit,
)

@Composable
fun ResultCard(original: String, state: CardState, actions: CardActions, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            when (state) {
                CardState.Loading -> Loading(original)
                is CardState.Done -> Result(state.result, actions)
                is CardState.Failed -> Failure(state.reason, actions)
            }
        }
    }
}

@Composable
private fun Loading(original: String) {
    Text(
        original,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(14.dp))
    LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun Result(result: CheckResult, actions: CardActions) {
    when (result) {
        CheckResult.AllGood -> Verdict(Mark.Good, "Looks good", "No fixes · Sounds natural")
        CheckResult.Unclear -> Verdict(Mark.Unsure, "Can't tell what this means")
        CheckResult.NotSupported -> Verdict(Mark.Unsure, "Not Catalan or Spanish")
        is CheckResult.Feedback -> Feedback(result, actions)
    }
}

@Composable
private fun Feedback(result: CheckResult.Feedback, actions: CardActions) {
    val correction = result.correction
    if (correction == null) {
        StatusLine(Mark.Good, "No fixes")
    } else {
        val label = if (correction.edits == 1) "1 fix" else "${correction.edits} fixes"
        SuggestionBlock(label, correction, MaterialTheme.colorScheme.error, actions)
    }

    Spacer(Modifier.height(14.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(14.dp))

    val natural = result.natural
    if (natural == null) {
        StatusLine(Mark.Good, "Sounds natural")
    } else {
        SuggestionBlock("More natural", natural, MaterialTheme.colorScheme.primary, actions)
    }
}

@Composable
private fun SuggestionBlock(label: String, suggestion: Suggestion, accent: Color, actions: CardActions) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = accent)
    Spacer(Modifier.height(6.dp))
    Text(highlighted(suggestion, accent), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { actions.onCopy(suggestion.text) }) { Text("Copy") }
        actions.onReplace?.let { replace ->
            FilledTonalButton(onClick = { replace(suggestion.text) }) { Text("Replace") }
        }
    }
}

private fun highlighted(suggestion: Suggestion, accent: Color): AnnotatedString = buildAnnotatedString {
    append(suggestion.text)
    val style = SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)
    for (range in suggestion.changed) {
        addStyle(style, range.first, range.last + 1)
    }
}

@Composable
private fun Failure(reason: CheckFailure.Reason, actions: CardActions) {
    val (title, detail) = when (reason) {
        CheckFailure.Reason.NoKey -> "Add an API key" to null
        CheckFailure.Reason.BadKey -> "API key rejected" to "Check it in settings"
        CheckFailure.Reason.Offline -> "No connection" to null
        CheckFailure.Reason.Timeout -> "Took too long" to null
        CheckFailure.Reason.RateLimited -> "Rate limited" to "Try again in a moment"
        CheckFailure.Reason.Server -> "The model isn't responding" to null
        CheckFailure.Reason.BadResponse -> "Couldn't read the answer" to null
    }
    Verdict(Mark.Problem, title, detail)
    Spacer(Modifier.height(12.dp))
    val needsSettings = reason == CheckFailure.Reason.NoKey || reason == CheckFailure.Reason.BadKey
    if (needsSettings) {
        FilledTonalButton(onClick = actions.onOpenSettings) { Text("Open settings") }
    } else {
        FilledTonalButton(onClick = actions.onRetry) { Text("Retry") }
    }
}

private enum class Mark { Good, Unsure, Problem }

@Composable
private fun Verdict(mark: Mark, title: String, detail: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MarkBadge(mark, 36)
        Spacer(Modifier.size(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(mark: Mark, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MarkBadge(mark, 22)
        Spacer(Modifier.size(10.dp))
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun MarkBadge(mark: Mark, sizeDp: Int) {
    val colors = MaterialTheme.colorScheme
    val (glyph, background, foreground) = when (mark) {
        Mark.Good -> Triple("✓", colors.primaryContainer, colors.onPrimaryContainer)
        Mark.Unsure -> Triple("?", colors.secondaryContainer, colors.onSecondaryContainer)
        Mark.Problem -> Triple("!", colors.errorContainer, colors.onErrorContainer)
    }
    Surface(shape = CircleShape, color = background, modifier = Modifier.size(sizeDp.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                glyph,
                color = foreground,
                style = if (sizeDp > 30) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
            )
        }
    }
}
