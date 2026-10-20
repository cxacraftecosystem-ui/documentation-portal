package com.fieldrepository.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **THE SEAM THE WALKTHROUGH CARD CUTS A STEP AT** — the one piece of real logic in
 * `WalkthroughJourney.kt`.
 *
 * Ported from the designer portal's suite of the same name. [walkthroughFacets] is shared code, byte
 * for byte, so the parser's own guarantees are pinned exactly as they are there: a caution is found
 * by its WORDS and not its punctuation, its introduction is not left at the top of the bullet, and it
 * is not duplicated into the prose. This app's bodies are one short line, and their "why" and cautions
 * are fields of their own, so those guarantees are asserted against steps built for the purpose
 * rather than against the deck.
 *
 * What IS asserted against every deck is the shape the card is handed: a summary that is the whole
 * line, a "Why this step exists" and a "Watch out for" on every numbered step and on neither end, no
 * blank chips or bullets, and — for a card that opens the same screen in two decks — the same form
 * labels in both.
 */
class WalkthroughFacetsTest {

    private fun step(body: String) = WalkStep(id = "probe", title = "Probe · Test", body = body)

    // ── The parser, which is shared code ─────────────────────────────────────────────────────────

    @Test
    fun `a caution is found whatever punctuation it raises its voice with`() {
        // THE DESIGNER PORTAL'S SHIPPED DEFECT: the seam was the literal "Watch out:", and two steps
        // wrote "WATCH OUT, BECAUSE…" with a comma. The marker is the words, never the punctuation.
        listOf(
            "Record the thing you came for, in the order it happens. Watch out: the first caution here.",
            "Record the thing you came for, in the order it happens. WATCH OUT, BECAUSE THIS ONE MATTERS: a caution.",
            "Record the thing you came for, in the order it happens. Watch out — a caution with a dash.",
        ).forEach { body ->
            val watch = walkthroughFacets(step(body)).watch
            assertEquals("“$body” must yield exactly one caution", 1, watch.size)
            assertFalse(
                "the caution still wears its own introduction: “${watch.single()}”",
                watch.single().startsWith("watch out", ignoreCase = true)
            )
            assertTrue(
                "the caution opens on punctuation: “${watch.single()}”",
                watch.single().first().isLetterOrDigit()
            )
        }
    }

    @Test
    fun `the seam is removed from the prose rather than duplicated into both halves`() {
        val facets = walkthroughFacets(
            step("Record the thing you came for, in the order it happens. It matters later. Watch out: a caution.")
        )
        assertFalse(
            "the caution's introduction survived in the prose half, so it would be drawn twice",
            (facets.summary + " " + facets.detail).contains("watch out", ignoreCase = true)
        )
        assertEquals("Record the thing you came for, in the order it happens.", facets.summary)
        assertEquals("It matters later.", facets.detail)
    }

    @Test
    fun `a word that merely contains the seam is not a seam`() {
        // `\b` on both ends, so "watchtower" or "outbox" cannot be mistaken for a caution.
        val facets = walkthroughFacets(step("Climb the watchtower and check the outbox for anything left."))
        assertEquals(emptyList<String>(), facets.watch)
    }

    // ── The shape every deck's cards are handed ──────────────────────────────────────────────────

    @Test
    fun `a step's own why and cautions win over the body's seams`() {
        val facets = walkthroughFacets(
            WalkStep(
                id = "probe",
                title = "Probe · Test",
                body = "Record the thing you came for, in the order it happens. Watch out: a body caution.",
                why = "The dataset needs it.",
                watch = listOf("First caution.", "  ", "Second caution."),
            )
        )
        assertEquals("Record the thing you came for, in the order it happens.", facets.summary)
        assertEquals("The dataset needs it.", facets.detail)
        assertEquals(listOf("First caution.", "Second caution."), facets.watch)
    }

    @Test
    fun `every numbered step's summary is its whole line`() {
        // One short line per step: the collapsed card shows all of it. A body the cutter split would
        // mean the line had grown a second sentence — `WalkthroughStepsTest` forbids that, and this
        // asserts the card agrees.
        WALKTHROUGH_DECKS.flatMap { it.journey }.forEach { step ->
            assertEquals(
                "“${step.id}”'s summary is not its whole line",
                step.body.trim(),
                walkthroughFacets(step).summary
            )
        }
    }

    @Test
    fun `every numbered step says why it exists and what to watch out for`() {
        // THE OWNER'S REPORT, 2026-10-20: the handset's cards had neither section, where the designer
        // portal's do. An empty `why` or `watch` draws no heading, so a step missing one is invisible
        // on the card — this is the only place it shows.
        WALKTHROUGH_DECKS.flatMap { it.journey }.forEach { step ->
            val facets = walkthroughFacets(step)
            assertTrue("“${step.id}” draws no “Why this step exists”", facets.detail.isNotBlank())
            assertTrue("“${step.id}” draws no “Watch out for”", facets.watch.isNotEmpty())
        }
    }

    @Test
    fun `the why and the cautions stay concise`() {
        // Concise, premium copy: a why is at most two short sentences and a caution is one line.
        WALKTHROUGH_DECKS.flatMap { it.journey }.forEach { step ->
            val facets = walkthroughFacets(step)
            assertTrue(
                "“${step.id}”'s why runs ${facets.detail.length} characters: “${facets.detail}”",
                facets.detail.length <= 120
            )
            assertTrue("“${step.id}” has ${facets.watch.size} cautions", facets.watch.size <= 5)
            facets.watch.forEach { note ->
                assertTrue("“${step.id}” has a ${note.length}-character caution: “$note”", note.length <= 90)
                assertTrue("“${step.id}”'s caution must end as a sentence: “$note”", note.last() in ".?")
                assertFalse(
                    "“${step.id}”'s caution wears its own heading: “$note”",
                    note.startsWith("watch out", ignoreCase = true)
                )
            }
        }
    }

    @Test
    fun `the two ends of a deck draw no caution`() {
        // The opening and closing cards are not features; a "Watch out for" over them would be noise.
        WALKTHROUGH_DECKS.forEach { deck ->
            listOf(deck.steps.first(), deck.steps.last()).forEach { step ->
                assertEquals(
                    "“${step.id}” would draw a caution: ${walkthroughFacets(step).watch}",
                    emptyList<String>(),
                    walkthroughFacets(step).watch
                )
            }
        }
    }

    @Test
    fun `a card in another deck that reuses a researcher screen's words reuses them exactly`() {
        // The interview, media, workshop, craft, data, offline and account cards of the other decks
        // describe the researcher's screen in the researcher's words, by reference.
        val pairs = mapOf(
            "contributor-interview" to "questionnaire",
            "contributor-media" to "media",
            "contributor-offline" to "offline",
            "contributor-account" to "account",
            "admin-workshop" to "workshop",
            "admin-craft" to "craft",
            "admin-view-data" to "view-data",
        )
        val all = WALKTHROUGH_DECKS.flatMap { it.journey }.associateBy { it.id }
        pairs.forEach { (id, researcherId) ->
            val step = all.getValue(id)
            val source = all.getValue(researcherId)
            assertEquals("“$id”'s why drifted from “$researcherId”'s", source.why, step.why)
            assertEquals("“$id”'s cautions drifted from “$researcherId”'s", source.watch, step.watch)
        }
    }

    @Test
    fun `no card would draw an empty bullet`() {
        WALKTHROUGH_DECKS.flatMap { it.steps }.forEach { step ->
            walkthroughFacets(step).watch.forEach { note ->
                assertTrue("“${step.id}” carries a blank caution", note.isNotBlank())
            }
        }
    }

    @Test
    fun `no card would draw an empty chip`() {
        // The card decides whether to draw the heading by asking whether the list is EMPTY, so a blank
        // string survives every other assertion and renders as an empty bordered box.
        WALKTHROUGH_DECKS.flatMap { it.steps }.forEach { step ->
            walkthroughFacets(step).fields.forEach { field ->
                assertTrue("“${step.id}” carries a blank entry in its field list", field.isNotBlank())
            }
        }
    }

    @Test
    fun `the steps that teach no screen list no fields at all`() {
        // The two ends of every deck are not features, and the offline steps teach a behaviour rather
        // than a form. An empty list, not a list holding one blank string.
        val screenless = WALKTHROUGH_DECKS.flatMap { deck ->
            listOf(deck.steps.first(), deck.steps.last()) + deck.journey.filter { it.destination == null }
        }
        screenless.forEach { step ->
            assertEquals(
                "“${step.id}” teaches no screen and must list no fields",
                emptyList<String>(),
                walkthroughFacets(step).fields
            )
        }
    }

    @Test
    fun `one screen is described one way, whichever deck the card is in`() {
        // A contributor's or an admin's card that opens a screen the researcher's deck also teaches is
        // handed THAT screen's list — the same object, not a second register typed again.
        val researcherFields = walkthroughJourney
            .filter { it.destination != null }
            .associate { it.destination to walkthroughFacets(it).fields }
        listOf(WALKTHROUGH_CONTRIBUTOR_DECK, WALKTHROUGH_ADMIN_DECK).flatMap { it.journey }.forEach { step ->
            val shared = researcherFields[step.destination] ?: return@forEach
            assertSame(
                "“${step.id}” opens the same screen as a researcher card and lists different fields",
                shared,
                walkthroughFacets(step).fields
            )
        }
    }
}
