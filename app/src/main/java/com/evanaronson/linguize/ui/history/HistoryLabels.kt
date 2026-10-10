package com.evanaronson.linguize.ui.history

import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.history.Outcome
import com.evanaronson.linguize.history.SessionSummary
import com.evanaronson.linguize.ui.components.shortDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** When a check was made, as Recent shows it: "Today 14:02", "Yesterday", "9 Oct", or "9 Oct 2025" in another year. */
internal fun relativeDate(at: Long, now: Long, time: DateTimeFormatter, zone: ZoneId = ZoneId.systemDefault()): String {
    val moment = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (moment.toLocalDate()) {
        today -> "Today ${time.format(moment)}"
        today.minusDays(1) -> "Yesterday"
        else -> shortDate(at, now, zone)
    }
}

/** The first line with something on it, for a one-glance preview. */
internal fun firstLine(text: String): String = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

/**
 * What came of a check, in a few words: "3 fixes · 1 rewording · 2 taken", "Looks good",
 * "Couldn't check".
 */
internal val SessionSummary.statusLine: String
    get() = when {
        outcome == Outcome.Failed -> "Couldn't check"
        outcome == Outcome.Abandoned -> "Not finished"
        status == Verdict.Status.Unclear -> "Couldn't tell what it means"
        status == Verdict.Status.WrongLanguage -> "Another language"
        fixes + rewordings == 0 -> if (status == Verdict.Status.Ok) "Looks good" else "No suggestions"
        else -> listOfNotNull(
            count(fixes, "fix", "fixes"),
            count(rewordings, "rewording", "rewordings"),
            when {
                taken > 0 -> "$taken taken"
                outcome == Outcome.Copied -> "copied"
                else -> null
            },
        ).joinToString(" · ")
    }

private fun count(n: Int, one: String, many: String) = when (n) {
    0 -> null
    1 -> "1 $one"
    else -> "$n $many"
}
