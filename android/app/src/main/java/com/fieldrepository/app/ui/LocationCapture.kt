package com.fieldrepository.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.fieldrepository.app.data.LocationRequest
import kotlinx.coroutines.delay

/*
 * ---------------------------------------------------------------------------
 * Location capture: the field client's half of "location is mandatory, and it
 * fills itself in".
 *
 * WHAT "MANDATORY" MEANS HERE, because it is the decision everything else hangs
 * off. A record cannot be saved without a COORDINATE. It can be saved without
 * the GPS ever having worked: a pin dropped on the map, or two numbers typed
 * in, satisfy the requirement exactly as a satellite fix does. The alternative
 * — "the fix must have succeeded" — hands the veto over a day of fieldwork to a
 * permission somebody tapped Deny on last month, a phone whose location toggle
 * is off to save battery, and a workshop with a tin roof. A missing coordinate
 * is a gap in a dataset; a rejected save is an interview that never got written
 * down, and only one of those is recoverable. So every path through this file
 * that ends in "the device cannot answer" ends by pointing at the map.
 *
 * Word for word the same rule, and very nearly the same sentences, as
 * frontend/components/forms/LocationFields.tsx. A researcher who uses the phone
 * in the workshop and the laptop afterwards should not have to learn it twice.
 * ---------------------------------------------------------------------------
 */

/**
 * The accuracy radius past which a fix is not a satellite reading.
 *
 * A satellite fix is good to tens of metres. A device with no lock — indoors, under a tin roof,
 * inside a courtyard — falls back to the mobile network or Wi-Fi and reports kilometres while
 * returning coordinates that look every bit as precise. Past a kilometre nothing is looking at
 * satellites, and a position nobody asked for and nobody was told about is the definition of a bad
 * fix saved as fact.
 *
 * Above the line the coordinate is still KEPT — dropping it would leave the record with no location
 * at all over a radius nobody would otherwise have noticed, and a rough position beats none. What
 * changes is that it is NAMED, with its radius, in words, and that the [LocationRequest.placeName]
 * it carries into the database says which kind of fix produced it.
 *
 * Same 1000 m as the web card's `PINCODE_ACCURACY_LIMIT_METRES`, for the same physical reason.
 */
private const val SATELLITE_FIX_LIMIT_METRES = 1000.0

/**
 * The radius at which the automatic capture stops waiting for something better.
 *
 * Updates stream in as the receiver warms: the first is usually the network's guess, within a
 * second, and the ones after it improve. Something has to end the stream, or a phone left on a form
 * burns the battery a day of fieldwork depends on. A hundred metres is a genuine lock by any
 * reading, and the difference between 90 m and 12 m changes nothing anybody does with this record.
 */
private const val GOOD_ENOUGH_FIX_METRES = 100.0

/**
 * How long the automatic capture hunts before it settles for the best it saw.
 *
 * A cold start under a workshop roof genuinely takes thirty to sixty seconds, so a shorter budget
 * would routinely file the network estimate as the answer when a real fix was ten seconds away.
 * Nothing waits on it: the first update is already in the boxes and the form was never blocked.
 */
private const val CAPTURE_BUDGET_SECONDS = 60

/**
 * How old a cached fix may be before it is treated as no answer at all.
 *
 * This is the whole reason the capture below streams live updates instead of reading
 * `getLastKnownLocation` the way this app used to. That call returns whatever the platform last
 * heard from ANY app, which on a phone that was in Bagru yesterday and Dehradun today is Bagru —
 * and the old code stamped it onto the record with `placeName = "Android precise location"`, a
 * sentence that is wrong twice over. It is the same failure as the pincode that stayed behind when
 * the state moved on, arriving through a different door: a value that appeared by itself, was
 * believed, and had no way of announcing that it was stale.
 *
 * Two minutes is short enough that the researcher has not left the building, and long enough to
 * make the very common case — a form opened right after another one — instant.
 */
private const val CACHED_FIX_MAX_AGE_MS = 2 * 60 * 1000L

/**
 * How long the automatic capture waits before deciding this is a NEW record rather than an edit.
 *
 * Every edit form in this app fetches its record after mount, so at first composition an edit and a
 * create look identical: both hand this card a null location. A second and a half is comfortably
 * longer than that fetch on any connection worth calling one, and costs a new record nothing it was
 * not going to spend waiting for satellites regardless.
 */
private const val EDIT_FETCH_GRACE_MS = 1500L

/** Where the automatic capture has got to. Rendered, always: a field that fills itself in silence
 * is precisely the thing this must not be. */
private enum class CaptureStatus {
    /** Nothing running — the coordinate came from a pin or the keyboard, or an edit form loaded it. */
    Idle,
    Locating,
    Fixed,
    /** Declined this time. The system will still show the prompt again, so "Allow" is offered. */
    PermissionDenied,
    /** Declined permanently (or auto-denied). Only the app's settings page can undo it. */
    PermissionBlocked,
    /** The device's location master switch is off; no permission grant can work around that. */
    ServicesOff,
    /** No location providers at all — a tablet with no radios. */
    Unsupported,
    TimedOut
}

/** "±40 m" / "±12 km" — the radius as a field researcher would say it. Web card parity. */
private fun accuracyLabel(metres: Double): String =
    if (metres < 1000) "±${Math.round(metres)} m" else "±${String.format("%.1f", metres / 1000)} km"

private fun trimCoordinate(value: Double): String = String.format("%.6f", value)

/**
 * What produced this fix, in words, stored on the record.
 *
 * The old capture called everything "Android precise location", including a cached network estimate
 * from another district. The provider is the one thing the platform will tell you for free about
 * how much a coordinate is worth, so it goes in rather than a flattering constant.
 */
private fun providerPlaceName(provider: String?): String = when (provider) {
    LocationManager.GPS_PROVIDER -> "Device GPS fix"
    LocationManager.NETWORK_PROVIDER -> "Device network location"
    else -> "Device location"
}

private fun Context.findActivity(): Activity? {
    var cursor: Context? = this
    while (cursor is ContextWrapper) {
        if (cursor is Activity) return cursor
        cursor = cursor.baseContext
    }
    return null
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/** The device's master location switch. `isLocationEnabled` is API 28+; below that, ask the providers. */
private fun locationServicesOn(manager: LocationManager): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        manager.isLocationEnabled
    } else {
        @Suppress("DEPRECATION")
        (runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ||
            runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false))
    }

/**
 * A cached fix, but only while it is still plausibly about where the device is now.
 *
 * See [CACHED_FIX_MAX_AGE_MS]. Returning null rather than yesterday's coordinates is the point:
 * "no answer yet" is a state the card can show and the researcher can act on, and a wrong answer
 * dressed as a measurement is not.
 */
private fun freshCachedFix(context: Context, manager: LocationManager): Location? {
    if (!hasLocationPermission(context)) return null
    val cutoff = System.currentTimeMillis() - CACHED_FIX_MAX_AGE_MS
    return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
        .filter { it.time >= cutoff }
        .minByOrNull { it.accuracy }
}

private fun Location.toRequest(): LocationRequest = LocationRequest(
    latitude = latitude,
    longitude = longitude,
    altitude = if (hasAltitude()) altitude else null,
    accuracy = if (hasAccuracy()) accuracy.toDouble() else null,
    placeName = providerPlaceName(provider)
)

/**
 * Legacy [LocationListener] members. Default implementations only arrived in API 30 and this app
 * ships to API 26, so they are written out rather than left to desugaring.
 */
private class FixListener(private val onFix: (Location) -> Unit) : LocationListener {
    override fun onLocationChanged(location: Location) = onFix(location)

    @Deprecated("Required below API 30")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit
}

/**
 * The reason this record cannot be saved yet, or null when it can.
 *
 * Exported so each form's save handler can run it beside its other required-field checks and stop
 * BEFORE the request is built. That matters more than the duplicated server rule suggests: with no
 * signal a save goes into the offline outbox, and an outbox entry the API will refuse is a record
 * that sits there until somebody discards it. Catching it at the button keeps the interview.
 */
fun locationRequirementError(value: LocationRequest?, required: Boolean): String? =
    if (required && value == null) LOCATION_REQUIRED_MESSAGE else null

/** Web card parity, and deliberately about the fallback rather than the failure. */
const val LOCATION_REQUIRED_MESSAGE: String =
    "A location is required. Capture a GPS fix, drop a pin on the map, or enter the coordinates " +
        "by hand — any of the three satisfies this."

/**
 * A notice inside the location card. Two tones: [warn] for something the researcher has to act on,
 * plain for what is merely happening.
 */
@Composable
private fun CaptureNotice(warn: Boolean, text: String, action: (@Composable () -> Unit)? = null) {
    val container = if (warn) MaterialTheme.field.warningContainer else MaterialTheme.field.surface100
    val ink = if (warn) MaterialTheme.field.onWarningContainer else Muted
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text, color = ink, fontSize = 12.sp)
        action?.invoke()
    }
}

/**
 * The location card: coordinates, an automatic fix, a map pin, and the requirement.
 *
 * Replaces `LocationEditor` (ui/FieldComponents.kt) at its single call site, `LocationAddressEditor`
 * in MainActivity — which keeps owning the state and pincode that ride on the same row. The
 * coordinate half is what changed:
 *
 *  - it asks for a fix ITSELF on first composition, with no tap, and shows that it is doing so;
 *  - it streams live updates rather than reading a cached one, so the coordinate belongs to this
 *    place and this hour (see [CACHED_FIX_MAX_AGE_MS] for the bug that made that non-negotiable);
 *  - it drives the runtime permission prompt, and tells apart "declined" from "blocked" from
 *    "the device's location switch is off", because the researcher's next move differs in each;
 *  - it names the accuracy radius instead of calling everything precise;
 *  - and when [required] it refuses to offer "Clear location", because a button that puts the form
 *    into a state it cannot be saved from is a trap rather than a feature.
 *
 * [showRequirementError] is raised by the form's save handler (see [locationRequirementError]) so
 * the empty card explains itself at the moment Save is pressed, rather than in advance.
 */
@Composable
fun LocationCaptureCard(
    value: LocationRequest?,
    onChange: (LocationRequest?) -> Unit,
    modifier: Modifier = Modifier,
    required: Boolean = true,
    /**
     * Suppresses the automatic fix outright. An optimisation, not the safety net — pass it where a
     * form KNOWS it is editing, and read the two guards around the automatic capture below for why
     * a form that does not know is still safe.
     */
    isEdit: Boolean = false,
    showRequirementError: Boolean = false,
    /**
     * The heading and the sentence under it, when the caller frames this as something narrower than
     * "the location". `LocationFieldsSection` passes "Captured coordinates" because inside the
     * two-field model this card is provenance — where the DEVICE was — and calling it "Location"
     * there is the exact confusion that put fifteen Rajasthani artisans in West Bengal.
     */
    title: String? = null,
    description: String? = null,
    /**
     * Keep the machinery running but draw nothing.
     *
     * A caller that folds this card into a collapsed panel cannot simply leave it out of the
     * composition while the panel is shut: the automatic fix is an effect of being composed, so a
     * card that is not there does not capture, and a location the researcher never asked for and
     * never got is a record that cannot be saved. Everything above the layout below — the state,
     * the permission flow, the live stream, the budget — is declared before anything is drawn,
     * precisely so this flag can turn off the drawing and nothing else.
     */
    collapsed: Boolean = false,
    /**
     * Raised while the capture has stopped for a reason the researcher would have to act on: a
     * declined or blocked permission, the device's location switch, no hardware, or a minute with
     * no fix. A collapsed caller uses it to open itself — a warning inside a shut panel is a
     * warning nobody reads.
     */
    onNeedsAttention: (Boolean) -> Unit = {},
    onMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }
    var latText by remember { mutableStateOf(value?.latitude?.let(::trimCoordinate) ?: "") }
    var lngText by remember { mutableStateOf(value?.longitude?.let(::trimCoordinate) ?: "") }
    var showMap by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(CaptureStatus.Idle) }
    var seconds by remember { mutableIntStateOf(0) }
    var capturedMetres by remember { mutableStateOf<Double?>(null) }
    // True while the live stream should be running. Flipped off by a good enough fix, the budget,
    // a refusal, and above all by the researcher answering the question themselves.
    var listening by remember { mutableStateOf(false) }
    // The best radius this hunt has produced. A stream is not monotonically improving — a phone
    // handing over between towers genuinely gets worse again — so a later, worse update must not be
    // allowed to land on top of the satellite fix that already superseded it.
    var bestMetres by remember { mutableStateOf<Double?>(null) }
    // Whether the permission prompt has been shown once already, which is the only way to tell a
    // "not asked yet" false from `shouldShowRequestPermissionRationale` from a permanent denial.
    var promptShown by remember { mutableStateOf(false) }
    val latest = rememberUpdatedState(onChange)
    val currentValue = rememberUpdatedState(value)
    // The point this card last emitted, so an arriving `value` can be told apart from one the parent
    // loaded out of a record. See the guard below.
    var emitted by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    /*
     * The two boxes TRACK [value]; they are not seeded from it once.
     *
     * Inherited wholesale from `LocationEditor`, along with the reason, which has not stopped being
     * true: every edit form fetches its record AFTER mount, so plain unkeyed `remember`s showed
     * blank coordinates for a record that had a location — and blank is not merely wrong to read,
     * it is armed. `remember(value)` is not the fix either, because an emit happens on every
     * keystroke and "22.5" would be rewritten to "22.500000" under the cursor. This adopts an
     * incoming coordinate only when it is a genuinely different point from the one on screen.
     */
    LaunchedEffect(value?.latitude, value?.longitude) {
        val sameLat = value?.latitude == latText.trim().toDoubleOrNull()
        val sameLng = value?.longitude == lngText.trim().toDoubleOrNull()
        if (!sameLat || !sameLng) {
            latText = value?.latitude?.let(::trimCoordinate) ?: ""
            lngText = value?.longitude?.let(::trimCoordinate) ?: ""
        }
    }

    /** Take a fix, replacing every field it answers — including the ones it answers with nothing. */
    fun acceptFix(location: Location) {
        val metres = if (location.hasAccuracy()) location.accuracy.toDouble() else null
        bestMetres = metres
        capturedMetres = metres
        latText = trimCoordinate(location.latitude)
        lngText = trimCoordinate(location.longitude)
        status = CaptureStatus.Fixed
        emitted = location.latitude to location.longitude
        // The state and pincode already on the row are kept: the caller reverse-geocodes new
        // suggestions for the new point and owns whether they replace what a human typed.
        latest.value(
            location.toRequest().copy(
                state = currentValue.value?.state,
                pincode = currentValue.value?.pincode
            )
        )
    }

    /** Work out why there is no fix, so the notice can name the one thing that would fix it. */
    fun diagnose(): CaptureStatus {
        val locationManager = manager ?: return CaptureStatus.Unsupported
        if (!locationServicesOn(locationManager)) return CaptureStatus.ServicesOff
        if (!hasLocationPermission(context)) {
            val activity = context.findActivity()
            // `shouldShowRequestPermissionRationale` is false both before the first prompt and after
            // a permanent denial, which is why the prompt has to have been shown to read it at all.
            val canAskAgain = activity != null &&
                androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            return if (!promptShown || canAskAgain) CaptureStatus.PermissionDenied else CaptureStatus.PermissionBlocked
        }
        return CaptureStatus.TimedOut
    }

    fun beginCapture() {
        val locationManager = manager
        if (locationManager == null) {
            status = CaptureStatus.Unsupported
            return
        }
        if (!locationServicesOn(locationManager)) {
            status = CaptureStatus.ServicesOff
            return
        }
        if (!hasLocationPermission(context)) {
            status = diagnose()
            return
        }
        bestMetres = null
        seconds = 0
        status = CaptureStatus.Locating
        // A fix from the last two minutes goes in immediately so the boxes are never empty while the
        // receiver warms — and the live stream below replaces it the moment it does better.
        freshCachedFix(context, locationManager)?.let(::acceptFix)
        listening = true
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        promptShown = true
        if (granted.values.any { it }) beginCapture() else status = diagnose()
    }

    fun requestPermissionThenCapture() {
        if (hasLocationPermission(context)) {
            beginCapture()
            return
        }
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    /**
     * Ask on open, without being asked — but never on a record that already has a location.
     *
     * WHY THE PAUSE. An edit is as likely to be happening at a desk in another state a week later as
     * in the workshop, and stamping the editor's chair over a coordinate captured in Bagru is the
     * stale-pincode failure wearing a different hat. The trouble is that an edit form does not LOOK
     * like one at first composition: every one of them fetches its record afterwards, so `value` is
     * null here whether this is a new artisan or an old one being corrected. Waiting out the fetch
     * before asking anything is what tells them apart, and it costs a new record a second it spends
     * waiting for satellites anyway.
     *
     * The pause is a heuristic, so it is not the only guard: a location arriving later than this —
     * a slow rural fetch — still wins, immediately, in the effect below. [isEdit] short-circuits
     * both for the forms that already know, and is an optimisation rather than the safety net.
     */
    LaunchedEffect(Unit) {
        if (isEdit || value != null) return@LaunchedEffect
        delay(EDIT_FETCH_GRACE_MS)
        if (currentValue.value != null) return@LaunchedEffect
        requestPermissionThenCapture()
    }

    /**
     * A location this card did not produce outranks anything the device has to say about where the
     * phone is now: it came out of the record, which knows where the work actually happened.
     *
     * This is the guard that makes the pause above safe to be a guess. A fetch that lands after the
     * hunt has started stops it dead rather than racing it.
     */
    LaunchedEffect(value?.latitude, value?.longitude) {
        val incoming = value?.let { it.latitude to it.longitude } ?: return@LaunchedEffect
        if (incoming == emitted) return@LaunchedEffect
        listening = false
        capturedMetres = null
        if (status == CaptureStatus.Locating || status == CaptureStatus.Fixed) status = CaptureStatus.Idle
    }

    // The live stream. Attached to BOTH providers: the network answers in a second and the
    // satellites answer well, and the improvement rule below decides between them.
    DisposableEffect(listening) {
        val locationManager = manager
        if (!listening || locationManager == null || !hasLocationPermission(context)) {
            onDispose { }
        } else {
            val listener = FixListener { location ->
                val metres = if (location.hasAccuracy()) location.accuracy.toDouble() else Double.MAX_VALUE
                val best = bestMetres
                if (best == null || metres < best) acceptFix(location)
                if (metres <= GOOD_ENOUGH_FIX_METRES) listening = false
            }
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
                runCatching {
                    locationManager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                }
            }
            onDispose { runCatching { locationManager.removeUpdates(listener) } }
        }
    }

    // The elapsed counter, and the budget that ends the hunt. One loop rather than two: the number
    // on screen and the deadline are the same number.
    LaunchedEffect(listening) {
        if (!listening) return@LaunchedEffect
        while (seconds < CAPTURE_BUDGET_SECONDS) {
            delay(1000)
            seconds += 1
        }
        listening = false
        if (bestMetres == null && status == CaptureStatus.Locating) status = CaptureStatus.TimedOut
    }

    /** Emit whatever the two boxes now say. Any edit here is a human answering, so the hunt ends. */
    fun emitTyped() {
        listening = false
        if (status == CaptureStatus.Locating) status = CaptureStatus.Idle
        val lat = latText.trim().toDoubleOrNull()
        val lng = lngText.trim().toDoubleOrNull()
        when {
            lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0 -> {
                capturedMetres = null
                emitted = lat to lng
                onChange(
                    LocationRequest(
                        latitude = lat,
                        longitude = lng,
                        altitude = value?.altitude,
                        // A typed coordinate carries no measured radius, and leaving the last fix's
                        // would attribute its accuracy to a point it never measured.
                        accuracy = null,
                        placeName = value?.placeName ?: "Entered by hand",
                        state = value?.state,
                        pincode = value?.pincode
                    )
                )
            }
            latText.isBlank() && lngText.isBlank() -> {
                emitted = null
                onChange(null)
            }
            else -> Unit // incomplete or out of range: wait for the rest of the number
        }
    }

    val coarse = capturedMetres?.let { it > SATELLITE_FIX_LIMIT_METRES } == true

    val needsAttention = status == CaptureStatus.PermissionDenied ||
        status == CaptureStatus.PermissionBlocked ||
        status == CaptureStatus.ServicesOff ||
        status == CaptureStatus.Unsupported ||
        status == CaptureStatus.TimedOut
    val reportAttention = rememberUpdatedState(onNeedsAttention)
    LaunchedEffect(needsAttention) { reportAttention.value(needsAttention) }

    if (collapsed) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title ?: if (required) "Location *" else "Location",
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            description ?: if (required) {
                "Where this was documented, and it is required. The app captures it by itself when " +
                    "the form opens — if the device cannot answer, drop a pin on the map instead."
            } else {
                "Tag where this was documented. Enter coordinates, capture a GPS fix, or drop a pin " +
                    "on the map."
            },
            color = Muted,
            fontSize = 12.sp
        )

        when (status) {
            CaptureStatus.Locating -> CaptureNotice(
                warn = false,
                text = "Finding this device's location… ${seconds}s. " +
                    if (seconds >= 8) {
                        "A first satellite fix can take up to a minute indoors — carry on filling " +
                            "the form, the coordinates will drop in on their own."
                    } else {
                        "Carry on filling the form."
                    }
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }

            CaptureStatus.Fixed -> if (coarse) {
                CaptureNotice(
                    warn = true,
                    text = "This fix is only accurate to ${accuracyLabel(capturedMetres ?: 0.0)} — that is " +
                        "this device's network estimate of where it is, not a satellite reading. The " +
                        "coordinates have been kept so nothing is lost, and the radius is saved with " +
                        "them. If this record needs a real position, step outside and press Use " +
                        "current GPS, or drop a pin on the map."
                )
            } else {
                CaptureNotice(
                    warn = false,
                    text = "Location captured automatically: $latText, $lngText" +
                        (capturedMetres?.let { " (${accuracyLabel(it)})" } ?: "") +
                        ". Check it is the right place — correct it below or point somewhere else on " +
                        "the map if it is not."
                )
            }

            CaptureStatus.PermissionDenied -> CaptureNotice(
                warn = true,
                text = "Location permission was declined, so nothing could be captured" +
                    (if (required) ", and a location is required" else "") +
                    ". Allow it and the app will take the fix, or drop a pin on the map — a pin " +
                    "satisfies this exactly as a GPS fix does."
            ) {
                OutlinedButton(onClick = { requestPermissionThenCapture() }) { Text("Allow location") }
            }

            CaptureStatus.PermissionBlocked -> CaptureNotice(
                warn = true,
                text = "Location permission is blocked for this app, so only Android's settings can " +
                    "turn it back on. You do not have to: drop a pin on the map instead and the " +
                    "record saves normally."
            ) {
                OutlinedButton(onClick = { context.openAppSettings() }) { Text("Open app settings") }
            }

            CaptureStatus.ServicesOff -> CaptureNotice(
                warn = true,
                text = "Location is switched off on this device, so no app can get a fix. Switch it " +
                    "on and press Use current GPS, or drop a pin on the map instead."
            ) {
                OutlinedButton(onClick = { context.openLocationSettings() }) { Text("Open location settings") }
            }

            CaptureStatus.TimedOut -> CaptureNotice(
                warn = true,
                text = "No fix after a minute of trying — thick walls and tin roofs do this. Step " +
                    "outside and press Use current GPS, or drop a pin on the map."
            )

            CaptureStatus.Unsupported -> CaptureNotice(
                warn = true,
                text = "This device has no location hardware the app can use. Drop a pin on the map, " +
                    "or type the coordinates in below."
            )

            CaptureStatus.Idle -> Unit
        }

        /*
         * The requirement, in the canonical sentence.
         *
         * Shown red the moment a save handler raises [showRequirementError], and shown quietly
         * BEFORE that whenever the automatic capture has already come back empty-handed — which is
         * the whole set of cases where a researcher would otherwise fill the form in, tap Save, and
         * only then be told. Deliberately silent while the hunt is still running (nothing is wrong
         * yet) and while the card is idle with nothing captured (an edit form waiting on its fetch).
         */
        val settledWithNothing = status != CaptureStatus.Locating && status != CaptureStatus.Idle
        if (required && value == null && (showRequirementError || settledWithNothing)) {
            Text(
                LOCATION_REQUIRED_MESSAGE,
                color = if (showRequirementError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.field.onWarningContainer
                },
                fontSize = 12.sp
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = latText,
                onValueChange = { latText = it; emitTyped() },
                label = { Text("Latitude" + if (required) " *" else "") },
                isError = showRequirementError && value == null,
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = lngText,
                onValueChange = { lngText = it; emitTyped() },
                label = { Text("Longitude" + if (required) " *" else "") },
                isError = showRequirementError && value == null,
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    onMessage("Requesting a location fix…")
                    requestPermissionThenCapture()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Use current GPS") }
            OutlinedButton(onClick = { showMap = true }, modifier = Modifier.weight(1f)) {
                Text("Pick on map")
            }
        }
        // Offered only when the record can survive it. A "Clear location" on a mandatory field is a
        // button whose whole effect is to make the form unsaveable.
        if (!required && (latText.isNotBlank() || lngText.isNotBlank())) {
            TextButton(onClick = { listening = false; emitted = null; latText = ""; lngText = ""; onChange(null) }) {
                Text("Clear location")
            }
        }
    }

    if (showMap) {
        MapPickerDialog(
            initialLat = latText.trim().toDoubleOrNull() ?: value?.latitude,
            initialLng = lngText.trim().toDoubleOrNull() ?: value?.longitude,
            onDismiss = { showMap = false },
            onPick = { lat, lng ->
                // The researcher has pointed at the place. Nothing the satellites say afterwards
                // outranks that — and this is the path every refusal above falls back to, which is
                // why it has to satisfy the requirement on its own.
                listening = false
                status = CaptureStatus.Idle
                capturedMetres = null
                latText = trimCoordinate(lat)
                lngText = trimCoordinate(lng)
                emitted = lat to lng
                onChange(
                    LocationRequest(
                        latitude = lat,
                        longitude = lng,
                        placeName = "Pinned on map",
                        state = value?.state,
                        pincode = value?.pincode
                    )
                )
                onMessage("Pin set: ${trimCoordinate(lat)}, ${trimCoordinate(lng)}")
                showMap = false
            }
        )
    }
}

/** Android's own permission page for this app — the only place a blocked permission can be undone. */
private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** The device-wide location switch, which no permission grant can work around. */
private fun Context.openLocationSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
