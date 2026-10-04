package com.evanaronson.languagecheck.review

/**
 * The writer's text plus every suggested edit, and which ones they've accepted.
 * Immutable: each action returns a new Revision.
 *
 * Rules, where two edits "touch" as defined by [Edit.overlaps]:
 * 1. Edits of the same kind never touch each other.
 * 2. Accepting an edit retires every edit of the other kind that touches it.
 * 3. A retired edit is no longer offered: not shown, not counted, not applied by Replace all.
 * 4. Replace all accepts a kind's remaining edits as one action.
 * 5. Undo reverts the last action, bringing back what it accepted and what that retired.
 * So accepted edits never touch, and the working text is simply the original
 * with the accepted edits applied.
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

    /** Edits still on offer: neither accepted nor retired. */
    fun remaining(kind: EditKind) = edits(kind).filter { it.id !in accepted && !retired(it) }

    fun accept(id: Int): Revision {
        val edit = edits.firstOrNull { it.id == id } ?: return this
        return if (id in accepted || retired(edit)) this else copy(steps = steps + listOf(listOf(id)))
    }

    fun acceptAll(kind: EditKind): Revision {
        val ids = remaining(kind).map { it.id }
        return if (ids.isEmpty()) this else copy(steps = steps + listOf(ids))
    }

    fun undo() = copy(steps = steps.dropLast(1))

    /** The text with accepted edits applied: what goes back to the app. */
    val workingText: String get() = render(original, edits.filter { it.id in accepted }).text

    /** The working text with all of [kind]'s remaining edits applied too, and where they sit. */
    fun preview(kind: EditKind): Rendered = render(original, edits.filter { it.id in accepted } + remaining(kind))

    /** Touched by an accepted edit of the other kind. */
    private fun retired(edit: Edit) =
        edits.any { it.id in accepted && it.kind != edit.kind && it.overlaps(edit) }
}
