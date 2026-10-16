package com.fieldrepository.app.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

/**
 * Unsaved form drafts on disk: the value encoding every form field goes through, and the files
 * themselves (per account, per form, per record, kept across sign-out).
 */
class DraftStoreTest {
    private val root: File = Files.createTempDirectory("drafts-test").toFile()
    private val NEW = newDraftPrefix(null)

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `every field type a form holds survives the round trip`() {
        val values = listOf<Any?>(
            "Ajrakh", "", true, 42, 7L, 3.5, LocalDate.of(2026, 10, 16), null,
            listOf("a", "b"), linkedSetOf("x", "y"),
            mapOf("q1" to "answer"), mapOf("section:s1" to listOf("content://a", "content://b")),
            LocationRequest(latitude = 23.25, longitude = 69.67, address = "Bhuj")
        )
        values.forEach { value ->
            val encoded = DraftValues.encode(value)!!
            assertEquals("round trip of $value", value, DraftValues.decode(encoded))
        }
        // A set stays a set, so a restored selection is still a Set<String>.
        assertTrue(DraftValues.decode(DraftValues.encode(setOf("x"))!!) is Set<*>)
    }

    @Test
    fun `server data and UI objects are not saved, and unknown values leave the field alone`() {
        assertNull(DraftValues.encode(listOf(1, 2)))
        assertNull(DraftValues.encode(Any()))
        assertSame(DraftValues.Unparsed, DraftValues.decode(JsonPrimitive("bare")))
    }

    private fun draft(kind: String, key: String, savedAt: String, name: String = "Ajrakh") = FormDraftFile(
        formKind = kind,
        recordKey = key,
        label = name,
        savedAt = savedAt,
        fields = mapOf("name" to DraftValues.encode(name)!!)
    )

    @Test
    fun `drafts are per account, per form and per record`() {
        DraftFiles.write(root, "u1", draft("craft", NEW + "1", "2026-10-16T09:00:00Z"))
        DraftFiles.write(root, "u1", draft("craft", "rec-abc", "2026-10-16T09:05:00Z", "Edited"))
        DraftFiles.write(root, "u2", draft("craft", NEW + "2", "2026-10-16T09:10:00Z", "Theirs"))

        assertEquals("Ajrakh", DraftFiles.latestNew(root, "u1", "craft")!!.label)
        assertEquals("Edited", DraftFiles.read(root, "u1", "craft", "rec-abc")!!.label)
        assertNull(DraftFiles.read(root, "u2", "craft", "rec-abc"))
        assertNull(DraftFiles.latestNew(root, "u1", "artisan"))
        assertEquals(listOf("Edited", "Ajrakh"), DraftFiles.all(root, "u1").map { it.label })
    }

    @Test
    fun `the newest new-record draft is the one offered`() {
        DraftFiles.write(root, "u1", draft("tool", NEW + "old", "2026-10-15T09:00:00Z", "Old"))
        DraftFiles.write(root, "u1", draft("tool", NEW + "new", "2026-10-16T09:00:00Z", "New"))
        assertEquals("New", DraftFiles.latestNew(root, "u1", "tool")!!.label)
    }

    @Test
    fun `a new-record draft belongs to the parent its form was opened from`() {
        val forRam = draft("product", newDraftPrefix("artisan_ram") + "a", "2026-10-16T09:00:00Z", "Ram's pot")
            .copy(parentId = "artisan_ram", parentLabel = "Ram Kumar")
        DraftFiles.write(root, "u1", forRam)
        assertEquals("Ram Kumar", DraftFiles.latestNew(root, "u1", "product", "artisan_ram")!!.parentLabel)
        assertNull("never offered on another artisan's product form", DraftFiles.latestNew(root, "u1", "product", "artisan_sita"))
        assertNull("nor on a product form opened with no artisan", DraftFiles.latestNew(root, "u1", "product"))
    }

    @Test
    fun `deleting a draft removes only that draft`() {
        DraftFiles.write(root, "u1", draft("craft", NEW + "1", "2026-10-16T09:00:00Z"))
        DraftFiles.write(root, "u1", draft("craft", NEW + "2", "2026-10-16T09:01:00Z", "Second"))
        val removed = DraftFiles.delete(root, "u1", "craft", NEW + "2")
        assertEquals("Second", removed!!.label)
        assertEquals(listOf("Ajrakh"), DraftFiles.all(root, "u1").map { it.label })
    }

    @Test
    fun `a damaged draft is set aside, not deleted, and reads as absent`() {
        val file = DraftFiles.file(root, "u1", "craft", NEW + "1")
        file.parentFile!!.mkdirs()
        file.writeText("{\"formKind\":")
        assertNull(DraftFiles.read(file))
        assertFalse(file.exists())
        assertTrue(file.parentFile!!.listFiles()!!.any { it.name.contains(".damaged-") })
    }

    @Test
    fun `the sweep knows which capture files a draft still points at`() {
        val withFiles = draft("craft", NEW + "1", "2026-10-16T09:00:00Z").copy(
            fields = mapOf(
                "media.uris" to kotlinx.serialization.json.JsonObject(
                    mapOf(
                        "t" to JsonPrimitive("uris"),
                        "v" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("content://app.fileprovider/capture_files/field-captures/a.jpg")))
                    )
                )
            ),
            ownedFiles = listOf("/data/files/drafts-media/b.pdf")
        )
        DraftFiles.write(root, "u1", withFiles)
        val referenced = DraftFiles.referencedStrings(root)
        assertTrue(referenced.any { it.endsWith("/a.jpg") })
        assertTrue("/data/files/drafts-media/b.pdf" in referenced)
    }

    @Test
    fun `odd characters in an account or record id cannot escape the drafts folder`() {
        val file = DraftFiles.file(root, "../../etc", "craft", "rec-../x")
        assertTrue(file.canonicalPath.startsWith(root.canonicalPath))
    }
}
