package com.evanaronson.linguize.history

import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.llm.Provider
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * How an enum is written wherever it's kept: one stable lowercase token per constant,
 * the same in the database, the export and settings. Tokens are spelled out rather
 * than derived from constant names, so renaming a constant can't change what's stored,
 * and reading is lenient: a token this build doesn't know (written by a newer one, or
 * since retired) reads as null, never as a crash.
 */
class Tokens<E : Enum<E>>(private val entries: List<E>, private val token: (E) -> String) {
    init {
        val tokens = entries.map(token)
        require(tokens.toSet().size == tokens.size) { "Tokens must be unique: $tokens" }
        require(tokens.all { it.isNotEmpty() && it == it.lowercase() }) { "Tokens must be lowercase: $tokens" }
    }

    fun encode(value: E): String = token(value)

    /** The constant [stored] stands for, or null when it isn't one of this build's tokens. */
    fun decode(stored: String?): E? = stored?.let { s -> entries.firstOrNull { token(it) == s } }

    /**
     * Like [decode], but also accepts a constant's name: what settings and the first
     * history builds wrote before there were tokens.
     */
    fun decodeOrName(stored: String?): E? = decode(stored) ?: stored?.let { s -> entries.firstOrNull { it.name == s } }
}

/** Every enum that's kept, with its tokens. */
object Stored {
    val origin = Tokens(Origin.entries) {
        when (it) {
            Origin.Menu -> "menu"
            Origin.Button -> "button"
            Origin.Tester -> "tester"
        }
    }

    val decision = Tokens(Decision.entries) {
        when (it) {
            Decision.Accepted -> "accepted"
            Decision.Undone -> "undone"
            Decision.Retired -> "retired"
            Decision.Copied -> "copied"
            Decision.Ignored -> "ignored"
            Decision.Superseded -> "superseded"
        }
    }

    val outcome = Tokens(Outcome.entries) {
        when (it) {
            Outcome.Applied -> "applied"
            Outcome.Copied -> "copied"
            Outcome.None -> "none"
            Outcome.Failed -> "failed"
            Outcome.Abandoned -> "abandoned"
        }
    }

    val editKind = Tokens(EditKind.entries) {
        when (it) {
            EditKind.Fix -> "fix"
            EditKind.Natural -> "natural"
        }
    }

    /** The same tokens as the verdict's own JSON, so the export and the column agree. */
    val status = Tokens(Verdict.Status.entries) {
        when (it) {
            Verdict.Status.Ok -> "ok"
            Verdict.Status.Unclear -> "unclear"
            Verdict.Status.WrongLanguage -> "wrong_language"
        }
    }

    /** The same tokens the prompt sends the model. */
    val punctuation = Tokens(Punctuation.entries) {
        when (it) {
            Punctuation.Strict -> "strict"
            Punctuation.Moderate -> "moderate"
            Punctuation.Casual -> "casual"
        }
    }

    /** The same tokens the prompt sends the model. */
    val judgments = Tokens(Judgments.entries) {
        when (it) {
            Judgments.Both -> "both"
            Judgments.FixOnly -> "fix"
            Judgments.NaturalizeOnly -> "naturalize"
        }
    }

    val provider = Tokens(Provider.entries) {
        when (it) {
            Provider.Gemini -> "gemini"
            Provider.OpenAI -> "openai"
        }
    }
}

/**
 * Writes an enum as its token in JSON (the export). Reading an unknown token fails with
 * a [SerializationException]; nothing reads the export back today, and the database
 * reads its columns through [Tokens.decode] instead.
 */
abstract class TokenSerializer<E : Enum<E>>(name: String, private val tokens: () -> Tokens<E>) : KSerializer<E> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.evanaronson.linguize.history.$name", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: E) = encoder.encodeString(tokens().encode(value))

    override fun deserialize(decoder: Decoder): E {
        val stored = decoder.decodeString()
        return tokens().decode(stored) ?: throw SerializationException("Unknown ${descriptor.serialName} \"$stored\"")
    }
}

object OriginSerializer : TokenSerializer<Origin>("Origin", { Stored.origin })

object DecisionSerializer : TokenSerializer<Decision>("Decision", { Stored.decision })

object OutcomeSerializer : TokenSerializer<Outcome>("Outcome", { Stored.outcome })

object EditKindSerializer : TokenSerializer<EditKind>("EditKind", { Stored.editKind })
