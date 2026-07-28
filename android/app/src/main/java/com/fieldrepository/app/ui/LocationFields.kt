package com.fieldrepository.app.ui

import android.content.Context
import android.location.Geocoder
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import com.fieldrepository.app.PINCODE_LENGTH
import com.fieldrepository.app.data.AddressReferenceDto
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.LocationRequest
import com.fieldrepository.app.matchIndianState
import com.fieldrepository.app.pincodeValidationError
import com.fieldrepository.app.sameCoordinate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * ---------------------------------------------------------------------------
 * TWO LOCATIONS, NOT ONE.
 *
 * THE FINDING. All fifteen artisans on the live database that carry a location sit inside three
 * hundred metres of each other at 22.31 N, 87.31 E — Kharagpur, West Bengal. The places the
 * researchers TYPED are Bagru, Balotra, Kutch, Rudraprayag, Ballupur, Sanganer and Kappaladoddi:
 * Rajasthan, Gujarat, Uttarakhand, Andhra Pradesh. Fifteen hundred kilometres out, every one.
 *
 * The GPS was not broken. The coordinates jitter naturally and carry honest accuracy radii from
 * 26 m to 2.5 km, which is what a real receiver produces. What was broken is what the number MEANT:
 * the field recorded where the DEVICE was when the record was saved, and every reader — the map,
 * the exports, the research dataset — read it as where the ARTISAN is. The pilot data was entered
 * at a desk in Kharagpur about artisans a long way away, which is ordinary and reasonable, and the
 * schema had no way to say so. The researchers had already worked it out for themselves and were
 * hand-encoding "Bagru, Jaipur, Rajasthan" into the free-text place box, because there was nowhere
 * else to put it.
 *
 * SO THE FORM ASKS TWICE, and calls the two answers different things.
 *
 *   ARTISAN LOCATION — state, district, village, and an optional pin. A STATEMENT BY THE
 *   RESEARCHER about the person being documented. This is what the map, the exports and the dataset
 *   use. TWO ACTS may fill it in, and the difference between them is the rule the whole file turns
 *   on. PINNING THE ARTISAN'S PLACE overwrites the state, district and pincode, because pointing at
 *   a place on a map IS a statement about that place and asking somebody to confirm their own
 *   action twice is friction that gets tapped through rather than read. A GPS FIX ARRIVING BY
 *   ITSELF fills only the boxes that are still empty and offers the rest, because the device is
 *   very often at a desk in another state from the artisan. Both announce what they wrote, by name,
 *   with one control that puts back exactly what was there. Nothing here is written silently.
 *
 *   CAPTURED AT — coordinates, accuracy radius, timestamp. Provenance, collapsed by default,
 *   automatic. It says where the phone was, it is labelled as saying that, and it is never
 *   presented as the artisan's location.
 *
 * NOTHING IS BACKFILLED. The fifteen existing records keep their coordinates exactly as recorded.
 * Where the stated location and the captured coordinates disagree the form SAYS SO, in the record's
 * own edit screen, and leaves the correction to the researcher who was there. Guessing a village
 * from a coordinate that was never about the village is how the problem was created.
 *
 * Word for word the same two groups, the same field names and very nearly the same sentences as
 * frontend/components/forms/LocationFields.tsx. A researcher who uses the phone in the workshop and
 * the laptop afterwards should not have to work out that they are the same two questions.
 * ---------------------------------------------------------------------------
 */

// ---------------------------------------------------------------------------------------------
// Where the two new answers are stored
// ---------------------------------------------------------------------------------------------

/*
 * District, village and the subject's pin are REAL COLUMNS — `district`, `village`,
 * `subjectLatitude`, `subjectLongitude` on Location, promoted by migration
 * 20260727120000_location_stated_address. This card reads them and writes them.
 *
 * IT ALSO KEEPS WRITING THE METADATA KEYS BELOW, AND FALLS BACK TO THEM ON READ. That is not
 * belt-and-braces; it is the only arrangement in which a fleet mid-update stays coherent, and it
 * has to hold in BOTH directions:
 *
 *   READ THE COLUMN, THEN THE KEY. Everything this app has ever created keeps the stated address in
 *   `extraMetadata`, because until the migration there was nowhere else to put it. An edit form that
 *   read the column alone would show an empty district box over a record that has a district — and
 *   then save that blank over it, which is exactly the class of silent loss this file exists to end.
 *
 *   WRITE BOTH. A phone that has not taken this update reads only the keys. A build that wrote the
 *   columns alone would make every record it touched look district-less on the other phones in the
 *   same workshop until all of them had updated, which is not a state anybody can see happening.
 *
 * The server accepts either shape and normalises to the columns on the way in (`_stated_district`
 * in schemas/common.py, `lift_stated_address` in services/records.py, guarded by
 * tests/test_android_location_compat.py). None of that is a shim with an expiry date: records
 * written in the metadata form keep it for as long as they exist, and an edit re-sends what it was
 * given. Do not "simplify" either half away.
 *
 * THE KEYS ARE THE COLUMN NAMES, which is what made the promotion a rename of nothing at all —
 * except the pin, whose keys were named before the model settled on calling the documented party
 * the SUBJECT rather than the artisan (a workshop and a tool carry one too). The server maps both
 * spellings onto `subjectLatitude`/`subjectLongitude`.
 */
const val LOCATION_META_DISTRICT: String = "district"
const val LOCATION_META_VILLAGE: String = "village"

/**
 * The artisan's own pin, when the researcher dropped one — the metadata half of
 * [LocationRequest.subjectLatitude] / [LocationRequest.subjectLongitude].
 *
 * Deliberately NOT the row's `latitude`/`longitude`. Those two columns are the captured-at reading
 * and are written by the GPS; a statement about the artisan that shared them would be overwritten
 * by the next fix, which is the entire bug this file exists to end.
 */
const val LOCATION_META_ARTISAN_LAT: String = "artisanLatitude"
const val LOCATION_META_ARTISAN_LNG: String = "artisanLongitude"

/** A metadata value as text, whether it was written as a JSON string or as a JSON number. */
private fun LocationRequest.meta(key: String): String =
    (extraMetadata?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()

/**
 * Replace metadata keys, dropping any whose answer is null.
 *
 * Entries this file knows nothing about are carried through untouched. The API creates a NEW
 * Location row on every update rather than patching the old one, so anything dropped here is
 * dropped permanently — and a client has no business deleting a key it does not recognise.
 */
private fun LocationRequest.withMeta(vararg pairs: Pair<String, JsonPrimitive?>): LocationRequest {
    val merged = LinkedHashMap<String, JsonElement>(extraMetadata.orEmpty())
    pairs.forEach { (key, value) -> if (value == null) merged.remove(key) else merged[key] = value }
    return copy(extraMetadata = if (merged.isEmpty()) null else JsonObject(merged))
}

/** A typed answer as a metadata value, or null when the researcher left the box empty. */
private fun metaText(value: String): JsonPrimitive? =
    value.trim().takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) }

/** The four artisan-location answers, held together so they can be parked before a coordinate exists. */
private data class StatedPlace(
    val state: String = "",
    val district: String = "",
    val village: String = "",
    val pincode: String = "",
    val pinLat: String = "",
    val pinLng: String = ""
) {
    val isEmpty: Boolean
        get() = state.isBlank() && district.isBlank() && village.isBlank() && pincode.isBlank() &&
            pinLat.isBlank() && pinLng.isBlank()
}

/** The column when it holds an answer, the pre-column metadata key when it does not. */
private fun LocationRequest.statedPlace(): StatedPlace = StatedPlace(
    state = state.orEmpty(),
    district = district?.trim().orEmpty().ifEmpty { meta(LOCATION_META_DISTRICT) },
    village = village?.trim().orEmpty().ifEmpty { meta(LOCATION_META_VILLAGE) },
    pincode = pincode.orEmpty(),
    pinLat = subjectLatitude?.let { trimCoordinate(it) } ?: meta(LOCATION_META_ARTISAN_LAT),
    pinLng = subjectLongitude?.let { trimCoordinate(it) } ?: meta(LOCATION_META_ARTISAN_LNG)
)

/** Both shapes, every time. See the note at the top of this file for why neither may be dropped. */
private fun LocationRequest.withStatedPlace(place: StatedPlace): LocationRequest {
    // Half a pin is not a place, and the API says so with a 422 (`_pin_is_a_pair`). A pair that does
    // not parse as two numbers is stored as no pin at all rather than as a refused save.
    val pin = place.pinLat.trim().toDoubleOrNull()?.let { lat ->
        place.pinLng.trim().toDoubleOrNull()?.let { lng -> lat to lng }
    }
    return copy(
        state = place.state.ifBlank { null },
        district = place.district.trim().ifBlank { null },
        village = place.village.trim().ifBlank { null },
        pincode = place.pincode.ifBlank { null },
        subjectLatitude = pin?.first,
        subjectLongitude = pin?.second
    ).withMeta(
        LOCATION_META_DISTRICT to metaText(place.district),
        LOCATION_META_VILLAGE to metaText(place.village),
        // Written as JSON NUMBERS, matching the columns they mirror. `lift_stated_address` copies a
        // metadata value straight into a Float column when the column is absent, so a quoted string
        // here would be a string handed to Postgres for a `double precision` — a 500 on the save
        // rather than a validation message, on the one build that is meant to be fixing this.
        LOCATION_META_ARTISAN_LAT to pin?.let { JsonPrimitive(it.first) },
        LOCATION_META_ARTISAN_LNG to pin?.let { JsonPrimitive(it.second) }
    )
}

// ---------------------------------------------------------------------------------------------
// The reference list, cached for a phone with no signal
// ---------------------------------------------------------------------------------------------

/**
 * The cached copy of `GET /reference/address`, as a plain file in the app's private storage.
 *
 * WHY THIS IS NOT OPTIONAL. This is the field client. A rural workshop with no bars is the normal
 * condition, not the edge case — the whole offline outbox exists because of it — and a state
 * dropdown that renders "Loading the state list…" for ever is a required field the researcher
 * cannot answer. The old code fetched on every composition with an in-memory fallback that died
 * with the process, so a phone that had been online an hour ago still showed an empty list.
 *
 * WHY A FILE RATHER THAN SharedPreferences. The rest of the app's small settings live in
 * preferences, correctly: they are a handful of short strings. This payload is ~12 KB of JSON, and
 * SharedPreferences parses its entire XML file into memory the first time any key in it is touched
 * — so parking it beside the auth token would make reading the auth token twelve kilobytes more
 * expensive for the life of the process. A file is read when this card is opened and not otherwise.
 *
 * HOW IT IS INVALIDATED. The server stamps the payload with `version`, and bumps it when the lists
 * move — a union territory merges, a district is renamed, a new one is created. Every successful
 * fetch overwrites the cache when the JSON differs, so a version bump propagates on the next
 * request the phone manages to make, and an unchanged payload costs no write. There is deliberately
 * no time-based expiry: a list that has not changed is not stale, and expiring it would blank the
 * dropdowns of exactly the offline phone this exists for.
 */
private const val REFERENCE_CACHE_FILE = "address-reference.json"

private val referenceJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

internal object AddressReferenceCache {

    fun read(context: Context): AddressReferenceDto? = runCatching {
        val file = File(context.filesDir, REFERENCE_CACHE_FILE)
        if (!file.exists()) return null
        referenceJson.decodeFromString(AddressReferenceDto.serializer(), file.readText())
    }.getOrNull()

    /** Overwrite when the served payload differs. Returns true when the cache changed. */
    fun write(context: Context, value: AddressReferenceDto): Boolean = runCatching {
        val file = File(context.filesDir, REFERENCE_CACHE_FILE)
        val encoded = referenceJson.encodeToString(AddressReferenceDto.serializer(), value)
        if (file.exists() && file.readText() == encoded) return false
        file.writeText(encoded)
        true
    }.getOrDefault(false)
}

/**
 * The state and district lists: the cached copy first, then whatever the server has to add.
 *
 * The cache is read BEFORE the request goes out, so the dropdowns are populated on the first frame
 * on a phone that has ever been online, and a failed fetch changes nothing on screen. A fetch that
 * comes back empty-handed — which is what a 401 mid-refresh or a captive-portal HTML page decodes
 * to — is discarded rather than allowed to blank a list that was working.
 */
@Composable
private fun rememberAddressReference(repository: FieldRepository): AddressReferenceDto {
    val context = LocalContext.current
    var reference by remember { mutableStateOf(AddressReferenceDto()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { AddressReferenceCache.read(context) }
            ?.let { reference = it }
        val fresh = runCatching { repository.addressReference() }.getOrNull() ?: return@LaunchedEffect
        if (fresh.statesAndUnionTerritories.isEmpty() && fresh.states.isEmpty()) return@LaunchedEffect
        reference = fresh
        withContext(Dispatchers.IO) { AddressReferenceCache.write(context, fresh) }
    }
    return reference
}

/** The states the dropdown offers, with a stored value kept at the front until the list arrives. */
private fun stateOptions(current: String, reference: AddressReferenceDto): List<Pair<String, String>> {
    val served = reference.statesAndUnionTerritories.ifEmpty { reference.states }
    val known = served.any { it.equals(current, ignoreCase = true) }
    // An edit form whose record holds a state must not show "Select state" over it: that reads as
    // "not answered" and invites the researcher to answer it again, differently.
    val all = if (current.isNotBlank() && !known) listOf(current) + served else served
    return all.map { it to it }
}

/** The districts of [state], with the same kept-at-the-front rule for a stored value. */
private fun districtOptions(
    state: String,
    current: String,
    reference: AddressReferenceDto
): List<Pair<String, String>> {
    val served = reference.districts?.byState?.get(state).orEmpty()
    val known = served.any { it.equals(current, ignoreCase = true) }
    val all = if (current.isNotBlank() && !known) listOf(current) + served else served
    return all.map { it to it }
}

// ---------------------------------------------------------------------------------------------
// What the geocoder is allowed to say, and when
// ---------------------------------------------------------------------------------------------

/**
 * The accuracy radius past which a fix may not choose an address.
 *
 * ANDROID HAD NO SUCH LINE AT ALL. The web card has had `PINCODE_ACCURACY_LIMIT_METRES` since it
 * was written; this client reverse-geocoded whatever it was handed, including a 2.5 km network
 * estimate — and two of the fifteen live records carry radii over two kilometres, so this is not
 * hypothetical. A satellite fix is good to tens of metres; a phone with no lock, indoors or under a
 * tin roof, silently falls back to the mobile network and reports kilometres while returning
 * coordinates that look every bit as precise. Rural districts are tens of kilometres across and
 * rural PIN areas a few, so past a kilometre the geocoder is choosing between neighbours on the
 * strength of an error term. A blank box is honest; a district that arrived by itself reads as
 * measured fact and gets exported as one.
 *
 * WHAT THE LIMIT GOVERNS NOW THAT A POINT CAN WRITE. Both halves, and it must not be softened at
 * either: above the line a fix neither WRITES nor OFFERS. A one-tap "yes" to a district picked out
 * of a 2.5 km circle is exactly as wrong as writing it in silently, and rather easier to tap. The
 * coordinates themselves are still kept, with their radius — a rough position beats none — and the
 * card says in words why no address came with them.
 *
 * The same 1000 m, for the same physical reason, as the web card and as
 * [SATELLITE_FIX_LIMIT_METRES] in LocationCapture.kt.
 */
private const val GEOCODE_ACCURACY_LIMIT_METRES = 1000.0

/**
 * How long a new point waits before it is looked up.
 *
 * Two unrelated reasons, both real. Typing a coordinate by hand re-emits on every keystroke and each
 * emit is a different point, so this keeps it to one lookup per place rather than one per digit. And
 * on the pin path it guarantees the pin's own write has been committed before the address write reads
 * the boxes back. Invisible next to the geocoder's own round trip either way.
 */
private const val LOOKUP_DEBOUNCE_MS = 400L

/** Trailing administrative words MapTiler and Android's geocoder disagree about attaching. */
private val DISTRICT_SUFFIXES = listOf("district", "districts", "zila", "zilla", "jila", "jilla")

/** A name reduced to its comparison key. Same fold as `_fold` in services/address.py. */
private fun foldName(value: String): String =
    value.lowercase(Locale.UK).replace("&", "and").filter { it in 'a'..'z' || it in '0'..'9' }

/**
 * A geocoded district name, stripped of the administrative word.
 *
 * The geocoders are inconsistent about it in both case and presence — "Jammu district" lowercase,
 * "Akola District" capitalised, "Bagru" bare — and the served list holds none of them, so a name
 * that keeps its suffix matches nothing and is silently dropped.
 */
internal fun normaliseDistrictName(raw: String?): String {
    var name = raw.orEmpty().trim().trim(',', '.', '-').trim()
    for (suffix in DISTRICT_SUFFIXES) {
        if (name.length > suffix.length + 1 && name.lowercase(Locale.UK).endsWith(" $suffix")) {
            name = name.dropLast(suffix.length + 1).trim()
            break
        }
    }
    return name
}

/** The entry of [districts] that [text] names, or "" when the list does not hold it. */
private fun matchDistrict(text: String, districts: List<String>): String {
    val wanted = foldName(normaliseDistrictName(text))
    if (wanted.isEmpty()) return ""
    districts.firstOrNull { foldName(it) == wanted }?.let { return it }
    return districts.firstOrNull { entry ->
        val name = foldName(entry)
        (name.length >= 5 && wanted.contains(name)) || (wanted.length >= 5 && name.contains(wanted))
    }.orEmpty()
}

/** A geocoded postal code, kept only when it satisfies the rule the researcher is held to. */
private fun usablePincode(text: String?): String {
    val digits = text.orEmpty().filter { it in '0'..'9' }
    return if (pincodeValidationError(digits) == null) digits else ""
}

/**
 * What the geocoder says is at a point, already resolved against the API's own closed lists.
 *
 * EVERY FIELD MAY BE "", AND "" IS AN ANSWER. Sampled across rural Rajasthan, Uttarakhand and
 * Jammu & Kashmir, 57 of 60 points carry no postal code at all — so "no pincode here" is the
 * ordinary reply, not a failure, and the caller must treat it as a reply. Reading it as "leave
 * whatever was there" is precisely the staleness bug this file removes.
 */
private data class PlaceSuggestion(
    val state: String = "",
    val district: String = "",
    val pincode: String = ""
) {
    val isEmpty: Boolean get() = state.isBlank() && district.isBlank() && pincode.isBlank()
}

/**
 * The three boxes a point is allowed to fill, named so a write can say which ones it touched.
 *
 * The village is deliberately absent. No closed list of Indian villages exists, so there is nothing
 * to resolve a geocoded settlement name against — and `locality` names a tehsil, a bypass or a
 * national highway often enough that a village taken from it is wrong more often than it is right.
 */
private enum class PlaceField(val label: String) {
    State("state"),
    District("district"),
    Pincode("pincode")
}

/**
 * "state, district and pincode" — the boxes a write touched, as a sentence.
 *
 * Named rather than counted. "3 fields were filled in" sends the researcher hunting for them; naming
 * them says where to look, which is the whole value of announcing the write at all. Same sentence,
 * built the same way, as `FIELD_NAMES` in the web card.
 */
private fun fieldNames(fields: List<PlaceField>): String = when (fields.size) {
    0 -> ""
    1 -> fields.first().label
    else -> fields.dropLast(1).joinToString(", ") { it.label } + " and " + fields.last().label
}

/**
 * Why a point is being looked up, and therefore what its answer is allowed to do to the boxes.
 *
 * The two are not a preference and not a setting; they are two different human acts, and the whole
 * of the difference between them is enforced in `applyPlace`.
 */
private enum class PlaceIntent {
    /**
     * The researcher pointed at the artisan's place, or accepted an offer. A request for THIS
     * place's address, so it overwrites — a blank pincode included.
     */
    Explicit,

    /**
     * A fix arrived by itself, or the device's own position was captured. Fills empty boxes and
     * overwrites nothing.
     */
    Passive
}

/**
 * One automatic write, kept so it can be announced and taken back.
 *
 * [previous] is the point of it. An "undo" that CLEARED the boxes instead of restoring them would
 * destroy a typed answer just as thoroughly as the silent overwrite this replaces, so what was there
 * is snapshotted at the moment of the write and put back verbatim.
 */
private data class AppliedWrite(
    val fields: List<PlaceField>,
    val previous: PlaceSuggestion,
    val intent: PlaceIntent
)

/**
 * The three geocodable answers as the boxes currently hold them.
 *
 * [PlaceSuggestion] doubles as this snapshot rather than earning a near-identical twin: it is
 * already exactly these three values, and every comparison in the write path is "what is in the box
 * against what the point says", which wants both sides in one shape.
 */
private fun StatedPlace.geocodable(): PlaceSuggestion = PlaceSuggestion(state, district, pincode)

/**
 * The record of what this card wrote, updated for one write of [fields] to [values].
 *
 * Fields the write did not touch keep whatever they were credited with before — which is what lets a
 * researcher's own answer in one box sit beside a machine-filled one in the next without either
 * being mistaken for the other. Undo passes an empty [values]: a restored box holds a human's value
 * again, so this card is no longer the author of it.
 */
private fun PlaceSuggestion.crediting(fields: List<PlaceField>, values: PlaceSuggestion) = PlaceSuggestion(
    state = if (PlaceField.State in fields) values.state else state,
    district = if (PlaceField.District in fields) values.district else district,
    pincode = if (PlaceField.Pincode in fields) values.pincode else pincode
)

/**
 * Ask the device's geocoder what is at these coordinates.
 *
 * `adminArea` is the state and `subAdminArea` is the DISTRICT — the Android equivalent of
 * MapTiler's `region` and `subregion`, and the same trap avoided: `locality` and the thoroughfare
 * fields name a tehsil, a bypass or a national highway often enough that a place name taken from
 * them is wrong more often than it is right.
 *
 * Runs off the main thread and swallows everything. A geocoder is a network service behind a system
 * API — absent on some builds, rate-limited on others, and simply wrong about a hamlet often enough
 * in rural India to matter — so nothing it does may reach the researcher as an error and nothing it
 * fails to do may block a save.
 */
private suspend fun geocode(context: Context, lat: Double, lng: Double): Triple<String?, String?, String?> =
    withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext Triple(null, null, null)
        runCatching {
            @Suppress("DEPRECATION")
            val results = Geocoder(context, Locale.UK).getFromLocation(lat, lng, 1)
            val first = results?.firstOrNull() ?: return@runCatching Triple(null, null, null)
            Triple(first.adminArea, first.subAdminArea, first.postalCode)
        }.getOrDefault(Triple(null, null, null))
    }

/**
 * The accuracy radius when it is too wide for a point to be allowed to name a place, or null when
 * the point may be looked up.
 *
 * A HAND-PLACED PIN PASSES NULL AND IS EXEMPT, and that is not a loophole in the guard: the
 * researcher pointed at the place, and a pointer has no error radius to disqualify it. What the
 * limit disqualifies is a MEASUREMENT that admits to being kilometres wide.
 */
private fun coarseRadius(accuracy: Double?): Double? =
    accuracy?.takeIf { it > GEOCODE_ACCURACY_LIMIT_METRES }

/**
 * The suggestion for a point, or an empty one when the point is too coarse to have an address.
 *
 * A fix wider than [GEOCODE_ACCURACY_LIMIT_METRES] is not looked up at all. The caller checks the
 * radius too, so it can say why nothing appeared; the check is repeated here because the rule is the
 * one thing in this file that may not be softened, and a guard that lives only at the call site is a
 * guard the next call site forgets.
 */
private suspend fun suggestPlaceFor(
    context: Context,
    lat: Double,
    lng: Double,
    accuracy: Double?,
    reference: AddressReferenceDto
): PlaceSuggestion {
    if (coarseRadius(accuracy) != null) return PlaceSuggestion()
    val (adminArea, subAdminArea, postal) = geocode(context, lat, lng)
    val served = reference.statesAndUnionTerritories.ifEmpty { reference.states }
    val state = adminArea?.let { matchIndianState(it, served) }.orEmpty()
    val district = if (state.isBlank()) {
        ""
    } else {
        matchDistrict(subAdminArea.orEmpty(), reference.districts?.byState?.get(state).orEmpty())
    }
    return PlaceSuggestion(state = state, district = district, pincode = usablePincode(postal))
}

// ---------------------------------------------------------------------------------------------
// Presentation helpers
// ---------------------------------------------------------------------------------------------

private fun trimCoordinate(value: Double): String = String.format(Locale.UK, "%.6f", value)

private fun radiusLabel(metres: Double): String =
    if (metres < 1000) "±${Math.round(metres)} m" else "±${String.format(Locale.UK, "%.1f", metres / 1000)} km"

/** ISO 8601 with an offset, which is what the API's `capturedAt` parses. */
private fun isoNow(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.UK).format(Date())

/**
 * A stored `capturedAt` as a researcher would read it, or "" when the string is not a timestamp.
 *
 * Tolerant on the way in on purpose: the value is round-tripped through Postgres and JSON, and a
 * provenance line is not worth an exception. Every stored coordinate written before this change is
 * undated, and "" is how the card says so.
 */
private fun readableStamp(iso: String?): String {
    val raw = iso?.trim().orEmpty()
    if (raw.length < 19) return ""
    /*
     * The fraction is dropped rather than parsed. Postgres hands back MICROseconds
     * ("2026-06-20T06:22:56.518000Z") and SimpleDateFormat's `S` is milliseconds however many
     * digits it is given, so "518000" reads as 518 seconds and the card would quietly claim the fix
     * was taken eight and a half minutes later than it was. Nothing here needs sub-second
     * resolution, and a wrong minute on a provenance line is worse than no fraction at all.
     */
    val instant = raw.substring(0, 19)
    val tail = raw.substring(19)
    val zone = when {
        tail.endsWith("Z") || tail.isEmpty() -> TimeZone.getTimeZone("UTC")
        else -> TimeZone.getTimeZone("GMT" + tail.takeLast(6))
    }
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.UK).apply { timeZone = zone }
    val parsed = runCatching { parser.parse(instant) }.getOrNull() ?: return ""
    // Shown in the reader's own zone: a researcher checking a Kutch record in Kolkata wants the
    // time they would have looked at a watch and seen.
    return SimpleDateFormat("d MMM yyyy, HH:mm", Locale.UK).format(parsed)
}

/** A group heading, marked as one so TalkBack's heading navigation can jump between the two. */
@Composable
private fun GroupHeading(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            title,
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            modifier = Modifier.semantics { heading() }
        )
        Text(subtitle, color = MaterialTheme.field.muted, fontSize = 12.sp)
    }
}

/** A notice inside a group. [warn] for something to act on, plain for what is merely so. */
@Composable
private fun GroupNotice(warn: Boolean, text: String) {
    val container = if (warn) MaterialTheme.field.warningContainer else MaterialTheme.field.surface100
    val ink = if (warn) MaterialTheme.field.onWarningContainer else MaterialTheme.field.muted
    Text(
        text,
        color = ink,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(12.dp))
            .padding(12.dp)
    )
}

// ---------------------------------------------------------------------------------------------
// The card
// ---------------------------------------------------------------------------------------------

/**
 * Why a NEW record cannot be saved without a coordinate, a state and a district — and why an
 * existing one still can.
 *
 * THE ASTERISKS ARE NOW ENFORCED, at the save button of every form that opens a record. The server
 * enforces exactly this on create (`require_location`, schemas/common.py) and the same reference
 * payload that fills these dropdowns is what its validators check against, so a form that offered
 * the save anyway would be spending a round trip to fetch a 422 the phone could already read. That
 * matters most where there is no round trip to spend: a record saved with no signal goes into the
 * outbox, and a body the server will refuse sits there being retried until somebody notices it
 * failed, days later, a long way from the artisan.
 *
 * IT IS FOR CREATES ONLY, and the caller is the one that knows. `forbid_clearing_location`
 * deliberately does NOT ask an update for a state and a district: the records written before those
 * columns existed have neither, and a researcher who opened one to correct a phone number must be
 * able to save it without inventing a district from a desk. Guessed data is worse than absent data.
 * The card flags the gap and invites them to close it; this refuses to close it on their behalf.
 *
 * A null [value] answers with the location message rather than null, because on create the server
 * demands a Location at all — and a pin or two typed numbers satisfy that as well as a fix does.
 */
fun artisanLocationRequirementError(value: LocationRequest?): String? {
    val place = value?.statedPlace() ?: return LOCATION_REQUIRED_MESSAGE
    if (place.state.isBlank()) return "Choose the artisan's state under 'Artisan location' before saving."
    if (place.district.isBlank()) return "Choose the artisan's district under 'Artisan location' before saving."
    return null
}

/**
 * Both groups, in the order a researcher answers them: what they know about the artisan first, what
 * the phone happens to know about itself second and collapsed.
 *
 * Drop-in for `LocationAddressEditor` in MainActivity — same parameters minus `onUseGps`, which
 * nothing needed once [LocationCaptureCard] started driving its own permission flow.
 *
 * WHERE THE ANSWERS LIVE. On the [LocationRequest] itself, read straight back out of it, so a
 * record whose location arrives after mount (every edit form fetches its record) shows what it
 * stored rather than a blank box. A [LocationRequest] cannot exist without a coordinate, though, so
 * before there is one the four artisan answers are parked in [StatedPlace] and folded in the moment
 * a fix or a pin arrives — and the card says so, because four answers that silently do not save is
 * the worst version of this.
 *
 * WHICH PATH IS WHICH, because everything below reads better with the two named. The artisan's own
 * MapPickerDialog at the bottom of this function is the EXPLICIT path: it writes the state, district
 * and pincode of the point the researcher tapped, over whatever was there. Everything arriving
 * through [LocationCaptureCard] — the fix taken on open, "Use current GPS", the capture pin, typed
 * coordinates — is the PASSIVE path: it fills empty boxes only, and offers the rest.
 */
@Composable
fun LocationFieldsSection(
    repository: FieldRepository,
    value: LocationRequest?,
    onChange: (LocationRequest?) -> Unit,
    modifier: Modifier = Modifier,
    required: Boolean = true,
    isEdit: Boolean = false,
    showRequirementError: Boolean = false,
    /**
     * The village, when the FORM owns it rather than this card.
     *
     * Artisan, craft, workshop, product, tool and questionnaire all have a real `place` column, and
     * it is the column the shipped "Village/Place" export field already reads — it is where
     * "Bagru, Jaipur, Rajasthan" was being hand-encoded for want of a state and a district box.
     * A form that passes its own `place` through here gets ONE village question in the group where
     * it belongs; a form that passes nothing keeps a village of its own in the location metadata.
     * Leaving both is the one arrangement that is actually wrong, so see the call-site note in the
     * report: each form should hand its `place` down and drop its separate box.
     */
    village: String? = null,
    onVillageChange: ((String) -> Unit)? = null,
    onMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reference = rememberAddressReference(repository)
    var parked by remember { mutableStateOf(StatedPlace()) }
    var expanded by remember { mutableStateOf(false) }
    var pincodeProblemShown by remember { mutableStateOf(false) }
    var showArtisanMap by remember { mutableStateOf(false) }
    /*
     * What this card last WROTE into the three boxes, and the only thing afterwards that can tell a
     * machine-filled answer from one a human gave.
     *
     * It is no longer what decides whether a write may happen — the passive path asks whether a box
     * is EMPTY, which is both a stricter question and a simpler one. What still needs this is the
     * case where a LATER point has nothing to say: the values standing in the boxes then describe
     * the point BEFORE it, and only the ones this card copied in may be offered for clearing.
     * Recording the VALUES rather than a "the user touched it" flag is what makes that survive a
     * record that loads after mount, and hands a box back to the geocoder when a researcher empties
     * it by hand.
     */
    var applied by remember { mutableStateOf(PlaceSuggestion()) }
    /*
     * WHAT WAS JUST FILLED IN FOR THE RESEARCHER, AND WHAT IT REPLACED.
     *
     * An automatic write with no visible trace is exactly the bug the offer below was built to avoid
     * — a Bagru pincode saved onto a Dehradun record, because 95% of rural points return no postal
     * code and the stale value survived. So the write is loud instead of silent: this drives a notice
     * naming every box that changed, and one Undo that puts back precisely what was there.
     *
     * Cleared when a human edits any of the three boxes (see `setPlace`), and NOT cleared merely
     * because another lookup went out — which is where this deliberately parts company with the web
     * card. The live fix streams an update a second, so clearing the announcement at request time
     * would erase the Undo for a pin the researcher dropped a moment ago, on a lookup that is not
     * allowed to write anything anyway.
     */
    var autofill by remember { mutableStateOf<AppliedWrite?>(null) }
    /** The radius of the last fix that was too coarse to name a place, for the notice saying so. */
    var coarseFixMetres by remember { mutableStateOf<Double?>(null) }
    /*
     * The geocoder's reading of the current DEVICE point, for the parts of it the passive path was
     * not allowed to write, waiting to be accepted or waved away.
     *
     * IT IS NOT WRITTEN ANYWHERE UNTIL SOMEBODY TAPS. That is what separates the passive path from
     * what produced the fifteen wrong records: a device reading may fill a box that is EMPTY, but it
     * may not replace an answer a researcher gave, so what it would have said is put on the table
     * instead. On site that is one tap; at a desk in Kharagpur it is correctly declined, and
     * declining it costs nothing and leaves nothing behind.
     */
    var offer by remember { mutableStateOf<PlaceSuggestion?>(null) }
    var offerPoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    /*
     * The point moved, the map has nothing to say about where it moved to, and the boxes still hold
     * what this card copied in for the point BEFORE it.
     *
     * This is the last hiding place of the staleness bug, and it needed a third answer rather than
     * one of the two obvious ones. Keeping the old values silently is what put Bagru's pincode on a
     * Dehradun record. Clearing them silently is no better: a researcher affirmed those values by
     * tapping, and a form that quietly un-answers a question they answered is a form they stop
     * trusting. So it says what happened and offers the button, which is the only version where
     * nobody is guessing on anybody's behalf.
     */
    var appliedNowStale by remember { mutableStateOf(false) }
    val latest = rememberUpdatedState(value)
    val lookup = remember { mutableStateOf<Job?>(null) }
    /** What the in-flight lookup is for, so a passive one cannot cancel an explicit one. */
    var lookupIntent by remember { mutableStateOf(PlaceIntent.Passive) }
    // The state the CURRENT coordinate is really in, when the geocoder can say and the answer is
    // worth showing. Read only — see the effect below.
    var coordinateState by remember { mutableStateOf("") }
    /*
     * The point this card itself produced, so a coordinate that came out of a RECORD can be told
     * apart from one this session captured.
     *
     * That distinction is what the flag below runs on, and it is better than an `isEdit` flag for
     * the same reason [LocationCaptureCard] does not trust one: none of the three call sites passes
     * it, every edit form fetches its record after mount, and a card that has to be told what it is
     * looking at will eventually be told wrong.
     */
    var sessionPoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    val place = value?.statedPlace() ?: parked
    val pincodeProblem = pincodeValidationError(place.pincode)
    val showPincodeProblem = pincodeProblem != null &&
        (pincodeProblemShown || place.pincode.length == PINCODE_LENGTH)
    val districts = districtOptions(place.state, place.district, reference)
    val districtsKnown = reference.districts?.byState?.isNotEmpty() == true

    /** Write one edited artisan answer back, wherever the answers currently live. */
    fun setPlace(next: StatedPlace) {
        // A human has just had their say about these boxes, so there is nothing left to warn about.
        appliedNowStale = false
        /*
         * And nothing left to offer taking back, either. Once a researcher has edited one of the
         * three boxes, an Undo restoring the snapshot from before the write would overwrite the edit
         * they just made — a second silent overwrite, wearing the hat of the control that exists to
         * prevent the first. The announcement goes rather than half of it being honoured.
         *
         * `applyPlace` and `undoAutofill` both write through here and then set this themselves,
         * which is why clearing it unconditionally is safe.
         */
        autofill = null
        val current = latest.value
        if (current == null) parked = next else onChange(current.withStatedPlace(next))
    }

    /**
     * Fold the artisan answers onto whatever coordinate the captured-at card just produced.
     *
     * [LocationCaptureCard] rebuilds its [LocationRequest] from scratch on every change, so
     * anything not re-applied here is dropped the next time a digit is typed into the latitude box.
     */
    fun emitCoordinate(next: LocationRequest?) {
        if (next == null) {
            // The coordinate was cleared and the row the answers live on goes with it. Park them so
            // clearing a mis-tapped pin does not also silently discard a typed district.
            parked = place
            sessionPoint = null
            onChange(null)
            return
        }
        sessionPoint = next.latitude to next.longitude
        // A reading is dated when it is TAKEN. Carrying the stored stamp through an unchanged
        // coordinate is what stops opening a record for an unrelated correction from re-dating a
        // measurement nobody re-took.
        val moved = !sameCoordinate(next, value)
        onChange(
            next
                .copy(capturedAt = if (moved) isoNow() else value?.capturedAt)
                .withStatedPlace(place)
        )
    }

    /**
     * Write a point's address into the three boxes it is allowed to touch, and report what changed.
     *
     * [intent] IS THE WHOLE DIFFERENCE, and it is not a preference:
     *
     *   * EXPLICIT — the researcher has just pointed at the artisan's place, or tapped "Use …" on an
     *     offer. That is a request for THIS place's address, so every box is written, INCLUDING with
     *     a BLANK pincode. Leaving the previous point's PIN standing under this point's coordinates
     *     is exactly how a Bagru pincode ended up on a Dehradun record, and 57 of 60 sampled rural
     *     Indian points carry no postal code at all — so "no answer" is the ORDINARY answer here and
     *     reading it as "leave what is there" is the staleness bug itself, not a kindness.
     *   * PASSIVE — a fix arrived by itself, or the device's own position was captured. Only EMPTY
     *     boxes are filled and nothing is ever overwritten. A researcher at a desk in Kharagpur
     *     documenting a Bagru artisan must not have "Rajasthan" replaced by "West Bengal", and one
     *     typing the district while the receiver warms must not have it replaced a second later by a
     *     satellite.
     *
     * Returns what it wrote and what it replaced — with [AppliedWrite.fields] empty when the point
     * had nothing to add — so the caller can offer the leftovers, and so the write can be announced
     * and undone from one place rather than at each call site.
     */
    fun applyPlace(fresh: PlaceSuggestion, intent: PlaceIntent): AppliedWrite {
        // Wherever the answers currently live: a pin can be dropped before any coordinate exists, in
        // which case the boxes are the parked ones and there is no LocationRequest to read.
        val held = latest.value?.statedPlace() ?: parked
        val previous = held.geocodable()
        val overwrite = intent == PlaceIntent.Explicit
        val touched = mutableListOf<PlaceField>()
        var next = held

        if (fresh.state.isNotBlank() && fresh.state != previous.state &&
            (overwrite || previous.state.isBlank())
        ) {
            next = next.copy(state = fresh.state)
            touched += PlaceField.State
        }
        /*
         * THE STATE GATES THE OTHER TWO, and it gates them differently.
         *
         * A DISTRICT NEEDS A POSITIVE MATCH. It is only meaningful inside its own state — Bilaspur
         * belongs to two of them, which is why the API resolves a district WITHIN a state — so it may
         * be written only when the state now standing in the box IS the state the geocoder resolved
         * it in. `suggestPlaceFor` already refuses to resolve a district under a state that did not
         * match the served list; this is the other half of the same rule, and on the passive path it
         * is the half that does the work. A researcher at a desk in Kharagpur who has typed Rajasthan
         * for a Bagru artisan must not have Paschim Medinipur dropped in underneath it: that pairing
         * is one the API refuses and no export could interpret. It goes into the offer instead, where
         * accepting it is an explicit act and replaces both halves at once.
         *
         * A PINCODE NEEDS ONLY NOT TO BE CONTRADICTED. A code is a code OF a state — the leading
         * digit is the postal zone — so 721302 written under a typed "Rajasthan" is a contradiction
         * the API's own zone check would then accuse the researcher of having made. But with no state
         * in the box there is nothing to contradict, and the code is still this point's own answer,
         * so a positive match is more than this needs.
         */
        val stateAgrees = fresh.state.isNotBlank() && next.state.equals(fresh.state, ignoreCase = true)
        val district = if (stateAgrees) fresh.district else ""
        val contradicted = next.state.isNotBlank() && fresh.state.isNotBlank() && !stateAgrees
        val pincode = if (contradicted) "" else fresh.pincode

        // Written even when BLANK on the explicit path: a district belonging to the point before this
        // one is worse than no district, and the state above may have just changed underneath it.
        if (district != previous.district && (overwrite || previous.district.isBlank()) &&
            (district.isNotBlank() || overwrite)
        ) {
            next = next.copy(district = district)
            touched += PlaceField.District
        }
        if (pincode != previous.pincode && (overwrite || previous.pincode.isBlank()) &&
            (pincode.isNotBlank() || overwrite)
        ) {
            next = next.copy(pincode = pincode)
            touched += PlaceField.Pincode
        }

        // `toList()` because this outlives the function: an AppliedWrite parked in composable state
        // must not share a mutable list with the builder above it.
        val write = AppliedWrite(fields = touched.toList(), previous = previous, intent = intent)
        if (touched.isEmpty()) return write
        applied = applied.crediting(touched, next.geocodable())
        // A pincode this card wrote has not been abandoned half-typed by anybody, so the on-blur
        // complaint must not fire over it — and a blank one it wrote is not a problem at all.
        pincodeProblemShown = false
        setPlace(next)
        // AFTER setPlace, which drops any earlier announcement: this is the one that stands now.
        autofill = write
        return write
    }

    /** Put back exactly what was in the three boxes before the last automatic write. */
    fun undoAutofill() {
        val write = autofill ?: return
        val held = latest.value?.statedPlace() ?: parked
        // The restored values are a human's again, so this card is no longer their author.
        applied = applied.crediting(write.fields, PlaceSuggestion())
        setPlace(
            held.copy(
                state = write.previous.state,
                district = write.previous.district,
                pincode = write.previous.pincode
            )
        )
    }

    /**
     * Look up what is at a point and hand the answer to [applyPlace].
     *
     * [accuracy] is the radius of the reading, and null for a hand-placed pin — which is what exempts
     * a pin from the coarse-fix guard below. Pass the pin's own coordinates on the explicit path: the
     * artisan's address is resolved from where the researcher said the ARTISAN is, never from where
     * the device happens to be standing.
     */
    fun lookupPlace(lat: Double, lng: Double, accuracy: Double?, intent: PlaceIntent) {
        /*
         * AN EXPLICIT LOOKUP OUTRANKS A PASSIVE ONE. The live fix streams an update a second, so
         * without this a pin dropped on the artisan's place would routinely have its lookup
         * cancelled by a satellite update that is not allowed to write anything anyway — and the
         * explicit path would then fail at random, which is the worst way for it to fail.
         */
        val running = lookup.value
        val explicitInFlight = lookupIntent == PlaceIntent.Explicit && running?.isActive == true
        if (intent == PlaceIntent.Passive && explicitInFlight) return
        running?.cancel()
        lookupIntent = intent
        // The previous point's offer is off the table whatever happens next, and it is dropped when
        // the request GOES OUT rather than when it comes back: the seconds a rural lookup takes are
        // exactly when the last place's answer sits under this place's coordinates.
        offer = null
        offerPoint = null
        coarseFixMetres = coarseRadius(accuracy)
        // ABSOLUTE, AND NOT TO BE SOFTENED — see [GEOCODE_ACCURACY_LIMIT_METRES]. Above the line
        // there is no write and no offer either, only the notice explaining the silence.
        if (coarseFixMetres != null) return

        lookup.value = scope.launch {
            delay(LOOKUP_DEBOUNCE_MS)
            val fresh = suggestPlaceFor(context, lat, lng, accuracy, reference)

            /*
             * The researcher may have moved on while the lookup ran, and the newer point is the one
             * that must survive — so an answer about a point that is no longer the one in question
             * is dropped rather than written. WHICH point that is differs by path: the explicit
             * answer belongs to the artisan's pin and the passive one to the captured coordinate.
             */
            when (intent) {
                PlaceIntent.Explicit -> {
                    // The pin itself is the point in question, so the pin is what is checked — a
                    // researcher who moved it again, or removed it, has retracted this question.
                    val held = latest.value?.statedPlace() ?: parked
                    if (held.pinLat != trimCoordinate(lat) || held.pinLng != trimCoordinate(lng)) {
                        return@launch
                    }
                }

                PlaceIntent.Passive -> {
                    val current = latest.value ?: return@launch
                    val looked = LocationRequest(latitude = lat, longitude = lng)
                    if (!sameCoordinate(looked, current)) return@launch
                }
            }

            if (fresh.isEmpty) {
                /*
                 * NOTHING FOUND IS NOT NOTHING TO SAY — and it is deliberately NOT treated as "this
                 * place has no address", which is the one shortcut that would undo the rest of this
                 * function. A geocoder that is absent from the build, rate-limited, or simply offline
                 * in a rural workshop returns exactly this, so blanking the boxes here would let a
                 * dropped connection erase a researcher's typed answer.
                 *
                 * What is left is the third answer, and it needed to be a third one. The boxes still
                 * hold what was copied in for the PREVIOUS point: keeping that silently is what put
                 * Bagru's pincode on a Dehradun record, and clearing it silently would un-answer a
                 * question a researcher answered. So the card says what happened and offers the
                 * button, which is the only version where nobody guesses on anybody's behalf.
                 */
                val held = latest.value?.statedPlace() ?: parked
                val stale = !applied.isEmpty && (
                    (applied.state.isNotBlank() && held.state == applied.state) ||
                        (applied.district.isNotBlank() && held.district == applied.district) ||
                        (applied.pincode.isNotBlank() && held.pincode == applied.pincode)
                    )
                appliedNowStale = stale
                // The warning describes the same values and carries its own control, so leaving the
                // announcement of the write it supersedes would be a second story about one thing.
                if (stale) autofill = null
                if (intent == PlaceIntent.Explicit) {
                    // The researcher asked a direct question by tapping the map and is owed an
                    // answer even when the answer is "the map does not know".
                    onMessage("The map has no address for that pin — set the state and district yourself.")
                }
                return@launch
            }

            appliedNowStale = false
            val write = applyPlace(fresh, intent)
            // Everything was written on the explicit path, and the notice says which. There is
            // nothing left to put on the table.
            if (intent == PlaceIntent.Explicit) return@launch
            /*
             * PASSIVE ONLY. Whatever the fix could not fill — because a human had already answered
             * it — is still worth OFFERING, so a disagreement between the device and the typed answer
             * is visible rather than swallowed. Read against the write's own snapshot rather than the
             * boxes: the write has only just gone out through `onChange` and `latest` does not catch
             * up until the next composition.
             */
            val previous = write.previous
            val leftOver = (fresh.state.isNotBlank() && fresh.state != previous.state &&
                PlaceField.State !in write.fields) ||
                (fresh.district.isNotBlank() && fresh.district != previous.district &&
                    PlaceField.District !in write.fields) ||
                (fresh.pincode.isNotBlank() && fresh.pincode != previous.pincode &&
                    PlaceField.Pincode !in write.fields)
            if (leftOver) {
                offer = fresh
                offerPoint = lat to lng
            }
        }
    }

    /**
     * Take the offer, whole.
     *
     * Tapping "Use Dehradun, Uttarakhand" is an EXPLICIT request for that place's address, so it
     * overwrites — which is both what the words on the button promise and the only behaviour in which
     * they are not a lie. The alternative shipped here until now: the accept path protected any box a
     * human had answered, so a researcher who tapped "Use Dehradun, Uttarakhand" over a typed
     * "Rajasthan" watched the button do nothing to the state.
     *
     * INCLUDING THE PARTS THAT ARE EMPTY. A point with no postal code clears the pincode box rather
     * than leaving the last point's six digits standing under this point's state, which is the
     * original bug wearing its last available disguise. A typed pincode is lost when this is pressed
     * and that is the right trade: the button is a request for a different place's address, a value
     * from the place before it is not worth more than the one just asked for, and the box is one
     * keystroke from being right — with the Undo beside it in the meantime.
     */
    fun acceptOffer(fresh: PlaceSuggestion) {
        offer = null
        offerPoint = null
        applyPlace(fresh, PlaceIntent.Explicit)
    }

    /** Drop only what this card copied in, leaving anything a human typed exactly where it is. */
    fun clearApplied() {
        val held = latest.value?.statedPlace() ?: parked
        setPlace(
            held.copy(
                state = if (held.state == applied.state) "" else held.state,
                district = if (held.district == applied.district) "" else held.district,
                pincode = if (held.pincode == applied.pincode) "" else held.pincode
            )
        )
        applied = PlaceSuggestion()
    }

    /*
     * FLAG, NEVER REWRITE.
     *
     * On a record that already has both a coordinate and a stated state, ask the geocoder which
     * state the coordinate is actually in and say so when the two disagree — which is the case on
     * all fifteen live records, whose coordinates are in West Bengal and whose stated places are in
     * Rajasthan, Gujarat, Uttarakhand and Andhra Pradesh. It reads; it never writes. The researcher
     * who was there decides whether the coordinate was the desk or the workshop, and this cannot
     * know.
     */
    LaunchedEffect(value?.latitude, value?.longitude, reference.version) {
        coordinateState = ""
        val current = value ?: return@LaunchedEffect
        // Only a coordinate that arrived from the RECORD is worth reporting on. One captured a
        // moment ago is already described, accurately, by the card that captured it.
        if (sessionPoint == current.latitude to current.longitude) return@LaunchedEffect
        val metres = current.accuracy
        if (metres != null && metres > GEOCODE_ACCURACY_LIMIT_METRES) return@LaunchedEffect
        val (adminArea, _, _) = geocode(context, current.latitude, current.longitude)
        val served = reference.statesAndUnionTerritories.ifEmpty { reference.states }
        coordinateState = adminArea?.let { matchIndianState(it, served) }.orEmpty()
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {

        // ----- Group one: the artisan's location -----
        GroupHeading(
            title = "Artisan location",
            subtitle = "Where the artisan actually works — what the map, the exports and the " +
                "dataset use. Pinning the artisan's place on the map fills in the state, district " +
                "and pincode, and says so, so you can put it back. A GPS fix fills in only what is " +
                "still blank, because the device is very often at a desk in another state from the " +
                "artisan."
        )

        /*
         * WHAT WAS JUST WRITTEN, AND HOW TO UNDO IT.
         *
         * A form that fills itself in silently is the bug the offer below was built to avoid — a
         * Bagru pincode saved onto a Dehradun record, because 95% of rural points return no postal
         * code and the stale value survived with nothing on screen saying anything had happened.
         * Filling boxes in automatically is fine; doing it invisibly is not. So every automatic write
         * names the boxes it touched and offers exactly one button that restores what was there.
         */
        val written = autofill
        if (written != null) {
            // Which act wrote it, because the two mean different things to a reader: one is their own
            // pin read back to them, the other is a machine that found some boxes empty.
            val source = when (written.intent) {
                PlaceIntent.Explicit -> "Filled in from the place you pointed at: "
                PlaceIntent.Passive -> "Filled in from this device's location (only the boxes that " +
                    "were empty): "
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.surface100, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    source + fieldNames(written.fields) + ". Check it, and change anything that is " +
                        "wrong — you know this place and the geocoder does not.",
                    color = MaterialTheme.field.body,
                    fontSize = 12.sp
                )
                OutlinedButton(
                    onClick = { undoAutofill() },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Undo") }
            }
        }

        /*
         * The offer: what the PASSIVE path read off the device's own point and was not allowed to
         * write, because a researcher had already answered those boxes. One tap to take it, one to
         * wave it away, and nothing at all if it is ignored.
         *
         * It names the coordinate and the radius it was read from, because "is this where the
         * artisan is?" is not a question anybody can answer about an unnamed point — and being able
         * to answer it is the difference between a researcher standing in the workshop and one
         * sitting at a desk fifteen hundred kilometres away, which is the entire finding.
         */
        val pending = offer
        val pendingPoint = offerPoint
        if (pending != null && pendingPoint != null) {
            val named = listOfNotNull(
                pending.district.takeIf { it.isNotBlank() },
                pending.state.takeIf { it.isNotBlank() },
                pending.pincode.takeIf { it.isNotBlank() }
            ).joinToString(", ")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.surface100, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "This device is at ${trimCoordinate(pendingPoint.first)}, " +
                        "${trimCoordinate(pendingPoint.second)}" +
                        (value?.accuracy?.let { " (${radiusLabel(it)})" } ?: "") +
                        ", and the map calls that $named. Use it as the artisan's location?",
                    color = MaterialTheme.field.body,
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { acceptOffer(pending) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) { Text("Use $named") }
                    TextButton(
                        onClick = { offer = null; offerPoint = null },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Not here") }
                }
            }
        }

        if (appliedNowStale) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.warningContainer, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "The location moved, and the map has nothing to say about the new point — which " +
                        "is the ordinary answer in rural India rather than a fault. The state, " +
                        "district and pincode below still describe the PREVIOUS point. Check them, " +
                        "or clear the ones that were copied in.",
                    color = MaterialTheme.field.onWarningContainer,
                    fontSize = 12.sp
                )
                OutlinedButton(
                    onClick = { clearApplied() },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Clear the copied answers") }
            }
        }

        /*
         * THE COARSE FIX, NAMED, and the reason no address arrived with it.
         *
         * The coordinates ARE kept: dropping them would leave the record with no location at all over
         * a radius nobody would otherwise have noticed, and a rough position beats none. What must not
         * happen is a district arriving from a 2.5 km circle — silently, or with a one-tap Yes beside
         * it, which is no better.
         *
         * Shown only while it explains something the researcher can see. With the state and the
         * district both answered there was nothing for the fix to add in the first place, and the
         * live stream would otherwise re-raise this every second it reported.
         */
        val coarse = coarseFixMetres
        if (coarse != null && (place.state.isBlank() || place.district.isBlank())) {
            GroupNotice(
                warn = true,
                text = "No address was read from this device's location: the fix is only accurate to " +
                    "${radiusLabel(coarse)}, which is its network estimate of where it is rather " +
                    "than a satellite reading, and a circle that wide covers more than one district. " +
                    "The coordinates have been kept with their radius, so nothing is lost. Choose the " +
                    "state and district below, or pin the artisan's place on the map — a pin has no " +
                    "radius to be wrong about."
            )
        }

        SearchableSelectField(
            label = "State / union territory *",
            options = remember(place.state, reference) {
                stateOptions(place.state, reference).asSelectOptions()
            },
            selectedValue = place.state,
            placeholder = if (reference.statesAndUnionTerritories.isEmpty() && reference.states.isEmpty()) {
                "Loading the state list…"
            } else {
                "Select state"
            },
            onSelect = { next ->
                // A district only exists inside its own state, so changing the state discards a
                // district that is now in the wrong one rather than leaving a pairing the server
                // would refuse and no export could interpret.
                val keepDistrict = next.equals(place.state, ignoreCase = true)
                setPlace(place.copy(state = next, district = if (keepDistrict) place.district else ""))
            }
        )

        SearchableSelectField(
            label = "District *",
            options = remember(districts) { districts.asSelectOptions() },
            selectedValue = place.district,
            enabled = place.state.isNotBlank() && districts.isNotEmpty(),
            placeholder = when {
                place.state.isBlank() -> "Choose a state first"
                districts.isEmpty() -> "District list not on this phone yet"
                else -> "Select district"
            },
            onSelect = { setPlace(place.copy(district = it)) }
        )
        if (place.state.isNotBlank() && districts.isEmpty() && !districtsKnown) {
            Text(
                "This phone has not received the district list yet — the server it last reached " +
                    "does not serve one. Connect once and it is cached for good, after which this " +
                    "dropdown works with no signal. Until then a NEW record cannot be started, " +
                    "because the API asks every new record for a district; an existing one can " +
                    "still be corrected and saved.",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
        }

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OutlinedTextField(
                value = village ?: place.village,
                onValueChange = { next ->
                    if (onVillageChange != null) onVillageChange(next) else setPlace(place.copy(village = next))
                },
                label = { Text("Village / place") },
                supportingText = {
                    Text(
                        "The settlement itself — just its name. The state and district go in the " +
                            "two boxes above rather than into this one.",
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OutlinedTextField(
                value = place.pincode,
                // Filtered rather than validated after the fact, so a pasted "380 001" becomes six
                // digits instead of an error message about spaces.
                onValueChange = { input ->
                    pincodeProblemShown = false
                    setPlace(place.copy(pincode = input.filter { it in '0'..'9' }.take(PINCODE_LENGTH)))
                },
                label = { Text("Pincode (optional)") },
                placeholder = { Text("303007") },
                isError = showPincodeProblem,
                supportingText = {
                    val shown = pincodeProblem?.takeIf { showPincodeProblem }
                    Text(
                        shown ?: "Six digits, if you know it. Most rural points have no postcode " +
                            "the geocoder can find, which is why the district above is the one " +
                            "that is required.",
                        color = if (shown != null) MaterialTheme.colorScheme.error else MaterialTheme.field.muted,
                        fontSize = 12.sp
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focus ->
                        if (!focus.isFocused && place.pincode.isNotEmpty()) pincodeProblemShown = true
                    }
            )
        }

        // The artisan's own pin. Optional, and deliberately a separate coordinate from the one the
        // GPS writes: a statement about the artisan that shared the captured-at row would be
        // overwritten by the next fix.
        if (place.pinLat.isNotBlank() && place.pinLng.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Artisan pin: ${place.pinLat}, ${place.pinLng}",
                    color = MaterialTheme.field.body,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { setPlace(place.copy(pinLat = "", pinLng = "")) },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Remove pin") }
            }
        }
        OutlinedButton(
            onClick = { showArtisanMap = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Text(
                if (place.pinLat.isBlank()) {
                    "Pin the artisan's location on the map (optional)"
                } else {
                    "Move the artisan's pin"
                }
            )
        }

        if (value == null && !place.isEmpty) {
            // The API keeps this half of the address on the location row, which cannot exist
            // without a coordinate — so say it here rather than letting four answers vanish at save
            // time.
            GroupNotice(
                warn = true,
                text = "Add a captured location below — a GPS fix, a map pin or typed coordinates. " +
                    "The state, district, village and pincode are stored on the same row as the " +
                    "coordinates, and without one they are not saved."
            )
        }

        /*
         * FLAGGED, NEVER REWRITTEN.
         *
         * Two shapes of the same problem. Either the record states a location and its coordinates
         * are somewhere else, or it states none at all and its coordinates are the only thing a
         * reader has — which is the condition of all fifteen live records, whose coordinates are in
         * West Bengal and whose places were typed into a free-text box as Bagru, Kutch and
         * Rudraprayag. Both say what was found and change nothing. The researcher who was there is
         * the only one who knows whether the coordinate was the workshop or the desk, and a
         * migration that guessed would bury the evidence it guessed from.
         */
        if (coordinateState.isNotBlank() && place.state.isNotBlank() &&
            !coordinateState.equals(place.state, ignoreCase = true)
        ) {
            GroupNotice(
                warn = true,
                text = "The coordinates saved on this record are in $coordinateState, but its " +
                    "artisan location says ${place.state}. That is normal if the record was written " +
                    "up away from the workshop — the coordinates say where the device was, not " +
                    "where the artisan is. Nothing has been changed. Correct whichever of the two " +
                    "is wrong."
            )
        } else if (coordinateState.isNotBlank() && place.state.isBlank()) {
            GroupNotice(
                warn = true,
                text = "This record has coordinates in $coordinateState and no artisan location. " +
                    "Until this form existed the coordinates were the only location a record had, " +
                    "and they say where the device was — often a desk a long way from the workshop. " +
                    "Please say where the artisan is above. Nothing has been changed or guessed."
            )
        }

        HorizontalDivider(color = MaterialTheme.field.hairline)

        // ----- Group two: provenance -----
        val stamp = readableStamp(value?.capturedAt)
        val summary = when {
            value == null && required -> "not captured yet"
            value == null -> "not captured"
            else -> trimCoordinate(value.latitude) + ", " + trimCoordinate(value.longitude) +
                (value.accuracy?.let { " · ${radiusLabel(it)}" } ?: "") +
                (if (stamp.isNotEmpty()) " · $stamp" else "")
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(12.dp))
                .background(MaterialTheme.field.surface50, RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Captured at. $summary. Provenance only, not the " +
                        "artisan's location."
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                    heading()
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Captured at",
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Text(summary, color = MaterialTheme.field.muted, fontSize = 12.sp)
            }
            Text(
                if (expanded) "Hide" else "Show",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.clearAndSetSemantics { }
            )
        }

        // The requirement is announced even while collapsed: a researcher must not have to open a
        // panel to discover why Save refused. Not repeated once open — the card inside says it.
        if (required && value == null && showRequirementError && !expanded) {
            Text(LOCATION_REQUIRED_MESSAGE, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }

        AnimatedVisibility(visible = expanded) {
            Text(
                "Where this device was when the record was written, and how sure it is. " +
                    "Provenance — it is not the artisan's location and nothing reads it as one. " +
                    "Filled in automatically; correct it only if the device got it wrong." +
                    when {
                        value == null -> ""
                        stamp.isNotEmpty() -> " Taken $stamp."
                        // Every coordinate stored before this change is undated, and saying so is
                        // the point: an undated reading cannot be told apart from one taken a month
                        // later in another state.
                        else -> " This reading carries no timestamp — it predates the field."
                    },
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
        }
        /*
         * OUTSIDE the AnimatedVisibility, and drawing nothing while the panel is shut.
         *
         * The automatic fix is an effect of this card being composed, and AnimatedVisibility does
         * not compose what it is not showing — so folding the card inside it produced a form that
         * captured nothing until somebody thought to open a panel labelled "provenance", on a
         * record that cannot be saved without a coordinate. `collapsed` is what lets the panel be
         * shut by default and the capture still happen.
         */
        LocationCaptureCard(
            value = value,
            onChange = { next ->
                val hadCoordinate = value != null
                emitCoordinate(next)
                /*
                 * THE PASSIVE PATH, all of it: the fix taken when the form opens, "Use current GPS",
                 * the pin dropped on the capture map, and typed coordinates. Every one of them says
                 * where the DEVICE is, which is why none of them may overwrite a statement about the
                 * artisan — they fill what is blank and offer the rest.
                 *
                 * Only a NEW point is worth a lookup; retyping a decimal place is not.
                 */
                if (next != null && (!hadCoordinate || !sameCoordinate(next, value))) {
                    lookupPlace(next.latitude, next.longitude, next.accuracy, PlaceIntent.Passive)
                }
            },
            required = required,
            isEdit = isEdit,
            showRequirementError = showRequirementError,
            title = if (required) "Captured coordinates *" else "Captured coordinates",
            description = "The device's own reading. A pin or two typed numbers satisfy this " +
                "exactly as a satellite fix does.",
            collapsed = !expanded,
            // A permission prompt, a dead location switch or a minute with no fix all need the
            // researcher, and none of them can be read through a shut panel.
            onNeedsAttention = { if (it) expanded = true },
            onMessage = onMessage
        )
    }

    if (showArtisanMap) {
        MapPickerDialog(
            initialLat = place.pinLat.toDoubleOrNull() ?: value?.latitude,
            initialLng = place.pinLng.toDoubleOrNull() ?: value?.longitude,
            onDismiss = { showArtisanMap = false },
            /*
             * THE EXPLICIT PATH, and the only one in this file that overwrites.
             *
             * The researcher has pointed at the artisan's place, on a map, deliberately. That is a
             * direct assertion about where the artisan is, so the state, district and pincode of that
             * point are written over whatever was there — asking them to then confirm the state and
             * district implied by their own pin is a second question about one answer, and a second
             * question about one answer gets tapped through rather than read.
             *
             * The write is announced and undoable (see `autofill`) because an automatic write nobody
             * can see is how a Bagru pincode ended up on a Dehradun record. Announced and reversible
             * is a different thing from silent.
             *
             * The pin carries no accuracy, so the coarse-fix guard does not apply and correctly does
             * not: the researcher pointed at the place, and a pointer has no error radius.
             */
            onPick = { lat, lng ->
                setPlace(place.copy(pinLat = trimCoordinate(lat), pinLng = trimCoordinate(lng)))
                onMessage("Artisan pin set: ${trimCoordinate(lat)}, ${trimCoordinate(lng)}")
                showArtisanMap = false
                lookupPlace(lat, lng, accuracy = null, intent = PlaceIntent.Explicit)
            }
        )
    }
}
