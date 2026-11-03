package com.fieldrepository.app.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementMarkersTest {

    private fun geometry() = geometryMarker(TECHNIQUE_SCALE)

    private fun methodOf(body: JsonObject?, column: String): String? =
        (body?.get(column) as? JsonObject)?.get("method")?.jsonPrimitive?.content

    @Test
    fun `an untouched acceptance is marked`() {
        val markers = MeasurementMarkers()
        markers.accept("lengthInches", "12.4", geometry())

        val body = markers.body(mapOf("lengthInches" to "12.4"))

        assertEquals("PHOTO_GEOMETRY", methodOf(body, "lengthInches"))
        assertEquals(
            "the technique rides along so a later reader can repeat the geometry",
            "SCALE",
            (body?.get("lengthInches") as JsonObject)["technique"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `typing over an accepted reading drops its marker`() {
        val markers = MeasurementMarkers()
        markers.accept("lengthInches", "12.4", geometry())

        val body = markers.body(mapOf("lengthInches" to "13"))

        assertNull(
            "a PHOTO_GEOMETRY marker on a hand-typed number is a false claim, and UNRECORDED is honest",
            body,
        )
    }

    @Test
    fun `clearing an accepted reading drops its marker`() {
        val markers = MeasurementMarkers()
        markers.accept("heightInches", "8", geometry())

        assertNull("an empty box carries no number and must carry no method", markers.body(mapOf("heightInches" to "")))
        assertNull("and the same for a box that is not in the request at all", markers.body(emptyMap()))
    }

    @Test
    fun `a changed digit drops the marker even when the number is nearly the same`() {
        val markers = MeasurementMarkers()
        markers.accept("breadthInches", "12.0", geometry())

        assertNull(markers.body(mapOf("breadthInches" to "12.00")))
    }

    @Test
    fun `whitespace alone DOES drop the marker, because the browser says so and the two must agree`() {
        val markers = MeasurementMarkers()
        markers.accept("lengthInches", "12.4", geometry())

        assertNull(
            "a box whose text is not character-for-character what the route wrote must carry no " +
                "claim about how the number was obtained, whatever the difference is",
            markers.body(mapOf("lengthInches" to " 12.4 ")),
        )

        assertNotNull(markers.body(mapOf("lengthInches" to "12.4")))
    }

    @Test
    fun `retyping the identical digits by hand is a typed number, and forget is what says so`() {
        val markers = MeasurementMarkers()
        markers.accept("lengthInches", "12.4", geometry())

        markers.forget("lengthInches")

        assertNull(markers.body(mapOf("lengthInches" to "12.4")))
    }

    @Test
    fun `forgetting one dimension leaves the others marked, and forgetting nothing is harmless`() {
        val markers = MeasurementMarkers()
        markers.accept("lengthInches", "12.4", geometry())
        markers.accept("breadthInches", "6.1", geometry())

        markers.forget("breadthInches")
        markers.forget("heightInches")
        markers.forget("height")

        val body = markers.body(mapOf("lengthInches" to "12.4", "breadthInches" to "6.1"))
        assertEquals("PHOTO_GEOMETRY", methodOf(body, "lengthInches"))
        assertNull(methodOf(body, "breadthInches"))
    }

    @Test
    fun `editing one dimension leaves the others marked`() {
        val markers = MeasurementMarkers()
        markers.accept("lengthInches", "12.4", geometry())
        markers.accept("breadthInches", "6.1", geometry())

        val body = markers.body(mapOf("lengthInches" to "99", "breadthInches" to "6.1"))

        assertNull("the one that was typed over", methodOf(body, "lengthInches"))
        assertEquals("the one that was not", "PHOTO_GEOMETRY", methodOf(body, "breadthInches"))
    }

    @Test
    fun `a value the column could not store is never marked`() {
        val markers = MeasurementMarkers()
        markers.accept("lengthInches", "12,5", geometry())
        assertNull("an unparseable acceptance is refused outright", markers.body(mapOf("lengthInches" to "12,5")))

        val typed = MeasurementMarkers()
        typed.accept("lengthInches", "12.4", geometry())
        assertNull(
            "and a box that has since become unparseable drops its marker too",
            typed.body(mapOf("lengthInches" to "12.4 inches")),
        )
    }

    @Test
    fun `a vision reading echoes the server's own marker verbatim`() {
        val fromServer = buildJsonObject {
            put("method", JsonPrimitive("VISION_MODEL"))
            put("provider", JsonPrimitive("gemini"))
            put("modelId", JsonPrimitive("gemini-2.5-flash-lite"))
            put("selfReportedConfidence", JsonPrimitive(0.8))
        }
        val markers = MeasurementMarkers()
        markers.accept("lengthInches", "12", visionMarker(fromServer))

        val marker = markers.body(mapOf("lengthInches" to "12"))?.get("lengthInches") as JsonObject

        assertEquals(
            "rebuilding it here would lose the confidence, which is the only number on the stamp",
            fromServer,
            marker,
        )
    }

    @Test
    fun `a server that sent no marker still records that a model produced the number`() {
        val marker = visionMarker(null)

        assertEquals("VISION_MODEL", marker["method"]?.jsonPrimitive?.content)
        assertNull("nothing is invented to fill the gap", marker["provider"])
        assertNull(marker["modelId"])
        assertNull(marker["selfReportedConfidence"])
    }

    @Test
    fun `an unknown technique is dropped rather than sent`() {
        val marker = geometryMarker("TRIANGULATED")

        assertEquals("PHOTO_GEOMETRY", marker["method"]?.jsonPrimitive?.content)
        assertNull(marker["technique"])
    }

    @Test
    fun `both geometry techniques are spelled the way the geometry module spells them`() {
        assertEquals(PhotoMeasure.METHOD_SCALE, TECHNIQUE_SCALE)
        assertEquals(PhotoMeasure.METHOD_RECTIFIED, TECHNIQUE_RECTIFIED)
        assertEquals("SCALE", geometryMarker(PhotoMeasure.METHOD_SCALE)["technique"]?.jsonPrimitive?.content)
        assertEquals("RECTIFIED", geometryMarker(PhotoMeasure.METHOD_RECTIFIED)["technique"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a column outside the documented three can never be marked`() {
        val markers = MeasurementMarkers()
        listOf("height", "width", "thickness", "weight", "radius", "costOfMaking").forEach {
            markers.accept(it, "5", geometry())
        }

        assertNull(markers.body(mapOf("height" to "5", "width" to "5", "costOfMaking" to "5")))
    }

    @Test
    fun `every markable column is one the server allows`() {
        assertEquals(setOf("lengthInches", "breadthInches", "heightInches"), MEASUREMENT_DIMENSIONS)
    }

    @Test
    fun `a form nobody accepted anything on sends no key at all`() {
        assertNull(MeasurementMarkers().body(mapOf("lengthInches" to "12", "breadthInches" to "6")))
    }

    @Test
    fun `a body with nothing to say carries no measurementMethods key on the wire`() {
        val body = ProductCreateRequest(
            craftName = "Pattachitra",
            place = "Raghurajpur",
            artisanName = "A. Maharana",
            productName = "Palm leaf box",
            lengthInches = 12.4,
            measurementMethods = null,
        )

        val wire = ApiClient.json.encodeToString(ProductCreateRequest.serializer(), body)

        assertFalse("an absent key is refused by nothing, ever", wire.contains("measurementMethods"))
        assertTrue(wire.contains("\"lengthInches\""))
    }

    @Test
    fun `a marked body does carry the key`() {
        val markers = MeasurementMarkers()
        markers.accept("heightInches", "8.25", geometry())
        val body = ToolCreateRequest(
            craftName = "Pattachitra",
            place = "Raghurajpur",
            artisanName = "A. Maharana",
            toolkitName = "Brush set",
            heightInches = 8.25,
            measurementMethods = markers.body(mapOf("heightInches" to "8.25")),
        )

        val wire = ApiClient.json.encodeToString(ToolCreateRequest.serializer(), body)

        assertTrue(wire.contains("\"measurementMethods\""))
        assertTrue(wire.contains("PHOTO_GEOMETRY"))
    }

    @Test
    fun `the marker survives the outbox`() {
        val outbox = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val markers = MeasurementMarkers()
        markers.accept("lengthInches", "12.4", geometry())

        val body = ProductCreateRequest(
            craftName = "Pattachitra",
            place = "Raghurajpur",
            artisanName = "A. Maharana",
            productName = "Palm leaf box",
            lengthInches = 12.4,
            measurementMethods = markers.body(mapOf("lengthInches" to "12.4")),
        )

        val wire = outbox.encodeToString(ProductCreateRequest.serializer(), body)
        val restored = outbox.decodeFromString(ProductCreateRequest.serializer(), wire)

        assertEquals(body.measurementMethods, restored.measurementMethods)
        assertEquals("PHOTO_GEOMETRY", methodOf(restored.measurementMethods, "lengthInches"))
    }

    @Test
    fun `an unmarked entry queued by that same outbox still posts without the key`() {
        val outbox = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val queued = outbox.encodeToString(
            ProductCreateRequest.serializer(),
            ProductCreateRequest(
                craftName = "Pattachitra",
                place = "Raghurajpur",
                artisanName = "A. Maharana",
                productName = "Palm leaf box",
                lengthInches = 12.4,
            ),
        )
        assertTrue("the stored payload does carry the null", queued.contains("\"measurementMethods\":null"))

        val replayed = outbox.decodeFromString(ProductCreateRequest.serializer(), queued)
        val posted = ApiClient.json.encodeToString(ProductCreateRequest.serializer(), replayed)

        assertFalse("but what is POSTED does not", posted.contains("measurementMethods"))
    }

    @Test
    fun `an entry queued before this key existed still replays`() {
        val outbox = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val legacy = """{"craftName":"Pattachitra","place":"Raghurajpur","artisanName":"A. Maharana",
            |"productName":"Palm leaf box","lengthInches":12.4}""".trimMargin()

        val restored = outbox.decodeFromString(ProductCreateRequest.serializer(), legacy)

        assertNull(restored.measurementMethods)
        assertTrue(restored.lengthInches == 12.4)
    }
}
