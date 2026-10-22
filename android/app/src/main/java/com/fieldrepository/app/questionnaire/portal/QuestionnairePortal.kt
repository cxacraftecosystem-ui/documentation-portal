package com.fieldrepository.app.questionnaire.portal

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.BuildConfig
import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.ArtisanCreateRequest
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.OfflineOutbox
import com.fieldrepository.app.data.OutboxSyncWorker
import com.fieldrepository.app.data.TokenStore
import com.fieldrepository.app.data.replayableBy
import com.fieldrepository.app.forms.FormsV2
import com.fieldrepository.app.questionnaire.data.QWorkshopOption
import com.fieldrepository.app.questionnaire.ui.NewArtisan
import com.fieldrepository.app.ui.ActionRow
import com.fieldrepository.app.ui.CAMERA_PERMISSION
import com.fieldrepository.app.ui.CAMERA_REFUSED_SENTENCE
import com.fieldrepository.app.ui.mostRecentRecordableWorkshop
import com.fieldrepository.app.ui.rememberPermissionGate
import kotlinx.serialization.json.Json
import retrofit2.Retrofit

/**
 * FIELD REPOSITORY'S HALF OF THE SHARED QUESTIONNAIRE TREES. NOT SHARED.
 *
 * `questionnaire/data`, `questionnaire/ui` and `recording` are the same Kotlin as designer-portal's
 * once the package prefix is normalised (`scripts/questionnaire_android.py` in designer-portal, UNIFY
 * U10). Everything that differs between the portals is reached through this object, which declares
 * the same members as designer-portal's over this app's code: its HTTP stack (`ApiClient`, with the
 * read cache, the gateway retry and the clock headers), WorkManager for a background pass, sessions
 * filed under a WORKSHOP, this app's artisan create, its legacy outbox, its forms written offline
 * (`forms/FormAuthoringOutbox`), its rich prose box and its CameraX QR reader.
 */
object QuestionnairePortal {
    const val NAME = "documentation-portal"

    /** Field Repository files a session under a workshop (`workshopId`), never a design workshop. */
    const val FILES_UNDER_DESIGN_WORKSHOP = false

    val json: Json get() = ApiClient.json

    val apiBaseUrl: String get() = BuildConfig.DEFAULT_API_BASE_URL

    fun retrofit(context: Context): Retrofit =
        ApiClient.retrofit(TokenStore(context.applicationContext), OutboxSyncWorker.readCacheDir(context.applicationContext))

    fun token(context: Context): String? = TokenStore(context.applicationContext).getToken()

    fun userId(context: Context): String? = TokenStore(context.applicationContext).getUser()?.id

    fun userName(context: Context): String? = TokenStore(context.applicationContext).getUser()?.name

    /** Send what is queued as soon as there is a network, app open or not (WorkManager). */
    fun requestSync(context: Context) = OutboxSyncWorker.schedule(context.applicationContext)

    /** The artisan create an offline registration replays before its session (a frozen body). */
    fun artisanCreateJson(artisan: NewArtisan, workshopId: String?): String = json.encodeToString(
        ArtisanCreateRequest.serializer(),
        ArtisanCreateRequest(
            name = artisan.name.trim(), place = artisan.place.trim(),
            aadhaarNumber = artisan.aadhaarDigits.takeUnless { artisan.aadhaarNotShared }?.ifBlank { null },
            aadhaarNotShared = if (artisan.aadhaarNotShared) true else null,
            dos = artisan.dos.trim(), donts = artisan.donts.trim(), workshopId = workshopId?.ifBlank { null }
        )
    )

    /** Creates the artisan an offline registration queued; returns the server's id. */
    suspend fun createArtisan(context: Context, idempotencyKey: String, bodyJson: String): String =
        ApiClient.create(TokenStore(context.applicationContext))
            .createArtisan(idempotencyKey, json.decodeFromString(ArtisanCreateRequest.serializer(), bodyJson)).id

    private suspend fun workshopRows(context: Context) =
        FieldRepository(ApiClient.create(TokenStore(context.applicationContext)), TokenStore(context.applicationContext)).workshops()

    /** The workshops this account may file a session under (B30). */
    suspend fun workshops(context: Context): List<QWorkshopOption> = workshopRows(context).map { w ->
        QWorkshopOption(
            id = w.id,
            title = w.title.ifBlank { w.id },
            place = w.place.ifBlank { null },
            district = w.place.ifBlank { null },
            craft = w.crafts.singleOrNull()?.craft?.name,
            updatedAt = w.startDate ?: w.date ?: w.createdAt
        )
    }

    /** The most recent workshop this account may record in: the start sheet's default (B30). */
    suspend fun defaultWorkshopId(context: Context): String? = mostRecentRecordableWorkshop(workshopRows(context))?.id

    /** Old-format questionnaire interviews still waiting in this app's outbox; the v2 queue waits for them. */
    suspend fun legacyQuestionnaireWaiting(context: Context): Int = runCatching {
        legacyQuestionnaireEntries(OfflineOutbox.all(context).replayableBy(userId(context)))
    }.getOrDefault(0)

    /** Legacy entries that are questionnaire interviews and still replayable. */
    fun legacyQuestionnaireEntries(entries: List<com.fieldrepository.app.data.PendingEntry>): Int =
        entries.count { it.failure == null && !it.needsReview && (it.type == "questionnaire" || it.type == "questionnaireinterview") }

    /** A form written on this phone has a local id until it lands (A15): null while it waits. */
    suspend fun resolveFormId(context: Context, id: String): String? = FormsV2.get(context).outbox.resolveFormId(id)

    /**
     * The answer box (C8): this app's rich prose field with its formatting toolbar. A formatted answer
     * is held as this app's rich document; plain prose stays prose.
     */
    @Composable
    fun AnswerField(label: String?, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
        com.fieldrepository.app.ui.RecordProseField(label = label, value = value, onValueChange = onValueChange, modifier = modifier, minLines = 2, rich = true)
    }

    /** What the answer box holds, as an answer: its plain words, and its formatting when it has any. */
    data class AnswerParts(val text: String, val richText: String?)

    /** C8: plain `text` always; `richText` (the rich-text document) only when it is formatted. */
    fun answerParts(stored: String): AnswerParts =
        if (com.fieldrepository.app.ui.looksLikeRichDocument(stored)) {
            AnswerParts(com.fieldrepository.app.ui.richtext.toPlain(com.fieldrepository.app.ui.recordDocFromStored(stored)), stored)
        } else AnswerParts(stored, null)

    /** The live QR reader of the join screen: one tap asks for the camera and opens it; a read code comes back once. */
    @Composable
    fun LiveQrScan(
        label: String,
        pickLabel: String?,
        onText: (String) -> Unit,
        onRefusal: (String) -> Unit,
        modifier: Modifier = Modifier,
        alongside: (@Composable () -> Unit)? = null
    ) {
        var scanning by remember { mutableStateOf(false) }
        val gate = rememberPermissionGate { onRefusal(it) }
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (scanning) LiveQrScanner(onRead = { text -> scanning = false; onText(text) })
            ActionRow(Modifier.fillMaxWidth()) {
                androidx.compose.material3.Button(onClick = {
                    if (scanning) scanning = false else gate.run(CAMERA_PERMISSION, CAMERA_REFUSED_SENTENCE) { scanning = true }
                }) { Text(if (scanning) "Cancel" else label) }
                alongside?.invoke()
            }
        }
    }
}
