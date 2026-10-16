package com.fieldrepository.app.ui

import com.fieldrepository.app.data.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **THREE WALKTHROUGHS OVER ONE RENDERER, AND THE ROLE PICKS WHICH ONE OPENS.**
 *
 * Ported from the designer portal's suite of the same name. There the second deck is the inspector's;
 * here the role-specific features are the ones the menu gates — record creation from Researcher, the
 * shared lists and user roles from Professor, the access roster and the settings hub from Admin — so
 * the decks are the researcher's journey, the contributor's (below Researcher) and the admin's.
 *
 * Three things can go wrong quietly, and each is a test below:
 *
 *  1. **A deck declared and not registered**, so the role could open it and no switcher could reach
 *     it — or the reverse.
 *  2. **A default turning into a gate.** The role picks which deck OPENS and takes nothing away: the
 *     nav row is ungated for every tier, every deck ships to everybody, and the opening card of
 *     whichever deck opens offers the others.
 *  3. **A deck teaching doors its own reader cannot open.** Each deck exists because its reader's menu
 *     differs, so each deck's doors are held to the menu of the role it is written for.
 */
class WalkthroughDecksTest {

    /** Every role `FieldPermissions` knows, typed out so a dropped role cannot pass silently. */
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

    // ── The register of decks ────────────────────────────────────────────────────────────────────

    @Test
    fun `every deck this build carries is registered, and each is the object it claims to be`() {
        // Written as a list of ids rather than a length, so a deck left out fails BY NAME.
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
        // The researcher's deck IS the list `WalkthroughStepsTest` and the questionnaire contract read
        // by name — a copy, a filter or a slice would leave both green while the screen rendered
        // something else.
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

    // ── The selection ────────────────────────────────────────────────────────────────────────────

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
        // A role this build does not know ranks 0 and its menu shows only the ungated rows, which
        // are the contributor deck's. A null account is not reachable from the dialog — the app
        // renders the sign-in screen until there is a user — but the function is pure and must answer.
        assertEquals("contributor", walkthroughDeckFor(account("SOMETHING_NEW")).id)
        assertEquals("researcher", walkthroughDeckFor(null).id)
    }

    @Test
    fun `choosing a deck by role takes nothing away from anybody`() {
        // A DEFAULT, NOT A GATE. The switcher draws `WALKTHROUGH_DECKS`, unfiltered, and the deck that
        // opens is one member of it.
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

    // ── Each deck's own doors ────────────────────────────────────────────────────────────────────

    @Test
    fun `every door in a deck is a row the role it is written for can open`() {
        // The reason a deck exists at all is that its reader's menu differs. So the doors of each deck
        // are held to the menu of the LOWEST role it is the whole journey for: a Field Contributor
        // for the contributor's deck (review opens at Field Contributor, and the card says so), a
        // Professor for the researcher's (the workshop and craft rows open at Professor), and an
        // Admin for the admin's.
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
        // The deck exists to stop handing a volunteer seven doors their menu does not show.
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
        // Not the admin's: it is about running the repository, and an admin opening it is one tap from
        // the researcher's deck, which carries both.
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
