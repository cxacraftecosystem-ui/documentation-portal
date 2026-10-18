package com.fieldrepository.app.data

import com.fieldrepository.app.ui.completenessChipAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * THE HANDSET'S PORT OF THE RECORD COMPLETENESS METER (2026-10-18), against the one shared table.
 *
 * `shared/record-completeness.json` is byte-identical in both portals and read unmodified by the
 * server's `test_record_completeness.py` and the web's `record-completeness-unit.spec.ts`. This proves
 * the Kotlin evaluator gives the same answer on the shared vectors, that the APK's bundled copy is the
 * shared file, that a newer table wins over an older one, and what a missing-field chip does.
 */
class RecordCompletenessTest {

    private fun shared(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "shared/record-completeness.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("shared/record-completeness.json was not found above ${File("").absolutePath}")
    }

    private fun asset(): File = File(checkNotNull(shared().absoluteFile.parentFile?.parentFile), "android/app/src/main/assets/record-completeness.json")

    private fun rules(): CompletenessRules = checkNotNull(RecordCompletenessStore.decode(shared().readText()))

    private fun primitive(value: kotlinx.serialization.json.JsonElement): Any? = when {
        value is JsonNull -> null
        value is JsonPrimitive && value.isString -> value.content
        value is JsonPrimitive -> value.booleanOrNull ?: value.doubleOrNull
        else -> value.toString()
    }

    @Test
    fun `the shared vectors hold`() {
        val vectors = Json.parseToJsonElement(shared().readText()).jsonObject["vectors"]!!.jsonArray
        assertTrue("a vector table that failed to load would pass vacuously", vectors.isNotEmpty())
        val table = rules()
        for (element in vectors) {
            val vector = element.jsonObject
            val record = vector["record"]!!.jsonObject.mapValues { (_, v) -> primitive(v) }
            val counts = vector["counts"]!!.jsonObject.mapValues { (_, v) -> v.jsonPrimitive.int }
            val expect = vector["expect"]!!.jsonObject
            val block = evaluateCompleteness(table, vector["kind"]!!.jsonPrimitive.content, record, counts)
            assertEquals(vector.toString(), expect["filled"]!!.jsonPrimitive.int, block.filled)
            assertEquals(vector.toString(), expect["total"]!!.jsonPrimitive.int, block.total)
            assertEquals(vector.toString(), expect["missing"]!!.jsonArray.map { it.jsonPrimitive.content }, block.missing.map { it.key })
            assertEquals(vector.toString(), expect["requiredMissing"]!!.jsonPrimitive.int, block.requiredMissing)
            assertEquals(vector.toString(), expect["sentence"]!!.jsonPrimitive.content, completenessSentence(block))
        }
    }

    @Test
    fun `required items lead, three words then a count, and zero is present`() {
        val table = rules()
        val empty = evaluateCompleteness(table, CompletenessKind.ARTISAN, emptyMap())
        assertEquals("7 fields left: date of birth, craft start date, craft, +4 more", completenessSentence(empty))
        assertEquals(0, empty.percent)
        assertEquals(5, empty.requiredMissing)

        val product = mapOf(
            "artisanId" to "a", "sellingPrice" to 0, "costOfMaking" to 0, "timeTakenToCompleteProduct" to "2 days",
            "heightInches" to 0, "marketDemand" to "UNKNOWN",
        )
        val block = evaluateCompleteness(table, CompletenessKind.PRODUCT, product, mapOf("photos" to 1))
        assertEquals(listOf("marketDemand"), block.missing.map { it.key })
        assertEquals("1 field left: market demand", completenessSentence(block))
        assertEquals(85, block.percent)

        val done = evaluateCompleteness(table, CompletenessKind.PRODUCT, product + ("marketDemand" to "HIGH"), mapOf("photos" to 1))
        assertEquals("Complete", completenessSentence(done))
        assertEquals(100, done.percent)
        assertEquals("7 of 7 fields filled", completenessAria(done))
    }

    @Test
    fun `blank after trim is missing`() {
        val block = evaluateCompleteness(rules(), CompletenessKind.INTERVIEW, mapOf("interviewDate" to "2026-01-01", "place" to "  ", "language" to "Hindi"),
            mapOf("artisans" to 1, "answers" to 1, "recordings" to 1))
        assertEquals(listOf("place"), block.missing.map { it.key })
    }

    @Test
    fun `the bundled asset is the shared file, byte for byte`() {
        val normalise = { f: File -> f.readText().replace("\r\n", "\n").toByteArray() }
        assertArrayEquals(normalise(shared()), normalise(asset()))
    }

    @Test
    fun `the higher version wins and a foreign table never replaces the one in hand`() {
        val current = rules()
        assertSame(current, pickCompletenessRules(current, null))
        assertSame(current, pickCompletenessRules(current, current.copy(version = current.version - 1)))
        assertSame(current, pickCompletenessRules(current, current.copy(version = current.version + 1, contract = "other")))
        val newer = current.copy(version = current.version + 1)
        assertSame(newer, pickCompletenessRules(current, newer))
        assertNull(RecordCompletenessStore.decode("{}"))
        assertNull(RecordCompletenessStore.decode("not json"))
    }

    @Test
    fun `a cached file on disk is read back, and a damaged one is ignored`() {
        val dir = kotlin.io.path.createTempDirectory("completeness").toFile()
        try {
            val file = File(dir, "record-completeness.json")
            assertNull(RecordCompletenessStore.readFile(file))
            file.writeText(shared().readText())
            assertNotNull(RecordCompletenessStore.readFile(file))
            file.writeText("{broken")
            assertNull(RecordCompletenessStore.readFile(file))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a chip opens the form at its field, and only for a reader who may update`() {
        val item = CompletenessMissingDto("craftStartDate", "Craft start date", "craft start date", "required")
        var opened: String? = null
        val action = completenessChipAction(item, canUpdate = true) { opened = it }
        assertNotNull(action)
        action!!.invoke()
        assertEquals("craftStartDate", opened)
        assertNull(completenessChipAction(item, canUpdate = false) { opened = "never" })
    }

    @Test
    fun `live counts keep what the server counted and add what the form holds`() {
        val table = rules()
        val stored = evaluateCompleteness(table, CompletenessKind.ARTISAN, emptyMap(), mapOf("photos" to 2))
        assertEquals(mapOf("photos" to 1), liveCompletenessCounts(table, CompletenessKind.ARTISAN, stored))
        assertEquals(mapOf("photos" to 0), liveCompletenessCounts(table, CompletenessKind.ARTISAN, null))
        assertEquals(mapOf("photos" to 3), liveCompletenessCounts(table, CompletenessKind.ARTISAN, null, mapOf("photos" to 3)))
    }

    @Test
    fun `a record's block decodes from the API and old payloads without one still decode`() {
        val json = Json { ignoreUnknownKeys = true }
        val withBlock = json.decodeFromString(ArtisanDetailDto.serializer(),
            """{"id":"a","name":"N","completeness":{"rulesVersion":1,"filled":5,"total":7,"percent":71,"requiredMissing":1,
               "missing":[{"key":"dateOfBirth","label":"Date of birth","words":"date of birth","level":"required"}]}}""")
        assertEquals(71, withBlock.completeness!!.percent)
        assertTrue(withBlock.completeness.missing.single().required)
        val without = json.decodeFromString(ArtisanDetailDto.serializer(), """{"id":"a","name":"N"}""")
        assertNull(without.completeness)
        assertEquals(CompletenessFilter.REQUIRED, CompletenessFilter.parse("REQUIRED"))
        assertEquals(CompletenessFilter.ALL, CompletenessFilter.parse("junk"))
        assertEquals("QUESTIONNAIRE".let(CompletenessKind::forMode), CompletenessKind.INTERVIEW)
        assertNull(CompletenessKind.forMode("PROCESS"))
    }

    /** A real Retrofit client whose calls never leave the process: the URL it built is what is tested. */
    private fun wire(): Pair<FieldRepositoryApi, MutableList<HttpUrl>> {
        val sent = mutableListOf<HttpUrl>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                sent += chain.request().url
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"items":[],"total":0,"page":1,"pageSize":100,"pages":0}""".toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
        val api = Retrofit.Builder()
            .baseUrl("https://field-repository.invalid/api/")
            .client(client)
            .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FieldRepositoryApi::class.java)
        return api to sent
    }

    @Test
    fun `the completeness filter rides on every list that carries a meter, and only when asked`() = runBlocking {
        val (api, sent) = wire()
        api.artisans(completeness = "required")
        api.products(completeness = "incomplete")
        api.tools(completeness = "incomplete")
        api.workshops(completeness = "incomplete")
        api.interviews(completeness = "required")
        api.artisans()
        assertEquals(listOf("required", "incomplete", "incomplete", "incomplete", "required", null), sent.map { it.queryParameter("completeness") })
    }

    @Test
    fun `the rules route is one the offline cache keeps`() {
        // FR Android revalidates the rules through the OkHttp disk cache (ETag), so the path must not
        // be on the never-cached list.
        assertTrue(OfflineReadCache.isCacheablePath("/api/records/completeness-rules"))
    }
}
