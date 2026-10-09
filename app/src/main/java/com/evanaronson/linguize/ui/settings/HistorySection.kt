package com.evanaronson.linguize.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.ui.components.SectionTitle
import com.evanaronson.linguize.ui.history.shortDate

/** Whether checks are remembered, how many are, and Clear and Export. Browsing them is on Home. */
@Composable
internal fun HistorySection(state: SettingsState, settings: SettingsViewModel) {
    val context = LocalContext.current
    val kept by settings.history.collectAsState()
    val count = kept?.count ?: 0
    var confirmingClear by rememberSaveable { mutableStateOf(false) }

    SectionTitle("History")
    SwitchRow("Remember what I check", checked = state.historyEnabled, onChange = settings::setHistoryEnabled)
    Text(
        "Kept on this phone only, so Linguize can later show you the mistakes you tend to make. " +
            "Turning this off keeps what's already here.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(kept?.let(::keptLabel).orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(enabled = count > 0, onClick = { confirmingClear = true }) { Text("Clear") }
        TextButton(
            enabled = count > 0 && !settings.exporting,
            onClick = { settings.exportHistory { uri -> share(context, uri) } },
        ) { Text("Export") }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("Clear history?") },
            text = { Text("Deletes ${checks(count)} kept on this phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClear = false
                    settings.clearHistory()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** "312 checks since 9 Oct". */
private fun keptLabel(kept: KeptHistory): String {
    val since = kept.since
    return if (kept.count == 0 || since == null) "Nothing kept yet" else "${checks(kept.count)} since ${shortDate(since)}"
}

private fun checks(count: Int) = if (count == 1) "1 check" else "$count checks"

/** Offers the exported file through the share sheet. */
private fun share(context: Context, uri: Uri?) {
    if (uri == null) {
        Toast.makeText(context, "Couldn't export history", Toast.LENGTH_SHORT).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri("", uri)
    context.openFromSettings(Intent.createChooser(send, "Export history"))
}
