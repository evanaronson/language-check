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
                fixes = listOf(ModelChange("estas", "estàs", why = "Missing accent")),
                more_natural = true,
                natural = "Bon dia! Com portes la pluja?",
                natural_changes = listOf(ModelChange("estàs amb", "portes", why = "Usual way to say it")),
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
                    ModelChange("unes", "uns", why = "Masculine"),
                    ModelChange("tomàquet", "tomàquets", why = "Plural"),
                    ModelChange("bo", "bons", why = "Agreement"),
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
                fixes = listOf(ModelChange("x", "not in the text", why = "?")),
            ),
        ) as CheckResult.Feedback

        val change = result.correction!!.changes.single()
        assertEquals("estàs", result.correction!!.text.substring(change.range))
        assertNull(change.why)
    }

    @Test
    fun changesMatchWholeWordsBeforePartsOfWords() {
        val result = interpret(
            "tambien esta bien",
            ModelVerdict(
                status = "ok",
                has_errors = true,
                corrected = "también está bien",
                fixes = listOf(ModelChange("esta", "está", why = "Verb needs accent"), ModelChange("tambien", "también", why = "Accent")),
            ),
        ) as CheckResult.Feedback

        val correction = result.correction!!
        assertEquals(listOf("también", "está"), correction.changes.map { correction.text.substring(it.range) })
    }

    @Test
    fun punctuationFixesAreSeparateHighlights() {
        val result = interpret(
            "Hola qué tal bb estás bien",
            ModelVerdict(
                status = "ok",
                has_errors = true,
                corrected = "Hola, ¿qué tal, bb? ¿Estás bien?",
                fixes = listOf(
                    ModelChange("Hola qué", "Hola, ¿qué", why = "Comma, then open the question"),
                    ModelChange("tal bb", "tal, bb?", why = "Comma before name; close question"),
                    ModelChange("estás", "¿Estás", why = "New question"),
                    ModelChange("bien", "bien?", why = "Close question"),
                ),
            ),
        ) as CheckResult.Feedback

        val correction = result.correction!!
        assertEquals(4, correction.edits)
        assertEquals(
            listOf("Hola, ¿qué", "tal, bb?", "¿Estás", "bien?"),
            correction.changes.map { correction.text.substring(it.range) },
        )
    }

    @Test
    fun eachPunctuationMarkIsItsOwnChange() {
        val result = interpret(
            "te encanta esta musica no",
            ModelVerdict(
                status = "ok",
                has_errors = true,
                corrected = "te encanta esta música, no?",
                fixes = listOf(
                    ModelChange("musica", "música", context = "esta música, no?", why = "Missing accent"),
                    ModelChange("", ",", context = "música, no?", why = "Comma before a tag question"),
                    ModelChange("", "?", context = "música, no?", why = "End of question"),
                ),
            ),
        ) as CheckResult.Feedback

        val correction = result.correction!!
        assertEquals(3, correction.edits)
        assertEquals(listOf("música", ",", "?"), correction.changes.map { correction.text.substring(it.range) })
        assertEquals(listOf(16, 22, 26), correction.changes.map { it.range.first })
        assertEquals("", correction.changes[1].from)
    }

    @Test
    fun onlyRequestedJudgmentsAreShown() {
        val verdict = ModelVerdict(
            status = "ok",
            has_errors = true,
            corrected = "Bon dia! Com estàs amb la pluja?",
            more_natural = true,
            natural = "Bon dia! Com portes la pluja?",
        )
        val fixOnly = interpret(original, verdict, checkNaturalness = false) as CheckResult.Feedback
        assertNull(fixOnly.natural)
        val naturalOnly = interpret(original, verdict, checkFixes = false) as CheckResult.Feedback
        assertNull(naturalOnly.correction)
        assertEquals(
            CheckResult.AllGood(checkedFixes = true, checkedNaturalness = false),
            interpret(original, verdict.copy(has_errors = false), checkNaturalness = false),
        )
    }

    @Test
    fun nothingToSayIsAllGood() {
        val verdict = ModelVerdict(status = "ok", language = "Catalan")
        assertEquals(CheckResult.AllGood(true, true), interpret("Ens veiem demà a les set?", verdict))
    }

    @Test
    fun aCorrectionIdenticalToTheOriginalIsNotAFix() {
        val verdict = ModelVerdict(
            status = "ok", has_errors = true, corrected = " $original ",
            fixes = listOf(ModelChange("estas", "estas", why = "?")),
        )
        assertEquals(CheckResult.AllGood(true, true), interpret(original, verdict))
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
