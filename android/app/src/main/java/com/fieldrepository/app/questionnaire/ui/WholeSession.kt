package com.fieldrepository.app.questionnaire.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fieldrepository.app.explorer.ui.ExplorerMediaLoader
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.ui.field

@Composable
fun WholeSessionBlock(
    takes: List<InPlaceTake>,
    open: String?,
    showPair: Boolean,
    onOpen: (String?) -> Unit,
    onRecord: () -> Unit,
    onUpload: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: ((String) -> Unit)? = null,
    reveal: String? = null,
    clearance: RevealClearance = RevealClearance(),
    loader: ExplorerMediaLoader? = null,
    onMark: ((InPlaceTake) -> Unit)? = null,
    markable: (InPlaceTake) -> Boolean = { !it.local }
) {
    val shape = RoundedCornerShape(20.dp)
    val name = QuestionnaireCopy.t("wholeSession")
    Column(
        modifier.fillMaxWidth().padding(top = 16.dp).clip(shape).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.field.surface300, shape).testTag("questionnaire.whole")
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).background(MaterialTheme.field.surface100).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                name, modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface
            )
            if (takes.isNotEmpty()) Text(
                QuestionnaireCopy.t("takeCount", "count" to takes.size), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.field.muted
            )
        }
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = if (takes.isEmpty()) 14.dp else 4.dp)) {
            if (showPair) RecordUploadPair(
                recordLabel = QuestionnaireCopy.s("workspace.record"), uploadLabel = QuestionnaireCopy.t("upload"),
                onRecord = onRecord, onUpload = onUpload, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp).testTag("questionnaire.whole.pair")
            )
            TakesList(
                heading = null, takes = takes, scope = InPlaceTakes.WHOLE, open = open, name = name, onOpen = onOpen,
                loader = loader, onRetry = onRetry, reveal = reveal, clearance = clearance,
                onMark = onMark, markable = markable
            )
        }
    }
}

@Composable
fun ChangeSectionsChooser(
    sections: List<SectionChoice>,
    chosen: Set<String>,
    onChange: (Set<String>) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().padding(24.dp).testTag("questionnaire.mark"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            QuestionnaireCopy.t("changeSections"), modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface
        )
        SectionsField(sections, chosen, onChange = onChange)
        PromptButtons(listOf(
            PromptButton(QuestionnaireCopy.t("cancel"), onClick = onCancel),
            PromptButton(QuestionnaireCopy.s("save"), primary = true, onClick = onSave)
        ))
    }
}

@Composable
fun ChangeSectionsDialog(sections: List<SectionChoice>, initial: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    var chosen by remember(initial) { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(28.dp),
            modifier = Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth()
        ) {
            ChangeSectionsChooser(sections, chosen, onChange = { chosen = it }, onCancel = onDismiss, onSave = { onSave(chosen) })
        }
    }
}
