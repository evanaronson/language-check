package com.evanaronson.linguize.ui.history

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.core.interpret
import com.evanaronson.linguize.history.Decision
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SuggestionRecord
import com.evanaronson.linguize.ui.card.CardState
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Stored verdicts are read as leniently as they were when they arrived. */
private val json = Json { ignoreUnknownKeys = true }

/**
 * The card as the session's last successful answer showed it, for reading: the writer's
 * answers to assumptions, and the suggestions they took marked as applied. Null when no
 * attempt succeeded or the stored answer can't be read.
 */
internal fun rebuild(detail: SessionDetail): CardState.Done? {
    val session = detail.session
    val index = session.attempts.indexOfLast { it.verdict != null }.takeIf { it >= 0 } ?: return null
    val attempt = session.attempts[index]
    val raw = attempt.verdict ?: return null
    val verdict = try {
        json.decodeFromString<Verdict>(raw)
    } catch (_: SerializationException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    val result = when (val result = interpret(session.text, verdict, judgments(session.judgments), session.requestedLanguage)) {
        is CheckResult.Reviewed -> result.copy(
            revision = result.revision.acceptMatching(taken(detail.suggestions, index)),
            // A past check can't be asked again, so its assumptions are shown without the way to change them.
            assumptions = result.assumptions.map { it.copy(alternatives = emptyList()) },
        )
        else -> result
    }
    return CardState.Done(result, attempt.settled.map { Settled(it.about, it.answer) })
}

/** The suggestions from attempt [index] that ended up in the text, as edits to match against. */
private fun taken(suggestions: List<SuggestionRecord>, index: Int): List<Edit> = suggestions
    .filter { it.attempt == index && it.decision == Decision.Accepted }
    .mapNotNull { suggestion ->
        val kind = EditKind.entries.firstOrNull { it.name.equals(suggestion.kind, ignoreCase = true) } ?: return@mapNotNull null
        Edit(
            id = 0,
            kind = kind,
            start = suggestion.start,
            end = suggestion.end,
            from = suggestion.fromText,
            replacement = suggestion.toText,
            why = suggestion.why,
        )
    }

/** The judgments setting as stored on the session; by name, or the prompt's token. */
private fun judgments(stored: String): Judgments =
    Judgments.entries.firstOrNull { it.name.equals(stored, ignoreCase = true) } ?: when (stored.lowercase()) {
        "fix" -> Judgments.FixOnly
        "naturalize" -> Judgments.NaturalizeOnly
        else -> Judgments.Both
    }
