package com.fieldrepository.app.ui

import com.fieldrepository.app.ui.richtext.Align
import com.fieldrepository.app.ui.richtext.BlockKind
import com.fieldrepository.app.ui.richtext.Mark
import com.fieldrepository.app.ui.richtext.RichBlock
import com.fieldrepository.app.ui.richtext.RichDoc
import com.fieldrepository.app.ui.richtext.RichSpan
import com.fieldrepository.app.ui.richtext.richTextOpsSelfCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The record forms' prose controls, checked where they can be checked: on a plain JVM, with no
 * emulator, no `SpeechRecognizer` and no composition.
 *
 * ── WHAT THESE TESTS ARE FOR, AND IT IS NOT COVERAGE ──────────────────────────────────────────
 *
 * Three of the decisions in `RecordProseText.kt` fail SILENTLY when they are wrong, and none of the
 * three shows up as a crash, a red screen or a failing build:
 *
 *  1. **A mark written in the browser, silently dropped by the phone.** The alternative storage rule
 *     — flatten always — is one line shorter, reads as tidier, and loses a researcher's formatting
 *     the first time somebody corrects a typo on a handset. Nothing anywhere reports it.
 *  2. **Braces in an exported spreadsheet.** Encoding a document that did not need encoding puts
 *     `{"blocks":[…` into a CSV cell, a workbook and a reviewer's edit box. Also silent.
 *  3. **A refusal sentence that names somebody else's screen.** The ladder's copy is ported from an
 *     application built around design workshops; a sentence that survives the port telling a
 *     researcher to "record consent on the workshop screen" costs the feature permanently, because
 *     they stop tapping the microphone.
 *
 * Each one is pinned below by an assertion that FAILS on the plausible wrong implementation, not
 * merely on a broken one. Where a test would pass against both, it is not here.
 */
class RecordProseTest {

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  THE PORTED EDITING LIBRARY
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * The whole of `RichTextOps` against its own oracle.
     *
     * `richTextOpsSelfCheck` is the ported library's self-check: every ladder in it — span merging,
     * mark toggling, split, merge, outdent, the input rules, the degenerate documents — exercised
     * and returned as a list of the checks that failed. It came with the port and running it here is
     * what makes the port a port rather than three thousand lines nobody has executed: a copy that
     * lost a line to a bad paste, or a Kotlin/JVM difference the sibling never hit, shows up as a
     * named failure instead of as a caret that jumps on the second keystroke of a field visit.
     */
    @Test
    fun `the ported rich text operations pass their own self-check`() {
        assertEquals(emptyList<String>(), richTextOpsSelfCheck())
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  STORAGE — WHAT LANDS IN A `String?` COLUMN
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    private fun paragraph(text: String, vararg marks: Mark): RichBlock =
        RichBlock(spans = listOf(RichSpan(text, marks.toSet())))

    /**
     * The overwhelmingly common case: somebody typed or dictated, and formatted nothing.
     *
     * THE COLUMN MUST BE BYTE-IDENTICAL to what it would have held before this feature existed. This
     * is the assertion that lets the editor ship without a coordinated release: search, the CSV
     * exports, the `/data/report` workbook, `details.txt` and both review renderers keep reading
     * exactly what they read today. It fails against the obvious wrong implementation — encode the
     * document unconditionally — which is the one somebody will reach for when they notice that half
     * of `recordStoredFromDoc` looks redundant.
     */
    @Test
    fun `an unformatted answer is stored as the prose it is`() {
        val typed = "The warp is sized with rice paste.\nIt dries for two days before the loom."
        val stored = recordStoredFromDoc(recordDocFromStored(typed))
        assertEquals(typed, stored)
    }

    /** A blank box means "not filled in", and that is `null` rather than `""` — the columns are nullable. */
    @Test
    fun `an empty document clears the column rather than emptying it`() {
        assertEquals(null, recordStoredFromDoc(recordDocFromStored("")))
        assertEquals(null, recordStoredFromDoc(recordDocFromStored("   \n  ")))
        assertEquals(null, recordStoredFromDoc(RichDoc(listOf(paragraph("")))))
    }

    /**
     * A mark makes the value a document, and the document survives being reopened.
     *
     * BITES AGAINST FLATTEN-ALWAYS, which is what the sibling repository's Android does and what the
     * first draft of this port did. Under that rule the second assertion fails: the value comes back
     * as "the warp is sized", the bold is gone, and nobody is told. In THIS repository the browser
     * writes documents into these same columns today, so flatten-always would mean a researcher who
     * formats in the browser and then corrects a word on the phone loses their formatting to the
     * phone.
     */
    @Test
    fun `a formatted answer is stored as a document and reopens with its marks`() {
        val bolded = RichDoc(listOf(paragraph("the warp is sized", Mark.BOLD)))
        val stored = recordStoredFromDoc(bolded)
        assertTrue("expected a serialised document, got: $stored", stored!!.startsWith("{\"blocks\""))

        val reopened = recordDocFromStored(stored)
        assertEquals(setOf(Mark.BOLD), reopened.blocks.single().spans.single().marks)
        assertEquals("the warp is sized", reopened.blocks.single().text)
    }

    /**
     * Every non-prose feature counts, not merely the marks.
     *
     * `isPlainProse` is a whitelist — "a left-aligned unmarked paragraph at level 0 with no media and
     * no table rows" — precisely so that a feature added to the model later defaults to being stored
     * as a document rather than being quietly flattened away. Each case below fails if somebody
     * loosens one clause of it, and each is a real thing the browser's toolbar can produce.
     */
    @Test
    fun `lists headings quotes alignment and tables all force a document`() {
        val cases = mapOf(
            "bullet" to RichDoc(listOf(RichBlock(kind = BlockKind.BULLET_ITEM, spans = listOf(RichSpan("cotton"))))),
            "ordered" to RichDoc(listOf(RichBlock(kind = BlockKind.ORDERED_ITEM, spans = listOf(RichSpan("wash"))))),
            "heading" to RichDoc(listOf(RichBlock(kind = BlockKind.HEADING, level = 2, spans = listOf(RichSpan("Dyeing"))))),
            "quote" to RichDoc(listOf(RichBlock(kind = BlockKind.QUOTE, spans = listOf(RichSpan("as told to us"))))),
            "centred" to RichDoc(listOf(RichBlock(align = Align.CENTER, spans = listOf(RichSpan("Bhuj"))))),
            "table" to RichDoc(
                listOf(
                    RichBlock(
                        kind = BlockKind.TABLE,
                        rows = listOf(listOf(listOf(RichSpan("warp")), listOf(RichSpan("cotton")))),
                    )
                )
            ),
        )
        for ((name, doc) in cases) {
            val stored = recordStoredFromDoc(doc)
            assertTrue("$name should have been stored as a document, got: $stored", stored!!.startsWith("{"))
        }
    }

    /**
     * Two saves, not one. **A single round trip looks fine in every wrong implementation.**
     *
     * The defect this catches is accumulation: a bullet flattened to "• cotton" and re-read by a
     * marker-parsing reader gains a glyph on the next save ("• • cotton"), and a document re-encoded
     * from a re-parsed document drifts in key order or in span splitting. Saving twice and comparing
     * the two stored strings is the cheapest way to catch either, and it is how the sibling's own
     * test found its list-marker bug.
     */
    @Test
    fun `saving twice changes nothing, formatted or not`() {
        val values = listOf(
            "plain prose in a notes box",
            "two\nlines of it",
            recordStoredFromDoc(RichDoc(listOf(RichBlock(kind = BlockKind.BULLET_ITEM, spans = listOf(RichSpan("cotton"))))))!!,
            recordStoredFromDoc(RichDoc(listOf(paragraph("bold", Mark.BOLD))))!!,
        )
        for (value in values) {
            val once = recordStoredFromDoc(recordDocFromStored(value))
            val twice = recordStoredFromDoc(recordDocFromStored(once.orEmpty()))
            assertEquals("unstable round trip for: $value", once, twice)
        }
    }

    /**
     * Typed text that merely LOOKS like a list stays exactly what it is.
     *
     * BITES AGAINST PORTING THE SIBLING'S READER VERBATIM. Its Android flattens documents into the
     * column, so its reader parses "• " and "1. " back into list blocks — correct there, wrong here.
     * Under this repository's rule a list is stored as a document, so a marker in the column is
     * somebody's typing; re-reading it as a list would turn a plain answer into a formatted one on
     * open, and then store it back as a document (because a list is not plain prose) — a value the
     * researcher never asked for, in a column that was fine.
     */
    @Test
    fun `typed list markers stay prose and stay byte-identical`() {
        val typed = "1. wash in cold water\n2. dry in shade"
        val doc = recordDocFromStored(typed)
        assertTrue(doc.blocks.all { it.kind == BlockKind.PARAGRAPH })
        assertEquals(typed, recordStoredFromDoc(doc))
    }

    /**
     * The shape test is conservative in the direction that matters.
     *
     * A remark that begins with a brace is prose; a value that is a `{"blocks": …}` object is a
     * document. Both halves are load-bearing: the first stops `Json.parseToJsonElement` reinterpreting
     * somebody's typing, and the second is the only reason a document written in the browser is not
     * shown to a researcher as raw braces. The pairing matches `decodeStoredRichText`'s
     * `startsWith("{") && endsWith("}")` in `frontend/components/richtext/storedRichText.ts`.
     */
    @Test
    fun `only a blocks object is read as a document`() {
        assertFalse(looksLikeRichDocument("{note} the warp is sized"))
        assertFalse(looksLikeRichDocument("{\"note\": \"blocks are mentioned here\"}"))
        assertFalse(looksLikeRichDocument("true"))
        assertTrue(looksLikeRichDocument("{\"blocks\":[]}"))

        // And a value that is prose beginning with a brace survives a save unchanged.
        val prose = "{note} the warp is sized"
        assertEquals(prose, recordStoredFromDoc(recordDocFromStored(prose)))
    }

    /**
     * A truncated document is shown, not swallowed.
     *
     * The honest reading of `{"blocks": [{"kind"` — a value cut off by something upstream — is the
     * characters that are actually in the record. An empty box would invite the researcher to
     * overwrite what is left of it with nothing, which is the one outcome from which there is no
     * recovery.
     */
    @Test
    fun `a corrupt document is read as the text it is rather than as nothing`() {
        val truncated = "{\"blocks\": [{\"kind\": \"PARA"
        val doc = recordDocFromStored(truncated)
        assertTrue(doc.blocks.isNotEmpty())
        assertTrue(doc.blocks.first().text.contains("blocks"))
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  DICTATION — THE LADDER
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * **THE RUNG THAT MUST NOT EXIST.**
     *
     * The one assertion in this file that is about a thing NOT being there. The sibling repository's
     * ladder has a server rung, its record forms suppress it with a filter, and its own comments
     * explain that a weakened filter would silently start uploading an artisan's voice from a screen
     * where nobody was asked. Here the rung is not expressible at all — and this test is what makes
     * adding one a red build on a laptop rather than a discovery in a courtyard.
     */
    @Test
    fun `there are exactly two rungs and neither of them is a server`() {
        assertEquals(
            listOf(RecordDictationRung.ON_DEVICE_PACK, RecordDictationRung.NETWORK_RECOGNISER),
            RecordDictationRung.values().toList()
        )
    }

    private fun conditions(
        onDeviceEngine: Boolean = true,
        networkRecogniser: Boolean = true,
        online: Boolean = true,
        deviceRefusedLanguage: Boolean = false,
        label: String = "ଓଡ଼ିଆ — Odia",
    ) = RecordDictationConditions(
        languageLabel = label,
        onDeviceEngine = onDeviceEngine,
        networkRecogniser = networkRecogniser,
        online = online,
        deviceRefusedLanguage = deviceRefusedLanguage,
    )

    /**
     * The offline engine is tried first, and it is tried with no connection.
     *
     * The ordering is the whole argument of the ladder: free, offline and provably local before a
     * rung that needs signal and involves a third party. If somebody reverses it the first assertion
     * fails — and the symptom in the field would be a phone with a downloaded Odia pack sitting in a
     * courtyard timing out against a network recogniser it cannot reach.
     */
    @Test
    fun `the offline engine is walked first and works with no signal`() {
        assertEquals(
            listOf(RecordDictationRung.ON_DEVICE_PACK, RecordDictationRung.NETWORK_RECOGNISER),
            recordDictationRungs(conditions())
        )
        assertEquals(
            listOf(RecordDictationRung.ON_DEVICE_PACK),
            recordDictationRungs(conditions(online = false))
        )
    }

    /** A language this handset's offline engine has already refused is not offered to it again. */
    @Test
    fun `a measured refusal retires the offline rung for that language`() {
        assertEquals(
            listOf(RecordDictationRung.NETWORK_RECOGNISER),
            recordDictationRungs(conditions(deviceRefusedLanguage = true))
        )
        assertEquals(
            emptyList<RecordDictationRung>(),
            recordDictationRungs(conditions(deviceRefusedLanguage = true, online = false))
        )
    }

    /** A handset with no speech service at all has no ladder, and is told so before it waits. */
    @Test
    fun `a phone with no recogniser has nothing to walk`() {
        assertEquals(
            emptyList<RecordDictationRung>(),
            recordDictationRungs(conditions(onDeviceEngine = false, networkRecogniser = false))
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  DICTATION — THE COPY
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    private fun everyCondition(): List<RecordDictationConditions> = buildList {
        for (onDevice in listOf(true, false)) {
            for (network in listOf(true, false)) {
                for (online in listOf(true, false)) {
                    for (refused in listOf(true, false)) {
                        add(conditions(onDevice, network, online, refused))
                    }
                }
            }
        }
    }

    /**
     * **No sentence this application produces may describe another application's screen.**
     *
     * Copy is exactly the thing that rots quietly through a port: it compiles, it renders, and it is
     * wrong only to the person reading it in a village. The ported ladder's sentences came from an
     * app whose dictation is gated on a design workshop's recorded consent, a per-designer allowance
     * and a server route, and it sends its reader to a settings screen this app does not have. Every
     * one of those words is checked for here, over all sixteen combinations, because "we rewrote the
     * copy" is not a thing a reader of the diff can verify and this is.
     */
    @Test
    fun `no refusal sentence names a workshop, a consent, an allowance or a settings screen`() {
        val forbidden = listOf(
            "workshop", "consent", "allowance", "administrator", "Settings ›", "server",
            "designer", "provider",
        )
        for (c in everyCondition()) {
            val sentence = recordDictationNothingLeftSentence(c)
            for (word in forbidden) {
                assertFalse(
                    "\"$word\" appears in: $sentence",
                    sentence.contains(word, ignoreCase = true)
                )
            }
        }
    }

    /**
     * Every arm says something, names the language, and ends a sentence.
     *
     * The empty string is the failure that matters here: an arm added later that falls through to
     * `""` produces a microphone that does nothing and says nothing, which is the exact failure the
     * whole "degrade out loud" rule exists to prevent — and it would pass every other test in this
     * file.
     */
    @Test
    fun `every refusal sentence is a sentence, and names the language`() {
        for (c in everyCondition()) {
            val sentence = recordDictationNothingLeftSentence(c)
            assertTrue("empty sentence for $c", sentence.isNotBlank())
            assertTrue("does not end a sentence: $sentence", sentence.trimEnd().endsWith("."))
            // The one exception is the handset with no recogniser at all, whose sentence is about the
            // phone rather than about a language — naming a language there would imply that another
            // one might work.
            val aboutThePhone = !c.networkRecogniser && !c.onDeviceEngine && c.online
            if (!aboutThePhone) {
                assertTrue("does not name the language: $sentence", sentence.contains(c.languageLabel))
            }
        }
    }

    /**
     * The two places that answer "this phone cannot dictate" answer identically.
     *
     * One is printed under a box that asked for a microphone and did not get one; the other is what a
     * tap produces. A researcher who reads one and then taps must not be told a second, different
     * thing about the same handset — that is how a person decides an app is guessing.
     */
    @Test
    fun `the withheld-microphone note and the exhausted ladder agree`() {
        assertEquals(
            RECORD_DICTATION_UNAVAILABLE,
            recordDictationNothingLeftSentence(
                conditions(onDeviceEngine = false, networkRecogniser = false, online = true)
            )
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  DICTATION — JOINING WHAT WAS SAID TO WHAT IS THERE
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * One space, only where one is missing.
     *
     * The recogniser stops and starts many times across a long answer, so this runs on nearly every
     * dictated field. Both failures are trivial to write and permanent once exported: "…in Bhuj.The
     * second" and a double space nobody proof-reads out of four hundred cells.
     */
    @Test
    fun `spoken text is appended with exactly one space`() {
        assertEquals("first second", appendSpokenToRecord("first", "second"))
        assertEquals("first second", appendSpokenToRecord("first ", "second"))
        assertEquals("first\nsecond", appendSpokenToRecord("first\n", "second"))
        assertEquals("second", appendSpokenToRecord("", "second"))
        assertEquals("first", appendSpokenToRecord("first", "   "))
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  THE LANGUAGES
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * The list this application's two clients share.
     *
     * The tags are asserted rather than counted, and the order with them, because the failure being
     * guarded against is a well-meaning edit: somebody adds the sibling application's nineteen
     * languages, or reorders them "sensibly", and the phone and the browser stop offering the same
     * set for the same forms. `DICTATION_LANGUAGES` in `frontend/components/dictation/
     * onDeviceSpeech.ts` is the other half of this pair; changing one means changing both, and this
     * assertion is where a reader is told so.
     */
    @Test
    fun `the dictation languages match the browser's list, in order`() {
        assertEquals(
            listOf(
                "en-IN", "hi-IN", "bn-IN", "mr-IN", "te-IN", "ta-IN",
                "gu-IN", "kn-IN", "ml-IN", "or-IN", "pa-IN",
            ),
            RECORD_DICTATION_LANGUAGES.map { it.tag }
        )
        assertEquals(
            RECORD_DICTATION_LANGUAGES.size,
            RECORD_DICTATION_LANGUAGES.map { it.tag }.toSet().size
        )
        // A stale or unknown tag is shown as itself rather than as a blank control.
        assertEquals("xx-XX", recordDictationLabel("xx-XX"))
    }
}
