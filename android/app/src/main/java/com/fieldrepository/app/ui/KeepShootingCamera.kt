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

class KeepShootingCamera internal constructor(private val start: () -> Unit) {
    fun shoot() = start()
}

internal sealed interface KeepShootingStep {
    data object Again : KeepShootingStep

    data class Done(val taken: Int) : KeepShootingStep
}

internal fun keepShootingStep(success: Boolean, keepShooting: Boolean, takenSoFar: Int): KeepShootingStep = when {
    !success -> KeepShootingStep.Done(takenSoFar)
    keepShooting -> KeepShootingStep.Again
    else -> KeepShootingStep.Done(takenSoFar + 1)
}

internal fun keepShootingSentence(taken: Int): String? = if (taken > 1) "$taken photos added." else null

internal object KeepShootingSetting {
    private const val PREFS = "field_capture"
    private const val KEY = "keep_shooting"

    fun read(context: Context): Boolean =
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true) }.getOrDefault(true)

    fun write(context: Context, on: Boolean) {
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply() }
    }
}

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
