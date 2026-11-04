package com.fieldrepository.app.data

import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

sealed interface ArtisanCardResolution {
    data class Found(val artisanId: String, val name: String, val place: String?, val offline: Boolean) : ArtisanCardResolution

    data class Replaced(val artisanId: String) : ArtisanCardResolution

    data class Pending(val card: ArtisanCardCode.Parsed) : ArtisanCardResolution {
        val value: String get() = ArtisanCardCode.pendingValue(card)
    }

    data class Refused(val message: String) : ArtisanCardResolution
}

fun cardWaitingToSync(card: ArtisanCardCode.Parsed, payloads: List<String>): Boolean =
    payloads.any { card.shortCode in ArtisanCardCode.pendingIn(it) }

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
        if (e.code() >= 500 || e.code() == 408 || e.code() == 401) ArtisanCardResolution.Pending(card)
        else ArtisanCardResolution.Refused(e.apiErrorMessage("No artisan matches this card."))
    } catch (_: IOException) {
        ArtisanCardResolution.Pending(card)
    }
}

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
