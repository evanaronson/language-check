package com.evanaronson.linguize.ui.history

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.ui.card.CardState

/**
 * The card as the session's last successful answer showed it, for reading. [result] is
 * that answer as `Checker.replay` rebuilds it, with the suggestions the writer took
 * applied; this adds what only the page needs: the writer's answers to assumptions, and
 * assumptions shown without the way to change them, since a past check can't be asked again.
 */
internal fun readOnlyCard(session: SessionRecord, result: CheckResult): CardState.Done {
    val settled = session.attempts.lastOrNull { it.verdict != null }?.settled.orEmpty()
    val shown = when (result) {
        is CheckResult.Reviewed -> result.copy(assumptions = result.assumptions.map { it.copy(alternatives = emptyList()) })
        else -> result
    }
    return CardState.Done(shown, settled.map { Settled(it.about, it.answer) })
}
