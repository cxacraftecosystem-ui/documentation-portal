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
import com.fieldrepository.app.questionnaire.data.JoinQr
import com.fieldrepository.app.data.OfflineOutbox
import com.fieldrepository.app.data.cardWaitingToSync
import com.fieldrepository.app.data.OutboxSyncWorker
import com.fieldrepository.app.questionnaire.data.QJoinRequest
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
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
import com.fieldrepository.app.questionnaire.portal.LiveQrScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException

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
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (!granted && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            granted = true
            blocked = false
        }
    }

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

fun fieldScanCardKind(raw: String): ScanCardKind = when (val parsed = ArtisanCardCode.parse(raw)) {
    is ArtisanCardCode.Result.Ok -> ScanCardKind.VALID
    is ArtisanCardCode.Result.Failed ->
        if (ArtisanCardCode.failureMessage(raw, parsed.reason) == ArtisanCardCode.CHECK_MESSAGE) ScanCardKind.MISTYPED else ScanCardKind.NONE
}

private suspend fun queuedPayloads(context: Context): List<String> =
    try {
        OfflineOutbox.all(context).flatMap { entry -> listOf(entry.payloadJson) + entry.steps.map { it.payloadJson } }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

fun fieldScanServices(repository: FieldRepository, context: Context? = null): ScanServices = object : ScanServices {
    override val portal = ScanPortal.FIELD
    override val codecs: ScanCodecs? = null

    override fun cardKind(raw: String): ScanCardKind = fieldScanCardKind(raw)

    override suspend fun resolveCard(raw: String): ScanCardAnswer = when (val answer = repository.resolveArtisanCard(raw)) {
        is ArtisanCardResolution.Found -> ScanCardAnswer.Found(answer.artisanId, answer.name, answer.place)
        is ArtisanCardResolution.Replaced -> ScanCardAnswer.Replaced(answer.artisanId)
        is ArtisanCardResolution.Pending ->
            if (context != null && cardWaitingToSync(answer.card, queuedPayloads(context))) {
                ScanCardAnswer.Refused(ArtisanCardCode.pendingChip(answer.value))
            } else {
                ScanCardAnswer.Offline
            }
        is ArtisanCardResolution.Refused -> ScanCardAnswer.Refused(answer.message)
    }

    override suspend fun lookUpRecord(recordType: String, id: String, code: String): ScanRecordAnswer = ScanRecordAnswer.Missing()
}

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
        is ScanAction.Redeem -> ScanOutcome(ScanOutcomeKind.REFUSED, ScanCopy.JOIN_CARD_ELSEWHERE)
        is ScanAction.RequestAccess -> ScanOutcome(ScanOutcomeKind.REFUSED, ScanCopy.DESIGNER_CODE)
    }
