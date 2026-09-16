package com.fieldrepository.app.data

import com.fieldrepository.app.ui.listCutNotice
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Retrofit

/**
 * THE WORKSHOP SCOPE, PINNED ONTO THE ACTUAL REQUEST — defect (2)'s regression test.
 *
 * ── WHAT THE DEFECT WAS ─────────────────────────────────────────────────────────────────────────
 *
 * *"In the android, the questionnaire from the previous workshop are showing up even in the third
 * workshop."* `FieldRepository.interviews()` took no workshop argument at all, so every questionnaire
 * surface on the handset listed every interview in the repository regardless of which workshop was on
 * screen — while the artisan list beside it had been scoped since the shared control landed.
 *
 * ── WHY IT IS ASSERTED AT THE URL AND NOT AT THE KOTLIN CALL ────────────────────────────────────
 *
 * The bug is not "a parameter was never added"; that would be caught by the compiler the moment a
 * caller tried to pass one. The bug is that A SCOPE A SCREEN IS HOLDING FAILS TO REACH THE SERVER,
 * and every way of getting that wrong lives *below* the repository method's signature: a `@Query`
 * annotation spelled `workshopId` on the plural parameter, a default that quietly drops the
 * argument, a helper that turns an empty selection into `""` instead of leaving the parameter off.
 * A test that stopped at "the function takes a `List<String>`" would pass through all four. So this
 * suite drives a REAL Retrofit-generated `FieldRepositoryApi` and reads the query string it built.
 *
 * NO NETWORK AND NO NEW TEST DEPENDENCY. An OkHttp interceptor short-circuits every call with a
 * canned page and records the URL Retrofit assembled, which is the whole of what is being asserted.
 * `app/build.gradle.kts` carries only `junit:junit` for the JVM suite — no MockWebServer, no mocking
 * framework — and that file belongs to another slice of this change, so the seam had to be one the
 * module already has. It also happens to be the better seam: a mock of the API interface would
 * assert the arguments Kotlin passed, which is the half that was never in doubt.
 *
 * The converter is `ApiClient.json`, deliberately and not a fresh `Json { }` — the same decoder the
 * app ships, for the reason `ApiClient` states where it hoisted that value out of `retrofit()`.
 *
 * ── THE WEB TWIN ────────────────────────────────────────────────────────────────────────────────
 *
 * The browser sends the identical parameter from the identical control, and
 * `backend/app/services/record_filters.resolve_workshop_ids` parses one spelling for both. What is
 * asserted below — comma-joined ids, order preserved, blanks and duplicates dropped, the reserved
 * `none` passed through untouched, and an ABSENT parameter rather than an empty one for "every
 * workshop" — is therefore a claim about both clients, and the parity argument in the change report
 * rests on it.
 */
class QuestionnaireScopeTest {

    private val sent = mutableListOf<HttpUrl>()

    /** One interview and a `total` far past the page, so the envelope has something to say. */
    private val onePageOfMany = """
        {"items":[{"id":"iv1","title":"Sitting one","createdAt":"2026-09-01T00:00:00Z"}],
         "total":137,"page":1,"pageSize":100,"pages":2}
    """.trimIndent()

    /**
     * A real Retrofit-generated client whose calls never leave the process.
     *
     * The interceptor is the test's only instrument: it records `chain.request().url` — the URL
     * Retrofit built from the `@Query` annotations, which is the artefact under test — and answers
     * with [onePageOfMany] instead of opening a socket. The base URL is deliberately a `.invalid`
     * host: if the short-circuit is ever broken by an edit, the suite fails with a DNS error rather
     * than quietly reaching something real.
     */
    private fun api(): FieldRepositoryApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                sent += chain.request().url
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(onePageOfMany.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
        return Retrofit.Builder()
            .baseUrl("https://field-repository.invalid/api/")
            .client(client)
            .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FieldRepositoryApi::class.java)
    }

    private fun repository(api: FieldRepositoryApi): FieldRepository =
        FieldRepository(api, unopenedTokenStore())

    /**
     * A `TokenStore` THAT WAS NEVER OPENED — the one awkward line in this file, and the alternatives
     * are worse.
     *
     * `FieldRepository` takes two collaborators, and the second one wraps `SharedPreferences`: its
     * constructor asks an Android `Context` for them. A plain JVM suite has no `Context` to give. This
     * module carries no Robolectric and no mocking framework (`app/build.gradle.kts` declares
     * `junit:junit` and nothing else for the JVM suite), and that file belongs to another slice of
     * this change, so a new test dependency was not on the table. `RecordSwitcherTest` records the
     * same constraint and works around it the same way — by testing what it can actually reach.
     *
     * NULL IS NOT REACHABLE, which is the first thing to try and the first thing that fails: Kotlin
     * emits `Intrinsics.checkNotNullParameter` on every public constructor, so a null second argument
     * throws inside `FieldRepository.<init>` however it is smuggled in — through a generic, through
     * reflection, through a cast. So the object is ALLOCATED WITHOUT RUNNING ITS CONSTRUCTOR instead:
     * a real `TokenStore` of the right type, with `preferences` never assigned. This is the same
     * mechanism every serialization library and every mocking framework uses to build an instance
     * whose constructor it cannot call.
     *
     * IT IS SAFE HERE FOR A REASON THAT CAN BE CHECKED, not because it happens to work. The bearer
     * token is attached by an interceptor in `ApiClient`, not by this class; `FieldRepository`'s
     * constructor only stores the reference; and the four methods this suite drives — `interviews`,
     * `interviewsPage`, `interviewsForArtisan` and the page's envelope — are pass-throughs to Retrofit
     * that never reach for the session. If an edit makes one of them read the token store, this suite
     * fails with an NPE naming the line. That is the correct outcome and not a flake: a read that
     * needs the session is not the read this file is describing, and it should be looked at.
     *
     * AND IF THE MECHANISM ITSELF EVER GOES AWAY — a JDK that removes `allocateInstance`, a module
     * system that closes `sun.misc` — this throws and every test here goes red. Loudly wrong is the
     * requirement (`ui/RepoSources.kt` argues it at length for the source-reading suites): a helper
     * that swallowed the failure and skipped would report parity on the one day nobody should believe
     * it.
     */
    private fun unopenedTokenStore(): TokenStore {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, TokenStore::class.java) as TokenStore
    }

    /** The single request this test made, or a failure naming how many there actually were. */
    private fun onlyRequest(): HttpUrl {
        assertEquals("expected exactly one request, got $sent", 1, sent.size)
        return sent.first()
    }

    // -----------------------------------------------------------------------------------------
    // The scope reaches the wire
    // -----------------------------------------------------------------------------------------

    /**
     * THE ONE THAT WOULD HAVE FAILED BEFORE. `interviews()` used to be
     * `api.interviews(pageSize = 100).items` — no workshop argument existed to pass, so this request
     * went out naming no workshop and the server answered with the whole repository.
     */
    @Test
    fun `the chosen workshops ride onto the interviews request`() {
        runBlocking { repository(api()).interviews(listOf("w2", "w1")) }

        val url = onlyRequest()
        assertEquals("/api/questionnaire/interviews", url.encodedPath)
        assertEquals("w2,w1", url.queryParameter("workshopIds"))
        // The ceiling is asked for explicitly, not left to the route's `pageSize` default of 20 —
        // the notice asserted further down is only honest if the client asked for everything it could.
        assertEquals("100", url.queryParameter("pageSize"))
    }

    /**
     * ORDER IS THE CALLER'S, NOT THIS LAYER'S. `WorkshopScopeState.workshopIds` sorts before it hands
     * the selection over precisely so that re-ticking the same two workshops in the other order is one
     * scope and not two — an effect keyed on `requestKey` must not re-request for a selection that
     * means the same thing. A second, disagreeing sort here would put that key and this query string
     * out of step.
     */
    @Test
    fun `the order the caller chose is the order that is sent`() {
        runBlocking { repository(api()).interviews(listOf("alpha", "beta", "gamma")) }
        assertEquals("alpha,beta,gamma", onlyRequest().queryParameter("workshopIds"))
    }

    /** Blanks and duplicates are dropped before the join, not sent as empty ids that match nothing. */
    @Test
    fun `blank and repeated ids never reach the query string`() {
        runBlocking { repository(api()).interviews(listOf("w1", "  ", "w1", "")) }

        val url = onlyRequest()
        assertEquals("w1", url.queryParameter("workshopIds"))
        assertEquals("w1", url.queryParameter("workshopId"))
    }

    // -----------------------------------------------------------------------------------------
    // "Every workshop" is an ABSENT parameter, never an empty one
    // -----------------------------------------------------------------------------------------

    /**
     * The whole difference between "do not filter" and "filter by one blank id, which matches
     * nothing" is whether the parameter is there at all — the entire result set, decided by a single
     * `?:`. `resolve_workshop_ids` reads absent, empty and all-blank alike as "every workshop", but
     * only because this client never sends the middle two.
     *
     * This is also the assertion that pins REQUIREMENT 3 for the handset: the "All records" state is
     * an empty selection, and an empty selection asks for everything explicitly rather than falling
     * through to some other default.
     */
    @Test
    fun `no scope sends no workshop parameter at all`() {
        val api = api()
        runBlocking {
            repository(api).interviews()
            repository(api).interviews(emptyList())
            repository(api).interviews(listOf("", "   "))
        }

        assertEquals(3, sent.size)
        sent.forEach { url ->
            assertNull("workshopIds must be absent, not empty: $url", url.queryParameter("workshopIds"))
            assertNull("workshopId must be absent, not empty: $url", url.queryParameter("workshopId"))
        }
    }

    // -----------------------------------------------------------------------------------------
    // The singular parameter that rides beside the plural
    // -----------------------------------------------------------------------------------------

    /**
     * BOTH PARAMETERS FOR A SINGLE WORKSHOP, which is the handset's default scope and therefore the
     * overwhelmingly common request. FastAPI ignores a query parameter it does not declare, in
     * silence, so a handset updated ahead of the API would get the whole interview table back and
     * render it as one workshop's interviews — defect (2) reappearing as a deploy-order accident.
     * `FieldRepository.artisansForCraftsPage` sends `craftId` beside `craftIds` for the same reason.
     */
    @Test
    fun `a single workshop is also sent singularly for a server that predates the plural`() {
        runBlocking { repository(api()).interviews(listOf("w1")) }

        val url = onlyRequest()
        assertEquals("w1", url.queryParameter("workshopIds"))
        assertEquals("w1", url.queryParameter("workshopId"))
    }

    /**
     * TWO WORKSHOPS ARE NEVER COLLAPSED TO ONE. Sending the first id would answer a narrower question
     * than the one asked, with nothing on screen to say which workshop had been dropped — and against
     * a server that DOES understand the plural, both parameters narrow, so the first id would win and
     * the rest would silently vanish. A wide list is visibly wide; a silently narrowed one is not.
     */
    @Test
    fun `two workshops are never sent as the singular filter`() {
        runBlocking { repository(api()).interviews(listOf("w1", "w2")) }

        val url = onlyRequest()
        assertEquals("w1,w2", url.queryParameter("workshopIds"))
        assertNull(url.queryParameter("workshopId"))
    }

    /**
     * THE RESERVED SENTINEL IS NEVER SENT SINGULARLY. `workshopId=none` tests the column against the
     * literal string "none" and matches nothing whatsoever, rather than the interviews linked to no
     * workshop that the word exists to name — an empty screen where the answer was "these seven". The
     * plural still carries it, where `resolve_workshop_ids` knows what it means.
     */
    @Test
    fun `the unassigned sentinel goes out only in the plural`() {
        runBlocking { repository(api()).interviews(listOf("none")) }

        val url = onlyRequest()
        assertEquals("none", url.queryParameter("workshopIds"))
        assertNull(url.queryParameter("workshopId"))
    }

    @Test
    fun `a workshop plus the unassigned sentinel is sent whole in the plural`() {
        runBlocking { repository(api()).interviews(listOf("w1", "none")) }

        val url = onlyRequest()
        assertEquals("w1,none", url.queryParameter("workshopIds"))
        assertNull(url.queryParameter("workshopId"))
    }

    // -----------------------------------------------------------------------------------------
    // The sibling-save lookup, which must NOT be scoped
    // -----------------------------------------------------------------------------------------

    /**
     * THE ONE INTERVIEW READ THAT MUST STAY UNSCOPED, asserted so that a later "consistency" edit
     * cannot quietly scope it. `interviewsForArtisan` answers "every save ever made with this
     * artisan", which the View Data detail and the questionnaire form fold down to the sittings that
     * share an exact artisan set. The record has already been chosen by then; narrowing its own group
     * to whatever workshop the picker happens to be showing would hide the sittings the aggregation
     * exists to gather.
     *
     * It also pins the second half of the ceiling fix: the group used to be found by filtering the
     * newest hundred interviews in the WHOLE repository, so a sibling that had sorted off page one
     * took its answers and its recordings off the screen with it.
     */
    @Test
    fun `the sibling lookup asks by artisan and never by workshop`() {
        runBlocking { repository(api()).interviewsForArtisan("a1") }

        val url = onlyRequest()
        assertEquals("a1", url.queryParameter("artisanId"))
        assertNull(url.queryParameter("workshopIds"))
        assertNull(url.queryParameter("workshopId"))
    }

    // -----------------------------------------------------------------------------------------
    // The ceiling, and the sentence that admits it
    // -----------------------------------------------------------------------------------------

    /**
     * `interviewsPage` KEEPS `total`, which is the half that says whether the list is whole.
     *
     * Every questionnaire picker folds sittings together by artisan set before it draws them, so the
     * row count on screen is not the number of interviews and cannot be compared with anything. The
     * count has to be read from the envelope before the fold or there is no honest number left — and
     * without it a list cut at a hundred renders indistinguishably from a repository with nothing in
     * it. `listCutNotice` is the shared wording; this asserts the two are actually wired together.
     */
    @Test
    fun `the envelope survives so a truncated list can say so`() {
        val page = runBlocking { repository(api()).interviewsPage(listOf("w1")) }

        assertEquals(1, page.items.size)
        assertEquals(137, page.total)
        assertNotNull(
            "a page holding 1 of 137 interviews must produce a notice",
            listCutNotice(page.items.size, page.total, "interviews")
        )
        // And the complement: a list that IS whole says nothing, so the notice never becomes noise
        // a reader learns to skip past.
        assertNull(listCutNotice(loaded = 12, total = 12, noun = "interviews"))
    }
}
