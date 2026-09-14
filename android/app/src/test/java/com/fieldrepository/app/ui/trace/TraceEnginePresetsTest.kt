package com.fieldrepository.app.ui.trace

import com.offlinetracer.pipeline.Styles
import com.offlinetracer.pipeline.Subjects
import com.offlinetracer.pipeline.TraceParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The two preset registers, and the three places this engine and the portal's do not agree.
 *
 * Every claim in `TraceEnginePresets.kt`'s header is asserted here against the vendored register itself
 * rather than against a transcription of it, so a re-vendor that changes a table fails a build and
 * somebody decides — rather than the two clients drifting quietly.
 */
class TraceEnginePresetsTest {

    /* ── Membership ─────────────────────────────────────────────────────────────────────────── */

    /**
     * **ALL TWENTY STYLES SHIP**, including the ten that target a cutting machine or an embroidery
     * hoop. A filtered copy of somebody else's register is a second register that drifts, and the ids
     * are written into anything persisted, so a shortened list on one client cannot open the other
     * client's saved trace.
     */
    @Test
    fun everyStyleTheEngineCarriesIsOffered() {
        val tables = tracePresetTables()
        assertEquals(20, Styles.ALL.size)
        assertEquals(Styles.ALL.map { it.id }, tables.styles.map { it.id })
    }

    /** The order is the register's own, which it calls binding alongside the ids. */
    @Test
    fun theOrderIsTheRegistersOwn() {
        assertEquals(Styles.ALL.map { it.name }, tracePresetTables().styles.map { it.name })
    }

    /**
     * The TypeScript's factory function FORCES a preset's `styleId` to its own id, so a copy-pasted
     * entry cannot ship a style that reports itself as a different one. Here each of the twenty writes
     * it by hand, so the guarantee is a convention rather than a construction — and therefore asserted.
     */
    @Test
    fun noStyleReportsItselfAsADifferentStyle() {
        Styles.ALL.forEach {
            assertEquals("The ${it.id} preset writes a different styleId into its own tree", it.id, it.params.styleId)
        }
    }

    /**
     * **SUBJECTS DISAGREE ON THE SET.** This engine has twelve and the portal has ten; three here have
     * no portal row and one portal row has none here. That is a divergence with a decision in it, so it
     * is pinned rather than absorbed.
     */
    @Test
    fun thisEngineCarriesTwelveSubjectsAndTheThreeExtraOnesAreNamed() {
        assertEquals(12, Subjects.ALL.size)
        val ids = Subjects.ALL.map { it.id }.toSet()
        TRACE_SUBJECTS_ONLY_ON_THIS_ENGINE.forEach {
            assertTrue("$it is listed as engine-only but the engine does not carry it", it in ids)
        }
        TRACE_SUBJECTS_ONLY_ON_THE_PORTAL.forEach {
            assertTrue("$it is listed as portal-only but this engine carries it", it !in ids)
        }
    }

    /** A row cannot be added to one list without the other: the notes cover exactly the extra three. */
    @Test
    fun everyUnmatchedSubjectCarriesItsOwnSentence() {
        assertEquals(
            TRACE_SUBJECTS_ONLY_ON_THIS_ENGINE.toSet(),
            TRACE_SUBJECT_DIVERGENCE_NOTES.keys,
        )
    }

    /**
     * **EVERY SUBJECT ROW SAYS IT COMPOUNDS**, because on this engine every one of them does. See the
     * behavioural test below.
     */
    @Test
    fun everySubjectRowCarriesTheCompoundingSentence() {
        tracePresetTables().subjects.forEach {
            assertTrue(
                "${it.id} does not tell anybody that tapping it again adjusts again",
                it.description.contains(TRACE_SUBJECT_COMPOUNDS_NOTE),
            )
        }
    }

    /* ── Applying a style ───────────────────────────────────────────────────────────────────── */

    /**
     * A STYLE IS A COMPLETE TREE, NOT A DIFF: "a user who switches styles expects the second one to
     * look like itself rather than like a blend of the two". So the base is read and discarded.
     */
    @Test
    fun aStyleDiscardsWhateverWasThere() {
        val tuned = TraceParams(
            output = TraceParams().output.copy(simplify = 7.5f),
            cleanup = TraceParams().cleanup.copy(minBlobArea = 900),
        ).sanitized()
        val woodcut = traceApplyStyle(tuned, "woodcut")
        assertEquals(Styles.byId("woodcut")!!.params.sanitized(), woodcut)
    }

    @Test
    fun anUnknownStyleIsRefusedRatherThanSubstituted() {
        try {
            traceApplyStyle(TraceParams(), "not-a-style")
            fail("Falling back to the default would put clean-line on screen under another name")
        } catch (expected: IllegalArgumentException) {
            assertEquals(traceNoSuchStyleSentence("not-a-style"), expected.message)
        }
    }

    /* ── Applying a subject ─────────────────────────────────────────────────────────────────── */

    /**
     * **THE FOUR FIELDS THAT MAKE A STYLE WHAT IT IS SURVIVE EVERY SUBJECT.** Enforced rather than
     * trusted, because the subject tables are data edited by hand and this is the one path that applies
     * one without anybody having asked for it — so a table with a stray `engine =` in it would change
     * an export with nothing on screen to explain it.
     */
    @Test
    fun everySubjectPreservesTheStylesIdentity() {
        Styles.ALL.forEach { style ->
            val base = style.params.sanitized()
            Subjects.ALL.forEach { subject ->
                val after = traceApplySubject(base, subject.id)
                assertEquals(
                    "${subject.id} moved the edge engine on ${style.id}",
                    base.edge.engine,
                    after.edge.engine,
                )
                assertEquals(
                    "${subject.id} moved the vector mode on ${style.id}",
                    base.output.vectorMode,
                    after.output.vectorMode,
                )
                assertEquals(
                    "${subject.id} moved fillClosed on ${style.id}",
                    base.output.fillClosed,
                    after.output.fillClosed,
                )
                assertEquals(
                    "${subject.id} moved the styleId on ${style.id}",
                    base.styleId,
                    after.styleId,
                )
                assertEquals("${subject.id} moved the auto block on ${style.id}", base.auto, after.auto)
            }
        }
    }

    /**
     * **A SUBJECT COMPOUNDS ON THIS ENGINE AND IS IDEMPOTENT ON THE PORTAL'S.** This is not a defect
     * being asserted as correct — it is a vendored judgement being pinned so it cannot become a
     * surprise, and the surface says so on every subject row rather than pretending otherwise.
     */
    @Test
    fun applyingASubjectTwiceIsNotTheSameAsApplyingItOnce() {
        val base = Styles.byId("clean-line")!!.params.sanitized()
        val once = traceApplySubject(base, "painting")
        val twice = traceApplySubject(once, "painting")
        assertNotEquals(
            "If this ever becomes idempotent, TRACE_SUBJECT_COMPOUNDS_NOTE is a lie on every row",
            once,
            twice,
        )
    }

    /** The hand-tuned restore is a no-op today and is still wired, for the day somebody writes it. */
    @Test
    fun aHandTunedKnobIsPutBackAfterASubject() {
        val base = TraceParams(
            output = TraceParams().output.copy(simplify = 6.0f),
            auto = TraceParams().auto.copy(handTuned = setOf("simplify")),
        ).sanitized()
        val after = traceApplySubject(base, "painting")
        assertEquals(base.output.simplify, after.output.simplify, 1e-6f)
    }

    /**
     * A portal-only id gets the REGISTER DIFFERENCE spelled out, because "there is no such subject"
     * would be true and useless: the id is real, it is the portal's, and a host may have seeded it.
     */
    @Test
    fun aPortalOnlySubjectIsAnsweredWithTheRemedy() {
        val sentence = traceNoSuchSubjectSentence("carving")
        assertTrue(sentence.contains("Wood carving"))
        assertTrue(sentence.contains("Stone carving"))
        assertTrue(sentence.contains("Metalwork"))

        val bare = traceNoSuchSubjectSentence("not-a-subject")
        assertTrue(bare.contains("not-a-subject"))
        assertTrue("A plain unknown id gets the plain refusal", !bare.contains("Wood carving"))
    }

    @Test
    fun anUnknownSubjectIsRefusedRatherThanIgnored() {
        try {
            traceApplySubject(TraceParams(), "not-a-subject")
            fail("An unknown subject must refuse rather than silently change nothing")
        } catch (expected: IllegalArgumentException) {
            assertEquals(traceNoSuchSubjectSentence("not-a-subject"), expected.message)
        }
    }

    /* ── The picker rows ────────────────────────────────────────────────────────────────────── */

    /**
     * THE GROUP IS FOLDED INTO THE LABEL rather than drawn as a sticky header, because the searchable
     * sheet matches on the label — so somebody can type "tech" and get the technical styles — and
     * because the register's order is NOT grouped-contiguous, so headers would recur.
     */
    @Test
    fun aStyleRowCarriesItsGroupInsideItsLabel() {
        val rows = traceStyleOptions(tracePresetTables().styles)
        val grouped = rows.first { it.label.contains(" · ") }
        assertTrue(grouped.hint!!.isNotBlank())

        val groups = tracePresetTables().styles.map { it.group }.filter { it.isNotBlank() }
        assertTrue(
            "If the register ever becomes grouped-contiguous, sticky headers become possible again",
            groups != groups.distinct().let { distinct -> groups.sortedBy { distinct.indexOf(it) } },
        )
    }

    @Test
    fun aSubjectRowIsFlat() {
        tracePresetTables().subjects.forEach {
            assertEquals("Subjects are a flat list on both clients", "", it.group)
        }
    }

    @Test
    fun anUnknownIdShowsItselfRatherThanNothing() {
        assertEquals("mystery", tracePresetName(tracePresetTables().styles, "mystery"))
        assertNull(traceStyleOptions(emptyList()).firstOrNull())
    }
}
