package com.fieldrepository.app.ui

import com.fieldrepository.app.data.ArtisanCreateRequest
import com.fieldrepository.app.data.CraftCreateRequest
import com.fieldrepository.app.data.ProcessCreateRequest
import com.fieldrepository.app.data.ProcessStepRequest
import com.fieldrepository.app.data.ProductCreateRequest
import com.fieldrepository.app.data.ToolCreateRequest
import com.fieldrepository.app.data.WorkshopCreateRequest
import com.fieldrepository.app.ui.richtext.Align
import com.fieldrepository.app.ui.richtext.BlockKind
import com.fieldrepository.app.ui.richtext.Mark
import com.fieldrepository.app.ui.richtext.RichBlock
import com.fieldrepository.app.ui.richtext.RichDoc
import com.fieldrepository.app.ui.richtext.RichSpan
import com.fieldrepository.app.ui.richtext.richTextOpsSelfCheck
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordProseTest {

    @Test
    fun `the ported rich text operations pass their own self-check`() {
        assertEquals(emptyList<String>(), richTextOpsSelfCheck())
    }

    private fun paragraph(text: String, vararg marks: Mark): RichBlock =
        RichBlock(spans = listOf(RichSpan(text, marks.toSet())))

    @Test
    fun `an unformatted answer is stored as the prose it is`() {
        val typed = "The warp is sized with rice paste.\nIt dries for two days before the loom."
        val stored = recordStoredFromDoc(recordDocFromStored(typed))
        assertEquals(typed, stored)
    }

    @Test
    fun `an empty document clears the column rather than emptying it`() {
        assertEquals(null, recordStoredFromDoc(recordDocFromStored("")))
        assertEquals(null, recordStoredFromDoc(recordDocFromStored("   \n  ")))
        assertEquals(null, recordStoredFromDoc(RichDoc(listOf(paragraph("")))))
    }

    @Test
    fun `a formatted answer is stored as a document and reopens with its marks`() {
        val bolded = RichDoc(listOf(paragraph("the warp is sized", Mark.BOLD)))
        val stored = recordStoredFromDoc(bolded)
        assertTrue("expected a serialised document, got: $stored", stored!!.startsWith("{\"blocks\""))

        val reopened = recordDocFromStored(stored)
        assertEquals(setOf(Mark.BOLD), reopened.blocks.single().spans.single().marks)
        assertEquals("the warp is sized", reopened.blocks.single().text)
    }

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

    @Test
    fun `typed list markers stay prose and stay byte-identical`() {
        val typed = "1. wash in cold water\n2. dry in shade"
        val doc = recordDocFromStored(typed)
        assertTrue(doc.blocks.all { it.kind == BlockKind.PARAGRAPH })
        assertEquals(typed, recordStoredFromDoc(doc))
    }

    @Test
    fun `only a blocks object is read as a document`() {
        assertFalse(looksLikeRichDocument("{note} the warp is sized"))
        assertFalse(looksLikeRichDocument("{\"note\": \"blocks are mentioned here\"}"))
        assertFalse(looksLikeRichDocument("true"))
        assertTrue(looksLikeRichDocument("{\"blocks\":[]}"))

        val prose = "{note} the warp is sized"
        assertEquals(prose, recordStoredFromDoc(recordDocFromStored(prose)))
    }

    @Test
    fun `a corrupt document is read as the text it is rather than as nothing`() {
        val truncated = "{\"blocks\": [{\"kind\": \"PARA"
        val doc = recordDocFromStored(truncated)
        assertTrue(doc.blocks.isNotEmpty())
        assertTrue(doc.blocks.first().text.contains("blocks"))
    }

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

    @Test
    fun `a phone with no recogniser has nothing to walk`() {
        assertEquals(
            emptyList<RecordDictationRung>(),
            recordDictationRungs(conditions(onDeviceEngine = false, networkRecogniser = false))
        )
    }

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

    @Test
    fun `every refusal sentence is a sentence, and names the language`() {
        for (c in everyCondition()) {
            val sentence = recordDictationNothingLeftSentence(c)
            assertTrue("empty sentence for $c", sentence.isNotBlank())
            assertTrue("does not end a sentence: $sentence", sentence.trimEnd().endsWith("."))
            val aboutThePhone = !c.networkRecogniser && !c.onDeviceEngine && c.online
            if (!aboutThePhone) {
                assertTrue("does not name the language: $sentence", sentence.contains(c.languageLabel))
            }
        }
    }

    @Test
    fun `the withheld-microphone note and the exhausted ladder agree`() {
        assertEquals(
            RECORD_DICTATION_UNAVAILABLE,
            recordDictationNothingLeftSentence(
                conditions(onDeviceEngine = false, networkRecogniser = false, online = true)
            )
        )
    }

    @Test
    fun `spoken text is appended with exactly one space`() {
        assertEquals("first second", appendSpokenToRecord("first", "second"))
        assertEquals("first second", appendSpokenToRecord("first ", "second"))
        assertEquals("first\nsecond", appendSpokenToRecord("first\n", "second"))
        assertEquals("second", appendSpokenToRecord("", "second"))
        assertEquals("first", appendSpokenToRecord("first", "   "))
    }

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
        assertEquals("xx-XX", recordDictationLabel("xx-XX"))
    }

    private val RECORD_FORM_TOTALS: Map<RecordFormKind, Pair<Int, Int>> = mapOf(
        RecordFormKind.CRAFT to (3 to 7),
        RecordFormKind.ARTISAN to (5 to 18),
        RecordFormKind.WORKSHOP to (4 to 7),
        RecordFormKind.PRODUCT to (10 to 13),
        RecordFormKind.TOOL to (9 to 21),
        RecordFormKind.PROCESS to (3 to 8),
        RecordFormKind.MEDIA to (2 to 2),
        RecordFormKind.LOCATION to (1 to 12),
    )

    @Test
    fun `every record column on every form is classified, and none of them twice`() {
        for (kind in RecordFormKind.values()) {
            val dictated = RECORD_DICTATED[kind]
            val notDictated = RECORD_NOT_DICTATED[kind]
            assertTrue(
                "$kind has no entry in RECORD_DICTATED. A surface with no table answers false to " +
                    "every column, so the whole form would ship without a microphone and nothing " +
                    "would report it",
                dictated != null,
            )
            assertTrue(
                "$kind has no entry in RECORD_NOT_DICTATED. Even a surface where everything is " +
                    "dictated has to say so, because an absent map is indistinguishable from a map " +
                    "nobody has written yet",
                notDictated != null,
            )
            val both = dictated!!.intersect(notDictated!!.keys)
            assertEquals(
                "$kind.$both is listed as dictated AND as not dictated — `recordDictates` would " +
                    "answer yes and the written reason would say no, so the screen and its own " +
                    "explanation would disagree",
                emptySet<String>(),
                both,
            )
            val (expectedDictated, expectedNotDictated) = RECORD_FORM_TOTALS.getValue(kind)
            assertEquals(
                "$kind's dictated set changed size. That is allowed — but the browser has to move " +
                    "with it (see RecordDictationParityTest.WEB_NOT_YET) and this number has to be " +
                    "edited on purpose",
                expectedDictated,
                dictated.size,
            )
            assertEquals(
                "$kind's excluded set changed size. Every exclusion carries a written argument; if " +
                    "one was added, the argument is what a reviewer reads, and if one was removed a " +
                    "box just gained a microphone",
                expectedNotDictated,
                notDictated.size,
            )
            assertEquals(
                "$kind's two tables overlap or a column is spelled two ways",
                expectedDictated + expectedNotDictated,
                (dictated + notDictated.keys).size,
            )
        }
    }

    private fun wireColumns(type: Class<*>): Set<String> = type.declaredFields
        .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) && !it.name.contains('$') }
        .map { it.name }
        .toSet()

    private val WIRE_PLUMBING =
        setOf("id", "recordedAt", "recordedTimezone", "location", "steps", "clientKey", "formContract", "duplicateAcknowledged")

    private fun stepKey(field: String) =
        if (field.startsWith("step")) field else "step" + field.replaceFirstChar { it.uppercase() }

    private val MEDIA_COLUMNS = setOf("mediaTitle", "linkedRecordType", "linkedRecordId", "caption")

    private val LOCATION_COLUMNS = setOf(
        "state", "district", "village", "pincode",
        "latitude", "longitude", "altitude", "accuracy",
        "placeName", "locationAddress", "capturedAt",
        "subjectLatitude", "subjectLongitude",
    )

    private fun assertEveryColumnClassified(kind: RecordFormKind, columns: Set<String>) {
        val classified = RECORD_DICTATED.getValue(kind) + RECORD_NOT_DICTATED.getValue(kind).keys
        assertEquals(
            "the dictation tables and $kind's payload have drifted apart: unclassified = " +
                "${columns - classified}, classified but not a column = ${classified - columns}. " +
                "A column in neither table is an omission nobody can tell from a decision.",
            columns,
            classified,
        )
    }

    @Test
    fun `every wire column the payload carries is classified`() {
        assertEveryColumnClassified(
            RecordFormKind.CRAFT,
            wireColumns(CraftCreateRequest::class.java) - WIRE_PLUMBING,
        )
        assertEveryColumnClassified(
            RecordFormKind.ARTISAN,
            wireColumns(ArtisanCreateRequest::class.java) - WIRE_PLUMBING,
        )
        assertEveryColumnClassified(
            RecordFormKind.WORKSHOP,
            wireColumns(WorkshopCreateRequest::class.java) - WIRE_PLUMBING,
        )
        assertEveryColumnClassified(
            RecordFormKind.PRODUCT,
            wireColumns(ProductCreateRequest::class.java) - WIRE_PLUMBING,
        )
        assertEveryColumnClassified(
            RecordFormKind.TOOL,
            wireColumns(ToolCreateRequest::class.java) - WIRE_PLUMBING,
        )
        assertEveryColumnClassified(
            RecordFormKind.PROCESS,
            (wireColumns(ProcessCreateRequest::class.java) - WIRE_PLUMBING) +
                (wireColumns(ProcessStepRequest::class.java) - WIRE_PLUMBING).map { stepKey(it) } +
                setOf("artisanId"),
        )
        assertEveryColumnClassified(RecordFormKind.MEDIA, MEDIA_COLUMNS)
        assertEveryColumnClassified(RecordFormKind.LOCATION, LOCATION_COLUMNS)
    }

    @Test
    fun `every box without a microphone says why, in a sentence`() {
        for ((kind, reasons) in RECORD_NOT_DICTATED) {
            for ((column, reason) in reasons) {
                val trimmed = reason.trim()
                assertTrue(
                    "`$kind.$column`'s exclusion carries no argument, only \"$reason\"",
                    trimmed.length >= 60,
                )
                assertTrue(
                    "`$kind.$column`'s reason is not a sentence — it does not end in a full stop: " +
                        "\"$reason\"",
                    trimmed.endsWith("."),
                )
                assertFalse(
                    "`$kind.$column`'s reason is the column name restated, which explains nothing",
                    trimmed.equals(column, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `the closed vocabularies, the calendars, the measurements and the identity numbers are excluded by name`() {
        val mustBeExcluded = mapOf(
            RecordFormKind.ARTISAN to listOf(
                "dateOfBirth", "experienceYears", "phone", "email", "aadhaarNumber",
                "pehchanCardAvailable", "pehchanCardNumber", "dos", "donts", "gender",
                "craftId", "status",
                "craftStartDate", "experienceMonths",
            ),
            RecordFormKind.PRODUCT to listOf(
                "lengthInches", "breadthInches", "heightInches", "costOfMaking", "sellingPrice",
                "productType", "marketDemand",
            ),
            RecordFormKind.TOOL to listOf(
                "yearsInUse", "height", "width", "lengthInches", "breadthInches", "thickness",
                "weight", "radius", "replacementCost", "maker", "traditionType",
                "heightInches",
            ),
            RecordFormKind.WORKSHOP to listOf("startDate", "endDate", "date", "workshopType"),
            RecordFormKind.PROCESS to listOf(
                "preProcessAvailable", "artisanId", "productId", "notes", "stepType", "stepSortOrder",
            ),
            RecordFormKind.LOCATION to listOf(
                "state", "district", "pincode", "latitude", "longitude", "placeName",
                "locationAddress", "subjectLatitude", "subjectLongitude",
            ),
        )
        for ((kind, columns) in mustBeExcluded) {
            for (column in columns) {
                assertFalse(
                    "`$kind.$column` has gained a microphone. It is a closed vocabulary, a calendar, " +
                        "a measurement, a price or a regulated identity number, and a recogniser " +
                        "answers all five with words the column cannot hold",
                    recordDictates(kind, column),
                )
                assertTrue(
                    "`$kind.$column` is no longer in RECORD_NOT_DICTATED, so the argument against " +
                        "dictating it has been deleted rather than answered",
                    RECORD_NOT_DICTATED.getValue(kind).containsKey(column),
                )
            }
        }
    }

    @Test
    fun `the required prose boxes dictate and the required list boxes do not`() {
        assertTrue(recordDictates(RecordFormKind.CRAFT, "name"))
        assertTrue(recordDictates(RecordFormKind.ARTISAN, "name"))
        assertTrue(recordDictates(RecordFormKind.ARTISAN, "place"))
        assertTrue(recordDictates(RecordFormKind.WORKSHOP, "title"))
        assertTrue(recordDictates(RecordFormKind.WORKSHOP, "place"))
        assertTrue(recordDictates(RecordFormKind.PRODUCT, "productName"))
        assertTrue(recordDictates(RecordFormKind.PRODUCT, "artisanName"))
        assertTrue(recordDictates(RecordFormKind.TOOL, "toolkitName"))
        assertTrue(recordDictates(RecordFormKind.PROCESS, "name"))
        assertTrue(recordDictates(RecordFormKind.PROCESS, "stepName"))

        assertFalse(
            "`dos` is required and is still not dictated, and the reason is the CONTROL rather than " +
                "the content: `NumberedListInput`'s whole interaction is one row per point",
            recordDictates(RecordFormKind.ARTISAN, "dos"),
        )
        assertFalse(recordDictates(RecordFormKind.ARTISAN, "donts"))
    }

    @Test
    fun `recordDictates fails closed on a column nobody has classified`() {
        assertFalse(recordDictates(RecordFormKind.PRODUCT, "colourway"))
        assertFalse(recordDictates(RecordFormKind.ARTISAN, "localname"))
        assertFalse(recordDictates(RecordFormKind.CRAFT, ""))
    }
}
