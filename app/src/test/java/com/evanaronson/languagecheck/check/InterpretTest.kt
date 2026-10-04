package com.evanaronson.languagecheck.check

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpretTest {
    private fun feedback(original: String, verdict: ModelVerdict) =
        interpret(original, verdict) as CheckResult.Feedback

    private fun Revision.replacements(kind: EditKind) = remaining(kind).map { it.replacement }

    @Test
    fun everyPunctuationMarkAndWordIsItsOwnFix() {
        val original = "hola q tal bb estas bien te encanta esta musica no"
        val corrected = "hola, q tal, bb? Estás bien? Te encanta esta música, no?"
        val revision = feedback(original, ModelVerdict(status = "ok", has_errors = true, corrected = corrected)).revision

        assertEquals(
            listOf(",", ",", "?", "Estás", "?", "Te", "música", ",", "?"),
            revision.replacements(EditKind.Fix),
        )
        assertEquals(corrected, revision.preview(EditKind.Fix).text)
    }

    @Test
    fun reasonsComeFromTheModelsList() {
        val revision = feedback(
            "Com estas?",
            ModelVerdict(
                status = "ok",
                has_errors = true,
                corrected = "Com estàs?",
                fixes = listOf(ModelChange("estas", "estàs", why = "Missing accent")),
            ),
        ).revision

        val fix = revision.remaining(EditKind.Fix).single()
        assertEquals("estas", fix.from)
        assertEquals("Missing accent", fix.why)
    }

    @Test
    fun fixesCanBeAcceptedInAnyOrderAndUndone() {
        val original = "unes tomàquets molt bo"
        val revision = feedback(
            original,
            ModelVerdict(status = "ok", has_errors = true, corrected = "uns tomàquets molt bons"),
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
        val revision = feedback(
            original,
            ModelVerdict(
                status = "ok",
                has_errors = true,
                corrected = "Voy a tomar una ducha y te llamo después",
                more_natural = true,
                natural = "Me voy a duchar y te llamo despues",
                natural_changes = listOf(ModelChange("Voy a tomar una ducha", "Me voy a duchar", why = "More usual")),
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
        val revision = feedback(
            original,
            ModelVerdict(
                status = "ok",
                has_errors = true,
                corrected = "Bon dia! Com estàs amb la pluja?",
                more_natural = true,
                natural = "Bon dia! Com portes la pluja?",
                natural_changes = listOf(ModelChange("estas amb", "portes", why = "Usual way to say it")),
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
        val revision = feedback(
            original,
            ModelVerdict(status = "ok", has_errors = true, corrected = "hola, q tal, bb?"),
        ).revision

        val all = revision.acceptAll(EditKind.Fix)
        assertEquals("hola, q tal, bb?", all.workingText)
        assertEquals(original, all.undo().workingText)
        assertFalse(all.undo().canUndo)
    }

    @Test
    fun deletedWordsAreShownWithTheirNeighbour() {
        val revision = feedback(
            "Ayer yo fui a la playa",
            ModelVerdict(status = "ok", has_errors = true, corrected = "Ayer fui a la playa"),
        ).revision

        val fix = revision.remaining(EditKind.Fix).single()
        assertEquals("yo fui", fix.from)
        assertEquals("fui", fix.replacement)
    }

    @Test
    fun insertedWordsDontCarryTheSpaceIntoTheHighlight() {
        val revision = feedback(
            "Ahir vaig mercat",
            ModelVerdict(status = "ok", has_errors = true, corrected = "Ahir vaig al mercat"),
        ).revision

        val fix = revision.remaining(EditKind.Fix).single()
        val preview = revision.preview(EditKind.Fix)
        assertEquals("Ahir vaig al mercat", preview.text)
        assertEquals("al ", preview.text.substring(preview.ranges.getValue(fix.id)))
    }

    @Test
    fun onlyRequestedJudgmentsBecomeEdits() {
        val original = "Com estas amb la pluja?"
        val verdict = ModelVerdict(
            status = "ok",
            has_errors = true,
            corrected = "Com estàs amb la pluja?",
            more_natural = true,
            natural = "Com portes la pluja?",
        )
        val fixOnly = interpret(original, verdict, checkNaturalness = false) as CheckResult.Feedback
        assertTrue(fixOnly.revision.edits(EditKind.Natural).isEmpty())
        val naturalOnly = interpret(original, verdict, checkFixes = false) as CheckResult.Feedback
        assertTrue(naturalOnly.revision.edits(EditKind.Fix).isEmpty())
        assertEquals(
            CheckResult.AllGood(checkedFixes = true, checkedNaturalness = false),
            interpret(original, verdict.copy(has_errors = false), checkNaturalness = false),
        )
    }

    @Test
    fun aCorrectionIdenticalToTheOriginalIsAllGood() {
        val original = "Ens veiem demà a les set?"
        val verdict = ModelVerdict(status = "ok", has_errors = true, corrected = " $original ")
        assertEquals(CheckResult.AllGood(true, true), interpret(original, verdict))
    }

    @Test
    fun unclearAndWrongLanguage() {
        assertEquals(CheckResult.Unclear, interpret("x", ModelVerdict(status = "unclear")))
        assertEquals(
            CheckResult.WrongLanguage("Catalan"),
            interpret("x", ModelVerdict(status = "wrong_language"), expectedLanguage = "Catalan"),
        )
        // Without a chosen language there's nothing to be wrong about.
        assertEquals(CheckResult.Unclear, interpret("x", ModelVerdict(status = "wrong_language")))
    }
}
