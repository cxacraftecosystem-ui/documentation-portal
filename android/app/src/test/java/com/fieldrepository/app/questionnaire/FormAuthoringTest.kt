package com.fieldrepository.app.questionnaire

import com.fieldrepository.app.data.BundleRead
import com.fieldrepository.app.data.FakeSessionTransport
import com.fieldrepository.app.data.FormAuthoringOutbox
import com.fieldrepository.app.data.FormBundles
import com.fieldrepository.app.data.FormKinds
import com.fieldrepository.app.data.FormQueue
import com.fieldrepository.app.data.FormTransport
import com.fieldrepository.app.data.InMemoryFormQueueStore
import com.fieldrepository.app.data.InMemorySessionUnitStore
import com.fieldrepository.app.data.PermanentStepFailure
import com.fieldrepository.app.data.QArtisanRef
import com.fieldrepository.app.data.QForm
import com.fieldrepository.app.data.QFormCreate
import com.fieldrepository.app.data.QFormQuestion
import com.fieldrepository.app.data.QFormSection
import com.fieldrepository.app.data.QQuestionCreate
import com.fieldrepository.app.data.QSectionCreate
import com.fieldrepository.app.data.QSessionCreate
import com.fieldrepository.app.data.SessionOutbox
import com.fieldrepository.app.data.StepState
import com.fieldrepository.app.data.outboxJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

/**
 * Offline form authoring with temporary ids (A15), reuse (A5) and the phone-to-phone handoff (A10):
 * forms, sections and questions written with no signal land in order, each child is sent with its
 * parent's server id, and a session started on a phone-only form waits for the form.
 */
class FormAuthoringTest {
    /** A forms server in memory: hands out ids, records every create with its parent. */
    private class FakeForms : FormTransport {
        val calls = mutableListOf<String>()
        val keys = mutableListOf<String>()
        var offline = false
        var refuseTitle: String? = null
        private var n = 0
        override suspend fun createForm(idempotencyKey: String, body: QFormCreate): String {
            if (offline) throw IOException("no signal")
            if (body.title == refuseTitle) throw PermanentStepFailure("A questionnaire titled that already exists.")
            keys += idempotencyKey; val id = "F${++n}"; calls += "form:${body.title}->$id"; return id
        }
        override suspend fun createSection(idempotencyKey: String, body: QSectionCreate): String {
            if (offline) throw IOException("no signal")
            keys += idempotencyKey; val id = "S${++n}"; calls += "section:${body.code}@${body.questionnaireId}->$id"; return id
        }
        override suspend fun createQuestion(idempotencyKey: String, body: QQuestionCreate): String {
            if (offline) throw IOException("no signal")
            keys += idempotencyKey; val id = "Q${++n}"; calls += "question:${body.prompt}@${body.sectionId}"; return id
        }
    }

    @QFeature("A15")
    @Test fun `A form written offline lands form, section, question, each with its parent's server id`() = runBlocking {
        val outbox = FormAuthoringOutbox(InMemoryFormQueueStore())
        val server = FakeForms().apply { offline = true }
        val form = outbox.queueForm(QFormCreate("Toolkit", kind = FormKinds.WORKSHOP_INTERVIEW), ownerUserId = "p1")
        val section = outbox.queueSection(form, "D", "Dyes", null)
        outbox.queueQuestion(section, "Which dye?", null, isRequired = true, sortOrder = null)
        assertTrue(FormQueue.isLocal(form))
        assertNull("not landed: no server id yet", outbox.resolveFormId(form))
        val local = outbox.localForms().single()
        assertEquals("Toolkit", local.title)
        assertEquals(1, local.questionCount)

        outbox.sync(server, "p1")
        assertEquals("no signal: nothing sent, all kept", 3, outbox.queue().pending.size)
        server.offline = false
        val keysBefore = outbox.queue().steps.map { it.idempotencyKey }
        val pass = outbox.sync(server, "p1")
        assertEquals(3, pass.sent)
        assertEquals(listOf("form:Toolkit->F1", "section:D@F1->S2", "question:Which dye?@S2"), server.calls)
        assertEquals("the keys minted when queued are the ones sent", keysBefore.toSet(), server.keys.toSet())
        assertEquals("F1", outbox.resolveFormId(form))
        assertTrue(outbox.queue().steps.isEmpty())
        assertTrue(outbox.localForms().isEmpty())
    }

    @QFeature("A15")
    @Test fun `A refused form keeps its children waiting, and another account's queue is never sent`() = runBlocking {
        val outbox = FormAuthoringOutbox(InMemoryFormQueueStore())
        val server = FakeForms().apply { refuseTitle = "Dupe" }
        val form = outbox.queueForm(QFormCreate("Dupe"), ownerUserId = "p1")
        outbox.queueSection(form, "A", "Intro", null)
        assertEquals(0, outbox.sync(server, "someone-else").sent)
        val pass = outbox.sync(server, "p1")
        assertEquals(1, pass.failed)
        assertEquals(StepState.FAILED, outbox.queue().steps.first { it.localId == form }.state)
        assertEquals("the section waits for its form", StepState.PENDING, outbox.queue().steps.first { it.localId != form }.state)
        outbox.discard(outbox.queue().steps.first { it.localId == form }.id)
        assertTrue("discarding the form discards what hangs from it", outbox.queue().steps.isEmpty())
    }

    @QFeature("A15")
    @Test fun `A session started on a phone-only form waits for the form and is sent with its real id`() = runBlocking {
        val forms = FormAuthoringOutbox(InMemoryFormQueueStore())
        val formId = forms.queueForm(QFormCreate("Field visit"), "p1")
        val sessions = SessionOutbox(InMemorySessionUnitStore(), resolveFormId = { forms.resolveFormId(it) })
        val transport = FakeSessionTransport()
        sessions.startSession(QSessionCreate(clientKey = "k1", questionnaireId = formId, sessionDate = "2026-10-22", startedAt = "2026-10-22T05:00:00Z", participants = listOf(QArtisanRef("a1"))), "p1")
        sessions.sync(transport, "p1")
        assertTrue("no form id yet: the session is not sent", transport.calls.isEmpty())
        forms.sync(FakeForms(), "p1")
        sessions.sync(transport, "p1")
        assertEquals("session", transport.calls.first())
        val sent = outboxJson.decodeFromString(QSessionCreate.serializer(), transport.bodies.first())
        assertEquals("F1", sent.questionnaireId)
    }

    private val source = QForm(
        id = "f1", title = "2nd Craft Toolkit", kind = FormKinds.WORKSHOP_INTERVIEW,
        sections = listOf(
            QFormSection("s1", "A", "About", 1, questions = listOf(
                QFormQuestion("q1", prompt = "Name?", isRequired = true, sortOrder = 1),
                QFormQuestion("q2", prompt = "Old wording", sortOrder = 2, supersededById = "q3"),
                QFormQuestion("q3", prompt = "Age?", helpText = "In years", sortOrder = 3)
            )),
            QFormSection("s2", "B", "Tools", 2, questions = listOf(QFormQuestion("q4", prompt = "Which tools?", sortOrder = 1))),
            QFormSection("s3", "C", "Retired", 3, isActive = false)
        )
    )

    @QFeature("A5")
    @Test fun `Reuse copies the live sections and questions only, as a new form of mine`() = runBlocking {
        val copy = FormBundles.of(source).let { it.copy(form = it.form.copy(title = source.title + " (copy)")) }
        assertEquals(listOf("A", "B"), copy.sections.map { it.code })
        assertEquals(listOf("Name?", "Age?", "Which tools?"), copy.questions.map { it.prompt })
        val outbox = FormAuthoringOutbox(InMemoryFormQueueStore())
        val server = FakeForms()
        assertNotNull(outbox.queueBundle(copy, "p1", bundleId = null))
        assertNotNull("a reuse is never refused as a repeat", outbox.queueBundle(copy, "p1", bundleId = null))
        outbox.sync(server, "p1")
        assertEquals("form:2nd Craft Toolkit (copy)->F1", server.calls.first())
        assertEquals(2 * (1 + 2 + 3), server.calls.size)
    }

    @QFeature("A10")
    @Test fun `A frwq round-trips, a dpwq v1 reads, a newer file is refused, and a bundle is added once`() = runBlocking {
        val bundle = FormBundles.of(source, bundleId = "b".repeat(32), createdAt = "2026-10-22T10:00:00+05:30")
        val read = FormBundles.read(FormBundles.encode(bundle)) as BundleRead.Ok
        assertEquals(bundle, read.bundle)
        assertEquals(2, read.bundle.v)
        assertTrue(FormBundles.readable("x.dpwq") && FormBundles.readable("X.FRWQ"))
        assertEquals("FieldRepository_Questionnaire_2026-10-22_2nd-Craft-Toolkit_Handoff.frwq", FormBundles.fileName(source.title, day = "2026-10-22"))

        val v1 = """{"format":"designprototype.questionnaire","schemaVersion":1,"title":"DR form","sections":[{"code":"D","title":"Dyes","sortOrder":1,"questions":[{"prompt":"Which dye?","isRequired":true,"sortOrder":1}]}]}"""
        val fromDr = FormBundles.read(gzip(v1)) as BundleRead.Ok
        assertEquals("DR form", fromDr.bundle.form.title)
        assertEquals(listOf("Which dye?"), fromDr.bundle.questions.map { it.prompt })
        assertEquals("BUNDLE_VERSION", (FormBundles.read(gzip("""{"v":3,"form":{"title":"x"}}""")) as BundleRead.Refused).code)
        assertEquals("DAMAGED", (FormBundles.read(byteArrayOf(1, 2, 3)) as BundleRead.Refused).code)
        assertEquals("NOT_A_FORM", (FormBundles.read(gzip("""{"v":2,"form":{"title":"x"},"sections":[],"questions":[{"sectionCode":"Z","prompt":"?"}]}""")) as BundleRead.Refused).code)
        val noIds = String(java.util.zip.GZIPInputStream(FormBundles.encode(bundle).inputStream()).readBytes())
        assertTrue("no ids, owner or author travel", listOf("\"id\"", "createdById", "ownerId").none { noIds.contains(it) })

        val outbox = FormAuthoringOutbox(InMemoryFormQueueStore())
        assertNotNull(outbox.queueBundle(read.bundle, "p2"))
        assertNull("the same bundle twice is added once", outbox.queueBundle(read.bundle, "p2"))
        assertEquals(1, outbox.localForms().size)
    }

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()
}
