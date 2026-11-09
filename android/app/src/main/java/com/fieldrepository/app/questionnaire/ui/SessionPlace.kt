package com.fieldrepository.app.questionnaire.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

private val PLACE_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private fun holdsPlacePermission(context: Context): Boolean =
    PLACE_PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

@Composable
fun PlaceFromDevice(onPlace: (String) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun fetch() {
        busy = true
        scope.launch {
            val place = placeHere(context)
            busy = false
            if (place != null) onPlace(place) else onError(QuestionnaireCopy.s("place.notFound"))
        }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) fetch() else onError(QuestionnaireCopy.s("place.permission"))
    }
    IconButton(
        enabled = !busy,
        onClick = { if (holdsPlacePermission(context)) fetch() else ask.launch(PLACE_PERMISSIONS) },
        modifier = Modifier.testTag("q-place-here")
    ) {
        Icon(Icons.Outlined.MyLocation, contentDescription = QuestionnaireCopy.s("place.useLocation"))
    }
}

@SuppressLint("MissingPermission")
internal suspend fun placeHere(context: Context): String? {
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val recent = runCatching {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 10 * 60_000L }
            .minByOrNull { it.accuracy }
    }.getOrNull()
    val fix = recent ?: withTimeoutOrNull(15_000) { oneFix(manager) } ?: return null
    return placeAt(context, fix.latitude, fix.longitude)
}

internal suspend fun placeAt(context: Context, latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
    if (!Geocoder.isPresent()) return@withContext null
    runCatching {
        @Suppress("DEPRECATION")
        val first = Geocoder(context, Locale.UK).getFromLocation(latitude, longitude, 1)?.firstOrNull()
        placeWords(first?.subAdminArea, first?.adminArea)
    }.getOrNull()
}

fun placeWords(district: String?, state: String?): String? =
    listOfNotNull(district?.trim()?.ifBlank { null }, state?.trim()?.ifBlank { null })
        .distinct().joinToString(", ").ifBlank { null }

@SuppressLint("MissingPermission")
private suspend fun oneFix(manager: LocationManager): Location? = suspendCancellableCoroutine { cont ->
    val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    if (provider == null) {
        cont.resume(null)
        return@suspendCancellableCoroutine
    }
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            runCatching { manager.removeUpdates(this) }
            if (cont.isActive) cont.resume(location)
        }
    }
    runCatching { manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }
        .onFailure { if (cont.isActive) cont.resume(null) }
    cont.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
}
