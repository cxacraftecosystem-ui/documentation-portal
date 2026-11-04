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
import androidx.core.content.ContextCompat
import com.fieldrepository.app.data.LocationRequest
import kotlinx.coroutines.delay

private const val SATELLITE_FIX_LIMIT_METRES = 1000.0

private const val GOOD_ENOUGH_FIX_METRES = 100.0

private const val CAPTURE_BUDGET_SECONDS = 60

private const val CACHED_FIX_MAX_AGE_MS = 2 * 60 * 1000L

private const val EDIT_FETCH_GRACE_MS = 1500L

private enum class CaptureStatus {
    Idle,
    Locating,
    Fixed,
    PermissionDenied,
    PermissionBlocked,
    ServicesOff,
    Unsupported,
    TimedOut
}

private fun accuracyLabel(metres: Double): String =
    if (metres < 1000) "±${Math.round(metres)} m" else "±${String.format("%.1f", metres / 1000)} km"

private fun trimCoordinate(value: Double): String = String.format("%.6f", value)

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

private fun locationServicesOn(manager: LocationManager): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        manager.isLocationEnabled
    } else {
        @Suppress("DEPRECATION")
        (runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ||
            runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false))
    }

private fun freshCachedFix(context: Context, manager: LocationManager): Location? {
    if (!hasLocationPermission(context)) return null
    val cutoff = System.currentTimeMillis() - CACHED_FIX_MAX_AGE_MS
    return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .mapNotNull { provider ->
            try {
                manager.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                null
            } catch (e: RuntimeException) {
                null
            }
        }
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

private class FixListener(private val onFix: (Location) -> Unit) : LocationListener {
    override fun onLocationChanged(location: Location) = onFix(location)

    @Deprecated("Required below API 30")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit
}

fun locationRequirementError(value: LocationRequest?, required: Boolean): String? =
    if (required && value == null) LOCATION_REQUIRED_MESSAGE else null

const val LOCATION_REQUIRED_MESSAGE: String =
    "Location required — use GPS, drop a pin, or type coordinates."

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
        Text(text, color = ink, style = MaterialTheme.typography.bodySmall)
        action?.invoke()
    }
}

@Composable
fun LocationCaptureCard(
    value: LocationRequest?,
    onChange: (LocationRequest?) -> Unit,
    modifier: Modifier = Modifier,
    required: Boolean = true,
    isEdit: Boolean = false,
    showRequirementError: Boolean = false,
    title: String? = null,
    description: String? = null,
    collapsed: Boolean = false,
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
    var listening by remember { mutableStateOf(false) }
    var bestMetres by remember { mutableStateOf<Double?>(null) }
    var promptShown by remember { mutableStateOf(false) }
    val latest = rememberUpdatedState(onChange)
    val currentValue = rememberUpdatedState(value)
    var emitted by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    LaunchedEffect(value?.latitude, value?.longitude) {
        val sameLat = value?.latitude == latText.trim().toDoubleOrNull()
        val sameLng = value?.longitude == lngText.trim().toDoubleOrNull()
        if (!sameLat || !sameLng) {
            latText = value?.latitude?.let(::trimCoordinate) ?: ""
            lngText = value?.longitude?.let(::trimCoordinate) ?: ""
        }
    }

    fun acceptFix(location: Location) {
        val metres = if (location.hasAccuracy()) location.accuracy.toDouble() else null
        bestMetres = metres
        capturedMetres = metres
        latText = trimCoordinate(location.latitude)
        lngText = trimCoordinate(location.longitude)
        status = CaptureStatus.Fixed
        emitted = location.latitude to location.longitude
        latest.value(
            location.toRequest().copy(
                state = currentValue.value?.state,
                pincode = currentValue.value?.pincode
            )
        )
    }

    fun diagnose(): CaptureStatus {
        val locationManager = manager ?: return CaptureStatus.Unsupported
        if (!locationServicesOn(locationManager)) return CaptureStatus.ServicesOff
        if (!hasLocationPermission(context)) {
            val activity = context.findActivity()
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

    LaunchedEffect(Unit) {
        if (isEdit || value != null) return@LaunchedEffect
        delay(EDIT_FETCH_GRACE_MS)
        if (currentValue.value != null) return@LaunchedEffect
        requestPermissionThenCapture()
    }

    LaunchedEffect(value?.latitude, value?.longitude) {
        val incoming = value?.let { it.latitude to it.longitude } ?: return@LaunchedEffect
        if (incoming == emitted) return@LaunchedEffect
        listening = false
        capturedMetres = null
        if (status == CaptureStatus.Locating || status == CaptureStatus.Fixed) status = CaptureStatus.Idle
    }

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
                try {
                    locationManager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                } catch (e: SecurityException) {
                } catch (e: RuntimeException) {
                }
            }
            onDispose { runCatching { locationManager.removeUpdates(listener) } }
        }
    }

    LaunchedEffect(listening) {
        if (!listening) return@LaunchedEffect
        while (seconds < CAPTURE_BUDGET_SECONDS) {
            delay(1000)
            seconds += 1
        }
        listening = false
        if (bestMetres == null && status == CaptureStatus.Locating) status = CaptureStatus.TimedOut
    }

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
            else -> Unit
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
                "Where this was documented. Captured automatically, or drop a pin."
            } else {
                "Where this was documented."
            },
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )

        when (status) {
            CaptureStatus.Locating -> CaptureNotice(
                warn = false,
                text = "Finding your location… ${seconds}s. " +
                    if (seconds >= 8) {
                        "Indoors this can take a minute — keep going, it fills in by itself."
                    } else {
                        "Keep going."
                    }
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }

            CaptureStatus.Fixed -> if (coarse) {
                CaptureNotice(
                    warn = true,
                    text = "Only accurate to ${accuracyLabel(capturedMetres ?: 0.0)} (network estimate). For a precise position, step outside and use GPS, or drop a pin."
                )
            } else {
                CaptureNotice(
                    warn = false,
                    text = "Location: $latText, $lngText" +
                        (capturedMetres?.let { " (${accuracyLabel(it)})" } ?: "") +
                        ". Correct it below if it's wrong."
                )
            }

            CaptureStatus.PermissionDenied -> CaptureNotice(
                warn = true,
                text = "Location permission declined" +
                    (if (required) ", and a location is required" else "") +
                    ". Allow it, or drop a pin."
            ) {
                OutlinedButton(onClick = { requestPermissionThenCapture() }) { Text("Allow location") }
            }

            CaptureStatus.PermissionBlocked -> CaptureNotice(
                warn = true,
                text = "Location is blocked for this app. Turn it on in Settings, or drop a pin."
            ) {
                OutlinedButton(onClick = { context.openAppSettings() }) { Text("Open app settings") }
            }

            CaptureStatus.ServicesOff -> CaptureNotice(
                warn = true,
                text = "Location is off. Turn it on and use GPS, or drop a pin."
            ) {
                OutlinedButton(onClick = { context.openLocationSettings() }) { Text("Open location settings") }
            }

            CaptureStatus.TimedOut -> CaptureNotice(
                warn = true,
                text = "No GPS fix after a minute. Step outside and try again, or drop a pin."
            )

            CaptureStatus.Unsupported -> CaptureNotice(
                warn = true,
                text = "No GPS on this phone. Drop a pin or type the coordinates."
            )

            CaptureStatus.Idle -> Unit
        }

        val settledWithNothing = status != CaptureStatus.Locating && status != CaptureStatus.Idle
        if (required && value == null && (showRequirementError || settledWithNothing)) {
            Text(
                LOCATION_REQUIRED_MESSAGE,
                color = if (showRequirementError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.field.onWarningContainer
                },
                style = MaterialTheme.typography.bodySmall
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
        ActionRow {
            OutlinedButton(
                onClick = {
                    onMessage("Requesting a location fix…")
                    requestPermissionThenCapture()
                },
            ) { Text("Use current GPS") }
            OutlinedButton(onClick = { showMap = true }) {
                Text("Pick on map")
            }
        }
        if (!required && (latText.isNotBlank() || lngText.isNotBlank())) {
            ActionRow {
                TextButton(onClick = { listening = false; emitted = null; latText = ""; lngText = ""; onChange(null) }) {
                    Text("Clear location")
                }
            }
        }
    }

    if (showMap) {
        MapPickerDialog(
            initialLat = latText.trim().toDoubleOrNull() ?: value?.latitude,
            initialLng = lngText.trim().toDoubleOrNull() ?: value?.longitude,
            onDismiss = { showMap = false },
            onPick = { lat, lng ->
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

private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun Context.openLocationSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
