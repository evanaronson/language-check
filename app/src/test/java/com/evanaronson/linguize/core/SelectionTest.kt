package com.evanaronson.linguize.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionTest {
    @Test
    fun withoutASelectionTheWholeTextIsChecked() {
        val selection = Selection.of("  hola q tal ", -1, -1)
        assertEquals("hola q tal", selection.text)
        assertEquals("  hola, ¿qué tal? ", selection.with("hola, ¿qué tal?"))
    }

    @Test
    fun aSelectionIsCheckedAndPutBackInPlace() {
        val full = "Primera frase. com estas? Última frase."
        val start = full.indexOf("com")
        val selection = Selection.of(full, start, start + "com estas? ".length)
        assertEquals("com estas?", selection.text)
        assertEquals("Primera frase. Com estás? Última frase.", selection.with("Com estás?"))
        assertEquals(full.indexOf(" Última"), selection.cursorAfter("Com estás?"))
        assertEquals(full, selection.full)
    }

    @Test
    fun aBackwardsSelectionIsTheSameSelection() {
        val full = "uno dos tres"
        assertEquals("dos", Selection.of(full, 7, 4).text)
    }

    @Test
    fun aCursorWithoutSelectionMeansTheWholeText() {
        assertEquals("hola", Selection.of("hola", 2, 2).text)
    }

    @Test
    fun surroundingWhitespaceIsKeptOutsideTheCheck() {
        val selection = Selection.of("hola q tal\n")
        assertEquals("hola q tal", selection.text)
        assertEquals("Hola, ¿qué tal?\n", selection.with("Hola, ¿qué tal?"))
    }

    @Test
    fun onlySpacesIsNothingToCheck() {
        assertEquals("", Selection.of("   ").text)
    }
}
