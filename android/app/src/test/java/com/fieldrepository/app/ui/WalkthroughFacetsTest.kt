package com.fieldrepository.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkthroughFacetsTest {

    private fun step(body: String) = WalkStep(id = "probe", title = "Probe · Test", body = body)

    @Test
    fun `a caution is found whatever punctuation it raises its voice with`() {
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
        val facets = walkthroughFacets(step("Climb the watchtower and check the outbox for anything left."))
        assertEquals(emptyList<String>(), facets.watch)
    }

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
        WALKTHROUGH_DECKS.flatMap { it.journey }.forEach { step ->
            val facets = walkthroughFacets(step)
            assertTrue("“${step.id}” draws no “Why this step exists”", facets.detail.isNotBlank())
            assertTrue("“${step.id}” draws no “Watch out for”", facets.watch.isNotEmpty())
        }
    }

    @Test
    fun `the why and the cautions stay concise`() {
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
        WALKTHROUGH_DECKS.flatMap { it.steps }.forEach { step ->
            walkthroughFacets(step).fields.forEach { field ->
                assertTrue("“${step.id}” carries a blank entry in its field list", field.isNotBlank())
            }
        }
    }

    @Test
    fun `the steps that teach no screen list no fields at all`() {
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
