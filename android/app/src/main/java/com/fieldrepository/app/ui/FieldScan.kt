package com.fieldrepository.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.fieldrepository.app.ui.questionnaire.LiveQrScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * The field app's camera and picture controls for the top-bar scanner (`ui/TopBarScanSheet.kt`).
 *
 * NO SECOND CAMERA READER. The live preview is `ui/questionnaire/LiveQrScanner.kt`, the one the
 * session join already uses (REQUIREMENTS_2026-10-20 §6.2), asked to hand on any QR rather than only
 * a join QR; a picked photo is read by the same `JoinQr.read` the join screen's picker uses. What a
 * code MEANS is `data/ScanDispatch.kt`'s to decide, so every QR read here is handed on as text.
 */

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

/** A picked photo, decoded in software and at most 1600 px on its long side (as `JoinScanner` reads one). */
private fun Context.scanBitmap(uri: Uri): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val long = maxOf(info.size.width, info.size.height)
            if (long > 1600) decoder.setTargetSampleSize(long / 1600)
        }
    } else {
        @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(contentResolver, uri)
    }
}.getOrNull()

private fun readScanBitmap(bitmap: Bitmap): String? {
    val soft = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    val pixels = IntArray(soft.width * soft.height)
    soft.getPixels(pixels, 0, soft.width, 0, 0, soft.width, soft.height)
    return JoinQr.read(pixels, soft.width, soft.height)?.takeIf { it.isNotBlank() }
}

/**
 * The slot [TopBarScanContent] leaves for this app's reader. A code is handed on once; the camera
 * stays open, and the same code is not handed on again until a different one is in view.
 */
@Composable
fun FieldScanControls(enabled: Boolean, onText: (String) -> Unit, onRefusal: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnText by rememberUpdatedState(onText)
    val last = remember { mutableStateOf<String?>(null) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var blocked by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }

    fun hand(text: String) {
        if (!latestEnabled || text == last.value) return
        last.value = text
        latestOnText(text)
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
            reading = true
            scope.launch {
                val text = withContext(Dispatchers.Default) { context.scanBitmap(uri)?.let(::readScanBitmap) }
                reading = false
                if (text != null) hand(text) else onRefusal(SCAN_PICTURE_EMPTY)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Asked only when the person presses "Allow camera": the typed box must work with no prompt.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (granted) {
                LiveQrScanner(onRead = { hand(it) }, accept = { true }, once = false)
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
                            Text(ScanCopy.USE_CAMERA)
                        }
                    }
                }
            }
        }
        OutlinedButton(
            onClick = { picture.launch("image/*") },
            enabled = enabled && !reading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(ScanCopy.CHOOSE_PHOTO)
        }
    }
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
