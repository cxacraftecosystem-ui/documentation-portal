package com.fieldrepository.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** The map screen's Coverage card: the wire shape, the rows it draws, and the empty state. */
class CoverageSummaryTest {
    /** A trimmed `GET /map/coverage?level=STATE` answer, with blocks the card does not read. */
    private val body = """
        {"asOf":"2026-10-18T00:00:00Z",
         "register":{"lgdAsOf":"2026-10-18","listVersion":1,"districtCount":795,"stateCount":36},
         "filters":{"level":"STATE","craftCode":[],"scheme":[],"fy":[],"stateLgd":null},
         "units":[
          {"stateLgd":8,"districtLgd":null,"state":"Rajasthan","district":null,"polygon":"STATE",
           "counts":{"designWorkshops":0,"workshops":2,"artisans":14,"crafts":2,"products":5,"tools":3,"media":40},
           "peer":null,"craftCodes":["ajrakh"],"clusters":[]},
          {"stateLgd":32,"districtLgd":null,"state":"Goa","district":null,"polygon":"STATE",
           "counts":{"workshops":1,"artisans":1,"crafts":1,"products":0,"tools":0,"media":0}}
         ],
         "markers":[],"markersTruncated":false,
         "gaps":{
          "craftsNeverDocumented":[{"code":"bidri","name":"Bidri"},{"code":"pattachitra","name":"Pattachitra"}],
          "districtsWithoutWorkshop":{"count":3,"byState":[
           {"stateLgd":8,"state":"Rajasthan","total":33,"without":[
            {"lgd":99,"name":"Barmer"}]},
           {"stateLgd":21,"state":"Odisha","total":2,"without":[{"lgd":350,"name":"Angul"},{"lgd":351,"name":"Balangir"}]}
          ]},
          "statesWithoutWorkshop":[{"lgd":21,"name":"Odisha"}]},
         "unmatched":{"count":0},
         "truncated":false}
    """.trimIndent()

    @Test
    fun theStateLevelAnswerDecodesAndFoldsIntoRows() {
        val dto = ApiClient.json.decodeFromString(CoverageDto.serializer(), body)
        val summary = coverageSummary(dto)

        assertEquals(2, summary.craftsNeverDocumented)
        assertEquals(3, summary.districtsWithoutWorkshop)
        assertFalse(summary.isEmpty)
        assertEquals(listOf("Goa", "Odisha", "Rajasthan"), summary.states.map { it.name }.sorted())

        // A state with no gap listed has every district covered, and is shown first.
        val goa = summary.states.first()
        assertEquals("Goa", goa.name)
        assertNull(goa.total)
        assertEquals("Every district has a workshop", CoverageCopy.districts(goa))

        val rajasthan = summary.states.single { it.name == "Rajasthan" }
        assertEquals(32, rajasthan.withWorkshop)
        assertEquals("32 of 33 districts with a workshop", CoverageCopy.districts(rajasthan))
        // Crafts are distinct crafts across the records, so they are never added into the records.
        assertEquals(64, rajasthan.records)
        assertEquals("64 records", CoverageCopy.records(rajasthan.records))

        assertEquals(listOf("Goa", "Rajasthan", "Odisha"), summary.states.map { it.name })
        assertEquals("Crafts never documented: 2", CoverageCopy.crafts(summary.craftsNeverDocumented))
    }

    @Test
    fun aCorpusWithNothingPlacedIsTheEmptyState() {
        val empty = """
            {"units":[],"gaps":{"craftsNeverDocumented":[{"code":"bidri","name":"Bidri"}],
             "districtsWithoutWorkshop":{"count":3,"byState":[{"stateLgd":32,"state":"Goa","total":2,"without":[{"lgd":1,"name":"North Goa"},{"lgd":2,"name":"South Goa"}]}]},
             "statesWithoutWorkshop":[]}}
        """.trimIndent()
        val summary = coverageSummary(ApiClient.json.decodeFromString(CoverageDto.serializer(), empty))
        assertTrue(summary.isEmpty)
        assertEquals("0 of 2 districts with a workshop", CoverageCopy.districts(summary.states.single()))

        // An answer missing every block still decodes, to nothing.
        val bare = coverageSummary(ApiClient.json.decodeFromString(CoverageDto.serializer(), "{}"))
        assertTrue(bare.states.isEmpty())
        assertTrue(bare.isEmpty)
        assertEquals(0, bare.craftsNeverDocumented)
        assertEquals("1 record", CoverageCopy.records(1))
    }

    @Test
    fun theRepositoryAsksForTheStateLevel() = runBlocking {
        val sent = mutableListOf<HttpUrl>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                sent += chain.request().url
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
        val api = Retrofit.Builder()
            .baseUrl("https://field-repository.invalid/api/")
            .client(client)
            .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FieldRepositoryApi::class.java)

        val dto = FieldRepository(api, unopenedTokenStore()).mapCoverage()

        assertEquals("/api/map/coverage", sent.single().encodedPath)
        assertEquals("STATE", sent.single().queryParameter("level"))
        assertEquals(2, dto.units.size)
    }
}
