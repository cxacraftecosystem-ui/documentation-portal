package com.fieldrepository.app.questionnaire.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.FieldTextStyles
import com.fieldrepository.app.ui.field

@Composable
fun ReviewScreen(
    model: ReviewModel,
    locked: Boolean,
    onBack: () -> Unit,
    onNeedsYou: (NeedsYouRow) -> Unit,
    onOpenSection: (String) -> Unit,
    onFinish: () -> Unit
) {
    var all by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("questionnaire.review")) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = QuestionnaireCopy.t("keepGoing"), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            }
            Text(QuestionnaireCopy.t("review"), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            val card = RoundedCornerShape(16.dp)
            Column(
                Modifier.fillMaxWidth().clip(card).background(MaterialTheme.colorScheme.surface).border(1.dp, MaterialTheme.field.hairline, card).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    QuestionnaireCopy.t("questionsAnswered", "answered" to model.tally.answered, "total" to model.tally.total),
                    style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface
                )
                FieldLinearProgressIndicator(
                    progress = { model.tally.fraction }, modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.field.surface200
                )
                Text(model.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.body)
            }
            if (model.needsYou.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Eyebrow(QuestionnaireCopy.t("needsYou"))
                model.needsYou.forEach { row -> NeedsYouLine(row) { onNeedsYou(row) } }
            }
            if (model.stillToAsk.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Eyebrow(QuestionnaireCopy.t("stillToAsk", "count" to model.stillToAsk.size))
                val shown = if (all) model.stillToAsk else model.stillToAsk.take(ReviewModel.FIRST_ROWS)
                shown.forEach { row -> StillToAskLine(row) { onOpenSection(row.section.id) } }
                if (!all && model.stillToAsk.size > ReviewModel.FIRST_ROWS) Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    OutlinedButton(
                        onClick = { all = true }, modifier = Modifier.height(48.dp),
                        border = BorderStroke(1.dp, MaterialTheme.field.surface300)
                    ) { Text(QuestionnaireCopy.t("showAll", "count" to model.stillToAsk.size), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground) }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.field.hairline)
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp).navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack, modifier = Modifier.weight(1f).height(56.dp), border = BorderStroke(1.dp, MaterialTheme.field.surface300)
            ) { Text(QuestionnaireCopy.t("keepGoing"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground) }
            if (!locked) Button(onClick = onFinish, modifier = Modifier.weight(1f).height(56.dp).testTag("questionnaire.review.finish")) {
                Text(QuestionnaireCopy.t("finish"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(text.uppercase(), style = FieldTextStyles.FieldLabel, color = MaterialTheme.field.muted)
}

@Composable
private fun NeedsYouLine(row: NeedsYouRow, onAction: () -> Unit) {
    val decide = row.code != null
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).then(if (decide) Modifier.clickable(onClick = onAction) else Modifier),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                if (decide) Icons.Filled.WarningAmber else Icons.Filled.CloudOff, contentDescription = null,
                tint = if (decide) MaterialTheme.field.warning else MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                Text(row.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (decide) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = row.action, tint = MaterialTheme.field.muted)
            else if (row.action.isNotBlank()) TextButton(onClick = onAction, modifier = Modifier.height(48.dp)) {
                Text(row.action, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
        }
        HorizontalDivider(color = MaterialTheme.field.hairline)
    }
}

@Composable
private fun StillToAskLine(row: StillToAskRow, onOpen: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionLetter(row.section.code, if (row.status.answered == 0) SectionTile.FRESH else SectionTile.STARTED, size = 36.dp, corner = 10.dp)
            Text(
                row.section.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(row.trailing, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.field.muted)
        }
        HorizontalDivider(color = MaterialTheme.field.hairline)
    }
}
