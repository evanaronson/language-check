package com.evanaronson.linguize.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge cases in turning the model's texts into edits, each one a bug that was found and fixed. */
class EditsTest {
    private fun revision(original: String, corrected: String = "", natural: String = "", naturalChanges: List<VerdictChange> = emptyList()) =
        (
            interpret(
                original,
                Verdict(
                    status = Verdict.Status.Ok,
                    hasErrors = corrected.isNotEmpty(),
                    corrected = corrected,
                    moreNatural = natural.isNotEmpty(),
                    natural = natural,
                    naturalChanges = naturalChanges,
                ),
            ) as CheckResult.Reviewed
            ).revision

    private fun Revision.changes(kind: EditKind) = remaining(kind).map { it.from to it.replacement }

    @Test
    fun aWordAndACommaInsertedTogetherKeepTheirOrder() {
        val revision = revision("hola cómo estás", corrected = "hola amor, cómo estás")
        assertEquals("hola amor, cómo estás", revision.acceptAll(EditKind.Fix).workingText)
    }

    @Test
    fun hyphensAndMiddleDotsBelongToTheWord() {
        assertEquals(listOf("dirho" to "dir-ho"), revision("vull dirho ara", corrected = "vull dir-ho ara").changes(EditKind.Fix))
        assertEquals(listOf("colegi" to "col·legi"), revision("al colegi", corrected = "al col·legi").changes(EditKind.Fix))
    }

    @Test
    fun anEmojiIsOneCharacter() {
        assertEquals(listOf("😀" to "😂"), revision("hola 😀", corrected = "hola 😂").changes(EditKind.Fix))
    }

    @Test
    fun aRewordingInsertedBeforeAnInsertedFixGoesInFrontOfIt() {
        val revision = revision("que tal", corrected = "¿Qué tal?", natural = "Hola, ¿qué tal?")
        val expected = "Hola, ¿qué tal?"
        assertEquals(expected, revision.acceptAll(EditKind.Natural).acceptAll(EditKind.Fix).workingText)
        assertEquals(expected, revision.acceptAll(EditKind.Fix).acceptAll(EditKind.Natural).workingText)
    }

    @Test
    fun aRewordingInsertedAfterAnInsertedFixKeepsTheFix() {
        val revision = revision("gracias por todo", corrected = "Gracias por todo.", natural = "Gracias por todo. Un beso.")
        val expected = "Gracias por todo. Un beso."
        assertEquals(expected, revision.acceptAll(EditKind.Natural).acceptAll(EditKind.Fix).workingText)
        assertEquals(expected, revision.acceptAll(EditKind.Fix).acceptAll(EditKind.Natural).workingText)
    }

    @Test
    fun changesToSpacingAloneAreNotSuggested() {
        assertEquals(listOf("" to "?"), revision("hola  que tal", corrected = "hola que tal?").changes(EditKind.Fix))
        assertTrue(revision("hola\n\nque tal", corrected = "hola\nque tal").edits(EditKind.Fix).isEmpty())
    }

    @Test
    fun aDeletedWordIsShownWithItsNeighbour() {
        val fixes = revision("hola de de amigos", corrected = "hola  de amigos").remaining(EditKind.Fix)
        assertTrue(fixes.isNotEmpty())
        assertTrue(fixes.all { it.replacement.isNotEmpty() })
    }

    @Test
    fun aReportedChangeThatChangesNothingIsIgnored() {
        val revision = revision(
            "Voy a tomar una ducha y te llamo despues",
            corrected = "Voy a tomar una ducha y te llamo después",
            natural = "Me voy a duchar y te llamo después",
            naturalChanges = listOf(VerdictChange("Voy a tomar una ducha", "Me voy a duchar"), VerdictChange("después", "después")),
        )
        assertEquals(listOf("Voy a tomar una ducha" to "Me voy a duchar"), revision.changes(EditKind.Natural))
    }

    @Test
    fun aFixReplacedByARewordingIsntCountedAsApplied() {
        val revision = revision("Com estas amb la pluja?", corrected = "Com estàs amb la pluja?", natural = "Com portes la pluja?")
        val both = revision.acceptAll(EditKind.Fix).acceptAll(EditKind.Natural)
        assertEquals("Com portes la pluja?", both.workingText)
        assertEquals(1, both.acceptedCount)
    }
}
