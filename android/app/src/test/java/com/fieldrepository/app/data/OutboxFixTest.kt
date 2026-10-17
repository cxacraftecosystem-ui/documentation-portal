package com.fieldrepository.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** B1: a refused outbox save can be fixed and sent, matched to an existing artisan, or discarded. */
class OutboxFixTest {
    private val dir: File = Files.createTempDirectory("outbox-fix").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val artisanBody =
        """{"name":"Ramesh Chhipa","aadhaarNumber":"234123412346","experienceYears":12,"craftId":"c1","place":"Bagru","notes":null}"""

    private fun refused(
        fields: List<String> = listOf("aadhaarNumber"),
        kind: String = OutboxKind.CREATE,
        createdId: String? = null,
    ) = PendingEntry(
        id = "e1",
        type = "artisan",
        payloadJson = artisanBody,
        label = "Ramesh Chhipa",
        createdAt = "2026-10-17T09:00:00Z",
        kind = kind,
        createdId = createdId,
        failure = "That Aadhaar number fails its checksum.",
        failureFields = fields,
    )

    @Test
    fun `a 422 names its fields by loc, without the leading body`() {
        val detail = Json.parseToJsonElement(
            """[{"loc":["body","aadhaarNumber"],"msg":"Value error, bad"},{"loc":["body","answers",3,"answerText"],"msg":"x"},{"loc":["body"],"msg":"whole"}]"""
        )
        assertEquals(listOf("aadhaarNumber", "answers.3.answerText"), refusalFieldPaths(detail))
    }

    @Test
    fun `an identity clash names its field`() {
        val detail = Json.parseToJsonElement("""{"code":"artisan_identity_conflict","field":"aadhaarNumber","message":"m"}""")
        assertEquals(listOf("aadhaarNumber"), refusalFieldPaths(detail))
        assertEquals(emptyList<String>(), refusalFieldPaths(JsonPrimitive("plain")))
    }

    @Test
    fun `the named field comes first and highlighted, ids and machine fields are never offered`() {
        val fields = editablePayloadFields(artisanBody, listOf("aadhaarNumber"))
        assertEquals("aadhaarNumber", fields.first().path)
        assertTrue(fields.first().highlighted)
        assertEquals("Aadhaar number", fields.first().label)
        assertFalse(fields.any { it.path == "craftId" })
        assertTrue(fields.filterNot { it.highlighted }.map { it.path }.containsAll(listOf("name", "experienceYears", "place", "notes")))
    }

    @Test
    fun `an edit is written back as the field's own type and nothing else in the body moves`() {
        val fields = editablePayloadFields(artisanBody, listOf("aadhaarNumber"))
        val fixed = Json.parseToJsonElement(
            applyPayloadEdits(artisanBody, fields, mapOf("aadhaarNumber" to "499118665246", "experienceYears" to "14"))
        ).jsonObject
        assertEquals("499118665246", fixed["aadhaarNumber"]!!.jsonPrimitive.content)
        assertTrue(fixed["aadhaarNumber"]!!.jsonPrimitive.isString)
        assertEquals("14", fixed["experienceYears"]!!.jsonPrimitive.content)
        assertFalse(fixed["experienceYears"]!!.jsonPrimitive.isString)
        assertEquals("c1", fixed["craftId"]!!.jsonPrimitive.content)
        assertEquals("Bagru", fixed["place"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a nested path the server named can be corrected`() {
        val body = """{"title":"T","answers":[{"questionId":"q1","answerText":"a"},{"questionId":"q2","answerText":"b"}]}"""
        val fields = editablePayloadFields(body, listOf("answers.1.answerText"))
        assertEquals("Answer 2", fields.first().label)
        val fixed = Json.parseToJsonElement(applyPayloadEdits(body, fields, mapOf("answers.1.answerText" to "fixed"))) as JsonObject
        assertEquals("fixed", fixed["answers"]!!.let { (it as kotlinx.serialization.json.JsonArray)[1].jsonObject["answerText"]!!.jsonPrimitive.content })
    }

    @Test
    fun `only a body that never reached the server is offered for fixing`() {
        assertTrue(refused().isFixable())
        assertFalse("the record is saved; only files are left", refused(createdId = "r1").isFixable())
        assertFalse(refused(kind = OutboxKind.ATTACH).isFixable())
        assertFalse(refused().copy(failure = null).isFixable())
    }

    @Test
    fun `use existing artisan is offered on a create that clashed with a named artisan`() {
        assertTrue(refused().copy(existingRecordId = "a9").offersExistingRecord())
        assertFalse(refused().offersExistingRecord())
        assertFalse(refused(kind = OutboxKind.UPDATE).copy(existingRecordId = "a9").offersExistingRecord())
    }

    @Test
    fun `an entry refused by an older build still decodes, with nothing named`() {
        val queue = File(dir, "queue.json")
        queue.writeText(
            """[{"id":"e1","type":"artisan","payloadJson":"{}","label":"Old","createdAt":"2026-10-01T00:00:00Z","failure":"No","failedAt":"2026-10-02T00:00:00Z"}]"""
        )
        val old = OfflineQueueFile.read(queue) { error("damaged: $it") }.single()
        assertEquals(emptyList<String>(), old.failureFields)
        assertNull(old.failureCode)
        assertNull(old.existingRecordId)
        assertTrue(old.isFixable())
    }

    @Test
    fun `the new refusal fields round-trip through the queue file`() {
        val queue = File(dir, "queue.json")
        val entry = refused().copy(failureCode = "artisan_identity_conflict", existingRecordId = "a9", existingRecordLabel = "Ramesh Chhipa, Bagru")
        OfflineQueueFile.write(queue, listOf(entry))
        assertEquals(entry, OfflineQueueFile.read(queue) { error(it) }.single())
    }

    @Test
    fun `the discard and use-existing sentences say what goes and what stays`() {
        assertEquals(
            "“Ramesh Chhipa” and its 3 photos and recordings will be deleted from this phone. This can't be undone. The record already on the server is not touched.",
            outboxDiscardSentence("Ramesh Chhipa", 3, clash = true)
        )
        assertEquals("“Pot” will be deleted from this phone. This can't be undone.", outboxDiscardSentence("Pot", 0, clash = false))
        assertEquals(
            "This copy of “Ramesh Chhipa” was dropped. Its 2 files go to Ramesh Chhipa, Bagru.",
            useExistingSentence("Ramesh Chhipa", "Ramesh Chhipa, Bagru", 2)
        )
    }
}
