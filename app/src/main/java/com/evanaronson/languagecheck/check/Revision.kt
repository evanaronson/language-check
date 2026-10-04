package com.evanaronson.languagecheck.check

/**
 * The writer's text plus every suggested edit, and which ones they've accepted.
 * Immutable: each action returns a new Revision.
 */
data class Revision(
    val original: String,
    val edits: List<Edit>,
    /** Accepted edit ids, one entry per action, so Undo reverts a whole "Replace all". */
    val steps: List<List<Int>> = emptyList(),
) {
    private val accepted: Set<Int> = steps.flatten().toSet()

    val acceptedCount get() = accepted.size
    val canUndo get() = steps.isNotEmpty()

    fun edits(kind: EditKind) = edits.filter { it.kind == kind }

    /** Edits still on offer: not accepted, and not overtaken by an accepted rewording. */
    fun remaining(kind: EditKind) = edits(kind).filter { it.id !in accepted && !superseded(it) }

    fun accept(id: Int) = if (id in accepted) this else copy(steps = steps + listOf(listOf(id)))

    fun acceptAll(kind: EditKind): Revision {
        val ids = remaining(kind).map { it.id }
        return if (ids.isEmpty()) this else copy(steps = steps + listOf(ids))
    }

    fun undo() = copy(steps = steps.dropLast(1))

    /** The text with accepted edits applied: what goes back to the app. */
    val workingText: String get() = render(edits.filter { it.id in accepted }).text

    /** The working text with all of [kind]'s remaining edits applied too, and where they sit. */
    fun preview(kind: EditKind): Rendered = render(edits.filter { it.id in accepted } + remaining(kind))

    /** A fix inside a phrase the writer has accepted a rewording for no longer applies. */
    private fun superseded(edit: Edit) = edit.kind == EditKind.Fix &&
        edits.any { it.kind == EditKind.Natural && it.id in accepted && it.overlaps(edit) }

    private fun render(chosen: List<Edit>): Rendered {
        // Where a rewording and a fix cover the same words, the rewording wins:
        // it replaces those words, and is itself correct.
        val naturals = chosen.filter { it.kind == EditKind.Natural }
        val applied = chosen.filter { edit -> edit.kind == EditKind.Natural || naturals.none { it.overlaps(edit) } }
        val out = StringBuilder()
        val ranges = mutableMapOf<Int, IntRange>()
        var pos = 0
        for (edit in applied.sortedWith(Edits.order)) {
            if (edit.start < pos) continue
            out.append(original, pos, edit.start)
            val start = out.length
            out.append(edit.replacement)
            if (edit.replacement.isNotEmpty()) ranges[edit.id] = start until out.length
            pos = edit.end
        }
        out.append(original, pos, original.length)
        return Rendered(out.toString(), ranges)
    }
}

/** Rendered text and the character range each applied edit occupies in it. */
data class Rendered(val text: String, val ranges: Map<Int, IntRange>)
