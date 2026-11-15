package com.fieldrepository.app.questionnaire.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.questionnaire.data.JoinQr
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.RecordBy
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.FieldTextStyles
import com.fieldrepository.app.ui.SelectOption
import com.fieldrepository.app.ui.field

data class HeaderParticipant(val artisanId: String, val name: String, val present: Boolean, val guest: Boolean = false)

@Composable
fun SessionTopBar(
    title: String,
    subline: String,
    showReview: Boolean,
    onBack: () -> Unit,
    onReview: () -> Unit,
    onMenu: () -> Unit,
    menu: @Composable () -> Unit = {}
) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = QuestionnaireCopy.t("backToSessions"), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title, style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (subline.isNotBlank()) Text(
                    subline, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.field.muted, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (showReview) TextButton(onClick = onReview, modifier = Modifier.height(48.dp)) {
                Text(QuestionnaireCopy.t("review"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
            Box {
                IconButton(onClick = onMenu, modifier = Modifier.size(48.dp).testTag("questionnaire.session.menu")) {
                    Icon(Icons.Filled.MoreVert, contentDescription = QuestionnaireCopy.t("sessionOptions"), tint = MaterialTheme.field.body)
                }
                menu()
            }
        }
        HorizontalDivider(color = MaterialTheme.field.hairline)
    }
}

@Composable
fun SessionProgressBlock(tally: SessionTally, pill: SavePillState, onOpenUploads: () -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    QuestionnaireCopy.t("questionsOfTotal", "answered" to tally.answered, "total" to tally.total), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    QuestionnaireCopy.t("sectionsOfTotal", "done" to tally.sectionsDone, "total" to tally.sectionsTotal),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted
                )
            }
            FieldLinearProgressIndicator(
                progress = { tally.fraction }, modifier = Modifier.fillMaxWidth().height(6.dp).testTag("questionnaire.progress"),
                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.field.surface200
            )
        }
        SavePill(pill, onOpen = onOpenUploads, onRetry = onRetry)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeopleRow(
    participants: List<HeaderParticipant>,
    locked: Boolean,
    onTogglePresent: (artisanId: String, present: Boolean) -> Unit,
    onOpenGuest: (subjectId: String) -> Unit,
    onAddArtisan: () -> Unit,
    onAddGuest: () -> Unit
) {
    var adding by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            QuestionnaireCopy.t("people"), modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground
        )
        if (!locked) Box {
            TextButton(onClick = { adding = true }) {
                Text(QuestionnaireCopy.t("addPeople"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                DropdownMenuItem(
                    text = { Text(QuestionnaireCopy.s("workspace.addArtisan"), color = MaterialTheme.colorScheme.onSurface) },
                    onClick = { adding = false; onAddArtisan() }
                )
                DropdownMenuItem(
                    text = { Text(QuestionnaireCopy.s("guest.add"), color = MaterialTheme.colorScheme.onSurface) },
                    onClick = { adding = false; onAddGuest() }
                )
            }
        }
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        participants.forEach { p ->
            if (p.guest) FilterChip(
                selected = true, onClick = { onOpenGuest(p.artisanId) },
                label = { Text(QuestionnaireCopy.firstName(p.name).ifBlank { QuestionnaireCopy.s("guest.label") } + " · " + QuestionnaireCopy.s("guest.label")) }
            ) else FilterChip(
                selected = p.present, enabled = !locked, onClick = { onTogglePresent(p.artisanId, !p.present) },
                label = { Text(QuestionnaireCopy.firstName(p.name) + if (p.present) "" else " · ${QuestionnaireCopy.s("workspace.away")}") }
            )
        }
    }
}

@Composable
fun FinishedBanner(date: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.field.surface100)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.field.body, modifier = Modifier.size(18.dp))
        Text(QuestionnaireCopy.t("finishedReadOnly", "date" to date), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.body)
    }
}

data class SessionMenuItem(val label: String, val icon: ImageVector, val destructive: Boolean = false, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionMenuSheet(
    recordBy: String,
    recordByEnabled: Boolean,
    onRecordBy: (String) -> Unit,
    items: List<SessionMenuItem>,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp).navigationBarsPadding().testTag("questionnaire.session.options"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(QuestionnaireCopy.t("fieldRecordBy").uppercase(), style = FieldTextStyles.FieldLabel, color = MaterialTheme.field.muted)
                SegmentedChoice(
                    label = "",
                    options = listOf(
                        SelectOption(RecordBy.SECTION.wire, QuestionnaireCopy.t("recordBySection")),
                        SelectOption(RecordBy.QUESTION.wire, QuestionnaireCopy.t("recordByQuestion"))
                    ),
                    selectedValue = recordBy, enabled = recordByEnabled, onSelect = onRecordBy
                )
                Text(QuestionnaireCopy.t("recordByHelper"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted)
            }
            val (danger, plain) = items.partition { it.destructive }
            if (plain.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.field.hairline)
                Column { plain.forEach { MenuRow(it, onDismiss) } }
            }
            if (danger.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.field.hairline)
                Column { danger.forEach { MenuRow(it, onDismiss) } }
            }
        }
    }
}

@Composable
private fun MenuRow(item: SessionMenuItem, onDismiss: () -> Unit) {
    val color = if (item.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    TextButton(
        onClick = { onDismiss(); item.onClick() },
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(item.icon, contentDescription = null, tint = if (item.destructive) color else MaterialTheme.field.body)
            Text(item.label, style = MaterialTheme.typography.bodyLarge, color = color, fontWeight = FontWeight.Normal)
        }
    }
}

object SessionMenuIcons {
    val edit: ImageVector get() = Icons.Filled.Edit
    val researcher: ImageVector get() = Icons.Filled.PersonAdd
    val join: ImageVector get() = Icons.Filled.QrCode2
    val read: ImageVector get() = Icons.Filled.Visibility
    val continueDay: ImageVector get() = Icons.Filled.EventRepeat
    val removeArtisan: ImageVector get() = Icons.Filled.PersonRemove
    val merge: ImageVector get() = Icons.AutoMirrored.Filled.CallMerge
    val delete: ImageVector get() = Icons.Filled.DeleteOutline
}

@Composable
fun JoinQrDialog(joinQr: String, joinCode: String?, onDismiss: () -> Unit) {
    val bitmap = remember(joinQr) { qrBitmap(joinQr) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(QuestionnaireCopy.t("joinCodeAndQr"), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(bitmap.asImageBitmap(), contentDescription = QuestionnaireCopy.s("join.scan"), modifier = Modifier.size(240.dp).background(Color.White))
                joinCode?.let { Text(it, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { contentDescription = QuestionnaireCopy.joinCode(it) }) }
            }
        },
        confirmButton = { PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("p5.ok"), primary = true, onClick = onDismiss))) }
    )
}

internal fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val matrix = JoinQr.matrix(text, size)
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix.get(i % matrix.width, i / matrix.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}
