package com.offlinetracer.pipeline

import com.offlinetracer.imaging.Classify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Subject presets, and the two invariants that make them compose with the twenty styles instead of
 * fighting them: a subject adjusts, and a subject never touches the style's identity.
 */
class SubjectsTest {

    private val expectedIds = listOf(
        "painting",
        "pottery",
        "textile",
        "jewellery",
        "sculpture",
        "architecture",
        "logo",
        "sketch",
        "photo",
        "wood-carving",
        "stone-carving",
        "metalwork",
    )

    private fun profile(
        bimodality: Float = 0.2f,
        edgeDensity: Float = 0.05f,
        orientationEntropy: Float = 0.9f,
        saturationSpread: Float = 0.3f,
        isLineArt: Boolean = false,
        isHighTexture: Boolean = false,
        isFlatGraphic: Boolean = false,
    ) = Classify.SourceProfile(
        bimodality = bimodality,
        edgeDensity = edgeDensity,
        orientationEntropy = orientationEntropy,
        saturationSpread = saturationSpread,
        isLineArt = isLineArt,
        isHighTexture = isHighTexture,
        isFlatGraphic = isFlatGraphic,
        suggestion = "synthetic",
    )

    @Test
    fun theDocumentedSubjectsAreAllPresentInOrder() {
        assertEquals(expectedIds, Subjects.ALL.map { it.id })
        assertEquals(Subjects.ALL.size, Subjects.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun everySubjectHasANameAndARealHint() {
        for (s in Subjects.ALL) {
            assertTrue(s.name.isNotBlank(), "${s.id} has no name")
            assertTrue(s.hint.length > 40, "${s.id} hint is too short to explain anything")
            assertTrue(s.hint.trim().endsWith("."), "${s.id} hint must be a sentence")
        }
    }

    @Test
    fun lookupByIdWorksAndIsTolerantOfWhitespace() {
        for (id in expectedIds) assertEquals(id, assertNotNull(Subjects.byId(id)).id)
        assertEquals("textile", assertNotNull(Subjects.byId(" textile ")).id)
        assertNull(Subjects.byId("marzipan"))
        assertEquals("photo", Subjects.default().id)
    }

    @Test
    fun everySubjectActuallyChangesSomething() {
        // A subject that is a no-op is a menu entry that teaches the user the menu does nothing.
        val base = Styles.default().params
        for (s in Subjects.ALL) {
            assertNotEquals(base, s.adjust(base), "${s.id} left the parameters untouched")
        }
    }

    @Test
    fun aSubjectAdjustsAndNeverReplacesTheStylesIdentity() {
        for (style in Styles.ALL) {
            for (subject in Subjects.ALL) {
                val composed = subject.adjust(style.params)
                assertEquals(
                    style.params.styleId, composed.styleId,
                    "${subject.id} lost the style id of ${style.id}",
                )
                // The vector mode and the engine are what a style *is*; nothing about the material can
                // turn "colouring book" into a centreline trace.
                assertEquals(
                    style.params.output.vectorMode, composed.output.vectorMode,
                    "${subject.id} changed the vector mode of ${style.id}",
                )
                assertEquals(
                    style.params.edge.engine, composed.edge.engine,
                    "${subject.id} changed the edge engine of ${style.id}",
                )
                assertEquals(
                    style.params.output.fillClosed, composed.output.fillClosed,
                    "${subject.id} changed the fill behaviour of ${style.id}",
                )
            }
        }
    }

    @Test
    fun adjustingIsAlwaysLegalAndStaysLegalWhenAppliedTwice() {
        for (subject in Subjects.ALL) {
            val once = subject.adjust(Styles.default().params)
            assertEquals(once, once.sanitized(), "${subject.id} produced an unsanitised result")
            val twice = subject.adjust(once)
            assertEquals(twice, twice.sanitized(), "${subject.id} applied twice went illegal")
        }
    }

    @Test
    fun adjustingLeavesTheReceiverAlone() {
        val base = Styles.default().params
        val snapshot = base.copy()
        for (subject in Subjects.ALL) subject.adjust(base)
        assertEquals(snapshot, base, "a subject mutated the parameters it was handed")
    }

    // -------------------------------------------------------------------------------------------
    // The individual adjustments have to do what their hints claim
    // -------------------------------------------------------------------------------------------

    @Test
    fun textileFlattensTheWeaveWithDiffusionAndRaisesTheBlobFloor() {
        val base = Styles.default().params
        val s = assertNotNull(Subjects.byId("textile")).adjust(base)
        assertEquals(DenoiseMode.ANISOTROPIC, s.preprocess.denoise)
        assertTrue(s.cleanup.minBlobArea > base.cleanup.minBlobArea * 2)
        assertTrue(s.edge.flow.sigmaM > base.edge.flow.sigmaM)
    }

    @Test
    fun jewelleryIsTheOneSubjectThatLowersTheBlobFloor() {
        val base = Styles.default().params
        val s = assertNotNull(Subjects.byId("jewellery")).adjust(base)
        assertTrue(
            s.cleanup.minBlobArea < base.cleanup.minBlobArea,
            "on filigree the small components are the subject: ${s.cleanup.minBlobArea}",
        )
        assertTrue(s.preprocess.workingLongEdge > base.preprocess.workingLongEdge)
        assertTrue(s.output.simplify < base.output.simplify)
    }

    @Test
    fun logoSwitchesOffEverythingThatSoftensAnEdge() {
        val s = assertNotNull(Subjects.byId("logo")).adjust(Styles.default().params)
        assertEquals(DenoiseMode.NONE, s.preprocess.denoise)
        assertTrue(!s.preprocess.claheEnabled, "CLAHE invents gradients inside flat colour")
        assertEquals(0, s.output.smoothIterations)
        assertEquals(0f, s.preprocess.unsharpAmount)
    }

    @Test
    fun architectureTurnsOnPerspectiveCorrectionAndProtectsMoreCorners() {
        val base = Styles.default().params
        val s = assertNotNull(Subjects.byId("architecture")).adjust(base)
        assertTrue(s.preprocess.perspectiveCorrect)
        assertTrue(s.output.corner > base.output.corner)
        assertTrue(s.output.minPathLength > base.output.minPathLength)
    }

    @Test
    fun stoneCarvingRaisesTheBlobFloorHardestOfAll() {
        val base = Styles.default().params
        val floors = Subjects.ALL.associate { it.id to it.adjust(base).cleanup.minBlobArea }
        val stone = assertNotNull(floors["stone-carving"])
        for ((id, floor) in floors) {
            if (id == "stone-carving") continue
            assertTrue(stone >= floor, "$id raised the blob floor to $floor, above stone's $stone")
        }
    }

    @Test
    fun aSubjectAppliedToAStyleThatAlreadyDidTheSameThingDoesNotUndoIt() {
        // Composition has to be monotone in the direction the hint promises, whatever the style set.
        val technical = assertNotNull(Styles.byId("technical-drawing")).params
        val logo = assertNotNull(Subjects.byId("logo")).adjust(technical)
        assertEquals(DenoiseMode.NONE, logo.preprocess.denoise)
        assertTrue(logo.output.corner >= technical.output.corner)
    }

    // -------------------------------------------------------------------------------------------
    // suggestFor
    // -------------------------------------------------------------------------------------------

    @Test
    fun lineArtSuggestsSketchBecauseGettingThatWrongIsTheExpensiveMistake() {
        assertEquals("sketch", Subjects.suggestFor(profile(isLineArt = true, bimodality = 0.9f)).id)
    }

    @Test
    fun aFlatGraphicSuggestsLogo() {
        assertEquals(
            "logo",
            Subjects.suggestFor(profile(isFlatGraphic = true, orientationEntropy = 0.4f)).id,
        )
    }

    @Test
    fun texturedAndColourfulSuggestsTextileWhileTexturedAndGreySuggestsStone() {
        assertEquals(
            "textile",
            Subjects.suggestFor(profile(isHighTexture = true, saturationSpread = 0.4f)).id,
        )
        assertEquals(
            "stone-carving",
            Subjects.suggestFor(profile(isHighTexture = true, saturationSpread = 0.02f)).id,
        )
    }

    @Test
    fun anOrdinaryPhotographSuggestsPhoto() {
        assertEquals("photo", Subjects.suggestFor(profile()).id)
    }

    @Test
    fun lineArtWinsOverEveryOtherFlagBecauseTheDecisionListIsOrdered() {
        val everything = profile(
            isLineArt = true,
            isHighTexture = true,
            isFlatGraphic = true,
            saturationSpread = 0f,
        )
        assertEquals("sketch", Subjects.suggestFor(everything).id)
    }

    @Test
    fun aSmoothObjectOnACleanBackgroundSuggestsPottery() {
        assertEquals(
            "pottery",
            Subjects.suggestFor(measured(Classify.SourceKind.SMOOTH_OBJECT, 0.8f)).id,
        )
    }

    @Test
    fun everySuggestionIsAPresetThatActuallyExists() {
        val flags = listOf(
            profile(isLineArt = true),
            profile(isFlatGraphic = true),
            profile(isHighTexture = true, saturationSpread = 0.5f),
            profile(isHighTexture = true, saturationSpread = 0f),
            profile(),
        )
        for (p in flags) {
            val suggested = Subjects.suggestFor(p)
            assertNotNull(Subjects.byId(suggested.id), "suggestFor returned an unlisted subject")
        }
        // And every measured kind, which is the path `Auto` actually takes.
        for (k in Classify.SourceKind.values()) {
            assertNotNull(
                Subjects.byId(Subjects.suggestFor(measured(k, 0.9f)).id),
                "suggestFor($k) returned an unlisted subject",
            )
        }
    }

    // -------------------------------------------------------------------------------------------
    // Auto: applying the classification, and every rule that bounds it
    // -------------------------------------------------------------------------------------------

    /** A profile that was genuinely measured: a real kind and a real confidence. */
    private fun measured(
        kind: Classify.SourceKind,
        confidence: Float,
        separable: Boolean = false,
        saturationSpread: Float = 0.3f,
    ) = Classify.SourceProfile(
        bimodality = 0.5f,
        edgeDensity = 0.1f,
        orientationEntropy = 0.8f,
        saturationSpread = saturationSpread,
        isLineArt = false,
        isHighTexture = false,
        isFlatGraphic = false,
        suggestion = "synthetic",
        separableSubject = separable,
        kind = kind,
        confidence = confidence,
    )

    private fun autoParams(
        mode: AutoMode = AutoMode.APPLY,
        subjectId: String = "",
        handTuned: Set<String> = emptySet(),
        allowMatte: Boolean = true,
    ) = AutoParams(mode = mode, subjectId = subjectId, handTuned = handTuned, allowMatte = allowMatte)

    private fun base(auto: AutoParams): TraceParams =
        Styles.default().params.copy(auto = auto).sanitized()

    @Test
    fun autoOffChangesNothingAndSaysNothing() {
        val p = base(autoParams(mode = AutoMode.OFF))
        val d = Auto.decide(measured(Classify.SourceKind.TEXTURED, 0.99f), p)
        assertSame(p, d.params, "OFF must not even allocate a new tree")
        assertFalse(d.applied)
        assertTrue(d.notes.isEmpty(), "a switched-off feature has nothing to report")
    }

    @Test
    fun autoWithoutAProfileSaysSoRatherThanSilentlyDoingNothing() {
        val p = base(autoParams())
        val d = Auto.decide(null, p)
        assertSame(p, d.params)
        assertFalse(d.applied)
        assertEquals(1, d.notes.size)
        assertTrue(d.notes[0].contains("did not classify"), d.notes[0])
    }

    @Test
    fun autoRefusesBelowItsConfidenceFloorAndNamesTheGuessItRejected() {
        val p = base(autoParams())
        val d = Auto.decide(measured(Classify.SourceKind.TEXTURED, 0.4f), p)
        assertSame(p, d.params, "a low-confidence guess must change nothing at all")
        assertFalse(d.applied)
        val note = d.notes.single()
        assertTrue(note.contains("not sure enough"), note)
        assertTrue(note.contains("textured surface"), "the rejected guess must be named: $note")
        assertTrue(note.contains("40%") && note.contains("55%"), "both numbers must be shown: $note")
    }

    @Test
    fun anUnclassifiedProfileIsRefusedEvenAtFullConfidence() {
        // UNKNOWN is the absence of a classification, not a sixth class, so a confidence attached to
        // it is meaningless and must not be enough to act on.
        val d = Auto.decide(measured(Classify.SourceKind.UNKNOWN, 1f), base(autoParams()))
        assertFalse(d.applied)
        val note = d.notes.single()
        assertTrue(note.contains("found nothing in this frame"), note)
        assertFalse(
            note.contains("best guess"),
            "an empty frame is not a failed guess, and saying so sends the user hunting: $note",
        )
    }

    @Test
    fun autoRespectsASubjectTheUserAlreadyChose() {
        val p = base(autoParams(subjectId = "jewellery"))
        val d = Auto.decide(measured(Classify.SourceKind.TEXTURED, 0.99f), p)
        assertSame(p, d.params, "the user's own choice is not a starting point to adjust")
        assertFalse(d.applied)
        val note = d.notes.single()
        assertTrue(note.contains("Jewellery"), note)
        assertTrue(note.contains("your choice was kept"), note)
    }

    @Test
    fun autoAppliesTheSubjectAndNamesEverythingItChanged() {
        val p = base(autoParams())
        val d = Auto.decide(measured(Classify.SourceKind.TEXTURED, 0.9f, saturationSpread = 0.4f), p)

        assertTrue(d.applied)
        assertEquals("textile", assertNotNull(d.subject).id)
        assertNotEquals(p, d.params, "APPLY that changes nothing is not APPLY")
        assertEquals(DenoiseMode.ANISOTROPIC, d.params.preprocess.denoise)

        val note = d.notes[0]
        assertTrue(note.contains("textured surface"), note)
        assertTrue(note.contains("90% sure"), note)
        assertTrue(note.contains("Textile"), note)
        // Every setting it moved has to appear by the name the editor shows, or the change is
        // invisible and indistinguishable from a bug.
        assertTrue(note.contains("anisotropic diffusion"), "the filter change must be named: $note")
        assertTrue(note.contains("minimum blob area"), "the blob floor moved and must be named: $note")
        assertTrue(note.trim().endsWith("."), "notes are sentences: $note")
    }

    @Test
    fun autoNeverOverwritesAHandTunedKnob() {
        val hand = setOf(Knobs.MIN_BLOB_AREA, Knobs.EDGE_SENSITIVITY)
        val tuned = Styles.default().params
            .copy(auto = autoParams(handTuned = hand))
            .copy(
                cleanup = Styles.default().params.cleanup.copy(minBlobArea = 4321),
                edge = Styles.default().params.edge.copy(sensitivity = 0.77f),
            )
            .sanitized()
        val d = Auto.decide(measured(Classify.SourceKind.TEXTURED, 0.9f, saturationSpread = 0.4f), tuned)

        assertTrue(d.applied)
        assertEquals(4321, d.params.cleanup.minBlobArea, "auto overwrote a hand-set blob floor")
        assertEquals(0.77f, d.params.edge.sensitivity, "auto overwrote a hand-set sensitivity")
        // And it says which ones it kept, so the user knows why the preset "did nothing" there.
        assertTrue(
            d.notes.any { it.contains("exactly as you set") && it.contains("minimum blob area") },
            "the kept knobs must be named: ${d.notes}",
        )
        // The knobs it was *not* told about are still free to move.
        assertNotEquals(
            tuned.preprocess.denoise, d.params.preprocess.denoise,
            "protecting two knobs must not freeze the whole tree",
        )
    }

    @Test
    fun autoNeverChangesTheIdentityOfAnyOfTheTwentyStyles() {
        // A subject is a modifier on a style. On the automatic path nobody asked for it, so the
        // boundary is enforced here rather than trusted to the subject tables.
        for (style in Styles.ALL) {
            for (kind in Classify.SourceKind.values()) {
                if (kind == Classify.SourceKind.UNKNOWN) continue
                val p = style.params.copy(auto = autoParams()).sanitized()
                val d = Auto.decide(measured(kind, 0.95f, separable = true), p)
                assertEquals(p.edge.engine, d.params.edge.engine, "${style.id}/$kind changed the engine")
                assertEquals(
                    p.output.vectorMode, d.params.output.vectorMode,
                    "${style.id}/$kind changed the vector mode",
                )
                assertEquals(
                    p.output.fillClosed, d.params.output.fillClosed,
                    "${style.id}/$kind changed the fill",
                )
                assertEquals(p.styleId, d.params.styleId, "${style.id}/$kind lost the style id")
                assertEquals(p.auto, d.params.auto, "${style.id}/$kind rewrote its own settings")
                assertEquals(
                    d.params, d.params.sanitized(),
                    "${style.id}/$kind produced an unsanitised tree",
                )
            }
        }
    }

    @Test
    fun suggestModeExplainsItselfAndChangesNothing() {
        val p = base(autoParams(mode = AutoMode.SUGGEST))
        val d = Auto.decide(measured(Classify.SourceKind.TEXTURED, 0.9f, saturationSpread = 0.4f), p)

        assertSame(p, d.params, "SUGGEST must return the caller's own tree")
        assertFalse(d.applied)
        assertEquals("textile", assertNotNull(d.subject).id, "it still says what it found")
        assertTrue(d.notes[0].contains("suggest only"), d.notes[0])
        assertTrue(
            d.notes.any { it.contains("Textile") },
            "a suggestion that does not name the subject is not a suggestion: ${d.notes}",
        )
    }

    @Test
    fun theMatteIsOnlyEnabledForASeparableSubjectAndOnlyWhenAllowed() {
        val kind = Classify.SourceKind.SMOOTH_OBJECT

        val separable = Auto.decide(measured(kind, 0.9f, separable = true), base(autoParams()))
        // SUBJECT and not BORDER_FLOOD: the fused matte contains the flood as one of its three cues
        // and, unlike the bare flood, reports a confidence the matte stage can refuse on. On a path
        // nobody asked for, being able to decline is the whole difference.
        assertEquals(MatteMode.SUBJECT, separable.params.matte.mode)
        assertTrue(separable.notes[0].contains("background separation"), separable.notes[0])

        val busy = Auto.decide(measured(kind, 0.9f, separable = false), base(autoParams()))
        assertEquals(
            MatteMode.NONE, busy.params.matte.mode,
            "without a clean background a matte would eat the artwork",
        )

        val refused = Auto.decide(
            measured(kind, 0.9f, separable = true),
            base(autoParams(allowMatte = false)),
        )
        assertEquals(MatteMode.NONE, refused.params.matte.mode)
    }

    @Test
    fun theMeasurementOutranksTheSubjectPresetsAssumptionAboutTheMaterial() {
        // SMOOTH_OBJECT maps to `pottery`, which asks for a matte because a pot is photographed
        // standing on something. That is a prior about the material; `separableSubject` is a
        // measurement of this photograph, and a pot shot on a cluttered workbench has no background
        // to remove. Inheriting the preset's assumption here would make the measurement decoration.
        val onABench = Auto.decide(
            measured(Classify.SourceKind.SMOOTH_OBJECT, 0.9f, separable = false),
            base(autoParams()),
        )
        assertEquals("pottery", assertNotNull(onABench.subject).id)
        assertEquals(
            MatteMode.SUBJECT,
            assertNotNull(Subjects.byId("pottery")).adjust(Styles.default().params).matte.mode,
            "the preset itself must still ask, or this test proves nothing",
        )
        assertEquals(
            MatteMode.NONE, onABench.params.matte.mode,
            "auto inherited the preset's matte instead of trusting what it measured",
        )
    }

    @Test
    fun onlyTheSubjectsWithABackgroundBehindThemAskForAMatte() {
        // The division is not "how photographic is this material" but "is there a background in the
        // frame at all". A logo's paper *is* the picture's ground and a textile fills the frame, so a
        // matte there can only cut into the artwork.
        val wants = setOf("pottery", "jewellery", "sculpture", "metalwork")
        val defaults = Styles.default().params
        assertEquals(
            MatteMode.NONE, defaults.matte.mode,
            "the default must stay off, or this proves nothing",
        )
        for (s in Subjects.ALL) {
            val mode = s.adjust(defaults).matte.mode
            assertEquals(
                if (s.id in wants) MatteMode.SUBJECT else MatteMode.NONE, mode,
                "${s.id} chose $mode",
            )
        }
    }

    @Test
    fun aSubjectNeverReplacesAMatteTheStyleOrTheUserAlreadyChose() {
        // `silhouette` names saliency deliberately. A preset's matte is a prior about the material
        // and a prior does not overrule a decision — which also keeps `adjust` idempotent, and the
        // UI re-adjusts on every edit.
        val silhouette = assertNotNull(Styles.byId("silhouette")).params
        assertEquals(MatteMode.SALIENCY, silhouette.matte.mode)
        for (s in Subjects.ALL) {
            assertEquals(
                MatteMode.SALIENCY, s.adjust(silhouette).matte.mode,
                "${s.id} overwrote the style's own matte",
            )
        }
    }

    @Test
    fun aMatteTheStyleAlreadyChoseIsNeverReplaced() {
        // `silhouette` is the one preset that opts into a matte, and it picks saliency deliberately.
        val style = assertNotNull(Styles.byId("silhouette")).params
        val p = style.copy(auto = autoParams()).sanitized()
        val d = Auto.decide(measured(Classify.SourceKind.SMOOTH_OBJECT, 0.95f, separable = true), p)
        assertEquals(MatteMode.SALIENCY, d.params.matte.mode)
    }

    @Test
    fun applyingAutoTwiceDoesNotWalkTheParametersOutOfRange() {
        val profile = measured(Classify.SourceKind.TEXTURED, 0.9f, saturationSpread = 0.4f)
        var p = base(autoParams())
        repeat(3) {
            val d = Auto.decide(profile, p)
            assertEquals(d.params, d.params.sanitized())
            p = d.params
        }
    }
}
