package com.fieldrepository.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walkthrough's content, held to the web's.
 *
 * ── WHY THIS TEST READS A `.ts` FILE OFF DISK ────────────────────────────────────────────────────
 *
 * "The Android walkthrough teaches the same journey as the web one" is a claim about TWO clients, and
 * the failure mode of one requirement implemented twice is that the two agree on the day they are
 * written and drift on the next edit — which nobody notices, because nobody re-reads a `.ts` while
 * editing Kotlin. The artefact this feature replaces is the shape itself: `MainActivity.kt:7763`
 * declares a twelve-card deck whose titles carry hand-typed numbers (`"1. Workshop · Record
 * workshop"`, :7769) and whose opening card types the count as a literal (:7765). Both are right
 * today and nothing holds them there, and the whole thing is `private` inside a 13,751-line file, so
 * no test in this source set could even see it to be wrong about.
 *
 * The designer portal reached the same conclusion after its own hand-copied register of nineteen web
 * ids silently covered only nineteen of the web's twenty-two, hiding three renamed steps for months.
 * Its fix, and this one: READ the web's file instead of restating it, so the register has exactly one
 * copy and this comment cannot rot.
 *
 * ── THIS IS THE FIRST TEST IN THIS MODULE TO READ A FILE AT ALL ──────────────────────────────────
 *
 * `app/src/test/java/com/fieldrepository/app/ui/` held only `AccessRosterTest`, `RecordProseTest` and
 * `RecordDictationParityTest` before this, and none of them touches the filesystem. So there is no
 * sibling to point at for the walk-up helper and no established convention to follow: [repoFile] is
 * written out in full below, with its reasoning, and the single most important property of it is that
 * MISSING IS A LOUD FAILURE AND NEVER A SKIP. A test that quietly passed when it could not find its
 * subject would prove nothing on the day somebody moves that subject — which is the one day it is
 * most needed, and the day its silence would be read as parity.
 *
 * ── WHAT IS COMPARED, AND WHAT IS DELIBERATELY NOT ───────────────────────────────────────────────
 *
 * Compared, verbatim: the step IDS and their ORDER, the feature LABELS, the ACTION each step names,
 * the `fields[]` register, and the `watch[]` cautions. Compared structurally: the body, which must be
 * the web's `summary` followed by its `why`.
 *
 * Not compared: anything about how a card is drawn. Layout, colour and animation are
 * `WalkthroughJourney.kt`'s and are not a cross-client contract — the web has a 1024px sticky rail
 * and this has a 360dp pinned header, and both are correct.
 *
 * Two prose divergences ARE allowed, and both are declared as data below rather than tolerated by a
 * loose assertion — [WATCH_REWRITTEN] and [BODY_EXTENDED]. Each entry is a place where the web names
 * a surface this handset routes differently, each carries its reason, and a THIRD divergence fails
 * this suite rather than joining them quietly. That is the whole point of a register: a listed
 * exception is a decision, an unlisted one is a drift.
 */
class WalkthroughStepsTest {

    // ── Parity with the web: the journey ─────────────────────────────────────────────────────────

    @Test
    fun `the parser found the web's step list`() {
        /*
         * WITHOUT THIS, EVERY PARITY ASSERTION BELOW IS VACUOUSLY GREEN the day `steps.ts` is
         * reformatted, renamed or moved: WEB_GUIDE_STEPS comes back empty, "every subject the web
         * teaches has a step here" iterates nothing and passes, and the suite reports parity while
         * checking none. That is the failure mode of every source-reading test and it is the one that
         * wastes a morning, because the report says "the walkthrough is fine" when the parser is the
         * thing that is broken.
         */
        assertTrue(
            "No steps parsed out of $WEB_GUIDE_PATH — the anchor or the file has moved, and every " +
                "other assertion in this class is now checking nothing.",
            WEB_GUIDE_STEPS.size >= 10,
        )
        /*
         * A FLOOR AND NOT THE EXACT COUNT, DELIBERATELY. Pinning the number here would make this file
         * a third copy of the register — after the web's and this app's — and it would fail the day
         * the web legitimately adds a step, which is a day when the correct outcome is that
         * `every subject the web walkthrough teaches has a step here` fails and names the new one.
         */
        val ids = WEB_GUIDE_STEPS.map { it.id }
        assertEquals(
            "The web declares the same step id twice. The join every assertion here makes is by id.",
            ids.size,
            ids.toSet().size,
        )
    }

    @Test
    fun `every subject the web walkthrough teaches has a step here`() {
        val here = walkthroughJourney.map { it.id }.toSet()
        val missing = WEB_GUIDE_STEPS.map { it.id }.filterNot { it in here }
        /*
         * ONE-DIRECTIONAL ON PURPOSE. The handset may teach MORE than the web — there are subjects a
         * web guide structurally cannot cover — so a step here with no counterpart there is not a
         * failure. A subject the web teaches and this does not is: it means a researcher who read the
         * walkthrough on a laptop opens it in a courtyard and finds a step gone.
         */
        assertTrue(
            "The web teaches steps this handset does not: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `the web's steps appear here in the web's own order`() {
        val shared = walkthroughJourney.map { it.id }.filter { it in WEB_GUIDE_STEPS.map { s -> s.id } }
        /*
         * Filtered rather than compared whole, so that an Android-only step added later does not
         * break this assertion — it is the ORDER of the shared subjects that is the contract.
         *
         * And it is a LIST comparison and not a set one, because two clients teaching the same
         * subjects in different orders is worse than a missing step: both look complete and only one
         * of them is the order the work happens in. The workshop before the records it contains, the
         * craft before the artisan that requires one, the product before the process documented
         * against it — get that wrong and the pickers on the later screens are empty and the
         * researcher concludes the feature is broken.
         */
        assertEquals(
            "The two clients teach the same subjects in a different order.",
            WEB_GUIDE_STEPS.map { it.id },
            shared,
        )
    }

    @Test
    fun `every step wears the web's own name for its feature and for the control it opens`() {
        /*
         * THE LABEL HALF IS THE CROSS-CLIENT VOCABULARY AND NEITHER CLIENT MAY REWORD ITS SIDE ALONE.
         * `steps.ts`'s own header calls `label` "the Android-parity feature name … the walkthrough
         * must call a screen exactly what the dashboard tile and the Android menu call it, or the
         * guide teaches a vocabulary the product does not use". This is that sentence, enforced.
         *
         * The title is split with [walkthroughTitleParts] rather than with a second `indexOf(" · ")`
         * here, because a test that re-implements the rule it is checking is green on the day it is
         * written and silently covering nothing the day the separator changes.
         */
        for (web in WEB_GUIDE_STEPS) {
            val step = walkthroughJourney.first { it.id == web.id }
            val (feature, action) = walkthroughTitleParts(step.title)
            assertEquals("Step '${web.id}' names its feature differently from the web.", web.label, feature)
            assertEquals("Step '${web.id}' names its action differently from the web.", web.action, action)
        }
    }

    @Test
    fun `every step asks for exactly the fields the web says that screen asks for`() {
        /*
         * THE REGISTER THAT MOST NEEDS A GUARD, AND THE REASON IT IS ON THE STEP RATHER THAN IN A MAP.
         *
         * `steps.ts` says of these strings: "`fields` mirrors the real form labels one-for-one, so a
         * researcher reading the guide recognises the screen when they open it". They are therefore
         * not prose to be paraphrased — a chip reading "Craft" where the form says "Craft (required)"
         * teaches a researcher that a required field is optional.
         *
         * The designer portal keeps the same register in a side map and holds it to the web with 674
         * lines of Python that parses TypeScript on one side and Kotlin on the other. This repository
         * has no such test and this workstream could not add one. Keeping the strings on
         * [WalkStep.fields] instead means this assertion can IMPORT them — same module, `internal`
         * visibility — and compare them with no Kotlin parser at all. One register, one guard.
         */
        for (web in WEB_GUIDE_STEPS) {
            val step = walkthroughJourney.first { it.id == web.id }
            assertEquals(
                "Step '${web.id}' lists different form fields from the web's.",
                web.fields,
                step.fields,
            )
        }
    }

    @Test
    fun `no caution the web raises is dropped on the handset`() {
        /*
         * COUNT FIRST, SEPARATELY FROM THE WORDS, because losing a caution and rewording one are
         * different failures and only one of them is ever acceptable. The designer portal's version
         * of this feature shipped with two cautions present in the source and NOT DRAWN, because the
         * card extracted them from the body with a regex that wanted a colon and got a comma; its own
         * step test stayed green throughout, since it only asked whether the body mentioned the
         * words. A count against the web is the assertion that would have caught it.
         */
        for (web in WEB_GUIDE_STEPS) {
            val step = walkthroughJourney.first { it.id == web.id }
            assertEquals(
                "Step '${web.id}' draws ${step.watch.size} cautions where the web raises ${web.watch.size}.",
                web.watch.size,
                step.watch.size,
            )
        }
    }

    @Test
    fun `every caution is the web's own words unless this handset spells a control differently`() {
        for (web in WEB_GUIDE_STEPS) {
            val step = walkthroughJourney.first { it.id == web.id }
            web.watch.forEachIndexed { index, webNote ->
                val expected = WATCH_REWRITTEN[web.id to index] ?: webNote
                assertEquals(
                    "Caution ${index + 1} of step '${web.id}' is neither the web's sentence nor a " +
                        "declared rewrite. If this handset really does spell that control " +
                        "differently, add it to WATCH_REWRITTEN with the reason; otherwise the two " +
                        "clients have drifted.",
                    expected,
                    step.watch[index],
                )
            }
        }
    }

    @Test
    fun `every step opens with the web's summary and then the web's reason`() {
        /*
         * THE BODY IS NOT FREE PROSE HERE — it is the web's `summary` and `why`, concatenated, in that
         * order, and [walkthroughFacets] cuts them back apart at the first sentence boundary so the
         * summary becomes the collapsed line and the why becomes the opened panel. That is the web
         * card's own shape, and it is the durable half of the contract between the two clients.
         *
         * Comparing the whole string rather than `startsWith` is what makes the ONE declared
         * extension visible: an undeclared sentence appended to any other step fails here by name.
         */
        for (web in WEB_GUIDE_STEPS) {
            val step = walkthroughJourney.first { it.id == web.id }
            val expected = web.summary + " " + web.why + (BODY_EXTENDED[web.id] ?: "")
            assertEquals(
                "Step '${web.id}' does not carry the web's summary followed by the web's reason.",
                expected,
                step.body,
            )
        }
    }

    // ── The deck's own rules ─────────────────────────────────────────────────────────────────────

    @Test
    fun `no two steps share an id`() {
        val ids = walkthroughSteps.map { it.id }
        assertEquals("Two cards in the deck share an id.", ids.size, ids.toSet().size)
        assertTrue("A card in the deck has a blank id.", ids.none { it.isBlank() })
    }

    @Test
    fun `no step title carries its own number`() {
        /*
         * THE DEFECT THIS REPLACES, ASSERTED. `MainActivity.kt:7769` onward types the numbers into the
         * titles — "1. Workshop · Record workshop" — and the moment a step is inserted every title
         * after it is a lie that only a human re-reading the whole file could catch. The position is
         * derived instead, by [walkthroughStepNumber], which is the one place it may come from.
         */
        val numbered = Regex("""^\s*\d+\s*[.)]""")
        for (step in walkthroughSteps) {
            assertFalse(
                "'${step.title}' carries a hand-typed step number. Numbers come from " +
                    "walkthroughStepNumber and nowhere else.",
                numbered.containsMatchIn(step.title),
            )
        }
    }

    @Test
    fun `the opening card counts the journey rather than claiming a number`() {
        val intro = walkthroughSteps.first()
        assertNull("The opening card is not a numbered step.", walkthroughStepNumber(intro))
        assertTrue(
            "The opening card must state the journey's length by interpolating it. A typed number " +
                "is right on the day it is typed and nothing keeps it right — which is what " +
                "'Ten steps, in this order' at MainActivity.kt:7765 is, one literal away from the " +
                "list it claims to describe.",
            intro.body.contains("${walkthroughJourney.size} steps"),
        )
        assertFalse(
            "The opening card is counting the DECK, which includes itself and the closing card.",
            intro.body.contains("${walkthroughSteps.size} steps"),
        )
    }

    @Test
    fun `the deck is the journey with one card at each end`() {
        assertEquals(
            "The deck must be the journey plus exactly two cards.",
            walkthroughJourney.size + 2,
            walkthroughSteps.size,
        )
        assertEquals(
            "The middle of the deck must be the journey itself, untouched and in order.",
            walkthroughJourney,
            walkthroughSteps.subList(1, walkthroughSteps.size - 1),
        )
    }

    @Test
    fun `only the journey is numbered, and it is numbered from one`() {
        assertNull(walkthroughStepNumber(walkthroughSteps.first()))
        assertNull(walkthroughStepNumber(walkthroughSteps.last()))
        walkthroughJourney.forEachIndexed { index, step ->
            assertEquals(
                "Step '${step.id}' reports the wrong position.",
                index + 1,
                walkthroughStepNumber(step),
            )
        }
    }

    @Test
    fun `every step that offers a door names a menu row that exists`() {
        /*
         * The "Open …" button borrows its words from `FIELD_NAV_ITEMS` rather than typing them, so a
         * step pointing at a destination with no menu row would render "Open the screen this step
         * teaches" — a working button with no name, on the one surface whose job is to teach a
         * newcomer what things are called.
         */
        for (step in walkthroughSteps) {
            val destination = step.destination ?: continue
            assertNotNull(
                "Step '${step.id}' opens $destination, which has no row in FIELD_NAV_ITEMS.",
                FIELD_NAV_ITEMS.firstOrNull { it.destination == destination },
            )
        }
    }

    @Test
    fun `the two ends open nothing, and nothing in the deck reopens the walkthrough`() {
        assertNull(walkthroughSteps.first().destination)
        assertNull(walkthroughSteps.last().destination)
        assertTrue(
            "A step offering to open the walkthrough would close it and open it again.",
            walkthroughSteps.none { it.destination == NavDestination.WALKTHROUGH },
        )
    }

    @Test
    fun `every card wears a glyph and says what it is`() {
        for (step in walkthroughSteps) {
            assertNotNull("Card '${step.id}' has no icon.", step.icon)
            assertTrue("Card '${step.id}' has a blank title.", step.title.isNotBlank())
            assertTrue("Card '${step.id}' has a blank body.", step.body.isNotBlank())
        }
    }
}

// ── The declared divergences ────────────────────────────────────────────────────────────────────

/**
 * Cautions this handset words differently from the web, by step id and position, with the reason.
 *
 * A LISTED EXCEPTION IS A DECISION; AN UNLISTED ONE IS A DRIFT. Every entry here has to name a
 * control this app actually spells differently, and the entry is what makes the difference auditable
 * — otherwise the only way to tell an intentional rewrite from a sloppy re-typing is to diff two
 * files in two languages by eye.
 */
private val WATCH_REWRITTEN: Map<Pair<String, Int>, String> = mapOf(
    /*
     * The web ends this one "use Search to find records instead" (steps.ts:321). There is no row
     * called "Search" in this menu: the same capability is `NavDestination.BROWSE_RECORDS`, labelled
     * "Browse records" (AppNavigation.kt:349) and ungated. Sending a researcher who has just been
     * refused the dataset to a name the drawer does not use turns a working fallback into a dead end
     * at the exact moment they need it.
     */
    ("view-data" to 2) to
        "Dataset download is a granted permission. If your role does not have it the browser " +
        "shows a restricted notice — use Browse records to find records instead.",
)

/**
 * Sentences appended to a step's body because the web's version names a surface this handset does not
 * have. Same rule as [WATCH_REWRITTEN]: declared here or it is a drift.
 */
private val BODY_EXTENDED: Map<String, String> = mapOf(
    /*
     * The web's `review.href` is `/review`, a page of its own (steps.ts:286). This handset has none:
     * `MainActivity.kt:1180` routes `NavDestination.REVIEW -> screen = screenFor(EntryMode.VIEW_DATA)`
     * under its own comment, "Android has no standalone review queue: reviewing happens inside the
     * record browser". The capability is here and the menu row is still called "Review", so the step
     * is not dropped — only the address is corrected. Carrying the web's sentence across unchanged
     * would send a researcher hunting the drawer for a queue that was never built, and what they
     * would conclude is that they cannot find it rather than that it is not there.
     */
    "review" to
        " This handset has no separate review queue: the \"Review\" row opens the record browser, " +
        "which is the one surface where a reviewer can read a submission and act on it.",
)

// ── Reading the web's own step list ─────────────────────────────────────────────────────────────

/** The one place this path is written. Named in failure messages so a move reports itself. */
private const val WEB_GUIDE_PATH = "frontend/components/guide/steps.ts"

/** One `GuideStep` as the web declares it, with every field this suite compares. */
private data class WebGuideStep(
    val id: String,
    val label: String,
    val action: String,
    val summary: String,
    val why: String,
    val fields: List<String>,
    val watch: List<String>,
)

/**
 * The steps `frontend/components/guide/steps.ts` declares, in its own order, read from the file.
 *
 * ── THE ANCHOR, AND WHY IT IS THIS ONE ───────────────────────────────────────────────────────────
 *
 * `GuideStep` objects are elements of one array literal, so their `id` sits at exactly four spaces of
 * indent. Nothing else in that file does: a nested field is deeper, a top-level declaration is
 * shallower, and a line of prose inside a comment block starts with a space and an asterisk. Checked
 * rather than assumed — the ten ids are the only four-space `id:` lines in the file.
 *
 * The scan starts at the `GUIDE_STEPS` declaration rather than at the top of the file, so a helper
 * array declared above it can never contribute an id. `the parser found the web's step list` fails
 * loudly if either half of that stops holding.
 *
 * COMMENTS ARE NOT STRIPPED, and that is a decision rather than an omission. A TypeScript comment
 * line in that file begins with a space and an asterisk or with two slashes, so none of them can
 * present a line in the anchor's shape, and none of them sits inside the `fields: [` or `watch: [`
 * bodies this reads. Adding a comment stripper would be a second parser to keep correct in exchange
 * for nothing.
 */
private val WEB_GUIDE_STEPS: List<WebGuideStep> by lazy {
    val source = webGuideSource()
    val start = source.indexOf("export const GUIDE_STEPS")
    check(start >= 0) { "GUIDE_STEPS is no longer declared in $WEB_GUIDE_PATH" }
    val body = source.substring(start)
    val anchors = Regex("""^ {4}id: "([^"]+)"""", RegexOption.MULTILINE).findAll(body).toList()
    anchors.mapIndexed { index, match ->
        val from = match.range.first
        val to = anchors.getOrNull(index + 1)?.range?.first ?: body.length
        val block = body.substring(from, to)
        WebGuideStep(
            id = match.groupValues[1],
            label = tsString(block, "label"),
            action = tsString(block, "action"),
            summary = tsString(block, "summary"),
            why = tsString(block, "why"),
            fields = tsArray(block, "fields"),
            watch = tsArray(block, "watch"),
        )
    }
}

/**
 * The single-quoted-free, double-quoted string literal `key` is assigned in `block`.
 *
 * `steps.ts` writes some of these on the declaration line and some on the line below it
 * (`summary:` and `why:` wrap), so the search is for the key and then for the next opening quote
 * rather than for a same-line pattern. The value is unescaped by [tsStrings], which is what turns the
 * file's `\"Document using grid\"` into the quoted phrase Kotlin holds.
 */
private fun tsString(block: String, key: String): String {
    val at = block.indexOf("$key:")
    check(at >= 0) { "'$key' is missing from a step in $WEB_GUIDE_PATH" }
    val quote = block.indexOf('"', at + key.length + 1)
    check(quote >= 0) { "'$key' has no string value in $WEB_GUIDE_PATH" }
    return tsStrings(block.substring(quote, block.length)).first()
}

/**
 * The string array `key` is assigned in `block`, unescaped.
 *
 * The extent of the array is found by scanning for the matching bracket WHILE TRACKING WHETHER THE
 * SCANNER IS INSIDE A STRING, because a bracket inside a caution would otherwise end the array early
 * and silently truncate the list — the kind of parser bug that does not fail loudly, it just compares
 * fewer things than it claims to.
 */
private fun tsArray(block: String, key: String): List<String> {
    val at = block.indexOf("$key: [")
    check(at >= 0) { "'$key' is missing from a step in $WEB_GUIDE_PATH" }
    val start = at + key.length + 3
    var index = start
    var depth = 1
    var inString = false
    while (index < block.length && depth > 0) {
        val here = block[index]
        when {
            inString && here == '\\' -> index++
            here == '"' -> inString = !inString
            !inString && here == '[' -> depth++
            !inString && here == ']' -> depth--
        }
        index++
    }
    check(depth == 0) { "'$key' has no closing bracket in $WEB_GUIDE_PATH" }
    return tsStrings(block.substring(start, index - 1))
}

/** Every double-quoted literal in `source`, with its backslash escapes resolved. */
private fun tsStrings(source: String): List<String> {
    val out = mutableListOf<String>()
    var index = 0
    while (index < source.length) {
        if (source[index] != '"') {
            index++
            continue
        }
        index++
        val text = StringBuilder()
        while (index < source.length && source[index] != '"') {
            if (source[index] == '\\' && index + 1 < source.length) {
                // The only escapes `steps.ts` uses inside these strings are an escaped double quote
                // and an escaped backslash; both resolve to the character that follows.
                text.append(source[index + 1])
                index += 2
            } else {
                text.append(source[index])
                index++
            }
        }
        index++
        out += text.toString()
    }
    return out
}

/**
 * `steps.ts`, read from wherever the test runner started.
 *
 * The CRLF normalisation matters on this repository specifically: the checkout on Windows can hand
 * back `\r\n`, and a caution that differed from the Kotlin literal only by a carriage return would
 * fail an assertion with two strings that print identically in the report.
 */
private fun webGuideSource(): String =
    repoFile(
        // The `..`-prefixed candidate is what lets this reach OUT of `android/` and into `frontend/`,
        // and it is tried at every level of the walk rather than at the top, because a Gradle test
        // worker's working directory is not something to depend on: it is `android/app` under a
        // plain `./gradlew` run and has been the module root, the project root and the daemon's own
        // directory under other runners.
        "../$WEB_GUIDE_PATH",
        WEB_GUIDE_PATH,
    ).readText(Charsets.UTF_8).replace("\r\n", "\n")

/**
 * A file of this repository, found by walking up from wherever the test runner started.
 *
 * MISSING IS A FAILURE, LOUDLY, AND NEVER A SKIP — which is the single most important line in this
 * file. A source-reading test that quietly passed when it could not find its subject would prove
 * nothing on the day somebody moves that subject, and that is the one day it is most needed: its
 * silence would be read as parity. The `AssertionError` names both candidates and the directory the
 * walk started from, so the report says what to fix rather than that something is missing.
 *
 * Both candidates are tried at EVERY level of the walk rather than one candidate all the way up and
 * then the other. The two differ by a `..`, so a walk that tried the bare path first would find
 * nothing until the repository root and then find it — which is the same answer, but only by luck of
 * this repository's shape; a sibling checkout with a `frontend/` one level higher would resolve to
 * the wrong tree.
 *
 * Nothing in this module shared a helper like this before — this is the first test here to read a
 * file at all — so it is written out rather than imported. If a second suite wants it, lift it to a
 * shared test file rather than copying it: two copies of a walk-up is how one of them later stops
 * checking the candidate that mattered.
 */
private fun repoFile(vararg relative: String): File {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
        for (path in relative) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
        }
        dir = dir.parentFile
    }
    throw AssertionError("none of ${relative.toList()} found from ${File(".").absolutePath}")
}
