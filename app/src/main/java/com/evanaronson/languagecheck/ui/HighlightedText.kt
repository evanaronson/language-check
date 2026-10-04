package com.evanaronson.languagecheck.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.evanaronson.languagecheck.check.Change

/**
 * Suggestion text with a separate rounded highlight behind each change, so
 * adjacent changes read as distinct blocks. Tapping a highlight shows why.
 */
@Composable
fun HighlightedText(text: String, changes: List<Change>, accent: Color) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var selected by remember(text) { mutableStateOf<Int?>(null) }

    val annotated = remember(text, changes) {
        buildAnnotatedString {
            append(text)
            for (change in changes) {
                addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), change.range.first, change.range.last + 1)
            }
        }
    }

    Column {
        Text(
            annotated,
            style = MaterialTheme.typography.bodyLarge,
            onTextLayout = { layout = it },
            modifier = Modifier
                .drawBehind {
                    val result = layout ?: return@drawBehind
                    // Highlights grow a little past their text, except where two changes touch,
                    // where both pull back so a gap keeps them visibly separate.
                    val grow = 2.5.dp.toPx()
                    val gap = 1.dp.toPx()
                    val padY = 1.dp.toPx()
                    val radius = CornerRadius(5.dp.toPx())
                    changes.forEachIndexed { index, change ->
                        val touchesBefore = changes.any { it.range.last + 1 == change.range.first }
                        val touchesAfter = changes.any { it.range.first == change.range.last + 1 }
                        val padLeft = if (touchesBefore) -gap else grow
                        val padRight = if (touchesAfter) -gap else grow
                        val color = accent.copy(alpha = if (index == selected) 0.42f else 0.18f)
                        for ((left, top, right, bottom) in lineBoxes(result, change.range)) {
                            drawRoundRect(
                                color = color,
                                topLeft = Offset(left - padLeft, top + padY),
                                size = Size(right - left + padLeft + padRight, bottom - top - 2 * padY),
                                cornerRadius = radius,
                            )
                        }
                    }
                }
                .pointerInput(changes) {
                    detectTapGestures { position ->
                        val result = layout ?: return@detectTapGestures
                        val hit = changes.indexOfFirst { characterAt(result, position) in it.range }
                        selected = if (hit < 0 || hit == selected) null else hit
                    }
                },
        )

        val shown = selected?.let { changes.getOrNull(it) }
        AnimatedVisibility(visible = shown != null) {
            val change = shown ?: return@AnimatedVisibility
            Column {
                Spacer(Modifier.height(8.dp))
                Explanation(change, text.substring(change.range), accent)
            }
        }
    }
}

@Composable
private fun Explanation(change: Change, replacement: String, accent: Color) {
    Surface(shape = MaterialTheme.shapes.medium, color = accent.copy(alpha = 0.12f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    buildAnnotatedString {
                        val from = change.from
                        if (from.isNullOrEmpty()) {
                            // Something added, like a comma.
                            append("+ ")
                        } else {
                            pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                            append(from)
                            pop()
                            append("  →  ")
                        }
                        pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                        append(replacement)
                        pop()
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                change.why?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private data class LineBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** One box per line the range covers, so a highlight wraps cleanly. */
private fun lineBoxes(layout: TextLayoutResult, range: IntRange): List<LineBox> {
    if (range.isEmpty() || range.last >= layout.layoutInput.text.length) return emptyList()
    val firstLine = layout.getLineForOffset(range.first)
    val lastLine = layout.getLineForOffset(range.last)
    return (firstLine..lastLine).map { line ->
        val left = if (line == firstLine) layout.getBoundingBox(range.first).left else layout.getLineLeft(line)
        val right = if (line == lastLine) layout.getBoundingBox(range.last).right else layout.getLineRight(line)
        LineBox(left, layout.getLineTop(line), right, layout.getLineBottom(line))
    }
}

/**
 * The character under [position], or -1. getOffsetForPosition gives the
 * nearest caret position, which for a narrow comma is often the one after it.
 */
private fun characterAt(layout: TextLayoutResult, position: Offset): Int {
    val caret = layout.getOffsetForPosition(position)
    val length = layout.layoutInput.text.length
    for (candidate in listOf(caret, caret - 1)) {
        if (candidate !in 0 until length) continue
        val box = layout.getBoundingBox(candidate)
        if (position.x >= box.left - 4 && position.x <= box.right + 4 && position.y in box.top..box.bottom) {
            return candidate
        }
    }
    return -1
}
