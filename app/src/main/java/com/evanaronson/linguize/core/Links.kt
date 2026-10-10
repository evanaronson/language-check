package com.evanaronson.linguize.core

import java.text.Normalizer

/**
 * Keeps suggestions from adding links. The model's texts can be steered by
 * instructions hidden in the text it checks, such as a message someone else
 * wrote that the writer is replying to or trying to understand, and a "fix"
 * that slips in a web address is the most direct way to turn that into harm.
 * So no suggestion may put a link into the text that wasn't already there.
 */
internal object Links {
    /** How far a run of text around an edit is looked at, each way. */
    private const val REACH = 1000

    /**
     * [edits] without those that would add a link ("https://…", "www.…", "example.com")
     * to [original]: on their own, together with the other edits of their kind, or as the
     * rewordings together with the fixes those leave on offer.
     */
    fun withoutNew(original: String, edits: List<Edit>): List<Edit> {
        val known = fold(original)
        val fixes = edits.filter { it.kind == EditKind.Fix }
        val naturals = edits.filter { it.kind == EditKind.Natural }
        val together = naturals + fixes.filterNot { fix -> naturals.any { it.touches(fix) } }
        val linking = linking(original, known, fixes) + linking(original, known, naturals) + linking(original, known, together) +
            edits.filter { edit ->
                val text = before(original, edit.start) + edit.replacement + after(original, edit.end)
                hasNew(known, text)
            }.map { it.id }
        return edits.filterNot { it.id in linking }
    }

    /** Whether [text] holds a link that [original] doesn't. */
    fun hasNewLink(original: String, text: String) = hasNew(fold(original), text)

    /** Whether [text] holds a link that isn't in [known], the original text [fold]ed. */
    private fun hasNew(known: String, text: String) =
        LINK.findAll(text).any { !isAbbreviation(it.value) && fold(it.value) !in known }

    /** Short capitals joined by dots, like "EE.UU." or "RR.HH.", are abbreviations, not addresses. */
    private fun isAbbreviation(match: String) =
        match.split('.').all { it.length <= 2 && it.all(Char::isUpperCase) }

    /** Ids of [edits] that, applied together to [original], sit in a run of text without spaces holding a new link. */
    private fun linking(original: String, known: String, edits: List<Edit>): Set<Int> {
        val out = StringBuilder()
        val placed = mutableListOf<Triple<Int, Int, Int>>()
        var pos = 0
        for (edit in edits.sortedWith(editOrder)) {
            if (edit.start < pos) continue
            out.append(original, pos, edit.start)
            val start = out.length
            out.append(edit.replacement)
            placed += Triple(edit.id, start, out.length)
            pos = edit.end
        }
        out.append(original, pos, original.length)
        val text = out.toString()
        return placed
            .filter { (_, start, end) -> hasNew(known, before(text, start) + text.substring(start, end) + after(text, end)) }
            .map { it.first }
            .toSet()
    }

    /** The text without spaces that runs up to [at]. */
    private fun before(text: String, at: Int): String {
        var start = at
        while (start > 0 && at - start < REACH && !text[start - 1].isWhitespace()) start--
        return text.substring(start, at)
    }

    /** The text without spaces that runs on from [at]. */
    private fun after(text: String, at: Int): String {
        var end = at
        while (end < text.length && end - at < REACH && !text[end].isWhitespace()) end++
        return text.substring(at, end)
    }

    private const val LABEL = """[\p{L}\p{N}](?:[\p{L}\p{N}-]*[\p{L}\p{N}])?"""

    /** What a link's path may end with: not the punctuation of the sentence around it. */
    private const val PATH = """[^\s]*[^\s.,;:!?)\]}"'»…]"""

    /** A web address with a scheme or "www.", or anything shaped like a domain name. */
    private val LINK = Regex(
        """(?i)(?:[a-z][a-z0-9+.-]*://|www\.)$PATH""" +
            """|(?<![\p{L}\p{N}\-])$LABEL(?:\.$LABEL)*\.(?:\p{L}{2,24}|xn--[a-z0-9-]+)(?![\p{L}\p{N}\-])(?:[/:]$PATH)?""",
    )

    /** Lowercase without accents: a fix to the case or accents of words a dot joins ("estas.Bien") adds no link. */
    private fun fold(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
}
