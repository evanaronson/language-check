package com.evanaronson.languagecheck.check

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterpretTest {
    private val original = "Bon dia! Com estas amb la pluja?"

    @Test
    fun correctionAndNaturalAlternative() {
        val result = interpret(
            original,
            ModelVerdict(
                status = "ok",
                language = "ca",
                has_errors = true,
                corrected = "Bon dia! Com estàs amb la pluja?",
                more_natural = true,
                natural = "Bon dia! Com portes la pluja?",
            ),
        ) as CheckResult.Feedback

        val correction = result.correction!!
        assertEquals(1, correction.edits)
        assertEquals("estàs", correction.text.substring(correction.changed.single()))
        assertEquals("portes", result.natural!!.text.substring(result.natural!!.changed.single()))
    }

    @Test
    fun nothingToSayIsAllGood() {
        val verdict = ModelVerdict(status = "ok", language = "ca")
        assertEquals(CheckResult.AllGood, interpret("Ens veiem demà a les set?", verdict))
    }

    @Test
    fun aCorrectionIdenticalToTheOriginalIsNotAFix() {
        val verdict = ModelVerdict(status = "ok", has_errors = true, corrected = " $original ")
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
    fun unclearAndUnsupported() {
        assertEquals(CheckResult.Unclear, interpret("x", ModelVerdict(status = "unclear")))
        assertEquals(CheckResult.NotSupported, interpret("x", ModelVerdict(status = "not_supported")))
    }
}
