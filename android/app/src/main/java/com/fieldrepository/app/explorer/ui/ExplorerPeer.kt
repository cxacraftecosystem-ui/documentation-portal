package com.fieldrepository.app.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.explorer.data.ExplorerFilterApi
import com.fieldrepository.app.explorer.data.ExplorerFilterKey
import com.fieldrepository.app.explorer.data.ExplorerFilters
import com.fieldrepository.app.explorer.data.ExplorerPeerPage
import com.fieldrepository.app.explorer.data.ExplorerPeerRow
import com.fieldrepository.app.explorer.data.ExplorerPeerTypes
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.CancellationException

sealed interface PeerLoad {
    data object Loading : PeerLoad
    data class Ready(val rows: List<ExplorerPeerRow>, val last: ExplorerPeerPage) : PeerLoad
    data object Failed : PeerLoad
}

object ExplorerPeerRows {
    fun merge(old: List<ExplorerPeerRow>, page: ExplorerPeerPage): List<ExplorerPeerRow> {
        val seen = old.map { it.type + "/" + it.id }.toHashSet()
        return old + page.items.filter { seen.add(it.type + "/" + it.id) }
    }

    fun meta(row: ExplorerPeerRow): String = listOfNotNull(
        row.subtitle?.takeIf { it.isNotBlank() },
        row.place?.takeIf { it.isNotBlank() },
        row.recordDate?.take(10)?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    fun hasMore(load: PeerLoad.Ready): Boolean = load.rows.size < load.last.total && load.last.page < load.last.pages
}

@Composable
internal fun PeerResults(
    api: ExplorerFilterApi,
    path: String,
    filters: ExplorerFilters,
    onOpen: ((type: String, id: String) -> Unit)?,
    q: String? = null,
) {
    val type = if (q.isNullOrBlank()) ExplorerPeerTypes.browseKindOf(path) else null
    val query = filters.query()
    val words = q?.trim()?.takeIf { it.isNotEmpty() }
    var load by remember(type, query, words) { mutableStateOf<PeerLoad>(PeerLoad.Loading) }
    var page by remember(type, query, words) { mutableIntStateOf(1) }
    var attempt by remember(type, query, words) { mutableIntStateOf(0) }
    var more by remember(type, query, words) { mutableStateOf(false) }
    LaunchedEffect(type, query, words, page, attempt) {
        if (words != null && page == 1 && attempt == 0) kotlinx.coroutines.delay(350)
        val before = (load as? PeerLoad.Ready)?.rows.orEmpty().takeIf { page > 1 }.orEmpty()
        more = page > 1
        load = try {
            val got = api.peer(type = type, q = words, page = page, filters = query)
            PeerLoad.Ready(ExplorerPeerRows.merge(before, got), got)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (before.isNotEmpty()) (load as? PeerLoad.Ready ?: PeerLoad.Failed) else PeerLoad.Failed
        }
        more = false
    }
    Column(Modifier.fillMaxWidth().testTag("explorer.peer"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val ready = load as? PeerLoad.Ready
        Text(
            ready?.last?.portalLabel?.takeIf { it.isNotBlank() }?.let { "From $it" } ?: FilterText.otherPortal,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        when (val now = load) {
            PeerLoad.Loading -> LoadingLine(FilterText.loading)
            PeerLoad.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(FilterText.peerFailed, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f).testTag("explorer.peer.failed"))
                ExplorerLinkButton(FilterText.retry, onClick = { attempt++ })
            }
            is PeerLoad.Ready -> {
                ExplorerPeerTypes.statusLine(now.last)?.let { HintLine(it) }
                if (now.last.status == "ok" && now.rows.isEmpty()) HintLine(FilterText.peerNone)
                val skipped = now.last.notApplied.mapNotNull { param -> ExplorerFilterKey.entries.firstOrNull { it.param == param }?.label ?: param.takeIf { it.isNotBlank() } }
                if (skipped.isNotEmpty() && now.rows.isNotEmpty()) HintLine(FilterText.notApplied(skipped))
                if (now.rows.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().clip(ExplorerShapes.card).explorerCard()) {
                        now.rows.forEachIndexed { index, row ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.field.hairline)
                            PeerRow(row, onOpen)
                        }
                    }
                }
                if (now.last.total > now.rows.size) {
                    Text(FilterText.peerShowing(now.rows.size, now.last.total), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("explorer.peer.showing"))
                }
                if (ExplorerPeerRows.hasMore(now)) {
                    ExplorerSecondaryButton(
                        text = if (more) FilterText.loading else FilterText.peerMore(minOf(now.last.pageSize, now.last.total - now.rows.size)),
                        onClick = { page = now.last.page + 1 },
                        enabled = !more,
                        modifier = Modifier.fillMaxWidth().testTag("explorer.peer.more"),
                    )
                }
            }
        }
    }
}

@Composable
private fun PeerRow(row: ExplorerPeerRow, onOpen: ((type: String, id: String) -> Unit)?) {
    val target = row.remote
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .clickable(enabled = target != null && onOpen != null, role = Role.Button) { target?.let { (t, id) -> onOpen?.invoke(t, id) } }
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("explorer.peer.row.${row.type}.${row.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title.ifBlank { row.id }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ExplorerPeerRows.meta(row).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Text(
            row.portalLabel.ifBlank { ExplorerFilters.portalLabel(ExplorerFilters.OTHER_PORTAL) },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer, ExplorerShapes.pill).padding(horizontal = 10.dp, vertical = 4.dp).testTag("explorer.peer.chip.${row.id}"),
        )
    }
}
