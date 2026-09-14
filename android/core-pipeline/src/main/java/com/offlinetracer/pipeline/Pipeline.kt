package com.offlinetracer.pipeline

import com.offlinetracer.imaging.Classify
import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.Mask
import com.offlinetracer.imaging.RgbaImage
import com.offlinetracer.vector.VecDocument
import kotlin.jvm.Volatile
import kotlin.math.max

/** How long one stage took. Reported for **every** stage, including the ones that did nothing. */
data class StageResult(val id: String, val label: String, val millis: Long)

/**
 * Everything one trace produced.
 *
 * @param document the finished vector, in **frame coordinates** — see [sourceWidth].
 * @param preview the binary mask the vector was traced from, at working resolution. This is what an
 *   overlay draws, and it is the only artefact that shows what the cleanup stages actually decided.
 * @param processedGray the greyscale image as the edge engine saw it: post-denoise, post-contrast,
 *   post-matte. Working resolution.
 * @param distanceTransform the stroke-half-width field, or `null` when width modulation was off.
 * @param workingWidth, workingHeight the resolution the trace ran at, which is the size of [preview]
 *   and [processedGray]. After a crop this is the **cropped** size, because that is what the stages
 *   after the crop actually saw; [cropX] and [cropY] say where it sits.
 * @param cropX, cropY where [preview] and [processedGray] sit inside the un-cropped working image,
 *   both 0 when no crop was taken. A consumer overlaying [preview] on the source has to honour them,
 *   and this is the only place the offset is recorded.
 * @param sourceWidth, sourceHeight the frame [document]'s coordinates live in, and therefore exactly
 *   `document.width` × `document.height`. Normally the source image's own size; the **rectified page**
 *   when perspective correction ran, because after a rectification the original photograph's
 *   coordinate system no longer describes the geometry and pretending otherwise would put every
 *   exported path in the wrong place. **A crop never changes it** — cropping decides what is traced,
 *   not what coordinate system the result is reported in.
 * @param profile the source classification, or `null` when classification was skipped.
 * @param appliedParams the parameters the stages actually ran with. Identical to the sanitised input
 *   unless auto-detection changed something, and carried back so the UI can show what ran rather than
 *   what was asked for — the two differing silently is the whole failure mode auto-detection invites.
 * @param autoSubjectId the subject auto-detection applied, or empty when it applied none.
 * @param notes one plain-English sentence per cap the pipeline applied. Not decoration: see [Pipeline].
 */
data class TraceResult(
    val document: VecDocument,
    val preview: Mask,
    val processedGray: GrayF,
    val distanceTransform: GrayF?,
    val stages: List<StageResult>,
    val workingWidth: Int,
    val workingHeight: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val profile: Classify.SourceProfile?,
    val notes: List<String>,
    val cropX: Int = 0,
    val cropY: Int = 0,
    val appliedParams: TraceParams = TraceParams(),
    val autoSubjectId: String = "",
) {
    /** Sum of the stage times. Not wall-clock: classification and allocation sit outside the stages. */
    val totalMillis: Long
        get() {
            var t = 0L
            for (s in stages) t += s.millis
            return t
        }
}

/**
 * Called twice per stage: once as the stage begins, with the fraction of stages already finished, and
 * once as it ends.
 *
 * Both calls carry the same id and label, so a UI can show "Detecting edges" for the whole duration of
 * that stage and still have a monotone fraction. Reporting only on completion would leave the label
 * one stage behind the work throughout, which reads as the app having stalled on the stage it just
 * finished.
 */
fun interface ProgressListener {
    fun onProgress(stageId: String, label: String, fraction: Float)
}

/**
 * Cooperative cancellation. One token per trace; safe to cancel from any thread.
 *
 * There is no interruption anywhere in this engine — the stages are plain loops over arrays and a
 * thread interrupt would leave half-written buffers behind. Instead every stage boundary and every
 * subdivisible point inside the long stages checks this flag, which is why a cancel takes effect
 * within one sub-step rather than instantly and why it always leaves consistent state.
 */
class CancellationToken {

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    val isCancelled: Boolean get() = cancelled

    fun throwIfCancelled() {
        if (cancelled) throw CancelledException()
    }
}

/**
 * Thrown out of [Pipeline.run] when the trace was cancelled.
 *
 * A distinct type, not an `IllegalStateException`: [Batch] has to tell "the user cancelled" apart from
 * "this file is corrupt", and it makes opposite decisions about the two.
 */
class CancelledException : RuntimeException("The trace was cancelled before it finished.")

/**
 * The orchestrator: it runs [Stages.ALL] in order, times each one, reports progress, honours
 * cancellation, and assembles a [TraceResult].
 *
 * Two responsibilities here matter more than the sequencing:
 *
 * **The result is resolution-independent.** Every stage runs at working resolution, and the assemble
 * stage scales the geometry by `frame/working` into the returned document. Skipping that scale is the
 * single most damaging bug this file could contain, because it would not look like a bug: the SVG would
 * render correctly, at the wrong size, and only fail once somebody printed it.
 *
 * **Every cap is reported.** Blobs removed and how many, paths dropped below the minimum length and how
 * many, a requested edge model that was unavailable and what ran instead, a tiling fallback, a matte
 * that removed most of the frame. [TraceResult.notes] carries one sentence for each, and the UI is
 * required to show them. Without that, a pipeline that silently discarded four thousand paths and one
 * that genuinely found nothing produce the same blank canvas, and the user has no way at all to tell
 * which happened — the ambiguity this project takes most seriously.
 */
object Pipeline {

    /** The stage ids in execution order, for a progress UI to enumerate ahead of time. */
    fun stageIds(): List<String> = Stages.ids()

    /**
     * Traces [src].
     *
     * @param params clamped through [TraceParams.sanitized] on entry, once, so no stage defends itself.
     * @param progress notified at the start and end of every stage; may be `null`.
     * @param cancel checked between every stage and inside the long ones.
     * @param classify whether to compute a [Classify.SourceProfile]. Off for previews, where the answer
     *   would be identical and the cost is not.
     * @throws CancelledException if [cancel] was cancelled. Nothing else is thrown for any input: a
     *   1-pixel image, an all-background mask and a frame the matte deleted entirely all return an
     *   empty document plus a note saying so.
     */
    fun run(
        src: RgbaImage,
        params: TraceParams,
        progress: ProgressListener? = null,
        cancel: CancellationToken = CancellationToken(),
        classify: Boolean = true,
    ): TraceResult {
        val requested = params.sanitized()
        val notes = ArrayList<String>()
        val stages = ArrayList<StageResult>(Stages.ALL.size)
        val stageCount = Stages.ALL.size
        var finished = 0

        // The timing / progress / cancellation wrapper, written once. Eighteen hand-rolled copies of
        // it is eighteen chances for one stage to forget the cancellation check.
        fun <T> stage(id: String, body: () -> T): T {
            val spec = Stages.byId(id) ?: Stage(id, id, false)
            cancel.throwIfCancelled()
            progress?.onProgress(spec.id, spec.label, finished.toFloat() / stageCount)
            val started = System.nanoTime()
            val result = body()
            stages.add(StageResult(spec.id, spec.label, (System.nanoTime() - started) / 1_000_000L))
            finished++
            progress?.onProgress(spec.id, spec.label, finished.toFloat() / stageCount)
            return result
        }

        var profile: Classify.SourceProfile? = null
        val oriented = stage(Stages.ORIENT) {
            // Classification is folded into the first stage's clock rather than given a stage of its
            // own: it is not a transform and adding an id would change stageIds(), a list the
            // progress UI is built against.
            //
            // It runs whenever the caller asked for it **or** auto-detection is in a mode that will
            // actually change a setting. The second half is what keeps a preview honest: a preview is
            // not allowed to be a cheaper different pipeline (see [Preview]), and one where auto chose
            // no subject while the export's auto chose one would have the user tuning twenty sliders
            // against a picture the export will not reproduce. [AutoMode.SUGGEST] changes nothing, so
            // a preview that skips it is bit-identical to one that does not — and it skips a 512 px
            // proxy classification on every keystroke.
            if (classify || requested.auto.mode == AutoMode.APPLY) profile = Classify.profile(src)
            StageOps.orient(src, requested.preprocess)
        }

        // Auto-detection resolves the parameters *before* any stage that reads them. It sits here,
        // between the pass-through orient stage and the first stage that actually consumes a setting,
        // because the subject it picks can change perspective correction, the working resolution and
        // the matte — all of which are consumed by the three stages immediately below.
        val decision = Auto.decide(profile, requested)
        notes.addAll(decision.notes)
        val p = decision.params

        val frame = stage(Stages.PERSPECTIVE) { StageOps.perspective(oriented, p.preprocess, notes) }
        val working = stage(Stages.DOWNSCALE) { StageOps.downscale(frame, p.preprocess, notes) }

        // Tiling is decided once, from the working size, so every tile-local stage makes the same
        // choice. Deciding per stage is how one stage ends up tiled and the next not, which produces a
        // seam that is present in the mask and absent from the greyscale it came from.
        val tiled = max(working.width, working.height) > StageOps.TILE_THRESHOLD

        val gray = stage(Stages.GRAYSCALE) { StageOps.grayscale(working, p.preprocess) }
        val denoised = stage(Stages.DENOISE) {
            StageOps.denoise(gray, p.preprocess, tiled, notes, cancel)
        }
        val enhanced = stage(Stages.CONTRAST) { StageOps.contrast(denoised, p.preprocess, cancel) }
        val matted = stage(Stages.MATTE) {
            StageOps.matte(working, enhanced, p.matte, notes)
        }
        val crop = stage(Stages.CROP) { StageOps.cropToSubject(working, matted, p, notes) }
        val processedGray = crop.gray

        val edge = stage(Stages.EDGE) { StageOps.edges(processedGray, p.edge, notes, cancel) }
        if (edge.engine != p.edge.engine && p.edge.engine != EdgeEngine.MODEL) {
            // Defensive: today only MODEL can be substituted, and that path writes its own note.
            notes.add(
                "The ${p.edge.engine} edge engine was requested but ${edge.engine} ran instead."
            )
        }

        val binary = stage(Stages.BINARISE) { StageOps.binarise(edge, p.edge) }
        val cleaned = stage(Stages.MORPHOLOGY) { StageOps.morphology(binary, p.cleanup, cancel) }
        val filtered = stage(Stages.BLOBS) { StageOps.blobFilter(cleaned, p.cleanup, notes, cancel) }
        // Full stroke width, with the bridges included. Both the skeleton and the distance transform
        // are derived from this one mask, which is what keeps their coordinates in step.
        val bridged = stage(Stages.BRIDGE) { StageOps.bridge(filtered, p.cleanup, notes, cancel) }

        val centreline = p.output.vectorMode == VectorModeParam.CENTERLINE
        val thinned = stage(Stages.SKELETON) {
            if (centreline && p.cleanup.skeletonize) {
                StageOps.thin(bridged, p.cleanup)
            } else {
                if (!centreline && p.cleanup.skeletonize) {
                    notes.add(
                        "Outline mode traces region boundaries, so skeletonisation and spur pruning " +
                            "were skipped — a skeleton would delete the boundary this mode exists to " +
                            "trace."
                    )
                }
                bridged
            }
        }
        val pruned = stage(Stages.PRUNE) {
            if (centreline && p.cleanup.skeletonize) StageOps.prune(thinned, p.cleanup) else thinned
        }

        val dt = stage(Stages.DISTANCE) {
            if (p.output.modulateWidth) StageOps.distance(bridged) else null
        }

        val traced = stage(Stages.VECTORISE) {
            StageOps.vectorise(pruned, p.output, dt, notes, cancel)
        }
        val styled = stage(Stages.STYLE) { StageOps.style(traced, p.output) }
        val document = stage(Stages.ASSEMBLE) {
            // `working`, not `processedGray`: the scale from working to frame is the one the
            // downscale established, and the crop removed pixels without resampling the rest. The
            // crop's offset is undone by the same transform — see [StageOps.assemble].
            StageOps.assemble(
                styled,
                working.width, working.height,
                frame.width, frame.height,
                p.output,
                crop.offsetX, crop.offsetY,
            )
        }

        // The last and most important note: distinguishing "found nothing" from "discarded everything".
        if (styled.isEmpty()) {
            notes.add(
                if (pruned.countTrue() == 0) {
                    "No ink survived the cleanup stages, so the drawing is empty — raise the " +
                        "sensitivity or lower the minimum blob area."
                } else {
                    "Ink survived cleanup but no path was long enough to keep, so the drawing is " +
                        "empty — lower the minimum path length."
                }
            )
        }

        return TraceResult(
            document = document,
            preview = pruned,
            processedGray = processedGray,
            distanceTransform = dt,
            // Copied, not handed over: these two are the only mutable things the pipeline built, and a
            // caller that appended to them would be editing a record of what already happened.
            stages = stages.toList(),
            workingWidth = processedGray.width,
            workingHeight = processedGray.height,
            sourceWidth = frame.width,
            sourceHeight = frame.height,
            profile = profile,
            notes = notes.toList(),
            cropX = crop.offsetX,
            cropY = crop.offsetY,
            appliedParams = p,
            autoSubjectId = if (decision.applied) decision.subject?.id ?: "" else "",
        )
    }

    /**
     * Traces [src] at a reduced working resolution for an interactive preview.
     *
     * Delegates to [Preview.runPreview], which reuses this exact stage list — see that file for why a
     * preview is not allowed to be a cheaper *different* pipeline.
     */
    fun runPreview(
        src: RgbaImage,
        params: TraceParams,
        longEdge: Int = Preview.DEFAULT_LONG_EDGE,
        cancel: CancellationToken = CancellationToken(),
    ): TraceResult = Preview.runPreview(src, params, longEdge, cancel)
}
