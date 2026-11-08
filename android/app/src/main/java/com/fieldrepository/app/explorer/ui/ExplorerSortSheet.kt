package com.fieldrepository.app.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.explorer.data.ExplorerSort
import com.fieldrepository.app.explorer.data.ExplorerSorts
import com.fieldrepository.app.ui.field

@Composable
fun ExplorerSortChip(sort: ExplorerSort, onClick: () -> Unit, modifier: Modifier = Modifier, tag: String = "explorer.sort") {
    val spoken = ExplorerSorts.spoken(sort)
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .clip(ExplorerShapes.pill)
            .clickable(role = Role.Button, onClickLabel = ExplorerSorts.TITLE, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                role = Role.Button
            }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 36.dp)
                .widthIn(max = 260.dp)
                .background(MaterialTheme.colorScheme.surface, ExplorerShapes.pill)
                .border(1.dp, explorerControlLine(), ExplorerShapes.pill)
                .padding(start = 10.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.SwapVert, contentDescription = null, tint = MaterialTheme.field.body, modifier = Modifier.size(18.dp))
            Text(
                ExplorerSorts.short(sort),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("$tag.label"),
            )
        }
    }
}

@Composable
fun ExplorerSortLine(sort: ExplorerSort, keys: List<String>, onClick: () -> Unit, modifier: Modifier = Modifier, tag: String = "explorer.sort") {
    if (ExplorerSorts.offered(keys).isEmpty()) return
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ExplorerSortChip(sort, onClick, tag = tag)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerSortSheet(current: ExplorerSort, keys: List<String>, onPick: (ExplorerSort) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, shape = ExplorerShapes.sheet, containerColor = MaterialTheme.colorScheme.surface, dragHandle = { ExplorerSheetHandle() }) {
        ExplorerSortSheetBody(current, keys, onPick, onDismiss, Modifier.navigationBarsPadding())
    }
}

@Composable
fun ExplorerSortSheetBody(current: ExplorerSort, keys: List<String>, onPick: (ExplorerSort) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val offered = ExplorerSorts.offered(keys)
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 20.dp).testTag("explorer.sort.sheet"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ExplorerSorts.TITLE,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            ExplorerLinkButton(ExplorerSorts.DONE, onClick = onDone, modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp).testTag("explorer.sort.done"))
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            offered.forEach { key ->
                val on = key == current.key
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(ExplorerShapes.tile)
                        .background(if (on) explorerSelectedRow() else MaterialTheme.colorScheme.surface, ExplorerShapes.tile)
                        .selectable(selected = on, role = Role.RadioButton) { if (!on) onPick(ExplorerSorts.pick(key)) }
                        .padding(horizontal = 8.dp)
                        .testTag("explorer.sort.key.$key"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = on, onClick = null)
                    Text(
                        ExplorerSorts.label(key),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        val directions = ExplorerSorts.directions(current.key)
        if (directions.size > 1) {
            Spacer(Modifier.height(16.dp))
            Text(
                ExplorerSorts.ORDER,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("explorer.sort.dir")) {
                directions.forEachIndexed { index, dir ->
                    SegmentedButton(
                        selected = current.dir == dir,
                        onClick = { if (current.dir != dir) onPick(ExplorerSort(current.key, dir)) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = directions.size),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("explorer.sort.dir.$dir"),
                    ) {
                        Text(ExplorerSorts.word(current.key, dir), maxLines = 1)
                    }
                }
            }
        }
    }
}
