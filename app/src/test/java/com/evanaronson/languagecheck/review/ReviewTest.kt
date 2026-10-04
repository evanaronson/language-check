package com.evanaronson.languagecheck.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewTest {
    private fun reviewed(original: String, verdict: Verdict) =
        interpret(original, verdict) as CheckResult.Reviewed

    private fun Revision.replacements(kind: EditKind) = remaining(kind).map { it.replacement }

    @Test
    fun everyPunctuationMarkAndWordIsItsOwnFix() {
        val original = "hola q tal bb estas bien te encanta esta musica no"
        val corrected = "hola, q tal, bb? Estás bien? Te encanta esta música, no?"
        val revision = reviewed(original, Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = corrected)).revision

        assertEquals(
            listOf(",", ",", "?", "Estás", "?", "Te", "música", ",", "?"),
            revision.replacements(EditKind.Fix),
        )
        assertEquals(corrected, revision.preview(EditKind.Fix).text)
    }

    @Test
    fun reasonsComeFromTheModelsList() {
        val revision = reviewed(
            "Com estas?",
            Verdict(
                status = Verdict.Status.Ok,
                hasErrors = true,
                corrected = "Com estàs?",
                fixes = listOf(VerdictChange("estas", "estàs", why = "Missing accent")),
            ),
        ).revision

        val fix = revision.remaining(EditKind.Fix).single()
        assertEquals("estas", fix.from)
        assertEquals("Missing accent", fix.why)
    }

    @Test
    fun fixesCanBeAcceptedInAnyOrderAndUndone() {
        val original = "unes tomàquets molt bo"
        val revision = reviewed(
            original,
            Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = "uns tomàquets molt bons"),
        ).revision
        val (first, second) = revision.remaining(EditKind.Fix)

        val secondOnly = revision.accept(second.id)
        assertEquals("unes tomàquets molt bons", secondOnly.workingText)
        assertEquals(listOf("uns"), secondOnly.replacements(EditKind.Fix))

        val both = secondOnly.accept(first.id)
        assertEquals("uns tomàquets molt bons", both.workingText)
        assertTrue(both.remaining(EditKind.Fix).isEmpty())

        assertEquals("unes tomàquets molt bons", both.undo().workingText)
        assertEquals(original, both.undo().undo().workingText)
    }

    @Test
    fun rewordingLeavesUnrelatedErrorsToTheFixes() {
        val original = "Voy a tomar una ducha y te llamo despues"
        val revision = reviewed(
            original,
            Verdict(
                status = Verdict.Status.Ok,
                hasErrors = true,
                corrected = "Voy a tomar una ducha y te llamo después",
                moreNatural = true,
                natural = "Me voy a duchar y te llamo despues",
                naturalChanges = listOf(VerdictChange("Voy a tomar una ducha", "Me voy a duchar", why = "More usual")),
            ),
        ).revision

        val natural = revision.remaining(EditKind.Natural).single()
        assertEquals("Voy a tomar una ducha", natural.from)
        assertEquals("More usual", natural.why)

        val reworded = revision.accept(natural.id)
        assertEquals("Me voy a duchar y te llamo despues", reworded.workingText)
        // The accent fix is elsewhere, so it's still on offer.
        assertEquals(listOf("después"), reworded.replacements(EditKind.Fix))
        assertEquals("Me voy a duchar y te llamo después", reworded.preview(EditKind.Fix).text)
    }

    @Test
    fun acceptingARewordingRetiresFixesInsideIt() {
        val original = "Bon dia! Com estas amb la pluja?"
        val revision = reviewed(
            original,
            Verdict(
                status = Verdict.Status.Ok,
                hasErrors = true,
                corrected = "Bon dia! Com estàs amb la pluja?",
                moreNatural = true,
                natural = "Bon dia! Com portes la pluja?",
                naturalChanges = listOf(VerdictChange("estas amb", "portes", why = "Usual way to say it")),
            ),
        ).revision
        val fix = revision.remaining(EditKind.Fix).single()
        val natural = revision.remaining(EditKind.Natural).single()

        // Rewording first: the fix inside it disappears.
        val reworded = revision.accept(natural.id)
        assertEquals("Bon dia! Com portes la pluja?", reworded.workingText)
        assertTrue(reworded.remaining(EditKind.Fix).isEmpty())

        // Fix first, then rewording: same result.
        val fixedThenReworded = revision.accept(fix.id).accept(natural.id)
        assertEquals("Bon dia! Com portes la pluja?", fixedThenReworded.workingText)

        // Undoing the rewording brings the accepted fix back into effect.
        assertEquals("Bon dia! Com estàs amb la pluja?", fixedThenReworded.undo().workingText)
    }

    @Test
    fun replaceAllIsOneUndoStep() {
        val original = "hola q tal bb"
        val revision = reviewed(
            original,
            Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = "hola, q tal, bb?"),
        ).revision

        val all = revision.acceptAll(EditKind.Fix)
        assertEquals("hola, q tal, bb?", all.workingText)
        assertEquals(original, all.undo().workingText)
        assertFalse(all.undo().canUndo)
    }

    @Test
    fun deletedWordsAreShownWithTheirNeighbour() {
        val revision = reviewed(
            "Ayer yo fui a la playa",
            Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = "Ayer fui a la playa"),
        ).revision

        val fix = revision.remaining(EditKind.Fix).single()
        assertEquals("yo fui", fix.from)
        assertEquals("fui", fix.replacement)
    }

    @Test
    fun insertedWordsDontCarryTheSpaceIntoTheHighlight() {
        val revision = reviewed(
            "Ahir vaig mercat",
            Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = "Ahir vaig al mercat"),
        ).revision

        val fix = revision.remaining(EditKind.Fix).single()
        val preview = revision.preview(EditKind.Fix)
        assertEquals("Ahir vaig al mercat", preview.text)
        assertEquals("al ", preview.text.substring(preview.ranges.getValue(fix.id)))
    }

    @Test
    fun onlyRequestedJudgmentsBecomeEdits() {
        val original = "Com estas amb la pluja?"
        val verdict = Verdict(
            status = Verdict.Status.Ok,
            hasErrors = true,
            corrected = "Com estàs amb la pluja?",
            moreNatural = true,
            natural = "Com portes la pluja?",
        )
        val fixOnly = interpret(original, verdict, Judgments.FixOnly) as CheckResult.Reviewed
        assertEquals(listOf(EditKind.Fix), fixOnly.kinds)
        assertTrue(fixOnly.revision.edits(EditKind.Natural).isEmpty())

        val naturalOnly = interpret(original, verdict, Judgments.NaturalizeOnly) as CheckResult.Reviewed
        assertEquals(listOf(EditKind.Natural), naturalOnly.kinds)
        assertTrue(naturalOnly.revision.edits(EditKind.Fix).isEmpty())

        val nothingToFix = interpret(original, verdict.copy(hasErrors = false), Judgments.FixOnly) as CheckResult.Reviewed
        assertTrue(nothingToFix.looksGood)
    }

    @Test
    fun aCorrectionIdenticalToTheOriginalLooksGood() {
        val original = "Ens veiem demà a les set?"
        val verdict = Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = " $original ")
        assertTrue((interpret(original, verdict) as CheckResult.Reviewed).looksGood)
    }

    @Test
    fun resolvedOnceEverySuggestionIsAcceptedOrOvertaken() {
        val result = interpret(
            "Bon dia! Com estas amb la pluja?",
            Verdict(
                status = Verdict.Status.Ok,
                hasErrors = true,
                corrected = "Bon dia! Com estàs amb la pluja?",
                moreNatural = true,
                natural = "Bon dia! Com portes la pluja?",
            ),
        ) as CheckResult.Reviewed
        assertFalse(result.isResolved)
        val reworded = result.copy(revision = result.revision.acceptAll(EditKind.Natural))
        assertTrue(reworded.isResolved)
    }

    @Test
    fun unclearAndWrongLanguage() {
        assertEquals(CheckResult.Unclear, interpret("x", Verdict(status = Verdict.Status.Unclear)))
        assertEquals(
            CheckResult.WrongLanguage("Catalan"),
            interpret("x", Verdict(status = Verdict.Status.WrongLanguage), expectedLanguage = "Catalan"),
        )
        // Without a chosen language there's nothing to be wrong about.
        assertEquals(CheckResult.Unclear, interpret("x", Verdict(status = Verdict.Status.WrongLanguage)))
    }
}
