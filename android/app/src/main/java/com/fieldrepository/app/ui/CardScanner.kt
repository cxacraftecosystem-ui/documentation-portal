package com.fieldrepository.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fieldrepository.app.data.ArtisanCardCode
import com.fieldrepository.app.data.ArtisanCardResolution
import com.fieldrepository.app.data.FieldRepository
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Scanning an artisan card: ML Kit's on-device barcode reader on a CameraX preview, a picked picture
 * read by the same reader, and the code under the QR typed in. Ported from the designer app's
 * `DwQrLiveScanner` / `MlKitQrFrameReader` without its ZXing fallback, and with the whole frame read
 * (an artisan card holds one QR). Nothing leaves the phone to read the code; resolving it is
 * `FieldRepository.resolveArtisanCard`, which tries the register this phone already holds first.
 */

const val CARD_SCAN_BUTTON = "Scan card"
const val CARD_CODE_PLACEHOLDER = "F-XXXXX-XXXXX-XX"

/** The camera refusal, in the designer app's words (`DwCameraRefusal`), for a card. */
fun cardCameraRefusal(blocked: Boolean): String = if (blocked) {
    "Camera blocked. Turn it on in this app's permission settings, or type the code under the QR."
} else {
    "Camera denied. Tap “$CARD_SCAN_BUTTON” to be asked again, or type the code under the QR."
}

const val CARD_CAMERA_UNAVAILABLE = "Couldn't open the camera. Type the code under the QR instead."
const val CARD_SCAN_AIMING = "Hold the card's QR in view. It reads on its own."
const val CARD_PICTURE_EMPTY = "No QR code in that picture."

private fun Context.hostActivity(): Activity? {
    var cursor: Context? = this
    while (cursor is ContextWrapper) {
        if (cursor is Activity) return cursor
        cursor = cursor.baseContext
    }
    return null
}

private fun cameraBlocked(context: Context): Boolean {
    val activity = context.hostActivity() ?: return true
    return !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
}

private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** ML Kit, narrowed to QR codes so a barcode on the next table is never read as a card. */
private class CardQrReader : AutoCloseable {
    val scanner: BarcodeScanner? = runCatching {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }.getOrNull()

    /** One analysed frame. Always closes [image]. */
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    fun analyse(image: ImageProxy, onText: (String) -> Unit) {
        val client = scanner
        val media = image.image
        if (client == null || media == null) {
            image.close()
            return
        }
        val input = runCatching { InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees) }.getOrNull()
        if (input == null) {
            image.close()
            return
        }
        client.process(input)
            .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }?.let(onText) }
            .addOnCompleteListener { image.close() }
    }

    fun readPicture(context: Context, uri: Uri, onText: (String) -> Unit, onNone: () -> Unit) {
        val client = scanner ?: return onNone()
        val input = runCatching { InputImage.fromFilePath(context, uri) }.getOrNull() ?: return onNone()
        client.process(input)
            .addOnSuccessListener { codes ->
                val text = codes.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }
                if (text != null) onText(text) else onNone()
            }
            .addOnFailureListener { onNone() }
    }

    override fun close() {
        runCatching { scanner?.close() }
    }
}

/**
 * "Scan card": opens [ArtisanCardScanDialog] and hands back what the card came to. Drawn only while
 * cards are on here ([enabled] comes from `FieldRepository.artisanCardsEnabled`).
 */
@Composable
fun ArtisanCardScanButton(
    resolve: suspend (String) -> ArtisanCardResolution,
    onResolved: (ArtisanCardResolution) -> Unit,
    modifier: Modifier = Modifier,
    label: String = CARD_SCAN_BUTTON
) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
    if (open) {
        ArtisanCardScanDialog(
            resolve = resolve,
            onResolved = { result ->
                open = false
                onResolved(result)
            },
            onDismiss = { open = false }
        )
    }
}

/**
 * The scanner: a live preview that reads on its own, "Use a picture", and the typed code. A code
 * the server refuses stays on screen with its sentence, and the same QR is not sent again until a
 * different one is in view.
 */
@Composable
fun ArtisanCardScanDialog(
    resolve: suspend (String) -> ArtisanCardResolution,
    onResolved: (ArtisanCardResolution) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reader = remember { CardQrReader() }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var blocked by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val lastRefused = remember { mutableStateOf<String?>(null) }
    val inFlight = remember { AtomicBoolean(false) }

    fun submit(text: String, fromCamera: Boolean) {
        if (fromCamera && text == lastRefused.value) return
        if (!inFlight.compareAndSet(false, true)) return
        busy = true
        scope.launch {
            val result = try {
                resolve(text)
            } finally {
                inFlight.set(false)
                busy = false
            }
            if (result is ArtisanCardResolution.Refused) {
                lastRefused.value = text
                message = result.message
            } else {
                onResolved(result)
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) {
            blocked = cameraBlocked(context)
            message = cardCameraRefusal(blocked)
        }
    }
    val picture = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            reader.readPicture(context, uri, onText = { submit(it, fromCamera = false) }, onNone = { message = CARD_PICTURE_EMPTY })
        }
    }
    LaunchedEffect(Unit) {
        if (!granted) permission.launch(Manifest.permission.CAMERA)
    }
    DisposableEffect(Unit) { onDispose { reader.close() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Scan an artisan card", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (granted) {
                        CardCameraPreview(
                            reader = reader,
                            onText = { text ->
                                val parsed = ArtisanCardCode.parse(text)
                                if (parsed is ArtisanCardCode.Result.Ok) submit(text, fromCamera = true)
                                else if (text != lastRefused.value) {
                                    lastRefused.value = text
                                    message = ArtisanCardCode.NOT_A_CARD_MESSAGE
                                }
                            },
                            onUnavailable = { message = CARD_CAMERA_UNAVAILABLE }
                        )
                        Box(
                            Modifier
                                .fillMaxWidth(0.62f)
                                .aspectRatio(1f)
                                .border(BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)), RoundedCornerShape(16.dp))
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                            if (blocked) {
                                Button(onClick = { context.openAppSettings() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Camera settings") }
                            } else {
                                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text("Allow camera")
                                }
                            }
                        }
                    }
                }
                Text(
                    text = when {
                        busy -> "Checking…"
                        message != null -> message!!
                        granted -> CARD_SCAN_AIMING
                        else -> ""
                    },
                    color = if (message != null && !busy) MaterialTheme.colorScheme.error else MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                OutlinedButton(onClick = { picture.launch("image/*") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Use a picture")
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.take(64) },
                    label = { Text("Card code") },
                    placeholder = { Text(CARD_CODE_PLACEHOLDER, fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (typed.isNotBlank()) submit(typed, fromCamera = false) }),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { submit(typed, fromCamera = false) },
                    enabled = typed.isNotBlank() && !busy,
                    modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)
                ) { Text("Open") }
            }
        }
    }
}

@Composable
private fun CardCameraPreview(reader: CardQrReader, onText: (String) -> Unit, onUnavailable: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val providerHolder = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    LaunchedEffect(Unit) {
        val provider = runCatching { ProcessCameraProvider.awaitInstance(context) }.getOrNull()
        if (provider == null) {
            onUnavailable()
            return@LaunchedEffect
        }
        providerHolder.value = provider
        val hasBack = runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
        val hasFront = runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
        if (!hasBack && !hasFront) {
            onUnavailable()
            return@LaunchedEffect
        }
        val preview = Preview.Builder().build().apply { setSurfaceProvider { request -> surfaceRequest = request } }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply { setAnalyzer(executor) { image -> reader.analyse(image) { text -> onText(text) } } }
        val bound = runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                if (hasBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )
        }.isSuccess
        if (!bound) onUnavailable()
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { providerHolder.value?.unbindAll() }
            runCatching { executor.shutdown() }
        }
    }
    surfaceRequest?.let { request -> CameraXViewfinder(surfaceRequest = request, modifier = Modifier.fillMaxSize()) }
}

/** Whether cards are on here, asked once per screen (offline, the last answer this phone kept). */
@Composable
fun rememberArtisanCardsEnabled(repository: FieldRepository): Boolean {
    var enabled by remember { mutableStateOf(false) }
    LaunchedEffect(repository) { enabled = repository.artisanCardsEnabled() }
    return enabled
}

/**
 * The picker's scan affordance: "Scan card" plus, while a card scanned with no signal is waiting,
 * its chip. [onPicked] gets the artisan id (or the pending `card:` value) and, when known, the name
 * and place the form fills.
 */
@Composable
fun ArtisanCardPickerScan(
    repository: FieldRepository,
    selected: List<String>,
    onPicked: (id: String, name: String?, place: String?) -> Unit,
    onError: (String) -> Unit
) {
    if (!rememberArtisanCardsEnabled(repository)) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ArtisanCardScanButton(
            resolve = { text -> repository.resolveArtisanCard(text) },
            onResolved = { result ->
                when (result) {
                    is ArtisanCardResolution.Found -> onPicked(result.artisanId, result.name, result.place)
                    is ArtisanCardResolution.Replaced -> onError("This card was replaced. Pick the artisan from the list.")
                    is ArtisanCardResolution.Pending -> onPicked(result.value, null, null)
                    is ArtisanCardResolution.Refused -> onError(result.message)
                }
            }
        )
        selected.filter(ArtisanCardCode::isPending).forEach { pending ->
            Text(ArtisanCardCode.pendingChip(pending), color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
