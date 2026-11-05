package com.fieldrepository.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.explorer.data.ExplorerText
import com.fieldrepository.app.explorer.ui.ExplorerScreen
import com.fieldrepository.app.explorer.ui.ExplorerUiText
import com.fieldrepository.app.explorer.ui.LocalExplorerSearchAll

@Composable
fun DataBrowserScreen(
    repository: FieldRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit = {},
    onOpenMedia: ((mediaId: String) -> Unit)? = null,
    startPath: String = "",
    onPathChange: (String) -> Unit = {},
    onOpenRemote: ((type: String, id: String) -> Unit)? = null,
    onRecordNow: ((folderPath: String, folderName: String) -> Unit)? = null,
    onOpenSection: ((artisanId: String, sectionKey: String) -> Unit)? = null,
    onOpenRecord: ((kind: String, id: String) -> Unit)? = null,
) {
    ExplorerScreen(
        onBack = onBack,
        modifier = modifier,
        onMessage = onMessage,
        onOpenMedia = onOpenMedia,
        startPath = startPath.takeIf { it.isNotBlank() },
        search = { onOpen -> DataRecordSearch(repository, onOpen) },
        searchScreen = { query, onOpenInData, onPlay, onClose ->
            DataUnifiedSearch(
                repository = repository,
                initial = query,
                onOpenInData = onOpenInData,
                onPlay = onPlay,
                onOpenRemote = onOpenRemote?.let { open -> { type: String, id: String -> onClose(); open(type, id) } },
                onClose = onClose,
            )
        },
        onRecordNow = onRecordNow,
        onOpenSection = onOpenSection,
        onOpenRecord = onOpenRecord,
    )
}

fun dataFolderArtisan(path: String): String? =
    path.split('/').zipWithNext().lastOrNull { (segment, _) -> segment == "artisans" }?.second?.takeIf { it.isNotBlank() }

internal fun quickSearchFetch(repository: FieldRepository, filters: SearchFilters): SearchPageFetch {
    val (dateFrom, dateTo) = filters.resolveDateRange()
    return { types, page, pageSize ->
        repository.search(
            q = filters.query.trim().ifBlank { null },
            craftId = filters.craftId.ifBlank { null },
            place = filters.place.trim().ifBlank { null },
            artisanId = filters.artisanId.ifBlank { null },
            mediaType = filters.mediaType.ifBlank { null },
            types = types,
            dateFrom = dateFrom,
            dateTo = dateTo,
            page = page,
            pageSize = pageSize,
        )
    }
}

@Composable
internal fun DataRecordSearch(repository: FieldRepository, onOpen: (recordType: String, recordId: String, title: String) -> Unit) {
    var filters by remember { mutableStateOf(SearchFilters()) }
    var browsing by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf<SearchFilters?>(null) }
    val search = rememberQuickSearch(repository, filters, browse = browsing)
    val searchAll = LocalExplorerSearchAll.current
    val active = quickSearchRuns(filters, browsing)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = filters.query,
            onValueChange = { filters = filters.copy(query = it) },
            placeholder = { Text(ExplorerUiText.jumpHint) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                if (filters.query.isNotBlank()) {
                    IconButton(onClick = { filters = filters.copy(query = "") }) {
                        Icon(Icons.Filled.Close, contentDescription = ExplorerText.clear, modifier = Modifier.size(18.dp))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { searchAll?.invoke(filters.query) }),
            modifier = Modifier.fillMaxWidth().testTag("explorer.search")
        )
        SearchFilterBar(value = filters, onChange = { filters = it; browsing = true })
        search.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (active && search.searched && !search.loading && search.hits.isEmpty() && search.error == null) {
            Text(DataSearchText.noMatches(filters.query.trim()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (active && search.hits.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().testTag("explorer.search.summary"), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (search.total > search.hits.size) ExplorerText.showing(search.hits.size, search.total) else SearchAllText.title(search.total),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (browsing && filters.query.isBlank() && !filters.hasFilters) {
                    TextButton(onClick = { browsing = false }, modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.search.hide")) { Text(SearchAllText.hide) }
                }
                if (search.total > search.hits.size) {
                    TextButton(onClick = { showAll = filters }, modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.search.all")) { Text(SearchAllText.showAll(search.total)) }
                }
            }
        }
        if (active) {
            search.hits.forEach { hit ->
                SearchResultRow(row = hit, onOpen = {
                    onOpen(hit.recordType, hit.id, hit.title)
                    filters = SearchFilters()
                    browsing = false
                })
            }
        }
        if (searchAll != null && filters.query.trim().length >= 2) {
            TextButton(onClick = { searchAll(filters.query) }, modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.search.everything")) {
                Text("${ExplorerUiText.searchEverything} for “${filters.query.trim()}”")
            }
        }
    }
    showAll?.let { picked ->
        SearchAllDialog(
            filters = picked,
            fetch = remember(picked) { quickSearchFetch(repository, picked) },
            onOpen = { type, id, title ->
                onOpen(type, id, title)
                filters = SearchFilters()
                browsing = false
            },
            onClose = { showAll = null },
        )
    }
}
