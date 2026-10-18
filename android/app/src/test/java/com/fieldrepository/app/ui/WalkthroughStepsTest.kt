package com.fieldrepository.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE WALKTHROUGH'S CONTENT, HELD TO THE WEB'S JOURNEY AND TO ITS OWN RULES.
 *
 * Ported from the designer portal's suite of the same name and adapted to this app's content.
 *
 * ── THE FAILURES BEING GUARDED, ALL OF THEM INVISIBLE BY INSPECTION ──────────────────────────────
 *
 * 1. THE HANDSET FALLING BEHIND THE WEB. `every subject the web walkthrough teaches has a step here`
 *    reads `frontend/components/guide/steps.ts` at runtime, so the register has one copy and it is
 *    the web's.
 * 2. A NUMBER TYPED INTO A TITLE. Pinned by `no step title carries its own number`.
 * 3. A COUNT TYPED INTO A SENTENCE — the deck this replaced said "Ten steps" over a counter reading
 *    1/12. Pinned per deck by `the opening card counts the journey rather than claiming a number`.
 * 4. A STEP POINTING AT A DOOR THAT IS NO LONGER THERE. Pinned by `every step that offers a door
 *    names a menu row that exists`.
 * 5. A STEP GROWING BACK INTO A PARAGRAPH. The owner's rule for this walkthrough is one short title
 *    and one short line. Pinned by `every step is one short title and one short line`.
 * 6. THE FORM LABELS DRIFTING FROM THE WEB'S. Pinned by `every step the web teaches asks for exactly
 *    the fields the web lists`.
 *
 * ── THE PARITY HALF IS THE RESEARCHER'S DECK, THE RULES HALF IS EVERY DECK ───────────────────────
 *
 * `steps.ts` is the register for exactly one of this app's three decks, so the parity assertions read
 * [walkthroughJourney] by name. Every rule the list sets itself is a rule about a deck and not about
 * that deck, so those walk [WALKTHROUGH_DECKS] and a fourth deck is covered the day it is registered.
 */
class WalkthroughStepsTest {

    /**
     * The steps `frontend/components/guide/steps.ts` teaches, in its own order, read from the file.
     *
     * `GuideStep` objects are elements of one array literal, so their `id` sits at exactly four spaces
     * of indent and nothing else in that file does. The scan starts at the `GUIDE_STEPS` declaration
     * so a helper array above it can never contribute an id, and `the parser found the web's step
     * list` fails loudly if either half of that stops holding.
     */
    private val WEB_GUIDE_STEPS: List<WebGuideStep> by lazy { readWebGuideSteps() }
    private val WEB_GUIDE_IDS: List<String> by lazy { WEB_GUIDE_STEPS.map { it.id } }

    // ── Parity with the web ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the parser found the web's step list`() {
        // Without this, every parity assertion below is vacuously green the day `steps.ts` is
        // reformatted, renamed or moved. A floor and not the current count, so the web adding a
        // step is welcomed rather than resisted.
        assertTrue(
            "$WEB_GUIDE_PATH parsed to ${WEB_GUIDE_IDS.size} step ids — the anchor this file scans " +
                "for has stopped matching, so every parity assertion below is now checking nothing",
            WEB_GUIDE_IDS.size >= 8
        )
        assertEquals(
            "the web's guide has two steps with the same id, so it cannot be the join",
            WEB_GUIDE_IDS.size,
            WEB_GUIDE_IDS.toSet().size
        )
        assertTrue(
            "the parser read no fields at all, so the fields assertion below compares nothing",
            WEB_GUIDE_STEPS.any { it.fields.isNotEmpty() }
        )
    }

    @Test
    fun `every subject the web walkthrough teaches has a step here`() {
        val here = walkthroughJourney.map { it.id }.toSet()
        val missing = WEB_GUIDE_IDS.filterNot { it in here }
        assertTrue(
            "the web walkthrough teaches $missing and this handset does not. Add the step to " +
                "WalkthroughSteps.kt. This list is READ FROM the web's own file; if the id merely " +
                "CHANGED on one side, this reports it as missing rather than as renamed, so check the " +
                "web's spelling before adding anything.",
            missing.isEmpty()
        )
    }

    @Test
    fun `the web's steps appear here in the web's own order`() {
        // Filtered rather than compared whole, because this app teaches three subjects of its own —
        // search, no signal, and the account. The shared subjects must read in the same sequence:
        // two clients teaching the same subjects in different orders is worse than a missing step.
        val shared = walkthroughJourney.map { it.id }.filter { it in WEB_GUIDE_IDS }
        assertEquals(
            "the two clients teach the same subjects in different orders",
            WEB_GUIDE_IDS,
            shared
        )
    }

    @Test
    fun `every step the web teaches asks for exactly the fields the web lists`() {
        // The chips under "What the screen asks for" are the web's `GuideStep.fields`, carried in
        // `WALKTHROUGH_FIELDS`. They are form labels, not prose: a chip reading "Craft" where the form
        // says "Craft (required)" teaches a researcher that a required field is optional. Asserted
        // through `walkthroughFacets`, which is what the card actually draws from.
        for (web in WEB_GUIDE_STEPS) {
            val step = walkthroughJourney.first { it.id == web.id }
            assertEquals(
                "Step '${web.id}' lists different form fields from the web's.",
                handsetFieldsFor(web.id, web.fields),
                walkthroughFacets(step).fields,
            )
        }
    }

    @Test
    fun `every web-ahead entry still names a field the web lists`() {
        // A stale entry would quietly widen the parity check above; each one must still match.
        for ((id, swaps) in WEB_AHEAD_OF_HANDSET) {
            val web = WEB_GUIDE_STEPS.first { it.id == id }
            for (swap in swaps) {
                if (swap.web != null) {
                    assertTrue("'$id' no longer lists '${swap.web}'; drop the entry", swap.web in web.fields)
                }
            }
        }
    }

    /** One web label and the handset label it stands for; null on one side = only that side has it. */
    private data class FieldSwap(val web: String?, val handset: String?, val after: String? = null)

    /**
     * WEB CAPTURE (2026-10-17) shipped on the web first: the product starts from the artisan, a
     * process step may be unnamed, and a sitting is named by the server when left untitled. The
     * handset's forms still draw the earlier labels, so its register keeps them until those screens
     * follow. Remove an entry in the commit that brings the handset form level.
     */
    private val WEB_AHEAD_OF_HANDSET: Map<String, List<FieldSwap>> = mapOf(
        "product" to listOf(
            FieldSwap(web = "Artisan (required)", handset = null),
            FieldSwap(web = "Artisan not on record", handset = null),
            FieldSwap(web = null, handset = "Linked artisan (fills artisan + place)", after = "Craft name (required)"),
        ),
        "process" to listOf(FieldSwap(web = "Per step: Name of the step", handset = "Per step: Name of the step (required)")),
    )

    private fun handsetFieldsFor(id: String, webFields: List<String>): List<String> {
        val fields = webFields.toMutableList()
        for (swap in WEB_AHEAD_OF_HANDSET[id].orEmpty()) {
            when {
                swap.web != null && swap.handset != null -> fields[fields.indexOf(swap.web)] = swap.handset
                swap.web != null -> fields.remove(swap.web)
                swap.handset != null -> fields.add(fields.indexOf(swap.after) + 1, swap.handset)
            }
        }
        return fields
    }

    // ── The rules the list sets itself ───────────────────────────────────────────────────────────

    @Test
    fun `no two steps share an id`() {
        // ACROSS EVERY DECK AND NOT WITHIN ONE. `walkthroughFacets` looks a step's field list up by
        // id in one flat register, and the card has no idea which deck a step came from — so one id
        // in two decks would draw one deck's labels on the other deck's card.
        val ids = WALKTHROUGH_DECKS.flatMap { deck -> deck.steps.map { it.id } }
        assertEquals(
            "a duplicated id makes every id-based check answer for the wrong step, silently",
            ids.size,
            ids.toSet().size
        )
        assertTrue("an id is the join key and cannot be blank", ids.none { it.isBlank() })
    }

    @Test
    fun `no step title carries its own number`() {
        val numbered = Regex("""^\s*\d+\s*[.)]""")
        WALKTHROUGH_DECKS.flatMap { it.steps }.forEach { step ->
            assertFalse(
                "“${step.title}” has a step number typed into it; the position is derived instead.",
                numbered.containsMatchIn(step.title)
            )
        }
    }

    @Test
    fun `the opening card counts the journey rather than claiming a number`() {
        // The original defect, pinned from both ends and per deck: the sentence must contain the REAL
        // size, and it must not contain the size of the deck it is part of.
        WALKTHROUGH_DECKS.forEach { deck ->
            val intro = deck.steps.first()
            assertNull(
                "“${deck.id}”'s opening card is not one of its numbered steps",
                walkthroughStepNumber(deck, intro)
            )
            assertTrue(
                "“${deck.id}”'s opening card must state how many steps its journey has, truly",
                intro.body.contains("${deck.journey.size} steps")
            )
            assertFalse(
                "“${deck.id}”'s opening card must count the JOURNEY and not the deck",
                intro.body.contains("${deck.steps.size} steps")
            )
        }
    }

    @Test
    fun `every deck is a journey with one card at each end`() {
        WALKTHROUGH_DECKS.forEach { deck ->
            assertEquals(
                "“${deck.id}” is the opening card, the journey, and the closing checklist",
                deck.journey.size + 2,
                deck.steps.size
            )
            assertEquals(
                "“${deck.id}”'s journey must reach the screen in its own order, unedited",
                deck.journey,
                deck.steps.subList(1, deck.steps.size - 1)
            )
            // Three is the smallest shape `WalkthroughJourney` draws; below it the window is blank.
            assertTrue(
                "“${deck.id}” has ${deck.steps.size} cards; the journey draws nothing under three",
                deck.steps.size >= 3
            )
        }
    }

    @Test
    fun `only the journey is numbered, and it is numbered from one`() {
        WALKTHROUGH_DECKS.forEach { deck ->
            assertNull(walkthroughStepNumber(deck, deck.steps.first()))
            assertNull(walkthroughStepNumber(deck, deck.steps.last()))
            deck.journey.forEachIndexed { index, step ->
                assertEquals(
                    "“${step.title}” must report itself as step ${index + 1} of “${deck.id}”",
                    index + 1,
                    walkthroughStepNumber(deck, step)
                )
            }
            // A deck numbers its own steps and nobody else's.
            WALKTHROUGH_DECKS.filter { it.id != deck.id }.forEach { other ->
                other.journey.forEach { step ->
                    assertNull(
                        "“${step.id}” belongs to “${other.id}” and “${deck.id}” gave it a number",
                        walkthroughStepNumber(deck, step)
                    )
                }
            }
        }
    }

    // ── The doors ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every step that offers a door names a menu row that exists`() {
        // The dialog borrows the row's own label for its "Open …" button. A destination dropped from
        // the menu degrades that button to "Open the screen this step teaches".
        WALKTHROUGH_DECKS.flatMap { it.steps }.forEach { step ->
            val destination = step.destination ?: return@forEach
            assertNotNull(
                "“${step.title}” opens $destination, which is no longer a row in FIELD_NAV_ITEMS.",
                FIELD_NAV_ITEMS.firstOrNull { it.destination == destination }
            )
        }
    }

    @Test
    fun `the two ends open nothing`() {
        WALKTHROUGH_DECKS.forEach { deck ->
            assertNull("“${deck.id}”'s opening card is not a feature", deck.steps.first().destination)
            assertNull("“${deck.id}”'s closing checklist is not a feature", deck.steps.last().destination)
        }
        assertTrue(
            "no step may open the walkthrough from inside the walkthrough",
            WALKTHROUGH_DECKS.flatMap { it.steps }.none { it.destination == NavDestination.WALKTHROUGH }
        )
    }

    // ── The shape of a step ──────────────────────────────────────────────────────────────────────

    @Test
    fun `every step is one short title and one short line`() {
        // THE OWNER'S RULE: concise, premium, no AI slop. The collapsed card is what a reader
        // scrolling the journey actually reads, so a step's body is ONE sentence, short enough to sit
        // on two lines of a 360dp handset, and its title is a feature and an action, not a phrase.
        //
        // The opening cards are allowed a second, equally short sentence — the count, and how to get
        // back here — and nothing more.
        val sentenceBreak = Regex("""[.;!?]\s+[A-Z]""")
        WALKTHROUGH_DECKS.forEach { deck ->
            deck.steps.forEachIndexed { index, step ->
                val isIntro = index == 0
                assertTrue(
                    "“${step.id}” has a ${step.title.length}-character title: “${step.title}”",
                    step.title.length <= 42
                )
                val ceiling = if (isIntro) 110 else 90
                assertTrue(
                    "“${step.id}” has a ${step.body.length}-character body; one short line is " +
                        "$ceiling at most: “${step.body}”",
                    step.body.length <= ceiling
                )
                val sentences = sentenceBreak.findAll(step.body).count() + 1
                assertTrue(
                    "“${step.id}” is $sentences sentences long: “${step.body}”",
                    sentences <= if (isIntro) 2 else 1
                )
                assertTrue("“${step.id}” must end as a sentence", step.body.trimEnd().endsWith("."))
            }
        }
    }

    @Test
    fun `every numbered step names a feature and the thing you do there`() {
        // The card splits a title at the middle dot into a heading and an action pill. A title that
        // lost its separator renders as a heading with no pill, which reads as a different kind of
        // card from its neighbours.
        WALKTHROUGH_DECKS.flatMap { it.journey }.forEach { step ->
            val parts = step.title.split(" · ")
            assertEquals("“${step.title}” must read “Feature · Action”", 2, parts.size)
            assertTrue("“${step.title}” has a blank half", parts.all { it.isNotBlank() })
        }
    }

    @Test
    fun `every step wears a glyph and says what it is`() {
        WALKTHROUGH_DECKS.flatMap { it.steps }.forEach { step ->
            assertNotNull("“${step.title}” has no icon to find its row by", step.icon)
            assertTrue("a step with no words is not a step", step.title.isNotBlank())
            assertTrue("“${step.title}” has no body", step.body.isNotBlank())
        }
    }
}

// ── Reading the web's own step list ─────────────────────────────────────────────────────────────

/** The one place this path is written. Named in failure messages so a move reports itself. */
private const val WEB_GUIDE_PATH = "frontend/components/guide/steps.ts"

/** One `GuideStep` as the web declares it, with the two fields this suite compares. */
private data class WebGuideStep(val id: String, val fields: List<String>)

/** The `GuideStep`s `steps.ts` declares, in its own order. See `WEB_GUIDE_STEPS` above. */
private fun readWebGuideSteps(): List<WebGuideStep> {
    val source = repoFile("../$WEB_GUIDE_PATH", WEB_GUIDE_PATH)
        .readText(Charsets.UTF_8).replace("\r\n", "\n")
    val start = source.indexOf("export const GUIDE_STEPS")
    check(start >= 0) { "GUIDE_STEPS is no longer declared in $WEB_GUIDE_PATH" }
    val body = source.substring(start)
    val anchors = Regex("""^ {4}id: "([^"]+)"""", RegexOption.MULTILINE).findAll(body).toList()
    return anchors.mapIndexed { index, match ->
        val to = anchors.getOrNull(index + 1)?.range?.first ?: body.length
        val block = body.substring(match.range.first, to)
        WebGuideStep(id = match.groupValues[1], fields = tsArray(block, "fields"))
    }
}

/**
 * The string array `key` is assigned in `block`, unescaped.
 *
 * The extent of the array is found by scanning for the matching bracket WHILE TRACKING WHETHER THE
 * SCANNER IS INSIDE A STRING, because a bracket inside a label would otherwise end the array early and
 * silently truncate the list.
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

// `repoFile` is `RepoSources.kt`, in this package.
