package com.fieldrepository.app.questionnaire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RecordUploadPair(
    recordLabel: String,
    uploadLabel: String,
    onRecord: () -> Unit,
    onUpload: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    showUpload: Boolean = true,
    trailing: @Composable () -> Unit = {}
) {
    val text = if (height >= 52.dp) 16.sp else 15.sp
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (height >= 52.dp) 12.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onRecord,
            modifier = Modifier.weight(1f).height(height).testTag("questionnaire.pair.record"),
            contentPadding = PaddingValues(horizontal = 12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(recordLabel, fontSize = text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (showUpload) FilledTonalButton(
            onClick = onUpload,
            modifier = Modifier.weight(1f).height(height).testTag("questionnaire.pair.upload"),
            contentPadding = PaddingValues(horizontal = 12.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        ) {
            Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(uploadLabel, fontSize = text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}
