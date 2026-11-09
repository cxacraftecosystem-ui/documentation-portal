package com.fieldrepository.app.questionnaire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.BuildConfig
import com.fieldrepository.app.data.PlaceHit
import com.fieldrepository.app.questionnaire.data.QSessionLocation
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.ui.ActionRow
import com.fieldrepository.app.ui.MapPickerDialog
import com.fieldrepository.app.ui.PlaceSearchOverlay
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.launch
import java.util.Locale

data class SessionWhere(val place: String = "", val location: QSessionLocation? = null)

internal const val SESSION_PLACE_MAX = 200

internal fun placeFromHit(name: String, context: String): String {
    val seen = mutableSetOf<String>()
    return (listOf(name) + context.split(","))
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.equals("India", ignoreCase = true) && seen.add(it.lowercase(Locale.ROOT)) }
        .take(3)
        .joinToString(", ")
        .take(SESSION_PLACE_MAX)
}

internal fun locationFromHit(hit: PlaceHit): QSessionLocation = QSessionLocation(
    latitude = hit.lat,
    longitude = hit.lon,
    placeName = listOf(hit.name, hit.context).filter { it.isNotBlank() }.joinToString(", ").take(SESSION_PLACE_MAX).ifBlank { null }
)

internal fun pinnedLine(location: QSessionLocation): String = QuestionnaireCopy.t(
    "placePinned",
    "lat" to String.format(Locale.ROOT, "%.5f", location.latitude),
    "lon" to String.format(Locale.ROOT, "%.5f", location.longitude)
)

internal fun pinnedAt(where: SessionWhere, lat: Double, lng: Double): SessionWhere =
    where.copy(location = QSessionLocation(latitude = lat, longitude = lng, placeName = where.place.trim().ifBlank { null }))

@Composable
fun SessionPlaceField(
    value: SessionWhere,
    onChange: (SessionWhere) -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(value)
    var mapOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().testTag("q-place"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value.place,
            onValueChange = { onChange(current.copy(place = it.take(SESSION_PLACE_MAX))) },
            label = { Text(QuestionnaireCopy.s("start.place")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("q-place-text"),
            trailingIcon = trailingIcon
        )
        PlaceSearchOverlay(
            apiKey = BuildConfig.MAPTILER_API_KEY,
            getProximity = { current.location?.let { it.longitude to it.latitude } },
            onChoose = { hit -> onChange(SessionWhere(placeFromHit(hit.name, hit.context), locationFromHit(hit))) },
            label = QuestionnaireCopy.s("place.search"),
            hint = QuestionnaireCopy.s("place.searchHint"),
            elevated = false
        )
        ActionRow(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { mapOpen = true }, modifier = Modifier.testTag("q-place-map")) {
                Icon(Icons.Outlined.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(QuestionnaireCopy.s("place.pickOnMap"))
            }
        }
        value.location?.let { location ->
            Row(Modifier.fillMaxWidth().testTag("q-place-pin"), verticalAlignment = Alignment.CenterVertically) {
                Text(pinnedLine(location), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted, modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(current.copy(location = null)) }, modifier = Modifier.testTag("q-place-unpin")) {
                    Text(QuestionnaireCopy.s("place.remove"))
                }
            }
        }
    }
    if (mapOpen) {
        MapPickerDialog(
            initialLat = value.location?.latitude,
            initialLng = value.location?.longitude,
            onDismiss = { mapOpen = false }
        ) { lat, lng ->
            mapOpen = false
            onChange(pinnedAt(current, lat, lng))
            if (current.place.isBlank()) scope.launch {
                placeAt(context, lat, lng)?.let { words -> if (current.place.isBlank()) onChange(current.copy(place = words)) }
            }
        }
    }
}
