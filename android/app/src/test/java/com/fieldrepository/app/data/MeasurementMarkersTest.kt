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

/**
 * The claim a saved dimension makes about HOW it was measured, and the rule that withdraws it.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THESE ARE TESTS AND NOT A CODE REVIEW
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Both directions of failure are invisible on the screen where they happen.
 *
 * A marker that is WRONGLY KEPT — left on a number somebody typed over after accepting a proposal —
 * renders identically to one that is right. The form shows the researcher's own number, the save
 * succeeds, and the record quietly asserts that a photograph's geometry produced a figure a person
 * typed. Nobody finds out until somebody tries to re-derive it from marks that never existed.
 *
 * A marker that is WRONGLY SENT — naming a dimension this request carries no value for, or a column
 * outside the documented three — is worse than invisible: the server refuses the WHOLE save with a
 * 422, and the outbox will not queue a 4xx because the server saw it and said no. So the record is
 * not merely unmarked, it is GONE, and it was filled in somewhere with no signal.
 *
 * That is the whole reason this logic lives in a pure class with no Compose and no network in it.
 */
class MeasurementMarkersTest {

    private fun geometry() = geometryMarker(TECHNIQUE_SCALE)

    private fun methodOf(body: JsonObject?, column: String): String? =
        (body?.get(column) as? JsonObject)?.get("method")?.jsonPrimitive?.content

    /* ── The claim stands while the number is still the one that was proposed ─────────────────── */

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

    /* ── …and is withdrawn the moment it stops being true ─────────────────────────────────────── */

    @Test
    fun `typing over an accepted reading drops its marker`() {
        val markers = MeasurementMarkers()
        markers.accept("lengthInches", "12.4", geometry())

        // The researcher looked at the object again and typed their own figure.
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

        // Not a rounding detail: the panel rounds to the precision its error bar reaches, so the
        // number of digits is itself part of what was measured.
        assertNull(markers.body(mapOf("breadthInches" to "12.00")))
    }

    @Test
    fun `whitespace alone DOES drop the marker, because the browser says so and the two must agree`() {
        // THE INVERSION OF WHAT THIS TEST ASSERTED UNTIL 2026-09-14, and the reason is worth reading
        // because the old behaviour was the more intuitive one.
        //
        // `body` used to trim both sides, on the reasoning that a stray space does not change the
        // number so a true claim should not be dropped over it. The browser
        // (`frontend/components/forms/measurementMethods.ts`) compares the strings as they stand, so
        // " 12.4 " lost its marker there and kept it here — and each suite asserted its own client's
        // behaviour, so both looked correct while the product stored two different provenances for
        // one researcher action.
        //
        // Settled toward the browser, and not merely because it was first: the rule this mechanism
        // rests on is that the check is on the VALUE and never on the event that changed it. Trimming
        // is an exception carved into exactly that rule, and the failure it risks is the one the
        // whole file exists to prevent — a marker surviving onto a number a person touched. Dropping
        // a true claim costs UNRECORDED, which is honest and distinguishable; keeping a false one is
        // a lie a later reader cannot catch. The tie goes to the cheaper mistake.
        val markers = MeasurementMarkers()
        markers.accept("lengthInches", "12.4", geometry())

        assertNull(
            "a box whose text is not character-for-character what the route wrote must carry no " +
                "claim about how the number was obtained, whatever the difference is",
            markers.body(mapOf("lengthInches" to " 12.4 ")),
        )

        // And the untouched box still keeps it, so the assertion above is about the whitespace and
        // not about the mechanism having stopped working.
        assertNotNull(markers.body(mapOf("lengthInches" to "12.4")))
    }

    /* ── The one case value-equality cannot see, which is why the boxes also forget ───────────── */

    @Test
    fun `retyping the identical digits by hand is a typed number, and forget is what says so`() {
        // THE CASE THE COMPARISON CANNOT CATCH. A researcher deletes the accepted 12.4 and types
        // "12.4" back themselves. The text matches character for character, so `body` alone would keep
        // a PHOTO_GEOMETRY claim about a number nobody measured with a photograph. The dimension boxes
        // call `forget` from their own `onValueChange`, which is the only signal that a PERSON wrote
        // this — and both record forms are wired that way.
        val markers = MeasurementMarkers()
        markers.accept("lengthInches", "12.4", geometry())

        markers.forget("lengthInches") // the first keystroke of the retype

        assertNull(markers.body(mapOf("lengthInches" to "12.4")))
    }

    @Test
    fun `forgetting one dimension leaves the others marked, and forgetting nothing is harmless`() {
        val markers = MeasurementMarkers()
        markers.accept("lengthInches", "12.4", geometry())
        markers.accept("breadthInches", "6.1", geometry())

        markers.forget("breadthInches")
        // Called on every keystroke, including for columns that never had an acceptance and for names
        // outside the three. It is a map removal; nothing about it may throw.
        markers.forget("heightInches")
        markers.forget("height")

        val body = markers.body(mapOf("lengthInches" to "12.4", "breadthInches" to "6.1"))
        assertEquals("PHOTO_GEOMETRY", methodOf(body, "lengthInches"))
        assertNull(methodOf(body, "breadthInches"))
    }

    /* ── One field's edit must not take another field's marker with it ────────────────────────── */

    @Test
    fun `editing one dimension leaves the others marked`() {
        val markers = MeasurementMarkers()
        markers.accept("lengthInches", "12.4", geometry())
        markers.accept("breadthInches", "6.1", geometry())

        val body = markers.body(mapOf("lengthInches" to "99", "breadthInches" to "6.1"))

        assertNull("the one that was typed over", methodOf(body, "lengthInches"))
        assertEquals("the one that was not", "PHOTO_GEOMETRY", methodOf(body, "breadthInches"))
    }

    /* ── A marker is never sent for a value the request cannot carry ──────────────────────────── */

    @Test
    fun `a value the column could not store is never marked`() {
        // Both forms send `lengthInches = length.toDoubleOrNull()`, so a box holding "12,5" — which is
        // what a comma-decimal keyboard produces — sends NO VALUE for that dimension. A marker beside
        // a missing value is a 422 that names the field, and on this platform that is a lost record.
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

    /* ── The two shapes the server accepts, and the one it refuses by name ────────────────────── */

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
        // THE CASE THIS CLIENT IS IN TODAY. `FieldRepository.analyzeMeasurement` returns a bare Double,
        // so `GridMeasurementSection` calls `visionMarker(null)`. The fact that matters most — a model
        // produced this and a person accepted it — is still recorded, and nothing else is invented.
        val marker = visionMarker(null)

        assertEquals("VISION_MODEL", marker["method"]?.jsonPrimitive?.content)
        assertNull("nothing is invented to fill the gap", marker["provider"])
        assertNull(marker["modelId"])
        assertNull(marker["selfReportedConfidence"])
    }

    @Test
    fun `an unknown technique is dropped rather than sent`() {
        // The server refuses a technique outside its own set, and a refusal here costs the whole
        // record. The method is worth recording without it.
        val marker = geometryMarker("TRIANGULATED")

        assertEquals("PHOTO_GEOMETRY", marker["method"]?.jsonPrimitive?.content)
        assertNull(marker["technique"])
    }

    @Test
    fun `both geometry techniques are spelled the way the geometry module spells them`() {
        // PASSED STRAIGHT THROUGH FROM `MeasureResult.Measurement.method`, with no mapping table in
        // between — which is what keeps the handset, the browser and the server from drifting.
        assertEquals(PhotoMeasure.METHOD_SCALE, TECHNIQUE_SCALE)
        assertEquals(PhotoMeasure.METHOD_RECTIFIED, TECHNIQUE_RECTIFIED)
        assertEquals("SCALE", geometryMarker(PhotoMeasure.METHOD_SCALE)["technique"]?.jsonPrimitive?.content)
        assertEquals("RECTIFIED", geometryMarker(PhotoMeasure.METHOD_RECTIFIED)["technique"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a column outside the documented three can never be marked`() {
        val markers = MeasurementMarkers()
        // The tool form's unit-less `height`, and its four other typed boxes. The server answers a
        // marker naming one of these with a 422 that names it, so this must not reach the wire.
        listOf("height", "width", "thickness", "weight", "radius", "costOfMaking").forEach {
            markers.accept(it, "5", geometry())
        }

        assertNull(markers.body(mapOf("height" to "5", "width" to "5", "costOfMaking" to "5")))
    }

    @Test
    fun `every markable column is one the server allows`() {
        // Pins this file's set against the three names the server's dimension-field set holds. A fourth
        // added here without the server agreeing is a 422 on every save that uses it.
        assertEquals(setOf("lengthInches", "breadthInches", "heightInches"), MEASUREMENT_DIMENSIONS)
    }

    @Test
    fun `a form nobody accepted anything on sends no key at all`() {
        // The ordinary case, and the one that must stay byte-for-byte identical to what this app sent
        // before the key existed: null, so `explicitNulls = false` drops it from the body entirely.
        assertNull(MeasurementMarkers().body(mapOf("lengthInches" to "12", "breadthInches" to "6")))
    }

    /* ── The round trip that actually reaches the server ──────────────────────────────────────── */

    @Test
    fun `a body with nothing to say carries no measurementMethods key on the wire`() {
        // THE 422 THIS PREVENTS. The web deploys separately from the API, so a newer client meets an
        // older server whose request models forbid unknown keys — and `"measurementMethods": null`
        // would then be a 422 on the WHOLE save, which the outbox will not queue. Asserted against
        // `ApiClient.json`, the converter's OWN configuration, rather than a copy of it made here: a
        // copy would go on passing after somebody dropped `explicitNulls = false` from the real one.
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
        // ⚠ THE KOTLIN TRAP THIS ASSERTS AWAY: `ignoreUnknownKeys = true` means a field missing from
        // ApiModels.kt is dropped SILENTLY, with no error anywhere. The only way to know the marker
        // actually left the handset is to look at the bytes.
    }

    @Test
    fun `the marker survives the outbox`() {
        // THE POINT OF THIS TEST. A record saved in a courtyard is serialised into the outbox and
        // decoded back out a fortnight later, and if the marker did not survive that trip an offline
        // record would lose its provenance while an online one kept it — the one asymmetry nobody would
        // notice, because both saves succeed.
        //
        // The Json here is `MainActivity.offlineFormJson` / `FieldRepository.offlineJson`, spelled the
        // same way: `encodeDefaults = true` and NO `explicitNulls = false`.
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
        // The outbox serialiser does NOT set `explicitNulls = false`, so an unmarked record is stored
        // as `"measurementMethods": null`. That is harmless for exactly one reason, and it is worth
        // pinning: the stored string is decoded back into the typed body and RE-ENCODED by the
        // Retrofit converter on the way out. Nothing ever posts the stored bytes as a request body.
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
        // A handset may be a fortnight behind, and its outbox older still. An entry written without the
        // key must decode to "no marker" rather than failing the replay.
        val outbox = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val legacy = """{"craftName":"Pattachitra","place":"Raghurajpur","artisanName":"A. Maharana",
            |"productName":"Palm leaf box","lengthInches":12.4}""".trimMargin()

        val restored = outbox.decodeFromString(ProductCreateRequest.serializer(), legacy)

        assertNull(restored.measurementMethods)
        assertTrue(restored.lengthInches == 12.4)
    }
}
