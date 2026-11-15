package com.fieldrepository.app.questionnaire.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.ui.field

data class OverflowItem(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
fun QuestionOverflow(code: String, items: List<OverflowItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp).testTag("questionnaire.overflow.$code")) {
            Icon(Icons.Filled.MoreVert, contentDescription = QuestionnaireCopy.t("moreFor", "code" to code), tint = MaterialTheme.field.body)
        }
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.field.surface200
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) },
                    leadingIcon = { Icon(item.icon, contentDescription = null, tint = MaterialTheme.field.body) },
                    onClick = { open = false; item.onClick() },
                    modifier = Modifier.size(width = 232.dp, height = 52.dp)
                )
            }
        }
    }
}
