package com.fieldrepository.app.questionnaire.ui

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
import com.fieldrepository.app.questionnaire.data.JoinQr
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import com.fieldrepository.app.questionnaire.portal.QuestionnairePortal
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Join a session (DESIGN §3.7): scan the QR (works offline, live in the camera preview or from a
 * picked image) or type the 6-character code (online only). A scanned QR creates the session on this phone
 * under the SAME clientKey, so when its outbox syncs the server answers with the existing session.
 */
@Composable
fun JoinScanner(v2: QuestionnaireV2, meId: String?, onJoined: (clientKey: String) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    /** A read QR joins offline: the session is created here under the same clientKey. */
    suspend fun joinFrom(text: String?) {
        val payload = text?.let(JoinQr::decode)
        if (payload == null) { onError(QuestionnaireCopy.s("join.noQr")); return }
        val existing = v2.outbox.unit(payload.clientKey)
        if (existing == null) v2.outbox.startSession(JoinQr.sessionCreate(payload, meId), meId)
        v2.requestSync()
        onJoined(payload.clientKey)
    }

    fun fromBitmap(bitmap: Bitmap?) {
        if (bitmap == null) return
        scope.launch {
            val text = withContext(Dispatchers.Default) {
                val soft = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
                val pixels = IntArray(soft.width * soft.height)
                soft.getPixels(pixels, 0, soft.width, 0, 0, soft.width, soft.height)
                JoinQr.read(pixels, soft.width, soft.height)
            }
            joinFrom(text)
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

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> if (uri != null) fromBitmap(decode(uri)) }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(QuestionnaireCopy.s("sessions.join"), style = MaterialTheme.typography.titleLarge)
        // The portal's live reader (it asks for the camera itself); a picked picture reads the same code.
        QuestionnairePortal.LiveQrScan(
            label = QuestionnaireCopy.s("join.scan"),
            pickLabel = QuestionnaireCopy.s("join.pick"),
            onText = { text -> scope.launch { joinFrom(text) } },
            onRefusal = onError,
            alongside = { androidx.compose.material3.OutlinedButton(onClick = { picker.launch("image/*") }) { Text(QuestionnaireCopy.s("join.pick")) } }
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
                runCatching { v2.api.join(com.fieldrepository.app.questionnaire.data.QJoinRequest(normalised)) }
                    .onSuccess { session ->
                        val qid = session.questionnaire?.id.orEmpty()
                        val unit = v2.outbox.adoptSession(session, qid, meId)
                        onJoined(unit.clientKey)
                    }
                    // Only the server's own refusal means no such session; no signal or a server fault
                    // does not, and the QR still joins offline.
                    .onFailure { e -> onError(QuestionnaireCopy.s(joinFailureKey(e))) }
                busy = false
            }
        }))
    }
}

/** A typed code the server refused (4xx other than 408/429): the code is wrong, not the signal. */
/**
 * What a failed join says: too many wrong codes (429 RATE_LIMITED, ten in ten minutes) asks the
 * researcher to wait; any other refusal is an unknown code; anything else needs a signal.
 */
internal fun joinFailureKey(failure: Throwable): String = when {
    (failure as? retrofit2.HttpException)?.code() == 429 -> "join.rateLimited"
    joinCodeUnknown(failure) -> "join.notFound"
    else -> "join.onlineOnly"
}

internal fun joinCodeUnknown(failure: Throwable): Boolean =
    (failure as? retrofit2.HttpException)?.code()?.let { it in 400..499 && it != 408 && it != 429 } == true
