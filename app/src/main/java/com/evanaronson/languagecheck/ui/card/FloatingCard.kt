package com.evanaronson.languagecheck.ui.card

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The card floating at the bottom of the screen over a dimmed view of the app
 * the text came from. Tapping outside the card calls [onDismiss].
 */
@Composable
fun FloatingCard(state: CardState, actions: CardActions, onDismiss: () -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .noRippleClickable(onDismiss)
            .safeDrawingPadding()
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        ResultCard(
            state = state,
            actions = actions,
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                // Long text scrolls inside the card; keep some of the app visible to tap away.
                .heightIn(max = maxHeight * 0.85f)
                // Taps on the card itself shouldn't dismiss it.
                .noRippleClickable {},
        )
    }
}

@Composable
private fun Modifier.noRippleClickable(onClick: () -> Unit) =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
