package com.evanaronson.languagecheck.check

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterpretTest {
    private val original = "Bon dia! Com estas amb la pluja?"

    @Test
    fun correctionAndNaturalAlternativeUseTheModelsChanges() {
        val result = interpret(
            original,
            ModelVerdict(
                status = "ok",
                language = "Catalan",
                has_errors = true,
                corrected = "Bon dia! Com estàs amb la pluja?",
                fixes = listOf(ModelChange("estas", "estàs", "Missing accent")),
                more_natural = true,
                natural = "Bon dia! Com portes la pluja?",
                natural_changes = listOf(ModelChange("estàs amb", "portes", "Usual way to say it")),
            ),
        ) as CheckResult.Feedback

        val correction = result.correction!!
        assertEquals(1, correction.edits)
        val fix = correction.changes.single()
        assertEquals("estàs", correction.text.substring(fix.range))
        assertEquals("estas", fix.from)
        assertEquals("Missing accent", fix.why)
        assertEquals("portes", result.natural!!.text.substring(result.natural!!.changes.single().range))
    }

    @Test
    fun adjacentMistakesStaySeparate() {
        val result = interpret(
            "unes tomàquet bo",
            ModelVerdict(
                status = "ok",
                has_errors = true,
                corrected = "uns tomàquets bons",
                fixes = listOf(
                    ModelChange("unes", "uns", "Masculine"),
                    ModelChange("tomàquet", "tomàquets", "Plural"),
                    ModelChange("bo", "bons", "Agreement"),
                ),
            ),
        ) as CheckResult.Feedback

        val correction = result.correction!!
        assertEquals(3, correction.edits)
        assertEquals(listOf("uns", "tomàquets", "bons"), correction.changes.map { correction.text.substring(it.range) })
    }

    @Test
    fun fallsBackToTheWordDiffWhenChangesCantBeLocated() {
        val result = interpret(
            original,
            ModelVerdict(
                status = "ok",
                has_errors = true,
                corrected = "Bon dia! Com estàs amb la pluja?",
                fixes = listOf(ModelChange("x", "not in the text", "?")),
            ),
        ) as CheckResult.Feedback

        val change = result.correction!!.changes.single()
        assertEquals("estàs", result.correction!!.text.substring(change.range))
        assertNull(change.why)
    }

    @Test
    fun nothingToSayIsAllGood() {
        val verdict = ModelVerdict(status = "ok", language = "Catalan")
        assertEquals(CheckResult.AllGood, interpret("Ens veiem demà a les set?", verdict))
    }

    @Test
    fun aCorrectionIdenticalToTheOriginalIsNotAFix() {
        val verdict = ModelVerdict(
            status = "ok", has_errors = true, corrected = " $original ",
            fixes = listOf(ModelChange("estas", "estas", "?")),
        )
        assertEquals(CheckResult.AllGood, interpret(original, verdict))
    }

    @Test
    fun naturalAlternativeIdenticalToTheCorrectionIsDropped() {
        val fixed = "Bon dia! Com estàs amb la pluja?"
        val verdict = ModelVerdict(
            status = "ok", has_errors = true, corrected = fixed, more_natural = true, natural = fixed,
        )
        val result = interpret(original, verdict) as CheckResult.Feedback
        assertNull(result.natural)
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
