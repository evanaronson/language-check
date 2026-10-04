package com.evanaronson.languagecheck.ui.card

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.evanaronson.languagecheck.review.CheckResult
import com.evanaronson.languagecheck.review.EditKind
import com.evanaronson.languagecheck.review.Revision
import com.evanaronson.languagecheck.review.Settled

/** Any assumptions, one section per judgment, then Undo and Done once something has been accepted. */
@Composable
internal fun ReviewContent(result: CheckResult.Reviewed, settled: List<Settled>, actions: CardActions) {
    // The assumptions are a page of their own: in from the right, back to the left.
    var showingAssumptions by remember(result.assumptions, settled) { mutableStateOf(false) }
    AnimatedContent(
        targetState = showingAssumptions,
        transitionSpec = {
            val direction = if (targetState) 1 else -1
            (slideInHorizontally { direction * it / 3 } + fadeIn()) togetherWith
                (slideOutHorizontally { -direction * it / 3 } + fadeOut())
        },
        label = "assumptions",
    ) { assumptionsPage ->
        Column {
            if (assumptionsPage) {
                AssumptionsPage(result.assumptions, settled, actions.onSettle, onBack = { showingAssumptions = false })
            } else {
                val count = settled.size + result.assumptions.count { a -> settled.none { it.about == a.about } }
                AssumptionsEntry(count, onOpen = { showingAssumptions = true })
                if (count > 0) Spacer(Modifier.height(8.dp))
                Suggestions(result, actions)
            }
        }
    }
}

@Composable
private fun Suggestions(result: CheckResult.Reviewed, actions: CardActions) {
    if (result.looksGood) {
        val checked = result.kinds.map { if (it == EditKind.Fix) "No fixes" else "Sounds natural" }
        Verdict(Mark.Good, "Looks good", checked.joinToString(" · "))
        return
    }

    val review = actions.review
    result.kinds.forEachIndexed { index, kind ->
        if (index > 0) SectionDivider()
        EditSection(kind, result.revision, actions.onCopy, review)
    }

    if (review != null && result.revision.canUndo) {
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val count = result.revision.acceptedCount
            Text(
                if (count == 1) "1 change applied" else "$count changes applied",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = review.onUndo) { Text("Undo") }
            FilledTonalButton(onClick = review.onDone) { Text("Done") }
        }
    }
}

@Composable
private fun EditSection(kind: EditKind, revision: Revision, onCopy: (String) -> Unit, review: ReviewActions?) {
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
            HighlightedText(preview.text, highlights, accent, review?.onAccept)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onCopy(preview.text) }) { Text("Copy") }
                if (review != null) {
                    FilledTonalButton(onClick = { review.onAcceptAll(kind) }) { Text("Replace all") }
                }
            }
        }
    }
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(14.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(14.dp))
}
