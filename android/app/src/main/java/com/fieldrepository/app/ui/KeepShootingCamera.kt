package com.fieldrepository.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * KEEP SHOOTING (A10, 2026-10-17): the camera opens again after every photo until the researcher
 * backs out of it.
 *
 * Before this, every angle of an object was Take photo → shutter → tick → back in the form → Take
 * photo again: three taps and a camera cold start per picture, thirty to forty taps for the ten
 * angles a product record wants. Now it is one tap to start, the shutter for each angle, and Back
 * when done. Each photo still lands in the batch the moment it is taken, so it starts its eager
 * upload straight away exactly as a single shot always did; nothing about uploading changes.
 *
 * The behaviour is ONE device-wide setting ([KeepShootingSetting]), on by default, with the switch
 * drawn beside each camera button ([KeepShootingToggle]) so a researcher who wants one picture at a
 * time can say so where they are, and every camera in the app then agrees with them.
 */
class KeepShootingCamera internal constructor(private val start: () -> Unit) {
    /** Open the camera. Call it after the camera permission is held (see [PermissionGate.run]). */
    fun shoot() = start()
}

/** What the camera does after one shot comes back. Pure, so a JVM test can pin it. */
internal sealed interface KeepShootingStep {
    /** Open the camera again for the next photo. */
    data object Again : KeepShootingStep

    /** Stop. [taken] photos were added in this run (0 when the first one was cancelled). */
    data class Done(val taken: Int) : KeepShootingStep
}

/**
 * [success] is the camera's own answer for the shot just finished; [keepShooting] the setting;
 * [takenSoFar] the photos already added in this run, NOT counting this one.
 *
 * A cancelled shot always ends the run — that is how a researcher says "enough", and treating it
 * as anything else would trap them in a camera that keeps coming back.
 */
internal fun keepShootingStep(success: Boolean, keepShooting: Boolean, takenSoFar: Int): KeepShootingStep = when {
    !success -> KeepShootingStep.Done(takenSoFar)
    keepShooting -> KeepShootingStep.Again
    else -> KeepShootingStep.Done(takenSoFar + 1)
}

/** The one line said when a run of more than one photo ends. Null when there is nothing to say. */
internal fun keepShootingSentence(taken: Int): String? = if (taken > 1) "$taken photos added." else null

/** The device-wide setting, kept beside the other per-device conveniences in plain preferences. */
internal object KeepShootingSetting {
    private const val PREFS = "field_capture"
    private const val KEY = "keep_shooting"

    fun read(context: Context): Boolean =
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true) }.getOrDefault(true)

    fun write(context: Context, on: Boolean) {
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply() }
    }
}

/**
 * A photo camera that keeps shooting. [newUri] makes the file each shot is written to (the form's own
 * `createAppFileUri`, so drafts keep pointing at app storage); [onShot] receives each saved photo as
 * it is taken; [onRunEnded] gets the sentence for a run of several, to show however the screen shows
 * messages.
 */
@Composable
fun rememberKeepShootingCamera(
    newUri: () -> Uri,
    onShot: (Uri) -> Unit,
    onRunEnded: (String) -> Unit = {},
): KeepShootingCamera {
    val context = LocalContext.current
    val shot by rememberUpdatedState(onShot)
    val ended by rememberUpdatedState(onRunEnded)
    val makeUri by rememberUpdatedState(newUri)
    // Saveable: the camera app is the usual reason Android reclaims this process, and the file the
    // shot was written to must still be known when the answer comes back.
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var taken by rememberSaveable { mutableIntStateOf(0) }
    val self = remember { arrayOfNulls<ManagedActivityResultLauncher<Uri, Boolean>>(1) }

    fun open() {
        val launcher = self[0] ?: return
        val uri = makeUri()
        pending = uri.toString()
        runCatching { launcher.launch(uri) }.onFailure { pending = null }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pending?.let(Uri::parse)
        pending = null
        val ok = success && uri != null
        if (ok) shot(uri!!)
        when (val step = keepShootingStep(ok, KeepShootingSetting.read(context), taken)) {
            KeepShootingStep.Again -> {
                taken += 1
                open()
            }
            is KeepShootingStep.Done -> {
                taken = 0
                keepShootingSentence(step.taken)?.let(ended)
            }
        }
    }
    self[0] = launcher
    return remember(launcher) { KeepShootingCamera { taken = 0; open() } }
}

/** The switch for [KeepShootingSetting], drawn under a camera button. */
@Composable
fun KeepShootingToggle(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(KeepShootingSetting.read(context)) }
    AppToggle(
        label = "Keep the camera open",
        description = "Opens again after each photo. Press Back to stop.",
        checked = on,
        onCheckedChange = { next ->
            on = next
            KeepShootingSetting.write(context, next)
        },
        modifier = modifier,
    )
}
