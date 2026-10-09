package com.evanaronson.linguize.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.ui.components.SectionTitle

/** How to turn on the accessibility button, for apps whose selection menu doesn't show Linguize. */
@Composable
internal fun OtherAppsSection() {
    val context = LocalContext.current
    SectionTitle("Apps without the menu")
    Text(
        "Some apps, like Telegram, don't show Linguize in their selection menu. Turn on the Linguize " +
            "accessibility button instead, then tap it while typing to check that text field.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "1. App info → ⋮ → Allow restricted settings (needed once, because the app wasn't installed from the Play Store).\n" +
            "2. Accessibility → Linguize → turn it on, with its shortcut.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            context.openFromSettings(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
            )
        }) { Text("App info") }
        OutlinedButton(onClick = {
            context.openFromSettings(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) { Text("Accessibility") }
    }
}

/**
 * Opens another app's screen from settings. Marked as not the user leaving, so a
 * card waiting behind settings stays hidden (see [com.evanaronson.linguize.ui.home.HomeActivity.onUserLeaveHint]).
 */
internal fun Context.openFromSettings(intent: Intent) =
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION))
