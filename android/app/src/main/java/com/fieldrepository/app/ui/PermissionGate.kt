package com.fieldrepository.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

class PermissionGate internal constructor(
    private val context: Context,
    private val request: (String) -> Unit,
    private val park: (Pair<() -> Unit, String>?) -> Unit,
) {
    fun holds(permission: String): Boolean = gateHolds(context, permission)

    fun ask(permission: String, refusedSentence: String) = run(permission, refusedSentence) {}

    fun run(permission: String, refusedSentence: String, action: () -> Unit) {
        if (gateHolds(context, permission)) {
            action()
            return
        }
        park(action to refusedSentence)
        request(permission)
    }
}

private fun gateHolds(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun rememberPermissionGate(onRefused: (String) -> Unit): PermissionGate {
    val context = LocalContext.current
    val refused = rememberUpdatedState(onRefused)
    val parked = remember { arrayOfNulls<Pair<() -> Unit, String>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = parked[0]
        parked[0] = null
        if (pending == null) return@rememberLauncherForActivityResult
        if (granted) pending.first() else refused.value(pending.second)
    }
    return remember(context, launcher) {
        PermissionGate(context, { launcher.launch(it) }, { parked[0] = it })
    }
}

const val CAMERA_REFUSED_SENTENCE =
    "Camera access is off. Allow it in Settings, or pick a photo instead."
const val MICROPHONE_REFUSED_SENTENCE =
    "Microphone access is off. Allow it in Settings, or pick a recording instead."

const val CAMERA_PERMISSION: String = Manifest.permission.CAMERA
const val MICROPHONE_PERMISSION: String = Manifest.permission.RECORD_AUDIO

val LOCATION_PERMISSIONS: Array<String> =
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

fun holdsLocation(context: Context): Boolean =
    LOCATION_PERMISSIONS.any { gateHolds(context, it) }
