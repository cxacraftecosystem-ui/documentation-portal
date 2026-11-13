package com.fieldrepository.app.questionnaire.ui

import com.fieldrepository.app.questionnaire.data.QSessionPatch
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.time.LocalDate

class SessionStaleRetryTest {
    private val base = SessionEdit(
        date = LocalDate.parse("2026-11-13"), purpose = "FIRST_SITTING", place = "Bagru",
        languageCode = "hi", languageOther = "", note = "", crafts = listOf("Block printing")
    )

    private fun stale(): HttpException = HttpException(
        Response.error<Any>(409, """{"detail":{"code":"STALE_VERSION","message":"Stale"}}""".toResponseBody("application/json".toMediaType()))
    )

    private fun conflictOther(): HttpException = HttpException(
        Response.error<Any>(409, """{"detail":{"code":"PARTICIPANT_HAS_TAKES","message":"No"}}""".toResponseBody("application/json".toMediaType()))
    )

    @Test fun `only a 409 that names STALE_VERSION is a stale version`() {
        assertTrue(stale().isStaleVersion())
        assertFalse(conflictOther().isStaleVersion())
        assertFalse(IllegalStateException("x").isStaleVersion())
    }

    @Test fun `a stale save refetches, re-applies the user's own edits on the new version and retries once`() = runBlocking {
        val mine = base.copy(note = "After the recording")
        val server = base.copy(place = "Sanganer")
        val sent = mutableListOf<QSessionPatch>()
        var fetched = 0
        val result = SessionStaleRetry.save(
            version = 3, initial = base, mine = mine,
            patch = { body -> sent += body; if (sent.size == 1) throw stale() },
            fetch = { fetched++; 5 to server },
            isStale = { it.isStaleVersion() }
        )
        assertEquals(SessionEditResult.Saved, result)
        assertEquals(1, fetched)
        assertEquals(2, sent.size)
        assertEquals(3, sent[0].version)
        assertEquals(5, sent[1].version)
        assertEquals("After the recording", sent[1].note)
        assertNull("the server's new place is not overwritten", sent[1].place)
        assertNull(sent[1].sessionDate)
        assertNull(sent[1].crafts)
    }

    @Test fun `when the other device changed the same field, nothing is sent and the field-level diff is shown`() = runBlocking {
        val mine = base.copy(place = "Jaipur")
        val server = base.copy(place = "Sanganer")
        val sent = mutableListOf<QSessionPatch>()
        val result = SessionStaleRetry.save(
            version = 3, initial = base, mine = mine,
            patch = { body -> sent += body; throw stale() },
            fetch = { 4 to server },
            isStale = { it.isStaleVersion() }
        )
        assertEquals(1, sent.size)
        val conflict = result as SessionEditResult.Conflict
        assertEquals(listOf(SessionFieldDiff(SessionEditField.PLACE, "Jaipur", "Sanganer")), conflict.fields)
        assertEquals(QuestionnaireCopy.s("start.place"), QuestionnaireCopy.s(conflict.fields.single().field.labelKey))
    }

    @Test fun `a second stale answer stops after one retry with the diff`() = runBlocking {
        val mine = base.copy(note = "Mine")
        val sent = mutableListOf<QSessionPatch>()
        var fetches = 0
        val result = SessionStaleRetry.save(
            version = 1, initial = base, mine = mine,
            patch = { body -> sent += body; throw stale() },
            fetch = { fetches++; if (fetches == 1) 2 to base else 3 to base.copy(note = "Theirs") },
            isStale = { it.isStaleVersion() }
        )
        assertEquals(2, sent.size)
        val conflict = result as SessionEditResult.Conflict
        assertEquals(listOf(SessionFieldDiff(SessionEditField.NOTE, "Mine", "Theirs")), conflict.fields)
    }

    @Test fun `the same change on both devices is not a conflict`() {
        val mine = base.copy(place = "Sanganer")
        val outcome = SessionStaleRetry.resolve(base, mine, base.copy(place = "Sanganer"), 9)
        assertEquals(StaleOutcome.Retry(null), outcome)
    }

    @Test fun `any other error is not swallowed`() {
        val thrown = runCatching {
            runBlocking {
                SessionStaleRetry.save(1, base, base.copy(note = "x"), patch = { throw conflictOther() }, fetch = { 1 to base }, isStale = { it.isStaleVersion() })
            }
        }.exceptionOrNull()
        assertTrue(thrown is HttpException)
    }
}
