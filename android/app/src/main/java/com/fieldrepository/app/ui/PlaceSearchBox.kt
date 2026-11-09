package com.fieldrepository.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.data.ConnectivityObserver
import com.fieldrepository.app.data.PLACE_SEARCH_MIN_QUERY_LENGTH
import com.fieldrepository.app.data.PlaceHit
import com.fieldrepository.app.data.PlaceSearchException
import com.fieldrepository.app.data.describePlaceSearchFailure
import com.fieldrepository.app.data.placeSearchAvailable
import com.fieldrepository.app.data.searchPlaces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val PLACE_SEARCH_DEBOUNCE_MS = 350L

internal enum class PlaceSearchState { Idle, Short, Searching, Done, Failed }

internal fun placeSearchStatusLine(
    state: PlaceSearchState,
    query: String,
    hits: List<PlaceHit>,
    problem: String?
): String = when (state) {
    PlaceSearchState.Short -> "Type at least $PLACE_SEARCH_MIN_QUERY_LENGTH letters."
    PlaceSearchState.Searching -> "Searching…"
    PlaceSearchState.Failed -> problem ?: "The place search did not answer."
    PlaceSearchState.Done ->
        if (hits.isNotEmpty()) {
            "${hits.size} place${if (hits.size == 1) "" else "s"} found."
        } else {
            "No place called “${query.trim()}”. Try a nearby town, or place the pin by hand."
        }
    PlaceSearchState.Idle -> ""
}

@Composable
fun PlaceSearchOverlay(
    apiKey: String,
    getProximity: () -> Pair<Double, Double>?,
    onChoose: (PlaceHit) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Search for a place to move the map",
    hint: String = "Search only moves the map; place the pin yourself.",
    elevated: Boolean = true
) {
    if (!placeSearchAvailable(apiKey)) {
        Surface(
            color = Canvas,
            shape = RoundedCornerShape(10.dp),
            shadowElevation = 2.dp,
            modifier = modifier.fillMaxWidth()
        ) {
            Text(
                "Place search unavailable. Move the map or type coordinates.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        return
    }

    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf(emptyList<PlaceHit>()) }
    var state by remember { mutableStateOf(PlaceSearchState.Idle) }
    var problem by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        val wanted = query.trim()
        if (wanted.length < PLACE_SEARCH_MIN_QUERY_LENGTH) {
            hits = emptyList()
            problem = null
            state = if (wanted.isEmpty()) PlaceSearchState.Idle else PlaceSearchState.Short
            return@LaunchedEffect
        }
        state = PlaceSearchState.Searching
        delay(PLACE_SEARCH_DEBOUNCE_MS)
        if (!ConnectivityObserver.isOnline(context)) {
            hits = emptyList()
            problem = describePlaceSearchFailure(PlaceSearchException(null, "No connection"))
            state = PlaceSearchState.Failed
            open = true
            return@LaunchedEffect
        }
        try {
            val found = searchPlaces(wanted, apiKey, getProximity())
            hits = found
            problem = null
            state = PlaceSearchState.Done
            open = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            hits = emptyList()
            problem = describePlaceSearchFailure(error)
            state = PlaceSearchState.Failed
            open = true
        }
    }

    val status = placeSearchStatusLine(state, query, hits, problem)

    Surface(
        color = Canvas,
        shape = RoundedCornerShape(10.dp),
        shadowElevation = if (elevated) 2.dp else 0.dp,
        modifier = modifier.fillMaxWidth().testTag("place-search")
    ) {
        Column(modifier = Modifier.padding(if (elevated) 8.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; open = true },
                label = { Text(label) },
                placeholder = { Text("Village, town or district", color = Muted) },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = Muted,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    when {
                        state == PlaceSearchState.Searching ->
                            FieldCircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        query.isNotEmpty() ->
                            IconButton(onClick = { query = ""; open = false }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear the place search", tint = Muted)
                            }
                        else -> {}
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                hint,
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 14.sp
            )

            if (open && state != PlaceSearchState.Idle) {
                HorizontalDivider(color = Hairline)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 168.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (hits.isEmpty()) {
                        Text(
                            status,
                            color = if (state == PlaceSearchState.Failed) MaterialTheme.colorScheme.error else Muted,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 16.sp,
                            fontWeight = if (state == PlaceSearchState.Failed) FontWeight.Medium else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    } else {
                        hits.forEach { hit ->
                            PlaceSearchRow(
                                hit = hit,
                                onClick = {
                                    onChoose(hit)
                                    open = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceSearchRow(hit: PlaceHit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(SurfaceCard, RoundedCornerShape(8.dp))
            .heightIn(min = 48.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                hit.name.ifBlank { "Unnamed place" },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (hit.context.isNotBlank()) {
                Text(hit.context, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
