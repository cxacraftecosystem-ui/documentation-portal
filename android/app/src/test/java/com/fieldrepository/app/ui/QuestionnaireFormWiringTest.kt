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

class QuestionnaireFormWiringTest {

    private data class Wiring(val contractKey: String, val wireKey: String, val seed: String)

    private val fieldWiring = listOf(
        Wiring("title", "title", "editing?.title"),
        Wiring("place", "place", "editing?.place"),
        Wiring("language", "language", "editing?.language"),
        Wiring("workshop", "workshopId", "editing?.workshopId"),
        Wiring("questionnaireId", "questionnaireId", "editing?.questionnaireId"),
        Wiring("status", "status", "editing?.status"),
        Wiring("artisans", "artisanIds", "editing?.artisans"),
        Wiring("notes", "notes", "editing?.notes"),
    )

    private fun contractKeys(): List<String> {
        val contract = Json.parseToJsonElement(
            repoSource(
                "shared/questionnaire-form-contract.json",
                "../shared/questionnaire-form-contract.json",
            )
        ).jsonObject
        return contract["fields"]!!.jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content }
    }

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

    @Test
    fun `every contract field survives serialisation on both spellings`() {
        val filled = QuestionnaireInterviewCreateRequest(
            title = "Interview with Ram Kumar",
            place = "Bagru",
            language = "Kutchi, and some Gujarati",
            notes = "Two sittings; the second ran long.",
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
        status = "APPROVED",
        craftId = craft?.id,
        craft = craft,
    )

    private companion object {
        const val FORM_ANCHOR = "private fun QuestionnaireForm("
        const val CREATE_REQUEST = "QuestionnaireInterviewCreateRequest("
    }
}
