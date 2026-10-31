package com.fieldrepository.app.questionnaire.data

import android.content.SharedPreferences
import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.portal.QuestionnairePortal
import com.fieldrepository.app.questionnaire.ui.FilterField
import com.fieldrepository.app.questionnaire.ui.activePills
import com.fieldrepository.app.questionnaire.ui.merged
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.nio.file.Files

/**
 * Recording chips and the shared filters on Android (owner, 31 Oct 2026), held to
 * `shared/questionnaire-chips.json`, the vectors the web is held to as well: the chips each take
 * shows, what tapping one does, the query every list sends, the lists' routes, the filters kept per
 * screen and the cached rows a list filters with no signal.
 */
class RecordingFiltersTest {
    private fun repoRoot(): File =
        generateSequence(File(".").absoluteFile) { it.parentFile }.firstOrNull { File(it, "shared/questionnaire-chips.json").isFile }
            ?: error("shared/questionnaire-chips.json not found above ${File(".").absolutePath}")

    private val vectors: JsonObject by lazy { Json.parseToJsonElement(File(repoRoot(), "shared/questionnaire-chips.json").readText()) as JsonObject }

    private fun filtersOf(o: JsonObject): QuestionnaireFilters = o.entries.fold(QuestionnaireFilters()) { f, (k, v) ->
        f.with(k, if (v is JsonArray) v.map { it.jsonPrimitive.content } else listOf(v.jsonPrimitive.content))
    }

    private fun plain(f: QuestionnaireFilters): Map<String, List<String>> = f.values.filterValues { it.isNotEmpty() }

    @QFeature("E1")
    @Test fun `every take shows the shared vectors' chips, in their order and words`() {
        (vectors["cases"] as JsonArray).forEach { case ->
            case as JsonObject
            val chips = outboxJson.decodeFromJsonElement(QChips.serializer(), case["chips"]!!)
            val got = ChipRules.display(chips).map { c ->
                listOf(c.kind, c.label, c.filter?.let { "${it.key}=${it.value}" }, c.title)
            }
            val want = (case["expect"] as JsonArray).map { e ->
                e as JsonObject
                val filter = e["filter"].takeUnless { it is JsonNull } as JsonObject?
                listOf(
                    e["kind"]!!.jsonPrimitive.content, e["label"]!!.jsonPrimitive.content,
                    filter?.let { "${it["key"]!!.jsonPrimitive.content}=${it["value"]!!.jsonPrimitive.content}" },
                    (e["title"] as? JsonPrimitive)?.content
                )
            }
            assertEquals(case["name"]!!.jsonPrimitive.content, want, got)
        }
    }

    @QFeature("E1")
    @Test fun `a recording, an inbox row and a media row carry their chips`() {
        val take = QuestionnairePortal.json.decodeFromString(
            QRecording.serializer(),
            """{"id":"r1","chips":{"targets":[{"kind":"SECTION","sectionId":"s1","sectionCode":"A","sectionTitle":"Materials"}],"durationMs":42000}}"""
        )
        assertEquals(listOf("A · Materials", "0:42"), ChipRules.display(take.chips).map { it.label })
        val row = Json.parseToJsonElement("""{"mediaId":"m1","chips":{"sessionDate":"2026-10-22"}}""") as JsonObject
        assertEquals("22 Oct 2026", ChipRules.display(ChipRules.parse(row["chips"])).single { it.kind == "date" }.label)
        assertEquals(null, ChipRules.parse(JsonNull))
    }

    @QFeature("E2")
    @Test fun `tapping a chip applies it, and tapping it again takes it off`() {
        (vectors["applyCases"] as JsonArray).forEach { case ->
            case as JsonObject
            val chip = case["chip"] as JsonObject
            val filter = ChipFilter(chip["key"]!!.jsonPrimitive.content, chip["value"]!!.jsonPrimitive.content)
            val after = filtersOf(case["before"] as JsonObject).applyChip(filter)
            assertEquals(case["name"]!!.jsonPrimitive.content, plain(filtersOf(case["after"] as JsonObject)), plain(after))
        }
        val on = QuestionnaireFilters().applyChip(ChipFilter("artisanId", "a1"), "Giriraj")
        assertTrue(on.isActive(ChipFilter("artisanId", "a1")))
        assertEquals("Giriraj", on.labels["a1"])
        assertEquals(listOf("artisanId" to "a1"), activePills(on, emptySet()))
    }

    @QFeature("E3")
    @Test fun `every list sends the canonical query, and reads it back`() {
        (vectors["queryCases"] as JsonArray).forEach { case ->
            case as JsonObject
            val f = filtersOf(case["filters"] as JsonObject)
            val query = case["query"]!!.jsonPrimitive.content
            assertEquals(query, f.toQuery())
            assertEquals(plain(f), plain(QuestionnaireFilters.fromQuery(query)))
        }
        val f = QuestionnaireFilters().with("dateFrom", listOf("2026-10-01")).with("dateTo", listOf("2026-10-31")).withQ("loom")
        assertEquals("a range is one filter, the text none", 1, f.activeCount)
    }

    private data class Seen(val path: String, val query: String?)

    private fun api(seen: MutableList<Seen>, reply: (String) -> String): QuestionnaireApi = Retrofit.Builder()
        .baseUrl("http://localhost/api/")
        .client(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath.removePrefix("/api/")
            seen += Seen(path, request.url.encodedQuery)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(reply(path).toResponseBody("application/json".toMediaType())).build()
        }.build())
        .addConverterFactory(QuestionnairePortal.json.asConverterFactory("application/json".toMediaType()))
        .build().create(QuestionnaireApi::class.java)

    @QFeature("E3")
    @Test fun `the sessions list asks the server with every filter and draws each session's chips`() = runBlocking {
        val seen = mutableListOf<Seen>()
        val api = api(seen) { """{"items":[{"id":"s1","title":"Interview_2026-10-22","chips":{"questionnaire":{"id":"f1","title":"2nd Craft Toolkit"},"artisans":[{"id":"a1","name":"Giriraj"}],"sessionDate":"2026-10-22"}}],"nextCursor":"c2"}""" }
        val f = QuestionnaireFilters().with("artisanId", listOf("a1", "a2")).with("transcript", listOf("HAS")).withQ("block print")
        val page = api.sessionsFiltered(f.toParams(), cursor = null, limit = 30)
        assertEquals("questionnaire/sessions", seen.last().path)
        assertEquals("q=block%20print&artisanId=a1,a2&transcript=HAS&limit=30", seen.last().query)
        assertEquals(listOf("2nd Craft Toolkit", "Giriraj", "22 Oct 2026"), com.fieldrepository.app.questionnaire.data.ChipRules.displaySession(page.items.single().chips).map { it.label })
        assertEquals("c2", page.nextCursor)
    }

    @QFeature("E4")
    @Test fun `a form's sessions fix the form, and the library offers who and where its sessions were`() = runBlocking {
        val fixed = QuestionnaireFilters().with("questionnaireId", listOf("f1"))
        val chosen = QuestionnaireFilters().with("artisanId", listOf("a1")).with("questionnaireId", listOf("other"))
        val applied = merged(chosen, fixed)
        assertEquals(listOf("f1"), applied["questionnaireId"])
        assertEquals(listOf("a1"), applied["artisanId"])
        assertTrue("the fixed form is no pill", activePills(applied, setOf("questionnaireId")).none { it.first == "questionnaireId" })
        assertFalse(FilterField.QUESTIONNAIRE in FilterField.FORMS)
        assertTrue(FilterField.ARTISANS in FilterField.FORMS && FilterField.WORKSHOP in FilterField.FORMS)
        val seen = mutableListOf<Seen>()
        api(seen) { """{"items":[]}""" }.sessionsFiltered(applied.toParams())
        assertEquals("questionnaireId=f1&artisanId=a1", seen.last().query)
    }

    @QFeature("E5")
    @Test fun `the recordings list and the filter options read their routes`() = runBlocking {
        val seen = mutableListOf<Seen>()
        val api = api(seen) { path ->
            if (path == "questionnaire/recordings") """{"items":[{"id":"r1","sessionId":"s1","chips":{"session":{"id":"s1","title":"Interview_2026-10-22"},"targets":[{"kind":"QUESTION","questionLineageId":"q1","displayCode":"F1","sectionTitle":"Tools"}]}}],"nextCursor":null}"""
            else """{"artisans":[{"id":"a1","label":"Giriraj","count":3}]}"""
        }
        val page = api.recordings(QuestionnaireFilters().with("sectionId", listOf("secA")).with("source", listOf("UPLOAD")).toParams())
        assertEquals("sectionId=secA&source=UPLOAD", seen.last().query)
        assertEquals("F1 · Tools", ChipRules.display(page.items.single().chips).first().label)
        val options = api.filterOptions(field = "artisans", q = "gir")
        assertEquals("questionnaire/filters", seen.last().path)
        assertEquals("field=artisans&q=gir", seen.last().query)
        assertEquals(3, options.artisans.single().count)
    }

    @QFeature("E5")
    @Test fun `a media list sends the questionnaire filters and keeps its own search and upload days`() {
        val f = QuestionnaireFilters().withQ("loom").applyChip(ChipFilter("date", "2026-10-22"))
            .with("artisanId", listOf("a1", "a2")).with("source", listOf("UPLOAD"))
        assertEquals(mapOf("artisanId" to "a1,a2", "source" to "UPLOAD"), f.mediaParams())
        assertFalse("a day on a media list is its upload day, not the session's", f.appliesToMedia(ChipFilter("date", "2026-10-22")))
        assertTrue(f.appliesToMedia(ChipFilter("artisanId", "a3")))
        assertTrue(FilterField.DATES !in FilterField.MEDIA && FilterField.SECTION in FilterField.MEDIA)
    }

    @QFeature("E5")
    @Test fun `the app search reads a take as what it answers, and opens the list on its form`() {
        val take = QuestionnairePortal.json.decodeFromString(
            QRecording.serializer(),
            """{"id":"r9","chips":{"session":{"id":"s1","title":"Interview_2026-07-07"},"questionnaire":{"id":"f2","title":"Market survey"},"targets":[{"kind":"QUESTION","questionLineageId":"q1","displayCode":"F1","sectionTitle":"Tools"}]}}"""
        )
        val bare = QuestionnairePortal.json.decodeFromString(QRecording.serializer(), """{"id":"r8"}""")
        val hits = RecordingSearch.hits(listOf(take, bare), " loom ")
        assertEquals(listOf("F1 · Tools", "Whole session"), hits.map { it.title })
        assertEquals("Interview_2026-07-07", hits.first().subtitle)
        assertEquals("q=loom&questionnaireId=f2", hits.first().filters.toQuery())
        assertEquals("Market survey", hits.first().filters.labels["f2"])
        assertEquals("q=loom", hits.last().filters.toQuery())
    }

    private class MemoryPrefs : SharedPreferences {
        val map = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as String? ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = apply { map[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = apply { map.remove(key) }
            override fun clear() = apply { map.clear() }
            override fun commit() = true
            override fun apply() {}
        }
    }

    @QFeature("E6")
    @Test fun `a list's filters come back after navigating away, for the account that set them only`() {
        val prefs = MemoryPrefs()
        var me: String? = "u1"
        val store = FilterStore(prefs) { me }
        val set = QuestionnaireFilters().applyChip(ChipFilter("artisanId", "a1"), "Giriraj").applyChip(ChipFilter("date", "2026-10-22")).withQ("loom")
        store.save(FilterStore.SESSIONS, set)
        val back = FilterStore(prefs) { me }.load(FilterStore.SESSIONS)
        assertEquals(plain(set), plain(back))
        assertEquals("Giriraj", back.labels["a1"])
        assertTrue("each list keeps its own", store.load(FilterStore.RECORDINGS).isEmpty)
        me = "u2"
        assertTrue("another account starts clean", store.load(FilterStore.SESSIONS).isEmpty)
    }

    @QFeature("E6")
    @Test fun `with no signal a list filters the rows this phone last read`() {
        val dir = Files.createTempDirectory("rows").toFile()
        var me: String? = "u1"
        val rows = OfflineRows(dir, { me })
        val json = QuestionnairePortal.json
        val r1 = json.decodeFromString(QRecording.serializer(), """{"id":"r1","capturedAt":"2026-10-20T05:00:00Z","subjects":[{"artisanId":"a1"}],"transcript":{"status":"COMPLETED","text":"the loom came from Bagru"},"chips":{"questionnaire":{"id":"f1","title":"Survey","kind":"MARKET_SURVEY"},"targets":[{"kind":"SECTION","sectionId":"secA","sectionCode":"A","sectionTitle":"Materials"}],"artisans":[{"id":"a1","name":"Giriraj"}],"sessionDate":"2026-10-20","source":"APP"}}""")
        val r2 = json.decodeFromString(QRecording.serializer(), """{"id":"r2","capturedAt":"2026-10-21T20:00:00Z","subjects":[{"artisanId":"a3","role":"PRESENT"}],"chips":{"questionnaire":{"id":"f1","title":"Survey"},"targets":[{"kind":"QUESTION","questionLineageId":"q3","sectionId":"secB","displayCode":"B1"}],"artisans":[{"id":"a3","name":"Ravi","role":"PRESENT"}],"sessionDate":"2026-10-20","capturedOn":"2026-10-22","workshop":{"id":"dw1","name":"Bagru","type":"DESIGN"},"source":"UPLOAD"}}""")
        rows.keepRecordings(listOf(r1, r2))
        fun ids(f: QuestionnaireFilters) = rows.recordings(f).map { it.id }
        val none = QuestionnaireFilters()
        assertEquals("newest first", listOf("r2", "r1"), ids(none))
        assertEquals(listOf("r2"), ids(none.with("artisanId", listOf("a3"))))
        assertEquals(listOf("r1"), ids(none.with("sectionId", listOf("secA"))))
        assertEquals(listOf("r2"), ids(none.with("questionLineageId", listOf("q3"))))
        assertEquals("the capture day counts", listOf("r2"), ids(none.with("dateFrom", listOf("2026-10-22")).with("dateTo", listOf("2026-10-22"))))
        assertEquals(listOf("r1"), ids(none.with("transcript", listOf("HAS"))))
        assertEquals(listOf("r2"), ids(none.with("transcript", listOf("NONE"))))
        assertEquals(listOf("r2"), ids(none.with("designWorkshopId", listOf("dw1"))))
        assertEquals(listOf("r2"), ids(none.with("source", listOf("UPLOAD"))))
        assertEquals("the transcript is searched", listOf("r1"), ids(none.withQ("LOOM")))
        assertEquals(listOf("r1"), ids(none.with("kind", listOf("MARKET_SURVEY"))))

        val s1 = json.decodeFromString(QSession.serializer(), """{"id":"s1","title":"Interview_2026-10-20_Bagru","sessionDate":"2026-10-20","participants":[{"artisanId":"a1","name":"Giriraj"}],"questionnaire":{"id":"f1","title":"Survey"},"source":"APP"}""")
        rows.keepSessions(listOf(s1))
        assertEquals(listOf("s1"), rows.sessions(none.with("artisanId", listOf("a1"))).map { it.id })
        assertTrue(rows.sessions(none.with("artisanId", listOf("a9"))).isEmpty())
        assertTrue("a session row cannot say its takes' sections offline", rows.sessions(none.with("sectionId", listOf("secA"))).isEmpty())
        me = "u2"
        assertTrue("another account never sees them", rows.recordings(none).isEmpty())
        dir.deleteRecursively()
    }
}
