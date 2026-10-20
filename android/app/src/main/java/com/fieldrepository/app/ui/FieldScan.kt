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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fieldrepository.app.data.ArtisanCardCode
import com.fieldrepository.app.data.ArtisanCardResolution
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.JoinQr
import com.fieldrepository.app.data.OutboxSyncWorker
import com.fieldrepository.app.data.QJoinRequest
import com.fieldrepository.app.data.QuestionnaireCopy
import com.fieldrepository.app.data.QuestionnaireV2
import com.fieldrepository.app.data.ScanAction
import com.fieldrepository.app.data.ScanCardAnswer
import com.fieldrepository.app.data.ScanCardKind
import com.fieldrepository.app.data.ScanCodecs
import com.fieldrepository.app.data.ScanCopy
import com.fieldrepository.app.data.ScanOutcome
import com.fieldrepository.app.data.ScanOutcomeKind
import com.fieldrepository.app.data.ScanPortal
import com.fieldrepository.app.data.ScanRecordAnswer
import com.fieldrepository.app.data.ScanServices
import com.fieldrepository.app.data.ScanTarget
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The field app's camera and picture controls for the top-bar scanner (`ui/TopBarScanSheet.kt`):
 * a live back-camera preview that reads any QR on its own, and "Use a picture". On the device, with
 * ML Kit narrowed to QR codes, exactly as `CardScanner.kt` reads an artisan card; what a code MEANS
 * is `data/ScanDispatch.kt`'s to decide, so every QR read here is handed on as text.
 */

private const val SCAN_CAMERA_UNAVAILABLE = "Couldn't open the camera. Use a picture or type the code."
private const val SCAN_CAMERA_DENIED = "Camera denied. Allow it to scan, or type the code."
private const val SCAN_CAMERA_BLOCKED = "Camera blocked. Turn it on in this app's settings, or type the code."
private const val SCAN_PICTURE_EMPTY = "No QR code in that picture."

private fun Context.scanHostActivity(): Activity? {
    var cursor: Context? = this
    while (cursor is ContextWrapper) {
        if (cursor is Activity) return cursor
        cursor = cursor.baseContext
    }
    return null
}

private fun Context.openScanAppSettings() {
    runCatching {
        startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** ML Kit, narrowed to QR codes. */
private class TopBarQrReader : AutoCloseable {
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
 * The slot [TopBarScanContent] leaves for this app's reader. A code is handed on once; the camera
 * stays open, and the same code is not handed on again until a different one is in view.
 */
@Composable
fun FieldScanControls(enabled: Boolean, onText: (String) -> Unit, onRefusal: (String) -> Unit) {
    val context = LocalContext.current
    val reader = remember { TopBarQrReader() }
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnText by rememberUpdatedState(onText)
    val last = remember { mutableStateOf<String?>(null) }
    val inFlight = remember { AtomicBoolean(false) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var blocked by remember { mutableStateOf(false) }

    fun hand(text: String) {
        if (!latestEnabled || text == last.value || !inFlight.compareAndSet(false, true)) return
        last.value = text
        try {
            latestOnText(text)
        } finally {
            inFlight.set(false)
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) {
            val activity = context.scanHostActivity()
            blocked = activity == null || !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
            onRefusal(if (blocked) SCAN_CAMERA_BLOCKED else SCAN_CAMERA_DENIED)
        }
    }
    val picture = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            last.value = null
            reader.readPicture(context, uri, onText = { hand(it) }, onNone = { onRefusal(SCAN_PICTURE_EMPTY) })
        }
    }
    // Asked only when the person presses "Allow camera": the typed box must work with no prompt.
    DisposableEffect(Unit) { onDispose { reader.close() } }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (granted) {
                TopBarCameraPreview(reader = reader, onText = { hand(it) }, onUnavailable = { onRefusal(SCAN_CAMERA_UNAVAILABLE) })
                Box(
                    Modifier
                        .fillMaxWidth(0.62f)
                        .aspectRatio(1f)
                        .border(BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)), RoundedCornerShape(16.dp)),
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                    if (blocked) {
                        Button(onClick = { context.openScanAppSettings() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Camera settings") }
                    } else {
                        Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Allow camera")
                        }
                    }
                }
            }
        }
        OutlinedButton(
            onClick = { picture.launch("image/*") },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Use a picture")
        }
    }
}

@Composable
private fun TopBarCameraPreview(reader: TopBarQrReader, onText: (String) -> Unit, onUnavailable: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnText by rememberUpdatedState(onText)
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
        val main = ContextCompat.getMainExecutor(context)
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply { setAnalyzer(executor) { image -> reader.analyse(image) { text -> main.execute { latestOnText(text) } } } }
        val bound = runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                if (hasBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis,
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

// ---------------------------------------------------------------------------------------------
// The top-bar dispatcher's field half (REQUIREMENTS_2026-10-20 §6)
// ---------------------------------------------------------------------------------------------

/** How an artisan card reads, by this app's [ArtisanCardCode]. */
fun fieldScanCardKind(raw: String): ScanCardKind = when (val parsed = ArtisanCardCode.parse(raw)) {
    is ArtisanCardCode.Result.Ok -> ScanCardKind.VALID
    is ArtisanCardCode.Result.Failed ->
        if (ArtisanCardCode.failureMessage(raw, parsed.reason) == ArtisanCardCode.CHECK_MESSAGE) ScanCardKind.MISTYPED else ScanCardKind.NONE
}

/** The field app's answers to the dispatcher: the cached register first, then the server. */
fun fieldScanServices(repository: FieldRepository): ScanServices = object : ScanServices {
    override val portal = ScanPortal.FIELD
    override val codecs: ScanCodecs? = null

    override fun cardKind(raw: String): ScanCardKind = fieldScanCardKind(raw)

    override suspend fun resolveCard(raw: String): ScanCardAnswer = when (val answer = repository.resolveArtisanCard(raw)) {
        is ArtisanCardResolution.Found -> ScanCardAnswer.Found(answer.artisanId, answer.name, answer.place)
        is ArtisanCardResolution.Replaced -> ScanCardAnswer.Replaced(answer.artisanId)
        // No signal and no cached row: nothing to open until it resolves.
        is ArtisanCardResolution.Pending -> ScanCardAnswer.Offline
        // An unlinked Designer Repository card is refused by the server in its own words.
        is ArtisanCardResolution.Refused -> ScanCardAnswer.Refused(answer.message)
    }

    /** The Field Repository reads no DPW record codes; the dispatcher refuses them before this. */
    override suspend fun lookUpRecord(recordType: String, id: String): ScanRecordAnswer = ScanRecordAnswer.Missing
}

/**
 * Run the action the person pressed. Returns the next outcome to show, or null once it has handed
 * [onTarget] a place to go.
 *
 * A scanned session QR joins OFFLINE: the session is created on this phone under the same
 * `clientKey`, so when the outbox syncs the server answers with the existing session (`JoinQr`). A
 * typed code needs a connection (`POST /questionnaire/sessions/join`). Both open the session.
 */
suspend fun runFieldScanAction(context: Context, action: ScanAction, meId: String?, onTarget: (ScanTarget) -> Unit): ScanOutcome? =
    when (action) {
        is ScanAction.Open -> {
            onTarget(action.target)
            null
        }
        is ScanAction.Add -> {
            onTarget(action.target)
            null
        }
        is ScanAction.JoinSession -> {
            val v2 = QuestionnaireV2.get(context)
            val qr = action.qr
            val code = action.joinCode
            when {
                qr != null -> {
                    val payload = JoinQr.decode(qr.json)
                    if (payload == null) {
                        ScanOutcome(ScanOutcomeKind.REFUSED, QuestionnaireCopy.s("join.noQr"))
                    } else {
                        if (v2.outbox.unit(payload.clientKey) == null) v2.outbox.startSession(JoinQr.sessionCreate(payload, meId), meId)
                        OutboxSyncWorker.schedule(context)
                        onTarget(ScanTarget.Session(payload.clientKey))
                        null
                    }
                }
                code != null -> try {
                    val session = v2.api.join(QJoinRequest(code))
                    val unit = v2.outbox.adoptSession(session, session.questionnaire?.id.orEmpty(), meId)
                    onTarget(ScanTarget.Session(unit.clientKey))
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: HttpException) {
                    ScanOutcome(ScanOutcomeKind.REFUSED, QuestionnaireCopy.s("join.notFound"))
                } catch (_: Exception) {
                    ScanOutcome(ScanOutcomeKind.OFFLINE, ScanCopy.OFFLINE)
                }
                else -> ScanOutcome(ScanOutcomeKind.REFUSED, ScanCopy.UNKNOWN)
            }
        }
        // Designer Repository acts; the dispatcher refuses their codes here before an action exists.
        is ScanAction.Redeem -> ScanOutcome(ScanOutcomeKind.REFUSED, ScanCopy.JOIN_CARD_ELSEWHERE)
        is ScanAction.RequestAccess -> ScanOutcome(ScanOutcomeKind.REFUSED, ScanCopy.DESIGNER_CODE)
    }
