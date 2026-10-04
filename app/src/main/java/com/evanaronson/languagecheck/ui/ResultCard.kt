package com.evanaronson.languagecheck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanaronson.languagecheck.check.CheckFailure
import com.evanaronson.languagecheck.check.CheckResult
import com.evanaronson.languagecheck.check.EditKind
import com.evanaronson.languagecheck.check.Revision

sealed interface CardState {
    data object Loading : CardState
    data class Done(val result: CheckResult) : CardState
    data class Failed(val reason: CheckFailure.Reason, val detail: String? = null) : CardState
}

class CardActions(
    val onCopy: (String) -> Unit,
    /**
     * Receives the text with accepted changes whenever that changes; it goes back
     * to the app when the card closes. Null when the selected text can't be replaced.
     */
    val onWorkingText: ((String) -> Unit)?,
    /** Closes the card, handing back the accepted changes. */
    val onDone: () -> Unit,
    val onRetry: () -> Unit,
    val onOpenSettings: () -> Unit,
)

@Composable
fun ResultCard(
    original: String,
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
                    CardState.Loading -> Loading(original)
                    is CardState.Done -> Result(state.result, actions)
                    is CardState.Failed -> Failure(state.reason, state.detail, actions)
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
        is CheckResult.AllGood -> Verdict(
            Mark.Good,
            "Looks good",
            listOfNotNull(
                "No fixes".takeIf { result.checkedFixes },
                "Sounds natural".takeIf { result.checkedNaturalness },
            ).joinToString(" · "),
        )
        CheckResult.Unclear -> Verdict(Mark.Unsure, "Can't tell what this means")
        is CheckResult.WrongLanguage -> Verdict(Mark.Unsure, "Not ${result.expected}", "Change the language in settings")
        is CheckResult.Feedback -> Feedback(result, actions)
    }
}

@Composable
private fun Feedback(result: CheckResult.Feedback, actions: CardActions) {
    var revision by remember(result) { mutableStateOf(result.revision) }
    val editable = actions.onWorkingText != null
    val kinds = listOfNotNull(
        EditKind.Fix.takeIf { result.checkedFixes },
        EditKind.Natural.takeIf { result.checkedNaturalness },
    )

    fun update(next: Revision) {
        revision = next
        actions.onWorkingText?.invoke(next.workingText)
    }

    kinds.forEachIndexed { index, kind ->
        if (index > 0) {
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(14.dp))
        }
        EditSection(
            kind = kind,
            revision = revision,
            onCopy = actions.onCopy,
            onReplace = if (editable) {
                { id -> update(revision.accept(id)) }
            } else {
                null
            },
            onReplaceAll = if (editable) {
                {
                    val next = revision.acceptAll(kind)
                    update(next)
                    // Nothing left to decide: hand the text back.
                    if (kinds.all { next.remaining(it).isEmpty() }) actions.onDone()
                }
            } else {
                null
            },
        )
    }

    if (editable && revision.canUndo) {
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val count = revision.acceptedCount
            Text(
                if (count == 1) "1 change applied" else "$count changes applied",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { update(revision.undo()) }) { Text("Undo") }
            FilledTonalButton(onClick = actions.onDone) { Text("Done") }
        }
    }
}

@Composable
private fun EditSection(
    kind: EditKind,
    revision: Revision,
    onCopy: (String) -> Unit,
    onReplace: ((Int) -> Unit)?,
    onReplaceAll: (() -> Unit)?,
) {
    val fix = kind == EditKind.Fix
    val remaining = revision.remaining(kind)
    when {
        revision.edits(kind).isEmpty() -> StatusLine(Mark.Good, if (fix) "No fixes" else "Sounds natural")
        remaining.isEmpty() -> StatusLine(Mark.Good, if (fix) "Fixes applied" else "Rewording applied")
        else -> {
            val accent = if (fix) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            val label = when {
                !fix -> "More natural"
                remaining.size == 1 -> "1 fix"
                else -> "${remaining.size} fixes"
            }
            val preview = revision.preview(kind)
            val highlights = remaining.mapNotNull { edit ->
                preview.ranges[edit.id]?.let { Highlight(edit.id, it, edit.from, edit.why) }
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = accent)
            Spacer(Modifier.height(6.dp))
            HighlightedText(preview.text, highlights, accent, onReplace)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onCopy(preview.text) }) { Text("Copy") }
                onReplaceAll?.let { FilledTonalButton(onClick = it) { Text("Replace all") } }
            }
        }
    }
}

@Composable
private fun Failure(reason: CheckFailure.Reason, providerMessage: String?, actions: CardActions) {
    val (title, detail) = when (reason) {
        CheckFailure.Reason.NoKey -> "Add an API key" to null
        CheckFailure.Reason.BadKey -> "API key rejected" to "Check it in settings"
        CheckFailure.Reason.BadModel -> "This model can't be used" to "Pick another in settings"
        CheckFailure.Reason.Offline -> "No connection" to null
        CheckFailure.Reason.Timeout -> "Took too long" to null
        CheckFailure.Reason.RateLimited -> "Rate limited" to "Try again in a moment"
        CheckFailure.Reason.Server -> "The model isn't responding" to null
        CheckFailure.Reason.BadResponse -> "Couldn't read the answer" to null
        CheckFailure.Reason.TooLong -> "Selection too long" to "Select up to about a page of text"
    }
    Verdict(Mark.Problem, title, detail)
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
    val needsSettings = reason in setOf(CheckFailure.Reason.NoKey, CheckFailure.Reason.BadKey, CheckFailure.Reason.BadModel)
    if (needsSettings) {
        FilledTonalButton(onClick = actions.onOpenSettings) { Text("Open settings") }
    } else if (reason != CheckFailure.Reason.TooLong) {
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
