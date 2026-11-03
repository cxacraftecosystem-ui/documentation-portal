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

const val LOCATION_META_DISTRICT: String = "district"
const val LOCATION_META_VILLAGE: String = "village"

const val LOCATION_META_ARTISAN_LAT: String = "artisanLatitude"
const val LOCATION_META_ARTISAN_LNG: String = "artisanLongitude"

data class InheritedPlace(
    val fromName: String,
    val sourceId: String?,
    val state: String?,
    val district: String?,
    val village: String?,
    val pincode: String? = null
)

internal data class InheritResult(
    val state: String,
    val district: String,
    val village: String,
    val filled: List<String>,
    val pincode: String = ""
)

internal fun inheritStatedAddress(state: String, district: String, village: String, from: InheritedPlace, pincode: String = ""): InheritResult {
    val filled = mutableListOf<String>()
    var nextState = state
    val theirState = from.state?.trim().orEmpty()
    if (nextState.isBlank() && theirState.isNotEmpty()) {
        nextState = theirState
        filled += "state"
    }
    var nextDistrict = district
    val theirDistrict = from.district?.trim().orEmpty()
    if (nextDistrict.isBlank() && theirDistrict.isNotEmpty() && nextState.equals(theirState, ignoreCase = true)) {
        nextDistrict = theirDistrict
        filled += "district"
    }
    var nextVillage = village
    val theirVillage = from.village?.trim().orEmpty()
    if (nextVillage.isBlank() && theirVillage.isNotEmpty()) {
        nextVillage = theirVillage
        filled += "village"
    }
    val theirPincode = from.pincode.orEmpty().filter(Char::isDigit).take(PINCODE_LENGTH)
    var nextPincode = pincode
    if (nextPincode.isBlank() && theirPincode.isNotEmpty() && (theirState.isEmpty() || nextState.equals(theirState, ignoreCase = true))) {
        nextPincode = theirPincode
        filled += "pincode"
    }
    return InheritResult(nextState, nextDistrict, nextVillage, filled, nextPincode)
}

internal fun inheritedFieldWords(filled: List<String>): String = when (filled.size) {
    0 -> ""
    1 -> filled[0]
    else -> filled.dropLast(1).joinToString(", ") + " and " + filled.last()
}

private fun LocationRequest.meta(key: String): String =
    (extraMetadata?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()

private fun LocationRequest.withMeta(vararg pairs: Pair<String, JsonPrimitive?>): LocationRequest {
    val merged = LinkedHashMap<String, JsonElement>(extraMetadata.orEmpty())
    pairs.forEach { (key, value) -> if (value == null) merged.remove(key) else merged[key] = value }
    return copy(extraMetadata = if (merged.isEmpty()) null else JsonObject(merged))
}

private fun metaText(value: String): JsonPrimitive? =
    value.trim().takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) }

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

private fun LocationRequest.statedPlace(): StatedPlace = StatedPlace(
    state = state.orEmpty(),
    district = district?.trim().orEmpty().ifEmpty { meta(LOCATION_META_DISTRICT) },
    village = village?.trim().orEmpty().ifEmpty { meta(LOCATION_META_VILLAGE) },
    pincode = pincode.orEmpty(),
    pinLat = subjectLatitude?.let { trimCoordinate(it) } ?: meta(LOCATION_META_ARTISAN_LAT),
    pinLng = subjectLongitude?.let { trimCoordinate(it) } ?: meta(LOCATION_META_ARTISAN_LNG)
)

private fun LocationRequest.withStatedPlace(place: StatedPlace): LocationRequest {
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
        LOCATION_META_ARTISAN_LAT to pin?.let { JsonPrimitive(it.first) },
        LOCATION_META_ARTISAN_LNG to pin?.let { JsonPrimitive(it.second) }
    )
}

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

    fun write(context: Context, value: AddressReferenceDto): Boolean = runCatching {
        val file = File(context.filesDir, REFERENCE_CACHE_FILE)
        val encoded = referenceJson.encodeToString(AddressReferenceDto.serializer(), value)
        if (file.exists() && file.readText() == encoded) return false
        file.writeText(encoded)
        true
    }.getOrDefault(false)
}

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

private fun stateOptions(current: String, reference: AddressReferenceDto): List<Pair<String, String>> {
    val served = reference.statesAndUnionTerritories.ifEmpty { reference.states }
    val known = served.any { it.equals(current, ignoreCase = true) }
    val all = if (current.isNotBlank() && !known) listOf(current) + served else served
    return all.map { it to it }
}

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

private const val GEOCODE_ACCURACY_LIMIT_METRES = 1000.0

private const val LOOKUP_DEBOUNCE_MS = 400L

private val DISTRICT_SUFFIXES = listOf("district", "districts", "zila", "zilla", "jila", "jilla")

private fun foldName(value: String): String =
    value.lowercase(Locale.UK).replace("&", "and").filter { it in 'a'..'z' || it in '0'..'9' }

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

private fun matchDistrict(text: String, districts: List<String>): String {
    val wanted = foldName(normaliseDistrictName(text))
    if (wanted.isEmpty()) return ""
    districts.firstOrNull { foldName(it) == wanted }?.let { return it }
    return districts.firstOrNull { entry ->
        val name = foldName(entry)
        (name.length >= 5 && wanted.contains(name)) || (wanted.length >= 5 && name.contains(wanted))
    }.orEmpty()
}

private fun usablePincode(text: String?): String {
    val digits = text.orEmpty().filter { it in '0'..'9' }
    return if (pincodeValidationError(digits) == null) digits else ""
}

private data class PlaceSuggestion(
    val state: String = "",
    val district: String = "",
    val pincode: String = ""
) {
    val isEmpty: Boolean get() = state.isBlank() && district.isBlank() && pincode.isBlank()
}

private enum class PlaceField(val label: String) {
    State("state"),
    District("district"),
    Pincode("pincode")
}

private fun fieldNames(fields: List<PlaceField>): String = when (fields.size) {
    0 -> ""
    1 -> fields.first().label
    else -> fields.dropLast(1).joinToString(", ") { it.label } + " and " + fields.last().label
}

private enum class PlaceIntent {
    Explicit,

    Passive
}

private data class AppliedWrite(
    val fields: List<PlaceField>,
    val previous: PlaceSuggestion,
    val intent: PlaceIntent
)

private fun StatedPlace.geocodable(): PlaceSuggestion = PlaceSuggestion(state, district, pincode)

private fun PlaceSuggestion.crediting(fields: List<PlaceField>, values: PlaceSuggestion) = PlaceSuggestion(
    state = if (PlaceField.State in fields) values.state else state,
    district = if (PlaceField.District in fields) values.district else district,
    pincode = if (PlaceField.Pincode in fields) values.pincode else pincode
)

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

private fun coarseRadius(accuracy: Double?): Double? =
    accuracy?.takeIf { it > GEOCODE_ACCURACY_LIMIT_METRES }

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

private fun trimCoordinate(value: Double): String = String.format(Locale.UK, "%.6f", value)

private fun radiusLabel(metres: Double): String =
    if (metres < 1000) "±${Math.round(metres)} m" else "±${String.format(Locale.UK, "%.1f", metres / 1000)} km"

private fun isoNow(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.UK).format(Date())

private fun readableStamp(iso: String?): String {
    val raw = iso?.trim().orEmpty()
    if (raw.length < 19) return ""
    val instant = raw.substring(0, 19)
    val tail = raw.substring(19)
    val zone = when {
        tail.endsWith("Z") || tail.isEmpty() -> TimeZone.getTimeZone("UTC")
        else -> TimeZone.getTimeZone("GMT" + tail.takeLast(6))
    }
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.UK).apply { timeZone = zone }
    val parsed = runCatching { parser.parse(instant) }.getOrNull() ?: return ""
    return SimpleDateFormat("d MMM yyyy, HH:mm", Locale.UK).format(parsed)
}

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
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        Text(subtitle, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun GroupNotice(warn: Boolean, text: String) {
    val container = if (warn) MaterialTheme.field.warningContainer else MaterialTheme.field.surface100
    val ink = if (warn) MaterialTheme.field.onWarningContainer else MaterialTheme.field.muted
    Text(
        text,
        color = ink,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(12.dp))
            .padding(12.dp)
    )
}

fun artisanLocationRequirementError(value: LocationRequest?, heading: String = ARTISAN_LOCATION_HEADING): String? {
    val place = value?.statedPlace() ?: return LOCATION_REQUIRED_MESSAGE
    if (place.state.isBlank()) return "Choose the state under '$heading' before saving."
    if (place.district.isBlank()) return "Choose the district under '$heading' before saving."
    return null
}

const val ARTISAN_LOCATION_HEADING: String = "Artisan location"

internal fun locationSubtitle(heading: String): String {
    val where = when (heading) {
        ARTISAN_LOCATION_HEADING -> "Where the artisan works. "
        "Craft location" -> "Where the craft is practised. "
        "Workshop location" -> "Where the workshop is held. "
        else -> ""
    }
    return where + "A map pin fills in the state, district and pincode."
}

@Composable
fun LocationFieldsSection(
    repository: FieldRepository,
    value: LocationRequest?,
    onChange: (LocationRequest?) -> Unit,
    modifier: Modifier = Modifier,
    required: Boolean = true,
    isEdit: Boolean = false,
    showRequirementError: Boolean = false,
    village: String? = null,
    onVillageChange: ((String) -> Unit)? = null,
    onMessage: (String) -> Unit = {},
    heading: String = ARTISAN_LOCATION_HEADING,
    inherit: InheritedPlace? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reference = rememberAddressReference(repository)
    var parked by remember { mutableStateOf(StatedPlace()) }
    var inherited by remember { mutableStateOf<Pair<InheritedPlace, InheritResult>?>(null) }
    var inheritPrevious by remember { mutableStateOf(StatedPlace()) }
    var expanded by remember { mutableStateOf(false) }
    var pincodeProblemShown by remember { mutableStateOf(false) }
    var showArtisanMap by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf(PlaceSuggestion()) }
    var autofill by remember { mutableStateOf<AppliedWrite?>(null) }
    var coarseFixMetres by remember { mutableStateOf<Double?>(null) }
    var offer by remember { mutableStateOf<PlaceSuggestion?>(null) }
    var offerPoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var appliedNowStale by remember { mutableStateOf(false) }
    val latest = rememberUpdatedState(value)
    val lookup = remember { mutableStateOf<Job?>(null) }
    var lookupIntent by remember { mutableStateOf(PlaceIntent.Passive) }
    var coordinateState by remember { mutableStateOf("") }
    var sessionPoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    val place = value?.statedPlace() ?: parked
    val pincodeProblem = pincodeValidationError(place.pincode)
    val showPincodeProblem = pincodeProblem != null &&
        (pincodeProblemShown || place.pincode.length == PINCODE_LENGTH)
    val districts = districtOptions(place.state, place.district, reference)
    val districtsKnown = reference.districts?.byState?.isNotEmpty() == true

    fun setPlace(next: StatedPlace) {
        appliedNowStale = false
        autofill = null
        val current = latest.value
        if (current == null) parked = next else onChange(current.withStatedPlace(next))
    }

    LaunchedEffect(inherit) {
        val source = inherit ?: return@LaunchedEffect
        var held = latest.value?.statedPlace() ?: parked
        inherited?.let { (_, before) ->
            held = held.copy(
                state = if ("state" in before.filled && held.state == before.state) inheritPrevious.state else held.state,
                district = if ("district" in before.filled && held.district == before.district) inheritPrevious.district else held.district,
                village = if ("village" in before.filled && held.village == before.village) inheritPrevious.village else held.village,
                pincode = if ("pincode" in before.filled && held.pincode == before.pincode) inheritPrevious.pincode else held.pincode
            )
        }
        val result = inheritStatedAddress(held.state, held.district, held.village, source, held.pincode)
        if (result.filled.isEmpty()) return@LaunchedEffect
        inheritPrevious = held
        inherited = source to result
        setPlace(held.copy(state = result.state, district = result.district, village = result.village, pincode = result.pincode))
        onVillageChange?.let { if ("village" in result.filled) it(result.village) }
    }

    fun emitCoordinate(next: LocationRequest?) {
        if (next == null) {
            parked = place
            sessionPoint = null
            onChange(null)
            return
        }
        sessionPoint = next.latitude to next.longitude
        val moved = !sameCoordinate(next, value)
        onChange(
            next
                .copy(capturedAt = if (moved) isoNow() else value?.capturedAt)
                .withStatedPlace(place)
        )
    }

    fun applyPlace(fresh: PlaceSuggestion, intent: PlaceIntent): AppliedWrite {
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
        val stateAgrees = fresh.state.isNotBlank() && next.state.equals(fresh.state, ignoreCase = true)
        val district = if (stateAgrees) fresh.district else ""
        val contradicted = next.state.isNotBlank() && fresh.state.isNotBlank() && !stateAgrees
        val pincode = if (contradicted) "" else fresh.pincode

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

        val write = AppliedWrite(fields = touched.toList(), previous = previous, intent = intent)
        if (touched.isEmpty()) return write
        applied = applied.crediting(touched, next.geocodable())
        pincodeProblemShown = false
        setPlace(next)
        autofill = write
        return write
    }

    fun undoAutofill() {
        val write = autofill ?: return
        val held = latest.value?.statedPlace() ?: parked
        applied = applied.crediting(write.fields, PlaceSuggestion())
        setPlace(
            held.copy(
                state = write.previous.state,
                district = write.previous.district,
                pincode = write.previous.pincode
            )
        )
    }

    fun lookupPlace(lat: Double, lng: Double, accuracy: Double?, intent: PlaceIntent) {
        val running = lookup.value
        val explicitInFlight = lookupIntent == PlaceIntent.Explicit && running?.isActive == true
        if (intent == PlaceIntent.Passive && explicitInFlight) return
        running?.cancel()
        lookupIntent = intent
        offer = null
        offerPoint = null
        coarseFixMetres = coarseRadius(accuracy)
        if (coarseFixMetres != null) return

        lookup.value = scope.launch {
            delay(LOOKUP_DEBOUNCE_MS)
            val fresh = suggestPlaceFor(context, lat, lng, accuracy, reference)

            when (intent) {
                PlaceIntent.Explicit -> {
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
                val held = latest.value?.statedPlace() ?: parked
                val stale = !applied.isEmpty && (
                    (applied.state.isNotBlank() && held.state == applied.state) ||
                        (applied.district.isNotBlank() && held.district == applied.district) ||
                        (applied.pincode.isNotBlank() && held.pincode == applied.pincode)
                    )
                appliedNowStale = stale
                if (stale) autofill = null
                if (intent == PlaceIntent.Explicit) {
                    onMessage("No address for that pin — set the state and district.")
                }
                return@launch
            }

            appliedNowStale = false
            val write = applyPlace(fresh, intent)
            if (intent == PlaceIntent.Explicit) return@launch
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

    fun acceptOffer(fresh: PlaceSuggestion) {
        offer = null
        offerPoint = null
        applyPlace(fresh, PlaceIntent.Explicit)
    }

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

    LaunchedEffect(value?.latitude, value?.longitude, reference.version) {
        coordinateState = ""
        val current = value ?: return@LaunchedEffect
        if (sessionPoint == current.latitude to current.longitude) return@LaunchedEffect
        val metres = current.accuracy
        if (metres != null && metres > GEOCODE_ACCURACY_LIMIT_METRES) return@LaunchedEffect
        val (adminArea, _, _) = geocode(context, current.latitude, current.longitude)
        val served = reference.statesAndUnionTerritories.ifEmpty { reference.states }
        coordinateState = adminArea?.let { matchIndianState(it, served) }.orEmpty()
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {

        GroupHeading(
            title = heading,
            subtitle = locationSubtitle(heading)
        )

        inherited?.let { (source, result) ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.surface100, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "From ${source.fromName}'s record: ${inheritedFieldWords(result.filled)}. Change any box to override.",
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodySmall
                )
                ActionRow {
                    OutlinedButton(
                        onClick = {
                            val held = latest.value?.statedPlace() ?: parked
                            inherited = null
                            setPlace(
                                held.copy(
                                    state = if ("state" in result.filled) inheritPrevious.state else held.state,
                                    district = if ("district" in result.filled) inheritPrevious.district else held.district,
                                    village = if ("village" in result.filled) inheritPrevious.village else held.village,
                                    pincode = if ("pincode" in result.filled) inheritPrevious.pincode else held.pincode
                                )
                            )
                            if ("village" in result.filled) onVillageChange?.invoke(inheritPrevious.village)
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Undo") }
                }
            }
        }

        val written = autofill
        if (written != null) {
            val source = when (written.intent) {
                PlaceIntent.Explicit -> "Filled from your pin: "
                PlaceIntent.Passive -> "Filled from your location (empty boxes only): "
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.surface100, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    source + fieldNames(written.fields) + ". Check and correct if needed.",
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodySmall
                )
                ActionRow {
                    OutlinedButton(
                        onClick = { undoAutofill() },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Undo") }
                }
            }
        }

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
                    "You're at ${trimCoordinate(pendingPoint.first)}, ${trimCoordinate(pendingPoint.second)}" +
                        (value?.accuracy?.let { " (${radiusLabel(it)})" } ?: "") +
                        " — $named. Use as the artisan's location?",
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodySmall
                )
                ActionRow {
                    OutlinedButton(
                        onClick = { acceptOffer(pending) },
                        modifier = Modifier
                            
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
                    "No address for the new point. The state, district and pincode below are from the previous one — check them.",
                    color = MaterialTheme.field.onWarningContainer,
                    style = MaterialTheme.typography.bodySmall
                )
                ActionRow {
                    OutlinedButton(
                        onClick = { clearApplied() },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Clear copied fields") }
                }
            }
        }

        val coarse = coarseFixMetres
        if (coarse != null && (place.state.isBlank() || place.district.isBlank())) {
            GroupNotice(
                warn = true,
                text = "Location too rough (${radiusLabel(coarse)}) to read an address. Choose the state and district, or drop a pin."
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
                districts.isEmpty() -> "District list not downloaded"
                else -> "Select district"
            },
            onSelect = { setPlace(place.copy(district = it)) }
        )
        if (place.state.isNotBlank() && districts.isEmpty() && !districtsKnown) {
            Text(
                "Connect once to download districts — then this works offline. New records need a district.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
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
                        "Just the village or town name.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OutlinedTextField(
                value = place.pincode,
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
                        shown ?: "Six digits, if known.",
                        color = if (shown != null) MaterialTheme.colorScheme.error else MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
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

        if (place.pinLat.isNotBlank() && place.pinLng.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Artisan pin: ${place.pinLat}, ${place.pinLng}",
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodySmall,
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
                    "Pin on map (optional)"
                } else {
                    "Move the artisan's pin"
                }
            )
        }

        if (value == null && !place.isEmpty) {
            GroupNotice(
                warn = true,
                text = "Add a location below — the address saves with it."
            )
        }

        if (coordinateState.isNotBlank() && place.state.isNotBlank() &&
            !coordinateState.equals(place.state, ignoreCase = true)
        ) {
            GroupNotice(
                warn = true,
                text = "Coordinates are in $coordinateState but the artisan location says ${place.state}. Correct whichever is wrong."
            )
        } else if (coordinateState.isNotBlank() && place.state.isBlank()) {
            GroupNotice(
                warn = true,
                text = "Coordinates are in $coordinateState, but no artisan location is set. Add it above."
            )
        }

        HorizontalDivider(color = MaterialTheme.field.hairline)

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
                    style = MaterialTheme.typography.titleMedium
                )
                Text(summary, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (expanded) "Hide" else "Show",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clearAndSetSemantics { }
            )
        }

        if (required && value == null && showRequirementError && !expanded) {
            Text(LOCATION_REQUIRED_MESSAGE, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        AnimatedVisibility(visible = expanded) {
            Text(
                "Where the phone was when this was recorded. Not the artisan's location." +
                    when {
                        value == null -> ""
                        stamp.isNotEmpty() -> " Taken $stamp."
                        else -> " No timestamp."
                    },
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
        LocationCaptureCard(
            value = value,
            onChange = { next ->
                val hadCoordinate = value != null
                emitCoordinate(next)
                if (next != null && (!hadCoordinate || !sameCoordinate(next, value))) {
                    lookupPlace(next.latitude, next.longitude, next.accuracy, PlaceIntent.Passive)
                }
            },
            required = required,
            isEdit = isEdit,
            showRequirementError = showRequirementError,
            title = if (required) "Captured coordinates *" else "Captured coordinates",
            description = "GPS, a pin, or typed coordinates.",
            collapsed = !expanded,
            onNeedsAttention = { if (it) expanded = true },
            onMessage = onMessage
        )
    }

    if (showArtisanMap) {
        MapPickerDialog(
            initialLat = place.pinLat.toDoubleOrNull() ?: value?.latitude,
            initialLng = place.pinLng.toDoubleOrNull() ?: value?.longitude,
            onDismiss = { showArtisanMap = false },
            onPick = { lat, lng ->
                setPlace(place.copy(pinLat = trimCoordinate(lat), pinLng = trimCoordinate(lng)))
                onMessage("Artisan pin set: ${trimCoordinate(lat)}, ${trimCoordinate(lng)}")
                showArtisanMap = false
                lookupPlace(lat, lng, accuracy = null, intent = PlaceIntent.Explicit)
            }
        )
    }
}
