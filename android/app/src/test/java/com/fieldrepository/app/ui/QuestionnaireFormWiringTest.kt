package com.fieldrepository.app.ui

import com.fieldrepository.app.artisanSelectOptions
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.CraftDto
import com.fieldrepository.app.data.QuestionnaireInterviewCreateRequest
import com.fieldrepository.app.data.QuestionnaireResponseRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE QUESTIONNAIRE CAPTURE FORM IS WIRED END TO END — every box the contract declares reaches the
 * request body, on both save paths, and comes back on an edit.
 *
 * ── WHAT THIS CATCHES THAT THE CONTRACT SUITE DOES NOT ──────────────────────────────────────────
 *
 * `backend/tests/test_questionnaire_form_contract.py` measures which controls the form DRAWS and
 * what they are called. That is the half the owner reported. It is silent about the other half, and
 * the other half is worse: a control that renders and does not save discards what a researcher
 * typed, in front of an artisan, with a green "Saved" afterwards. Nobody finds out until somebody
 * reads the record back, by which time the sitting is over and the artisan has gone home.
 *
 * THAT IS NOT HYPOTHETICAL HERE. `questionnaireId` sat in `QuestionnaireInterviewCreateRequest`,
 * in `FieldRepository`, and in `FieldRepositoryApi` for a fortnight before this commit, fully
 * plumbed and never filled in by the handset, because nothing joined the DTO's fields to the form's
 * call sites. The form drew four boxes and the payload carried eight fields; both halves compiled.
 *
 * ── WHY IT READS SOURCE RATHER THAN RENDERING THE FORM ──────────────────────────────────────────
 *
 * Compose is deliberately not on this module's unit-test classpath (see `app/build.gradle.kts`), so
 * there is no renderer to drive and no `QuestionnaireInterviewCreateRequest` to capture from a fake
 * `onSubmit`. The alternative considered was an instrumented androidTest that fills the form on a
 * device and asserts on the body: it is the stronger assertion and it is the one nobody runs before
 * a release, which makes it weaker in the only way that matters. What is scanned here is argument
 * syntax — `key =` inside a named argument list — which is exactly the shape that goes wrong when a
 * field is added to the UI and forgotten in the payload.
 *
 * The register is the CONTRACT and not a list retyped here: [fieldWiring] must cover every field the
 * contract declares and nothing else, so a ninth field added to the form declares itself in this
 * file or fails in it.
 */
class QuestionnaireFormWiringTest {

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  The register: a contract field, the wire key it becomes, and how an edit loads it back
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * One row per field of `shared/questionnaire-form-contract.json`.
     *
     * THE CONTRACT KEY AND THE WIRE KEY ARE DIFFERENT NAMES FOR THREE OF THE EIGHT, and that is the
     * whole reason this table is written out rather than derived. The contract names the field as
     * the FORM has it — `workshop`, `artisans` — and the request body names it as the API has it —
     * `workshopId`, `artisanIds`. A test that assumed the two agreed would pass over a form that had
     * stopped sending the workshop, because it would be looking for the wrong word.
     *
     * [seed] is the expression that loads the field back when an existing interview is opened. It is
     * in this table and not in a second one because the three facts belong together: a field that is
     * drawn, saved and NOT seeded is a form that silently blanks what the record already held the
     * moment somebody opens it to fix a typo — which is the same class of defect as a control that
     * does not save, arriving from the other direction.
     */
    private data class Wiring(val contractKey: String, val wireKey: String, val seed: String)

    private val fieldWiring = listOf(
        Wiring("title", "title", "editing?.title"),
        Wiring("place", "place", "editing?.place"),
        Wiring("language", "language", "editing?.language"),
        // The workshop is seeded through the picker rather than into a local `var`: an edit form must
        // never move off the workshop the record was saved at, which is a rule `rememberWorkshopPicker`
        // owns (it is also what makes `settled` true from construction there).
        Wiring("workshop", "workshopId", "editing?.workshopId"),
        Wiring("questionnaireId", "questionnaireId", "editing?.questionnaireId"),
        Wiring("status", "status", "editing?.status"),
        Wiring("artisans", "artisanIds", "editing?.artisans"),
        Wiring("notes", "notes", "editing?.notes"),
    )

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  Reading the two subjects
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    private fun contractKeys(): List<String> {
        val contract = Json.parseToJsonElement(
            repoSource(
                "shared/questionnaire-form-contract.json",
                "../shared/questionnaire-form-contract.json",
            )
        ).jsonObject
        return contract["fields"]!!.jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content }
    }

    /**
     * `QuestionnaireForm`'s body, comment-stripped.
     *
     * BRACE-BALANCED FROM THE FUNCTION'S OWN `{`, and not "up to the next `@Composable`". The obvious
     * slice is wrong quietly: the next top-level composable after this one is hundreds of lines past
     * the end of it, so that slice swallows the next screen whole and would report ITS controls as
     * this form's. The enforcing test on the backend side records having tried exactly that.
     */
    private fun formBody(): String {
        val source = kotlinWithoutComments(
            repoSource(
                "app/src/main/java/com/fieldrepository/app/MainActivity.kt",
                "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt",
            )
        )
        val at = source.indexOf(FORM_ANCHOR)
        assertTrue(
            "$FORM_ANCHOR is no longer in MainActivity.kt. That is how this file finds the handset's " +
                "capture form; if it has been renamed or moved, this parser moves with it.",
            at >= 0,
        )
        val open = source.indexOf('{', source.indexOf(')', at))
        return balancedFrom(source, open, '{', '}')
    }

    /** Every `QuestionnaireInterviewCreateRequest(...)` argument list the form builds, in order. */
    private fun createRequestLiterals(): List<String> {
        val body = formBody()
        val found = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = body.indexOf(CREATE_REQUEST, from)
            if (at < 0) break
            val open = body.indexOf('(', at)
            found.add(balancedFrom(body, open, '(', ')'))
            from = open
        }
        return found
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  1. The register covers the contract, and only the contract
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * The guard that keeps every assertion below from going quietly out of date.
     *
     * Without it, a ninth field added to the contract and to the form would simply not be checked
     * here: the loops below iterate [fieldWiring], so a field missing from that list is a field this
     * suite is silent about — and silence reads as parity. Held BOTH ways, because a row left behind
     * for a field that has been removed is a different lie and just as durable.
     */
    @Test
    fun `this suite knows about exactly the fields the contract declares`() {
        assertEquals(
            "shared/questionnaire-form-contract.json and this file's `fieldWiring` table disagree " +
                "about which fields this form has. Add the row (with the wire key the request body " +
                "uses and the expression an edit seeds it from) or remove it — a field with no row " +
                "is a field nothing below checks.",
            contractKeys().sorted(),
            fieldWiring.map { it.contractKey }.sorted(),
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  2. Every field reaches the request body — on BOTH save paths
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * TWO LITERALS AND NOT ONE, and the second is the one that gets forgotten.
     *
     * The form builds `QuestionnaireInterviewCreateRequest` twice: once for the offline outbox and
     * once for the live POST. They are written out separately because the offline path serialises the
     * object into local storage and the online path hands it to Retrofit — and separate means they
     * can diverge, silently, in the direction that matters most. An interview captured offline is the
     * one record that cannot be reconstructed later: the artisan has gone home, and whatever the
     * outbox failed to store is simply not in the archive.
     *
     * The count is asserted too. If a third construction appears this test must be told about it
     * rather than checking two out of three and reporting parity.
     */
    @Test
    fun `every contract field reaches the create request body on both save paths`() {
        val literals = createRequestLiterals()
        assertEquals(
            "the questionnaire form builds $CREATE_REQUEST a different number of times than this " +
                "test expects. Two is the offline outbox and the live POST; a third path must be " +
                "named here so it is checked too.",
            2,
            literals.size,
        )
        val missing = mutableListOf<String>()
        literals.forEachIndexed { index, literal ->
            val path = if (index == 0) "the offline outbox payload" else "the live POST payload"
            fieldWiring.forEach { field ->
                if (!Regex("\\b${field.wireKey}\\s*=").containsMatchIn(literal)) {
                    missing.add("  $path does not set `${field.wireKey}` (contract field \"${field.contractKey}\")")
                }
            }
        }
        assertEquals(
            "a box on the questionnaire capture form does not reach the request body:\n" +
                missing.joinToString("\n") +
                "\n\n  A control that renders and does not save is worse than an absent one: it " +
                "discards what the researcher typed and says \"Saved\". Fill it in at the call site " +
                "in MainActivity.kt — and check BOTH payloads, because the offline one is the copy " +
                "of an interview that cannot be taken again.",
            emptyList<String>(),
            missing,
        )
    }

    /**
     * THE OTHER END OF THE SAME WIRE: the field survives serialisation.
     *
     * A call site that fills a field and a DTO that drops it on the way out are indistinguishable
     * from the form's side. Both spellings this app actually uses are checked, because they differ in
     * a way that decides the answer: the outbox sets `encodeDefaults = true` (a queued payload has to
     * be complete — it is replayed days later by code that cannot ask the form anything), while the
     * wire converter leaves defaults out and drops nulls. A field that only survives one of the two
     * is a field that saves online and vanishes offline, or the reverse.
     */
    @Test
    fun `every contract field survives serialisation on both spellings`() {
        val filled = QuestionnaireInterviewCreateRequest(
            title = "Interview with Ram Kumar",
            place = "Bagru",
            language = "Kutchi, and some Gujarati",
            notes = "Two sittings; the second ran long.",
            // Non-default on purpose: `encodeDefaults = false` omits a value equal to its default, so
            // a DTO default of "PENDING" here would make this assertion pass without proving anything.
            status = "APPROVED",
            artisanIds = listOf("artisan-1", "artisan-2"),
            responses = listOf(QuestionnaireResponseRequest(questionId = "q1", answerText = "yes")),
            workshopId = "workshop-3",
            questionnaireId = "instrument-revised",
            recordedAt = "2026-09-16T09:30:00Z",
        )
        val outbox = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val wire = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }
        listOf("the outbox spelling" to outbox, "the wire spelling" to wire).forEach { (name, json) ->
            val encoded = json.encodeToString(filled)
            fieldWiring.forEach { field ->
                assertTrue(
                    "$name of QuestionnaireInterviewCreateRequest does not carry \"${field.wireKey}\" " +
                        "(contract field \"${field.contractKey}\"). The form fills it and the JSON " +
                        "does not have it, so it never reaches the server: $encoded",
                    encoded.contains("\"${field.wireKey}\""),
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  3. An edit opens on what the record already holds
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Every field is SEEDED from the interview being edited.
     *
     * The failure this guards is quiet and destructive in the same breath: a field that saves but is
     * not seeded opens blank over a record that has a value, and the next save writes the blank
     * through. A researcher who opened the interview to correct its title erases its language and its
     * notes, having touched neither, and the form said nothing.
     */
    @Test
    fun `the edit path seeds every field the create path sends`() {
        val body = formBody()
        val missing = fieldWiring.filterNot { body.contains(it.seed) }.map {
            "  \"${it.contractKey}\" is not seeded from the record being edited (looked for `${it.seed}`)"
        }
        assertEquals(
            "the questionnaire form does not load every field back when an interview is opened:\n" +
                missing.joinToString("\n") +
                "\n\n  A field that saves but does not seed opens blank over a record that has a " +
                "value, and the next save writes the blank through.",
            emptyList<String>(),
            missing,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  4. The artisan picker sends the set it displays
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * WHAT THE SHEET SHOWS AND WHAT THE PAYLOAD CARRIES ARE THE SAME IDS.
     *
     * [artisanSelectOptions] is the whole join between the two: its `value` is what
     * [SearchableMultiSelectField] hands back in `onSelectedChange`, what the form stores in
     * `selectedArtisans`, and what the payload sends as `artisanIds`. Put a display string in that
     * slot — a name, a "name · place" — and the control looks completely normal, the chips read
     * correctly, and every interview files against nobody at all.
     *
     * ORDER IS ASSERTED TOO, and it is not decoration. The roster arrives in the server's own order
     * and neither the option builder nor the scoped-roster helper re-sorts it; a picker that
     * re-ordered would change which artisan is element 0 of a selection, and element 0 is what the
     * carried context and the browser's `primaryInterviewArtisanId` both read.
     */
    @Test
    fun `the artisan picker offers one row per artisan, keyed by artisan id, in roster order`() {
        val roster = listOf(
            artisan("a1", "Ram Kumar", "Bagru", CraftDto(id = "c1", name = "Block printing")),
            artisan("a2", "Sita Devi", "Sanganer", CraftDto(id = "c2", name = "Blue pottery")),
            artisan("a3", "Mohan Lal", "Jaipur", null),
        )
        val options = artisanSelectOptions(roster)

        assertEquals(
            "the picker must offer exactly the roster it was handed — an option dropped here is an " +
                "artisan a researcher cannot file an interview against",
            roster.map { it.id },
            options.map { it.value },
        )
        assertTrue(
            "every row must name the artisan and where they work: ${options.map { it.label }}",
            options.map { it.label } == listOf(
                "Ram Kumar · Bagru",
                "Sita Devi · Sanganer",
                "Mohan Lal · Jaipur",
            ),
        )
        assertEquals(
            "the craft goes in the hint, where the sheet searches it — and an artisan with no craft " +
                "on file gets the browser's own word for the blank rather than an empty second line",
            listOf("Block printing", "Blue pottery", "No craft"),
            options.map { it.hint },
        )
    }

    /**
     * The state the control is bound to IS the state the payload reads.
     *
     * The assertion above proves the option rows carry artisan ids; this one proves the form does not
     * then send some OTHER collection. Together they are "the picker sends the set it displays": the
     * sheet writes `selectedArtisans`, both payloads read `selectedArtisans`, and nothing sits in
     * between filtering or re-deriving it.
     */
    @Test
    fun `the artisan control and the payload read one selection`() {
        val body = formBody()
        val control = body.indexOf("SearchableMultiSelectField(")
        assertTrue("the capture form no longer draws SearchableMultiSelectField", control >= 0)
        val args = balancedFrom(body, body.indexOf('(', control), '(', ')')
        assertTrue(
            "the artisan control must be bound to `selectedArtisans`, which is what the payload " +
                "sends. A control bound to anything else displays one set and saves another: $args",
            Regex("\\bselected\\s*=\\s*selectedArtisans\\b").containsMatchIn(args),
        )
        assertTrue(
            "the artisan control must be the one labelled \"Artisans interviewed\" — the web's word, " +
                "and the one both walkthrough registers and the printed guide use",
            args.contains("label = \"Artisans interviewed\""),
        )
        createRequestLiterals().forEach { literal ->
            assertTrue(
                "a create payload sends something other than the displayed selection as `artisanIds`",
                Regex("artisanIds\\s*=\\s*selectedArtisans\\.toList\\(\\)").containsMatchIn(literal),
            )
        }
    }

    /**
     * NO WALL OF CHECKBOXES, ON THE ONE SCREEN THE OWNER NAMED.
     *
     * A duplicate of the pin in `backend/tests/test_questionnaire_form_contract.py`, deliberately,
     * and the duplication is the point: that suite runs on the backend's Python and this one runs
     * where an Android change is made. A regression that reintroduces the wall — most likely beside
     * the dropdown while half-landing something else, rather than instead of it — should turn the
     * suite red for the person making it, not for whoever next runs pytest.
     *
     * BOTH FORBIDDEN CONTROLS, because `CheckboxMultiSelectField` draws the same wall over a generic
     * option list and would satisfy any check that only named the artisan-specific one.
     */
    @Test
    fun `the capture form draws no checkbox wall`() {
        val body = formBody()
        listOf("ArtisanMultiSelectField(", "CheckboxMultiSelectField(").forEach { forbidden ->
            assertTrue(
                "$forbidden is back inside the questionnaire capture form. It paints a Column of " +
                    "checkboxes — every artisan at the workshop, one row each, straight into a form " +
                    "that is already long, with no summary line and no room for a Select-all row. " +
                    "The owner asked for a searchable multi-select dropdown by name; use " +
                    "SearchableMultiSelectField from ui/SearchableSelect.kt and carry the " +
                    "four-sentence emptyMessage across with it.",
                !body.contains(forbidden),
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  5. The four sentences an empty roster can mean
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * THE EMPTY STATE SURVIVED THE WIDGET SWAP, all four arms of it.
     *
     * An empty artisan list means one of four different things — the request is in flight, the
     * request failed, nobody is recorded at this workshop, nobody is recorded at all — and only two
     * of them are facts about the repository. Printing "No artisans are recorded at this workshop
     * yet" off an empty array while the answer is still coming makes a claim before it exists, and
     * the researcher who believes it goes off to create a duplicate of an artisan who is already
     * there.
     *
     * ASSERTED AS THE FOUR SENTENCES AND NOT AS THE `when`, because the sentences are what the
     * browser prints from the same three facts (`page.tsx`'s `emptyLabel`) and a researcher comparing
     * a handset against a laptop must not have to wonder whether a difference in wording is a
     * difference in meaning. A rewrite that kept the branch structure and reworded the strings would
     * be exactly that difference.
     */
    @Test
    fun `the artisan picker still says which kind of empty it is`() {
        val body = formBody()
        listOf(
            "This workshop's artisan list could not be loaded",
            "Loading artisans…",
            "No artisans are recorded at this workshop yet",
            "No artisans recorded yet",
        ).forEach { sentence ->
            assertTrue(
                "the artisan picker no longer says \"$sentence\". An empty roster means one of four " +
                    "different things and only two of them are about the repository; the browser " +
                    "prints these same four sentences off the same three facts.",
                body.contains("\"$sentence\""),
            )
        }
    }

    private fun artisan(id: String, name: String, place: String, craft: CraftDto?) = ArtisanDto(
        id = id,
        name = name,
        place = place,
        // Required by the DTO and irrelevant here: the picker offers every artisan the scoped roster
        // returned, and filtering by review status is the SERVER's job on that request.
        status = "APPROVED",
        craftId = craft?.id,
        craft = craft,
    )

    private companion object {
        const val FORM_ANCHOR = "private fun QuestionnaireForm("
        const val CREATE_REQUEST = "QuestionnaireInterviewCreateRequest("
    }
}
