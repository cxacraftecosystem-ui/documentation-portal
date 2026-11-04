package com.fieldrepository.app.ui

import com.fieldrepository.app.data.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkthroughDecksTest {

    private val everyRole = listOf(
        "CROWDSOURCE_VOLUNTEER",
        "FIELD_CONTRIBUTOR",
        "RESEARCHER",
        "PROFESSOR",
        "ADMIN",
        "MASTER_ADMIN",
    )

    private fun account(role: String) =
        UserDto(id = "u-$role", email = "$role@example.org", name = role, role = role)

    @Test
    fun `every deck this build carries is registered, and each is the object it claims to be`() {
        assertEquals(
            "a deck is declared and not registered, so nothing can switch to it",
            listOf("researcher", "contributor", "admin"),
            WALKTHROUGH_DECKS.map { it.id }
        )
        assertEquals(
            "two decks share an id",
            WALKTHROUGH_DECKS.size,
            WALKTHROUGH_DECKS.map { it.id }.toSet().size
        )
        assertSame(walkthroughJourney, WALKTHROUGH_RESEARCHER_DECK.journey)
        assertSame(walkthroughSteps, WALKTHROUGH_RESEARCHER_DECK.steps)
        assertSame(walkthroughContributorJourney, WALKTHROUGH_CONTRIBUTOR_DECK.journey)
        assertSame(walkthroughAdminJourney, WALKTHROUGH_ADMIN_DECK.journey)
    }

    @Test
    fun `no deck is empty, because the journey draws nothing under three cards`() {
        WALKTHROUGH_DECKS.forEach { deck ->
            assertTrue("“${deck.id}” has no numbered steps", deck.journey.isNotEmpty())
            assertTrue(
                "“${deck.id}” has ${deck.steps.size} cards and the journey draws nothing under three",
                deck.steps.size >= 3
            )
            assertTrue("“${deck.id}” has no name for the switcher", deck.name.isNotBlank())
            assertTrue("“${deck.id}” does not say who it is for", deck.audience.isNotBlank())
        }
    }

    @Test
    fun `the role picks the deck that opens, tier by tier`() {
        val expected = mapOf(
            "CROWDSOURCE_VOLUNTEER" to "contributor",
            "FIELD_CONTRIBUTOR" to "contributor",
            "RESEARCHER" to "researcher",
            "PROFESSOR" to "researcher",
            "ADMIN" to "admin",
            "MASTER_ADMIN" to "admin",
        )
        assertEquals("the sweep must cover every role", everyRole.toSet(), expected.keys)
        everyRole.forEach { role ->
            assertEquals(
                "a $role opens on the wrong walkthrough",
                expected.getValue(role),
                walkthroughDeckFor(account(role)).id
            )
        }
    }

    @Test
    fun `an unknown role opens on the deck with the fewest doors, and a null account on the researcher's`() {
        assertEquals("contributor", walkthroughDeckFor(account("SOMETHING_NEW")).id)
        assertEquals("researcher", walkthroughDeckFor(null).id)
    }

    @Test
    fun `choosing a deck by role takes nothing away from anybody`() {
        val menu = FIELD_NAV_ITEMS.first { it.destination == NavDestination.WALKTHROUGH }
        everyRole.forEach { role ->
            val user = account(role)
            assertTrue("the walkthrough is hidden from $role", menu.can(user))
            assertTrue(
                "a $role opens on “${walkthroughDeckFor(user).id}”, which is not a registered deck",
                WALKTHROUGH_DECKS.any { it.id == walkthroughDeckFor(user).id }
            )
        }
    }

    @Test
    fun `every door in a deck is a row the role it is written for can open`() {
        val readers = mapOf(
            WALKTHROUGH_CONTRIBUTOR_DECK to "FIELD_CONTRIBUTOR",
            WALKTHROUGH_RESEARCHER_DECK to "PROFESSOR",
            WALKTHROUGH_ADMIN_DECK to "ADMIN",
        )
        readers.forEach { (deck, role) ->
            val user = account(role)
            deck.journey.forEach { step ->
                val destination = step.destination ?: return@forEach
                val row = FIELD_NAV_ITEMS.first { it.destination == destination }
                assertTrue(
                    "“${step.id}” in “${deck.id}” opens ${row.label}, which a $role's menu never shows",
                    row.can(user)
                )
            }
        }
    }

    @Test
    fun `the contributor's deck teaches nothing that needs Researcher`() {
        val contributor = account("FIELD_CONTRIBUTOR")
        walkthroughContributorJourney.forEach { step ->
            val destination = step.destination ?: return@forEach
            assertTrue(
                "“${step.id}” opens a screen that needs record creation",
                FIELD_NAV_ITEMS.first { it.destination == destination }.can(contributor)
            )
        }
    }

    @Test
    fun `every deck carries the offline and account subjects a reader of that role needs`() {
        listOf(WALKTHROUGH_RESEARCHER_DECK, WALKTHROUGH_CONTRIBUTOR_DECK).forEach { deck ->
            assertTrue(
                "“${deck.id}” has no step about working with no signal",
                deck.journey.any { it.destination == null && it.icon != null }
            )
            assertTrue(
                "“${deck.id}” has no step about the account's own settings",
                deck.journey.any { it.destination == NavDestination.SETTINGS }
            )
        }
    }
}
