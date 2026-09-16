package com.fieldrepository.app.data

import com.fieldrepository.app.ui.artisanScopeNoun
import com.fieldrepository.app.ui.artisansNotAtWorkshop
import com.fieldrepository.app.ui.listCutNotice
import com.fieldrepository.app.ui.outOfWorkshopNotice
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
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

/**
 * WHICH ARTISANS THE QUESTIONNAIRE FORM OFFERS — the handset half of a contract the browser also owes.
 *
 * ── THE TWO DEFECTS PINNED HERE ─────────────────────────────────────────────────────────────────
 *
 * 1. **The two clients offered DIFFERENT PEOPLE for the same workshop.** `FieldRepository.artisansPage`
 *    was one request for one page, clamped to a hundred rows by `normalize_pagination`; the browser's
 *    `useWorkshopArtisans` has walked five pages since the scoping landed. A workshop with 140 linked
 *    artisans offered all 140 on a laptop and 100 on the phone standing inside it — and `/artisans` is
 *    ordered `createdAt desc`, so the hundred the handset kept were the NEWEST hundred and the cut fell
 *    somewhere different every day. Both clients printed an honest cut notice about it, which is why
 *    nobody caught it by reading a screen.
 *
 * 2. **A workshop change did different things to a selection already made.** The browser ran the
 *    ticked ids against the new workshop's roster and silently unticked whoever it did not hold; this
 *    client kept them and said nothing. One gesture, two clients, two different sets of
 *    `QuestionnaireInterviewArtisan` rows. Keeping won — `artisan_workshop_clause` counts *having sat
 *    in an interview taken at the workshop* as a link, and filing this very interview is what creates
 *    it, so a roster's silence about somebody is not evidence that ticking them was a mistake. What
 *    was missing on BOTH clients was the sentence, which is now `outOfWorkshopNotice` on both.
 *
 * ── WHY THE PAGING HALF IS ASSERTED AT THE URL ──────────────────────────────────────────────────
 *
 * The same reason `QuestionnaireScopeTest` gives for the scope: every way of getting "walk the pages"
 * wrong lives below the Kotlin signature — a `page` parameter left at its default, a loop that starts
 * at 1 and re-fetches page one, a budget applied to `total` instead of `pages`. A test that stopped at
 * "the function returns more rows" would pass through the middle one. So this drives a REAL
 * Retrofit-generated `FieldRepositoryApi` through an interceptor that records the URLs and answers
 * with canned pages, exactly as that suite does, and with the same `junit`-only dependency budget.
 *
 * ── THE WEB TWIN ────────────────────────────────────────────────────────────────────────────────
 *
 * `frontend/e2e/questionnaire-artisan-scope-unit.spec.ts` asserts the same rules against the same
 * strings — `ARTISAN_PAGE_BUDGET`, the three-way ruling, and the notice word for word. If a rule
 * changes in one suite and not the other, the clients have started disagreeing again.
 */
class QuestionnaireArtisanOfferTest {

    private val sent = mutableListOf<HttpUrl>()

    /**
     * Four pages of artisans, 2 rows to a page, 7 rows in total — so the last page is SHORT.
     *
     * DELIBERATELY SMALL AND DELIBERATELY UNEVEN. The numbers are what make the assertions readable —
     * a budget of 2 pages against `pages = 4` is a cut the suite can state exactly — the odd `total`
     * is what catches an implementation that reports the accumulated count as the total (which would
     * silence [listCutNotice] on a list that really is short), and the short final page is what
     * catches one that assumes every page is full.
     */
    private fun pageBody(page: Int): String {
        val first = (page - 1) * 2 + 1
        val ids = listOf(first, first + 1).filter { it <= 7 }
        val items = ids.joinToString(",") { """{"id":"a$it","name":"Artisan $it","place":"Bhuj","status":"APPROVED"}""" }
        return """{"items":[$items],"total":7,"page":$page,"pageSize":2,"pages":4}"""
    }

    /**
     * A real Retrofit client whose calls never leave the process — the seam `QuestionnaireScopeTest`
     * established, reused rather than re-invented. The base URL is a `.invalid` host so a broken
     * short-circuit fails with DNS rather than quietly reaching something real.
     */
    private fun api(): FieldRepositoryApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url
                synchronized(sent) { sent += url }
                val page = url.queryParameter("page")?.toIntOrNull() ?: 1
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(pageBody(page).toResponseBody("application/json".toMediaType()))
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

    /** See `QuestionnaireScopeTest.unopenedTokenStore` for why this exists and why it is safe here. */
    private fun repository(api: FieldRepositoryApi): FieldRepository {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return FieldRepository(api, allocate.invoke(unsafe, TokenStore::class.java) as TokenStore)
    }

    // -----------------------------------------------------------------------------------------
    // 1. The page walk
    // -----------------------------------------------------------------------------------------

    /**
     * THE ONE THAT WOULD HAVE FAILED BEFORE. `artisansPage` was a single `api.artisans(pageSize = 100)`
     * with no `page` argument at all, so a workshop past one page simply lost its tail on this client
     * while the browser walked five pages of it.
     */
    @Test
    fun `the picker's budget walks the pages and concatenates them in server order`() {
        val page = runBlocking { repository(api()).artisansPage(listOf("w1"), pageBudget = ARTISAN_PAGE_BUDGET) }

        // `pages = 4` is below the budget of 5, so all four are walked and nothing is left behind.
        assertEquals(listOf("a1", "a2", "a3", "a4", "a5", "a6", "a7"), page.items.map { it.id })
        // ORDER IS THE SERVER'S, END TO END. `/artisans` answers `createdAt desc` and neither client
        // re-sorts; concatenating in page order is what keeps that true across several requests.
        assertEquals("a1", page.items.first().id)
        assertEquals("a7", page.items.last().id)
    }

    @Test
    fun `every page after the first asks for its own page number, and page one is not asked twice`() {
        runBlocking { repository(api()).artisansPage(listOf("w1"), pageBudget = ARTISAN_PAGE_BUDGET) }

        val pages = sent.mapNotNull { it.queryParameter("page")?.toIntOrNull() }.sorted()
        assertEquals(listOf(1, 2, 3, 4), pages)
        // The scope rides on every one of them. A tail fetched unscoped would fold the whole
        // repository into a workshop's roster from page two onward — worse than not paging at all.
        assertTrue("every request carries the scope", sent.all { it.queryParameter("workshopIds") == "w1" })
    }

    /**
     * THE BUDGET IS A CEILING ON REQUESTS AND THE NOTICE IS WHAT COVERS THE REST. A budget of 2 against
     * `pages = 3` must stop at two requests, and `total` must survive as the SERVER'S number so the
     * sentence under the picker counts the rows that were not reached.
     */
    @Test
    fun `the budget bounds the walk, and the envelope still reports what was left behind`() {
        val page = runBlocking { repository(api()).artisansPage(listOf("w1"), pageBudget = 2) }

        assertEquals(listOf("a1", "a2", "a3", "a4"), page.items.map { it.id })
        assertEquals("the server's count, not the accumulated one", 7, page.total)
        assertEquals(2, sent.size)
        assertNotNull(
            "4 of 7 artisans loaded must produce a notice",
            listCutNotice(page.items.size, page.total, artisanScopeNoun("w1"))
        )
    }

    /**
     * EVERY OTHER CALLER IS UNCHANGED, and this is the assertion that keeps it that way.
     *
     * The budget defaults to one because parity is per surface: the browser's consolidated index asks
     * for one page and its repository-wide carry probe asks for one page, so raising the default would
     * fix one divergence by creating two — and would walk five pages of the whole artisan table at
     * every app start to widen `carryScope`'s reach behind the researcher's back.
     */
    @Test
    fun `the default budget is one request, exactly as before this change`() {
        val page = runBlocking { repository(api()).artisansPage(listOf("w1")) }

        assertEquals(1, sent.size)
        assertEquals(listOf("a1", "a2"), page.items.map { it.id })
        assertEquals(7, page.total)
    }

    /** No scope sends no parameter — "every artisan", the same meaning `resolve_workshop_ids` gives an absent one. */
    @Test
    fun `an empty scope sends no workshop parameter even while paging`() {
        runBlocking { repository(api()).artisansPage(emptyList(), pageBudget = ARTISAN_PAGE_BUDGET) }

        assertTrue("no request may name a workshop", sent.all { it.queryParameter("workshopIds") == null })
        assertEquals(4, sent.size)
    }

    /** The number itself, because the whole point is that it is the browser's number. */
    @Test
    fun `the budget is the same five the browser pages to`() {
        assertEquals(5, ARTISAN_PAGE_BUDGET)
        assertEquals(100, LIST_PAGE_CEILING)
    }

    // -----------------------------------------------------------------------------------------
    // 2. What a workshop change does to a selection already made
    // -----------------------------------------------------------------------------------------

    private val offered = listOf("a-ramesh", "a-sita")

    /**
     * The case: the form opens on last week's workshop, the researcher ticks two people, then corrects
     * the workshop to the one they are standing in. The tick rides along — deliberately, on both
     * clients now — and this is the sentence that stops it being a surprise when the record is read
     * back.
     */
    @Test
    fun `an artisan the workshop's complete roster does not hold is named`() {
        assertEquals(
            listOf("a-outsider"),
            artisansNotAtWorkshop(
                selectedIds = listOf("a-ramesh", "a-outsider"),
                offeredIds = offered,
                loadedForWorkshop = "w-2",
                workshopId = "w-2",
                cut = null
            )
        )
    }

    /**
     * NOTHING IS SAID BEFORE THIS WORKSHOP'S ROSTER HAS LANDED — and the same arm covers a FAILED
     * request, where `loadedForWorkshop` is left null on purpose. Without it the line would appear on
     * every mount and every workshop change for the length of a round trip, naming everybody, and a
     * warning that is usually wrong is one nobody reads.
     */
    @Test
    fun `nobody is named before this workshop's roster has landed, or after it failed`() {
        assertEquals(
            emptyList<String>(),
            artisansNotAtWorkshop(
                selectedIds = listOf("a-ramesh", "a-outsider"),
                offeredIds = offered,
                // Still the PREVIOUS workshop's answer.
                loadedForWorkshop = "w-1",
                workshopId = "w-2",
                cut = null
            )
        )
        assertEquals(
            emptyList<String>(),
            artisansNotAtWorkshop(
                selectedIds = listOf("a-ramesh", "a-outsider"),
                offeredIds = emptyList(),
                // The failure arm: nothing loaded, nothing claimed.
                loadedForWorkshop = null,
                workshopId = "w-2",
                cut = null
            )
        )
    }

    /**
     * AND NOBODY IS NAMED OFF A TRUNCATED ROSTER. An artisan absent from a list that stopped at the
     * page budget may be perfectly well linked here and simply past the cut; `listCutNotice` is already
     * on screen saying the list is short, and this sentence would be contradicting it.
     */
    @Test
    fun `nobody is named while the roster is cut`() {
        assertEquals(
            emptyList<String>(),
            artisansNotAtWorkshop(
                selectedIds = listOf("a-ramesh", "a-outsider"),
                offeredIds = offered,
                loadedForWorkshop = "w-2",
                workshopId = "w-2",
                cut = "Showing 500 of 640 artisans at this workshop — the other 140 are not on this list."
            )
        )
    }

    /** The names keep the researcher's own tick order, so the sentence reads the same on both clients. */
    @Test
    fun `the named ids keep the researcher's own order`() {
        assertEquals(
            listOf("a-outsider", "a-stranger"),
            artisansNotAtWorkshop(
                selectedIds = listOf("a-sita", "a-outsider", "a-stranger", "a-ramesh"),
                offeredIds = offered,
                loadedForWorkshop = "w-2",
                workshopId = "w-2",
                cut = null
            )
        )
    }

    // -----------------------------------------------------------------------------------------
    // 3. The sentence itself — word for word the browser's
    // -----------------------------------------------------------------------------------------

    @Test
    fun `says nothing when there is nothing to say`() {
        assertNull(outOfWorkshopNotice(emptyList()))
        assertNull(outOfWorkshopNotice(listOf("", "   ")))
    }

    @Test
    fun `names one artisan, and does not read as an accusation`() {
        val line = outOfWorkshopNotice(listOf("Ramesh Kumar"))
        assertNotNull(line)
        assertTrue(line!!.contains("Ramesh Kumar is not recorded at this workshop yet"))
        assertTrue(line.contains("They stay ticked"))
        // The whole point of the rule: saving is what creates the link, so the sentence says so rather
        // than telling a researcher they have made a mistake they have not made.
        assertTrue(line.contains("saving this interview here is what links them to it"))
    }

    @Test
    fun `names several, in the order given`() {
        assertTrue(outOfWorkshopNotice(listOf("Sita Devi", "Ramesh Kumar"))!!.contains("Sita Devi, Ramesh Kumar are"))
    }

    /** A selection of thirty must not print a paragraph. */
    @Test
    fun `caps the list and counts the remainder`() {
        val line = outOfWorkshopNotice(listOf("A", "B", "C", "D", "E", "F"))!!
        assertTrue(line.contains("A, B, C, D and 2 more are"))
    }

    /** The noun names the SCOPE and not just the record type, on both clients. */
    @Test
    fun `the cut sentence's noun follows the scope`() {
        assertEquals("artisans at this workshop", artisanScopeNoun("w-2"))
        assertEquals("artisans", artisanScopeNoun(""))
    }
}
