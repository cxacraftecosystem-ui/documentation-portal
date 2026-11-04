package com.fieldrepository.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.explorer.data.ExplorerText
import com.fieldrepository.app.explorer.ui.ExplorerScreen

@Composable
fun DataBrowserScreen(
    repository: FieldRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit = {},
    onOpenMedia: ((mediaId: String) -> Unit)? = null,
    startPath: String = "",
    onPathChange: (String) -> Unit = {}
) {
    ExplorerScreen(
        onBack = onBack,
        modifier = modifier,
        onMessage = onMessage,
        onOpenMedia = onOpenMedia,
        startPath = startPath.takeIf { it.isNotBlank() },
        search = { onOpen -> DataRecordSearch(repository, onOpen) }
    )
}

@Composable
internal fun DataRecordSearch(repository: FieldRepository, onOpen: (recordType: String, recordId: String, title: String) -> Unit) {
    var filters by remember { mutableStateOf(SearchFilters()) }
    val search = rememberQuickSearch(repository, filters)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = filters.query,
            onValueChange = { filters = filters.copy(query = it) },
            placeholder = { Text(ExplorerText.searchHint) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                if (filters.query.isNotBlank()) {
                    IconButton(onClick = { filters = filters.copy(query = "") }) {
                        Icon(Icons.Filled.Close, contentDescription = ExplorerText.clear, modifier = Modifier.size(18.dp))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("explorer.search")
        )
        if (filters.query.isNotBlank() || filters.hasFilters) SearchFilterBar(value = filters, onChange = { filters = it })
        search.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        search.hits.forEach { hit ->
            SearchResultRow(row = hit, onOpen = {
                onOpen(hit.recordType, hit.id, hit.title)
                filters = SearchFilters()
            })
        }
        if (search.total > search.hits.size) {
            Text(ExplorerText.showing(search.hits.size, search.total), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
