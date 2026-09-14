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
import androidx.compose.material3.CircularProgressIndicator
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
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see ui/FieldText.kt.
import com.fieldrepository.app.ui.Text
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * **"SAVE THIS DRAWING" — the export step of the tracer, on the handset.**
 *
 * ── WHAT THIS CARD IS FOR ─────────────────────────────────────────────────────────────────────
 *
 * Somebody has traced a photographed sketch and is looking at the result. This is where the drawing
 * leaves the application: five formats, one button, and then the OS's own share sheet.
 * `TraceExport.kt` holds the table and the words; `TraceExporter.kt` holds the seam to whatever writes
 * the bytes; `TraceExportFile.kt` holds the route to Downloads. This file is only the surface.
 *
 * IT IS NOT THE ATTACH. The panel hands one derived file to the host's `onAttach`; this card writes to
 * the DEVICE and to the share sheet and touches no record at all. Keeping the two apart is what lets
 * somebody take a PDF away without anything reaching the archive — and [TRACE_EXPORT_KEEP_SENTENCE] is
 * printed against every save so that the difference between "on my phone" and "on the record" is never
 * left to be inferred.
 *
 * ── IT TAKES PRIMITIVES AND NOT A [TraceResult], ON PURPOSE ───────────────────────────────────
 *
 * Everything this card needs from a finished trace it takes one value at a time rather than taking the
 * result object, which keeps this file independent of a class other parts of the feature own. It also
 * means the card can be composed in a preview, or read by a person, with no runtime wired. (NO COUNT OF
 * THOSE VALUES IS WRITTEN HERE. A count in a KDoc is accurate on the day and wrong by the next
 * parameter; the parameter list below is the register.)
 *
 * ── ONE BUSY FLAG, HELD BY THE HOST ───────────────────────────────────────────────────────────
 *
 * [busy] and [onBusyChange] are the panel's flag, not a private one. Building a file ends in a
 * MediaStore write, and two of those racing into one folder is how one of them ends up truncated with
 * no error anywhere. It is also the trace panel's own rule from the other direction: two independent
 * busy flags mean "the loser would report 'the trace did not finish' while the winner quietly
 * succeeded".
 *
 * ── A PREVIEW IS NEVER SAVED ──────────────────────────────────────────────────────────────────
 *
 * [isPreview] is [TraceResult.isPreview] — the trace ran below full resolution. Saving that hands
 * somebody a coarser drawing than the one they approved with nothing on screen to say so, which is the
 * same gate the panel already puts on its attach button. So the card refuses, names the remedy, and
 * offers [onNeedFullResolution] where the host wired one.
 */

/** The 48dp touch floor this app applies wherever a control was thought about. */
private fun Modifier.heightIn48(): Modifier = this.heightIn(min = 48.dp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TraceExportCard(
    /**
     * The engine's own SVG string for the trace on screen — [TraceResult.svg], unaltered.
     *
     * Used verbatim for every SVG save; `TraceExporter.kt`'s header carries the proof that it is
     * exactly what the exporter would produce. Never edited, re-indented or re-printed on this side.
     */
    traceSvg: String,
    /**
     * The traced geometry, or null when the caller has none to give.
     *
     * Null is survivable and not an error — the SVG still saves, because the engine wrote it on the way
     * out — but it is no longer free: the PNG is painted from these shapes, so a host with none loses
     * the picture as well as the three vector take-aways and is told so by
     * [TRACE_NO_GEOMETRY_SENTENCE].
     */
    geometry: TraceGeometry?,
    /** [TraceResult.width]/[TraceResult.height] — the document's own frame, not a bounding box. */
    documentWidth: Int,
    documentHeight: Int,
    /**
     * What the document stage ACTUALLY wrote, packed ARGB or null.
     *
     * Read off the finished document rather than off the requested parameters, because auto-detection
     * runs before the first stage and the two can differ. See [traceExportBackground] for why the
     * export passes this through rather than choosing.
     */
    documentBackground: Int?,
    /** The photograph's name. Only the last segment is used, for the file name and the note. */
    sourceName: String,
    shapeCount: Int,
    nodeCount: Int,
    /**
     * [TraceResult.frameNote] — the crop clause, or empty when the whole sheet was traced.
     *
     * TWO JOBS, ONE STRING. It is appended to the provenance note handed to the exporter, and it is
     * what tells [traceExportLosses] to say out loud that a file with no metadata channel cannot record
     * a crop somebody made. A file that was cropped and says nothing about it leaves a reviewer holding
     * a photograph of a whole sheet and a drawing of a corner of it.
     */
    frameNote: String = "",
    /** [TraceResult.isPreview]. A preview is never saved — see the class header. */
    isPreview: Boolean,
    exporter: TraceEngineExporter,
    busy: Boolean,
    onBusyChange: (Boolean) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Where the host wired the "White background" toggle, if it did.
     *
     * `TraceParams.kt` relocates that control to [TraceTier.EXPORT], which is this card — but the VALUE
     * is a leaf of the engine's parameter tree and changing it means patching the params and running
     * the pipeline again, which is machinery the panel owns and this file must not duplicate. So the
     * card draws the chips and calls back; the host patches through
     * [TraceEngineRuntime.withOverrides] and re-traces. Null draws the current background as a plain
     * fact instead, so somebody always knows what ground their file will have.
     */
    onBackgroundChange: ((white: Boolean) -> Unit)? = null,
    /** Where the host wired "trace it at full size", if it did. Null draws the sentence alone. */
    onNeedFullResolution: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var formatId by remember { mutableStateOf(TRACE_EXPORT_FORMATS.first().id) }
    var working by remember { mutableStateOf(false) }

    /*
      SAVED STATE IS ABOUT THE FILE THAT WAS WRITTEN, AND IS DROPPED WHEN THE SELECTION MOVES.

      A "Saved — Downloads/sheet-line-art.svg" line still on screen after the chips have been switched
      to PDF is a confident wrong answer about which file exists: the Share button beneath it would hand
      over the SVG while the chip above it says PDF. Clearing on a change of format is the cheap, honest
      version of that — the file is still in Downloads, and the sentence that said so has simply stopped
      claiming to describe the current selection.
    */
    var saved by remember { mutableStateOf<TraceExportSaved?>(null) }

    /** What was true of the file that was just written — the PNG's reduction. Cleared with [saved]. */
    var savedNote by remember { mutableStateOf("") }

    val format = remember(formatId) {
        traceExportFormat(formatId) ?: TRACE_EXPORT_FORMATS.first()
    }

    /*
      `traceExportPlan` owns both refusals so the routing rule stays testable with no exporter, no
      runtime and no device — and so that "this build has no writers" and "this trace came back with no
      shapes" stay two sentences. They are different sets of working controls: with no writers the SVG
      AND the picture still save; with no shapes only the SVG does.
    */
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
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            // THE COUNT IS READ, NEVER TYPED. See TRACE_EXPORT_FORMAT_COUNT.
            "$TRACE_EXPORT_FORMAT_COUNT formats. The drawing is the same in all of them; what " +
                "changes is which machine can open it.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
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
                    label = { Text(row.label, fontSize = 13.sp) },
                    modifier = Modifier.heightIn48(),
                )
            }
        }

        // ALWAYS-VISIBLE TEXT UNDER THE ROW, never a tooltip: a phone has no hover, and these sentences
        // are the only documentation somebody offline for a fortnight has. Carried verbatim from the
        // web's table so both clients describe one format one way.
        Text(
            format.hint,
            color = MaterialTheme.field.body,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        HorizontalDivider(color = MaterialTheme.field.hairline)

        /*
          THE BACKGROUND, WHICH IS ONE VALUE WITH ONE AUTHORITY.

          `output.background` is a leaf of the engine's parameter tree and the engine's sanitiser is the
          only thing entitled to say what is legal in it. This card shows it and, where the host wired a
          callback, asks for it to be changed — it never holds a second copy. See
          [traceExportBackground] for why an export-time background of its own would break the SVG
          shortcut and let one drawing leave this phone two ways.
        */
        Text(
            "Background",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
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
                        label = { Text(if (option) "White" else "Transparent", fontSize = 13.sp) },
                        modifier = Modifier.heightIn48(),
                    )
                }
            }
            Text(
                TRACE_BACKGROUND_RETRACE_SENTENCE,
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        } else {
            // NO CONTROL WIRED, SO THIS IS A FACT AND NOT A CHOICE. Drawn anyway, because the losses
            // below refer to the ground the file will have and a reader needs to know which it is.
            Text(
                traceBackgroundLabel(documentBackground),
                color = MaterialTheme.field.body,
                fontSize = 12.sp,
            )
        }

        /*
          EVERY LOSS, UNCONDITIONALLY, AND THE "YOURS TO KEEP" SENTENCE IS ALWAYS THE FIRST OF THEM.

          The same rule the vendored pipeline states about its own notes and this repository takes most
          seriously: "A pipeline that silently discards 4 000 paths and one that found nothing look
          identical on screen otherwise." Here the ambiguity being closed is between a drawing that is on
          the record and one that is only on this phone.
        */
        losses.forEach { line ->
            Text(
                "· $line",
                color = MaterialTheme.field.warning,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        when {
            isPreview -> {
                Text(
                    TRACE_EXPORT_PREVIEW_SENTENCE,
                    color = MaterialTheme.field.warning,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                if (onNeedFullResolution != null) {
                    OutlinedButton(
                        onClick = { if (!busy) onNeedFullResolution() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn48(),
                    ) {
                        Text("Trace it at full size", fontSize = 13.sp)
                    }
                }
            }

            plan is TraceExportPlan.Refused -> {
                // A SENTENCE AND NOT A DEAD BUTTON, on the rule every refusal in this feature is held
                // to: a dead button teaches somebody the feature is broken; a sentence teaches them what
                // to do. The SVG chip beside this one still works, which is the remedy the sentence
                // names.
                Text(
                    plan.reason,
                    color = MaterialTheme.field.warning,
                    fontSize = 12.sp,
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
                                    // A CANCELLED JOB IS NOT A FAILURE. A cancel must never reach the
                                    // user as one, and the same holds when a composition leaves while a
                                    // file is being written.
                                    if (error !is CancellationException) {
                                        onError(
                                            error.message?.takeIf { it.isNotBlank() }
                                                ?: "That file could not be saved."
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
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Filled.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (working) "Writing the file…" else format.save, fontSize = 13.sp)
                }
            }
        }

        /*
          A POLITE LIVE REGION, because this block APPEARS IN ANSWER TO A PRESS. A reader who cannot see
          the layout gets nothing at all from a panel that quietly materialises below the button they
          just activated. The box is drawn whether or not there is anything in it, so the region is
          stable across the change, which is what makes the announcement fire.
        */
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
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // WHERE IT WENT, IN THE NAME MediaProvider ACTUALLY USED. A colliding DISPLAY_NAME
                    // is silently uniquified to `name (1).ext`, and printing the requested name would
                    // name a file that is not on disk.
                    Text(file.savedTo, color = MaterialTheme.field.body, fontSize = 11.sp)

                    // WHAT WAS DECIDED WHILE THIS FILE WAS BEING WRITTEN, in the past tense and with the
                    // real numbers in it. Inside the live region above rather than beside it, so a
                    // reader who cannot see the layout is told the picture was reduced in the same
                    // announcement that tells them it was saved.
                    if (savedNote.isNotBlank()) {
                        Text(
                            savedNote,
                            color = MaterialTheme.field.warning,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }

                    if (file.shareUri != null) {
                        OutlinedButton(
                            onClick = {
                                val send = traceExportShareIntent(file) ?: return@OutlinedButton
                                // `runCatching`: a handset with no app willing to receive this type
                                // throws `ActivityNotFoundException`, which must not take a screen down
                                // over a button somebody pressed out of curiosity.
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(send, "Send the drawing")
                                    )
                                }.onFailure {
                                    onError(
                                        "Nothing installed on this device offered to send it. It is " +
                                            "still saved — ${file.savedTo}"
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
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    } else {
                        Text(
                            TRACE_EXPORT_NO_SHARE_SENTENCE,
                            color = MaterialTheme.field.muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The press, as a function
 * ──────────────────────────────────────────────────────────────────────────── */

/** Either a file on the flash, or a sentence saying why not. */
sealed class TraceWriteOutcome {
    /**
     * @param note what is true of THIS file and was decided while it was being written, or empty.
     *
     * The only thing that fills it today is [tracePngReductionNote] — a picture capped at
     * [TRACE_PNG_MAX_EDGE_PX] is smaller than the drawing it came from, and how much smaller is not
     * known until the size has been computed. A cap stated in the future tense beside a format chooser
     * is advice; the same cap stated in the past tense with two real numbers in it is a fact about the
     * file now in somebody's Downloads folder.
     */
    class Saved(val saved: TraceExportSaved, val note: String = "") : TraceWriteOutcome()

    class Refused(val reason: String) : TraceWriteOutcome()
}

/**
 * Produce the bytes for one save and put them in Downloads.
 *
 * OUT OF THE BUTTON'S LAMBDA ON PURPOSE. What a press does is: pick the route, get bytes, name the
 * file, write it. Four steps that have to happen in that order and that a reader should be able to see
 * in one place — a composable's `onClick` is where a sequence like this becomes six nested lambdas
 * nobody re-reads.
 *
 * THE SVG ROUTE IS THE ONLY PLACE THIS SIDE PRODUCES BYTES ITSELF, and it produces them by encoding a
 * string the engine wrote. `TraceExporter.kt`'s header carries the proof that the string is exactly the
 * exporter's own answer, and the warning about what would invalidate that proof.
 */
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

        // `Charsets.UTF_8` and nothing else: the SVG declares `encoding="UTF-8"` in its own XML
        // declaration, and a platform default charset here would produce a file whose bytes disagree
        // with its own header on any handset whose locale is not UTF-8.
        is TraceExportPlan.FromTraceSvg -> traceSvg.toByteArray(Charsets.UTF_8)

        is TraceExportPlan.FromPlatformRaster -> {
            // THIS DEVICE PAINTS IT AND THIS DEVICE'S OWN ENCODER WRITES IT.
            // `TraceExportRaster.kt` carries the argument for why a picture is the platform's job on
            // both clients, and moves itself off the main thread.
            val handle = geometry
                ?: return TraceWriteOutcome.Refused(TRACE_NO_GEOMETRY_SENTENCE)
            // THE SIZE IS COMPUTED HERE AS WELL AS INSIDE THE RENDERER, AND THEY CANNOT DISAGREE
            // BECAUSE IT IS ONE FUNCTION OVER ONE PAIR OF NUMBERS. The alternative — the renderer
            // handing its size back — would put a second return value on a function whose answer is
            // bytes, to save a call that is three integer operations.
            note = tracePngReductionNote(
                documentWidth = documentWidth,
                documentHeight = documentHeight,
                size = tracePngSize(documentWidth, documentHeight),
            )
            traceRenderPngBytes(
                geometry = handle,
                documentWidth = documentWidth,
                documentHeight = documentHeight,
                // PASSED THROUGH, NEVER CHOSEN — the same rule and the same function the vector arm
                // below uses, which is what stops a PNG and a PDF of one drawing disagreeing about
                // their ground. `renderTrace`'s own default is the COMPARATOR's white and is wrong for
                // a file, so this argument is never omitted.
                background = traceExportBackground(documentBackground),
            ) ?: return TraceWriteOutcome.Refused(TRACE_PNG_MEMORY_REFUSAL)
        }

        is TraceExportPlan.FromExporter -> {
            // Unreachable through the card, which routes to Refused when the geometry is missing. Kept
            // as an answer rather than a throw, because a caller holding a stale plan crashing a screen
            // is worse than a sentence. It answers the MISSING-SHAPES sentence and not the
            // missing-writers one: this branch is reached by exactly the first of them.
            val handle = geometry
                ?: return TraceWriteOutcome.Refused(TRACE_NO_GEOMETRY_SENTENCE)
            val outcome = exporter.export(
                TraceExportRequest(
                    geometry = handle,
                    width = documentWidth,
                    height = documentHeight,
                    format = format,
                    // PASSED THROUGH, NEVER CHOSEN. See traceExportBackground for what null would do
                    // differently to the vector writers and to the rasteriser.
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
