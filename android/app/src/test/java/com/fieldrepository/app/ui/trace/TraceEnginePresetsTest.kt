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

class TraceEnginePresetsTest {

    @Test
    fun everyStyleTheEngineCarriesIsOffered() {
        val tables = tracePresetTables()
        assertEquals(20, Styles.ALL.size)
        assertEquals(Styles.ALL.map { it.id }, tables.styles.map { it.id })
    }

    @Test
    fun theOrderIsTheRegistersOwn() {
        assertEquals(Styles.ALL.map { it.name }, tracePresetTables().styles.map { it.name })
    }

    @Test
    fun noStyleReportsItselfAsADifferentStyle() {
        Styles.ALL.forEach {
            assertEquals("The ${it.id} preset writes a different styleId into its own tree", it.id, it.params.styleId)
        }
    }

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

    @Test
    fun noUnmatchedSubjectCarriesANoteAboutThePortal() {
        assertEquals(emptySet<String>(), TRACE_SUBJECT_DIVERGENCE_NOTES.keys)
    }

    @Test
    fun everySubjectRowCarriesTheCompoundingSentence() {
        tracePresetTables().subjects.forEach {
            assertTrue(
                "${it.id} does not tell anybody that tapping it again adjusts again",
                it.description.contains(TRACE_SUBJECT_COMPOUNDS_NOTE),
            )
        }
    }

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

    @Test
    fun aHandTunedKnobIsPutBackAfterASubject() {
        val base = TraceParams(
            output = TraceParams().output.copy(simplify = 6.0f),
            auto = TraceParams().auto.copy(handTuned = setOf("simplify")),
        ).sanitized()
        val after = traceApplySubject(base, "painting")
        assertEquals(base.output.simplify, after.output.simplify, 1e-6f)
    }

    @Test
    fun aPortalOnlySubjectIsAnsweredWithTheRemedy() {
        val sentence = traceNoSuchSubjectSentence("carving")
        assertTrue(sentence.contains("Wood carving"))
        assertTrue(sentence.contains("Stone carving"))
        assertTrue("no talk of engines or of the portal", !sentence.contains("engine") && !sentence.contains("portal"))

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
