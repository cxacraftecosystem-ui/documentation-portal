package com.fieldrepository.app.ui.trace

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * **HOW A SCREEN GETS THE TRACER, AND THE ONE THING ANDROID HAS TO DO AFTER THE ENGINE HAS RUN.**
 *
 * [rememberTraceRuntime] is the mount. [tracePlateResult] turns a finished trace into the two display
 * plates the comparator draws. Both import `android.graphics`, which is exactly why they are not in
 * `TraceRuntime.kt`: that file is otherwise reachable by a JVM unit test and these two are not.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Construction
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The tracer, which every build of this app has and every phone can run.
 *
 * ── THERE IS NOTHING TO PROBE, AND THAT IS THE POINT ──────────────────────────────────────────
 *
 * The engine is `:core-imaging`, `:core-vector`, `:core-pipeline` and `:core-export`, compiled into the
 * APK by the same Gradle build that compiles this file, so **if this app runs, it traces.** There is no
 * probe here because there is nothing a probe could discover, and there is no branch above it that
 * hides the panel on a device, because there is no such device.
 *
 * What CAN still stop one trace is memory, and that is measured per trace against the frame actually
 * being traced rather than answered once at construction — [traceMemoryRefusal], which runs after the
 * decode and before the first stage. A ceiling on the resolution is separate again and lives on
 * [TraceAvailability], which is only about how big a trace this phone should attempt.
 *
 * ── NO `DisposableEffect`, BECAUSE THERE IS NOTHING TO HOLD ───────────────────────────────────
 *
 * A [TraceEngineRuntimeImpl] owns no process, no connection and no native handle; it is a small object
 * holding two integers and an application context. `remember` keyed on that context is the whole of its
 * lifetime.
 */
@Composable
fun rememberTraceRuntime(): TraceEngineRuntime {
    val app = LocalContext.current.applicationContext
    return remember(app) { traceRuntime(app) }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The plates, after the engine has finished
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The finished result, with the two display plates when they could be made and a sentence when they
 * could not.
 *
 * ── NOTHING IN HERE MAY COST THE DRAWING ──────────────────────────────────────────────────────
 *
 * This function has three ways to fail and **none of them throws**. That is the whole shape of it, and
 * it is a correction of the obvious arrangement rather than a precaution: build the plates inside the
 * run and a display artefact nobody attaches destroys the artefact that reaches the record. On the
 * device least able to allocate two 1024 px ARGB bitmaps, an out-of-memory in a village would throw
 * away a trace that had already finished.
 *
 * So the three failures are three sentences carried on [TraceResult.plateRefusal], the SVG is returned
 * in every one of them, and the panel prints the sentence where the comparator would have been.
 *
 * ── THE SIZE CHECK STAYS, AND KEEPS ITS BETTER REMEDY ─────────────────────────────────────────
 *
 * Both plates must be the same size and *a mismatch beyond a rounding pixel is a REFUSAL rather than an
 * assumption*. The one thing that causes a mismatch is `preprocess.perspectiveCorrect`, which makes the
 * document frame the rectified page rather than the photograph — so the refusal names that control by
 * the label the panel shows for it. Only what it costs has changed: the comparison, and not the
 * drawing.
 */
internal fun tracePlateResult(
    decoded: TraceDecoded,
    rgba: ByteArray,
    sourceWidth: Int,
    sourceHeight: Int,
    frameNote: String,
    request: TraceRequest,
): TraceResult {
    var tracePlate: Bitmap? = null
    var photographPlate: Bitmap? = null
    var plateRefusal = ""

    if (decoded.width != sourceWidth || decoded.height != sourceHeight) {
        plateRefusal = traceSentence(
            TraceFailureKind.FRAME_MISMATCH,
            "${decoded.width}x${decoded.height} from a ${sourceWidth}x$sourceHeight photograph",
        )
    } else {
        val (plateWidth, plateHeight) = traceWorkingSize(
            decoded.width,
            decoded.height,
            request.plateLongEdgePx,
        )
        tracePlate = TracePlates.renderTrace(
            geometry = decoded.geometry,
            documentWidth = decoded.width,
            documentHeight = decoded.height,
            plateWidth = plateWidth,
            plateHeight = plateHeight,
        )
        photographPlate = tracePlate?.let {
            TracePlates.photographPlate(
                rgba = rgba,
                width = sourceWidth,
                height = sourceHeight,
                plateWidth = plateWidth,
                plateHeight = plateHeight,
            )
        }
        if (tracePlate == null || photographPlate == null) {
            // BOTH OR NEITHER. A comparator with one layer is not a comparator, and the half that did
            // allocate is 4.2 MB held for nothing on a phone that has just proved it is short.
            tracePlate?.recycle()
            photographPlate?.recycle()
            tracePlate = null
            photographPlate = null
            plateRefusal = TRACE_PLATE_MEMORY_REFUSAL
        }
    }

    return TraceResult(
        svg = decoded.svg,
        // Carried rather than dropped, because the PNG export paints from it — see
        // [TraceResult.geometry] for the arithmetic on what holding it costs. It is the same object the
        // plates above were painted from; nothing is copied here.
        geometry = decoded.geometry,
        tracePlate = tracePlate,
        photographPlate = photographPlate,
        plateRefusal = plateRefusal,
        width = decoded.width,
        height = decoded.height,
        workingWidth = decoded.workingWidth,
        workingHeight = decoded.workingHeight,
        shapeCount = decoded.shapeCount,
        nodeCount = decoded.nodeCount,
        stages = decoded.stages,
        totalMillis = decoded.totalMillis,
        // EVERY SENTENCE THE PIPELINE SAID, IN ORDER AND WITHOUT EXCEPTION, which `Pipeline.kt` states
        // as a requirement and names the bug it prevents: "a pipeline that silently discarded four
        // thousand paths and one that genuinely found nothing produce the same blank canvas".
        notes = decoded.notes,
        appliedParams = decoded.appliedParams,
        autoSubjectId = decoded.autoSubjectId,
        suggestedStyleId = decoded.suggestedStyleId,
        frameNote = frameNote,
    )
}
