package com.fieldrepository.app.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fieldrepository.app.BuildConfig
import com.fieldrepository.app.data.ArtisanAnswerDto
import com.fieldrepository.app.data.LocationRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ---------------------------------------------------------------------------
// Location editor: editable latitude/longitude, a one-tap GPS fix, and a
// point-on-map picker. Shared by every record form so capture is consistent.
// ---------------------------------------------------------------------------

@Composable
fun LocationEditor(
    value: LocationRequest?,
    onUseGps: () -> LocationRequest?,
    onChange: (LocationRequest?) -> Unit,
    onMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var latText by remember { mutableStateOf(value?.latitude?.let(::trimCoord) ?: "") }
    var lngText by remember { mutableStateOf(value?.longitude?.let(::trimCoord) ?: "") }
    var showMap by remember { mutableStateOf(false) }
    /*
     * The two boxes TRACK [value]; they are not seeded from it once.
     *
     * WHAT WENT WRONG. They were plain unkeyed `remember`s, so each captured whatever `value` held at
     * the first composition and never looked again. Every edit form fetches its record after mount,
     * so every edit form showed BLANK coordinates for a record that had a location — and blank here
     * is not merely wrong to read, it is armed: type one digit into latitude and delete it and both
     * boxes are blank, which [emit] reads as "the researcher cleared the location" and answers with
     * `onChange(null)`, destroying the stored coordinates (and, through the caller that folds state
     * and pincode onto the same row, those two as well). The "Clear location" button that would have
     * shown the location was still there was hidden by the same blankness.
     *
     * WHY AN EFFECT RATHER THAN A KEYED `remember`. `remember(value)` re-seeds on every emit, and an
     * emit happens on every keystroke — "22.5" would be rewritten to "22.500000" under the cursor as
     * it was being typed. This adopts an incoming coordinate only when it is a genuinely DIFFERENT
     * point from the one the boxes already hold, so a `value` that changed because of this editor's
     * own `onChange` leaves the typing alone and one that changed anywhere else is shown.
     */
    LaunchedEffect(value?.latitude, value?.longitude) {
        val sameLat = value?.latitude == latText.trim().toDoubleOrNull()
        val sameLng = value?.longitude == lngText.trim().toDoubleOrNull()
        if (!sameLat || !sameLng) {
            latText = value?.latitude?.let(::trimCoord) ?: ""
            lngText = value?.longitude?.let(::trimCoord) ?: ""
        }
    }

    fun emit() {
        val lat = latText.trim().toDoubleOrNull()
        val lng = lngText.trim().toDoubleOrNull()
        when {
            lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0 ->
                onChange(
                    LocationRequest(
                        latitude = lat,
                        longitude = lng,
                        altitude = value?.altitude,
                        accuracy = value?.accuracy,
                        placeName = value?.placeName ?: "Pinned location"
                    )
                )
            latText.isBlank() && lngText.isBlank() -> onChange(null)
            else -> { /* incomplete / out of range: wait for valid input */ }
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Location", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Text(
            "Where this was documented.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = latText,
                onValueChange = { latText = it; emit() },
                label = { Text("Latitude") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = lngText,
                onValueChange = { lngText = it; emit() },
                label = { Text("Longitude") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        ActionRow {
            OutlinedButton(
                onClick = {
                    val loc = onUseGps()
                    if (loc != null) {
                        latText = trimCoord(loc.latitude)
                        lngText = trimCoord(loc.longitude)
                        onChange(loc)
                        onMessage("Location tagged: ${trimCoord(loc.latitude)}, ${trimCoord(loc.longitude)}")
                    } else {
                        onMessage("No GPS fix yet. Try again shortly.")
                    }
                },
            ) { Text("Use current GPS") }
            OutlinedButton(onClick = { showMap = true }) {
                Text("Pick on map")
            }
        }
        if (latText.isNotBlank() || lngText.isNotBlank()) {
            ActionRow {
                TextButton(onClick = { latText = ""; lngText = ""; onChange(null) }) {
                    Text("Clear location")
                }
            }
        }
    }

    if (showMap) {
        MapPickerDialog(
            initialLat = latText.trim().toDoubleOrNull() ?: value?.latitude,
            initialLng = lngText.trim().toDoubleOrNull() ?: value?.longitude,
            onDismiss = { showMap = false },
            onPick = { lat, lng ->
                latText = trimCoord(lat)
                lngText = trimCoord(lng)
                onChange(
                    LocationRequest(
                        latitude = lat,
                        longitude = lng,
                        placeName = "Pinned on map"
                    )
                )
                onMessage("Pin set: ${trimCoord(lat)}, ${trimCoord(lng)}")
                showMap = false
            }
        )
    }
}

private fun trimCoord(value: Double): String = String.format("%.6f", value)

private class MapBridge(val callback: (Double, Double) -> Unit) {
    @JavascriptInterface
    fun onPick(lat: Double, lng: Double) {
        callback(lat, lng)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapPickerDialog(
    initialLat: Double?,
    initialLng: Double?,
    onDismiss: () -> Unit,
    onPick: (Double, Double) -> Unit
) {
    // Holds the most recent coordinate reported back from the WebView map.
    var picked by remember { mutableStateOf(initialLat?.let { lat -> initialLng?.let { lng -> lat to lng } }) }
    val startLat = initialLat ?: 22.0
    val startLng = initialLng ?: 79.0
    val startZoom = if (initialLat != null && initialLng != null) 13 else 4
    val html = remember(startLat, startLng, startZoom) {
        mapHtml(startLat, startLng, startZoom, BuildConfig.MAPTILER_API_KEY)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = Canvas),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Drop a pin", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text("Tap or drag the marker.", color = Muted, style = MaterialTheme.typography.bodySmall)
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(380.dp).background(SurfaceCard, RoundedCornerShape(12.dp)),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            webChromeClient = android.webkit.WebChromeClient()
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String?) {
                                    // Leaflet sizes itself to the container; the WebView often lays out
                                    // after the script runs, so nudge it once painting settles.
                                    view.evaluateJavascript(
                                        "if(window.__map){window.__map.invalidateSize(true);}",
                                        null
                                    )
                                }
                            }
                            addJavascriptInterface(MapBridge { lat, lng -> picked = lat to lng }, "AndroidMapBridge")
                            loadDataWithBaseURL("https://tile.openstreetmap.org/", html, "text/html", "utf-8", null)
                        }
                    },
                    onRelease = { it.destroy() }
                )
                picked?.let { (lat, lng) ->
                    Text("Selected: ${trimCoord(lat)}, ${trimCoord(lng)}", color = Body, style = MaterialTheme.typography.bodySmall)
                }
                ActionRow {
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = { picked?.let { (lat, lng) -> onPick(lat, lng) } },
                        enabled = picked != null,
                    ) { Text("Use this point") }
                }
            }
        }
    }
}

private fun mapHtml(lat: Double, lng: Double, zoom: Int, key: String): String {
    // OpenStreetMap raster tiles need no API key and render reliably inside a WebView.
    val tileUrl = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
          <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
          <style>
            html, body { height: 100%; margin: 0; padding: 0; }
            #map { position: absolute; top: 0; bottom: 0; left: 0; right: 0; background: #efe9de; }
          </style>
        </head>
        <body>
          <div id="map"></div>
          <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
          <script>
            function initMap() {
              if (typeof L === 'undefined') { setTimeout(initMap, 150); return; }
              var map = L.map('map').setView([$lat, $lng], $zoom);
              window.__map = map;
              L.tileLayer('$tileUrl', {
                maxZoom: 19,
                attribution: '&copy; OpenStreetMap contributors'
              }).addTo(map);
              var marker = L.marker([$lat, $lng], { draggable: true }).addTo(map);
              function report(p) {
                if (window.AndroidMapBridge) { AndroidMapBridge.onPick(p.lat, p.lng); }
              }
              marker.on('dragend', function (e) { report(e.target.getLatLng()); });
              map.on('click', function (e) { marker.setLatLng(e.latlng); report(e.latlng); });
              // The WebView frequently finishes layout after this script runs, leaving Leaflet
              // with a zero-size container (the blank-map symptom). Re-measure a few times.
              [120, 350, 700, 1200].forEach(function (t) { setTimeout(function () { map.invalidateSize(true); }, t); });
            }
            window.addEventListener('load', initMap);
            initMap();
          </script>
        </body>
        </html>
    """.trimIndent()
}

// ---------------------------------------------------------------------------
// Field provenance: who populated each field. Admin-only surface, fed by the
// extraMetadata.fieldProvenance object the backend stamps on every write.
// ---------------------------------------------------------------------------

data class ProvenanceRow(val field: String, val byName: String?, val at: String?)

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNullSafe()

private fun JsonPrimitive.contentOrNullSafe(): String? = if (isString) content else content.takeIf { it != "null" }

fun extractProvenance(meta: JsonObject?): List<ProvenanceRow> {
    val provenance = meta?.get("fieldProvenance") as? JsonObject ?: return emptyList()
    return provenance.entries.mapNotNull { (field, element) ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        ProvenanceRow(field = field, byName = obj.stringValue("byName"), at = obj.stringValue("at"))
    }.sortedBy { it.field }
}

private fun prettyFieldName(name: String): String =
    name.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .replaceFirstChar { it.uppercase() }

@Composable
fun ProvenanceSection(meta: JsonObject?, createdByName: String?) {
    val rows = remember(meta) { extractProvenance(meta) }
    if (rows.isEmpty() && createdByName.isNullOrBlank()) return
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Field provenance", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            if (!createdByName.isNullOrBlank()) {
                Text("Record created by $createdByName", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (rows.isEmpty()) {
                Text("No attribution yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
            } else {
                HorizontalDivider()
                rows.forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(prettyFieldName(row.field), color = Body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Text(row.byName ?: "Unknown", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            row.at?.take(10)?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Answered questionnaire Q&A, grouped by section. Only answered prompts are
// shown so the artisan page is never crowded with blanks.
// ---------------------------------------------------------------------------

@Composable
fun ArtisanQuestionnairePanel(answers: List<ArtisanAnswerDto>, loading: Boolean) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = Canvas),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Questionnaire answers", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            when {
                loading -> Text("Loading answers...", color = Muted, style = MaterialTheme.typography.bodySmall)
                answers.isEmpty() -> Text("No answers yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
                else -> {
                    val grouped = answers.groupBy { (it.sectionCode ?: "") + " " + (it.sectionTitle ?: "Section") }
                    grouped.forEach { (sectionLabel, sectionAnswers) ->
                        Spacer(Modifier.height(2.dp))
                        Text(sectionLabel.trim(), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        sectionAnswers.forEach { answer ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SurfaceCard, RoundedCornerShape(10.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                answer.prompt?.let { Text(it, color = Body, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium) }
                                Text(answer.answerText.orEmpty(), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                                val meta = listOfNotNull(answer.interviewTitle, answer.answeredByName?.let { "by $it" }).joinToString(" · ")
                                if (meta.isNotBlank()) Text(meta, color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
