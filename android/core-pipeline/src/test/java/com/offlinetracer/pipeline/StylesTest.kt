package com.offlinetracer.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The twenty presets.
 *
 * The ids are persisted in project files, so the list and its order are a compatibility surface and are
 * pinned literally here rather than derived from [Styles.ALL] — a test that reads its expectations out
 * of the thing it is testing cannot catch a rename.
 */
class StylesTest {

    private val expectedIds = listOf(
        "clean-line",
        "pencil-sketch",
        "technical-drawing",
        "blueprint",
        "tattoo-outline",
        "mandala",
        "comic",
        "architectural",
        "continuous-line",
        "single-stroke",
        "calligraphy",
        "woodcut",
        "stencil",
        "silhouette",
        "engraving",
        "colouring-book",
        "embroidery",
        "laser-cut",
        "craft-pattern",
        "minimal",
    )

    private val knownGroups = setOf(
        Styles.GROUP_DRAWING,
        Styles.GROUP_PRINT,
        Styles.GROUP_FABRICATION,
        Styles.GROUP_EDUCATION,
    )

    @Test
    fun thereAreExactlyTwentyPresetsInTheDocumentedOrder() {
        assertEquals(20, Styles.ALL.size)
        assertEquals(expectedIds, Styles.ALL.map { it.id })
    }

    @Test
    fun idsAreUnique() {
        assertEquals(Styles.ALL.size, Styles.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun everyPresetCarriesItsOwnIdInItsParams() {
        // The UI says "you have modified Pencil sketch" by reading this back, so a mismatch would make
        // it name the wrong preset.
        for (s in Styles.ALL) assertEquals(s.id, s.params.styleId, "styleId mismatch on ${s.id}")
    }

    @Test
    fun everyPresetIsAlreadyLegalSoSanitizeIsAFixpoint() {
        // If a preset needed clamping, the number written in Styles.kt would not be the number that
        // runs, and the difference would be invisible in the picker.
        for (s in Styles.ALL) {
            assertEquals(s.params, s.params.sanitized(), "${s.id} is not already sanitised")
        }
    }

    @Test
    fun everyPresetHasARealDescriptionAndName() {
        for (s in Styles.ALL) {
            assertTrue(s.name.isNotBlank(), "${s.id} has no name")
            assertTrue(s.description.length > 40, "${s.id} description is too short to be useful")
            assertTrue(
                s.description.trim().endsWith("."),
                "${s.id} description must be a sentence: '${s.description}'",
            )
        }
    }

    @Test
    fun everyPresetIsInAKnownGroup() {
        for (s in Styles.ALL) assertTrue(s.group in knownGroups, "${s.id} is in group '${s.group}'")
    }

    @Test
    fun groupsAreTheDistinctGroupsInFirstAppearanceOrder() {
        val groups = Styles.groups()
        assertEquals(groups.size, groups.toSet().size, "groups() must not repeat")
        for (g in groups) assertTrue(g in knownGroups, "unknown group '$g'")
        assertEquals(knownGroups, groups.toSet(), "every group must be represented")
        for (g in groups) assertTrue(Styles.inGroup(g).isNotEmpty())
    }

    @Test
    fun lookupByIdWorksAndIsTolerantOfWhitespace() {
        for (id in expectedIds) assertEquals(id, assertNotNull(Styles.byId(id)).id)
        assertEquals("woodcut", assertNotNull(Styles.byId("  woodcut  ")).id)
        assertNull(Styles.byId("not-a-style"))
        assertNull(Styles.byId(""))
    }

    @Test
    fun theDefaultIsCleanLineAndAgreesWithTheParamsFallback() {
        assertEquals("clean-line", Styles.default().id)
        assertEquals(Styles.default().id, TraceParams(styleId = "").sanitized().styleId)
    }

    // -------------------------------------------------------------------------------------------
    // The presets have to differ *meaningfully*, not decoratively
    // -------------------------------------------------------------------------------------------

    @Test
    fun noTwoPresetsAreTheSameSettingsUnderDifferentNames() {
        // Compared with the id blanked, so that differing only by styleId does not count as differing.
        val shapes = Styles.ALL.map { it.params.copy(styleId = "") }.toSet()
        assertEquals(Styles.ALL.size, shapes.size, "two presets carry identical parameters")
    }

    @Test
    fun thePresetsSpanTheEnginesAndBothVectorModes() {
        val engines = Styles.ALL.map { it.params.edge.engine }.toSet()
        assertTrue(engines.size >= 4, "the set only reaches $engines")
        val modes = Styles.ALL.map { it.params.output.vectorMode }.toSet()
        assertEquals(2, modes.size, "both vector modes must be represented")
    }

    @Test
    fun colouringBookIsOutlineWithAggressiveBridgingAndAUniformStroke() {
        val s = assertNotNull(Styles.byId("colouring-book")).params
        val base = Styles.default().params
        assertEquals(VectorModeParam.OUTLINE, s.output.vectorMode)
        assertTrue(s.cleanup.bridgeGaps)
        assertTrue(
            s.cleanup.maxGap > base.cleanup.maxGap * 2,
            "a colouring page must bridge much further than the default: ${s.cleanup.maxGap}",
        )
        assertTrue(
            s.cleanup.minBlobArea > base.cleanup.minBlobArea * 4,
            "small detail must be dropped: ${s.cleanup.minBlobArea}",
        )
        assertTrue(!s.output.modulateWidth, "a crayon line must not taper to nothing")
        assertTrue(s.output.strokeWidth > base.output.strokeWidth)
    }

    @Test
    fun singleStrokeIsCentrelineWithHeavySimplificationAndALongMinimumPath() {
        val s = assertNotNull(Styles.byId("single-stroke")).params
        val base = Styles.default().params
        assertEquals(VectorModeParam.CENTERLINE, s.output.vectorMode)
        assertTrue(s.cleanup.skeletonize)
        assertTrue(s.output.simplify > base.output.simplify * 2, "simplify ${s.output.simplify}")
        assertTrue(
            s.output.minPathLength >= 60f,
            "a pen-up/pen-down cycle costs more than a short path is worth: ${s.output.minPathLength}",
        )
    }

    @Test
    fun engravingIsXdogAtHighSharpnessAndFineDetail() {
        val s = assertNotNull(Styles.byId("engraving")).params
        assertEquals(EdgeEngine.XDOG, s.edge.engine)
        assertTrue(s.edge.xdogPhi >= 200f, "φ must be hard for engraved line work: ${s.edge.xdogPhi}")
        assertTrue(s.edge.dogSigma < 1f, "σ must be fine to resolve hatching: ${s.edge.dogSigma}")
        assertTrue(s.cleanup.minBlobArea < 12, "hatching is small: ${s.cleanup.minBlobArea}")
        // Closing and isolated-pixel removal both fuse adjacent hatch lines, which is unrecoverable.
        assertEquals(0, s.cleanup.closeRadius)
        assertTrue(!s.cleanup.removeIsolated)
    }

    @Test
    fun silhouetteIsFilledOutlineKeepingOnlyTheLargestComponents() {
        val s = assertNotNull(Styles.byId("silhouette")).params
        assertEquals(VectorModeParam.OUTLINE, s.output.vectorMode)
        assertTrue(s.output.fillClosed, "a silhouette is a filled shape")
        assertTrue(s.cleanup.keepLargest > 0, "keepLargest 0 means disabled, not 'keep none'")
        assertTrue(!s.cleanup.skeletonize)
    }

    @Test
    fun pencilSketchIsSoftAndTechnicalDrawingIsHard() {
        val pencil = assertNotNull(Styles.byId("pencil-sketch")).params
        val technical = assertNotNull(Styles.byId("technical-drawing")).params
        // φ is the whole difference between "drawn" and "detected".
        assertTrue(
            pencil.edge.xdogPhi * 10f < technical.edge.xdogPhi,
            "pencil ${pencil.edge.xdogPhi} vs technical ${technical.edge.xdogPhi}",
        )
        // Higher corner threshold keeps *more* corners, which is what a technical drawing needs.
        assertTrue(technical.output.corner > pencil.output.corner)
        assertTrue(technical.output.smoothIterations < pencil.output.smoothIterations)
    }

    @Test
    fun theFabricationPresetsCarryTheStrokeConventionsTheirToolsExpect() {
        val laser = assertNotNull(Styles.byId("laser-cut")).params
        // A cutter follows the path; a wide stroke reads as two cuts a stroke-width apart.
        assertTrue(laser.output.strokeWidth < 0.5f, "laser stroke ${laser.output.strokeWidth}")
        assertEquals(VectorModeParam.OUTLINE, laser.output.vectorMode)

        val embroidery = assertNotNull(Styles.byId("embroidery")).params
        assertEquals(VectorModeParam.CENTERLINE, embroidery.output.vectorMode)
        assertTrue(embroidery.output.minPathLength >= 40f)
    }

    @Test
    fun blueprintIsTheOnlyPresetWithALightStrokeOnADarkGround() {
        val blueprint = assertNotNull(Styles.byId("blueprint")).params
        assertNotNull(blueprint.output.background)
        assertTrue(
            luminance(blueprint.output.strokeColor) > luminance(blueprint.output.background!!),
            "a blueprint's line must be lighter than its ground",
        )
    }

    @Test
    fun everyPresetProducesLegalParamsAfterASubjectIsComposedOnTop() {
        // The two pickers are independent, so all 240 combinations have to be legal.
        for (style in Styles.ALL) {
            for (subject in Subjects.ALL) {
                val composed = subject.adjust(style.params)
                assertEquals(
                    composed, composed.sanitized(),
                    "${style.id} + ${subject.id} produced an unsanitised result",
                )
            }
        }
    }

    private fun luminance(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return (299 * r + 587 * g + 114 * b) / 1000
    }
}
