package com.fieldrepository.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.RemoteRecord
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.cardRows
import com.fieldrepository.app.data.openInLabel
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/**
 * One record from the other portal, read-only (docs/SEARCH.md): what that portal shares about it and
 * a button that opens it there, in the browser, where the account's own permissions apply. Nothing
 * here can be edited or downloaded, and nothing is kept on the phone. The web's twin is
 * `frontend/app/(protected)/remote/[type]/[id]/page.tsx`.
 */
@Composable
fun RemoteRecordScreen(repository: FieldRepository, type: String, id: String) {
    var record by remember(type, id) { mutableStateOf<RemoteRecord?>(null) }
    var error by remember(type, id) { mutableStateOf<String?>(null) }
    LaunchedEffect(type, id) {
        try {
            record = repository.remoteRecord(type, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            error = remoteRecordErrorLine(e.code())
        } catch (e: Exception) {
            error = remoteRecordErrorLine(0)
        }
    }
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        val shown = record
        when {
            error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            shown == null -> FieldCircularProgressIndicator()
            else -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            shown.title,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() }
                        )
                        shown.subtitle?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted)
                        }
                        shown.cardRows { it.take(10) }.forEach { (label, value) ->
                            Column {
                                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.field.muted)
                                Text(value, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        shown.snippet?.takeIf { it.isNotBlank() }?.let { com.fieldrepository.app.transcript.data.TranscriptPreview.cleanSnippet(it) }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                shown.openUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { url ->
                    ActionRow {
                        Button(
                            onClick = {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                } catch (_: ActivityNotFoundException) {
                                    error = "No browser on this phone can open it."
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                            Text(openInLabel(shown.portal), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

/** The one sentence for a record that could not be shown, by HTTP status. The web says the same. */
fun remoteRecordErrorLine(status: Int): String =
    if (status == 503) "The other portal can't be reached right now. Try again shortly."
    else "This record isn't available."
