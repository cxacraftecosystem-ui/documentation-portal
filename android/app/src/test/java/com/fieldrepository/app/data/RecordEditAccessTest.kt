package com.fieldrepository.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordEditAccessTest {
    @Test
    fun a_submittable_workshop_has_no_refusal() {
        assertNull(submissionRefusalReason(canSubmit = true, accessLevel = "VIEW", requestStatus = "PENDING"))
    }

    @Test
    fun each_refusal_says_the_real_reason() {
        assertEquals(
            "Your access request for this workshop is waiting on an admin.",
            submissionRefusalReason(false, null, "PENDING"),
        )
        assertEquals(
            "Your access request for this workshop was denied. Ask an admin to reconsider.",
            submissionRefusalReason(false, null, "DENIED"),
        )
        assertEquals(
            "Your access to this workshop was revoked. Request access again if you need it.",
            submissionRefusalReason(false, "VIEW", "REVOKED"),
        )
        assertEquals(
            "Your access to this workshop is View only. Ask an admin to raise it to Contribute.",
            submissionRefusalReason(false, "VIEW", "GRANTED"),
        )
        assertEquals(
            "Your access to this workshop is View only. Ask an admin to raise it to Contribute.",
            submissionRefusalReason(false, "view", null),
        )
        assertEquals(
            "You're not assigned to this workshop. Ask an admin to assign you.",
            submissionRefusalReason(false, null, null),
        )
    }

    @Test
    fun the_check_answer_carries_the_access_fields_and_older_answers_still_decode() {
        val json = Json { ignoreUnknownKeys = true }
        val fresh = json.decodeFromString(
            WorkshopSubmissionCheckDto.serializer(),
            """{"workshopId":"w1","assigned":false,"canSubmit":false,"accessLevel":null,"requestStatus":"PENDING"}""",
        )
        assertEquals("Your access request for this workshop is waiting on an admin.", fresh.refusalReason())
        val older = json.decodeFromString(WorkshopSubmissionCheckDto.serializer(), """{"canSubmit":false}""")
        assertEquals("You're not assigned to this workshop. Ask an admin to assign you.", older.refusalReason())
    }

    @Test
    fun fill_empty_locks_only_filled_fields() {
        assertTrue(fieldLocked("FILL_EMPTY", "Ramesh"))
        assertFalse(fieldLocked("FILL_EMPTY", ""))
        assertFalse(fieldLocked("FILL_EMPTY", null))
        assertFalse(fieldLocked("FULL", "Ramesh"))
        assertFalse(fieldLocked(null, "Ramesh"))
        assertEquals(FILL_EMPTY_NOTE, editAccessNote("FILL_EMPTY"))
        assertEquals(VIEW_ONLY_NOTE, editAccessNote("NONE"))
        assertNull(editAccessNote("FULL"))
        assertNull(editAccessNote(null))
    }

    @Test
    fun detail_answers_without_edit_access_still_decode() {
        val json = Json { ignoreUnknownKeys = true }
        val detail = json.decodeFromString(ArtisanDetailDto.serializer(), """{"id":"a1","name":"Ramesh"}""")
        assertNull(detail.editAccess)
        val limited = json.decodeFromString(ToolDetailDto.serializer(), """{"id":"t1","editAccess":"FILL_EMPTY"}""")
        assertEquals("FILL_EMPTY", limited.editAccess)
    }

    @Test
    fun only_refusals_keep_the_form_open_for_a_retry() {
        assertTrue(isEditRefusal(403))
        assertTrue(isEditRefusal(409))
        assertTrue(isEditRefusal(422))
        assertFalse(isEditRefusal(500))
        assertFalse(isEditRefusal(401))
    }
}
