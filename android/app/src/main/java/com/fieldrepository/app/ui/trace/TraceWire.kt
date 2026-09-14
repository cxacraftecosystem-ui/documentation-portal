package com.fieldrepository.app.ui.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * **THE PARTS OF THE TRACE FEATURE THAT CAN BE QUIETLY WRONG — with no Android in them.**
 *
 * ── WHY THIS FILE EXISTS AT ALL, WHICH IS A BUILD FACT AND NOT A TASTE ────────────────────────
 *
 * `app/build.gradle.kts` declares `testImplementation("junit:junit:4.13.2")` and nothing else — no
 * Robolectric — so anything that touches `android.*` is, by construction, code no unit test in this
 * module can reach. `data/PhotoMeasure.kt` states the same split for the measuring feature and gives
 * the same reason. So every piece of this feature that could be wrong WITHOUT FAILING lives here:
 * the channel marshalling, the geometry mirror, the working-size arithmetic, the device ceilings and
 * the failure sentences. What is left in `TracePlates.kt` and `TraceImageDecode.kt` is allocation,
 * iteration and drawing — the parts that fail loudly or not at all.
 *
 * The files this one is the core of:
 *
 *  - `TracePlates.kt` — the only file in the feature that knows what a `Bitmap` is. It hands this
 *    file an `IntArray` of packed ARGB and takes back a [TraceGeometry] to draw.
 *  - `TraceHost.kt` — the mount, the decoder and the plate builder.
 *  - `TraceRuntime.kt` — the [TraceEngineRuntime] implementation, over the vendored Kotlin engine.
 *  - `TraceEngine.kt` — the port the whole surface is written against.
 *
 * ── WHAT RUNS THE ENGINE, AND WHY NOBODY HAND-WROTE IT ────────────────────────────────────────
 *
 * ONE upstream, vendored twice. `frontend/lib/trace/engine/` is the TypeScript the web client runs;
 * `android/core-imaging`, `core-vector`, `core-pipeline` and `core-export` are the Kotlin this APK
 * compiles, hashed file by file in `android/UPSTREAM-MANIFEST-KOTLIN.txt`. Neither is a hand-written
 * port, and that is the whole discipline: a port would fork a numerical library into a second
 * language — Otsu, Canny, Bézier fitting, thinning, morphology — where two independent
 * implementations do not agree to the digit about the line art that ends up in a report.
 *
 * Two vendorings are still two floating-point implementations, so the thing that holds them together
 * is not a manifest but a test: `:core-pipeline`'s own `ParityTest` replays the shared fixtures under
 * `docs/fixtures/` through the Kotlin engine and asserts the TypeScript's numbers.
 *
 * ── WHAT THE DESIGNER PORTAL'S COPY OF THIS FILE CARRIED THAT THIS ONE DELIBERATELY DOES NOT ──
 *
 * The designer portal's `DwSketchTraceWire.kt` is 1,466 lines and roughly half of them describe a
 * JAVASCRIPT BRIDGE: a `DwTraceJsHost` port over `androidx.javascriptengine`, an envelope protocol, a
 * pump loop, a bundle-contract handshake, a heap cap for an isolate, and five [TraceFailureKind]
 * members naming a WebView. That route was deleted in that repository when the engine became four
 * Gradle modules, and its own header says what was left behind: *"**No designer can see any of their
 * sentences**, because nothing constructs them."*
 *
 * **It has never existed in THIS repository at all.** There is no bundle in `assets/`, no
 * `androidx.javascriptengine` dependency, and no isolate — the engine arrived here as the four
 * `:core-*` modules on its first day. Carrying the bridge across would have meant vendoring a
 * protocol with no second half, and — worse — shipping sentences that apologise for a device:
 * *"This phone's Android System WebView is too old to trace a sketch."* A researcher who read that
 * would go and update WebView, which would change nothing, because the tracer in this app is
 * compiled into it. A sentence that sends somebody to a remedy that cannot work is worse than no
 * sentence, which is the rule [TRACE_ENGINE_SILENT_SENTENCE] is written under.
 *
 * So [TraceFailureKind] carries FIVE members rather than twelve, and every one of them is
 * constructed by something in this package. `TraceWireTest` asserts that, so the enum cannot grow a
 * member nothing builds.
 *
 * ── THE ONE THING THIS LAYER MAY NEVER DO ─────────────────────────────────────────────────────
 *
 * **It proposes; it never writes.** A traced document is a machine-produced value, and this
 * repository's rule for those is that a person presses for it: `records.merge_field_provenance`
 * stamps every changed field with the `{by, byName, at}` of the account that pressed Save, so a
 * machine value written without a press makes the record positively assert that A NAMED HUMAN
 * produced it. Nothing in this package opens a record, uploads a file or attaches anything.
 * [TraceOutcome.Done] is a value handed back for a person to look at, and the button that accepts it
 * belongs to the panel — which hands ONE derived file to an `onAttach` callback the host owns, and
 * nothing else.
 */

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 1. FAILURE — every kind gets its own sentence, and the sentences are the API
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The ways a trace can fail on a handset. **One kind per remedy, never one per exception class.**
 *
 * This repository forbids "Something went wrong": a researcher four days from a connection cannot act
 * on it, and neither can whoever reads the screenshot afterwards. `TraceWireTest` keeps that honest —
 * it asserts every kind produces a DIFFERENT sentence, that none is generic, and that each names
 * something the reader can do next.
 *
 * **FIVE MEMBERS, AND EVERY ONE IS CONSTRUCTED SOMEWHERE IN THIS PACKAGE.** See the file header for
 * the six the designer portal carries that this does not, and why importing a sentence about
 * Android System WebView into an app with no WebView tracer would be worse than having none.
 */
enum class TraceFailureKind {
    /**
     * The trace could not be started or could not be held, for want of memory.
     *
     * The feasibility work behind this engine measured a full-resolution trace wanting +93 MB with
     * the adaptive edge engine and **+278 MB with Canny**, at the product's own input size. Both
     * remedies are in the researcher's hands and both are named in the sentence.
     *
     * `TraceRuntime.kt` refuses BEFORE the first stage wherever it can — see
     * `traceMemoryRefusal`, which writes its own sentence with both numbers in it — so this kind is
     * for the allocation that failed anyway, after the estimate said it would fit.
     */
    OUT_OF_MEMORY,

    /** The engine itself refused. Its own sentence is carried through verbatim in `detail`. */
    ENGINE_ERROR,

    /**
     * The trace succeeded, in a frame that is not the photograph's, so the comparator cannot lay the
     * two pictures over each other.
     *
     * Both plates must be the same size and *a mismatch beyond a rounding pixel is a REFUSAL rather
     * than an assumption* — because the alternative is a wipe in which the two layers do not
     * correspond, and every line the researcher checks is checked against the wrong part of their
     * drawing.
     *
     * Exactly one thing causes it: `preprocess.perspectiveCorrect`, which makes the document frame
     * the rectified page rather than the source. So the remedy is a control the researcher can see,
     * named by the label the panel shows for it ("Rectify the page"). **Its own kind and not an
     * [ENGINE_ERROR]** because the engine did not fail, and because the sentence a researcher needs
     * here would not survive [ENGINE_ERROR]'s detail cap.
     *
     * **IT COSTS THE COMPARISON AND NOT THE DRAWING.** It is carried as [TraceResult.plateRefusal]
     * beside a drawing that is still on screen and still attachable, never thrown out of the run —
     * a picture nobody attaches must not destroy the one artefact that reaches a record.
     */
    FRAME_MISMATCH,

    /**
     * The photograph could not be read at all.
     *
     * A HEIC the platform decoder will not open, a truncated file from an interrupted copy, a
     * content Uri whose permission has lapsed, or a bitmap the phone had no memory for. The
     * photograph on the record is untouched either way and the sentence says so — a researcher who
     * reads "could not be read" about a file they can see in the gallery will otherwise assume the
     * record is damaged.
     */
    IMAGE_UNREADABLE,

    /** The file decoded, to nothing. A different remedy: choose another photograph. */
    IMAGE_EMPTY,
}

/** See [traceSentence]. The upstream worker caps its own detail at the same length. */
const val TRACE_DETAIL_MAX: Int = 160

/**
 * @return the sentence to put on screen for [kind]. Never a code, never a stack trace.
 *
 * [detail] is appended only where the engine or the platform said something a reader can use, and it
 * is length-capped for the reason the upstream's `sentenceFor` caps its own at 160: a message that
 * long is a stack trace wearing a sentence's clothes.
 */
fun traceSentence(kind: TraceFailureKind, detail: String = ""): String {
    val trimmed = detail.trim().let { if (it.isNotEmpty() && it.length <= TRACE_DETAIL_MAX) it else "" }
    return when (kind) {
        TraceFailureKind.OUT_OF_MEMORY ->
            "This phone ran out of memory part-way through the trace. Set the trace resolution to " +
                "Fast, or choose a different edge engine — Canny needs about three times the memory " +
                "of the others. Closing other apps first also helps."

        TraceFailureKind.ENGINE_ERROR ->
            if (trimmed.isEmpty()) {
                "The tracing engine could not finish this photograph. Try a different edge engine, " +
                    "or set the trace resolution to Fast."
            } else {
                "The tracing engine could not finish this photograph: $trimmed"
            }

        TraceFailureKind.FRAME_MISMATCH ->
            "The trace finished in a different frame from the photograph, so the two cannot be laid " +
                "over each other and there is no comparison to show. The drawing itself is " +
                "unaffected and can still be attached. Turn off “Rectify the page” and trace " +
                "again if you want the comparison as well." +
                if (trimmed.isEmpty()) "" else " ($trimmed)"

        TraceFailureKind.IMAGE_UNREADABLE ->
            "This phone could not read that photograph, so there is nothing to trace. The " +
                "photograph on the record is unaffected — it can still be attached as it is, and " +
                "the portal can trace it on a laptop."

        TraceFailureKind.IMAGE_EMPTY ->
            "That photograph decoded to no pixels at all, so there is nothing to trace. Take or " +
                "choose another photograph of the sheet."
    }
}

/**
 * A failure carrying a classified [kind], thrown inside this package.
 *
 * An exception here and a [TraceOutcome.Refused] at the [TraceEngineRuntime] boundary, which is not
 * an inconsistency: a sentence that HAS to be printed is a value, and that is true of the boundary
 * the panel reads. Inside, a failure has to unwind a decode/crop/convert sequence from wherever it
 * happened, which is what exceptions are for. `TraceRuntime.kt` converts one into the other in
 * exactly one place.
 */
class TraceHostFailure(
    val kind: TraceFailureKind,
    val detail: String = "",
    cause: Throwable? = null,
) : Exception(traceSentence(kind, detail), cause)

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 2. THE GEOMETRY MIRROR — thin, faithful, and deliberately not a re-model
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/** Segment kind codes, mirroring the upstream worker's protocol. `TraceWireTest` pins all three. */
const val TRACE_VERB_LINE: Byte = 0

/** @see TRACE_VERB_LINE */
const val TRACE_VERB_QUAD: Byte = 1

/** @see TRACE_VERB_LINE */
const val TRACE_VERB_CUBIC: Byte = 2

/**
 * One entry of the geometry's style table. A field-for-field mirror of the engine's `VecStyle`.
 *
 * [stroke] and [fill] are packed ARGB, or null for "none" — `null` is the only spelling of absent,
 * and a `0` would be transparent black, which is a different thing that draws.
 *
 * [fillRule], [cap] and [join] stay STRINGS. The engine's enums have values equal to their names
 * (`FillRule.EVENODD`), and re-declaring three Kotlin enums here would be three more lists to keep in
 * step with a vendored file for no benefit. `TracePlates` maps them where it draws, and an
 * unrecognised value there falls back to the engine's own default rather than crashing — a newer
 * upstream that adds a join style must not be a crash on a phone in a village.
 */
class TraceStyle(
    val stroke: Int?,
    val strokeWidth: Float,
    val fill: Int?,
    val fillRule: String,
    val cap: String,
    val join: String,
    val miterLimit: Float,
    val opacity: Float,
)

/**
 * The traced geometry, as the flat arrays the engine already produced.
 *
 * **THIS IS A MIRROR AND MUST STAY ONE.** A 50,000-path trace is roughly a million coordinates, and
 * as `{x, y}` objects that is a million allocations. Rebuilding it here into Kotlin path classes
 * would pay that cost AND start the second implementation this whole approach exists to avoid: the
 * moment there is a Kotlin opinion about what a cubic segment IS, there is something for the two
 * clients to disagree about, and nothing that can check it. So the arrays cross as arrays,
 * `TracePlates` walks them once to draw, and no other file in this app holds geometry in any other
 * shape.
 *
 * Layout, from the upstream worker's `serializeGeometry`:
 *  - shape `i` owns `verbs[verbStarts[i] until verbStarts[i + 1]]` and
 *    `coords[coordStarts[i] until coordStarts[i + 1]]`;
 *  - a shape's coordinate run BEGINS with its start point, then two floats per line, four per quad,
 *    six per cubic;
 *  - [verbStarts] and [coordStarts] are `shapeCount + 1` long, so an extent is a subtraction.
 */
class TraceGeometry(
    val coords: FloatArray,
    val verbs: ByteArray,
    val verbStarts: IntArray,
    val coordStarts: IntArray,
    val closed: ByteArray,
    val styleTable: List<TraceStyle>,
    val styleIndex: IntArray,
) {
    val shapeCount: Int get() = closed.size

    fun isClosed(shape: Int): Boolean = closed[shape].toInt() != 0

    fun styleOf(shape: Int): TraceStyle = styleTable[styleIndex[shape]]

    /**
     * Refuses a self-inconsistent set of arrays rather than letting it become a crash inside a canvas.
     *
     * Everything here is cheap integer arithmetic over `shapeCount + 1` entries, run once per trace,
     * and it is the difference between a sentence on screen and an `ArrayIndexOutOfBoundsException`
     * thrown out of a draw call three frames later — by which point nothing on screen says the
     * geometry was the problem.
     *
     * The coordinate-count check is the one that earns its keep. A mismatch there does not crash: it
     * reads a neighbouring shape's numbers as this shape's curve and draws something plausible and
     * wrong, which is the worse failure.
     *
     * **IT IS NOT DEAD WEIGHT JUST BECAUSE ONE PRODUCER FILLS THESE ARRAYS.** `traceGeometryOf`
     * writes them and `traceDocumentOf` reads them back, minutes apart, off a result that has been
     * sitting in a composition — and the export walks them without a bounds check per read precisely
     * BECAUSE this ran first.
     *
     * @throws TraceHostFailure with [TraceFailureKind.ENGINE_ERROR]
     */
    fun validate() {
        val n = shapeCount
        if (verbStarts.size != n + 1) bad("verbStarts is ${verbStarts.size} for $n shapes")
        if (coordStarts.size != n + 1) bad("coordStarts is ${coordStarts.size} for $n shapes")
        if (styleIndex.size != n) bad("styleIndex is ${styleIndex.size} for $n shapes")
        if (n > 0 && styleTable.isEmpty()) bad("the style table is empty for $n shapes")
        if (verbStarts[n] != verbs.size) {
            bad("verbs is ${verbs.size} long, verbStarts ends at ${verbStarts[n]}")
        }
        if (coordStarts[n] != coords.size) {
            bad("coords is ${coords.size} long, coordStarts ends at ${coordStarts[n]}")
        }
        for (i in 0 until n) {
            if (verbStarts[i] < 0 || verbStarts[i] > verbStarts[i + 1]) {
                bad("verbStarts does not increase at shape $i")
            }
            if (coordStarts[i] < 0 || coordStarts[i] > coordStarts[i + 1]) {
                bad("coordStarts does not increase at shape $i")
            }
            if (styleIndex[i] !in styleTable.indices) bad("shape $i names style ${styleIndex[i]}")
            var want = 2
            for (v in verbStarts[i] until verbStarts[i + 1]) {
                want += when (verbs[v]) {
                    TRACE_VERB_LINE -> 2
                    TRACE_VERB_QUAD -> 4
                    TRACE_VERB_CUBIC -> 6
                    else -> bad("shape $i has verb ${verbs[v]}, which is not a line, quad or cubic")
                }
            }
            val have = coordStarts[i + 1] - coordStarts[i]
            if (have != want) bad("shape $i has $have coordinates where its verbs need $want")
        }
    }

    private fun bad(why: String): Nothing =
        throw TraceHostFailure(TraceFailureKind.ENGINE_ERROR, why)
}

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 3. WHAT A FINISHED TRACE IS, BEFORE ANYTHING HAS DRAWN IT
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * Everything a finished run carries, decoded, and NOT ONE THING MORE.
 *
 * The split from [TraceResult] is the testability split this file's header describes: that class
 * holds two `Bitmap`s, so nothing that constructs one can be reached by a JVM test. Everything worth
 * pinning — the geometry, the notes, the counts, the parameters that actually ran — is here, where it
 * can be.
 */
class TraceDecoded(
    /**
     * The vector document as a string. **Carried, never re-printed.**
     *
     * What reaches a record is a string, so nothing on this side may re-indent it, normalise its
     * numbers or "tidy" its path data. Both engines must spell it identically.
     */
    val svg: String,
    val geometry: TraceGeometry,
    /** The document background, packed ARGB, or null for transparent — the engine's own spelling. */
    val background: Int?,
    val width: Int,
    val height: Int,
    val workingWidth: Int,
    val workingHeight: Int,
    val shapeCount: Int,
    val nodeCount: Int,
    val stages: List<TraceStageTiming>,
    val totalMillis: Long,
    val notes: List<String>,
    val appliedParams: TraceValues,
    val autoSubjectId: String,
    val suggestedStyleId: String,
)

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 4. MARSHALLING IN — Android's packed ARGB to the engine's RGBA
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * One row of `Bitmap.getPixels` output, written into [out] as the bytes the engine's own
 * `fromImageData` reads.
 *
 * **PURE, AND THAT IS THE WHOLE REASON IT IS HERE** rather than three lines inside the file that owns
 * the bitmap: it is the one piece of the marshalling that can be wrong in a way nothing else would
 * notice. Swap two channels and the engine still traces — it traces a picture with red and blue
 * exchanged, which on a pencil sketch on cream paper looks very nearly right and comes out quietly
 * different from the portal's answer forever. `TraceWireTest` pins it against the engine's own
 * buffer arithmetic, and round-trips it through [traceImageOf]'s inverse.
 *
 * Android's `getPixels` hands back `0xAARRGGBB`; the engine reads `[R, G, B, A]` and packs it back to
 * `(a shl 24) or (r shl 16) or (g shl 8) or b`. So this function and that packing are exact
 * inverses, and the byte order stays where the engine insists it stays — inside the engine, in the
 * only two functions allowed to know about it.
 *
 * @param pixels packed ARGB, [width] of them starting at index 0
 * @param out    at least `rowStart + width * 4` bytes
 */
fun traceArgbRowToRgba(pixels: IntArray, width: Int, out: ByteArray, rowStart: Int) {
    var o = rowStart
    for (x in 0 until width) {
        val p = pixels[x]
        out[o] = ((p ushr 16) and 0xFF).toByte()
        out[o + 1] = ((p ushr 8) and 0xFF).toByte()
        out[o + 2] = (p and 0xFF).toByte()
        out[o + 3] = ((p ushr 24) and 0xFF).toByte()
        o += 4
    }
}

/**
 * The longest edge a decode may produce, mirroring the web's own `DECODE_MAX_EDGE_PX`.
 *
 * 4096 is that file's number, and the reason it gives is that it is exactly the ceiling the web's
 * parameter table puts on "Trace resolution" — decoding below it would silently cap a slider the
 * researcher can still see at its top end. **On a handset the panel offers a lower choice** (see
 * [TraceAvailability.maxWorkingLongEdge]), and that is a different thing from decoding lower: a cap
 * the researcher picks with the cost named is a choice, and a limit nobody is told about is a
 * disagreement between two clients that nobody finds for a year. They must not be conflated, which is
 * why this constant equals the web's and is not quietly reduced.
 */
const val TRACE_DECODE_MAX_EDGE_PX: Int = 4096

/**
 * @return the working size a source of [width] x [height] is decoded down to, honouring [maxEdge].
 *
 * A LINE-FOR-LINE MIRROR of the web's `decodeToPixels`, including the `Math.round` and the
 * `max(1, …)`, because the two clients must hand the engine the same number of pixels or they are
 * not tracing the same picture. A `floor` here instead of a round is a one-pixel difference in the
 * working frame, and every coordinate the engine reports moves with it.
 *
 * Never upscales: a source already inside the cap comes back untouched, so the common scanned A4 at
 * 2480x3508 is not resampled for nothing.
 */
fun traceWorkingSize(width: Int, height: Int, maxEdge: Int = TRACE_DECODE_MAX_EDGE_PX): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= 0 || maxEdge <= 0 || longest <= maxEdge) return width to height
    val scale = maxEdge.toDouble() / longest.toDouble()
    val w = maxOf(1, Math.round(width * scale).toInt())
    val h = maxOf(1, Math.round(height * scale).toInt())
    return w to h
}

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 5. WHAT THIS PHONE IS ALLOWED TO ASK FOR — arithmetic, so it can be tested and argued with
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The working long edge a handset may trace at until somebody measures one.
 *
 * **THIS IS A GUESS AND IS LABELLED AS ONE**, which is why [TraceAvailability.measuredOn] is null
 * everywhere in this build: a ceiling is a claim about the world, and an undated claim is rot.
 *
 * What IS measured, on a laptop's V8 at the product's own input size of 1600x1200: a full trace is
 * 2.9 s with the adaptive engine and **16.7 s with the shipped flow default**, and 13,037 of those
 * 16,655 ms are in one stage. What is NOT measured is the desktop-to-handset factor. Published
 * single-thread figures put a mid-range Android handset 4–7x below that laptop, which would be
 * 12–20 s for adaptive and 67–117 s for flow — and one phone is one data point for a whole fleet.
 *
 * 2048 rather than the web's 4096 because the web's own table narrowed its slider to 4096 with the
 * sentence *"a 4096 trace is already several seconds of a worker thread on the phones this
 * application is used from"* — written about a browser on those phones, before anybody had run the
 * engine natively on one.
 *
 * Re-check by running a throughput matrix on a real device and writing the answer down. Until then
 * this number stays where it is and `measuredOn` stays null, because raising it on a hunch is how a
 * researcher waits two minutes in a village for something they will cancel.
 */
const val TRACE_DEFAULT_MAX_WORKING_EDGE: Int = 2048

/**
 * The same ceiling for the flow (FDOG) edge engine, which is 5.7x the cost of every alternative.
 *
 * **A CEILING AND NOT A SUBSTITUTION.** The obvious fix — quietly swap flow for adaptive on a phone
 * — is the worst option available and the one this vendoring discipline exists to prevent: one sheet
 * of paper would then produce two different drawings depending on which client traced it. So the
 * handset refuses above this edge and names the remedy, the researcher chooses, and both clients then
 * agree because they are running the same parameters.
 */
const val TRACE_DEFAULT_FDOG_MAX_WORKING_EDGE: Int = 1024

/**
 * The two ceilings for a phone with [totalRamBytes] of memory, halved on a small one.
 *
 * The threshold is 3 GB and it errs low on purpose: `ActivityManager.MemoryInfo.totalMem` is always
 * below the number on the box (the firmware's reservations are taken before Android sees them), so a
 * handset sold as 4 GB reports something in the threes and lands above this, while a genuine 2 GB
 * device lands below. A failed memory read (`null`) takes the cautious half, for the reason every
 * device probe in this repository takes it: a handset that would have said it was small must not be
 * promoted by a lookup that failed.
 */
fun traceCeilings(totalRamBytes: Long?): Pair<Int, Int> {
    val small = totalRamBytes == null || totalRamBytes < 3L * 1024L * 1024L * 1024L
    return if (small) {
        (TRACE_DEFAULT_MAX_WORKING_EDGE / 2) to (TRACE_DEFAULT_FDOG_MAX_WORKING_EDGE / 2)
    } else {
        TRACE_DEFAULT_MAX_WORKING_EDGE to TRACE_DEFAULT_FDOG_MAX_WORKING_EDGE
    }
}

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 6. THE PARAMETER CODEC — one flattener, so nothing can classify a leaf two ways
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The parser. Lenient about fields it does not know, strict about the ones it does.
 *
 * `ignoreUnknownKeys` is not set because nothing here is deserialised into a data class; every read
 * below goes through the element tree by hand, which ignores unknown keys by construction. That
 * matters for the same reason it matters on the wire types in `data/ApiModels.kt`: a newer engine
 * that adds a leaf must not stop an older surface from tracing.
 */
private val traceJson = Json

/**
 * The engine's sanitised tree, flattened for reading and carried whole for sending back.
 *
 * TWO REPRESENTATIONS OF ONE THING, AND [TraceValues.wire] IS THE AUTHORITY. The flat map exists so
 * a slider can read its own value in one lookup; the text is what goes back, unaltered, so a leaf
 * this Kotlin has never heard of survives a round trip instead of being dropped by a
 * re-serialisation. `TraceEngine.kt` makes the same argument where it declares the class.
 */
fun traceValuesOf(wire: String): TraceValues {
    val tree = runCatching { traceJson.parseToJsonElement(wire) }.getOrNull() as? JsonObject
        ?: throw TraceHostFailure(
            TraceFailureKind.ENGINE_ERROR,
            "a parameter tree that is not a JSON object",
        )
    val leaves = LinkedHashMap<String, TraceValue>()
    traceFlatten(tree, "", leaves)
    return TraceValues(leaves, wire)
}

/**
 * Walks the tree into dot paths — `edge.flow.sigmaM`, the same keys the web's table uses.
 *
 * **`JsonNull` IS A `JsonPrimitive`**, so it has to be tested first or `output.background: null`
 * decodes as the string "null" and the "White background" toggle reads as ON forever. That is the
 * kind of bug a `when` over a sealed hierarchy invites and a test has to catch; `TraceWireTest` does.
 *
 * ARRAYS ARE NOT LEAVES AND ARE SKIPPED. There is exactly one today — `auto.handTuned`, a
 * `Set<String>` — and it is not something a control reads. It is carried in [TraceValues.wire] with
 * everything else, and `traceApplyLeaves` copies it across from the base untouched, which is why
 * skipping it here loses nothing.
 */
private fun traceFlatten(tree: JsonObject, prefix: String, out: MutableMap<String, TraceValue>) {
    for ((key, value) in tree) {
        val path = if (prefix.isEmpty()) key else "$prefix.$key"
        when {
            value is JsonNull -> out[path] = TraceValue.Absent
            value is JsonObject -> traceFlatten(value, path, out)
            value is JsonArray -> Unit
            value is JsonPrimitive -> out[path] = traceLeafOf(value, path)
            else -> Unit
        }
    }
}

private fun traceLeafOf(primitive: JsonPrimitive, path: String): TraceValue {
    if (primitive.isString) return TraceValue.Choice(primitive.content)
    return when (val raw = primitive.content) {
        "true" -> TraceValue.Flag(true)
        "false" -> TraceValue.Flag(false)
        else -> raw.toDoubleOrNull()?.let { TraceValue.Num(it) }
            ?: throw TraceHostFailure(
                TraceFailureKind.ENGINE_ERROR,
                "$path is neither a number, a flag nor a name",
            )
    }
}

/**
 * A patch, as the JSON an engine's own `withOverrides` would take.
 *
 * NON-FINITE IS A REFUSAL, NOT A SILENT SUBSTITUTION. `NaN` and the infinities have no JSON spelling,
 * so the alternatives are to emit invalid JSON, to drop the key, or to say so. Dropping it is the
 * worst of the three — the slider moves, the trace runs, and the parameter the researcher changed is
 * the one that did not change.
 *
 * **NOTHING IN THIS BUILD SENDS A PATCH OVER A WIRE**, because the engine is on the classpath and
 * `traceApplyLeaves` applies a patch to a `TraceParams` directly. This is kept because it is the one
 * place the patch shape is written down in a form a test can read, and `TraceWireTest` uses it to
 * assert that the four [TraceValue] cases survive a round trip through [traceValuesOf] — which is
 * the property the panel's whole "what changed" reporting rests on.
 */
fun tracePatchJson(patch: Map<String, TraceValue>): String {
    val out = StringBuilder(patch.size * 24 + 2)
    out.append('{')
    var first = true
    for ((key, value) in patch) {
        if (!first) out.append(',')
        first = false
        out.append(traceJsonString(key)).append(':')
        when (value) {
            is TraceValue.Num -> {
                if (!value.value.isFinite()) {
                    throw TraceHostFailure(
                        TraceFailureKind.ENGINE_ERROR,
                        "$key was set to ${value.value}, which is not a number the engine can be sent",
                    )
                }
                out.append(value.value.toString())
            }
            is TraceValue.Flag -> out.append(if (value.value) "true" else "false")
            is TraceValue.Choice -> out.append(traceJsonString(value.value))
            TraceValue.Absent -> out.append("null")
        }
    }
    out.append('}')
    return out.toString()
}

/**
 * [value] as a JSON string literal.
 *
 * Hand-written rather than `Json.encodeToString`, and the reason is the one `data/ApiModels.kt` gives
 * for building its metadata by hand: this is three lines of escaping used in one place, against a
 * serializer call that would have to be given a type. `U+2028` and `U+2029` are escaped even though
 * JSON permits them raw, because anything that re-parses this as a script would choke on them.
 */
fun traceJsonString(value: String): String {
    val out = StringBuilder(value.length + 2)
    out.append('"')
    for (ch in value) {
        when {
            ch == '"' -> out.append("\\\"")
            ch == '\\' -> out.append("\\\\")
            ch == '\n' -> out.append("\\n")
            ch == '\r' -> out.append("\\r")
            ch == '\t' -> out.append("\\t")
            ch == '\b' -> out.append("\\b")
            // U+000C, U+2028 and U+2029 are compared BY CODE POINT and never written as literal
            // characters. A form feed or a line separator typed into Kotlin source is invisible in
            // every diff, every review and every editor on earth, which is the one place a character
            // class must not be — and this file was written with them in it once, which is how the
            // rule was learnt. The two separators are escaped on the way out even though JSON permits
            // them raw, because anything that re-parses the result as script would choke on them.
            ch.code == 0x0C -> out.append("\\f")
            ch < ' ' || ch.code == 0x2028 || ch.code == 0x2029 ->
                out.append("\\u").append(String.format("%04x", ch.code))
            else -> out.append(ch)
        }
    }
    out.append('"')
    return out.toString()
}
