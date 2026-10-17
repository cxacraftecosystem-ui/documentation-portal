package com.fieldrepository.app.ui

import com.fieldrepository.app.data.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **THE WALKTHROUGH'S SURFACE AND ITS WIRING** — one window, one exit, and the flag that exit writes.
 *
 * ── WHY THIS FILE EXISTS ────────────────────────────────────────────────────────────────────────
 *
 * `WalkthroughStepsTest` holds the step list to the web's. Nothing held the six things around it,
 * and they are the half that silently breaks, because each one is a single line living in a file
 * that is seventeen thousand lines long and that four other pieces of work land in on any given day.
 * The list of them is short and every entry is a defect somebody has already shipped, here or
 * elsewhere:
 *
 *  1. **A skip that does not write the flag.** The walkthrough closes, the designer gets on with
 *     their morning, and it opens again over their dashboard tomorrow. It looks completely correct
 *     in review and in a manual pass; it is only wrong on the SECOND launch, which nobody does while
 *     testing the change that broke it.
 *  2. **A second exit.** The moment there are two lambdas that both close the walkthrough, one of
 *     them is the one that later loses its `markWalkthroughSeen`. Defect 1 is not prevented by
 *     remembering to write the flag twice; it is prevented by there being one place that writes it.
 *  3. **The flag read one frame late.** Read in a `LaunchedEffect` rather than in the `remember`
 *     initialiser, the dashboard paints on its own and the walkthrough lands on top of it a frame
 *     later — a visible flash of the real screen, shown to the exact person who has seen neither.
 *  4. **The walkthrough burying a required update.** The update prompt is deliberately
 *     non-dismissable; a full-screen walkthrough drawn over it is an app that cannot be used and
 *     cannot be updated, and the only way out is to clear the app's data.
 *  5. **A door quietly closing.** The island bar's root and the dashboard button are how anybody
 *     reads this a second time. Losing one is invisible — the other still works, so the feature still "works".
 *  6. **The menu chip acquiring a gate.** The entry is ungated on purpose: a crowdsource volunteer
 *     on day one, who has earned no capability at all, needs the walkthrough more than an admin
 *     does. A `can` predicate added here in the ordinary course of tidying the nav table would hide
 *     it from precisely the account it was written for.
 *
 * ── WHY IT READS SOURCE ─────────────────────────────────────────────────────────────────────────
 *
 * Because none of the six can be imported. `showWalkthrough`, `finishWalkthrough`, the router arm,
 * the `navigate` exemption and the ordering guard are all locals inside one composable in
 * `MainActivity.kt`; the window's own properties and the prefs strings are private to
 * `WalkthroughScreen.kt`; Skip, Done and the back handler are inside composables, and there is no
 * Robolectric in this module, so a rule written inside a composable is a rule nothing can exercise.
 * That is the same reasoning, the same helpers and the same trade `DashboardTileParityTest` makes
 * against the dashboard grid, and `DesignWorkshopCardTest` and `DwWorkshopSearchRegistryTest` against
 * their own registers. Source is also the stronger instrument for what is being defended against
 * here, which is somebody EDITING one of these lines: a deleted door or a moved flag write fails
 * below BY NAME, and none of the six has a type, a lint or a compiler with an opinion about it.
 *
 * What CAN be imported is imported — the step list and the nav table are both `internal` and this
 * test source set is the same module, so the two assertions that can be made against real values
 * rather than against text are made that way.
 *
 * COMMENTS ARE STRIPPED FIRST, and that is not housekeeping. These three files are more prose than
 * code, and every sentence this test looks for is also DISCUSSED at length somewhere above the line
 * that implements it — `markWalkthroughSeen` is named in four comments and called once. An assertion
 * that the flag is written must not be satisfiable by a paragraph about writing the flag, and, worse,
 * a count of call sites would come back four times too high and would be green for the wrong reason.
 *
 * [stripComments] is a copy of the one in `DashboardTileParityTest`, which pins both halves of its
 * behaviour in a test of its own; it is copied rather than shared because it is private to that file
 * and lifting it would put a test helper in the production tree. It nests, because Kotlin block
 * comments nest and this file's whole job is to read Kotlin the way the compiler does.
 *
 * ⚠ NEITHER A BLOCK-COMMENT OPENER NOR A CLOSER IS TYPED ANYWHERE IN THIS KDOC, and that is a rule
 * rather than a preference. Kotlin nests them, so an opener written inside a comment — a glob, a
 * mime wildcard, a regex quoted in prose — opens a SECOND comment that this block's own closer then
 * closes instead of ending the block, and everything from here to the next closer in the file stops
 * being code. The failure is a compile error a long way from the sentence that caused it, and it
 * takes the whole unit-test source set with it, because Kotlin compiles it as one unit. That has
 * already happened once in this tree, to `DashboardTileParityTest`, whose own KDoc records it.
 * Name the characters in words, as this paragraph does.
 */
class WalkthroughSurfaceTest {

    // ── The three files, read once, with their prose taken off ──────────────────────────────────

    private val sourcePaths = mapOf(
        "MainActivity.kt" to "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt",
        "WalkthroughScreen.kt" to "android/app/src/main/java/com/fieldrepository/app/ui/WalkthroughScreen.kt",
        "WalkthroughJourney.kt" to "android/app/src/main/java/com/fieldrepository/app/ui/WalkthroughJourney.kt",
    )

    private val mainActivity = flattenedCode(sourcePaths.getValue("MainActivity.kt"))
    private val window = flattenedCode(sourcePaths.getValue("WalkthroughScreen.kt"))
    private val journey = flattenedCode(sourcePaths.getValue("WalkthroughJourney.kt"))

    @Test
    fun `the parser found the three files it is aimed at`() {
        // Without this the whole file is vacuously green the day any of the three is renamed, split
        // or moved: every lookup below would search an empty string, and every failure would be
        // reported as "the wiring is gone" when the wiring is fine and the parser is not. It is the
        // failure mode of every source-reading test, and it is the one that wastes a morning.
        assertTrue("MainActivity.kt parsed to ${mainActivity.length} characters of code", mainActivity.length > 100_000)
        assertTrue("WalkthroughScreen.kt parsed to ${window.length} characters of code", window.length > 500)
        assertTrue("WalkthroughJourney.kt parsed to ${journey.length} characters of code", journey.length > 5_000)
        // AND THE STRIPPER ACTUALLY STRIPPED. If a file came back with its prose still attached,
        // every count below would be inflated and several assertions would pass on a sentence
        // ABOUT the thing rather than on the thing — `markWalkthroughSeen` is named in four
        // comments and called once, so "the flag is written from exactly one place" would fail at
        // five and the report would blame the wiring.
        //
        // Measured as a ratio rather than by looking for a known sentence, deliberately. These
        // three files are more prose than code and their prose is edited constantly; a canary
        // pinned to one phrase would fail the next time somebody improved a paragraph, which is
        // how a suite trains its readers to delete tests instead of reading them. The ratio only
        // moves if the stripper stops stripping.
        //
        // SEVENTY PER CENT IS TAKEN FROM A MEASUREMENT, NOT CHOSEN. On 2026-08-30 the three files
        // came through this pipeline at 46%, 11% and 27% of their raw length; with the comment
        // stripping removed and only the whitespace flattening left, the same three measure 79%,
        // 96% and 83%. Any cut between those two bands would do, and 70% leaves the tightest of
        // them — MainActivity, the least comment-heavy of the three — twenty-four points of room
        // to gain or lose prose without anybody having to revisit this number.
        listOf(
            "MainActivity.kt" to mainActivity,
            "WalkthroughScreen.kt" to window,
            "WalkthroughJourney.kt" to journey,
        ).forEach { (name, stripped) ->
            val raw = repoFile(sourcePaths.getValue(name)).readText(Charsets.UTF_8)
                .replace("\r\n", "\n").length
            assertTrue(
                "$name stripped to ${stripped.length} of $raw characters — the comment stripper " +
                    "is no longer removing comments, so every assertion in this file may now be " +
                    "satisfied by a sentence about the code instead of by the code",
                stripped.length * 10 < raw * 7,
            )
        }
    }

    // ── The step list ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `the step list is not empty and every id is unique`() {
        // Imported rather than read, because it can be. `WalkthroughStepsTest` asserts uniqueness
        // too and this is deliberately a second copy of that assertion rather than a reference to
        // it: the deck is what the surface below renders, and a surface test that assumed a
        // non-empty deck would report "the journey draws nothing" as a layout fault. An empty list
        // is a blank full-screen window with a Skip button on it, which is not a failure any of the
        // other assertions in this file would notice.
        assertTrue("the walkthrough has no cards to draw", walkthroughSteps.isNotEmpty())
        assertTrue("the journey between the two ends is empty", walkthroughJourney.isNotEmpty())
        val ids = walkthroughSteps.map { it.id }
        assertEquals(
            "a duplicated id makes the one-open-at-a-time card, the deep link and every parity " +
                "check answer for the wrong step, silently",
            ids.size,
            ids.toSet().size,
        )
        assertTrue("an id is the join key and cannot be blank", ids.none { it.isBlank() })
    }

    // ── One surface ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `there is exactly one walkthrough surface`() {
        // This codebase deletes second answers to one question on purpose — `Screen.Settings` was
        // removed once traced, and its own comment records what the dead arm cost: four `when`
        // branches nothing ever assigned. The walkthrough was a paged card in an alert-shaped
        // dialog this morning and is a full-bleed journey now, and the way that change goes wrong
        // is not a compile error, it is the old surface surviving beside the new one behind some
        // condition nobody re-reads.
        assertEquals(
            "the walkthrough is drawn from exactly one place, and adding a second is how the two " +
                "start disagreeing about the seen flag",
            1,
            occurrences(mainActivity, "WalkthroughDialog("),
        )
        assertEquals(
            "the window hosts the journey once",
            1,
            occurrences(window, "WalkthroughJourney("),
        )
        // It is a dialog, and it is deliberately NOT a screen. A `Screen.Walkthrough` would put the
        // walkthrough behind `navigate`'s unsaved-changes guard, so re-opening it from the menu
        // halfway through an artisan form would start asking whether to throw the form away — and
        // it would owe four exhaustive `when` tables (`openDestination`, `headerTitle`,
        // `currentDestination`, `goBack`) for a surface with no back stack of its own.
        assertFalse(
            "the walkthrough has become a Screen; see the argument in WalkthroughScreen.kt",
            mainActivity.contains("Screen.Walkthrough"),
        )
        // The paged deck's own furniture, gone rather than left behind unreferenced.
        assertFalse("the alert-shaped card is still in the window file", window.contains("ElevatedCard"))
        assertFalse("the window is an AlertDialog again", window.contains("AlertDialog"))
        assertEquals(
            // Bounded on the left so that `WalkthroughDialog(` — the composable this file declares,
            // named once here and called once from MainActivity — is not counted as a second window.
            "the window is one Dialog",
            1,
            Regex("""(?<![A-Za-z])Dialog\(""").findAll(window).count(),
        )
        assertTrue(
            "the window must be full-bleed: a journey with a spine down its side needs the height " +
                "of the handset to be a journey at all",
            window.contains("usePlatformDefaultWidth = false") && window.contains("fillMaxSize()"),
        )
    }

    // ── One exit ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `skip and done and the back gesture are literally the same exit`() {
        // Not "all three write the flag" — all three are THE SAME LAMBDA, which is the only version
        // of that requirement that cannot rot. Two lambdas doing the same two things is precisely
        // the shape in which one of them later loses a half, and the symptom does not appear until
        // the next morning.
        listOf("Text(\"Skip\")", "Text(\"Done\")").forEach { label ->
            val at = journey.indexOf(label)
            assertTrue("the journey has no $label button any more", at >= 0)
            val button = journey.substring(maxOf(0, at - 200), at)
            assertTrue(
                "$label no longer calls onFinish — it is a second way out of the walkthrough, and " +
                    "a second way out is a way out that can forget the seen flag",
                button.contains("onFinish"),
            )
        }
        assertEquals(
            "back is owned by exactly one handler. `dismissOnBackPress` is false on the window so " +
                "that the gesture has one listener rather than two with different opinions; a " +
                "second BackHandler here would restore the ambiguity from the other side",
            1,
            occurrences(journey, "BackHandler("),
        )
        assertTrue(
            "the window must keep dismissOnBackPress = false, or the platform's own listener and " +
                "the journey's both fire and which wins is an ordering detail of whichever Compose " +
                "version is on the classpath",
            window.contains("dismissOnBackPress = false"),
        )
        // Back from the journey must land the reader where they came from. It does so by not
        // navigating at all: the walkthrough is a dialog drawn OVER the page that was already
        // there, so closing it reveals that page — the dashboard on first run, the half-filled form
        // otherwise. There is no stack to pop and no destination to compute, which is what rules
        // out the failure this is guarded against: a back press finishing the activity and dropping
        // somebody onto the launcher. That property is only true while it stays a dialog, which the
        // Screen assertion above is what protects.
        assertFalse(
            "the journey must not navigate on back — closing the dialog is the whole of it",
            journey.contains("goBack()") || journey.contains("Screen."),
        )
    }

    @Test
    fun `every exit from the walkthrough writes the seen flag`() {
        // ONE WRITE SITE IN THE WHOLE APPLICATION. Not "the exits remember to write it" — there is
        // one place that can, so there is nothing to remember. Everything the journey offers
        // funnels into `onFinish`, `onFinish` is bound to `finishWalkthrough`, and
        // `finishWalkthrough` is the only caller of `markWalkthroughSeen` anywhere.
        assertEquals(
            "the seen flag is written from more than one place; the extra one is the one that " +
                "will lose it",
            1,
            occurrences(mainActivity, "markWalkthroughSeen("),
        )
        assertEquals(
            "the journey must not write the flag itself — it has no Context and no business " +
                "having one, and a second writer is a second thing to keep in step",
            0,
            occurrences(journey, "markWalkthroughSeen"),
        )
        val exit = Regex("""fun\s+finishWalkthrough\(\)\s*\{[^}]*}""").find(mainActivity)?.value
        assertNotNull("finishWalkthrough is gone or is no longer a single-expression body", exit)
        assertTrue(
            "finishWalkthrough must do BOTH halves: hide the walkthrough and mark it seen. Doing " +
                "only the first is the defect that reopens the walkthrough tomorrow morning for " +
                "somebody who already said no to it. It reads: $exit",
            exit!!.contains("showWalkthrough = false") && exit.contains("markWalkthroughSeen(context)"),
        )
        // Both of the dialog's own lambdas route through it — Skip/Done/back through `onFinish`,
        // and a step's own "Open ..." button through `onOpen`, because a designer who used the
        // guide to get somewhere got what it is for.
        val callSite = slice(mainActivity, "WalkthroughDialog(", 240)
        assertTrue(
            "onFinish no longer routes through finishWalkthrough: $callSite",
            callSite.contains("onFinish = { finishWalkthrough() }"),
        )
        assertTrue(
            "onOpen must mark seen before it leaves, and must leave through `navigate` rather than " +
                "`openDestination` — the walkthrough itself is exempt from the unsaved-changes " +
                "guard because it draws over the page you were on, but a screen it launches is a " +
                "real departure from a possibly half-filled form: $callSite",
            callSite.contains("finishWalkthrough() navigate(destination)"),
        )
    }

    @Test
    fun `opening the walkthrough does not write the seen flag`() {
        // The flag means "you have been shown this and are done with it", not "this has been
        // opened". Writing it on the way IN would be invisible in every manual pass and wrong in
        // exactly one case that matters: the process is killed mid-read — a designer twenty cards
        // in puts the phone down to photograph something and the camera takes the memory — and the
        // walkthrough must come back, because they have not read it. Every door below therefore
        // only ever sets the boolean.
        listOf(
            "the router arm" to "NavDestination.WALKTHROUGH -> showWalkthrough = true",
            "the dashboard button" to "onWalkthrough = { message = null; showWalkthrough = true }",
        ).forEach { (name, wiring) ->
            assertTrue("$name is gone or no longer reads `$wiring`", mainActivity.contains(wiring))
            assertFalse("$name writes the seen flag on the way in", wiring.contains("markWalkthroughSeen"))
        }
        // And the read is a read. One bare call to `walkthroughSeen`, in the initialiser asserted
        // below; `markWalkthroughSeen` is excluded by the letter in front of it rather than by
        // hoping the two names never collide.
        assertEquals(
            "the seen flag is read from more than one place, so the two can now disagree about " +
                "whether the walkthrough is due",
            1,
            Regex("""(?<![A-Za-z])walkthroughSeen\(""").findAll(mainActivity).count(),
        )
    }

    // ── The three orderings ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the first run flag is read while the first screen is being decided`() {
        // IT USED TO BE A `LaunchedEffect(Unit)` AND THAT WAS ONE FRAME LATE. An effect runs after
        // the composition it belongs to has been applied, so the dashboard painted once on its own
        // and the walkthrough arrived over it on the next frame — a flash of the real screen shown
        // to the one person who has never seen either. Reading inside the `remember` initialiser is
        // what makes the decision part of the first composition instead of a correction to it.
        assertTrue(
            "the first-run gate is no longer read in the remember initialiser; if it has moved to " +
                "an effect, the dashboard flashes before the walkthrough arrives over it",
            Regex("""var\s+showWalkthrough\s+by\s+remember\s*\{\s*mutableStateOf\(!walkthroughSeen\(context\)\)\s*}""")
                .containsMatchIn(mainActivity),
        )
    }

    @Test
    fun `the walkthrough never sits on top of a required update prompt`() {
        // The update prompt is non-dismissable BY DESIGN — no "Later", and tapping outside or
        // pressing back does nothing — because the version underneath it can no longer talk to the
        // backend. A full-screen walkthrough drawn over it is therefore not a cosmetic ordering
        // bug: it is an app that can neither be used nor updated, whose only remedy is clearing the
        // app's data. This guard mattered when the walkthrough was a card floating over the middle
        // of the screen. It is load-bearing now that it fills the handset.
        assertTrue(
            "the ordering guard is gone: the walkthrough can now cover a required-update prompt " +
                "that cannot be dismissed",
            Regex("""if\s*\(\s*showWalkthrough\s*&&\s*pendingUpdate\s*==\s*null\s*\)""")
                .containsMatchIn(mainActivity),
        )
        // And it is rendered beside the other dialogs rather than inside the layout branch. A
        // prompt that only existed on the scrolling branch would never reach a designer sitting in
        // the Data Browser or in their appearance settings when the resume check fires.
        val guardAt = mainActivity.indexOf("if (showWalkthrough && pendingUpdate == null)")
        val updateAt = mainActivity.indexOf("pendingUpdate?.let { release ->")
        assertTrue("the required-update prompt is gone from MainActivity", updateAt > 0)
        assertTrue(
            "the walkthrough is no longer rendered immediately before the update prompt, so the " +
                "two are no longer obviously reading the same state in the same place",
            updateAt > guardAt && updateAt - guardAt < 400,
        )
    }

    @Test
    fun `only the destinations that draw OVER a form are exempt from the unsaved changes guard`() {
        // The walkthrough is not a departure: it draws OVER the page you are on and takes nothing
        // away from it, so prompting would offer to throw away a form that is not going anywhere.
        // Every destination that IS a departure must keep asking — that exemption widening by one
        // enum entry is how the island bar became a silent way out of a half-filled artisan form
        // once already.
        //
        // ONE ENTRY ON THIS APP. The designer portal has a second, its terms dialog, on the same
        // ground; this app has no such dialog, so anything beyond WALKTHROUGH fails here by name.
        val exemption = slice(mainActivity, "if (destination == NavDestination.WALKTHROUGH", 340)
        assertTrue(
            "the exemption from the unsaved-changes guard is gone or has changed shape: $exemption",
            exemption.contains("{ openDestination(destination) } else { attemptExit {"),
        )
        assertEquals(
            "the unsaved-changes exemption widened. Every member listed here must be a DIALOG " +
                "drawn over the current screen; anything that assigns `screen` is a departure and " +
                "owes the prompt: $exemption",
            listOf("WALKTHROUGH"),
            Regex("""NavDestination\.(\w+)""")
                .findAll(exemption.substringBefore(") {"))
                .map { it.groupValues[1] }
                .toList(),
        )
    }

    // ── Both doors ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `both doors back into the walkthrough are still there`() {
        // ON THIS APP THE TWO DOORS ARE THE ISLAND BAR'S LOOSE "Walkthrough" ROOT AND THE DASHBOARD'S
        // "Open the walkthrough" BUTTON. The designer portal's third, a Settings row, is not carried
        // across: the bar's root is on screen on every page for every role, which is the job that row
        // does there for the roles whose dashboard has no button.
        // The walkthrough opens itself once and then never again on its own. These two rows are the
        // whole of how anybody reads it a second time — the designer halfway through an artisan
        // form who cannot remember whether Do's and Don'ts is one lesson per line. Losing one is
        // invisible, because the other still works and the feature still "works".
        val menu = FIELD_NAV_ITEMS.firstOrNull { it.destination == NavDestination.WALKTHROUGH }
        assertNotNull("the Walkthrough menu row is gone from FIELD_NAV_ITEMS", menu)
        assertEquals("the menu row must keep the web's own word for it", "Walkthrough", menu!!.label)
        assertTrue(
            "the Walkthrough row sits loose above the groups, mirroring the web's pill; giving it " +
                "a group buries the one row a newcomer is looking for",
            menu.group == null,
        )
        assertFalse("the Walkthrough row must not be admin chrome", menu.adminSurface)
        assertTrue(
            "the dashboard's button into the walkthrough is gone",
            mainActivity.contains("onWalkthrough = { message = null; showWalkthrough = true }") &&
                mainActivity.contains("TextButton(onClick = onWalkthrough"),
        )
    }

    @Test
    fun `the walkthrough is handed the signed-in account so a deck can be chosen for it`() {
        // TWO WALKTHROUGHS SINCE 2026-09-15, AND THE ROLE PICKS WHICH ONE OPENS. The dialog was
        // role-blind: it hardcoded `steps = walkthroughSteps` and took no account at all, so an
        // Inspector / Reviewer was handed twenty-five cards about recording artisans, exactly one of
        // which describes a screen they can open. What is pinned here is the wiring, not the rule —
        // `WalkthroughDecksTest` owns which deck each tier lands on — because the wiring is the half
        // that can be undone by a refactor that looks like a simplification: drop the argument, and
        // the selection silently becomes "the designer's, always" with every deck test still green.
        val callSite = slice(mainActivity, "WalkthroughDialog(", 240)
        assertTrue(
            "the walkthrough is no longer handed the signed-in account, so it cannot choose a deck " +
                "for them and every tier opens on the researcher's: " + callSite,
            callSite.contains("user = user"),
        )
        assertEquals(
            "the deck is chosen from more than one place, or from none. `walkthroughDeckFor` is the " +
                "one rule and the window is the one caller: a second answer is a second opinion " +
                "about whose walkthrough this is",
            1,
            occurrences(window, "walkthroughDeckFor("),
        )
        assertFalse(
            "the window hardcodes one deck again, which is what made it role-blind the first time",
            window.contains("steps = walkthroughSteps"),
        )
        // AND IT IS A DEFAULT, NOT A GATE. The switcher on the opening card draws the whole
        // register, unfiltered — `tracks.ts` rules for the web that the role "picks which deck OPENS
        // and takes nothing away from anybody", and a handset that narrowed the register per account
        // would be enforcing as a gate what the browser offers as a default.
        assertTrue(
            "the window no longer hands the journey the whole deck register, so a reader cannot " +
                "reach any deck but the one their role opened",
            window.contains("decks = WALKTHROUGH_DECKS"),
        )
    }

    @Test
    fun `the menu row into the walkthrough is ungated for every role there is`() {
        // A CROWDSOURCE VOLUNTEER ON DAY ONE NEEDS THIS MORE THAN AN ADMIN DOES, and they are the
        // account with no capability at all — so they are also the account a `can` predicate added
        // while tidying the nav table would take it away from first. Walked role by role rather
        // than asserted against the `everyone` lambda by identity, because what matters is the
        // answer for each account and not which predicate happens to produce it.
        val menu = FIELD_NAV_ITEMS.first { it.destination == NavDestination.WALKTHROUGH }
        listOf(
            "CROWDSOURCE_VOLUNTEER",
            "FIELD_CONTRIBUTOR",
            "RESEARCHER",
            "PROFESSOR",
            "ADMIN",
            "MASTER_ADMIN",
        ).forEach { role ->
            assertTrue(
                "the walkthrough is hidden from $role. It is the one row that must never be " +
                    "gated: several of its steps describe screens that need designer access and " +
                    "each of those steps ends by saying so, which is how somebody who has not been " +
                    "granted it finds out the capability exists to be asked for",
                menu.can(UserDto(id = "u-$role", email = "$role@example.org", name = role, role = role)),
            )
        }
    }

    // ── Motion and the screen reader ────────────────────────────────────────────────────────────

    @Test
    fun `both reduce-motion switches get a vote`() {
        // The Accessibility card promises the device's own reduce-motion setting is honoured too. A
        // promise that rests entirely on a framework default is a promise nothing asserts.
        assertTrue(
            "the app's own reducedMotion preference must be read",
            window.contains("LocalAppPreferences.current.reducedMotion"),
        )
        assertTrue(
            "the device's own animator duration scale must be read too",
            window.contains("Settings.Global.ANIMATOR_DURATION_SCALE"),
        )
    }

    @Test
    fun `the reveal is switched off for a screen reader rather than worked around`() {
        // A layer at alpha zero reports not-visible-to-user for itself and every descendant, and
        // TalkBack drops those nodes. The reveal must not run for a reader exploring by touch.
        assertTrue(
            "the journey must ask whether touch exploration is on",
            journey.contains("isTouchExplorationEnabled"),
        )
        assertFalse(
            "isEnabled is true for any accessibility service at all and is the wrong question",
            journey.contains("manager.isEnabled"),
        )
        assertTrue(
            "the screen-reader answer and the reduced-motion answer must be ORed into ONE value",
            journey.contains("val revealAll = reduceMotion || walkthroughScreenReaderActive()"),
        )
    }

    // ── The flag's own two strings ──────────────────────────────────────────────────────────────

    @Test
    fun `the seen flag keeps its pre-rebrand file name and key`() {
        // `getSharedPreferences` with a name no file has yet does not fail: it hands back an empty
        // document. So renaming either of these would not migrate one stored flag — it would
        // silently re-show the walkthrough to every installed user on the update that renamed it,
        // with no error anywhere and nothing to notice until the reports arrive. `TokenStore` and
        // `AppPreferencesStore` carry the same warning over their own file names, where the price
        // is a forced sign-out and a lost theme respectively.
        assertTrue(
            "the walkthrough's preferences file has been renamed away from the pre-rebrand name",
            window.contains("\"fieldrepo_prefs\""),
        )
        assertTrue(
            "the walkthrough's seen key has been renamed",
            window.contains("\"walkthrough_seen\""),
        )
        // `apply()` and not `commit()`: the write goes to a background thread and the in-memory
        // value updates at once, so a read on the very next frame already sees true. A `commit()`
        // blocks the UI thread on a disk write at the exact moment the designer is trying to leave.
        assertTrue("the flag write is no longer apply()", window.contains(".apply()"))
        assertFalse(
            "the flag write blocks the UI thread on a disk write, at the moment the designer is " +
                "trying to leave the walkthrough",
            window.contains(".commit()"),
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────────────────────────

// `repoFile` is `RepoSources.kt`, in this package.

/**
 * A Kotlin file with its comments removed and every run of whitespace flattened to one space.
 *
 * The flattening is what lets the assertions above be written as the source reads rather than as the
 * source is indented. Every line this file looks for is inside three or four levels of composable,
 * so its leading whitespace is both large and the single most likely thing about it to change; an
 * assertion that broke when somebody reformatted a lambda would be an assertion everybody learns to
 * delete rather than to read.
 */
private fun flattenedCode(relative: String): String =
    stripComments(repoFile(relative).readText(Charsets.UTF_8).replace("\r\n", "\n"))
        .replace(Regex("""\s+"""), " ")

/** Occurrences of a literal in already-stripped code. */
private fun occurrences(source: String, literal: String): Int =
    Regex(Regex.escape(literal)).findAll(source).count()

/** The [length] characters of [source] starting at [literal], for an assertion message worth reading. */
private fun slice(source: String, literal: String, length: Int): String {
    val at = source.indexOf(literal)
    if (at < 0) return "<<$literal not found>>"
    return source.substring(at, minOf(source.length, at + length))
}

/**
 * Drop line and block comments, keeping string contents intact.
 *
 * A copy of the stripper in `DashboardTileParityTest`, which pins both halves of its behaviour in a
 * test of its own. It NESTS, because Kotlin block comments nest: an opener written inside a comment
 * opens a second one that the block's own closer then closes instead of ending the block, and a
 * scanner that did not nest would hand this file the tail of a comment and call it code. It also
 * tracks quotes, because these three files are full of string values with slashes in them and a
 * scanner that mistook one for a comment opener would swallow the rest of the line.
 */
private fun stripComments(source: String): String {
    val out = StringBuilder()
    var i = 0
    var quote: Char? = null
    while (i < source.length) {
        val c = source[i]
        val next = if (i + 1 < source.length) source[i + 1] else ' '
        if (quote != null) {
            out.append(c)
            if (c == '\\') {
                if (i + 1 < source.length) out.append(next)
                i += 2
                continue
            }
            if (c == quote) quote = null
            i += 1
            continue
        }
        if (c == '"' || c == '\'') {
            quote = c
            out.append(c)
            i += 1
            continue
        }
        if (c == '/' && next == '/') {
            while (i < source.length && source[i] != '\n') i += 1
            continue
        }
        if (c == '/' && next == '*') {
            var depth = 1
            i += 2
            while (i < source.length && depth > 0) {
                if (source[i] == '/' && i + 1 < source.length && source[i + 1] == '*') {
                    depth += 1
                    i += 2
                    continue
                }
                if (source[i] == '*' && i + 1 < source.length && source[i + 1] == '/') {
                    depth -= 1
                    i += 2
                    continue
                }
                i += 1
            }
            continue
        }
        out.append(c)
        i += 1
    }
    return out.toString()
}
