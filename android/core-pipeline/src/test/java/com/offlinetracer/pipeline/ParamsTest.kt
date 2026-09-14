package com.offlinetracer.pipeline

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The clamping choke point and the on-disk format.
 *
 * Every other file in this module reads parameters as given, on the strength of `sanitized()` having
 * already run. So these assertions are not paperwork: a field this test forgets is a field some stage
 * will eventually receive a NaN in.
 */
class ParamsTest {

    /** Matches [ProjectCodec]'s configuration, which is what a real save/load goes through. */
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // -------------------------------------------------------------------------------------------
    // Clamping
    // -------------------------------------------------------------------------------------------

    private fun outOfRange(): TraceParams = TraceParams(
        preprocess = PreprocessParams(
            workingLongEdge = 99_999,
            denoiseStrength = 7f,
            medianRadius = -4,
            claheClip = 0f,
            claheTiles = 0,
            brightness = -9f,
            contrast = 9f,
            gamma = 0f,
            unsharpAmount = -1f,
            unsharpSigma = 0f,
        ),
        matte = MatteParams(tolerance = 4f, feather = -1f, threshold = 9f),
        edge = EdgeParams(
            sensitivity = 5f,
            blurSigma = 500f,
            dogSigma = 0f,
            dogK = 0.1f,
            dogTau = 3f,
            xdogEpsilon = 40f,
            xdogPhi = 0f,
            flow = FlowSettings(
                tensorSigma = 0f,
                etfIterations = -3,
                etfRadius = 0,
                sigmaC = -1f,
                sigmaM = 999f,
                tau = 1f,
                fdogIterations = 0,
            ),
            adaptiveRadius = 0,
            adaptiveC = -7f,
            logSigma = 0f,
            logSlope = 6f,
            modelId = "  spaced  ",
        ),
        cleanup = CleanupParams(
            minBlobArea = -5,
            closeRadius = 999,
            openRadius = -1,
            maxGap = 99_999,
            maxBridgeAngle = 400f,
            pruneSpurs = -2,
            fillHolesUpTo = -9,
            keepLargest = -1,
        ),
        output = OutputParams(
            simplify = -3f,
            fitError = 0f,
            corner = 400f,
            smoothIterations = 99,
            strokeWidth = 0f,
            widthScale = 0f,
            minPathLength = -1f,
        ),
        styleId = "   ",
    )

    @Test
    fun sanitizeClampsEveryPreprocessField() {
        val p = outOfRange().sanitized().preprocess
        assertEquals(8192, p.workingLongEdge)
        assertEquals(1f, p.denoiseStrength)
        assertEquals(0, p.medianRadius)
        assertEquals(1f, p.claheClip)
        assertEquals(1, p.claheTiles)
        assertEquals(-1f, p.brightness)
        assertEquals(1f, p.contrast)
        assertEquals(0.05f, p.gamma)
        assertEquals(0f, p.unsharpAmount)
        assertEquals(0.05f, p.unsharpSigma)
    }

    @Test
    fun sanitizeClampsEveryMatteField() {
        val m = outOfRange().sanitized().matte
        assertEquals(1f, m.tolerance)
        assertEquals(0f, m.feather)
        assertEquals(1f, m.threshold)
    }

    @Test
    fun sanitizeClampsEveryEdgeField() {
        val e = outOfRange().sanitized().edge
        assertEquals(1f, e.sensitivity)
        assertEquals(16f, e.blurSigma)
        assertEquals(0.05f, e.dogSigma)
        // k <= 1 makes the "coarse" Gaussian the finer of the two and the difference changes sign.
        assertEquals(1.05f, e.dogK)
        assertEquals(0.999f, e.dogTau)
        assertEquals(2f, e.xdogEpsilon)
        assertEquals(0.1f, e.xdogPhi)
        assertEquals(1, e.adaptiveRadius)
        assertEquals(-1f, e.adaptiveC)
        assertEquals(0.05f, e.logSigma)
        assertEquals(1f, e.logSlope)
        assertEquals("spaced", e.modelId)

        assertEquals(0.05f, e.flow.tensorSigma)
        assertEquals(0, e.flow.etfIterations)
        assertEquals(1, e.flow.etfRadius)
        assertEquals(0.05f, e.flow.sigmaC)
        assertEquals(32f, e.flow.sigmaM)
        assertEquals(0.999f, e.flow.tau)
        assertEquals(1, e.flow.fdogIterations)
    }

    @Test
    fun sanitizeClampsEveryCleanupField() {
        val c = outOfRange().sanitized().cleanup
        assertEquals(0, c.minBlobArea)
        assertEquals(64, c.closeRadius)
        assertEquals(0, c.openRadius)
        assertEquals(1024, c.maxGap)
        assertEquals(180f, c.maxBridgeAngle)
        assertEquals(0, c.pruneSpurs)
        assertEquals(0, c.fillHolesUpTo)
        assertEquals(0, c.keepLargest)
    }

    @Test
    fun sanitizeClampsEveryOutputField() {
        val o = outOfRange().sanitized().output
        assertEquals(0f, o.simplify)
        // A non-positive fit error asks the fitter to subdivide to its depth cap on every curve.
        assertEquals(0.01f, o.fitError)
        assertEquals(180f, o.corner)
        assertEquals(8, o.smoothIterations)
        assertEquals(0.01f, o.strokeWidth)
        assertEquals(0.01f, o.widthScale)
        assertEquals(0f, o.minPathLength)
    }

    @Test
    fun aBlankStyleIdBecomesTheDefaultStyle() {
        assertEquals("clean-line", outOfRange().sanitized().styleId)
        assertEquals("comic", TraceParams(styleId = "  comic  ").sanitized().styleId)
    }

    @Test
    fun nanBecomesTheDocumentedFallbackAndNeverPropagates() {
        val p = TraceParams(
            preprocess = PreprocessParams(gamma = Float.NaN, denoiseStrength = Float.NaN),
            edge = EdgeParams(sensitivity = Float.NaN, xdogPhi = Float.NaN),
            output = OutputParams(strokeWidth = Float.NaN, minPathLength = Float.NaN),
        ).sanitized()
        assertEquals(1f, p.preprocess.gamma)
        assertEquals(0.5f, p.preprocess.denoiseStrength)
        assertEquals(0.5f, p.edge.sensitivity)
        assertEquals(20f, p.edge.xdogPhi)
        assertEquals(1.6f, p.output.strokeWidth)
        assertEquals(3f, p.output.minPathLength)
    }

    @Test
    fun infinitiesAreClampedRatherThanCarried() {
        val p = TraceParams(
            edge = EdgeParams(blurSigma = Float.POSITIVE_INFINITY),
            output = OutputParams(simplify = Float.NEGATIVE_INFINITY, fitError = Float.POSITIVE_INFINITY),
        ).sanitized()
        assertEquals(16f, p.edge.blurSigma)
        assertEquals(0f, p.output.simplify)
        assertEquals(64f, p.output.fitError)
    }

    @Test
    fun theAutoCannySentinelHasExactlyOneRepresentation() {
        val auto = TraceParams(edge = EdgeParams(cannyLow = -0.3f, cannyHigh = -99f)).sanitized()
        assertEquals(EdgeParams.AUTO_THRESHOLD, auto.edge.cannyLow)
        assertEquals(EdgeParams.AUTO_THRESHOLD, auto.edge.cannyHigh)
        assertEquals(-1f, auto.edge.cannyLow)

        // Reversed explicit thresholds are normalised here so the UI and the detector cannot disagree
        // about which one is the high one.
        val swapped = TraceParams(edge = EdgeParams(cannyLow = 0.8f, cannyHigh = 0.2f)).sanitized()
        assertEquals(0.2f, swapped.edge.cannyLow)
        assertEquals(0.8f, swapped.edge.cannyHigh)
    }

    @Test
    fun sanitizeClampsEveryAutoField() {
        val p = TraceParams(
            auto = AutoParams(
                subjectId = "  textile  ",
                handTuned = setOf("  edge sensitivity  ", "", "   ", "edge sensitivity"),
                minConfidence = 9f,
            ),
        ).sanitized().auto
        assertEquals("textile", p.subjectId)
        // Trimmed and de-duplicated: two spellings of one label would otherwise protect a knob in one
        // build and leave it unprotected in the next.
        assertEquals(setOf("edge sensitivity"), p.handTuned)
        assertEquals(1f, p.minConfidence)

        val floored = TraceParams(auto = AutoParams(minConfidence = 0f)).sanitized().auto
        assertTrue(
            floored.minConfidence >= 0.05f,
            "a floor of 0 means 'act on anything', which is what the floor exists to prevent",
        )
        val nan = TraceParams(auto = AutoParams(minConfidence = Float.NaN)).sanitized().auto
        assertEquals(0.55f, nan.minConfidence)
    }

    @Test
    fun theDefaultAutoModeSuggestsRatherThanApplies() {
        // A library default may not rewrite a caller's explicit settings; see AutoParams' own note.
        assertEquals(AutoMode.SUGGEST, TraceParams().auto.mode)
        assertEquals(AutoMode.SUGGEST, TraceParams().sanitized().auto.mode)
    }

    @Test
    fun sanitizeIsAFixpointForEveryInputIncludingHostileOnes() {
        val cases = listOf(
            TraceParams(),
            outOfRange(),
            TraceParams(preprocess = PreprocessParams(gamma = Float.NaN)),
            TraceParams(edge = EdgeParams(cannyLow = -0.3f)),
            TraceParams(auto = AutoParams(minConfidence = Float.NaN, subjectId = " x ")),
            TraceParams(auto = AutoParams(handTuned = setOf(" a ", "a", ""))),
        )
        for (c in cases) {
            val once = c.sanitized()
            assertEquals(once, once.sanitized(), "sanitized() must be idempotent")
        }
    }

    // -------------------------------------------------------------------------------------------
    // Serialisation
    // -------------------------------------------------------------------------------------------

    @Test
    fun serializationRoundTripsExactly() {
        val original = TraceParams(
            preprocess = PreprocessParams(workingLongEdge = 1536, invertInput = true, gamma = 1.25f),
            matte = MatteParams(mode = MatteMode.SALIENCY, threshold = 0.42f),
            edge = EdgeParams(engine = EdgeEngine.XDOG, xdogPhi = 123.5f, modelId = "pidinet"),
            cleanup = CleanupParams(thinning = ThinningMode.GUO_HALL, keepLargest = 7),
            auto = AutoParams(
                mode = AutoMode.APPLY,
                subjectId = "pottery",
                handTuned = setOf("simplify"),
                minConfidence = 0.7f,
                allowCrop = false,
            ),
            output = OutputParams(
                vectorMode = VectorModeParam.OUTLINE,
                background = 0xFF102030.toInt(),
                fillClosed = true,
            ),
            styleId = "woodcut",
        )
        val text = json.encodeToString(TraceParams.serializer(), original)
        assertEquals(original, json.decodeFromString(TraceParams.serializer(), text))
    }

    @Test
    fun aNullBackgroundSurvivesTheRoundTripAsNullAndNotAsBlack() {
        val original = TraceParams(output = OutputParams(background = null))
        val decoded = json.decodeFromString(
            TraceParams.serializer(),
            json.encodeToString(TraceParams.serializer(), original),
        )
        assertEquals(null, decoded.output.background)
        assertNotEquals(0, decoded.output.background)
    }

    @Test
    fun jsonFromAnOlderBuildMissingWholeSubTreesStillDecodes() {
        // Everything but one nested field is absent, which is what a file written before those fields
        // existed looks like.
        val old = """{"preprocess":{"workingLongEdge":1024},"styleId":"comic"}"""
        val decoded = json.decodeFromString(TraceParams.serializer(), old)
        assertEquals(1024, decoded.preprocess.workingLongEdge)
        assertEquals(PreprocessParams().claheEnabled, decoded.preprocess.claheEnabled)
        assertEquals(MatteParams(), decoded.matte)
        assertEquals(EdgeParams(), decoded.edge)
        assertEquals(CleanupParams(), decoded.cleanup)
        assertEquals(OutputParams(), decoded.output)
        assertEquals("comic", decoded.styleId)
    }

    @Test
    fun anEmptyJsonObjectDecodesToTheDefaults() {
        assertEquals(TraceParams(), json.decodeFromString(TraceParams.serializer(), "{}"))
    }

    @Test
    fun fieldsFromANewerBuildAreIgnoredRatherThanFatal() {
        val future = """
            {"preprocess":{"workingLongEdge":1024,"quantumDenoise":true},
             "somethingEntirelyNew":{"a":1,"b":[2,3]},
             "styleId":"minimal"}
        """.trimIndent()
        val decoded = json.decodeFromString(TraceParams.serializer(), future)
        assertEquals(1024, decoded.preprocess.workingLongEdge)
        assertEquals("minimal", decoded.styleId)
    }

    @Test
    fun anEnumConstantThisBuildDoesNotKnowFallsBackToTheDefault() {
        // A build that shipped a sixth denoise mode, opened by one that did not.
        val old = """{"preprocess":{"denoise":"WAVELET"}}"""
        val decoded = json.decodeFromString(TraceParams.serializer(), old)
        assertEquals(PreprocessParams().denoise, decoded.preprocess.denoise)
    }

    @Test
    fun anIllegalValueOnDiskIsClampedOnTheWayIn() {
        val hostile = """{"preprocess":{"workingLongEdge":2000000},"output":{"fitError":-4.0}}"""
        val decoded = json.decodeFromString(TraceParams.serializer(), hostile).sanitized()
        assertEquals(8192, decoded.preprocess.workingLongEdge)
        assertTrue(decoded.output.fitError > 0f)
    }
}
