package com.fieldrepository.app.questionnaire.ui

import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.portal.QuestionnairePortal
import com.fieldrepository.app.questionnaire.data.QCoverageCheckResponse
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.RepeatReason
import com.fieldrepository.app.questionnaire.data.SessionPurpose
import com.fieldrepository.app.questionnaire.ui.AlreadyRecorded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** P2's copy variants and preselected reason (DESIGN §4.4), and the prompts' button rows. */
@QFeature("B2")
class QuestionnairePromptsTest {
    private val now = Instant.parse("2026-07-09T09:00:00Z")
    private val names = mapOf("a1" to "Giriraj Prasad Chhipa", "a2" to "Meena Devi", "a3" to "Ravi Kumar")

    private fun response(vararg covered: Pair<String, String>, via: Boolean = false, sameSession: Boolean = false, mine: Boolean = false, suggested: String = "RE_INTERVIEW") =
        QuestionnairePortal.json.decodeFromString(
            QCoverageCheckResponse.serializer(),
            """{"targets":[{"targetKey":"S:secD","label":"Section D","perArtisan":[""" +
                listOf("a1", "a2", "a3").joinToString(",") { id ->
                    val take = covered.firstOrNull { it.first == id }
                    if (take == null) """{"artisanId":"$id","state":"NOT_RECORDED","takes":[]}"""
                    else """{"artisanId":"$id","state":"${if (via) "VIA_SECTION" else "RECORDED"}","viaSection":$via,"takes":[{"recordingId":"r-$id","position":1,"capturedAt":"${take.second}T05:12:00Z","durationMs":${if (id == "a1") 852000 else 540000},"recordedBy":{"id":"u3","name":"${if (id == "a1") "Nandini" else "Ankur"}"},"session":{"id":"s0","date":"${take.second}","rosterCount":${if (id == "a1") 3 else 1}},"sameSession":$sameSession,"mine":$mine}]}"""
                } + """]}],"suggestedReason":"$suggested"}"""
        )

    @Test
    fun `the group body, the take lines and the first-take line`() {
        val model = AlreadyRecorded.model(response("a1" to "2026-07-07", "a2" to "2026-07-02"), "Section D", "D", names, listOf("a1", "a2", "a3"), SessionPurpose.FIRST_SITTING.wire, false, now)!!
        assertEquals("Already recorded", model.title)
        assertEquals("Section D is recorded for 2 of 3 here.", model.body)
        assertEquals(listOf("Giriraj · 14 min · Nandini · 7 Jul · Group of 3", "Meena · 9 min · Ankur · 2 Jul · Solo"), model.lines.map { it.text })
        assertEquals(listOf("Ravi hasn't been recorded for D. Their take will be their first."), model.firstTakes)
        assertEquals(RepeatReason.RE_INTERVIEW.wire, model.preselected)
        assertEquals("Record again", model.primaryButton)
        assertEquals(listOf("r-a1", "r-a2"), model.ackedRecordingIds)
    }

    @Test
    fun `one subject, and a question covered by its section`() {
        val one = AlreadyRecorded.model(response("a1" to "2026-07-07"), "Section D", "D", names, listOf("a1"), SessionPurpose.FIRST_SITTING.wire, false, now)!!
        assertEquals("Section D was recorded for Giriraj on 7 Jul.", one.body)
        val whole = AlreadyRecorded.model(response("a1" to "2026-07-07", via = true), "Section D", "D3", names, listOf("a1"), SessionPurpose.FIRST_SITTING.wire, false, now)!!
        assertEquals("Section D was recorded whole for Giriraj on 7 Jul.", whole.body)
    }

    @Test
    fun `a file pick reads Upload again, and the purpose preselects`() {
        val pick = AlreadyRecorded.model(response("a1" to "2026-07-07"), "Section D", "D", names, listOf("a1"), SessionPurpose.FOLLOW_UP.wire, true, now)!!
        assertEquals("Upload again", pick.primaryButton)
        assertEquals(RepeatReason.FOLLOW_UP.wire, pick.preselected)
        val redo = AlreadyRecorded.model(response("a1" to "2026-07-07"), "Section D", "D", names, listOf("a1"), SessionPurpose.REDO.wire, false, now)!!
        assertEquals(RepeatReason.REDO.wire, redo.preselected)
    }

    @Test
    fun `part 2 and replace only when their rules hold`() {
        val other = AlreadyRecorded.model(response("a1" to "2026-07-07"), "Section D", "D", names, listOf("a1"), SessionPurpose.FIRST_SITTING.wire, false, now)!!
        assertFalse(other.offerPartTwo); assertFalse(other.offerReplace)
        val mineHere = AlreadyRecorded.model(response("a1" to "2026-07-09", sameSession = true, mine = true, suggested = "REDO"), "Section D", "D", names, listOf("a1"), SessionPurpose.FIRST_SITTING.wire, false, now)!!
        assertTrue(mineHere.offerPartTwo); assertTrue(mineHere.offerReplace)
        assertEquals(RepeatReason.REDO.wire, mineHere.preselected)
    }

    @Test
    fun `nobody covered means no prompt`() {
        assertNull(AlreadyRecorded.model(response(), "Section D", "D", names, listOf("a1", "a2"), SessionPurpose.FIRST_SITTING.wire, false, now))
    }

    @Test
    fun `the other prompts read as DESIGN 4_5`() {
        assertEquals("Stop the Section D recording?", QuestionnaireCopy.p3Title("Section D"))
        assertEquals("It's 12:40 long and will be kept.", QuestionnaireCopy.p3Body(760_000))
        assertEquals("Stop and record F3", QuestionnaireCopy.p3Stop("F3"))
        assertEquals("Replace take 1 of Section D?", QuestionnaireCopy.p7Title("Section D"))
        assertEquals("A 12-minute take of Section D was saved before the app closed.", QuestionnaireCopy.p13Body(12, "Section D"))
        assertEquals("Update when you have Wi-Fi. Your recordings are safe.", QuestionnaireCopy.s("p14.banner"))
        assertEquals("2 takes will keep uploading in the background.", QuestionnaireCopy.leaveBody(2))
        assertEquals("6 sections recorded for 3 artisans. 2 takes still uploading.", QuestionnaireCopy.finishBody(6, 3, 2))
        assertEquals("Recordings of Giriraj may be sent to outside services to be written down or translated.", QuestionnaireCopy.consentNotice("Giriraj"))
        assertEquals("Recordings of Giriraj are not sent to outside services: consent was refused.", QuestionnaireCopy.consentNotice("Giriraj", refused = true))
        assertEquals("Nandini started one with Giriraj and Meena at 10:12.", QuestionnaireCopy.p1Body("Nandini", listOf("Giriraj", "Meena"), Instant.parse("2026-07-01T04:42:00Z")))
    }

    @Test
    fun `the coverage line for 1, 3 and 8 artisans`() {
        val c = QuestionnaireCopy::ArtisanCoverage
        assertEquals("Giriraj has 9 of 24 sections.", QuestionnaireCopy.coverageLine(listOf(c("Giriraj", 9)), 24, null))
        assertEquals("Giriraj 9 of 24 · Meena none yet · Ravi 14 of 24. · Checked 2 h ago",
            QuestionnaireCopy.coverageLine(listOf(c("Giriraj", 9), c("Meena", 0), c("Ravi", 14)), 24, "Checked 2 h ago"))
        assertEquals("3 of 8 have earlier recordings ▾", QuestionnaireCopy.coverageLine(List(8) { c("A$it", if (it < 3) 1 else 0) }, 24, null))
    }

    @Test
    fun `every questionnaire prompt goes through the shared button row`() {
        // The shared tree's own folder, found from this package wherever the test runs.
        val pkgPath = PromptButton::class.java.name.substringBeforeLast('.').replace('.', '/')
        val dir = generateSequence(java.io.File(".").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(java.io.File(it, "src/main/java/$pkgPath"), java.io.File(it, "android/app/src/main/java/$pkgPath")) }
            .first { it.isDirectory }
        val prompts = java.io.File(dir, "QuestionnairePrompts.kt").readText()
        assertTrue(prompts.contains("ActionRow("))
        dir.listFiles { f -> f.extension == "kt" }!!.forEach { file ->
            val text = file.readText()
            assertFalse("${file.name} draws its own dismissButton", text.contains("dismissButton ="))
        }
    }
}
