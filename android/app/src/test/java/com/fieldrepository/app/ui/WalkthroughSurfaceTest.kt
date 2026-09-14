package com.fieldrepository.app.ui

import com.fieldrepository.app.data.UserDto
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walkthrough's WIRING, asserted by reading the two source files this feature owns.
 *
 * ── WHY A TEST READS SOURCE AT ALL ───────────────────────────────────────────────────────────────
 *
 * Everything asserted here is a property of a Compose surface that no JVM test can observe by calling
 * anything: whether the window is a dialog or a full screen, whether back has one listener or two,
 * whether the exit that Skip calls is the same exit Done calls, whether the preference key is still
 * the pre-rebrand one. These are exactly the properties that are cheap to break while "tidying" and
 * expensive to notice — the seen-flag key in particular, whose symptom is that every installed user
 * is shown the walkthrough again on the morning after a release. An instrumented test could observe
 * some of them on a device; this module has no instrumented suite and adding one to answer four
 * structural questions would be a device farm in exchange for four regexes.
 *
 * ── THE SCOPE IS THE FILES THIS WORKSTREAM OWNS, AND THAT IS A REAL GAP ──────────────────────────
 *
 * The designer portal's equivalent suite also reads `MainActivity.kt`, where it pins the first-run
 * gate, the router arm, the single `finishWalkthrough` and the unsaved-changes exemption. Those
 * assertions are NOT here, because `MainActivity.kt` belongs to another workstream in this run and
 * the wiring they would assert has not landed yet — a test that fails for work somebody else has not
 * done is a red suite that teaches everybody to ignore red suites. The handoff notes list them
 * verbatim so they can be added in the same change that does the wiring. What IS asserted here holds
 * regardless of how the dialog is opened.
 *
 * ── AN AUTHORING RULE FOR THIS FILE, AND IT HAS ALREADY BITTEN SOMEBODY ──────────────────────────
 *
 * Never type a Kotlin block-comment opener or closer anywhere in this file's own prose, not even
 * inside a string literal or as an example. [stripComments] below is nesting-aware, so an unbalanced
 * pair typed into this comment would leave the stripper counting depth for the rest of the file — and
 * because this file is itself Kotlin, a stray pair here breaks the whole unit-test source set rather
 * than one assertion. Describe them in words, as this paragraph does.
 */
class WalkthroughSurfaceTest {

    private val window: String by lazy { flattenedCode(WINDOW_PATH) }
    private val journey: String by lazy { flattenedCode(JOURNEY_PATH) }

    @Test
    fun `the parser found the two files it is aimed at`() {
        /*
         * THE GUARD THAT KEEPS EVERY OTHER ASSERTION FROM BEING VACUOUSLY GREEN. A `contains` against
         * an empty string is false, an `occurrences` count against one is zero, and a suite that
         * cannot find its subject would report "the wiring is fine" while checking nothing at all.
         */
        assertTrue("$WINDOW_PATH came back too small to be the walkthrough window.", window.length > 1_000)
        assertTrue("$JOURNEY_PATH came back too small to be the journey.", journey.length > 5_000)

        /*
         * A CANARY ON THE COMMENT STRIPPER ITSELF. Both of these files are majority prose — they argue
         * their decisions at length — so if the stripper ever silently stops stripping, the searches
         * below start matching text that is DISCUSSED rather than code that RUNS, and counts come back
         * several times too high. Requiring that stripping removes at least thirty per cent of each
         * file is a floor no reasonable edit crosses and no broken stripper reaches.
         */
        for (path in listOf(WINDOW_PATH, JOURNEY_PATH)) {
            val raw = repoFile(path).readText(Charsets.UTF_8).replace("\r\n", "\n")
            val stripped = stripComments(raw)
            assertTrue(
                "Stripping comments out of $path removed almost nothing (${stripped.length} of " +
                    "${raw.length} characters left). The stripper is broken, and every search in " +
                    "this class is now matching prose.",
                stripped.length * 10 < raw.length * 7,
            )
        }
    }

    @Test
    fun `there is exactly one walkthrough surface and it is a full-bleed dialog`() {
        /*
         * BEING A DIALOG IS NOT A STYLING CHOICE, IT IS WHAT MAKES TWO OTHER THINGS CORRECT.
         * `MainActivity.navigate` exempts `NavDestination.WALKTHROUGH` from the unsaved-changes guard
         * on the stated ground that the walkthrough "draws OVER the page you are already on … so
         * there is nothing to save or discard". That exemption is only true while this is a dialog.
         * And the back gesture inside a dialog binds to the dialog's own dispatcher rather than the
         * Activity's, which is what makes it structurally impossible for a back press in here to
         * finish the Activity and drop somebody onto the launcher.
         */
        assertEquals(
            "The window must host the journey exactly once.",
            1,
            occurrences(window, "WalkthroughJourney("),
        )
        assertEquals(
            "There must be exactly one Dialog in the window file.",
            1,
            Regex("""(?<![A-Za-z])Dialog\(""").findAll(window).count(),
        )
        assertFalse(
            "An AlertDialog is the fixed-width, non-scrolling shape this feature replaced " +
                "(MainActivity.kt:7875). Several of these bodies run past four hundred characters.",
            window.contains("AlertDialog"),
        )
        assertFalse(
            "The walkthrough must not become a Screen: that would put it behind the " +
                "unsaved-changes guard and give the back gesture the Activity's dispatcher.",
            window.contains("Screen."),
        )
        assertTrue(
            "The dialog must be full-bleed — the platform's ~280dp alert width cannot hold a " +
                "journey with a spine down its left-hand side.",
            window.contains("usePlatformDefaultWidth = false") && window.contains("fillMaxSize()"),
        )
    }

    @Test
    fun `skip and done and the back gesture are literally the same exit`() {
        /*
         * The requirement is that Skip and Done both clear the seen flag and land in the same place,
         * and it is only ENFORCEABLE if the two are the same call. Two lambdas doing the same two
         * things is exactly the shape in which one of them later loses the flag write, and the
         * symptom — the walkthrough reappearing tomorrow for somebody who dismissed it — does not
         * show up until tomorrow.
         */
        for (label in listOf("""Text("Skip")""", """Text("Done")""")) {
            val at = journey.indexOf(label)
            assertTrue("$label is not drawn anywhere in the journey.", at >= 0)
            val before = journey.substring(maxOf(0, at - 200), at)
            assertTrue(
                "$label does not call onFinish. Every exit from this surface goes through the one " +
                    "lambda the caller owns.",
                before.contains("onFinish"),
            )
        }
        assertEquals(
            "Back must have exactly one handler. A second one is two opinions about the gesture " +
                "with registration order deciding which wins.",
            1,
            occurrences(journey, "BackHandler("),
        )
        assertTrue(
            "dismissOnBackPress must stay false: DialogWrapper registers its own callback on the " +
                "same dispatcher in its constructor, so true would give two enabled callbacks.",
            window.contains("dismissOnBackPress = false"),
        )
        assertFalse(
            "The journey must not navigate. It draws over whatever screen was already there, so " +
                "leaving is revealing that screen and never routing to a new one.",
            journey.contains("goBack()") || journey.contains("Screen."),
        )
    }

    @Test
    fun `the seen flag is written in one place and never by the journey itself`() {
        assertEquals(
            "The flag write belongs to the window file, next to the read, so there is one place to " +
                "look when somebody asks whether an exit remembers itself.",
            1,
            occurrences(window, "fun markWalkthroughSeen("),
        )
        assertEquals(
            "The journey must not write the seen flag. It reports an exit through onFinish and the " +
                "caller owns what that means; a second writer is a second thing to forget.",
            0,
            occurrences(journey, "markWalkthroughSeen"),
        )
    }

    @Test
    fun `the seen flag keeps its pre-rebrand file name and key`() {
        /*
         * ⚠ THE MOST EXPENSIVE ASSERTION IN THIS FILE, AND THE ONE THAT LOOKS MOST LIKE PEDANTRY.
         *
         * `MainActivity.kt:7728-7730` already stores `walkthrough_seen` in `fieldrepo_prefs` for the
         * deck this replaces, and every installed user has that pair on disk.
         * `getSharedPreferences` with a name no file has yet DOES NOT FAIL — it hands back an empty
         * document — so renaming either string would not migrate one stored flag and would not throw.
         * It would silently re-show the walkthrough to every installed user on the update that
         * renamed it, and nobody would find out until the morning after the release.
         */
        assertTrue(
            "The preferences file name must stay the pre-rebrand one.",
            window.contains("\"fieldrepo_prefs\""),
        )
        assertTrue(
            "The preference key must stay the one the shipped deck already wrote.",
            window.contains("\"walkthrough_seen\""),
        )
        assertTrue(
            "apply() and not commit(): the in-memory value updates at once, so a read on the very " +
                "next frame is already true, and the disk write does not block the UI thread at the " +
                "exact moment somebody is trying to leave a dialog.",
            window.contains(".apply()"),
        )
        assertFalse(
            "commit() blocks the UI thread on a disk write on the way out of the dialog.",
            window.contains(".commit()"),
        )
    }

    @Test
    fun `both reduce-motion switches get a vote`() {
        /*
         * `AppearanceScreen.kt:309` promises in as many words that "Your device's own reduce-motion
         * setting is always honoured too". Compose's recomposer does install a MotionDurationScale
         * from ANIMATOR_DURATION_SCALE, so the promise has been carried by a framework default — but a
         * promise that rests entirely on a framework default is a promise nothing in this repository
         * asserts. This is that assertion.
         */
        assertTrue(
            "The app's own reducedMotion preference must be read.",
            window.contains("LocalAppPreferences.current.reducedMotion"),
        )
        assertTrue(
            "The device's own animator duration scale must be read too.",
            window.contains("Settings.Global.ANIMATOR_DURATION_SCALE"),
        )
    }

    @Test
    fun `the reveal is switched off for a screen reader rather than worked around`() {
        /*
         * The reveal is a graphicsLayer whose alpha starts at zero, and a layer at alpha zero makes
         * NodeCoordinator.isTransparent true, which Compose turns into setVisibleToUser(false) for
         * the node AND EVERY DESCENDANT. TalkBack filters exactly those out of traversal, so a
         * scroll-driven reveal hands a blind reader a three-step walkthrough with no sign that the
         * rest exist. The fix has to be to not run the reveal; there is no way to keep it and keep
         * the nodes.
         *
         * Touch exploration and not isEnabled: isEnabled is true for any accessibility service at
         * all, including ones that do not navigate by traversal and should keep the animation.
         */
        assertTrue(
            "The journey must ask whether touch exploration is on.",
            journey.contains("isTouchExplorationEnabled"),
        )
        assertFalse(
            "isEnabled is true for any accessibility service at all and is the wrong question.",
            journey.contains("manager.isEnabled"),
        )
        assertTrue(
            "The screen-reader answer and the reduced-motion answer must be ORed into ONE value. " +
                "Three copies of a two-term condition is how one of them later loses a term.",
            journey.contains("val revealAll = reduceMotion || walkthroughScreenReaderActive()"),
        )
    }

    @Test
    fun `the menu row into the walkthrough is ungated for every role there is`() {
        /*
         * Read off FIELD_NAV_ITEMS itself rather than out of source: the predicate is a function and
         * calling it is a stronger assertion than matching the text of it.
         *
         * The row is ungated for the reason its own comment gives — the walkthrough teaches the
         * documentation process, so it has to reach the people who have earned no capability yet. A
         * crowdsource volunteer on their first day needs it MORE than an admin does, and gating the
         * one screen that explains the product behind the capabilities the product grants is the
         * shape of mistake that is only visible from the outside.
         */
        val entry = FIELD_NAV_ITEMS.firstOrNull { it.destination == NavDestination.WALKTHROUGH }
        assertNotNull("There is no Walkthrough row in FIELD_NAV_ITEMS.", entry)
        val row = entry!!
        assertEquals("Walkthrough", row.label)
        assertNull(
            "The Walkthrough row sits loose above the groups, as it does on the web.",
            row.group,
        )
        assertFalse("The Walkthrough row must not be admin chrome.", row.adminSurface)
        for (role in ROLES) {
            assertTrue(
                "The Walkthrough row is hidden from $role.",
                row.can(
                    UserDto(id = "u", email = "u@example.org", name = "U", role = role),
                ),
            )
        }
    }

    @Test
    fun `every door a step offers is a row that role can be shown`() {
        /*
         * The step's own button is NEVER hidden by role — that is deliberate and argued in
         * WalkthroughScreen.kt: three of these steps end by saying what happens when the capability
         * is missing, and hiding the button would make those sentences unreachable and would hide the
         * existence of a capability from the person who most needs to know it exists in order to ask
         * for it. What must hold is the weaker and more mechanical thing: every destination a step
         * points at is a destination the menu actually knows about, so the button can borrow a name.
         */
        for (step in walkthroughSteps) {
            val destination = step.destination ?: continue
            val row = FIELD_NAV_ITEMS.firstOrNull { it.destination == destination }
            assertNotNull("Step '${step.id}' points at $destination, which has no menu row.", row)
            assertTrue("$destination has a blank label.", row!!.label.isNotBlank())
        }
    }
}

// ── The roles this app has ──────────────────────────────────────────────────────────────────────

/**
 * Every role string `FieldPermissions.RANKS` knows (AppNavigation.kt:154-161).
 *
 * Typed out rather than read from that map because the map is private, and because a test that
 * iterated whatever the production code happened to contain could not fail when a role was
 * accidentally dropped from it.
 */
private val ROLES = listOf(
    "CROWDSOURCE_VOLUNTEER",
    "FIELD_CONTRIBUTOR",
    "RESEARCHER",
    "PROFESSOR",
    "ADMIN",
    "MASTER_ADMIN",
)

// ── Reading this module's own source ────────────────────────────────────────────────────────────

private const val WINDOW_PATH =
    "app/src/main/java/com/fieldrepository/app/ui/WalkthroughScreen.kt"
private const val JOURNEY_PATH =
    "app/src/main/java/com/fieldrepository/app/ui/WalkthroughJourney.kt"

// `repoFile` used to live here, copied from `WalkthroughStepsTest` with the condition for undoing
// that written into the comment: "If a third suite wants it, that is the moment to lift it." A
// fourth arrived. It is now `RepoSources.kt`, in this package, and takes a vararg — so this file's
// single-path calls now also try the `../`-prefixed spelling, which its own copy never could.

/**
 * A source file with its comments removed and its whitespace flattened to single spaces.
 *
 * BOTH HALVES ARE NECESSARY AND FOR DIFFERENT REASONS. Stripping comments is what stops a search
 * matching code that is DISCUSSED rather than code that RUNS — these two files argue every decision
 * at length and name most of their own identifiers while doing it, so an unstripped count of
 * `markWalkthroughSeen` comes back several times too high. Flattening whitespace is what lets an
 * assertion spell a multi-line expression the way a reader would say it out loud, instead of
 * committing to the exact line breaks the formatter happened to choose.
 */
private fun flattenedCode(path: String): String {
    val raw = repoFile(path).readText(Charsets.UTF_8).replace("\r\n", "\n")
    return stripComments(raw).replace(Regex("""\s+"""), " ")
}

/**
 * Kotlin source with its comments removed.
 *
 * NESTING-AWARE, BECAUSE KOTLIN BLOCK COMMENTS NEST — that is a real difference from C and Java and
 * it is the thing a naive stripper gets wrong: it stops at the first closing pair and leaves the rest
 * of an outer comment in the source as code. Quote-aware for the mirror-image reason: a file that
 * mentions a comment marker inside a string literal must not have the rest of itself eaten.
 *
 * This does not attempt to handle every Kotlin lexical form — character literals and raw strings with
 * embedded quotes are out of scope. It handles what these two files contain, and the canary in
 * `the parser found the two files it is aimed at` is what notices if that stops being true.
 */
private fun stripComments(source: String): String {
    val out = StringBuilder(source.length)
    var index = 0
    var depth = 0
    var inString = false
    while (index < source.length) {
        val here = source[index]
        val next = source.getOrNull(index + 1)
        when {
            depth > 0 -> {
                if (here == '/' && next == '*') {
                    depth++
                    index += 2
                } else if (here == '*' && next == '/') {
                    depth--
                    index += 2
                } else {
                    index++
                }
            }
            inString -> {
                out.append(here)
                if (here == '\\' && next != null) {
                    out.append(next)
                    index += 2
                } else {
                    if (here == '"') inString = false
                    index++
                }
            }
            here == '"' -> {
                inString = true
                out.append(here)
                index++
            }
            here == '/' && next == '*' -> {
                depth = 1
                index += 2
            }
            here == '/' && next == '/' -> {
                while (index < source.length && source[index] != '\n') index++
            }
            else -> {
                out.append(here)
                index++
            }
        }
    }
    return out.toString()
}

/** How many times `literal` appears in `source`, matched as text and never as a pattern. */
private fun occurrences(source: String, literal: String): Int =
    Regex(Regex.escape(literal)).findAll(source).count()
