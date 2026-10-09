package com.evanaronson.linguize.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.Outcome
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.ResultCard
import com.evanaronson.linguize.ui.card.title
import com.evanaronson.linguize.ui.components.BackButton
import com.evanaronson.linguize.ui.components.shortDate
import java.time.Instant
import java.time.ZoneId

/**
 * One past check: when and where, the card as it was (read-only), what went back to
 * the app, and Delete.
 */
@Composable
fun SessionScreen(session: SessionViewModel, onBack: () -> Unit) {
    Column(
        Modifier
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackButton(onBack)
        when (val page = session.page) {
            SessionPage.Loading -> Unit
            SessionPage.Missing -> Faint("This check is no longer kept.")
            is SessionPage.Shown -> Shown(
                page,
                onDelete = {
                    session.delete()
                    onBack()
                },
            )
        }
    }
}

@Composable
private fun Shown(page: SessionPage.Shown, onDelete: () -> Unit) {
    val session = page.session
    Header(session, page.appLabel)

    val card = page.card
    // The card shows the text itself only when it has suggestions to show in it.
    val cardShowsText = (card?.result as? CheckResult.Reviewed)?.looksGood == false
    if (!cardShowsText) TextBlock("Checked", session.text)
    if (card != null) {
        ReadOnlyCard(card)
    } else {
        Text(
            page.failure?.let { "Couldn't check · ${it.title}" } ?: "Couldn't check",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    WhatCameOfIt(session)
    DeleteButton(onDelete)
}

@Composable
private fun Header(session: SessionRecord, appLabel: String?) {
    val time = rememberTimeFormat()
    val started = Instant.ofEpochMilli(session.startedAt).atZone(ZoneId.systemDefault())
    Column {
        Text("${shortDate(session.startedAt)}, ${time.format(started)}", style = MaterialTheme.typography.titleLarge)
        source(session.origin, appLabel)?.let { Faint(it) }
    }
}

/**
 * The card's own content, with nothing to act on: no Replace, and assumptions can't be
 * changed. Copy only copies; no check stands behind the card, so it can't reach the
 * model or history.
 */
@Composable
private fun ReadOnlyCard(card: CardState.Done) {
    val context = LocalContext.current
    val actions = remember(context) { CardActions.readOnly(context) }
    ResultCard(
        state = card,
        actions = actions,
        modifier = Modifier.fillMaxWidth(),
        scrollable = false,
    )
}

@Composable
private fun WhatCameOfIt(session: SessionRecord) {
    val finalText = session.finalText
    if (finalText != null) {
        TextBlock(if (session.origin == Origin.Tester) "Applied" else "Sent", finalText)
        return
    }
    when (session.outcome) {
        Outcome.Copied -> "Copied; nothing went back to the app."
        Outcome.None -> "Closed without changes."
        Outcome.Abandoned -> "Linguize stopped while this card was open."
        Outcome.Applied, Outcome.Failed, null -> null
    }?.let { Faint(it) }
}

@Composable
private fun DeleteButton(onDelete: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    TextButton(
        onClick = { confirming = true },
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text("Delete") }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete this check?") },
            text = { Text("It's removed from this phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

/** A labelled text, set apart the way the card shows the meaning. */
@Composable
private fun TextBlock(label: String, text: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun Faint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
