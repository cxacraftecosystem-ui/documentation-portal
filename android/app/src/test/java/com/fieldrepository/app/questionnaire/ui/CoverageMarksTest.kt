package com.fieldrepository.app.questionnaire.ui

import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.data.QGrid
import com.fieldrepository.app.questionnaire.data.QGridCell
import com.fieldrepository.app.questionnaire.data.QGridSection
import com.fieldrepository.app.questionnaire.data.QSnapshotTake
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.portal.QuestionnairePortal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner amendment 2 (22 Oct 2026): typed or recorded, either counts, recording preferred (● recorded,
 * ✎ typed only, "Add a recording?"), and the admin's section override keeps three states (Marked
 * complete, Needs review, Needs redo), which win on the grid while the takes stay listed.
 */
class CoverageMarksTest {
    private val instrument = Instrument(
        "f", "Craft survey", listOf(
            InstrumentSection("secA", "A", "Respondent", listOf(InstrumentQuestion("l1", "A1", "Name?", "secA"), InstrumentQuestion("l2", "A2", "Age?", "secA"))),
            InstrumentSection("secB", "B", "Raw material", listOf(InstrumentQuestion("l3", "B1", "Which wood?", "secB")))
        )
    )
    private fun take(artisan: String, target: String, id: String = "r-$artisan-$target") =
        QSnapshotTake(recordingId = id, artisanId = artisan, targetKey = target, capturedAt = "2026-07-01T10:00:00Z")

    @QFeature("D4")
    @Test fun `a section typed but not recorded is the lighter ✎ mark, and asks gently for a recording`() {
        val rows = CoverageGridModel.rows(instrument, listOf("a1", "a2"), known = listOf(take("a1", "S:secA")), overlay = emptyList(), typed = setOf("a2" to "l1", "a2" to "l2"))
        val a = rows.first { it.section.id == "secA" }
        assertEquals("●", a.cells[0].glyph)
        assertEquals(CellMark.TYPED, a.cells[1].mark)
        assertEquals("✎", a.cells[1].glyph)
        assertTrue("typed counts as covered", a.cells[1].covered)
        assertTrue(a.cells[1].addRecording)
        assertFalse(a.cells[0].addRecording)
        // Partly typed: "✎ 1/2", not yet covered.
        val partly = CoverageGridModel.rows(instrument, listOf("a3"), emptyList(), emptyList(), typed = setOf("a3" to "l1")).first().cells.single()
        assertEquals("✎ 1/2", partly.glyph)
        assertFalse(partly.covered)
        // A question recorded and one typed: both count towards the section, the recording shown.
        val mixed = CoverageGridModel.rows(instrument, listOf("a4"), listOf(take("a4", "Q:l1")), emptyList(), typed = setOf("a4" to "l2")).first().cells.single()
        assertEquals("◐ 2/2", mixed.glyph)
        assertEquals(QuestionnaireCopy.s("mark.addRecording"), "Add a recording?")
    }

    @QFeature("D1")
    @Test fun `the three section overrides win on the grid and say which they are`() {
        val server = QGrid(sections = listOf(QGridSection("secA", cells = listOf(
            QGridCell(artisanId = "a1", override = "COMPLETED"), QGridCell(artisanId = "a2", override = "NEEDS_REVIEW"), QGridCell(artisanId = "a3", override = "NEEDS_REDO")
        ))))
        val cells = CoverageGridModel.rows(instrument, listOf("a1", "a2", "a3"), listOf(take("a3", "S:secA")), emptyList(), server = server).first().cells
        assertEquals(listOf("✓", "?", "↻"), cells.map { it.glyph })
        assertTrue("Marked complete counts", cells[0].covered)
        assertEquals(listOf("Marked complete", "Needs review", "Needs redo"), cells.map { QuestionnaireCopy.overrideLabel(it.override) })
        assertEquals("the takes are still there under the override", CellMark.RECORDED, cells[2].mark)
    }

    @QFeature("B13")
    @Test fun `the server's grid decodes with its overrides and the source of each cell`() {
        val grid = QuestionnairePortal.json.decodeFromString(QGrid.serializer(), """
            {"questionnaireId":"f","artisans":[{"artisanId":"a1","name":"Giriraj"}],"respondents":[{"respondentId":"g1","name":"Ravi","subjectKey":"R:g1"}],
             "sections":[{"sectionId":"secA","code":"A","title":"Respondent","cells":[
               {"artisanId":"a1","subjectKey":"A:a1","state":"RECORDED","takes":1,"covered":2,"total":2,"needsDecision":false,"override":"NEEDS_REDO","source":"RECORDED","recordedCount":2,"typedOnlyCount":0,"addRecording":false},
               {"respondentId":"g1","subjectKey":"R:g1","state":"NOT_RECORDED","takes":0,"covered":2,"total":2,"needsDecision":false,"override":null,"source":"TYPED","recordedCount":0,"typedOnlyCount":2,"addRecording":true}]}]}
        """.trimIndent())
        assertEquals("NEEDS_REDO", grid.cell("secA", "a1")?.override)
        assertEquals("TYPED", grid.cell("secA", "R:g1")?.source)
        assertEquals(mapOf("a1" to "Giriraj", "R:g1" to "Ravi"), grid.names)
    }

    @QFeature("C5")
    @Test fun `the completion matrix is the coverage grid, with partial sections and the phone summary`() {
        val rows = CoverageGridModel.rows(instrument, listOf("a1", "a2"), listOf(take("a1", "Q:l1"), take("a2", "S:secB")), emptyList())
        assertEquals("◐ 1/2", rows[0].cells[0].glyph)
        assertEquals("1/2", rows[1].summary)
        val typedRow = CoverageGridModel.rows(instrument, listOf("a1"), emptyList(), emptyList(), typed = setOf("a1" to "l3"))[1]
        assertEquals("1/1 ✎", typedRow.summary)
    }

    @QFeature("B12")
    @Test fun `the workshop coverage page draws the server's cells with the session grid's marks`() {
        assertEquals("●", cellOf(QGridCell(artisanId = "a1", state = "RECORDED", covered = 2, total = 2, source = "RECORDED")).glyph)
        assertEquals("◐ 1/3", cellOf(QGridCell(artisanId = "a1", state = "PARTIAL", covered = 1, total = 3, source = "RECORDED")).glyph)
        assertEquals("✎", cellOf(QGridCell(artisanId = "a1", covered = 3, total = 3, source = "TYPED")).glyph)
        assertEquals("⚠", cellOf(QGridCell(artisanId = "a1", needsDecision = true)).glyph)
        assertEquals("✓", cellOf(QGridCell(artisanId = "a1", override = "COMPLETED")).glyph)
        assertEquals("○", cellOf(QGridCell(artisanId = "a1")).glyph)
    }
}
