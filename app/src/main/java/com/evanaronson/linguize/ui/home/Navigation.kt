package com.evanaronson.linguize.ui.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** The launcher activity's screens: few enough for a state and a back handler, no navigation library. */
internal sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen

    /** A past check. */
    data class Detail(val id: String) : Screen

    /** For saved instance state. */
    val saved: String
        get() = when (this) {
            Home -> "home"
            Settings -> "settings"
            is Detail -> "detail:$id"
        }

    companion object {
        fun restore(saved: String?): Screen? = when {
            saved == null -> null
            saved == "settings" -> Settings
            saved.startsWith("detail:") -> Detail(saved.removePrefix("detail:"))
            else -> Home
        }
    }
}

/** Back to the previous screen, in the card's style ("‹  Back" on the understanding page). */
@Composable
internal fun BackButton(onBack: () -> Unit) {
    TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("‹  Back") }
}
