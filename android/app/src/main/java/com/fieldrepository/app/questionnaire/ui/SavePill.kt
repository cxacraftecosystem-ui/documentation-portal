package com.fieldrepository.app.questionnaire.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.ui.field

@Composable
fun SavePill(state: SavePillState, onOpen: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val dot: Color = when (state.tone) {
        SaveTone.SAVED, SaveTone.ON_PHONE -> MaterialTheme.field.success
        SaveTone.UPLOADING -> MaterialTheme.colorScheme.primary
        SaveTone.WAITING -> MaterialTheme.field.warning
        SaveTone.FAILED, SaveTone.CONFLICT -> MaterialTheme.colorScheme.error
    }
    val label = state.text + ". " + QuestionnaireCopy.t("openUploads")
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.field.surface100)
                .clickable(onClick = onOpen)
                .semantics(mergeDescendants = true) { contentDescription = label }
                .padding(start = 14.dp, end = 12.dp)
                .testTag("questionnaire.savePill"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Text(state.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.body)
            if (state.tone == SaveTone.UPLOADING) {
                Box(Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.field.surface300)) {
                    Box(Modifier.fillMaxWidth(state.percent.coerceIn(0, 100) / 100f).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.field.muted, modifier = Modifier.size(20.dp))
        }
        if (state.tone == SaveTone.FAILED) TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(QuestionnaireCopy.t("retry"), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
    }
}
