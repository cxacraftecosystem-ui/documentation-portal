package com.fieldrepository.app.ui

import com.fieldrepository.app.data.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WalkthroughSurfaceTest {

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
        assertTrue("MainActivity.kt parsed to ${mainActivity.length} characters of code", mainActivity.length > 100_000)
        assertTrue("WalkthroughScreen.kt parsed to ${window.length} characters of code", window.length > 500)
        assertTrue("WalkthroughJourney.kt parsed to ${journey.length} characters of code", journey.length > 5_000)
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

    @Test
    fun `the step list is not empty and every id is unique`() {
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

    @Test
    fun `there is exactly one walkthrough surface`() {
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
        assertFalse(
            "the walkthrough has become a Screen; see the argument in WalkthroughScreen.kt",
            mainActivity.contains("Screen.Walkthrough"),
        )
        assertFalse("the alert-shaped card is still in the window file", window.contains("ElevatedCard"))
        assertFalse("the window is an AlertDialog again", window.contains("AlertDialog"))
        assertEquals(
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

    @Test
    fun `skip and done and the back gesture are literally the same exit`() {
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
            "one back press must leave the walkthrough — collapsing a step is a tap on the step, " +
                "and an open first step made a first-run reader press back twice",
            journey.contains("BackHandler(enabled = true) { onFinish() }"),
        )
        assertTrue(
            "the window must keep dismissOnBackPress = false, or the platform's own listener and " +
                "the journey's both fire and which wins is an ordering detail of whichever Compose " +
                "version is on the classpath",
            window.contains("dismissOnBackPress = false"),
        )
        assertFalse(
            "the journey must not navigate on back — closing the dialog is the whole of it",
            journey.contains("goBack()") || journey.contains("Screen."),
        )
    }

    @Test
    fun `every exit from the walkthrough writes the seen flag`() {
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
        listOf(
            "the router arm" to "NavDestination.WALKTHROUGH -> showWalkthrough = true",
            "the dashboard button" to "onWalkthrough = { message = null; showWalkthrough = true }",
        ).forEach { (name, wiring) ->
            assertTrue("$name is gone or no longer reads `$wiring`", mainActivity.contains(wiring))
            assertFalse("$name writes the seen flag on the way in", wiring.contains("markWalkthroughSeen"))
        }
        assertEquals(
            "the seen flag is read from more than one place, so the two can now disagree about " +
                "whether the walkthrough is due",
            1,
            Regex("""(?<![A-Za-z])walkthroughSeen\(""").findAll(mainActivity).count(),
        )
    }

    @Test
    fun `the first run flag is read while the first screen is being decided`() {
        assertTrue(
            "the first-run gate is no longer read in the remember initialiser; if it has moved to " +
                "an effect, the dashboard flashes before the walkthrough arrives over it",
            Regex("""var\s+showWalkthrough\s+by\s+remember\s*\{\s*mutableStateOf\(!walkthroughSeen\(context\)\)\s*}""")
                .containsMatchIn(mainActivity),
        )
    }

    @Test
    fun `the walkthrough never sits on top of a required update prompt`() {
        assertTrue(
            "the ordering guard is gone: the walkthrough can now cover a required-update prompt " +
                "that cannot be dismissed",
            Regex("""if\s*\(\s*showWalkthrough\s*&&\s*pendingUpdate\s*==\s*null\s*\)""")
                .containsMatchIn(mainActivity),
        )
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

    @Test
    fun `both doors back into the walkthrough are still there`() {
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
        assertTrue(
            "the window no longer hands the journey the whole deck register, so a reader cannot " +
                "reach any deck but the one their role opened",
            window.contains("decks = WALKTHROUGH_DECKS"),
        )
    }

    @Test
    fun `the menu row into the walkthrough is ungated for every role there is`() {
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

    @Test
    fun `both reduce-motion switches get a vote`() {
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

    @Test
    fun `the seen flag keeps its pre-rebrand file name and key`() {
        assertTrue(
            "the walkthrough's preferences file has been renamed away from the pre-rebrand name",
            window.contains("\"fieldrepo_prefs\""),
        )
        assertTrue(
            "the walkthrough's seen key has been renamed",
            window.contains("\"walkthrough_seen\""),
        )
        assertTrue("the flag write is no longer apply()", window.contains(".apply()"))
        assertFalse(
            "the flag write blocks the UI thread on a disk write, at the moment the designer is " +
                "trying to leave the walkthrough",
            window.contains(".commit()"),
        )
    }
}

private fun flattenedCode(relative: String): String =
    stripComments(repoFile(relative).readText(Charsets.UTF_8).replace("\r\n", "\n"))
        .replace(Regex("""\s+"""), " ")

private fun occurrences(source: String, literal: String): Int =
    Regex(Regex.escape(literal)).findAll(source).count()

private fun slice(source: String, literal: String, length: Int): String {
    val at = source.indexOf(literal)
    if (at < 0) return "<<$literal not found>>"
    return source.substring(at, minOf(source.length, at + length))
}

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
