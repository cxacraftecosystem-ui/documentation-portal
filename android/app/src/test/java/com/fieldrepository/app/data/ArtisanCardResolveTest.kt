package com.fieldrepository.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * A scanned card, resolved: the register this phone holds first, then the server, and "links when
 * you're online" when neither answers. Then the outbox's half: a queued body's pending cards are
 * resolved and swapped in, or the entry is held with the server's sentence, never dropped.
 */
class ArtisanCardResolveTest {

    private val own = "F-01234-56789-S5"
    private val designer = "D-7K3QM-9XA2F-HQ"

    private fun row(id: String, code: String? = null, linked: List<String> = emptyList()) = ArtisanDto(
        id = id,
        name = "Artisan $id",
        place = "Bhuj",
        status = "APPROVED",
        card = code?.let { ArtisanCardCodesDto(it, ArtisanCardCode.shortCode('F', it), "t") },
        linkedCardCodes = linked
    )

    private val register = listOf(row("a1", code = "0123456789"), row("a2", linked = listOf("7K3QM9XA2F")))

    private fun http(code: Int, detail: String) = HttpException(
        Response.error<Any>(code, """{"detail":"$detail"}""".toResponseBody("application/json".toMediaTypeOrNull()))
    )

    private val noSignal: suspend (String) -> ResolveArtisanCardDto = { throw IOException("no route to host") }

    @Test
    fun `offline, a card opens from the register by its own code or by a link`() = runBlocking {
        assertEquals(
            ArtisanCardResolution.Found("a1", "Artisan a1", "Bhuj", offline = true),
            resolveArtisanCardWith(own, { register }, noSignal)
        )
        assertEquals("a2", (resolveArtisanCardWith(designer, { register }, noSignal) as ArtisanCardResolution.Found).artisanId)
    }

    @Test
    fun `offline and unknown, the card waits as pending`() = runBlocking {
        val result = resolveArtisanCardWith(ArtisanCardCode.shortCode('F', "ZZZZZZZZZZ"), { register }, noSignal)
        assertTrue(result is ArtisanCardResolution.Pending)
        assertEquals("card:F-ZZZZZ-ZZZZZ-" + ArtisanCardCode.check('F', "ZZZZZZZZZZ"), (result as ArtisanCardResolution.Pending).value)
        // A cache that cannot be read is the same as an empty one.
        assertTrue(resolveArtisanCardWith(own, { throw IOException("cache gone") }, noSignal) is ArtisanCardResolution.Pending)
    }

    @Test
    fun `online, the server answers what the register does not know`() = runBlocking {
        var asked = 0
        val found = resolveArtisanCardWith(ArtisanCardCode.shortCode('F', "ZZZZZZZZZZ"), { emptyList() }) {
            asked++
            ResolveArtisanCardDto(status = "FOUND", via = "LOCAL", artisan = CardArtisanDto(id = "a7", name = "Meena", place = "Nirona"))
        }
        assertEquals(ArtisanCardResolution.Found("a7", "Meena", "Nirona", offline = false), found)
        assertEquals(1, asked)
        val replaced = resolveArtisanCardWith(own, { emptyList() }) { ResolveArtisanCardDto(status = "REPLACED", artisanId = "a1") }
        assertEquals(ArtisanCardResolution.Replaced("a1"), replaced)
        val refused = resolveArtisanCardWith(designer, { emptyList() }) {
            throw http(404, "This card is from the Design Prototype Workshop and this artisan isn't linked here.")
        }
        assertEquals(
            ArtisanCardResolution.Refused("This card is from the Design Prototype Workshop and this artisan isn't linked here."),
            refused
        )
        assertTrue(resolveArtisanCardWith(own, { emptyList() }) { throw http(503, "down") } is ArtisanCardResolution.Pending)
    }

    @Test
    fun `a mistyped code never leaves the phone`() = runBlocking {
        var asked = 0
        val result = resolveArtisanCardWith("F-01234-56789-S4", { asked++; register }) { asked++; error("not called") }
        assertEquals(ArtisanCardResolution.Refused(ArtisanCardCode.CHECK_MESSAGE), result)
        assertEquals(0, asked)
    }

    @Test
    fun `the outbox swaps a resolved card into the queued body`() = runBlocking {
        val body = """{"artisanId":"card:$own","craftId":"c1"}"""
        val outcome = substitutePendingCards(body) { ArtisanCardResolution.Found("a1", "Artisan a1", "Bhuj", offline = false) }
        assertEquals(PendingCardOutcome.Resolved("""{"artisanId":"a1","craftId":"c1"}"""), outcome)
        // Nothing pending: the body goes out untouched and nobody is asked.
        assertEquals(PendingCardOutcome.Resolved("{}"), substitutePendingCards("{}") { error("not called") })
    }

    @Test
    fun `a refused card holds the entry with the sentence, and no signal waits`() = runBlocking {
        val body = """{"artisanIds":["card:$designer"]}"""
        val held = substitutePendingCards(body) { ArtisanCardResolution.Refused("No artisan matches this card.") }
        assertEquals(PendingCardOutcome.Held("Card $designer: No artisan matches this card."), held)
        val replaced = substitutePendingCards(body) { ArtisanCardResolution.Replaced("a2") }
        assertTrue(replaced is PendingCardOutcome.Held)
        val waiting = substitutePendingCards(body) { short ->
            ArtisanCardResolution.Pending((ArtisanCardCode.parse(short) as ArtisanCardCode.Result.Ok).card)
        }
        assertEquals(PendingCardOutcome.Retry, waiting)
    }
}
