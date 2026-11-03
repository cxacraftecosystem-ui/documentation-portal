package com.offlinetracer.pipeline

import com.offlinetracer.imaging.Classify
import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.Mask
import com.offlinetracer.imaging.RgbaImage
import com.offlinetracer.vector.VecDocument
import kotlin.jvm.Volatile
import kotlin.math.max

data class StageResult(val id: String, val label: String, val millis: Long)

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
    val totalMillis: Long
        get() {
            var t = 0L
            for (s in stages) t += s.millis
            return t
        }
}

fun interface ProgressListener {
    fun onProgress(stageId: String, label: String, fraction: Float)
}

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

class CancelledException : RuntimeException("The trace was cancelled before it finished.")

object Pipeline {

    fun stageIds(): List<String> = Stages.ids()

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
            if (classify || requested.auto.mode == AutoMode.APPLY) profile = Classify.profile(src)
            StageOps.orient(src, requested.preprocess)
        }

        val decision = Auto.decide(profile, requested)
        notes.addAll(decision.notes)
        val p = decision.params

        val frame = stage(Stages.PERSPECTIVE) { StageOps.perspective(oriented, p.preprocess, notes) }
        val working = stage(Stages.DOWNSCALE) { StageOps.downscale(frame, p.preprocess, notes) }

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
            notes.add(
                "The ${p.edge.engine} edge engine was requested but ${edge.engine} ran instead."
            )
        }

        val binary = stage(Stages.BINARISE) { StageOps.binarise(edge, p.edge) }
        val cleaned = stage(Stages.MORPHOLOGY) { StageOps.morphology(binary, p.cleanup, cancel) }
        val filtered = stage(Stages.BLOBS) { StageOps.blobFilter(cleaned, p.cleanup, notes, cancel) }
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
            StageOps.assemble(
                styled,
                working.width, working.height,
                frame.width, frame.height,
                p.output,
                crop.offsetX, crop.offsetY,
            )
        }

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

    fun runPreview(
        src: RgbaImage,
        params: TraceParams,
        longEdge: Int = Preview.DEFAULT_LONG_EDGE,
        cancel: CancellationToken = CancellationToken(),
    ): TraceResult = Preview.runPreview(src, params, longEdge, cancel)
}
