package com.fieldrepository.app.ui.questionnaire

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.JoinQr
import com.fieldrepository.app.data.OutboxSyncWorker
import com.fieldrepository.app.data.QuestionnaireCopy
import com.fieldrepository.app.data.QuestionnaireV2
import com.fieldrepository.app.ui.CAMERA_PERMISSION
import com.fieldrepository.app.ui.CAMERA_REFUSED_SENTENCE
import com.fieldrepository.app.ui.field
import com.fieldrepository.app.ui.rememberPermissionGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Join a session (DESIGN §3.7): scan the QR (works offline, with the system camera or a picked
 * image) or type the 6-character code (online only). A scanned QR creates the session on this phone
 * under the SAME clientKey, so when its outbox syncs the server answers with the existing session.
 */
@Composable
fun JoinScanner(v2: QuestionnaireV2, meId: String?, onJoined: (clientKey: String) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val cameraGate = rememberPermissionGate { onError(it) }

    fun fromBitmap(bitmap: Bitmap?) {
        if (bitmap == null) return
        scope.launch {
            val text = withContext(Dispatchers.Default) {
                val soft = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
                val pixels = IntArray(soft.width * soft.height)
                soft.getPixels(pixels, 0, soft.width, 0, 0, soft.width, soft.height)
                JoinQr.read(pixels, soft.width, soft.height)
            }
            val payload = text?.let(JoinQr::decode)
            if (payload == null) { onError(QuestionnaireCopy.s("join.noQr")); return@launch }
            val existing = v2.outbox.unit(payload.clientKey)
            if (existing == null) v2.outbox.startSession(JoinQr.sessionCreate(payload, meId), meId)
            OutboxSyncWorker.schedule(context)
            onJoined(payload.clientKey)
        }
    }

    fun decode(uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // A full-resolution photo is far more than a QR needs; 1600 px on the long side reads well.
            val long = maxOf(info.size.width, info.size.height)
            if (long > 1600) d.setTargetSampleSize(long / 1600)
        }
        else @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    }.getOrNull()

    // The system camera writes a full-resolution photo into the app's cache (the thumbnail
    // TakePicturePreview returns is too small for a dense code).
    val shot = remember { java.io.File(context.cacheDir, "join-qr.jpg") }
    val shotUri = remember { androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", shot) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) fromBitmap(decode(shotUri)) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> if (uri != null) fromBitmap(decode(uri)) }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(QuestionnaireCopy.s("sessions.join"), style = MaterialTheme.typography.titleLarge)
        PromptButtons(
            listOf(
                PromptButton(QuestionnaireCopy.s("join.scan"), primary = true) { cameraGate.run(CAMERA_PERMISSION, CAMERA_REFUSED_SENTENCE) { photo.launch(shotUri) } },
                PromptButton(QuestionnaireCopy.s("join.pick")) { picker.launch("image/*") }
            )
        )
        OutlinedTextField(
            value = code, onValueChange = { code = it.take(8) }, singleLine = true,
            label = { Text(QuestionnaireCopy.s("join.code")) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            supportingText = { Text(QuestionnaireCopy.s("join.onlineOnly"), color = MaterialTheme.field.muted) },
            modifier = Modifier.fillMaxWidth()
        )
        PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("join.submit"), primary = true) {
            val normalised = JoinQr.normaliseCode(code) ?: run { onError(QuestionnaireCopy.s("join.notFound")); return@PromptButton }
            if (busy) return@PromptButton
            busy = true
            scope.launch {
                runCatching { v2.api.join(com.fieldrepository.app.data.QJoinRequest(normalised)) }
                    .onSuccess { session ->
                        val qid = session.questionnaire?.id.orEmpty()
                        val unit = v2.outbox.adoptSession(session, qid, meId)
                        onJoined(unit.clientKey)
                    }
                    .onFailure { onError(QuestionnaireCopy.s("join.notFound")) }
                busy = false
            }
        }))
    }
}
