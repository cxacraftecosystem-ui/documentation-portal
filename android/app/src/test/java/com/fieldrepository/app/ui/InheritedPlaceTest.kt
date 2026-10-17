package com.fieldrepository.app.ui

import com.fieldrepository.app.data.LocationRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C17: a child record's stated address is filled from its artisan, marked, and never overwrites. */
class InheritedPlaceTest {

    private val ramesh = InheritedPlace(
        fromName = "Ramesh Chhipa",
        sourceId = "a1",
        state = "Rajasthan",
        district = "Jaipur",
        village = "Bagru"
    )

    @Test
    fun `empty boxes take the artisan's state, district and village`() {
        val result = inheritStatedAddress("", "", "", ramesh)
        assertEquals("Rajasthan", result.state)
        assertEquals("Jaipur", result.district)
        assertEquals("Bagru", result.village)
        assertEquals(listOf("state", "district", "village"), result.filled)
        assertEquals("state, district and village", inheritedFieldWords(result.filled))
    }

    @Test
    fun `a box somebody already answered is never replaced`() {
        val result = inheritStatedAddress("Gujarat", "", "Bhuj", ramesh)
        assertEquals("Gujarat", result.state)
        // The artisan's district belongs to another state, so it is not put under this one.
        assertEquals("", result.district)
        assertEquals("Bhuj", result.village)
        assertTrue(result.filled.isEmpty())
    }

    @Test
    fun `the district is taken when the state already agrees`() {
        val result = inheritStatedAddress("rajasthan", "", "", ramesh)
        assertEquals("Jaipur", result.district)
        assertEquals(listOf("district", "village"), result.filled)
        assertEquals("district and village", inheritedFieldWords(result.filled))
    }

    @Test
    fun `the requirement message names the heading the form draws, not an artisan it may not have`() {
        val noState = LocationRequest(latitude = 22.3, longitude = 87.3)
        assertEquals(
            "Choose the state under 'Workshop location' before saving.",
            artisanLocationRequirementError(noState, "Workshop location")
        )
        assertEquals(
            "Choose the district under 'Artisan location' before saving.",
            artisanLocationRequirementError(noState.copy(state = "Rajasthan"))
        )
    }

    @Test
    fun `the pincode is offered too, but only inside the artisan's own state`() {
        val withPin = ramesh.copy(pincode = "303007")
        val empty = inheritStatedAddress("", "", "", withPin)
        assertEquals("303007", empty.pincode)
        assertEquals(listOf("state", "district", "village", "pincode"), empty.filled)
        assertEquals("state, district, village and pincode", inheritedFieldWords(empty.filled))
        val elsewhere = inheritStatedAddress("Gujarat", "", "", withPin)
        assertEquals("", elsewhere.pincode)
        val typed = inheritStatedAddress("", "", "", withPin, pincode = "302001")
        assertEquals("a typed pincode is never replaced", "302001", typed.pincode)
    }

    @Test
    fun `both clients fill the boxes the shared contract names and write no provenance key`() {
        val contract = Json.parseToJsonElement(repoSource("shared/stated-address-prefill-contract.json", "../shared/stated-address-prefill-contract.json")).jsonObject
        assertEquals(listOf("state", "district", "village", "pincode"), contract["fields"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonNull, contract["provenanceMetadataKey"])
        for (vector in contract["vectors"]!!.jsonArray.map { it.jsonObject }) {
            fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content
            val current = vector["current"]!!.jsonObject
            val artisan = vector["artisan"]!!.jsonObject
            val expect = vector["expect"]!!.jsonObject
            val result = inheritStatedAddress(
                current.text("state"), current.text("district"), current.text("village"),
                InheritedPlace("A", "a1", artisan.text("state"), artisan.text("district"), artisan.text("village"), artisan.text("pincode")),
                pincode = current.text("pincode")
            )
            val name = vector.text("name")
            assertEquals(name, expect.text("state"), result.state)
            assertEquals(name, expect.text("district"), result.district)
            assertEquals(name, expect.text("village"), result.village)
            assertEquals(name, expect.text("pincode"), result.pincode)
        }
        val source = repoSource(
            "android/app/src/main/java/com/fieldrepository/app/ui/LocationFields.kt",
            "src/main/java/com/fieldrepository/app/ui/LocationFields.kt",
        )
        assertFalse("the web writes no statedFrom, so Android must not either", source.contains("\"statedFrom\""))
    }

    @Test
    fun `the line under the location heading follows the heading`() {
        assertTrue(locationSubtitle("Artisan location").startsWith("Where the artisan works."))
        assertTrue(locationSubtitle("Craft location").startsWith("Where the craft is practised."))
        assertTrue(locationSubtitle("Workshop location").startsWith("Where the workshop is held."))
        assertFalse(locationSubtitle("Workshop location").contains("artisan"))
        assertEquals("A map pin fills in the state, district and pincode.", locationSubtitle("Location"))
    }
}
