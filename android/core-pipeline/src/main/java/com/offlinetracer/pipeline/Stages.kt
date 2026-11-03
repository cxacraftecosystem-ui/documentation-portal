package com.offlinetracer.pipeline

import com.offlinetracer.imaging.Classify
import com.offlinetracer.imaging.Color
import com.offlinetracer.imaging.Components
import com.offlinetracer.imaging.Contrast
import com.offlinetracer.imaging.Denoise
import com.offlinetracer.imaging.Distance
import com.offlinetracer.imaging.EdgeCanny
import com.offlinetracer.imaging.EdgeDog
import com.offlinetracer.imaging.EdgeFlow
import com.offlinetracer.imaging.EdgeLog
import com.offlinetracer.imaging.EdgeModelRegistry
import com.offlinetracer.imaging.Geometry
import com.offlinetracer.imaging.GradientOp
import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.Mask
import com.offlinetracer.imaging.Matte
import com.offlinetracer.imaging.Morphology
import com.offlinetracer.imaging.Px
import com.offlinetracer.imaging.Resample
import com.offlinetracer.imaging.RgbaImage
import com.offlinetracer.imaging.SeShape
import com.offlinetracer.imaging.Thinning
import com.offlinetracer.imaging.Threshold
import com.offlinetracer.vector.FillRule
import com.offlinetracer.vector.LineCap
import com.offlinetracer.vector.LineJoin
import com.offlinetracer.vector.Mat2D
import com.offlinetracer.vector.StrokeStyle
import com.offlinetracer.vector.VecDocument
import com.offlinetracer.vector.VecLayer
import com.offlinetracer.vector.VecShape
import com.offlinetracer.vector.VecStyle
import com.offlinetracer.vector.VectorMode
import com.offlinetracer.vector.Vectorize
import com.offlinetracer.vector.VectorizeParams
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class Stage(val id: String, val label: String, val isTileLocal: Boolean)

object Stages {

    const val ORIENT = "orient"
    const val PERSPECTIVE = "perspective"
    const val DOWNSCALE = "downscale"
    const val GRAYSCALE = "grayscale"
    const val DENOISE = "denoise"
    const val CONTRAST = "contrast"
    const val MATTE = "matte"
    const val CROP = "crop"
    const val EDGE = "edge"
    const val BINARISE = "binarise"
    const val MORPHOLOGY = "morphology"
    const val BLOBS = "blobs"
    const val BRIDGE = "bridge"
    const val SKELETON = "skeleton"
    const val PRUNE = "prune"
    const val DISTANCE = "distance"
    const val VECTORISE = "vectorise"
    const val STYLE = "style"
    const val ASSEMBLE = "assemble"

    val ALL: List<Stage> = listOf(
        Stage(ORIENT, "Reading orientation", false),
        Stage(PERSPECTIVE, "Correcting perspective", false),
        Stage(DOWNSCALE, "Scaling to working size", false),
        Stage(GRAYSCALE, "Converting to greyscale", true),
        Stage(DENOISE, "Removing noise", true),
        Stage(CONTRAST, "Enhancing local contrast", false),
        Stage(MATTE, "Separating the background", false),
        Stage(CROP, "Cropping to the subject", false),
        Stage(EDGE, "Detecting edges", false),
        Stage(BINARISE, "Binarising", false),
        Stage(MORPHOLOGY, "Cleaning up", true),
        Stage(BLOBS, "Filtering specks", false),
        Stage(BRIDGE, "Bridging gaps", false),
        Stage(SKELETON, "Finding centrelines", false),
        Stage(PRUNE, "Pruning spurs", false),
        Stage(DISTANCE, "Measuring stroke width", false),
        Stage(VECTORISE, "Tracing paths", false),
        Stage(STYLE, "Styling strokes", false),
        Stage(ASSEMBLE, "Assembling the document", false),
    )

    private val index: Map<String, Stage> = LinkedHashMap<String, Stage>(ALL.size * 2).apply {
        for (s in ALL) put(s.id, s)
    }

    fun byId(id: String): Stage? = index[id]

    fun required(id: String): Stage =
        index[id] ?: error("\"$id\" is not a stage in the catalogue; the stage constants and Stages.ALL disagree")

    fun ids(): List<String> {
        val out = ArrayList<String>(ALL.size)
        for (s in ALL) out.add(s.id)
        return out
    }

    fun tileLocal(): List<Stage> {
        val out = ArrayList<Stage>(ALL.size)
        for (s in ALL) if (s.isTileLocal) out.add(s)
        return out
    }

    fun global(): List<Stage> {
        val out = ArrayList<Stage>(ALL.size)
        for (s in ALL) if (!s.isTileLocal) out.add(s)
        return out
    }
}

internal class EdgeOutcome(val response: GrayF, val mask: Mask?, val engine: EdgeEngine)

internal object StageOps {

    const val TILE_THRESHOLD = TilingPlan.TILE_THRESHOLD

    private const val MATTE_ALARM_FRACTION = 0.60f

    private val MODEL_FALLBACK = EdgeEngine.FDOG

    @Suppress("UNUSED_PARAMETER")
    fun orient(src: RgbaImage, p: PreprocessParams): RgbaImage = src

    fun perspective(src: RgbaImage, p: PreprocessParams, notes: MutableList<String>): RgbaImage {
        if (!p.perspectiveCorrect) return src
        val quad = Geometry.detectDocumentQuad(Color.toGray(src))
        if (quad == null || quad.size != 8) {
            notes.add(
                "Perspective correction was requested but no page-like quadrilateral was found, so " +
                    "the frame was left exactly as it came in."
            )
            return src
        }
        val ordered = Geometry.orderQuad(quad)
        val topW = edgeLength(ordered, 0, 1)
        val bottomW = edgeLength(ordered, 3, 2)
        val leftH = edgeLength(ordered, 0, 3)
        val rightH = edgeLength(ordered, 1, 2)
        val outW = max(8, ceil(max(topW, bottomW).toDouble()).toInt())
        val outH = max(8, ceil(max(leftH, rightH).toDouble()).toInt())
        val dst = floatArrayOf(
            0f, 0f,
            (outW - 1).toFloat(), 0f,
            (outW - 1).toFloat(), (outH - 1).toFloat(),
            0f, (outH - 1).toFloat(),
        )
        val homography = Geometry.solveHomography(ordered, dst)
        val warped = Geometry.warpPerspective(src, homography, outW, outH)
        notes.add(
            "Perspective correction rectified the page from ${src.width}x${src.height} to " +
                "${outW}x$outH, so the exported coordinates are in the rectified frame rather than " +
                "in the original photograph."
        )
        return warped
    }

    private fun edgeLength(quad: FloatArray, a: Int, b: Int): Float {
        val dx = (quad[b * 2] - quad[a * 2]).toDouble()
        val dy = (quad[b * 2 + 1] - quad[a * 2 + 1]).toDouble()
        return sqrt(dx * dx + dy * dy).toFloat()
    }

    fun downscale(src: RgbaImage, p: PreprocessParams, notes: MutableList<String>): RgbaImage {
        val fit = Resample.fitWithin(src.width, src.height, p.workingLongEdge)
        if (fit.size < 2 || (fit[0] == src.width && fit[1] == src.height)) return src
        notes.add(
            "The trace ran at ${fit[0]}x${fit[1]} instead of ${src.width}x${src.height} and the " +
                "vector result was scaled back up, so the export is at full size and nothing was " +
                "lost to the downscale."
        )
        return Resample.resize(src, fit[0], fit[1])
    }

    fun grayscale(src: RgbaImage, p: PreprocessParams): GrayF {
        val gray = Color.toGray(src)
        return if (p.invertInput) Contrast.invert(gray) else gray
    }

    private fun bilateralSpace(strength: Float): Float = 0.6f + 2.4f * strength

    private fun bilateralRange(strength: Float): Float = 0.04f + 0.22f * strength

    private fun anisotropicIterations(strength: Float): Int = 2 + (strength * 12f).roundToInt()

    private fun anisotropicKappa(strength: Float): Float = 0.03f + 0.17f * strength

    private fun denoiseRadius(p: PreprocessParams): Int = when (p.denoise) {
        DenoiseMode.NONE -> 0
        DenoiseMode.BILATERAL -> max(1, ceil(2.0 * bilateralSpace(p.denoiseStrength)).toInt())
        DenoiseMode.MEDIAN -> p.medianRadius
        DenoiseMode.ANISOTROPIC -> anisotropicIterations(p.denoiseStrength)
    }

    fun denoise(
        gray: GrayF,
        p: PreprocessParams,
        tiled: Boolean,
        notes: MutableList<String>,
        cancel: CancellationToken,
    ): GrayF {
        val plan = TilingPlan.decide(gray.width, gray.height)
        val useTiles = tiled && plan.tiled

        if (useTiles) notes.add(TilingPlan.note(plan))

        val support = denoiseRadius(p)
        if (p.denoise == DenoiseMode.NONE || support <= 0) return gray
        if (!useTiles) return denoiseWhole(gray, p, cancel)

        return TilingPlan.runGray(Stages.required(Stages.DENOISE), plan, support, gray) { patch ->
            denoiseWhole(patch, p, null)
        }
    }

    private fun denoiseWhole(gray: GrayF, p: PreprocessParams, cancel: CancellationToken?): GrayF =
        when (p.denoise) {
            DenoiseMode.NONE -> gray
            DenoiseMode.BILATERAL -> Denoise.bilateral(
                gray,
                bilateralSpace(p.denoiseStrength),
                bilateralRange(p.denoiseStrength),
            )
            DenoiseMode.MEDIAN -> Denoise.median(gray, p.medianRadius)
            DenoiseMode.ANISOTROPIC -> {
                val total = anisotropicIterations(p.denoiseStrength)
                val kappa = anisotropicKappa(p.denoiseStrength)
                var cur = gray
                var done = 0
                while (done < total) {
                    cancel?.throwIfCancelled()
                    val step = if (total - done > 2) 2 else total - done
                    cur = Denoise.anisotropicDiffusion(cur, step, kappa)
                    done += step
                }
                cur
            }
        }

    fun contrast(gray: GrayF, p: PreprocessParams, cancel: CancellationToken): GrayF {
        var g = gray
        if (p.claheEnabled) g = Contrast.clahe(g, p.claheTiles, p.claheTiles, p.claheClip)
        cancel.throwIfCancelled()
        if (p.gamma != 1f) g = Contrast.gamma(g, p.gamma)
        if (p.brightness != 0f || p.contrast != 0f) {
            g = Contrast.brightnessContrast(g, p.brightness, p.contrast)
        }
        cancel.throwIfCancelled()
        if (p.unsharpAmount > 0f) g = Contrast.unsharpMask(g, p.unsharpSigma, p.unsharpAmount)
        return g
    }

    fun matte(
        working: RgbaImage,
        gray: GrayF,
        p: MatteParams,
        notes: MutableList<String>,
    ): GrayF {
        if (p.mode == MatteMode.NONE) return gray
        val alpha = when (p.mode) {
            MatteMode.BORDER_FLOOD -> Matte.borderFlood(working, p.tolerance, p.feather)
            MatteMode.SALIENCY -> Matte.saliencyMatte(working, p.threshold, p.feather)
            MatteMode.SUBJECT -> {
                val found = Matte.subjectMatte(working, p.tolerance, p.feather)
                if (!found.confident) {
                    notes.add(
                        "The subject matte was not sure enough to remove the background, so the " +
                            "whole frame was traced with the background still in it. ${found.reason}"
                    )
                    return gray
                }
                found.alpha
            }
            MatteMode.NONE -> return gray
        }
        if (alpha.width != gray.width || alpha.height != gray.height) {
            notes.add(
                "The background matte produced a ${alpha.width}x${alpha.height} mask for a " +
                    "${gray.width}x${gray.height} image, so it was discarded and the whole frame was " +
                    "traced."
            )
            return gray
        }

        var kept = 0.0
        val ad = alpha.data
        for (i in ad.indices) kept += Px.clamp01(ad[i]).toDouble()
        val removed = if (ad.isEmpty()) 0f else (1.0 - kept / ad.size).toFloat()
        val name = when (p.mode) {
            MatteMode.BORDER_FLOOD -> "border-flood"
            MatteMode.SALIENCY -> "saliency"
            MatteMode.SUBJECT -> "subject"
            MatteMode.NONE -> "background"
        }
        notes.add(
            if (removed > MATTE_ALARM_FRACTION) {
                "The $name background matte removed ${percent(removed)}% of the frame — if part of " +
                    "your artwork is missing, that is where it went, so lower the matte tolerance or " +
                    "turn the matte off."
            } else {
                "The $name background matte removed ${percent(removed)}% of the frame."
            }
        )
        return Matte.applyMatte(gray, alpha, 1f)
    }

    internal class CropOutcome(val gray: GrayF, val offsetX: Int, val offsetY: Int)

    private const val CROP_PROXY_LONG_EDGE = 512

    private const val MIN_CROP_EDGE = 64

    fun cropToSubject(
        working: RgbaImage,
        gray: GrayF,
        p: TraceParams,
        notes: MutableList<String>,
    ): CropOutcome {
        val none = CropOutcome(gray, 0, 0)
        if (p.auto.mode != AutoMode.APPLY || !p.auto.allowCrop) return none
        if (working.width != gray.width || working.height != gray.height) return none

        val proxy = Resample.scaleToLongEdge(working, CROP_PROXY_LONG_EDGE)
        val find = Subject.locate(proxy, p.matte.tolerance)
        if (!find.confident) {
            notes.add("The working image was not cropped to the subject. ${find.reason}")
            return none
        }

        val w = gray.width
        val h = gray.height
        val sx = w.toDouble() / proxy.width.toDouble()
        val sy = h.toDouble() / proxy.height.toDouble()
        val x0 = Px.clamp(kotlin.math.floor(find.box.x * sx).toInt(), 0, w - 1)
        val y0 = Px.clamp(kotlin.math.floor(find.box.y * sy).toInt(), 0, h - 1)
        val x1 = Px.clamp(kotlin.math.ceil((find.box.x + find.box.w) * sx).toInt(), x0 + 1, w)
        val y1 = Px.clamp(kotlin.math.ceil((find.box.y + find.box.h) * sy).toInt(), y0 + 1, h)
        val cw = x1 - x0
        val ch = y1 - y0

        if (cw >= w && ch >= h) return none
        if (cw < MIN_CROP_EDGE || ch < MIN_CROP_EDGE) {
            notes.add(
                "The detected subject is only ${cw}x$ch px at the working size, which is too small " +
                    "to trace on its own, so the whole frame was traced instead."
            )
            return none
        }

        notes.add(
            "Cropped the working image to the detected subject: ${cw}x$ch px at ($x0, $y0) inside " +
                "the ${w}x$h frame, so the background no longer pulls the automatic thresholds. " +
                "The exported coordinates are still in the original frame."
        )
        return CropOutcome(Resample.crop(gray, x0, y0, cw, ch), x0, y0)
    }

    private fun suppressionFactor(sensitivity: Float): Float = 2f - 2f * sensitivity

    private fun cannySensitivity(sensitivity: Float): Float = 0.08f + 0.50f * sensitivity

    fun edges(
        gray: GrayF,
        p: EdgeParams,
        notes: MutableList<String>,
        cancel: CancellationToken,
    ): EdgeOutcome {
        var engine = p.engine
        if (engine == EdgeEngine.MODEL) {
            val model = if (p.modelId.isEmpty()) null else EdgeModelRegistry.byId(p.modelId)
            when {
                model == null -> notes.add(
                    if (p.modelId.isEmpty()) {
                        "A learned edge model was selected but no model was chosen, so the " +
                            "flow-based FDoG engine ran instead."
                    } else {
                        "The edge model \"${p.modelId}\" is not installed on this device, so the " +
                            "flow-based FDoG engine ran instead."
                    }
                )
                !model.isAvailable -> notes.add(
                    "The edge model \"${model.displayName}\" is installed but not ready to run, so " +
                        "the flow-based FDoG engine ran instead."
                )
                else -> {
                    val inferred = try {
                        model.infer(gray)
                    } catch (t: Throwable) {
                        notes.add(
                            "The edge model \"${model.displayName}\" failed while running " +
                                "(${describe(t)}), so the flow-based FDoG engine ran instead."
                        )
                        null
                    }
                    if (inferred != null) {
                        if (inferred.width == gray.width && inferred.height == gray.height) {
                            return EdgeOutcome(clamp01(inferred), null, EdgeEngine.MODEL)
                        }
                        notes.add(
                            "The edge model \"${model.displayName}\" returned a " +
                                "${inferred.width}x${inferred.height} map for a " +
                                "${gray.width}x${gray.height} image, so its result was discarded and " +
                                "the flow-based FDoG engine ran instead."
                        )
                    }
                }
            }
            engine = MODEL_FALLBACK
        }

        return when (engine) {
            EdgeEngine.CANNY -> {
                val mask = if (p.cannyLow < 0f || p.cannyHigh < 0f) {
                    EdgeCanny.detectAuto(gray, p.blurSigma, cannySensitivity(p.sensitivity))
                } else {
                    EdgeCanny.detect(gray, p.blurSigma, p.cannyLow, p.cannyHigh, GradientOp.SCHARR)
                }
                EdgeOutcome(mask.toGray(), mask, EdgeEngine.CANNY)
            }

            EdgeEngine.LOG -> {
                val slope = max(0f, p.logSlope * suppressionFactor(p.sensitivity))
                val mask = EdgeLog.detect(gray, p.logSigma, slope)
                EdgeOutcome(mask.toGray(), mask, EdgeEngine.LOG)
            }

            EdgeEngine.ADAPTIVE -> {
                val mask = if (p.useSauvola) {
                    Threshold.sauvola(gray, p.adaptiveRadius, sauvolaK(p.sensitivity), invert = true)
                } else {
                    val c = Px.clamp(p.adaptiveC * suppressionFactor(p.sensitivity), -1f, 1f)
                    Threshold.adaptiveMean(gray, p.adaptiveRadius, c, invert = true)
                }
                EdgeOutcome(mask.toGray(), mask, EdgeEngine.ADAPTIVE)
            }

            EdgeEngine.XDOG -> {
                val density = EdgeDog.xdog(
                    gray, p.dogSigma, p.dogK, p.dogTau, p.xdogEpsilon, p.xdogPhi,
                )
                EdgeOutcome(Contrast.invert(density), null, EdgeEngine.XDOG)
            }

            EdgeEngine.FDOG, EdgeEngine.MODEL -> {
                cancel.throwIfCancelled()
                val tensor = EdgeFlow.structureTensorFlow(gray, p.flow.tensorSigma)
                cancel.throwIfCancelled()
                val field = EdgeFlow.refineEtf(tensor, p.flow.etfIterations, p.flow.etfRadius)
                cancel.throwIfCancelled()
                val density = EdgeFlow.fdog(
                    gray, field,
                    p.flow.sigmaC, p.flow.sigmaM, p.flow.tau, p.flow.fdogIterations,
                    p.xdogEpsilon, p.xdogPhi,
                )
                EdgeOutcome(Contrast.invert(density), null, EdgeEngine.FDOG)
            }
        }
    }

    private fun sauvolaK(sensitivity: Float): Float = 0.34f - 0.28f * sensitivity

    fun binarise(edge: EdgeOutcome, p: EdgeParams): Mask {
        val direct = edge.mask
        if (direct != null) return direct
        val otsu = Threshold.otsu(edge.response)
        val t = Px.clamp(otsu * (1.6f - 1.2f * p.sensitivity), 0.01f, 0.99f)
        return Threshold.hysteresis(edge.response, t * 0.5f, t)
    }

    fun morphology(mask: Mask, p: CleanupParams, cancel: CancellationToken): Mask {
        var m = mask
        val support = 2 * (p.closeRadius + p.openRadius)
        if (support > 0) {
            val plan = TilingPlan.decide(mask.width, mask.height)
            m = if (plan.tiled) {
                TilingPlan.runMask(Stages.required(Stages.MORPHOLOGY), plan, support, m) { patch ->
                    closeThenOpen(patch, p, null)
                }
            } else {
                closeThenOpen(m, p, cancel)
            }
        }
        cancel.throwIfCancelled()
        if (p.fillHolesUpTo > 0) m = Components.fillHoles(m, p.fillHolesUpTo)
        return m
    }

    private fun closeThenOpen(mask: Mask, p: CleanupParams, cancel: CancellationToken?): Mask {
        var m = mask
        if (p.closeRadius > 0) m = Morphology.close(m, p.closeRadius, SeShape.ELLIPSE)
        cancel?.throwIfCancelled()
        if (p.openRadius > 0) m = Morphology.open(m, p.openRadius, SeShape.ELLIPSE)
        return m
    }

    fun blobFilter(
        mask: Mask,
        p: CleanupParams,
        notes: MutableList<String>,
        cancel: CancellationToken,
    ): Mask {
        var m = mask
        if (p.removeIsolated) m = Components.removeIsolated(m, 1, 8)
        cancel.throwIfCancelled()

        if (p.minBlobArea > 1) {
            val labels = Components.label(m, 8)
            var small = 0
            for (l in 1..labels.count) if (labels.area[l] < p.minBlobArea) small++
            m = Components.removeSmallBlobs(m, p.minBlobArea)
            if (small > 0) {
                notes.add(
                    "Removed $small of ${labels.count} ${noun(labels.count, "region", "regions")} " +
                        "for being smaller than ${p.minBlobArea} pixels."
                )
            }
        }
        cancel.throwIfCancelled()

        if (p.removeBorderTouching) {
            val labels = Components.label(m, 8)
            var touching = 0
            val lastX = labels.width - 1
            val lastY = labels.height - 1
            for (l in 1..labels.count) {
                val b = l * 4
                if (labels.bounds[b] == 0 || labels.bounds[b + 1] == 0 ||
                    labels.bounds[b + 2] == lastX || labels.bounds[b + 3] == lastY
                ) {
                    touching++
                }
            }
            m = Components.removeBorderTouching(m)
            if (touching > 0) {
                notes.add(
                    "Discarded $touching ${noun(touching, "region", "regions")} touching the frame " +
                        "edge, which is usually the scan border rather than the artwork."
                )
            }
        }
        cancel.throwIfCancelled()

        if (p.keepLargest > 0) {
            val total = Components.label(m, 8).count
            val dropped = if (total > p.keepLargest) total - p.keepLargest else 0
            m = Components.keepLargest(m, p.keepLargest)
            if (dropped > 0) {
                notes.add(
                    "Kept only the ${p.keepLargest} largest " +
                        "${noun(p.keepLargest, "region", "regions")} and discarded $dropped."
                )
            }
        }
        return m
    }

    fun bridge(
        mask: Mask,
        p: CleanupParams,
        notes: MutableList<String>,
        cancel: CancellationToken,
    ): Mask {
        if (!p.bridgeGaps || p.maxGap <= 0) return mask
        val scratch = thin(mask, p)
        cancel.throwIfCancelled()
        val bridged = Thinning.bridgeEndpoints(scratch, p.maxGap, p.maxBridgeAngle)
        val added = bridged.subtract(scratch)
        val runs = Components.label(added, 8).count
        if (runs == 0) return mask
        notes.add(
            "Bridged $runs ${noun(runs, "gap", "gaps")} of up to ${p.maxGap} px between stroke ends."
        )
        return mask.or(added)
    }

    fun thin(mask: Mask, p: CleanupParams): Mask =
        if (p.thinning == ThinningMode.GUO_HALL) Thinning.guoHall(mask) else Thinning.zhangSuen(mask)

    fun prune(mask: Mask, p: CleanupParams): Mask =
        if (p.pruneSpurs > 0) Thinning.pruneSpurs(mask, p.pruneSpurs) else mask

    fun distance(fullWidthMask: Mask): GrayF = Distance.euclidean(fullWidthMask, insideForeground = true)

    fun vectorise(
        mask: Mask,
        o: OutputParams,
        dt: GrayF?,
        notes: MutableList<String>,
        cancel: CancellationToken,
    ): List<VecShape> {
        cancel.throwIfCancelled()
        val params = VectorizeParams(
            mode = if (o.vectorMode == VectorModeParam.OUTLINE) VectorMode.OUTLINE else VectorMode.CENTERLINE,
            simplifyEpsilon = o.simplify,
            fitError = o.fitError,
            cornerThresholdDegrees = o.corner,
            smoothIterations = o.smoothIterations,
            minPathLength = 0f,
            strokeWidth = o.strokeWidth,
            modulateWidth = o.modulateWidth && dt != null,
            widthScale = o.widthScale,
            minWidth = 0.4f,
            maxWidth = max(12f, o.strokeWidth * 6f),
            fillRegions = o.fillClosed,
        )
        val traced = Vectorize.run(mask, params, dt)
        cancel.throwIfCancelled()
        if (o.minPathLength <= 0f || traced.isEmpty()) return traced

        val kept = ArrayList<VecShape>(traced.size)
        var i = 0
        for (shape in traced) {
            if ((i and 0xFF) == 0) cancel.throwIfCancelled()
            i++
            if (shape.path.length() >= o.minPathLength) kept.add(shape)
        }
        val dropped = traced.size - kept.size
        if (dropped > 0) {
            notes.add(
                "Dropped $dropped of ${traced.size} traced " +
                    "${noun(traced.size, "path", "paths")} for being shorter than " +
                    "${number(o.minPathLength)} px, keeping ${kept.size}."
            )
        }
        return kept
    }

    fun style(shapes: List<VecShape>, o: OutputParams): List<VecShape> {
        if (shapes.isEmpty()) return shapes
        val out = ArrayList<VecShape>(shapes.size)
        for (shape in shapes) {
            val widths = shape.path.strokeWidths
            if (o.modulateWidth && widths != null && widths.size >= 2) {
                val outline = StrokeStyle.variableWidthOutline(shape.path, widths, LineCap.ROUND)
                if (!outline.isEmpty()) {
                    out.add(
                        VecShape(
                            outline.copy(id = shape.path.id),
                            VecStyle(
                                stroke = null,
                                strokeWidth = o.strokeWidth,
                                fill = o.strokeColor,
                                fillRule = FillRule.NONZERO,
                                cap = LineCap.ROUND,
                                join = LineJoin.ROUND,
                            ),
                        )
                    )
                    continue
                }
            }
            out.add(
                VecShape(
                    shape.path,
                    VecStyle(
                        stroke = o.strokeColor,
                        strokeWidth = o.strokeWidth,
                        fill = if (o.fillClosed && shape.path.closed) o.strokeColor else null,
                        fillRule = FillRule.EVENODD,
                        cap = LineCap.ROUND,
                        join = LineJoin.ROUND,
                    ),
                )
            )
        }
        return out
    }

    fun assemble(
        shapes: List<VecShape>,
        workingWidth: Int,
        workingHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
        o: OutputParams,
        offsetX: Int = 0,
        offsetY: Int = 0,
    ): VecDocument {
        val layer = VecLayer(id = "trace", name = "Trace", shapes = shapes)
        val working = VecDocument(
            workingWidth.toFloat(),
            workingHeight.toFloat(),
            listOf(layer),
            o.background,
        )
        val scaled = workingWidth != frameWidth || workingHeight != frameHeight
        if (!scaled && offsetX == 0 && offsetY == 0) return working

        val sx = frameWidth.toFloat() / workingWidth.toFloat()
        val sy = frameHeight.toFloat() / workingHeight.toFloat()
        val map = Mat2D.scale(sx, sy) * Mat2D.translate(offsetX.toFloat(), offsetY.toFloat())
        val out = working.transform(map)
        return out.copy(width = frameWidth.toFloat(), height = frameHeight.toFloat())
    }

    private fun clamp01(src: GrayF): GrayF {
        val d = src.data
        var needs = false
        for (v in d) {
            if (v < 0f || v > 1f || v.isNaN()) {
                needs = true
                break
            }
        }
        if (!needs) return src
        val out = FloatArray(d.size)
        for (i in d.indices) out[i] = if (d[i].isNaN()) 0f else Px.clamp01(d[i])
        return GrayF(src.width, src.height, out)
    }

    private fun noun(n: Int, one: String, many: String): String = if (n == 1) one else many

    private fun number(v: Float): String {
        if (!v.isFinite() || v < 0f) return "0"
        val tenths = (v * 10f).roundToInt()
        val whole = tenths / 10
        val frac = tenths % 10
        return if (frac == 0) whole.toString() else "$whole.$frac"
    }

    private fun percent(fraction: Float): Int =
        Px.clamp((if (fraction.isNaN()) 0f else fraction * 100f).roundToInt(), 0, 100)

    private fun describe(t: Throwable): String {
        val name = t::class.simpleName ?: "error"
        val message = t.message
        return if (message.isNullOrBlank()) name else "$name: ${message.trim()}"
    }
}
