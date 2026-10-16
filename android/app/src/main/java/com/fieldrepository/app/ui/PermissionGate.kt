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

/**
 * Run an action only once its permission is HELD — asking for it first, and waiting for the answer.
 *
 * UX audit 2026-10-16, DC-A01 (P0; the designer portal's DA-01, same file). The record forms used to call
 * `permissionLauncher.launch(everything)` and then `takePhoto.launch(uri)` on the very next line,
 * without waiting. The manifest declares CAMERA, so a `TakePicture` started by an app that declares
 * it without holding it throws SecurityException — the first tap on "Take photo", or any tap after a
 * "Deny", killed the app holding an unsaved record. `DwMediaCapture` already did this properly; this
 * is that flow, lifted out so every capture button shares it.
 *
 * And DC-A16: one permission, asked at the moment its action is tapped, instead of location, camera,
 * microphone and media all at once the instant a form opened.
 */
class PermissionGate internal constructor(
    private val context: Context,
    private val request: (String) -> Unit,
    private val park: (Pair<() -> Unit, String>?) -> Unit,
) {
    /** Whether [permission] is held right now. */
    fun holds(permission: String): Boolean = gateHolds(context, permission)

    /**
     * Ask for [permission] with nothing waiting on the answer: the reader taps the control again once
     * it is granted. Used by the record-audio toggles, whose second tap means "stop".
     */
    fun ask(permission: String, refusedSentence: String) = run(permission, refusedSentence) {}

    /** Run [action] now if [permission] is held; otherwise ask, and run it only if granted. */
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

/** [onRefused] receives the sentence passed to [PermissionGate.run] when the answer is no. */
@Composable
fun rememberPermissionGate(onRefused: (String) -> Unit): PermissionGate {
    val context = LocalContext.current
    val refused = rememberUpdatedState(onRefused)
    // A plain holder, not state: the parked action is consumed by the callback and never drawn.
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

/** The sentences a refusal earns, each naming the way forward that does not need the permission. */
const val CAMERA_REFUSED_SENTENCE =
    "The camera permission was refused, so nothing can be captured here. Grant it in Settings, or choose a picture already on this phone instead."
const val MICROPHONE_REFUSED_SENTENCE =
    "The microphone permission was refused, so audio cannot be recorded. Grant it in Settings, or choose a recording already on this phone instead."

const val CAMERA_PERMISSION: String = Manifest.permission.CAMERA
const val MICROPHONE_PERMISSION: String = Manifest.permission.RECORD_AUDIO

/** Location is asked as the pair: Android 12+ ignores a request for the fine permission alone. */
val LOCATION_PERMISSIONS: Array<String> =
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** Whether either location permission is held, so "Use GPS" asks only when it has to. */
fun holdsLocation(context: Context): Boolean =
    LOCATION_PERMISSIONS.any { gateHolds(context, it) }
