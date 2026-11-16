package com.fieldrepository.app.questionnaire.ui

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.data.Naming
import com.fieldrepository.app.questionnaire.data.QSessionCreate
import com.fieldrepository.app.questionnaire.data.QShaMatch
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.SessionUnit
import com.fieldrepository.app.questionnaire.data.StepKind
import com.fieldrepository.app.questionnaire.data.StepState
import com.fieldrepository.app.questionnaire.data.outboxJson
import com.fieldrepository.app.ui.FieldCircularProgressIndicator
import com.fieldrepository.app.ui.FieldTextStyles
import com.fieldrepository.app.ui.SearchableSelectField
import com.fieldrepository.app.ui.SelectOption
import com.fieldrepository.app.ui.field
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PickedFile(
    val path: String,
    val sha256: String,
    val size: Long,
    val mime: String,
    val ext: String,
    val metadataTime: Instant?,
    val durationMs: Long,
    val name: String = File(path).name,
    val lastModified: Instant? = null
) {
    val recordedAt: Instant? get() = metadataTime ?: lastModified
}

enum class AttachKind { SECTION, QUESTION, WHOLE }

sealed interface UploadDuplicate {
    data object InThisList : UploadDuplicate
    data object OnThisPhone : UploadDuplicate
    data class OnServer(
        val on: LocalDate,
        val sessionId: String? = null,
        val recordingId: String? = null,
        val sameSession: Boolean = false,
        val sessionTitle: String? = null
    ) : UploadDuplicate {
        val openable: Boolean get() = if (sameSession) recordingId != null else sessionId != null
    }
}

data class UploadRow(val file: PickedFile, val duplicate: UploadDuplicate? = null) {
    val skipped: Boolean get() = duplicate != null
}

object UploadPlan {
    private val RECORDED: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.UK)

    fun defaultKind(sectionId: String?, lineageId: String?): AttachKind = when {
        lineageId != null -> AttachKind.QUESTION
        sectionId != null -> AttachKind.SECTION
        else -> AttachKind.WHOLE
    }

    fun uploading(rows: List<UploadRow>): List<PickedFile> = rows.filterNot { it.skipped }.map { it.file }

    fun button(count: Int): String = if (count == 1) QuestionnaireCopy.t("uploadOneFile") else QuestionnaireCopy.t("uploadFiles", "count" to count)

    fun recordedText(at: Instant?, sessionDate: LocalDate?): String {
        val instant = at ?: sessionDate?.atTime(12, 0)?.atZone(Naming.IST)?.toInstant() ?: return ""
        return instant.atZone(Naming.IST).format(RECORDED).replace("AM", "am").replace("PM", "pm")
    }

    fun meta(file: PickedFile, sessionDate: LocalDate?): String =
        QuestionnaireCopy.t("uploadFileMeta", "duration" to QuestionnaireCopy.elapsed(file.durationMs), "date" to recordedText(file.recordedAt, sessionDate))

    fun uploadedDay(uploadedAt: String?): LocalDate? =
        uploadedAt?.let { runCatching { Instant.parse(it).atZone(Naming.IST).toLocalDate() }.getOrNull() ?: runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    fun duplicateLine(on: LocalDate): String = QuestionnaireCopy.t("alreadyUploadedOn", "date" to QuestionnaireCopy.day(on))

    fun duplicateLine(duplicate: UploadDuplicate): String = when (duplicate) {
        UploadDuplicate.InThisList -> QuestionnaireCopy.t("alreadyInThisList")
        UploadDuplicate.OnThisPhone -> QuestionnaireCopy.t("uploadingFromThisPhone")
        is UploadDuplicate.OnServer -> duplicateLine(duplicate.on)
    }

    fun whereLine(duplicate: UploadDuplicate): String? = when {
        duplicate !is UploadDuplicate.OnServer -> null
        duplicate.sameSession -> QuestionnaireCopy.t("inThisSession")
        else -> duplicate.sessionTitle?.trim()?.ifBlank { null }?.let { QuestionnaireCopy.t("inOtherSession", "title" to it) }
    }

    fun duplicate(
        sha: String,
        listed: List<UploadRow>,
        units: List<SessionUnit>,
        currentKey: String,
        currentSessionId: String?,
        match: QShaMatch?,
        lookupFailed: Boolean,
        today: LocalDate
    ): UploadDuplicate? {
        if (listed.any { it.file.sha256 == sha }) return UploadDuplicate.InThisList
        val queued = units.flatMap { u -> u.steps.map { u to it } }.filter { (_, step) -> step.kind == StepKind.CLIP && step.clip?.contentSha256 == sha }
        if (queued.any { (_, step) -> step.state != StepState.DONE }) return UploadDuplicate.OnThisPhone
        if (match != null && !match.pending) {
            val sessionId = match.sessionId
            return UploadDuplicate.OnServer(
                uploadedDay(match.uploadedAt) ?: today, sessionId, match.recordingId,
                sameSession = match.sameSession || (sessionId != null && sessionId == currentSessionId),
                sessionTitle = match.sessionTitle?.ifBlank { null }
            )
        }
        if (!lookupFailed) return null
        val (owner, step) = queued.firstOrNull() ?: return null
        val day = owner.steps.firstOrNull { it.kind == StepKind.SESSION }?.bodyJson
            ?.let { runCatching { outboxJson.decodeFromString(QSessionCreate.serializer(), it) }.getOrNull() }
            ?.sessionDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
        return UploadDuplicate.OnServer(
            day ?: today, owner.sessionId, step.resultId,
            sameSession = owner.clientKey == currentKey || (owner.sessionId != null && owner.sessionId == currentSessionId)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadSheet(
    rows: List<UploadRow>,
    checking: Boolean,
    attach: AttachKind,
    sectionCode: String?,
    sectionQuestions: Int,
    questionOptions: List<SelectOption>,
    questionId: String?,
    sessionDate: LocalDate?,
    onAttach: (AttachKind) -> Unit,
    onQuestion: (String) -> Unit,
    onChoose: () -> Unit,
    onRemove: (UploadRow) -> Unit,
    onUpload: () -> Unit,
    onDismiss: () -> Unit,
    onOpenDuplicate: (UploadRow) -> Unit = {}
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        UploadSheetBody(
            rows, checking, attach, sectionCode, sectionQuestions, questionOptions, questionId, sessionDate,
            onAttach, onQuestion, onChoose, onRemove, onUpload, onDismiss, onOpenDuplicate,
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()
        )
    }
}

@Composable
fun UploadSheetBody(
    rows: List<UploadRow>,
    checking: Boolean,
    attach: AttachKind,
    sectionCode: String?,
    sectionQuestions: Int,
    questionOptions: List<SelectOption>,
    questionId: String?,
    sessionDate: LocalDate?,
    onAttach: (AttachKind) -> Unit,
    onQuestion: (String) -> Unit,
    onChoose: () -> Unit,
    onRemove: (UploadRow) -> Unit,
    onUpload: () -> Unit,
    onDismiss: () -> Unit,
    onOpenDuplicate: (UploadRow) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val count = UploadPlan.uploading(rows).size
    val ready = count > 0 && !checking && (attach != AttachKind.QUESTION || questionId != null) && (attach != AttachKind.SECTION || sectionCode != null)
    Column(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp).testTag("questionnaire.upload"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                QuestionnaireCopy.t("uploadRecordings"), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = QuestionnaireCopy.t("cancel"), tint = MaterialTheme.field.body)
            }
        }
        val zone = RoundedCornerShape(16.dp)
        Column(
            Modifier.fillMaxWidth().height(120.dp).clip(zone).background(MaterialTheme.colorScheme.background).border(1.5.dp, MaterialTheme.field.surface300, zone),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
        ) {
            OutlinedButton(
                onClick = onChoose, modifier = Modifier.height(48.dp),
                border = BorderStroke(1.dp, MaterialTheme.field.surface300),
                contentPadding = PaddingValues(horizontal = 20.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.field.surface100, contentColor = MaterialTheme.colorScheme.onSurface)
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(QuestionnaireCopy.t("chooseFiles"), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            }
            Text(QuestionnaireCopy.t("uploadPickHint"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted)
        }
        if (rows.isNotEmpty() || checking) Column(Modifier.fillMaxWidth()) {
            rows.forEach { row -> UploadFileRow(row, sessionDate, onOpen = { onOpenDuplicate(row) }) { onRemove(row) } }
            if (checking) Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                FieldCircularProgressIndicator(Modifier.size(24.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(QuestionnaireCopy.t("attachTo").uppercase(), style = FieldTextStyles.FieldLabel, color = MaterialTheme.field.muted)
            AttachChoice(
                options = listOfNotNull(
                    sectionCode?.let { AttachKind.SECTION to QuestionnaireCopy.t("chipSection", "code" to it) },
                    AttachKind.QUESTION to QuestionnaireCopy.t("attachQuestion"),
                    AttachKind.WHOLE to QuestionnaireCopy.t("wholeSession")
                ),
                selected = attach, onSelect = onAttach
            )
            when (attach) {
                AttachKind.SECTION -> sectionCode?.let {
                    Text(
                        QuestionnaireCopy.t("countsForAllQuestions", "count" to sectionQuestions, "code" to it),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted
                    )
                }
                AttachKind.QUESTION -> SearchableSelectField(
                    label = QuestionnaireCopy.t("attachQuestion"), options = questionOptions,
                    selectedValue = questionId.orEmpty(), includeNone = false, searchable = true, onSelect = onQuestion
                )
                AttachKind.WHOLE -> Unit
            }
        }
        Button(
            onClick = onUpload, enabled = ready,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(56.dp).testTag("questionnaire.upload.submit")
        ) {
            Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(UploadPlan.button(count), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun UploadFileRow(row: UploadRow, sessionDate: LocalDate?, onOpen: () -> Unit, onRemove: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = if (row.skipped) 12.dp else 8.dp).testTag("questionnaire.upload.row.${row.file.sha256}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (row.skipped) MaterialTheme.field.surface200 else MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.AudioFile, contentDescription = null,
                    tint = if (row.skipped) MaterialTheme.field.muted else MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp)
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    row.file.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(UploadPlan.meta(row.file, sessionDate), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted)
            }
            if (row.skipped) TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(QuestionnaireCopy.t("remove"), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            } else IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = QuestionnaireCopy.t("removeNamed", "name" to row.file.name), tint = MaterialTheme.field.body)
            }
        }
        row.duplicate?.let { duplicate ->
            val where = UploadPlan.whereLine(duplicate)
            val openable = (duplicate as? UploadDuplicate.OnServer)?.openable == true
            Row(
                Modifier.padding(start = 52.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.field.warningContainer)
                    .padding(start = 12.dp, end = if (openable) 4.dp else 12.dp, top = 8.dp, bottom = 8.dp)
                    .testTag("questionnaire.upload.duplicate"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.field.onWarningContainer, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(UploadPlan.duplicateLine(duplicate), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.field.onWarningContainer)
                    where?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.onWarningContainer, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                if (openable) TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp).testTag("questionnaire.upload.duplicate.open")) {
                    Text(QuestionnaireCopy.t("open"), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.field.hairline)
    }
}

@Composable
private fun AttachChoice(options: List<Pair<AttachKind, String>>, selected: AttachKind, onSelect: (AttachKind) -> Unit) {
    val track = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(track).background(MaterialTheme.colorScheme.background)
            .border(1.dp, MaterialTheme.field.hairline, track).padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (kind, label) ->
            val on = kind == selected
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp))
                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { if (!on) onSelect(kind) }),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.field.body
                )
            }
        }
    }
}

internal fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

internal fun lastModified(context: Context, uri: Uri): Instant? = runCatching {
    context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 }?.let(Instant::ofEpochMilli) else null
    }
}.getOrNull()

internal fun copyPicked(context: Context, uri: Uri, target: File): PickedFile {
    val mime = context.contentResolver.getType(uri) ?: "audio/mp4"
    val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "m4a"
    val file = File(target.parentFile, "${target.name}.$ext")
    val digest = MessageDigest.getInstance("SHA-256")
    var size = 0L
    context.contentResolver.openInputStream(uri)!!.use { input ->
        FileOutputStream(file).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                out.write(buffer, 0, n)
                size += n
            }
        }
    }
    var metadata: Instant? = null
    var duration = 0L
    runCatching {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        metadata = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let(::parseMetadataDate)
        retriever.release()
    }
    return PickedFile(
        file.absolutePath, digest.digest().joinToString("") { "%02x".format(it) }, size, mime, ext, metadata, duration,
        name = displayName(context, uri) ?: file.name, lastModified = lastModified(context, uri)
    )
}

internal fun parseMetadataDate(raw: String): Instant? = runCatching {
    val d = raw.trim()
    val date = LocalDate.of(d.substring(0, 4).toInt(), d.substring(4, 6).toInt(), d.substring(6, 8).toInt())
    if (date.year < 2000) return null
    val time = if (d.length >= 15) java.time.LocalTime.of(d.substring(9, 11).toInt(), d.substring(11, 13).toInt(), d.substring(13, 15).toInt()) else java.time.LocalTime.NOON
    date.atTime(time).toInstant(ZoneOffset.UTC)
}.getOrNull()
