package com.fieldrepository.app.questionnaire.ui

import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.data.ClipPayload
import com.fieldrepository.app.questionnaire.data.OverlayTake
import com.fieldrepository.app.questionnaire.data.QCompleteRecording
import com.fieldrepository.app.questionnaire.data.QCompleteSubject
import com.fieldrepository.app.questionnaire.data.QPartDone
import com.fieldrepository.app.questionnaire.data.QSnapshotTake
import com.fieldrepository.app.questionnaire.data.QTargetRef
import com.fieldrepository.app.questionnaire.data.SessionStep
import com.fieldrepository.app.questionnaire.data.SessionUnit
import com.fieldrepository.app.questionnaire.data.StepKind
import com.fieldrepository.app.questionnaire.data.StepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class SessionScreenModelsTest {
    private val instrument = Instrument(
        "f", "Craft survey", listOf(
            InstrumentSection("secA", "A", "Origin", listOf(InstrumentQuestion("l1", "A1", "History?", "secA"), InstrumentQuestion("l2", "A2", "Teacher?", "secA"))),
            InstrumentSection("secB", "B", "Family", listOf(InstrumentQuestion("l3", "B1", "Who helps?", "secB"), InstrumentQuestion("l4", "B2", "Children?", "secB"), InstrumentQuestion("l5", "B3", "Women?", "secB"))),
            InstrumentSection("secC", "C", "Tools", listOf(InstrumentQuestion("l6", "C1", "Which tools?", "secC")))
        )
    )

    private fun take(artisan: String, target: String, id: String = "r-$artisan-$target", filing: String = "FILED", duration: Long = 192_000) =
        QSnapshotTake(recordingId = id, sessionId = "s1", artisanId = artisan, targetKey = target, capturedAt = "2026-11-14T10:00:00Z", filing = filing, durationMs = duration)

    @QFeature("Q5")
    @Test fun `a section take answers every question in it, and a typed answer under it is not counted twice`() {
        val evidence = SessionEvidence(setOf("a1"), listOf(take("a1", "S:secA")), emptyList(), setOf("a1" to "l1"))
        val a = evidence.section(instrument.sections[0])
        assertEquals(2, a.answered)
        assertTrue(a.done && a.inSectionTake)
        assertEquals("2 of 2 · In the section take", a.subline())
        assertEquals(QuestionState.IN_SECTION_TAKE, evidence.question(instrument.sections[0].questions[0]).state)
        assertEquals("In the section take", evidence.question(instrument.sections[0].questions[1]).line())
        assertEquals(SessionTally(2, 6, 1, 3), evidence.tally(instrument))
    }

    @QFeature("Q5")
    @Test fun `a question counts once any participant answered it, by take or by typing`() {
        val evidence = SessionEvidence(setOf("a1", "a2", "a3"), listOf(take("a2", "Q:l3")), emptyList(), setOf("a3" to "l4"))
        val b = evidence.section(instrument.sections[1])
        assertEquals(2, b.answered)
        assertEquals(SectionTile.STARTED, b.tile)
        assertEquals("2 of 3", b.subline())
        assertEquals("Question take · 3:12", evidence.question(instrument.sections[1].questions[0]).line())
        assertEquals("Typed", evidence.question(instrument.sections[1].questions[1]).line())
        assertEquals("Not asked", evidence.question(instrument.sections[1].questions[2]).line())
        assertEquals("1 question", evidence.section(instrument.sections[2]).subline())
        assertEquals(SectionTile.FRESH, evidence.section(instrument.sections[2]).tile)
    }

    @QFeature("Q5")
    @Test fun `a typed-only finished section says so, a take on the phone counts, and whole-session takes mark nothing`() {
        val local = OverlayTake("k1", "s1", "a1", "Q:l3", "2026-11-14T10:00:00Z", 60_000, 1, uploading = true)
        val evidence = SessionEvidence(setOf("a1"), listOf(take("a1", "")), listOf(local), setOf("a1" to "l6"))
        assertEquals("1 of 1 · Typed", evidence.section(instrument.sections[2]).subline())
        assertEquals(QuestionState.ON_DEVICE, evidence.question(instrument.sections[1].questions[0]).state)
        assertEquals(2, evidence.tally(instrument).answered)
    }

    @QFeature("Q5")
    @Test fun `only this session's takes count towards its progress`() {
        val evidence = SessionEvidence.forSession(
            listOf("a1"), setOf("s1", "c-1"),
            listOf(take("a1", "S:secA"), take("a1", "S:secB").copy(sessionId = "s-old")),
            listOf(OverlayTake("k2", "c-other", "a1", "S:secC", "2026-11-14T10:00:00Z", 1, 1, false)), emptySet()
        )
        assertEquals(2, evidence.tally(instrument).answered)
    }

    @QFeature("Q6")
    @Test fun `review lists decisions and failed uploads under Needs you, and what is left under Still to ask`() {
        val evidence = SessionEvidence(setOf("a1"), listOf(take("a1", "Q:l3", filing = "NEEDS_DECISION"), take("a1", "S:secA")), emptyList(), emptySet())
        val tray = listOf(TrayRow("st1", StepKind.CLIP, "Section C (14 Nov)", "Storage refused", files = 1))
        val model = ReviewModel.of(instrument, evidence, listOf("Meera Devi"), pending = 2, tray = tray)
        assertEquals(SessionTally(3, 6, 1, 3), model.tally)
        assertEquals("1 section done for Meera Devi. 2 takes still uploading.", model.summary)
        assertEquals(listOf("B1 has two takes", "Take of C did not upload"), model.needsYou.map { it.title })
        assertEquals(listOf("Choose which one to keep", "Upload failed"), model.needsYou.map { it.detail })
        assertEquals("st1", model.needsYou[1].stepId)
        assertEquals(listOf("B", "C"), model.stillToAsk.map { it.section.code })
        assertEquals("2 left", model.stillToAsk[0].trailing)
        assertEquals("Not asked · 1", model.stillToAsk[1].trailing)
    }

    private fun clip(percent: Boolean) = ClipPayload(
        clientClipKey = "k", localPath = "/x", sizeBytes = 1000, label = "Section A",
        recording = QCompleteRecording(targets = listOf(QTargetRef(sectionId = "secA")), subjects = listOf(QCompleteSubject("a1")), deviceCapturedAt = "2026-11-14T10:00:00Z"),
        partSize = 400, partsDone = if (percent) listOf(QPartDone(1, "e")) else emptyList()
    )

    private fun unit(vararg steps: SessionStep) = SessionUnit("c-1", createdAt = "2026-11-14T10:00:00Z", questionnaireId = "f", steps = steps.toList())

    @QFeature("Q2")
    @Test fun `the save pill reads Saved, on this phone, uploading with a percent, waiting, and failed`() {
        assertEquals(SaveTone.SAVED, SavePillModel.of(unit(SessionStep("1", StepKind.SESSION, "t", "i", state = StepState.DONE))).tone)
        assertEquals("Saved", SavePillModel.of(null).text)
        val onPhone = SavePillModel.of(unit(SessionStep("1", StepKind.ANSWERS, "t", "i")))
        assertEquals(SaveTone.ON_PHONE, onPhone.tone)
        assertEquals("Saved on this phone", onPhone.text)
        val uploading = SavePillModel.of(unit(SessionStep("1", StepKind.CLIP, "t", "i", clip = clip(true)), SessionStep("2", StepKind.CLIP, "t", "j", clip = clip(true))))
        assertEquals(SaveTone.UPLOADING, uploading.tone)
        assertEquals("2 takes uploading · 40%", uploading.text)
        assertEquals("Waiting for signal", SavePillModel.of(unit(SessionStep("1", StepKind.CLIP, "t", "i", clip = clip(false)))).text)
        val failed = SavePillModel.of(unit(SessionStep("1", StepKind.CLIP, "t", "i", clip = clip(true), state = StepState.FAILED)))
        assertEquals(SaveTone.FAILED, failed.tone)
        assertEquals("Upload failed. Retrying.", failed.text)
        assertEquals(listOf("1"), failed.failedSteps)
    }

    @QFeature("Q4")
    @Test fun `upload attaches where it was opened from, counts only files that will upload, and names a duplicate`() {
        assertEquals(AttachKind.SECTION, UploadPlan.defaultKind("secA", null))
        assertEquals(AttachKind.QUESTION, UploadPlan.defaultKind("secA", "l1"))
        assertEquals(AttachKind.WHOLE, UploadPlan.defaultKind(null, null))
        val fresh = PickedFile("/a/Meera_part1.m4a", "sha1", 10, "audio/mp4", "m4a", Instant.parse("2026-11-14T10:42:00Z"), 2_530_000)
        val dup = PickedFile("/a/Meera_part2.m4a", "sha2", 10, "audio/mp4", "m4a", null, 1_083_000)
        val rows = listOf(UploadRow(fresh), UploadRow(dup, UploadDuplicate.OnServer(LocalDate.parse("2026-11-14"))))
        assertEquals(listOf(fresh), UploadPlan.uploading(rows))
        assertEquals("Upload 1 file", UploadPlan.button(1))
        assertEquals("Upload 3 files", UploadPlan.button(3))
        assertEquals("42:10 · recorded 14\u00A0Nov,\u00A04:12\u00A0pm", UploadPlan.meta(fresh, null))
        assertEquals("18:03 · recorded 13\u00A0Nov,\u00A012:00\u00A0pm", UploadPlan.meta(dup, LocalDate.parse("2026-11-13")))
        assertEquals("Already uploaded on 14 Nov", UploadPlan.duplicateLine(LocalDate.parse("2026-11-14")))
        assertFalse(rows[0].skipped)
    }

    @QFeature("Q2")
    @Test fun `the recorder names the section it is recording and keeps a two-digit clock`() {
        assertEquals("Recording section E", recordingLabel("Section E"))
        assertEquals("Recording E3", recordingLabel("E3"))
        assertEquals("07:42", clockText(462_000))
        assertEquals("1:02:03", clockText(3_723_000))
        assertEquals("RM", initials("Ramesh Maharana"))
    }
}
