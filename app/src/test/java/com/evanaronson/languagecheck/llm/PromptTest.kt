package com.evanaronson.languagecheck.llm

import com.evanaronson.languagecheck.review.Judgments
import com.evanaronson.languagecheck.review.Language
import com.evanaronson.languagecheck.review.Punctuation
import com.evanaronson.languagecheck.review.Settled
import com.evanaronson.languagecheck.review.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class PromptTest {
    private val prompt = Prompt(system = "", schemaJson = "{}")

    @Test
    fun userMessageListsSettingsThenSettledAnswersThenText() {
        val request = CheckRequest(
            text = "que me ha traído",
            language = Language.Spanish,
            punctuation = Punctuation.Moderate,
            judgments = Judgments.FixOnly,
            settled = listOf(Settled("Who brought the beers", "You")),
        )
        assertEquals(
            "Language: Spanish (Peninsular)\nPunctuation: moderate\nChecks: fix\n" +
                "Settled: Who brought the beers → You\nText: que me ha traído",
            prompt.userMessage(request),
        )
    }

    @Test
    fun assumptionsAreReadFromTheAnswer() {
        val verdict = prompt.parseVerdict(
            """{"status":"ok","assumptions":[{"about":"Who brought the beers","assumed":"Charles",""" +
                """"words":"me ha traído","alternatives":["You"]}],"has_errors":false,"corrected":"","fixes":[],""" +
                """"more_natural":false,"natural":"","natural_changes":[]}""",
        )
        assertEquals(Verdict.Status.Ok, verdict.status)
        assertEquals(listOf("You"), verdict.assumptions.single().alternatives)
    }
}
