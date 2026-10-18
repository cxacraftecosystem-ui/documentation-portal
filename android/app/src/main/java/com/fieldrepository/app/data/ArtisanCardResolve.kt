package com.fieldrepository.app.data

import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

/** What a scanned or typed artisan card came to. */
sealed interface ArtisanCardResolution {
    /** The artisan. [offline] when it was matched against the register this phone already holds. */
    data class Found(val artisanId: String, val name: String, val place: String?, val offline: Boolean) : ArtisanCardResolution

    /** The card was revoked; [artisanId] is the artisan it belonged to. */
    data class Replaced(val artisanId: String) : ArtisanCardResolution

    /** No signal and no cached row: kept as [ArtisanCardCode.pendingValue] until the phone is online. */
    data class Pending(val card: ArtisanCardCode.Parsed) : ArtisanCardResolution {
        val value: String get() = ArtisanCardCode.pendingValue(card)
    }

    /** A sentence to show: a mistyped code, or the server's answer to a miss. */
    data class Refused(val message: String) : ArtisanCardResolution
}

/**
 * The card rule, pure apart from its two lookups so `ArtisanCardResolveTest` can drive it:
 *
 * 1. parse (a bad check never leaves the phone);
 * 2. the register this phone already holds ([cachedRows]), so a card opens with no signal;
 * 3. the server ([online]), which knows revoked cards and every artisan this phone has not seen;
 * 4. no answer at all → [ArtisanCardResolution.Pending], so a picker can link it on sync.
 */
suspend fun resolveArtisanCardWith(
    input: String,
    cachedRows: suspend () -> List<ArtisanDto>,
    online: suspend (String) -> ResolveArtisanCardDto
): ArtisanCardResolution {
    val card = when (val parsed = ArtisanCardCode.parse(input)) {
        is ArtisanCardCode.Result.Ok -> parsed.card
        is ArtisanCardCode.Result.Failed ->
            return ArtisanCardResolution.Refused(ArtisanCardCode.failureMessage(input, parsed.reason))
    }
    val cached = try {
        cachedRows()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        emptyList()
    }
    ArtisanCardCode.matchInRegister(card, cached)?.let { row ->
        return ArtisanCardResolution.Found(row.id, row.name, row.place, offline = true)
    }
    return try {
        val answer = online(input.trim())
        when {
            answer.status == "FOUND" && answer.artisan != null ->
                ArtisanCardResolution.Found(answer.artisan.id, answer.artisan.name, answer.artisan.place, offline = false)
            answer.status == "REPLACED" && answer.artisanId != null -> ArtisanCardResolution.Replaced(answer.artisanId)
            else -> ArtisanCardResolution.Refused("No artisan matches this card.")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        if (e.code() >= 500 || e.code() == 408) ArtisanCardResolution.Pending(card)
        else ArtisanCardResolution.Refused(e.apiErrorMessage("No artisan matches this card."))
    } catch (_: IOException) {
        ArtisanCardResolution.Pending(card)
    }
}

/**
 * The pending cards in one queued body, resolved for the outbox sync. `null` values in the result
 * mean "still no signal" (stop the pass); a [ArtisanCardResolution.Refused] holds the entry.
 */
sealed interface PendingCardOutcome {
    data class Resolved(val payloadJson: String) : PendingCardOutcome
    data class Held(val reason: String) : PendingCardOutcome
    data object Retry : PendingCardOutcome
}

suspend fun substitutePendingCards(
    payloadJson: String,
    resolve: suspend (String) -> ArtisanCardResolution
): PendingCardOutcome {
    val pending = ArtisanCardCode.pendingIn(payloadJson)
    if (pending.isEmpty()) return PendingCardOutcome.Resolved(payloadJson)
    val ids = mutableMapOf<String, String>()
    for (short in pending) {
        when (val outcome = resolve(short)) {
            is ArtisanCardResolution.Found -> ids[short] = outcome.artisanId
            is ArtisanCardResolution.Pending -> return PendingCardOutcome.Retry
            is ArtisanCardResolution.Replaced ->
                return PendingCardOutcome.Held("Card $short was replaced. Pick the artisan again.")
            is ArtisanCardResolution.Refused -> return PendingCardOutcome.Held("Card $short: ${outcome.message}")
        }
    }
    return PendingCardOutcome.Resolved(ArtisanCardCode.substitutePending(payloadJson, ids))
}
