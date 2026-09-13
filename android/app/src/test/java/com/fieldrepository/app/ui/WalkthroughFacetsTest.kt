package com.fieldrepository.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a walkthrough card would actually DRAW, asserted without standing up a composition.
 *
 * ── WHY THIS SUITE EXISTS SEPARATELY FROM `WalkthroughStepsTest` ─────────────────────────────────
 *
 * That one asks whether the content agrees with the web. This one asks whether the content survives
 * the trip from a `WalkStep` to a card — which is a different question, and the designer portal
 * proved it is a question that needs asking. Its version of this feature shipped with two of its
 * loudest cautions PRESENT IN THE SOURCE AND NOT DRAWN: the card cut them out of the body with a
 * regex that wanted a colon, and two steps had written "WATCH OUT, BECAUSE…" with a comma. Its step
 * test was green the whole time, because it only ever asked whether the body mentioned the words.
 * The section the test was standing guard over was simply not being rendered.
 *
 * This port removes that failure by construction — the caution is [WalkStep.watch], a list, so there
 * is no seam and no punctuation for a parser to disagree with — but "by construction" is a claim
 * about today's code, and the assertions below are what keep it true after somebody decides the
 * bodies would read better with the caution inlined again.
 *
 * ── THE ASSERTIONS ARE ABOUT STRUCTURE AND NEVER ABOUT SENTENCES ─────────────────────────────────
 *
 * Nothing here pins a word. A test that fails when somebody improves a sentence is a test everybody
 * learns to ignore, and the words are already held to the web by `WalkthroughStepsTest`. What is
 * pinned here is the SHAPE: that every step has a collapsed line and something to open, that no
 * heading can be drawn over an empty block, and that no chip can be drawn with nothing in it.
 */
class WalkthroughFacetsTest {

    @Test
    fun `every numbered step arrives at the card with a caution to draw`() {
        val silent = walkthroughJourney.filter { walkthroughFacets(it).watch.isEmpty() }
        assertTrue(
            "These steps would draw no 'Watch out for' section at all: ${silent.map { it.id }}. " +
                "The caution is the half a reader who skims still takes in, and the half that costs " +
                "a return trip when it is missed.",
            silent.isEmpty(),
        )
    }

    @Test
    fun `no caution reaches a bullet blank`() {
        /*
         * An empty list means "draw no heading" and is correct; a list holding one blank string means
         * "draw the heading, with a bullet and nothing after it", which is the latent defect the
         * `emptyList()` contract in [walkthroughFacets] exists to make impossible.
         */
        for (step in walkthroughSteps) {
            for (note in walkthroughFacets(step).watch) {
                assertTrue("Step '${step.id}' has a blank caution bullet.", note.isNotBlank())
            }
        }
    }

    @Test
    fun `every numbered step has both a summary line and a panel worth opening`() {
        for (step in walkthroughJourney) {
            val facets = walkthroughFacets(step)
            assertTrue("Step '${step.id}' has no collapsed summary line.", facets.summary.isNotBlank())
            assertTrue(
                "Step '${step.id}' has nothing in its 'Why this step exists' panel, so pressing the " +
                    "card opens a chevron onto the fields alone.",
                facets.detail.isNotBlank(),
            )
            assertFalse(
                "Step '${step.id}' put its whole body on the collapsed line, so the card shows " +
                    "everything before it is opened and opening it adds nothing.",
                facets.summary == step.body.trim(),
            )
        }
    }

    @Test
    fun `the first sentence is lifted out of the prose rather than copied into both halves`() {
        /*
         * The cut is an index into one string, so summary and detail are disjoint substrings of the
         * body and rejoining them must give the body back exactly. If that ever stops holding, the
         * card is either repeating a sentence the reader has already read or has silently dropped the
         * characters at the seam.
         */
        for (step in walkthroughSteps) {
            val facets = walkthroughFacets(step)
            val rejoined = if (facets.detail.isEmpty()) {
                facets.summary
            } else {
                facets.summary + " " + facets.detail
            }
            assertEquals(
                "Cutting step '${step.id}' and rejoining it does not give its body back.",
                step.body.trim(),
                rejoined,
            )
        }
    }

    @Test
    fun `the summary is one readable line and not a paragraph`() {
        for (step in walkthroughSteps) {
            val summary = walkthroughFacets(step).summary
            /*
             * A ceiling and not an exact shape. The collapsed card head is a line of 14sp text at a
             * reader's own font scale; past about this length it stops being a summary and starts
             * being the panel, at which point the card is showing everything before it is opened.
             * The longest today is comfortably inside this.
             */
            assertTrue(
                "Step '${step.id}' has a ${summary.length}-character collapsed line — it is a " +
                    "paragraph, not a summary.",
                summary.length <= 320,
            )
        }
    }

    @Test
    fun `every step that opens a screen says what that screen asks for`() {
        /*
         * STATED AGAINST THE DOOR AND NOT AGAINST A LIST OF IDS, deliberately. A step that offers to
         * open a form and then says nothing about what the form wants has sent a researcher to a
         * screen cold, which is the one thing the "What the screen asks for" block exists to prevent.
         * Written this way the rule survives the web adding a step: the new step fails here until
         * somebody gives it fields, rather than passing because nobody remembered to add its id.
         */
        val silent = walkthroughJourney.filter {
            it.destination != null && walkthroughFacets(it).fields.isEmpty()
        }
        assertTrue(
            "These steps open a screen without saying what it asks for: ${silent.map { it.id }}",
            silent.isEmpty(),
        )
    }

    @Test
    fun `no card would draw an empty chip`() {
        for (step in walkthroughSteps) {
            for (label in walkthroughFacets(step).fields) {
                assertTrue("Step '${step.id}' has a blank field chip.", label.isNotBlank())
            }
        }
    }

    @Test
    fun `the cards that teach no screen list no fields at all`() {
        val ends = listOf(walkthroughSteps.first(), walkthroughSteps.last())
        val doorless = walkthroughJourney.filter { it.destination == null }
        for (step in ends + doorless) {
            assertEquals(
                "Card '${step.id}' teaches no screen, so it must have no 'What the screen asks for' " +
                    "block — an empty list, never a list holding one blank string.",
                emptyList<String>(),
                walkthroughFacets(step).fields,
            )
        }
    }

    @Test
    fun `the two ends of the deck come back with no caution and something to read`() {
        for (step in listOf(walkthroughSteps.first(), walkthroughSteps.last())) {
            assertEquals(
                "Card '${step.id}' is not a step and must raise no caution.",
                emptyList<String>(),
                walkthroughFacets(step).watch,
            )
            assertTrue(
                "Card '${step.id}' would render with no words on it.",
                walkthroughFacets(step).summary.isNotBlank(),
            )
        }
    }

    @Test
    fun `every numbered step splits into a feature name and the control you tap`() {
        for (step in walkthroughJourney) {
            val (feature, action) = walkthroughTitleParts(step.title)
            assertTrue("Step '${step.id}' has a blank feature name.", feature.isNotBlank())
            assertNotNull(
                "Step '${step.id}' has no action half, so its card draws a heading with no pill. " +
                    "The separator is a middle dot with a space each side and nothing else.",
                action,
            )
        }
    }

    @Test
    fun `the two ends draw a heading and no action pill`() {
        /*
         * The opening and closing cards are not features and name no control, so they must fall down
         * the no-separator branch of [walkthroughTitleParts] rather than producing an empty pill
         * beside their heading.
         */
        for (step in listOf(walkthroughSteps.first(), walkthroughSteps.last())) {
            val (feature, action) = walkthroughTitleParts(step.title)
            assertEquals(step.title, feature)
            assertNull("Card '${step.id}' would draw an action pill.", action)
        }
    }
}
