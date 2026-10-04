package com.evanaronson.languagecheck.check

import org.junit.Assert.assertEquals
import org.junit.Test

class WordDiffTest {
    private fun changedWords(old: String, new: String): List<String> =
        WordDiff.compare(old, new).changed.map { new.substring(it) }

    @Test
    fun identicalTextsHaveNoEdits() {
        assertEquals(0, WordDiff.compare("Hola, què tal?", "Hola, què tal?").edits)
    }

    @Test
    fun accentFixIsOneEdit() {
        val diff = WordDiff.compare("Com estas?", "Com estàs?")
        assertEquals(1, diff.edits)
        assertEquals(listOf("estàs?"), changedWords("Com estas?", "Com estàs?"))
    }

    @Test
    fun separateFixesAreCountedSeparately() {
        val old = "unes tomàquets i un poma"
        val new = "uns tomàquets i una poma"
        assertEquals(2, WordDiff.compare(old, new).edits)
        assertEquals(listOf("uns", "una"), changedWords(old, new))
    }

    @Test
    fun adjacentChangedWordsMergeIntoOneHighlight() {
        val old = "Com estàs amb la pluja?"
        val new = "Com portes la pluja?"
        val diff = WordDiff.compare(old, new)
        assertEquals(1, diff.edits)
        assertEquals(listOf("portes"), changedWords(old, new))
    }

    @Test
    fun deletionCountsAsAnEditWithoutAHighlight() {
        val diff = WordDiff.compare("Ayer yo fui a la playa", "Ayer fui a la playa")
        assertEquals(1, diff.edits)
        assertEquals(emptyList<IntRange>(), diff.changed)
    }
}
