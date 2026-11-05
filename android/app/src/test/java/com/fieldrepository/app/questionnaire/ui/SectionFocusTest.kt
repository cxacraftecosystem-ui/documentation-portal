package com.fieldrepository.app.questionnaire.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionFocusTest {
    @Test fun `a blank or catch-all key focuses nothing`() {
        assertNull(SectionFocus.wanted(null))
        assertNull(SectionFocus.wanted(" "))
        assertNull(SectionFocus.wanted("_"))
        assertEquals("B", SectionFocus.wanted(" B "))
    }

    @Test fun `a section the record lacks is still shown when asked for`() {
        assertTrue(SectionFocus.missing(listOf("A", "B"), "D"))
        assertFalse(SectionFocus.missing(listOf("A", "B"), "B"))
        assertFalse(SectionFocus.missing(listOf("A"), null))
    }

    @Test fun `only the asked-for section with no answers says so`() {
        assertTrue(SectionFocus.emptyNote("C", "C", answered = false))
        assertFalse(SectionFocus.emptyNote("C", "C", answered = true))
        assertFalse(SectionFocus.emptyNote("A", "C", answered = false))
        assertFalse(SectionFocus.emptyNote("A", null, answered = false))
    }
}
