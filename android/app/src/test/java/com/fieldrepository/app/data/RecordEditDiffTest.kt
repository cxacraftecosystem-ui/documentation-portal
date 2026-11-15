package com.fieldrepository.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordEditDiffTest {
    private val stored = ArtisanDetailDto(
        id = "a1",
        name = "Ramesh",
        phone = "+91 98765 43210",
        phoneCountryCode = "+91",
        email = "ramesh@example.org",
        place = "Bhuj",
        dateOfBirth = "1980-04-02T00:00:00Z",
        craftStartDate = "2000-01-01T00:00:00+00:00",
        experienceYears = 26,
        dos = "Keep tools clean\nUse natural dyes",
        donts = "Rush the drying",
        craftId = "c1",
        workshopId = "w1",
        status = "PENDING",
        location = LocationDto(latitude = 23.25, longitude = 69.67, state = "Gujarat"),
        editAccess = "FILL_EMPTY",
    )

    private fun unchangedRequest() = ArtisanCreateRequest(
        name = "Ramesh",
        phone = "9876543210",
        phoneCountryCode = "+91",
        email = "ramesh@example.org",
        place = "Bhuj",
        dateOfBirth = "1980-04-02",
        craftStartDate = "2000-01-01",
        experienceYears = 26,
        pehchanCardAvailable = true,
        dos = "Keep tools clean\nUse natural dyes",
        donts = "Rush the drying",
        craftId = "c1",
        workshopId = "w1",
        status = "PENDING",
        location = null,
        formContract = ARTISAN_FORM_CONTRACT,
    )

    private fun sentBody(request: ArtisanCreateRequest): JsonObject {
        val changed = editChangedKeys(
            ArtisanCreateRequest.serializer(),
            request,
            recordBase(ArtisanDetailDto.serializer(), stored),
        )
        return editPatchBody(ArtisanCreateRequest.serializer(), request, changed, ARTISAN_CLEARABLE_COLUMNS)
    }

    @Test
    fun an_unchanged_artisan_edit_sends_only_the_form_contract() {
        val body = sentBody(unchangedRequest())
        assertEquals(setOf("formContract"), body.keys)
        assertEquals(ARTISAN_FORM_CONTRACT.toString(), body["formContract"]!!.jsonPrimitive.content)
    }

    @Test
    fun date_and_phone_formatting_are_not_changes() {
        assertTrue(sameMoment("1980-04-02", "1980-04-02T00:00:00Z"))
        assertTrue(sameMoment("1980-04-02", "1980-04-02T00:00:00+00:00"))
        assertTrue(sameMoment("2026-11-10T08:00:00Z", "2026-11-10T08:00:00.000+00:00"))
        assertFalse(sameMoment("1980-04-03", "1980-04-02T00:00:00Z"))
        assertTrue(samePhone("9876543210", "+91 98765 43210"))
        assertFalse(samePhone("9876543210", "9123456780"))
        assertTrue(sameRecordValue("phone", JsonPrimitive("9876543210"), JsonPrimitive("98765-43210")))
        assertTrue(sameRecordValue("lengthInches", JsonPrimitive(12.5), JsonPrimitive("12.50")))
    }

    @Test
    fun a_real_change_is_sent() {
        val body = sentBody(unchangedRequest().copy(place = "Anjar", experienceYears = 27))
        assertEquals(setOf("formContract", "place", "experienceYears"), body.keys)
        assertEquals("Anjar", body["place"]!!.jsonPrimitive.content)
    }

    @Test
    fun a_cleared_field_is_sent_as_null_and_untouched_empty_columns_are_not() {
        val body = sentBody(unchangedRequest().copy(email = null))
        assertEquals(setOf("formContract", "email"), body.keys)
        assertEquals(JsonNull, body["email"])
        assertFalse("notes" in body)
        assertFalse("location was not touched", "location" in body)
    }

    @Test
    fun without_a_loaded_record_the_whole_body_is_sent_as_before() {
        assertNull(editChangedKeys(ArtisanCreateRequest.serializer(), unchangedRequest(), null))
        val body = editPatchBody(ArtisanCreateRequest.serializer(), unchangedRequest(), null, ARTISAN_CLEARABLE_COLUMNS)
        assertTrue(setOf("name", "place", "phone", "craftId", "formContract").all { it in body })
        assertFalse("nulls are never sent on the full body", body.values.any { it == JsonNull })
    }

    @Test
    fun tool_links_compare_as_sets() {
        val toolRecord = ToolDetailDto(
            id = "t1",
            toolkitName = "Chisel",
            craftLinks = listOf(ToolCraftLinkDto(craftId = "c1"), ToolCraftLinkDto(craftId = "c2")),
        )
        val base = recordBase(ToolDetailDto.serializer(), toolRecord)!!.withLinkIds(
            "craftIds" to toolRecord.craftLinks.map { it.craftId },
        )
        val same = ToolCreateRequest(toolkitName = "Chisel", craftIds = listOf("c2", "c1"))
        assertFalse("craftIds" in editChangedKeys(ToolCreateRequest.serializer(), same, base)!!)
        val added = ToolCreateRequest(toolkitName = "Chisel", craftIds = listOf("c1", "c2", "c3"))
        assertTrue("craftIds" in editChangedKeys(ToolCreateRequest.serializer(), added, base)!!)
    }

    @Test
    fun an_interview_correction_leaves_out_what_is_already_recorded() {
        val record = QuestionnaireInterviewDetailDto(
            id = "i1",
            title = "Lac turning",
            place = "Bhuj",
            notes = "First visit",
            status = "PENDING",
            workshopId = "w1",
        )
        val narrowed = narrowInterviewUpdate(
            QuestionnaireInterviewUpdateRequest(
                title = "Lac turning",
                place = "Bhuj",
                notes = "First visit\nSecond visit",
                status = "PENDING",
                workshopId = "w1",
                artisanIds = null,
            ),
            record,
        )
        assertNull(narrowed.title)
        assertNull(narrowed.place)
        assertNull(narrowed.status)
        assertNull(narrowed.workshopId)
        assertEquals("First visit\nSecond visit", narrowed.notes)
    }

    @Test
    fun reflowed_numbered_points_are_not_a_change() {
        assertTrue(
            sameRecordValue("dos", JsonPrimitive("Keep tools clean\nUse natural dyes"), JsonPrimitive("Keep tools clean\r\n\r\nUse  natural dyes "))
        )
        assertFalse(sameRecordValue("dos", JsonPrimitive("Keep tools clean"), JsonPrimitive("Keep tools clean\nUse natural dyes")))
        assertEquals(
            emptySet<String>(),
            recordEditChangedKeys(
                JsonObject(mapOf("craftIds" to JsonArray(listOf(JsonPrimitive("b"), JsonPrimitive("a"))))),
                JsonObject(mapOf("craftIds" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))))),
            ),
        )
    }
}
