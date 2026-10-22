package com.fieldrepository.app.data

import com.fieldrepository.app.questionnaire.QFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

/**
 * DESIGN §9 test 54: offline P2 comes from the snapshot plus the outbox overlay and answers in the
 * server's `CoverageCheckResponse` shape. The hierarchy rule is the server's (§3.1).
 */
class CoverageCacheTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = Instant.parse("2026-07-09T09:00:00Z")

    private val context = CoverageCache.CheckContext(
        sessionId = "s1", sessionKey = "c-1", rosterCount = 3, myUserId = "u4",
        sectionOfQuestion = mapOf("lin_d3" to "secD", "lin_d4" to "secD"),
        questionsOfSection = mapOf("secD" to listOf("lin_d3", "lin_d4")),
        labels = mapOf("S:secD" to "Section D", "Q:lin_d3" to "D3")
    )

    private val snapshot = QSnapshot(
        asOfSeq = "1042",
        takes = listOf(
            QSnapshotTake("r9", "s0", "a1", "S:secD", capturedAt = "2026-07-07T05:12:00Z", durationMs = 852_000, recordedBy = QUserRef("u3", "Nandini"), rosterCount = 3, sessionDate = "2026-07-07"),
            QSnapshotTake("r10", "s0", "a2", "S:secD", role = SubjectRole.PRESENT.wire, capturedAt = "2026-07-07T05:12:00Z"),
            QSnapshotTake("r11", "s5", "a3", "Q:lin_d4", capturedAt = "2026-07-02T05:00:00Z")
        )
    )

    /** The server's answer for the same request, the shape of DESIGN §5.2. */
    private val serverFixture = """
        {"asOf":"2026-07-09T09:00:00Z","identical":null,
         "targets":[{"targetKey":"S:secD","label":"Section D","perArtisan":[
           {"artisanId":"a1","state":"RECORDED","viaSection":false,
            "takes":[{"recordingId":"r9","position":1,"capturedAt":"2026-07-07T05:12:00Z","estimated":false,"durationMs":852000,
                      "recordedBy":{"id":"u3","name":"Nandini"},"session":{"id":"s0","date":"2026-07-07","rosterCount":3},
                      "sameSession":false,"mine":false,"repeatReason":null}]},
           {"artisanId":"a2","state":"NOT_RECORDED","viaSection":false,"takes":[]},
           {"artisanId":"a3","state":"PARTIAL","viaSection":false,"takes":[]}]}],
         "suggestedReason":"RE_INTERVIEW"}
    """.trimIndent()

    @QFeature("B11")
    @Test
    fun `offline check matches the server's response`() {
        val request = QCoverageCheckRequest("q_ctw3", listOf("a1", "a2", "a3"), listOf(QTargetRef(sectionId = "secD")), "s1")
        val offline = CoverageCache.checkAgainst(snapshot, request, emptyList(), context, now)
        val server = ApiClient.json.decodeFromString(QCoverageCheckResponse.serializer(), serverFixture)
        assertEquals(server, offline)
    }

    @Test
    fun `a question after a section take is covered via the section`() {
        val request = QCoverageCheckRequest("q_ctw3", listOf("a1"), listOf(QTargetRef(questionLineageId = "lin_d3")))
        val result = CoverageCache.checkAgainst(snapshot, request, emptyList(), context, now)
        val a1 = result.targets.single().perArtisan.single()
        assertEquals(CoverageState.VIA_SECTION.wire, a1.state)
        assertTrue(a1.viaSection)
        assertEquals("r9", a1.takes.single().recordingId)
    }

    @Test
    fun `a section after question takes does not prompt and PRESENT never covers`() {
        val request = QCoverageCheckRequest("q_ctw3", listOf("a2", "a3"), listOf(QTargetRef(sectionId = "secD")))
        val result = CoverageCache.checkAgainst(snapshot, request, emptyList(), context, now)
        assertTrue(result.targets.single().perArtisan.all { it.takes.isEmpty() })
        assertEquals(CoverageState.PARTIAL.wire, result.targets.single().perArtisan.first { it.artisanId == "a3" }.state)
    }

    @Test
    fun `a take still on this device counts`() {
        val overlay = listOf(OverlayTake("k1", "c-1", "a2", "S:secD", "2026-07-09T08:30:00Z", 60_000, 1, uploading = false))
        val request = QCoverageCheckRequest("q_ctw3", listOf("a2"), listOf(QTargetRef(sectionId = "secD")), "s1")
        val result = CoverageCache.checkAgainst(snapshot, request, overlay, context.copy(rosterCount = 1), now)
        val take = result.targets.single().perArtisan.single().takes.single()
        assertEquals("local:k1", take.recordingId)
        assertTrue(take.sameSession)
        assertTrue(take.mine)
        // Same session, mine, within 2 h: Redo is preselected.
        assertEquals(RepeatReason.REDO.wire, result.suggestedReason)
    }

    @Test
    fun `the session purpose decides the reason first`() {
        val request = QCoverageCheckRequest("q_ctw3", listOf("a1"), listOf(QTargetRef(sectionId = "secD")))
        assertEquals(RepeatReason.FOLLOW_UP.wire, CoverageCache.checkAgainst(snapshot, request, emptyList(), context.copy(purpose = SessionPurpose.FOLLOW_UP.wire), now).suggestedReason)
        assertEquals(RepeatReason.NEW_SETTING.wire, CoverageCache.checkAgainst(snapshot, request, emptyList(), context.copy(rosterCount = 1), now).suggestedReason)
    }

    @Test
    fun `deltas fold in edits and removals and the cache survives a restart`() {
        val dir = tmp.newFolder()
        val cache = CoverageCache(dir) { now }
        cache.apply("q", "w", snapshot, delta = false)
        cache.apply("q", "w", QSnapshot("1050", takes = listOf(QSnapshotTake("r12", "s6", "a2", "S:secD")), removedRecordingIds = listOf("r9")), delta = true)
        val reopened = CoverageCache(dir) { now }
        val takes = reopened.get("q", "w")!!.snapshot.takes
        assertFalse(takes.any { it.recordingId == "r9" })
        assertTrue(takes.any { it.recordingId == "r12" })
        assertEquals("1050", reopened.get("q", "w")!!.snapshot.asOfSeq)
    }

    @Test
    fun `the overlay comes from pending clips only`() {
        val unit = SessionUnit(
            clientKey = "c-1", createdAt = "x", questionnaireId = "q", sessionId = "s1",
            steps = listOf(
                SessionStep("1", StepKind.CLIP, "t", "k", clip = testClip(tmp.newFolder(), "k1", 10, "2026-07-09T08:00:00Z", listOf("a1", "a2"))),
                SessionStep("2", StepKind.CLIP, "t", "k", state = StepState.DONE, clip = testClip(tmp.newFolder(), "k2", 10, "2026-07-09T08:10:00Z", listOf("a1")))
            )
        )
        val overlay = CoverageCache.overlayFrom(listOf(unit), "q")
        assertEquals(listOf("a1", "a2"), overlay.map { it.artisanId })
        assertTrue(overlay.all { it.clientClipKey == "k1" })
    }
}
