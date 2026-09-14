package com.fieldrepository.app.ui.trace

import android.app.ActivityManager
import android.content.Context
import com.offlinetracer.export.ExportFormat
import com.offlinetracer.export.ExportOptions
import com.offlinetracer.export.SvgExport
import com.offlinetracer.imaging.RgbaImage
import com.offlinetracer.pipeline.CancellationToken
import com.offlinetracer.pipeline.CancelledException
import com.offlinetracer.pipeline.EdgeEngine
import com.offlinetracer.pipeline.Pipeline
import com.offlinetracer.pipeline.ProgressListener
import com.offlinetracer.pipeline.Stages
import com.offlinetracer.pipeline.TraceParams
import com.offlinetracer.pipeline.TraceResult as EngineTraceResult
import com.offlinetracer.vector.VecDocument
import com.offlinetracer.vector.VecLayer
import com.offlinetracer.vector.VecSeg
import com.offlinetracer.vector.VecShape
import com.offlinetracer.vector.VecStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **THE [TraceEngineRuntime] BACKED BY THE VENDORED ENGINE, IN THIS PROCESS.**
 *
 * ── WHAT THIS IS ─────────────────────────────────────────────────────────────────────────────
 *
 * `TraceEngine.kt` is the port the whole trace surface is written against, and **this is its one
 * implementation.** The engine is `:core-imaging`, `:core-vector`, `:core-pipeline` and
 * `:core-export`, compiled into this APK by the same Gradle build that compiles this file.
 * `android/UPSTREAM-MANIFEST-KOTLIN.txt` hashes every vendored source, and `:core-pipeline`'s own
 * `ParityTest` replays the shared fixtures under `docs/fixtures` through the Kotlin engine and holds it
 * to the TypeScript's numbers. (A glob is spelled out rather than written in these comments, because
 * Kotlin block comments NEST and a slash-star inside one opens a second comment that never closes.)
 *
 * This file imports no composable, no panel and no export card.
 *
 * ── THE COST OF RUNNING THE ENGINE IN THIS PROCESS, WHICH IS THE ONE THING THAT CAN HURT ──────
 *
 * There is no isolate and no second process: the peak working set lives in THIS app's Java heap,
 * beside the composition, the offline outbox and every bitmap a record screen is holding. That is the
 * one thing about this arrangement that can hurt somebody, so it is measured before every trace rather
 * than hoped about.
 *
 * **THE ARITHMETIC.** The input is bounded twice over: [TRACE_DECODE_MAX_EDGE_PX] caps any photograph
 * at 4096 on its long edge, and the panel's resolution control caps what the stages run at. Take a
 * 1600x1200 frame — 1,920,000 pixels:
 *
 *  - **This file's own buffers: 15.4 MB.** `TracePlates.readRgba` produces one RGBA `ByteArray`
 *    (4 B/px, 7.7 MB) and [traceImageOf] turns it into the packed-ARGB `IntArray` an [RgbaImage] is
 *    (4 B/px, 7.7 MB). Both are live for the whole trace: the engine holds the second, and the first is
 *    what the comparison plate is painted from afterwards.
 *  - **The engine's working set: 40–152 bytes per WORKING pixel**, which is [TRACE_ENGINE_BPP]. Those
 *    numbers are measured figures at exactly this size: adaptive +93 MB, LOG +76 MB, flow +72 MB,
 *    **Canny +278 MB**. They were measured on V8, and they are used here for the JVM because the two
 *    engines allocate the same planes at the same sizes — a `Float32Array` and a `FloatArray` are both
 *    4 B/px, a `Uint8Array` and a `BooleanArray` are both 1 B/px — so the count of buffers is a
 *    property of the pipeline rather than of the language. Structural agreement, checked: the stages
 *    hold three `RgbaImage` (12 B/px), five `GrayF` plus the edge response and the distance transform
 *    (28 B/px) and six `Mask` (6 B/px) simultaneously, which is 46 B/px — beside adaptive's measured 51.
 *  - **The result: up to [TRACE_ENGINE_RESULT_BYTES], 24 MB.** The geometry's flat arrays (the worst
 *    measured serialised result was 2.88 MiB at 20,975 shapes), the SVG string, and the `ByteArray`
 *    `SvgExport` returns before it is decoded into that string.
 *  - **The two display plates are NOT on this heap.** `minSdk = 26` and Android 8.0 moved `Bitmap`
 *    pixel storage to native memory, so the 8.4 MB pair costs the process without costing
 *    `Runtime.maxMemory()`. They are named here because they are real, and excluded from the estimate
 *    because counting them against the Java heap would refuse traces that fit.
 *
 * So a full-resolution trace of that input costs **112 MB with the shipped flow default and 317 MB with
 * Canny**, in this app's heap — 14.7 + 73.2 + 24, and 14.7 + 278.3 + 24, in MiB. `TraceRuntimeTest`
 * pins both figures, so this paragraph cannot rot quietly.
 *
 * `AndroidManifest.xml` declares `android:largeHeap`, which on most handsets is the difference between
 * `dalvik.vm.heapgrowthlimit` (typically 128–256 MB) and `dalvik.vm.heapsize` (typically 256–512 MB) —
 * but "typically" is not a bound, so nothing here assumes it. [traceHeapBytes] asks the running VM what
 * it actually has, every time.
 *
 * **AND IT IS BOUNDED, NOT JUST STATED.** [traceMemoryRefusal] runs after the decode and before the
 * first stage, compares the estimate above against what this VM can still hand out, and refuses in a
 * sentence naming both numbers and the two remedies. A refusal costs one re-tap at a lower resolution;
 * an `OutOfMemoryError` at stage fourteen costs the trace, and on a phone that is also holding an
 * unsaved form it can cost more than that.
 *
 * ── EVERY NOTE REACHES THE SCREEN, INCLUDING THE ONE THIS FILE ADDS ───────────────────────────
 *
 * `Pipeline.kt` calls rendering `TraceResult.notes` a REQUIREMENT and names the bug it prevents: "a
 * pipeline that silently discarded four thousand paths and one that genuinely found nothing produce the
 * same blank canvas". They are carried into [TraceDecoded.notes] unabridged and in order, and the SVG
 * writer's own cap ([TRACE_MAX_SHAPES]) appends one more sentence when it bites — the portal's own
 * sentence, copied rather than re-worded, so one cut is not described two ways in one archive.
 *
 * ── WHAT IS DELIBERATELY NOT HERE ─────────────────────────────────────────────────────────────
 *
 * No clamp table, no merge, no preset table and no flattener. `TraceEngineParams.kt` owns the
 * translation between the panel's flat [TraceValues] and the engine's nested `TraceParams`;
 * `TraceEnginePresets.kt` owns the two registers and the two `apply` verbs. This file is the
 * composition of those two with `Pipeline.run`, the pixels and the plates, and nothing else.
 *
 * It also never writes. The one derived file is written by a button in the panel; a runtime that wrote
 * would make a record assert that a named human produced a value no human pressed for.
 */

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 1. IDENTITY — which writer wrote the file, and which stage list ran
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * Which SVG writer produced [TraceResult.svg] on this route.
 *
 * Reported so a bug report can name it without opening the file, because **this and the portal do not
 * write the same bytes** and that is the first thing anyone comparing two drawings needs to know. See
 * [traceSvgOf] for what differs and what does not.
 */
const val TRACE_SVG_WRITER: String = "core-export/SvgExport#export"

/**
 * The engine's own stage list, read from `Stages.ALL` rather than transcribed.
 *
 * **NINETEEN, WHERE [TRACE_STAGES] HAS TWELVE, AND THAT IS A REAL DIVERGENCE BETWEEN THE TWO VENDORED
 * ENGINES.** The TypeScript's `STAGES` fuses several steps this one reports separately: its `prepare`
 * is `orient` + `perspective` + `downscale`, its `cleanup` is `binarise` + `morphology` + `blobs` +
 * `bridge`, and it spells three ids differently (`gray`/`grayscale`, `vectorize`/`vectorise`,
 * `document`/`assemble`).
 *
 * Most of the surface absorbs that correctly and by design. The progress row renders
 * [TraceProgress.label] — the engine's own string — and never the table's;
 * [TraceProgressWeights.fractionAt] falls back to the fraction the engine sent for an id it does not
 * know; and after one completed run [TraceProgressWeights.from] rebuilds the weights from the engine's
 * OWN ids and timings, so this route's nineteen stages get correctly measured weights with nothing
 * edited.
 *
 * **[traceProgressSentence] does NOT absorb it by itself**, which is why the panel passes this constant
 * rather than letting the default apply. Seven of these nineteen ids collide with the twelve-row table
 * — `denoise`, `contrast`, `matte`, `crop`, `edge`, `skeleton`, `distance` — and five of the seven sit
 * at a different position in it, so a screen reader would announce a wrong stage number the moment a
 * trace ran. `TraceRuntimeTest` pins both the divergence and the fix, so neither claim can rot.
 */
val TRACE_ENGINE_STAGES: List<TraceStage> =
    Stages.ALL.map { TraceStage(id = it.id, label = it.label) }

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 2. MEMORY — measured before the first stage, refused in a sentence
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * Peak working-set bytes per WORKING pixel, per edge engine. See the file header for where they come
 * from and why V8's measurements are used for the JVM.
 *
 * Each is the measured figure at 1600x1200 (1,920,000 px) divided by that pixel count and rounded up:
 * Canny 278 MiB → 152, adaptive 93 MiB → 51, LOG 76 MiB → 42, flow 72 MiB → 40.
 *
 * **XDOG AND MODEL WERE NOT MEASURED AND TAKE THE LARGEST MEASURED VALUE.** That is deliberately
 * pessimistic and it is this repository's own discipline for an unknown — [traceCeilings] takes the
 * cautious half for a phone whose memory read failed, on the argument that "a handset that would have
 * said it was small must not be promoted by a lookup that failed". Being wrong here costs a refusal
 * with a named remedy at the very top of the resolution range; being wrong the other way costs an
 * `OutOfMemoryError` in a village. Neither is free, and only one is recoverable. The remedy is to
 * measure them on a device and replace two entries.
 */
val TRACE_ENGINE_BPP: Map<EdgeEngine, Int> = linkedMapOf(
    EdgeEngine.CANNY to 152,
    EdgeEngine.ADAPTIVE to 51,
    EdgeEngine.LOG to 42,
    EdgeEngine.FDOG to 40,
    EdgeEngine.XDOG to 152,
    EdgeEngine.MODEL to 152,
)

/** Bytes per pixel this file's own two input buffers cost: one RGBA byte array plus one ARGB int array. */
const val TRACE_ENGINE_INPUT_BPP: Int = 8

/** Headroom for the finished geometry, the SVG string and the byte array it is decoded from. */
const val TRACE_ENGINE_RESULT_BYTES: Long = 24L * 1024L * 1024L

/**
 * Java heap left for the rest of the app while a trace runs.
 *
 * The composition, the offline outbox, the record form somebody is halfway through and whatever else is
 * open behind this panel. A trace that fits only by taking all of it is a trace that finishes and then
 * kills the screen it was going to be shown on.
 */
const val TRACE_ENGINE_HEAP_RESERVE_BYTES: Long = 32L * 1024L * 1024L

/**
 * The Java heap this VM could still hand out, in bytes.
 *
 * `maxMemory` is the ceiling ART will grow to for this process — `android:largeHeap` is what makes it
 * the large one — and `total - free` is what is already committed and live. The difference is what a
 * trace may ask for. It is read at the moment of asking rather than once at construction, because
 * somebody who has been photographing a workshop for ten minutes has a fuller heap than somebody who
 * has just opened the app, and the answer for the two is genuinely different.
 */
fun traceHeapBytes(runtime: Runtime = Runtime.getRuntime()): Long =
    runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())

/**
 * What one trace will cost this heap, in bytes.
 *
 * @param sourcePixels the photograph as the engine is handed it, after any crop
 * @param workingPixels what the pipeline will actually run the stages at, i.e. [sourcePixels] scaled
 *   down to `preprocess.workingLongEdge`. The stages dominate and they run at the working size, so
 *   using the source size here would refuse traces the downscale makes comfortable.
 */
fun tracePeakBytes(sourcePixels: Long, workingPixels: Long, engine: EdgeEngine): Long {
    val perPixel = TRACE_ENGINE_BPP[engine] ?: TRACE_ENGINE_BPP.values.max()
    return sourcePixels * TRACE_ENGINE_INPUT_BPP + workingPixels * perPixel + TRACE_ENGINE_RESULT_BYTES
}

/**
 * The working pixel count a trace of [width] x [height] will run at under [params].
 *
 * The pipeline's own downscale rule: the working long edge is the smaller of what was asked for and
 * what the source has, because the pipeline never upscales.
 */
fun traceWorkingPixels(width: Int, height: Int, params: TraceParams): Long {
    val (w, h) = traceWorkingSize(width, height, params.preprocess.workingLongEdge)
    return w.toLong() * h.toLong()
}

/**
 * @return the sentence to refuse this trace with, or null when it fits.
 *
 * REFUSED BEFORE THE FIRST STAGE AND NOT AFTER THE LAST. The check is worth having only if it runs
 * while nothing has been allocated and nothing has been computed — a refusal after twelve seconds of
 * arithmetic is a failure with extra steps.
 *
 * It errs high by one buffer, knowingly. The caller reads [traceHeapBytes] with the RGBA bytes already
 * allocated — they are what the crop was taken out of — while [tracePeakBytes] counts them again in its
 * 8 bytes per source pixel. That is 7.7 MB of double-counting at the input cap, in the direction of
 * refusing a trace that would just have fitted rather than starting one that will not.
 *
 * The sentence carries both numbers because "not enough memory" without them cannot be acted on, and it
 * names the two remedies that actually change the arithmetic: the resolution multiplies
 * [workingPixels], and the edge engine multiplies the bytes each of them costs. Closing other apps is
 * third because it moves the smallest term — this app's heap ceiling does not grow when another app
 * closes; only what is already live inside it can shrink.
 */
fun traceMemoryRefusal(
    sourcePixels: Long,
    workingPixels: Long,
    engine: EdgeEngine,
    heapBytes: Long,
): String? {
    val peak = tracePeakBytes(sourcePixels, workingPixels, engine)
    if (peak + TRACE_ENGINE_HEAP_RESERVE_BYTES <= heapBytes) return null
    val need = traceMegabytes(peak)
    val have = traceMegabytes(heapBytes - TRACE_ENGINE_HEAP_RESERVE_BYTES)
    return "This trace needs about $need MB of memory and this phone can spare about $have MB right " +
        "now, so it has not been started rather than started and lost part-way. Set the trace " +
        "resolution lower, or choose a different edge engine — Canny needs about three times the " +
        "memory of the others — and close other apps if you can."
}

/**
 * Whole megabytes, never negative, for a sentence a person reads.
 *
 * Rounded rather than truncated so the number in the refusal is the same number the file header states
 * for the same trace — 317 and not 316. A sentence and a comment that disagree by one about the same
 * arithmetic is a reader's afternoon.
 */
private fun traceMegabytes(bytes: Long): Long =
    if (bytes <= 0L) 0L else Math.round(bytes / (1024.0 * 1024.0))

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 3. PIXELS IN — the engine's packing, and nobody else's
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The bytes `TracePlates.readRgba` produced, as the [RgbaImage] the engine takes.
 *
 * **THE EXACT INVERSE OF [traceArgbRowToRgba], AND THAT IS ASSERTED RATHER THAN INTENDED.** That
 * function's own docblock says why it is the piece most able to be quietly wrong: "swap two channels
 * and the engine still traces — it traces a picture with red and blue exchanged, which on a pencil
 * sketch on cream paper looks very nearly right and comes out quietly different from the portal's
 * answer forever". `TraceRuntimeTest` round-trips a block of pixels through both functions and demands
 * the original integers back, so the pair cannot drift apart.
 *
 * `RgbaImage.pixels` holds `(a shl 24) or (r shl 16) or (g shl 8) or b`, which the engine documents as
 * "byte-identical in layout to `Bitmap.getPixels`", so the ints this produces are the ints the bitmap
 * held. The RGBA
 * byte order in between is not a detour for its own sake: going bitmap → bytes → ints keeps ONE crop
 * implementation ([traceCropRgba]) and ONE plate builder (`TracePlates.photographPlate`) serving the
 * cropped and uncropped paths alike, at the cost of the second buffer counted in the file header.
 *
 * @throws TraceHostFailure when [rgba] is shorter than `width * height * 4`
 */
fun traceImageOf(rgba: ByteArray, width: Int, height: Int): RgbaImage {
    if (width < 1 || height < 1) {
        throw TraceHostFailure(TraceFailureKind.IMAGE_EMPTY, "${width}x$height")
    }
    val count = width.toLong() * height.toLong()
    if (rgba.size.toLong() < count * 4L) {
        throw TraceHostFailure(
            TraceFailureKind.ENGINE_ERROR,
            "${rgba.size} bytes for a ${width}x$height image, which needs ${count * 4L}",
        )
    }
    val pixels = IntArray(count.toInt())
    var b = 0
    for (i in pixels.indices) {
        val r = rgba[b].toInt() and 0xFF
        val g = rgba[b + 1].toInt() and 0xFF
        val bl = rgba[b + 2].toInt() and 0xFF
        val a = rgba[b + 3].toInt() and 0xFF
        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or bl
        b += 4
    }
    return RgbaImage(width, height, pixels)
}

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 4. GEOMETRY OUT — the worker's own serialisation, in Kotlin
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * A finished [VecDocument] as the flat arrays [TraceGeometry] mirrors.
 *
 * **A LINE-FOR-LINE PORT OF THE UPSTREAM WORKER'S `serializeGeometry`**, because `TracePlates` and the
 * PNG export walk these arrays and must find the same layout whichever engine filled them: shapes
 * concatenated across layers in layer order, a shape's coordinate run beginning with its start point,
 * two floats per line, four per quad, six per cubic, and `starts` arrays one longer than the shape
 * count so an extent is a subtraction.
 *
 * The style table is de-duplicated exactly as the worker's is — it keys on all eight fields, so a run
 * of 50,000 identically-styled paths is one entry. The worker builds a string key out of the eight;
 * this uses the `VecStyle` data class itself as the map key, which compares the same eight fields by
 * value. Same partition, no string per shape.
 *
 * NOT A RE-MODEL. [TraceGeometry]'s header forbids rebuilding the geometry into Kotlin path objects —
 * "the moment there is a Kotlin opinion about what a cubic segment IS, there is something for the two
 * clients to disagree about". This copies numbers into arrays and forms no opinion.
 */
fun traceGeometryOf(doc: VecDocument): TraceGeometry {
    val shapes = ArrayList<VecShape>(doc.shapeCount())
    for (layer in doc.layers) shapes.addAll(layer.shapes)

    var segTotal = 0
    var coordTotal = 0
    for (shape in shapes) {
        val segs = shape.path.segments
        segTotal += segs.size
        coordTotal += 2
        for (seg in segs) coordTotal += traceCoordsOf(seg)
    }

    val coords = FloatArray(coordTotal)
    val verbs = ByteArray(segTotal)
    val verbStarts = IntArray(shapes.size + 1)
    val coordStarts = IntArray(shapes.size + 1)
    val closed = ByteArray(shapes.size)
    val styleIndex = IntArray(shapes.size)
    val styleTable = ArrayList<TraceStyle>()
    val styleKeys = LinkedHashMap<VecStyle, Int>()

    var v = 0
    var c = 0
    for (s in shapes.indices) {
        val shape = shapes[s]
        verbStarts[s] = v
        coordStarts[s] = c
        closed[s] = if (shape.path.closed) 1 else 0

        styleIndex[s] = styleKeys.getOrPut(shape.style) {
            styleTable.add(traceStyleOf(shape.style))
            styleTable.size - 1
        }

        coords[c++] = shape.path.start.x
        coords[c++] = shape.path.start.y
        for (seg in shape.path.segments) {
            when (seg) {
                is VecSeg.Line -> {
                    verbs[v++] = TRACE_VERB_LINE
                    coords[c++] = seg.to.x
                    coords[c++] = seg.to.y
                }

                is VecSeg.Quad -> {
                    verbs[v++] = TRACE_VERB_QUAD
                    coords[c++] = seg.c.x
                    coords[c++] = seg.c.y
                    coords[c++] = seg.to.x
                    coords[c++] = seg.to.y
                }

                is VecSeg.Cubic -> {
                    verbs[v++] = TRACE_VERB_CUBIC
                    coords[c++] = seg.c1.x
                    coords[c++] = seg.c1.y
                    coords[c++] = seg.c2.x
                    coords[c++] = seg.c2.y
                    coords[c++] = seg.to.x
                    coords[c++] = seg.to.y
                }
            }
        }
    }
    verbStarts[shapes.size] = v
    coordStarts[shapes.size] = c

    return TraceGeometry(
        coords = coords,
        verbs = verbs,
        verbStarts = verbStarts,
        coordStarts = coordStarts,
        closed = closed,
        styleTable = styleTable,
        styleIndex = styleIndex,
    )
}

/** Coordinates one segment contributes. The same two/four/six the worker counts. */
private fun traceCoordsOf(seg: VecSeg): Int = when (seg) {
    is VecSeg.Line -> 2
    is VecSeg.Quad -> 4
    is VecSeg.Cubic -> 6
}

/**
 * One `VecStyle` as the mirror the plates read.
 *
 * The three enums cross as their NAMES, which is what [TraceStyle] asks for and why: the vendored
 * TypeScript's `FillRule`, `LineCap` and `LineJoin` are string enums whose values equal their names, so
 * `LineCap.ROUND.name` here is the `"ROUND"` the other client sends, and `TracePlates` maps both with
 * one `when`.
 */
private fun traceStyleOf(style: VecStyle): TraceStyle = TraceStyle(
    stroke = style.stroke,
    strokeWidth = style.strokeWidth,
    fill = style.fill,
    fillRule = style.fillRule.name,
    cap = style.cap.name,
    join = style.join.name,
    miterLimit = style.miterLimit,
    opacity = style.opacity,
)

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 5. THE DOCUMENT OUT — the vendored writer, with the branding off and the cap reported
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * How many shapes one SVG may carry. The web's `MAX_SHAPES_PER_FILE`, to the digit.
 *
 * The number is the portal's because the two clients' files land in one archive and a ceiling that
 * differed between them would be a drawing that fits on a laptop and is cut on a handset. It is high
 * enough that a real sketch never meets it and a trace of a photograph of gravel does.
 */
const val TRACE_MAX_SHAPES: Int = 200000

/** What [traceSvgOf] produced: the file, how much of the drawing reached it, and the cut. */
class TraceSvg(
    val svg: String,
    val shapesWritten: Int,
    /** Non-null exactly when [TRACE_MAX_SHAPES] truncated the drawing. Ready to show. */
    val truncationNote: String?,
)

/**
 * The vector document, as the vendored writer spells it.
 *
 * ── THREE OPTIONS, AND EACH ONE IS LOAD-BEARING ───────────────────────────────────────────────
 *
 * `includeMetadata = false` — **this is the one that matters.** `SvgExport` stamps a
 * `<title>Offline Tracer export</title>` and a `<desc>` into every file it writes by default. That is
 * another product's branding, which must not reach an archived record. The portal's own writer emits
 * neither. Turning it off is not a formatting preference.
 *
 * `flattenLayers = true` — the pipeline assembles exactly one layer, and the portal's writer emits bare
 * `<path>` elements with no group at all. Flattening a single visible layer at full opacity discards
 * nothing and makes the two files the same shape.
 *
 * `precision = 2` — both writers' own default, and the parity harness's. Coordinates run to 4096 with
 * sub-pixel meaning; two places resolve a hundredth of a pixel.
 *
 * ── WHAT STILL DIFFERS FROM THE FILE THE PORTAL ATTACHES, MEASURED NOT GUESSED ────────────────
 *
 * The portal attaches its own page-side `buildSvg`. This route cannot use it: it is TypeScript, and the
 * alternatives were re-typing it in Kotlin — a THIRD speller of the `d` attribute — or using the
 * vendored writer, which is what is manifest-pinned. So the bytes differ, in ways that have been
 * measured between these same two writers:
 *
 *     buildSvg     M7.25 1 C7.83 1.08 8.42 1.17 9 1.25 C9 100.08 … 7.25 1 Z
 *     SvgWriter    M7.25 1C7.83 1.08 8.42 1.17 9 1.25 9 100.08 … 7.25 1Z
 *
 * — a space after the command letter, an explicit `C` on every cubic where the path-data writer elides
 * the letter for a run of one type, and a space before `Z`. Beyond that: the XML declaration carries
 * `standalone="no"`, the root element carries `version="1.1"` and `px` units, every `<path>` carries
 * the vectoriser's own id, and the elements are indented two spaces. **None of it changes the
 * drawing**: the parity work parses both writers' `d` strings back through the engine's own reader and
 * asserts identical start points, identical segments and identical closure, shape for shape. What it
 * does mean is that a byte comparison of a handset's file against a laptop's will differ while the
 * geometry does not, and anyone diffing two SVGs needs [TRACE_SVG_WRITER] to know which they are
 * holding.
 *
 * ── THE CAP IS REPORTED, NEVER SILENT ─────────────────────────────────────────────────────────
 *
 * `SvgExport` has no ceiling of its own, so the document is trimmed to [TRACE_MAX_SHAPES] before it is
 * written and the cut comes back as a sentence the caller appends to the notes — the portal's own
 * sentence, copied rather than re-worded, because two phrasings of one cut read as two different
 * faults. The geometry is NOT trimmed: the comparison plate still shows the whole drawing, and only the
 * file is cut.
 */
fun traceSvgOf(doc: VecDocument): TraceSvg {
    val shapeCount = doc.shapeCount()
    val written = if (shapeCount > TRACE_MAX_SHAPES) TRACE_MAX_SHAPES else shapeCount
    val target = if (written == shapeCount) doc else traceTrimmed(doc, written)
    val bytes = SvgExport.export(
        target,
        ExportOptions(
            format = ExportFormat.SVG,
            precision = 2,
            includeMetadata = false,
            flattenLayers = true,
        ),
    )
    return TraceSvg(
        svg = String(bytes, Charsets.UTF_8),
        shapesWritten = written,
        truncationNote = traceTruncationNote(shapeCount, written),
    )
}

/** [doc] carrying only its first [limit] shapes, in the order [traceGeometryOf] walks them. */
private fun traceTrimmed(doc: VecDocument, limit: Int): VecDocument {
    var left = limit
    val layers = ArrayList<VecLayer>(doc.layers.size)
    for (layer in doc.layers) {
        if (left <= 0) break
        if (layer.shapes.size <= left) {
            layers.add(layer)
            left -= layer.shapes.size
        } else {
            layers.add(layer.copy(shapes = ArrayList(layer.shapes.subList(0, left))))
            left = 0
        }
    }
    return VecDocument(doc.width, doc.height, layers, doc.background)
}

/**
 * The sentence shown when the shape ceiling cut a drawing short, or null when it did not.
 *
 * The web's `truncationNoteFor`, word for word, including the Indian digit grouping its
 * `toLocaleString("en-IN")` produces and the two control labels it names — both of which exist on this
 * panel verbatim, so the remedy it offers points at controls the researcher can actually see.
 */
fun traceTruncationNote(shapeCount: Int, shapesWritten: Int): String? {
    if (shapeCount <= shapesWritten) return null
    val count = traceCount(shapeCount)
    val kept = traceCount(shapesWritten)
    return "This drawing has $count separate paths and the file holds the first $kept. Raise " +
        "“Minimum speck” or “Simplify” and trace again to get a drawing that fits."
}

/**
 * A count as the portal's `toLocaleString("en-IN")` spells it — 2,00,000 and not 200,000.
 *
 * ── WRITTEN OUT RATHER THAN DELEGATED TO `NumberFormat`, WHICH WAS TRIED AND IS WRONG ─────────
 *
 * `NumberFormat.getIntegerInstance(Locale.forLanguageTag("en-IN")).format(250_000)` returns
 * **"250,000"** on a desktop JDK and **"2,50,000"** on Android, whose formatter is ICU. Measured, not
 * assumed. That is the worst shape a formatting bug can take — the sentence a researcher reads would
 * differ from the sentence the test that guards it reads, and it would differ again between two phones
 * with different locale data. `:core-export` declines the platform formatters for exactly this reason
 * ("that failure only reproduces on the affected device, which makes it exactly the kind of bug that
 * ships").
 *
 * The rule is the Indian system's own: the last three digits, then twos.
 */
private fun traceCount(value: Int): String {
    if (value < 1000) return value.toString()
    val digits = value.toString()
    val head = digits.substring(0, digits.length - 3)
    val out = StringBuilder()
    var i = head.length
    while (i > 2) {
        out.insert(0, "," + head.substring(i - 2, i))
        i -= 2
    }
    out.insert(0, head.substring(0, i))
    return out.append(',').append(digits.substring(digits.length - 3)).toString()
}

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 6. ONE TRACE — off the main thread, cancelled for real, decoded into the port's own shapes
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The working long edge a preview runs at. The web worker's `PREVIEW_LONG_EDGE`, and the engine's own
 * `Preview.DEFAULT_LONG_EDGE` — the two vendored engines already agree about this number.
 */
const val TRACE_PREVIEW_LONG_EDGE: Int = 720

/**
 * [base] as a preview runs it: the working long edge lowered, and NOTHING ELSE TOUCHED.
 *
 * ── WHY THIS IS NOT `Preview.runPreview`, WHICH IS THE ONE PLACE THIS FILE DECLINES THE ────────
 * ── VENDORED KOTLIN AND FOLLOWS THE VENDORED TYPESCRIPT INSTEAD ────────────────────────────────
 *
 * The two vendored engines implement previewing differently and this is not a spelling difference. The
 * TypeScript lowers `workingLongEdge` and re-sanitises, and that is all it does. `:core-pipeline`'s
 * `Preview.scaleToPreview` additionally rescales THIRTEEN geometric knobs — `medianRadius`,
 * `matte.feather`, `adaptiveRadius`, `minBlobArea`, `closeRadius`, `openRadius`, `maxGap`,
 * `pruneSpurs`, `fillHolesUpTo`, `simplify`, `fitError`, `strokeWidth`, `minPathLength` — and its
 * header argues the case well: a 24 px blob is three pixels at a third of the resolution, so a preview
 * that did not scale it drops things the export keeps.
 *
 * **THE PANEL IS WHAT DECIDES THIS, AND IT DECIDES IT AGAINST THE BETTER IMPLEMENTATION.** The panel
 * adopts `appliedParams` into its controls after every run, for a reason of its own that is correct —
 * "a dock that says one thing beside a drawing produced by another" — and `Pipeline.run` reports the
 * parameters the stages RAN with. Put those two together with `scaleToPreview` and one preview silently
 * rewrites thirteen of the researcher's settings to preview-sized values — `minBlobArea` 24 → 3,
 * `strokeWidth` 1.5 → 0.5 — and the full-resolution trace they press next runs with them and attaches
 * the result.
 *
 * That is the whole argument, and it is a real cost: this route's previews therefore drop specks the
 * export keeps, which is the thing `Preview.kt`'s header exists to prevent. The better fix is for the
 * panel to stop adopting a PREVIEW's applied parameters — a preview is a rehearsal — after which this
 * function should become a call to `Preview.runPreview` and this comment should be deleted. It is
 * written down rather than done because it changes what a researcher's controls say after a preview,
 * which is an owner's call and not a port's.
 */
fun tracePreviewParams(base: TraceParams): TraceParams = base
    .copy(preprocess = base.preprocess.copy(workingLongEdge = TRACE_PREVIEW_LONG_EDGE))
    .sanitized()

/**
 * Run one trace on [src] and hand back everything the port carries except the plates.
 *
 * ── OFF THE MAIN THREAD, AND THE PROGRESS EVENTS BACK ONTO THE CALLER'S ───────────────────────
 *
 * `Pipeline.run` is seconds of solid array arithmetic on the calling thread — it is not a suspend
 * function and it never yields — so it runs inside `withContext(Dispatchers.Default)` and the
 * `ProgressListener` fires on that pool's thread. The events are then handed back through an unbounded
 * [Channel] to a pump coroutine launched in the CALLER'S context: whatever dispatcher called this is
 * where [onProgress] is invoked, which is how the runtime below satisfies
 * [TraceEngineRuntime.trace]'s "called on the main thread" without this function knowing what a main
 * thread is (and is why a JVM test can drive it with no looper at all).
 *
 * The channel also fixes the ordering a bare `launch(Main)` per event would not: it is drained in order
 * by one consumer, and it is closed and JOINED before this function returns, so no progress event can
 * land after the result it belongs to. A fire-and-forget dispatch can, and a progress row that appears
 * after the drawing is the kind of bug nobody can reproduce.
 *
 * ── ONE EVENT PER STAGE, AT ITS START, WHICH IS THE OTHER ENGINE'S SHAPE ──────────────────────
 *
 * This engine's `ProgressListener` is called TWICE per stage — once at the start with the fraction of
 * stages already finished and once at the end — where the TypeScript posts once. The end event carries
 * the same id and label as the start it follows, so forwarding both would show every stage twice and
 * would make the fraction mean two different things. Only the first event for a given id is forwarded,
 * which reproduces the other runtime's `index/n` at the start of each stage exactly.
 * [TraceProgress.fraction] therefore still never reaches 1.0 — 18/19 here, 11/12 there.
 *
 * ── AND CANCELLED FOR REAL ────────────────────────────────────────────────────────────────────
 *
 * A guard coroutine on `Dispatchers.Unconfined` parks in `awaitCancellation` and calls
 * `CancellationToken.cancel()` from its `finally`. Unconfined is the point: the guard resumes on
 * whatever thread cancelled the job, with no dispatch to wait for, so the token is set the instant the
 * coroutine is cancelled even though every thread in the Default pool is busy tracing. The engine then
 * unwinds at its next check — a stage boundary, or a sub-step inside a long stage — and throws
 * `CancelledException`, which is converted to the coroutine cancellation the port promises.
 *
 * Worst-case latency is one sub-step, which for `edge` at full resolution is seconds; the surface says
 * "Stopping…" rather than disappearing for exactly that reason. It cannot leave the engine mid-write,
 * because the engine never interrupts — it only checks.
 *
 * @throws CancellationException when the calling job is cancelled. Not a failure.
 * @throws TraceHostFailure with a sentence for anything else.
 */
suspend fun traceRunEngine(
    src: RgbaImage,
    params: TraceParams,
    preview: Boolean,
    onProgress: suspend (TraceProgress) -> Unit,
): TraceDecoded = coroutineScope {
    val events = Channel<TraceProgress>(Channel.UNLIMITED)
    val pump = launch { for (event in events) onProgress(event) }
    val result = try {
        traceCallPipeline(src, params, preview, events)
    } finally {
        events.close()
    }
    pump.join()
    traceDecodedOf(result)
}

/** The engine call itself: the dispatcher, the cancellation guard and the two failure conversions. */
private suspend fun traceCallPipeline(
    src: RgbaImage,
    params: TraceParams,
    preview: Boolean,
    events: Channel<TraceProgress>,
): EngineTraceResult {
    val token = CancellationToken()
    // Only the FIRST event for an id is forwarded — see the caller's docblock. Written and read on the
    // one thread the engine calls the listener from, so it needs no synchronisation.
    var lastStageId: String? = null
    val listener = if (preview) {
        // No listener at all for a preview, because the vendored worker passes none to its preview
        // entry point and `TraceEngineRuntime.trace` repeats the promise: a preview that reported
        // progress would be this runtime inventing events the other one does not send.
        null
    } else {
        ProgressListener { stageId, label, fraction ->
            if (stageId != lastStageId) {
                lastStageId = stageId
                events.trySend(TraceProgress(stageId = stageId, label = label, fraction = fraction))
            }
        }
    }
    val effective = if (preview) tracePreviewParams(params) else params

    return try {
        withContext(Dispatchers.Default) {
            val guard = launch(Dispatchers.Unconfined) {
                try {
                    awaitCancellation()
                } finally {
                    token.cancel()
                }
            }
            try {
                // Classification off for a preview: the classifier answers from its own 512 px proxy,
                // so it would return the same profile for the same source and cost the same on every
                // keystroke. The TypeScript's own preview passes the same false.
                Pipeline.run(src, effective, listener, token, classify = !preview)
            } finally {
                // Ends the guard. `withContext` does not return until its children complete, so this is
                // not optional — and cancelling the token after a finished run is inert.
                guard.cancel()
            }
        }
    } catch (cancelled: CancelledException) {
        // The engine unwound because the token was set. Almost always that is this coroutine being
        // cancelled, and `ensureActive` turns it into the CancellationException the port promises — a
        // cancel must never reach the user as a failure. If the job is somehow still active, something
        // set the token that had no business doing so, and that is a fault worth a sentence rather than
        // a silent empty drawing.
        currentCoroutineContext().ensureActive()
        throw TraceHostFailure(
            TraceFailureKind.ENGINE_ERROR,
            "the trace stopped before it finished, and nothing had asked it to stop",
            cancelled,
        )
    } catch (exhausted: OutOfMemoryError) {
        // CAUGHT DELIBERATELY, WHICH IS NOT THE USUAL RULE. The pre-flight check above should make this
        // unreachable, but its per-pixel figures were measured on another runtime's heap, so "should"
        // is doing work there. The alternative to catching it is the process dying with an unsaved form
        // on screen, and this frame owns several tens of megabytes it is about to drop — which is the
        // one situation where an OutOfMemoryError is genuinely recoverable.
        throw TraceHostFailure(TraceFailureKind.OUT_OF_MEMORY, "", exhausted)
    }
}

/**
 * A finished engine result as the port's own [TraceDecoded].
 *
 * Every field is carried, nothing is recomputed, and the two that could be taken from the request
 * instead are deliberately taken from the result: `appliedParams`, because auto-detection runs before
 * the first stage and the controls have to show what ran; and the document's own frame, which is the
 * rectified page rather than the photograph when perspective correction fired — the one thing that
 * makes the comparator refuse, and it must refuse rather than lay two frames over each other.
 */
fun traceDecodedOf(result: EngineTraceResult): TraceDecoded {
    val doc = result.document
    val svg = traceSvgOf(doc)
    return TraceDecoded(
        svg = svg.svg,
        geometry = traceGeometryOf(doc),
        background = doc.background,
        width = result.sourceWidth,
        height = result.sourceHeight,
        workingWidth = result.workingWidth,
        workingHeight = result.workingHeight,
        shapeCount = doc.shapeCount(),
        nodeCount = doc.nodeCount(),
        stages = result.stages.map { TraceStageTiming(it.id, it.label, it.millis) },
        totalMillis = result.totalMillis,
        // EVERY sentence the pipeline said, then the writer's own cut if there was one. A truncated
        // drawing with nothing on screen to say so is the bug this project takes most seriously.
        notes = if (svg.truncationNote == null) result.notes else result.notes + svg.truncationNote,
        appliedParams = traceValuesOfParams(result.appliedParams),
        autoSubjectId = result.autoSubjectId,
        // ALWAYS EMPTY ON THIS ROUTE, AND THAT IS A FEATURE THIS ENGINE DOES NOT HAVE RATHER THAN A
        // FIELD THIS FILE FORGOT. See TRACE_NO_SUGGESTION_NOTE.
        suggestedStyleId = "",
    )
}

/**
 * Why [TraceResult.suggestedStyleId] is empty on this runtime, in one place, so nobody re-derives it.
 *
 * ── THE TWO VENDORED CLASSIFIERS PRODUCE DIFFERENT THINGS UNDER ONE FIELD NAME ────────────────
 *
 * The TypeScript's `SerializedProfile.suggestion` is documented as "a styles preset id. Never empty",
 * chosen by a five-line ladder over the source kind (line art → `single-stroke`, flat graphic →
 * `stencil`, textured → `minimal`, smooth object → `silhouette`, otherwise `clean-line`). The vendored
 * Kotlin's `Classify.SourceProfile.suggestion` is "**One human-readable sentence** naming the
 * recommended preset and why" — for the same five kinds, a paragraph like *"This is already line art
 * (82% bimodal, 61% confident), so the suggested preset is \"Ink Scan\"…"*.
 *
 * So the Kotlin value cannot be put in that field: it is prose, and a panel that rendered it as a style
 * would show a researcher a sentence where a preset name belongs.
 *
 * ── AND THE THREE WAYS OF RECOVERING AN ID WERE ALL WORSE ─────────────────────────────────────
 *
 * Reading the preset out of the sentence does not work even in principle: the names it recommends —
 * "Ink Scan", "Flat Graphic", "Coherent Line" — **are not in `Styles.ALL`**, so it is naming another
 * product's preset table. Transcribing the TypeScript's ladder into Kotlin would put a second register
 * of style ids in this app, which `TraceEngine.kt`'s header forbids outright, and it would have this
 * runtime suggest a style the engine that is actually running does not agree with. Inventing a mapping
 * from the source kind is the same thing with fewer sources.
 *
 * ── WHAT IS ACTUALLY LOST, WHICH IS LESS THAN IT SOUNDS ───────────────────────────────────────
 *
 * The panel's suggestion row never appears. **The portal has never had it either**: it computes the
 * suggestion, ships it across its worker boundary, and renders it nowhere. So this is a handset-only
 * nicety going quiet, not a capability leaving the product — and the empty string is a state the panel
 * already handles, because a preview has always produced one.
 */
const val TRACE_NO_SUGGESTION_NOTE: String =
    "This engine's classifier answers with a sentence rather than a style id, so this runtime " +
        "suggests no style. See TraceRuntime.kt for the three alternatives and why each is " +
        "worse than an empty field."

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 7. THE RUNTIME
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * A refusal with a sentence this file wrote, on its way to [TraceOutcome.Refused].
 *
 * [TraceHostFailure] would be the obvious carrier and it is the wrong one: its sentence is built from a
 * [TraceFailureKind], and the nearest kind — `OUT_OF_MEMORY` — says the phone "ran out of memory
 * part-way through the trace", which is false for a trace that was refused before it started. A
 * sentence that is nearly right is worse than one more exception type.
 */
private class TraceRefusal(message: String) : Exception(message)

/**
 * The tracer that runs the vendored engine in this process.
 *
 * Built by [traceRuntime]. Holds no Activity, no View and no composition — an in-flight trace belongs
 * to the job that called [trace], so a panel that launches it from a composition's scope loses it on
 * rotation and a caller that wants otherwise can hold the job somewhere longer-lived. There is nothing
 * here to dispose, which is why this class has no `dispose()` and why [rememberTraceRuntime] is a bare
 * `remember` with no `DisposableEffect` around it.
 *
 * **IT DOES NOT EVEN HOLD A `Context`**, which is worth saying because the absence is load-bearing
 * rather than tidy: there is no service to bind, no isolate to start and no asset to read out of the
 * APK. The engine is on the classpath. [traceRuntime] takes a `Context` only to ask the platform how
 * much memory this phone has, once, before returning — and a `ContentResolver` reaches the photograph
 * through the request's Uri at the moment of tracing, from the caller's own context.
 */
class TraceEngineRuntimeImpl internal constructor(
    override val availability: TraceAvailability,
    /**
     * How the photograph is read. The application context, held for its `ContentResolver`.
     *
     * A `content://` Uri cannot be opened without one, and a Uri is what this panel is handed — see
     * [TraceRequest.photograph] for why a path is not available on this platform any more. The
     * APPLICATION context specifically, so a runtime remembered across a configuration change cannot
     * pin a destroyed Activity.
     */
    private val context: Context,
) : TraceEngineRuntime {

    /** Which writer spelled [TraceResult.svg]. */
    val svgWriter: String = TRACE_SVG_WRITER

    /** The engine's nineteen stages. See [TRACE_ENGINE_STAGES] for why the number matters. */
    val stages: List<TraceStage> = TRACE_ENGINE_STAGES

    /*
      THE FIVE PARAMETER VERBS ARE ARITHMETIC, AND THEY STILL HOP.

      Each is a handful of field copies and one `sanitized()` — microseconds, as
      `TraceEnginePresets.kt` measures where it declines to make its own halves suspend. They are
      wrapped in `withContext(Dispatchers.Default)` anyway because `TraceEngineRuntime`'s header
      requires an implementation to be main-safe and because `presets()` and `defaults()` also
      serialise a tree to JSON, which is not free on a cold class-load path in front of a first frame.
      The cost is one dispatch per slider tick against work the panel already sequences behind a mutex.
    */

    override suspend fun presets(): TracePresetTables = withContext(Dispatchers.Default) {
        tracePresetTables()
    }

    override suspend fun defaults(): TraceValues = withContext(Dispatchers.Default) {
        traceValuesOfParams(TraceParams())
    }

    override suspend fun withOverrides(
        base: TraceValues,
        patch: Map<String, TraceValue>,
    ): TraceValues = withContext(Dispatchers.Default) {
        // ONE CALL: the engine's own merge-then-sanitise, with no Kotlin merge in between.
        traceValuesOfParams(traceApplyLeaves(traceParamsOf(base), patch))
    }

    override suspend fun applyStyle(base: TraceValues, styleId: String): TraceValues =
        withContext(Dispatchers.Default) { traceApplyStyle(base, styleId) }

    override suspend fun applySubject(base: TraceValues, subjectId: String): TraceValues =
        withContext(Dispatchers.Default) { traceApplySubject(base, subjectId) }

    /**
     * One trace, start to finish.
     *
     * The four-way catch is deliberate: a cancellation is rethrown and never becomes an error line, a
     * pre-flight refusal becomes the sentence it carries, a classified failure becomes its own
     * sentence, and anything else becomes a sentence naming its class so a bug report has something to
     * say. A runtime that let an exception escape here would take a screen with unsaved work down with
     * it.
     */
    override suspend fun trace(
        request: TraceRequest,
        onProgress: (TraceProgress) -> Unit,
    ): TraceOutcome = try {
        TraceOutcome.Done(runTrace(request, onProgress))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (refused: TraceRefusal) {
        TraceOutcome.Refused(refused.message.orEmpty())
    } catch (failure: TraceHostFailure) {
        TraceOutcome.Refused(failure.message.orEmpty())
    } catch (unexpected: Throwable) {
        TraceOutcome.Refused(
            traceSentence(
                TraceFailureKind.ENGINE_ERROR,
                unexpected::class.java.simpleName,
            ),
        )
    }

    private suspend fun runTrace(
        request: TraceRequest,
        onProgress: (TraceProgress) -> Unit,
    ): TraceResult {
        val preview = !request.kind.isFullResolution

        // ONE DECODER FOR THE WHOLE FEATURE. `traceDecodeForTrace` is the only place a photograph is
        // turned into pixels for tracing, and its own docblock states the rule a second copy would
        // break: "a second decoder here would be a second opinion about EXIF orientation".
        val source = traceDecodeForTrace(context, request.photograph)

        // The pixels, the crop and the conversion together, off the caller's thread. Reading 1.9M
        // pixels back out of a bitmap and packing them twice is tens of milliseconds — not an ANR, but
        // not something to spend a frame on either.
        val prepared = withContext(Dispatchers.Default) {
            val rgba = TracePlates.readRgba(source)
                ?: throw TraceHostFailure(TraceFailureKind.IMAGE_UNREADABLE, "reading the pixels back")
            val decodedWidth = source.width
            val decodedHeight = source.height
            // Dropped as early as possible: the bitmap is up to 67 MB and every pixel of it now exists
            // in `rgba`.
            source.recycle()

            // The crop is a trace input and not an edit — the photograph is untouched and the frame is
            // taken afresh from the whole decode on every run. `traceCropIn` rather than a bare clamp
            // because the frame the researcher aimed in and the frame this decode produced are allowed
            // to differ.
            val frame = request.frame
            val cropped = if (frame == null) {
                null
            } else {
                traceCropRgba(
                    rgba,
                    decodedWidth,
                    decodedHeight,
                    traceCropIn(frame, decodedWidth, decodedHeight),
                ) ?: throw TraceHostFailure(
                    TraceFailureKind.OUT_OF_MEMORY,
                    "taking the chosen frame out of the photograph",
                )
            }
            TraceInput(
                rgba = cropped?.rgba ?: rgba,
                width = cropped?.rect?.width ?: decodedWidth,
                height = cropped?.rect?.height ?: decodedHeight,
                // Built from the box that was actually applied after clamping rather than from what the
                // panel asked for: a sentence in an archived file has to describe what happened.
                frameNote = cropped?.let { traceCropNote(it.rect, decodedWidth, decodedHeight) }.orEmpty(),
            )
        }

        val params = traceParamsOf(request.params)
        val effective = if (preview) tracePreviewParams(params) else params

        // BEFORE THE FIRST STAGE. See the file header: this engine's working set is in this app's heap.
        traceMemoryRefusal(
            sourcePixels = prepared.width.toLong() * prepared.height.toLong(),
            workingPixels = traceWorkingPixels(prepared.width, prepared.height, effective),
            engine = effective.edge.engine,
            heapBytes = traceHeapBytes(),
        )?.let { throw TraceRefusal(it) }

        val image = withContext(Dispatchers.Default) {
            traceImageOf(prepared.rgba, prepared.width, prepared.height)
        }

        val decoded = traceRunEngine(image, params, preview) { progress ->
            // ON THE MAIN THREAD, because `TraceEngineRuntime.trace` says so and a panel's state is
            // written from a composition. The pump this runs on already inherits the caller's context,
            // so when the panel calls from its own scope this is a no-op fast path.
            withContext(Dispatchers.Main) { onProgress(progress) }
        }

        return withContext(Dispatchers.Default) {
            // THE PLATE BUILDER, WHICH LIVES IN `TraceHost.kt` FOR THE SAME REASON AS THE DECODER: it
            // touches `android.graphics.Bitmap`, and this file is otherwise reachable by a JVM test.
            tracePlateResult(
                decoded = decoded,
                rgba = prepared.rgba,
                sourceWidth = prepared.width,
                sourceHeight = prepared.height,
                frameNote = prepared.frameNote,
                request = request,
            )
        }
    }
}

/** The photograph after the decode and the crop, with the sentence the crop earned. */
private class TraceInput(
    val rgba: ByteArray,
    val width: Int,
    val height: Int,
    val frameNote: String,
)

/**
 * The tracer for this phone.
 *
 * **IT CANNOT REFUSE, WHICH IS THE POINT OF IT.** There is no probe here because there is nothing a
 * probe could discover: the engine is four Gradle modules compiled into this APK, so if this app runs,
 * it traces. That is why [TraceAvailability] carries no `canTrace` and no refusal string; its header
 * records the removal. The one thing that can still stop a trace is memory, and that is measured per
 * trace against the frame actually being traced.
 *
 * **THE TWO CEILINGS ARE UNMEASURED**, which is what [TraceAvailability.measuredOn] being null says out
 * loud. They come from [traceCeilings], which reasoned them from a laptop's V8 and published
 * single-thread figures, and **nobody has run this engine on a handset**. The JVM's arithmetic is very
 * likely faster than V8's on these array loops, which would make them conservative rather than wrong —
 * but "very likely" is not a measurement, and a UI that presented a guess as a limit is the thing this
 * whole arrangement is written against. The panel prints a sentence saying so.
 */
fun traceRuntime(context: Context): TraceEngineRuntime {
    val app = context.applicationContext
    val (maxEdge, fdogMaxEdge) = traceCeilings(traceTotalRamBytes(app))
    return TraceEngineRuntimeImpl(
        availability = TraceAvailability(
            maxWorkingLongEdge = maxEdge,
            fdogMaxWorkingLongEdge = fdogMaxEdge,
            measuredOn = null,
        ),
        context = app,
    )
}

/**
 * How much memory this handset has, or null when the platform would not say.
 *
 * `ActivityManager.MemoryInfo.totalMem` and nothing else. It is always BELOW the number on the box —
 * the firmware's reservations are taken before Android sees them — which is why [traceCeilings]'s 3 GB
 * threshold is set where it is rather than at 4.
 *
 * NULL IS A REAL ANSWER AND IS NOT COERCED TO A NUMBER. `getSystemService` can answer null, and a
 * `RuntimeException` out of a system service is not a reason to fail to open a panel. [traceCeilings]
 * reads null as "assume small", because a handset that would have said it was small must not be
 * promoted by a lookup that failed.
 */
private fun traceTotalRamBytes(context: Context): Long? = runCatching {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: return@runCatching null
    val info = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(info)
    info.totalMem.takeIf { it > 0L }
}.getOrNull()
