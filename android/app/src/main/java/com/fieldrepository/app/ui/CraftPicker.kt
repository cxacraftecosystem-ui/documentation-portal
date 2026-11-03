package com.fieldrepository.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.CatalogueCraft
import com.fieldrepository.app.data.CraftCatalogue
import com.fieldrepository.app.data.CraftCatalogueCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CraftPickerField(
    label: String,
    selectedIds: List<String>,
    modifier: Modifier = Modifier,
    multiple: Boolean = false,
    enabled: Boolean = true,
    required: Boolean = false,
    isError: Boolean = false,
    supportingText: String? = null,
    placeholder: String = if (multiple) "Pick crafts" else "Pick a craft",
    fallbackNames: Map<String, String> = emptyMap(),
    allowedIds: Set<String>? = null,
    refresh: suspend () -> Unit = {},
    onChange: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val catalogue by CraftCatalogueCache.catalogue.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { CraftCatalogueCache.cached(context) } }
    var open by remember { mutableStateOf(false) }

    val names = selectedIds.map { id -> catalogue?.craft(id)?.name ?: fallbackNames[id] ?: "Craft not on the list" }
    val shown = names.joinToString(", ")
    val fieldLabel = if (required) "$label *" else label

    Column(modifier = Modifier.fillMaxWidth()) {
        Box {
            OutlinedTextField(
                value = shown,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                isError = isError,
                label = { Text(fieldLabel) },
                placeholder = { Text(placeholder) },
                singleLine = !multiple,
                trailingIcon = {
                    if (!required && !multiple && selectedIds.isNotEmpty() && enabled) {
                        IconButton(onClick = { onChange(emptyList()) }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear $label")
                        }
                    } else {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                },
                supportingText = supportingText?.let { message -> { Text(message) } },
                modifier = modifier.fillMaxWidth()
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(end = if (!required && !multiple && selectedIds.isNotEmpty()) 48.dp else 0.dp)
                    .clickable(enabled = enabled) { open = true }
                    .semantics {
                        role = Role.Button
                        contentDescription = if (shown.isBlank()) "$label, $placeholder" else "$label, $shown"
                    }
            )
        }
    }

    if (open) {
        CraftPickerSheet(
            title = label,
            catalogue = catalogue,
            selectedIds = selectedIds,
            multiple = multiple,
            allowedIds = allowedIds,
            refresh = refresh,
            onPick = { id ->
                if (multiple) {
                    onChange(if (id in selectedIds) selectedIds - id else selectedIds + id)
                } else {
                    onChange(listOf(id))
                    open = false
                }
            },
            onDismiss = { open = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun CraftPickerSheet(
    title: String,
    catalogue: CraftCatalogue?,
    selectedIds: List<String>,
    multiple: Boolean,
    allowedIds: Set<String>?,
    refresh: suspend () -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        runCatching { refresh() }
        refreshing = false
    }
    val groups by produceState<List<CraftCatalogueCache.Group>?>(initialValue = null, catalogue, query, allowedIds) {
        val list = catalogue
        value = if (list == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                val found = CraftCatalogueCache.search(list, query)
                    .filter { allowedIds == null || it.id in allowedIds }
                CraftCatalogueCache.group(list, found)
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search crafts") },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Search crafts" }
            )
            val shown = groups
            when {
                catalogue == null -> Text(
                    if (refreshing) "Loading crafts…" else CraftCatalogueCache.OFFLINE_FIRST_RUN,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                shown == null -> Unit
                shown.isEmpty() -> Text(
                    if (query.isBlank()) "No crafts on the list yet." else CraftCatalogueCache.noMatch(query.trim()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    for (group in shown) {
                        stickyHeader(key = "h-${group.key}") {
                            Text(
                                group.heading,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(top = 12.dp, bottom = 4.dp)
                                    .semantics { heading() }
                            )
                        }
                        items(group.crafts, key = { it.id }) { craft ->
                            CraftRow(craft = craft, selected = craft.id in selectedIds, onClick = { onPick(craft.id) })
                        }
                    }
                }
            }
            if (multiple) {
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Done") }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun CraftRow(craft: CatalogueCraft, selected: Boolean, onClick: () -> Unit) {
    val states = craft.stateOfPractice.joinToString(", ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = listOfNotNull(craft.name, if (craft.isGi) "GI" else null, states.ifBlank { null }).joinToString(", ")
            }
            .padding(vertical = 8.dp)
    ) {
        Box(Modifier.width(28.dp)) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    craft.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (craft.isGi) {
                    Text(
                        "GI",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
            if (states.isNotBlank()) {
                Text(
                    states,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
