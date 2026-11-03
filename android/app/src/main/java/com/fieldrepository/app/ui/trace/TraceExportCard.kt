package com.fieldrepository.app.ui.trace

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import com.fieldrepository.app.ui.FieldCircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.data.apiErrorMessage
import com.fieldrepository.app.ui.Text
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun Modifier.heightIn48(): Modifier = this.heightIn(min = 48.dp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TraceExportCard(
    traceSvg: String,
    geometry: TraceGeometry?,
    documentWidth: Int,
    documentHeight: Int,
    documentBackground: Int?,
    sourceName: String,
    shapeCount: Int,
    nodeCount: Int,
    frameNote: String = "",
    isPreview: Boolean,
    exporter: TraceEngineExporter,
    busy: Boolean,
    onBusyChange: (Boolean) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    onBackgroundChange: ((white: Boolean) -> Unit)? = null,
    onNeedFullResolution: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var formatId by remember { mutableStateOf(TRACE_EXPORT_FORMATS.first().id) }
    var working by remember { mutableStateOf(false) }

    var saved by remember { mutableStateOf<TraceExportSaved?>(null) }

    var savedNote by remember { mutableStateOf("") }

    val format = remember(formatId) {
        traceExportFormat(formatId) ?: TRACE_EXPORT_FORMATS.first()
    }

    val plan = remember(format, exporter.refusal, geometry) {
        traceExportPlan(format, exporter.refusal, hasGeometry = geometry != null)
    }
    val losses = remember(format, documentBackground, documentWidth, documentHeight, frameNote) {
        traceExportLosses(
            format = format,
            documentBackground = documentBackground,
            documentLongEdgePx = maxOf(documentWidth, documentHeight),
            frameNote = frameNote,
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Save this drawing",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "$TRACE_EXPORT_FORMAT_COUNT formats of the same drawing.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TRACE_EXPORT_FORMATS.forEach { row ->
                FilterChip(
                    selected = row.id == formatId,
                    onClick = {
                        if (busy) return@FilterChip
                        formatId = row.id
                        saved = null
                        savedNote = ""
                    },
                    label = { Text(row.label, style = MaterialTheme.typography.labelLarge) },
                    modifier = Modifier.heightIn48(),
                )
            }
        }

        Text(
            format.hint,
            color = MaterialTheme.field.body,
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider(color = MaterialTheme.field.hairline)

        Text(
            "Background",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (onBackgroundChange != null) {
            val white = traceBackgroundIsWhite(documentBackground)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(true, false).forEach { option ->
                    FilterChip(
                        selected = option == white,
                        onClick = {
                            if (busy || option == white) return@FilterChip
                            saved = null
                            savedNote = ""
                            onBackgroundChange(option)
                        },
                        label = { Text(if (option) "White" else "Transparent", style = MaterialTheme.typography.labelLarge) },
                        modifier = Modifier.heightIn48(),
                    )
                }
            }
            Text(
                TRACE_BACKGROUND_RETRACE_SENTENCE,
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                traceBackgroundLabel(documentBackground),
                color = MaterialTheme.field.body,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        losses.forEach { line ->
            Text(
                "· $line",
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        when {
            isPreview -> {
                Text(
                    TRACE_EXPORT_PREVIEW_SENTENCE,
                    color = MaterialTheme.field.warning,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp,
                )
                if (onNeedFullResolution != null) {
                    OutlinedButton(
                        onClick = { if (!busy) onNeedFullResolution() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn48(),
                    ) {
                        Text("Trace it at full size", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            plan is TraceExportPlan.Refused -> {
                Text(
                    plan.reason,
                    color = MaterialTheme.field.warning,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp,
                )
            }

            else -> {
                OutlinedButton(
                    onClick = {
                        if (busy) return@OutlinedButton
                        onBusyChange(true)
                        working = true
                        saved = null
                        savedNote = ""
                        scope.launch {
                            runCatching {
                                traceWriteExport(
                                    context = context,
                                    exporter = exporter,
                                    plan = plan,
                                    format = format,
                                    geometry = geometry,
                                    documentWidth = documentWidth,
                                    documentHeight = documentHeight,
                                    documentBackground = documentBackground,
                                    traceSvg = traceSvg,
                                    sourceName = sourceName,
                                    shapeCount = shapeCount,
                                    nodeCount = nodeCount,
                                    frameNote = frameNote,
                                )
                            }
                                .onSuccess { outcome ->
                                    when (outcome) {
                                        is TraceWriteOutcome.Saved -> {
                                            saved = outcome.saved
                                            savedNote = outcome.note
                                        }
                                        is TraceWriteOutcome.Refused -> onError(outcome.reason)
                                    }
                                }
                                .onFailure { error ->
                                    if (error !is CancellationException) {
                                        onError(
                                            error.apiErrorMessage("That file could not be saved.")
                                        )
                                    }
                                }
                            working = false
                            onBusyChange(false)
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn48(),
                ) {
                    if (working) {
                        FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Filled.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (working) "Writing the file…" else format.save, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Box(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            }
        ) {
            saved?.let { file ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Saved",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(file.savedTo, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)

                    if (savedNote.isNotBlank()) {
                        Text(
                            savedNote,
                            color = MaterialTheme.field.warning,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    if (file.shareUri != null) {
                        OutlinedButton(
                            onClick = {
                                val send = traceExportShareIntent(file) ?: return@OutlinedButton
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(send, "Send the drawing")
                                    )
                                }.onFailure {
                                    onError(
                                        "No app can send this. It's saved — ${file.savedTo}"
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn48(),
                        ) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Send it")
                        }
                        Text(
                            TRACE_EXPORT_SHARE_CAVEAT,
                            color = MaterialTheme.field.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            TRACE_EXPORT_NO_SHARE_SENTENCE,
                            color = MaterialTheme.field.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

sealed class TraceWriteOutcome {
    class Saved(val saved: TraceExportSaved, val note: String = "") : TraceWriteOutcome()

    class Refused(val reason: String) : TraceWriteOutcome()
}

private suspend fun traceWriteExport(
    context: Context,
    exporter: TraceEngineExporter,
    plan: TraceExportPlan,
    format: TraceExportFormat,
    geometry: TraceGeometry?,
    documentWidth: Int,
    documentHeight: Int,
    documentBackground: Int?,
    traceSvg: String,
    sourceName: String,
    shapeCount: Int,
    nodeCount: Int,
    frameNote: String,
): TraceWriteOutcome {
    var note = ""
    val bytes: ByteArray = when (plan) {
        is TraceExportPlan.Refused -> return TraceWriteOutcome.Refused(plan.reason)

        is TraceExportPlan.FromTraceSvg -> traceSvg.toByteArray(Charsets.UTF_8)

        is TraceExportPlan.FromPlatformRaster -> {
            val handle = geometry
                ?: return TraceWriteOutcome.Refused(TRACE_NO_GEOMETRY_SENTENCE)
            note = tracePngReductionNote(
                documentWidth = documentWidth,
                documentHeight = documentHeight,
                size = tracePngSize(documentWidth, documentHeight),
            )
            traceRenderPngBytes(
                geometry = handle,
                documentWidth = documentWidth,
                documentHeight = documentHeight,
                background = traceExportBackground(documentBackground),
            ) ?: return TraceWriteOutcome.Refused(TRACE_PNG_MEMORY_REFUSAL)
        }

        is TraceExportPlan.FromExporter -> {
            val handle = geometry
                ?: return TraceWriteOutcome.Refused(TRACE_NO_GEOMETRY_SENTENCE)
            val outcome = exporter.export(
                TraceExportRequest(
                    geometry = handle,
                    width = documentWidth,
                    height = documentHeight,
                    format = format,
                    background = traceExportBackground(documentBackground),
                    provenanceNote = traceProvenanceNote(
                        sourceName = sourceName,
                        shapeCount = shapeCount,
                        nodeCount = nodeCount,
                        frameNote = frameNote,
                    ),
                )
            )
            when (outcome) {
                is TraceExportOutcome.Refused -> return TraceWriteOutcome.Refused(outcome.reason)
                is TraceExportOutcome.Done -> outcome.bytes
            }
        }
    }

    val name = traceExportFileName(
        sourceName = sourceName,
        extension = format.extension,
        suffix = traceSaveSuffix(format),
    )
    return TraceWriteOutcome.Saved(
        traceSaveExport(
            context = context,
            bytes = bytes,
            fileName = name,
            mime = format.mime,
        ),
        note = note,
    )
}
