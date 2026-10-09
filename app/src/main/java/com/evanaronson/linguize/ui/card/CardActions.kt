package com.evanaronson.linguize.ui.card

import android.content.Context
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.ui.copyToClipboard

/** What the card's buttons do; supplied by the screen hosting it. */
class CardActions(
    /** Copies the version shown for a kind: its text, and which kind it was. */
    val onCopy: (text: String, kind: EditKind) -> Unit,
    /** Null when the selected text can't be replaced; the card then offers only Copy. */
    val review: ReviewActions?,
    val onRetry: () -> Unit,
    /** Null where the card is already shown in settings. */
    val onOpenSettings: (() -> Unit)?,
    /** Overrides an assumption (by its "about") with the writer's answer and checks again. */
    val onSettle: (about: String, answer: String) -> Unit,
) {
    companion object {
        /**
         * The same behaviour wherever the card is shown: Copy copies the version on
         * screen and closes; closing in any way ([onClose]) hands back accepted changes.
         */
        fun of(
            check: CheckViewModel,
            context: Context,
            onClose: () -> Unit,
            onOpenSettings: (() -> Unit)?,
            canReplace: Boolean = true,
        ) = CardActions(
            onCopy = { text, kind ->
                context.copyToClipboard(text)
                check.copied(kind)
                onClose()
            },
            review = if (canReplace) {
                ReviewActions(
                    onAccept = check::accept,
                    onAcceptAll = { if (check.acceptAll(it)) onClose() },
                    onUndo = check::undo,
                    onDone = onClose,
                )
            } else {
                null
            },
            onRetry = check::retry,
            onOpenSettings = onOpenSettings,
            onSettle = check::settle,
        )
    }
}

class ReviewActions(
    val onAccept: (editId: Int) -> Unit,
    val onAcceptAll: (EditKind) -> Unit,
    val onUndo: () -> Unit,
    /** Closes the card, handing back the accepted changes. */
    val onDone: () -> Unit,
)
