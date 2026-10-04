package com.evanaronson.languagecheck.review

/** Which section of the card an edit belongs to. */
enum class EditKind { Fix, Natural }

/**
 * One suggested change, anchored to a span of the writer's original text, so
 * edits can be accepted in any order without disturbing each other.
 */
data class Edit(
    val id: Int,
    val kind: EditKind,
    /** Start of the replaced span in the original text. */
    val start: Int,
    /** End of the span (exclusive); equal to [start] when something is only inserted. */
    val end: Int,
    /** The original text being replaced; empty for an insertion. */
    val from: String,
    val replacement: String,
    val why: String?,
) {
    val isInsertion get() = start == end

    fun overlaps(other: Edit): Boolean = when {
        !isInsertion && !other.isInsertion -> start < other.end && other.start < end
        isInsertion && !other.isInsertion -> other.start < start && start < other.end
        !isInsertion && other.isInsertion -> start < other.start && other.start < end
        else -> start == other.start && kind != other.kind
    }
}

/** Text with edits applied, and the range each edit's replacement occupies in it. */
data class Rendered(val text: String, val ranges: Map<Int, IntRange>)

/** Applies non-overlapping [edits] to [original]; an insertion goes before a replacement at the same place. */
internal fun render(original: String, edits: List<Edit>): Rendered {
    val out = StringBuilder()
    val ranges = mutableMapOf<Int, IntRange>()
    var pos = 0
    for (edit in edits.sortedWith(compareBy({ it.start }, { if (it.isInsertion) 0 else 1 }))) {
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
