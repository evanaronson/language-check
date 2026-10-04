package com.evanaronson.languagecheck.ui.card

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.evanaronson.languagecheck.review.Assumption
import com.evanaronson.languagecheck.review.Settled

/** The quiet line at the top of the card that leads to the assumptions; nothing when there are none. */
@Composable
internal fun AssumptionsEntry(count: Int, onOpen: () -> Unit) {
    if (count == 0) return
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onOpen)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "ⓘ  " + if (count == 1) "1 assumption" else "$count assumptions",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * What the check assumed where the text was ambiguous, shown in place of the
 * suggestions. Each assumption is presented as right; alternatives appear only
 * after "Not right", and picking one checks again with that answer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AssumptionsPage(
    assumptions: List<Assumption>,
    settled: List<Settled>,
    onSettle: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    val open = assumptions.filterNot { a -> settled.any { it.about == a.about } }
    var changing by remember { mutableStateOf<String?>(null) }

    TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("‹  Back") }
    Text("Assumptions", style = MaterialTheme.typography.titleMedium)
    Text(
        "Where your text could be read more than one way, this is how it was read.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    settled.forEachIndexed { index, answer ->
        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(answer.about)
                Text(answer.answer, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
            Text("Yours ✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
    open.forEachIndexed { index, assumption ->
        if (index > 0 || settled.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.padding(vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label(assumption.about)
                    Text(assumption.assumed, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    if (assumption.words.isNotBlank()) {
                        Text(
                            "“${assumption.words}”",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (assumption.alternatives.isNotEmpty() && changing != assumption.about) {
                    TextButton(onClick = { changing = assumption.about }) { Text("Not right") }
                }
            }
            if (changing == assumption.about) {
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    assumption.alternatives.forEach { alternative ->
                        SuggestionChip(onClick = { onSettle(assumption.about, alternative) }, label = { Text(alternative) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
