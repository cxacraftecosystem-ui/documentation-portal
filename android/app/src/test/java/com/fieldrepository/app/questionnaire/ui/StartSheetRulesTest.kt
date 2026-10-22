package com.fieldrepository.app.questionnaire.ui

import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.data.QArtisanOption
import com.fieldrepository.app.questionnaire.data.QConfig
import com.fieldrepository.app.questionnaire.data.QFormOption
import com.fieldrepository.app.questionnaire.data.QSubjects
import com.fieldrepository.app.questionnaire.data.QWorkshopOption
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.RespondentKind
import com.fieldrepository.app.questionnaire.portal.QuestionnairePortal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The start sheet's rules (DESIGN §4.2, UNIFY §2.3 rule 5, owner amendment 2): the default workshop,
 * the form a workshop opens with, the forms grouped by kind, the generated title, guests, and what a
 * create carries.
 */
class StartSheetRulesTest {
    private val forms = listOf(
        QFormOption("f1", "Toolkit", kind = "WORKSHOP_INTERVIEW", kindLabel = "Workshop interview"),
        QFormOption("f2", "Market", kind = "MARKET_SURVEY", kindLabel = "Market survey", isDefault = true),
        QFormOption("f3", "Notes")
    )

    @QFeature("B31")
    @Test fun `the server's resolution wins, then the form already picked, then the default, then the first`() {
        assertEquals("f1", startFormFor("f1", "f3", forms))
        assertEquals("a form this account cannot see is ignored", "f3", startFormFor("gone", "f3", forms))
        assertEquals("f2", startFormFor(null, "", forms))
        assertEquals("f1", startFormFor(null, "", forms.map { it.copy(isDefault = false) }))
        // Grouped by kind, a bound form first in its kind and marked.
        val options = formOptions(forms, bound = setOf("f1"))
        assertEquals(listOf("f1", "f2", "f3"), options.map { it.value })
        assertEquals("Workshop interview · Bound to this workshop", options.first().hint)
    }

    @QFeature("B30")
    @Test fun `the default workshop is the one the server names, else the most recently changed`() {
        val list = listOf(
            QWorkshopOption("w1", "Bagru 2026", updatedAt = "2026-06-01T00:00:00Z"),
            QWorkshopOption("w2", "Kolkata 2026", updatedAt = "2026-07-01T00:00:00Z")
        )
        assertEquals("w1", mostRecentRecordableWorkshop(list, preferredId = "w1")?.id)
        assertEquals("w2", mostRecentRecordableWorkshop(list)?.id)
        assertNull(mostRecentRecordableWorkshop(emptyList()))
    }

    @QFeature("B3")
    @Test fun `the title is generated from the fields, never typed, and a guest is named in it`() {
        val artisans = mapOf("a1" to QArtisanOption("a1", "Giriraj Prasad Chhipa", "Bagru", craft = "Hand Block Printing"))
        val form = StartForm(questionnaireId = "f1", date = LocalDate.parse("2026-07-01"), artisanIds = listOf("a1"), place = "Bagru, Jaipur")
        assertEquals("Interview_2026-07-01_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad-Chhipa", form.titlePreview(null, artisans, meName = null))
        val create = form.toCreate("c1", "u1", Instant.parse("2026-07-01T05:00:00Z"))
        assertFalse(QuestionnairePortal.json.encodeToString(com.fieldrepository.app.questionnaire.data.QSessionCreate.serializer(), create).contains("\"title\""))
    }

    @QFeature("A18")
    @Test fun `someone else, or the researcher themself, can be the whole session`() {
        val guest = StartGuest(QSubjects.newLocalGuest(), "  Shop   owner ")
        val form = StartForm(questionnaireId = "f2", guests = listOf(guest))
        assertTrue("a guest with a name is enough to start", form.canStart)
        assertFalse(StartForm(questionnaireId = "f2", guests = listOf(StartGuest("R:local-x", " "))).canStart)
        assertTrue("Myself needs no name: the server takes the researcher's", StartForm(questionnaireId = "f2", guests = listOf(StartGuest("R:local-y", "", RespondentKind.SELF.wire))).canStart)
        val create = form.toCreate("c2", "u1", Instant.parse("2026-07-01T05:00:00Z"))
        assertEquals("Shop owner", create.respondents.single().name)
        assertTrue(create.participants.isEmpty())
        assertEquals(listOf(guest.localId), form.subjectIds)
    }

    @QFeature("B36")
    @Test fun `with no answer from the server v2 reads as off, and its switches have safe defaults`() {
        val config = QuestionnairePortal.json.decodeFromString(QConfig.serializer(), "{}")
        assertFalse(config.v2Enabled)
        assertTrue("transcription ships paused", config.transcriptionPaused)
        assertEquals(80, config.noteMaxChars)
        assertTrue(QuestionnairePortal.json.decodeFromString(QConfig.serializer(), """{"v2Enabled":true,"portal":"designer-portal"}""").v2Enabled)
    }

    @QFeature("D3")
    @Test fun `a designer's own form is offered beside the shared ones and can start a session`() {
        val mine = QFormOption("mine", "My craft walk", kind = null)
        assertEquals("mine", startFormFor("mine", "", forms + mine))
        val order = formOptions(forms + mine, emptySet()).map { it.value }
        assertTrue("forms without a kind sort after the kinds", order.indexOf("mine") > order.indexOf("f2"))
    }

    @Test fun `the session files under the design workshop on this portal`() {
        val create = StartForm(workshopId = "dw1", questionnaireId = "f1", artisanIds = listOf("a1")).toCreate("c3", null, Instant.EPOCH)
        if (QuestionnairePortal.FILES_UNDER_DESIGN_WORKSHOP) {
            assertEquals("dw1", create.designWorkshopId); assertNull(create.workshopId)
        } else {
            assertEquals("dw1", create.workshopId); assertNull(create.designWorkshopId)
        }
        assertEquals(QuestionnaireCopy.s("guest.someoneElse"), "Someone else")
    }
}
