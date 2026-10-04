package com.evanaronson.languagecheck.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class FieldTextTest {
    @Test
    fun withoutASelectionTheWholeFieldIsChecked() {
        val field = FieldText.of("  hola q tal ", -1, -1)
        assertEquals("hola q tal", field.text)
        assertEquals("  hola, ¿qué tal? ", field.with("hola, ¿qué tal?"))
    }

    @Test
    fun aSelectionIsCheckedAndPutBackInPlace() {
        val full = "Primera frase. com estas? Última frase."
        val start = full.indexOf("com")
        val field = FieldText.of(full, start, start + "com estas? ".length)
        assertEquals("com estas?", field.text)
        assertEquals("Primera frase. Com estás? Última frase.", field.with("Com estás?"))
        assertEquals(full.indexOf(" Última"), field.cursorAfter("Com estás?"))
    }

    @Test
    fun aCursorWithoutSelectionMeansTheWholeField() {
        assertEquals("hola", FieldText.of("hola", 2, 2).text)
    }

    @Test
    fun onlySpacesIsNothingToCheck() {
        assertEquals("", FieldText.of("   ", -1, -1).text)
    }
}
