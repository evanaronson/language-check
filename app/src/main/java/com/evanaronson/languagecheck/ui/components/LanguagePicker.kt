package com.evanaronson.languagecheck.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.languagecheck.MenuEntry

/**
 * "Linguize ▾": picks the language to check as. The list opens inline below the
 * button rather than in a popup window, so it also works in the accessibility
 * overlay, where popup windows can't attach. Opening it scrolls the list into view.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LanguagePicker(selected: MenuEntry, onSelect: (MenuEntry) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val list = remember { BringIntoViewRequester() }
    LaunchedEffect(open) {
        if (open) {
            // Wait for the list to be laid out, then scroll the screen so all of it shows.
            withFrameNanos {}
            list.bringIntoView()
        }
    }
    Column(modifier) {
        FilledTonalButton(onClick = { open = !open }) {
            Text(stringResource(selected.label))
            Spacer(Modifier.width(8.dp))
            Text(if (open) "▴" else "▾")
        }
        if (open) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shadowElevation = 6.dp,
                modifier = Modifier.padding(top = 4.dp).widthIn(min = 220.dp).bringIntoViewRequester(list),
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    MenuEntry.entries.forEach { entry ->
                        Row(
                            Modifier
                                .clickable {
                                    open = false
                                    onSelect(entry)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.widthIn(min = 160.dp)) {
                                Text(stringResource(entry.label), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    entry.language?.name ?: "Detects the language",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            Text(if (entry == selected) "✓" else " ", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
