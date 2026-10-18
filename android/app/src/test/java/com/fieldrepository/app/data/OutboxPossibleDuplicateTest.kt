package com.fieldrepository.app.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A CREATE HELD AS A LIKELY DUPLICATE (409 `artisan_possible_duplicate`) is its own outbox arm, never
 * a clash or a refusal: the tray says who it looks like and offers "Same person" / "Different person".
 */
class OutboxPossibleDuplicateTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val holdDetail = json.parseToJsonElement(
        """
        {"code":"artisan_possible_duplicate","message":"This may be Meena Devi (Bhuj), already recorded.",
         "candidates":[{"artisan":{"id":"a1","name":"Meena Devi","place":"Bhuj","craft":"Ajrakh","birthYear":1971},
           "score":0.86,"band":"LIKELY","reasons":[{"code":"NAME","label":"Names 92% alike"}]},
          {"artisan":{"id":"a2","name":"M. Devi","place":"Anjar"},"score":0.62,"band":"POSSIBLE","reasons":[]},
          {"artisan":{"id":"a3","name":"Meenu"},"score":0.40,"band":"POSSIBLE","reasons":[]}]}
        """.trimIndent()
    ).jsonObject

    private fun candidate(id: String, score: Double) = DuplicateCandidateDto(
        artisan = DuplicateCandidateArtisanDto(id = id, name = "Person $id"),
        score = score,
        band = "POSSIBLE"
    )

    private fun entry(
        candidates: List<OutboxDuplicateCandidate> = emptyList(),
        payload: String = """{"name":"Meena","place":"Bhuj","duplicateAcknowledged":[]}"""
    ) = PendingEntry(
        id = "e1",
        type = "artisan",
        payloadJson = payload,
        label = "Meena",
        createdAt = "2026-10-18T00:00:00Z",
        failure = if (candidates.isEmpty()) null else "held",
        duplicateCandidates = candidates
    )

    @Test
    fun the_hold_is_read_from_its_code_and_nothing_else_is() {
        val held = ApiFailure("artisan_possible_duplicate", "x", holdDetail).possibleDuplicates()
        assertEquals(listOf("a1", "a2", "a3"), held!!.map { it.artisan.id })
        assertEquals("Names 92% alike", held[0].reasons.single().label)
        assertNull(ApiFailure("artisan_identity_conflict", "x", holdDetail).possibleDuplicates())
        assertNull(ApiFailure(null, "x").possibleDuplicates())
    }

    @Test
    fun the_sheet_shows_at_most_three_above_the_threshold_and_skips_the_acknowledged() {
        val list = listOf(candidate("a", 0.7), candidate("b", 0.95), candidate("c", 0.59), candidate("d", 0.8), candidate("e", 0.61))
        assertEquals(listOf("b", "d", "a"), PossibleDuplicates.toShow(list, emptySet()).map { it.artisan.id })
        assertEquals(listOf("d", "a", "e"), PossibleDuplicates.toShow(list, setOf("b")).map { it.artisan.id })
        assertEquals((1..20).map { "$it" }, PossibleDuplicates.acknowledged((1..25).map { "$it" } + "1"))
    }

    @Test
    fun the_tray_names_the_top_candidate_and_is_not_a_clash() {
        val held = entry(listOf(OutboxDuplicateCandidate("a1", "Meena Devi", "Bhuj")))
        assertTrue(held.isPossibleDuplicate())
        assertEquals("Not saved yet. This looks like Meena Devi (Bhuj).", possibleDuplicateTrayText(held))
        assertFalse(entry().isPossibleDuplicate())
        assertNull(possibleDuplicateTrayText(entry()))
    }

    @Test
    fun same_person_skips_the_create_and_attaches_to_the_existing_artisan() {
        val answered = entry(listOf(OutboxDuplicateCandidate("a1", "Meena Devi"))).asSamePerson()
        assertEquals("a1", answered.createdId)
        assertTrue(answered.attachedToExisting)
        assertNull(answered.failure)
        assertFalse(answered.isPossibleDuplicate())
    }

    @Test
    fun different_person_resends_with_every_candidate_acknowledged_and_nothing_else_changed() {
        val before = """{"name":"Meena","place":"Bhuj","aadhaarSealed":"v1:abc","duplicateAcknowledged":["a0"]}"""
        val answered = entry(
            listOf(OutboxDuplicateCandidate("a1", "Meena Devi"), OutboxDuplicateCandidate("a0", "Old")),
            payload = before
        ).asDifferentPerson()
        val body = json.parseToJsonElement(answered.payloadJson).jsonObject
        assertEquals(listOf("a0", "a1"), body["duplicateAcknowledged"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("v1:abc", body["aadhaarSealed"]!!.jsonPrimitive.content)
        assertNull(answered.createdId)
        assertNull(answered.failure)
        assertTrue(answered.duplicateCandidates.isEmpty())
    }

    @Test
    fun a_queued_create_carries_an_empty_acknowledgement_and_an_old_entry_carries_none() {
        val wire = Json { explicitNulls = false; encodeDefaults = true }
        val fresh = wire.parseToJsonElement(
            wire.encodeToString(ArtisanCreateRequest(name = "Meena", place = "Bhuj", duplicateAcknowledged = emptyList()))
        ).jsonObject
        assertEquals(0, fresh["duplicateAcknowledged"]!!.jsonArray.size)
        val old = wire.parseToJsonElement(wire.encodeToString(ArtisanCreateRequest(name = "Meena", place = "Bhuj"))).jsonObject
        assertFalse("duplicateAcknowledged" in old)
    }
}
