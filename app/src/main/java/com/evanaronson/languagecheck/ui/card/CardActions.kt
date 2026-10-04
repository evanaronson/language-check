package com.evanaronson.languagecheck.ui.card

import com.evanaronson.languagecheck.review.EditKind

/** What the card's buttons do; supplied by the screen hosting it. */
class CardActions(
    val onCopy: (String) -> Unit,
    /** Null when the selected text can't be replaced; the card then offers only Copy. */
    val review: ReviewActions?,
    val onRetry: () -> Unit,
    val onOpenSettings: () -> Unit,
)

class ReviewActions(
    val onAccept: (editId: Int) -> Unit,
    val onAcceptAll: (EditKind) -> Unit,
    val onUndo: () -> Unit,
    /** Closes the card, handing back the accepted changes. */
    val onDone: () -> Unit,
)
