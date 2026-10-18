package com.fieldrepository.app.data

/**
 * Artisan card codes: parse and format only. The server signs; this app never holds a key.
 *
 * QR payload `CXA1:<issuer><keyVersion>:<code>:<tag>`; short code `F-01234-56789-S5`. The rules are
 * `backend/app/services/artisan_card.py`'s and `frontend/lib/artisanCard.ts`'s, held together by
 * `shared/artisan-card-vectors.json` (`ArtisanCardCodeTest` reads that file). The designer app's
 * `ArtisanCardCode.kt` carries the same logic in its own package.
 *
 * `F` is this portal (the Field Repository), `D` the Design Prototype Workshop. A `D` card opens an
 * artisan here only through a link that portal made, which the register row carries as
 * `linkedCardCodes`.
 */
object ArtisanCardCode {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val PREFIX = "CXA1"
    /** This portal's letter. */
    const val OWN_ISSUER = 'F'
    private const val CODE_LENGTH = 10
    private const val TAG_LENGTH = 16
    private const val SHORT_LENGTH = 1 + CODE_LENGTH + 2

    const val NOT_A_CARD = "NOT_A_CARD"
    const val BAD_CHECK = "BAD_CHECK"
    const val BAD_ISSUER = "BAD_ISSUER"
    const val BAD_CHARACTER = "BAD_CHARACTER"

    /** The same words as the server's 422, so a mistyped code reads the same offline. */
    const val CHECK_MESSAGE = "Check the code. One character is wrong."
    const val NOT_A_CARD_MESSAGE = "That isn't an artisan card."

    data class Parsed(
        val issuer: Char,
        val code: String,
        val keyVersion: Int?,
        val tag: String?,
        val typed: Boolean
    ) {
        val shortCode: String get() = shortCode(issuer, code)
    }

    sealed interface Result {
        data class Ok(val card: Parsed) : Result
        data class Failed(val reason: String) : Result
    }

    private fun isCrockford(text: String) = text.all { it in ALPHABET }
    private fun mapConfusables(text: String) = text.replace('I', '1').replace('L', '1').replace('O', '0')
    private fun isIssuer(ch: Char) = ch == 'D' || ch == 'F'

    internal fun fnv1a32(text: String): Long {
        var hash = 0x811C9DC5L
        for (byte in text.toByteArray(Charsets.US_ASCII)) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash = (hash * 0x01000193L) and 0xFFFFFFFFL
        }
        return hash
    }

    /** The two check characters: FNV-1a 32-bit over `CXA1:<issuer>:<code>`, mod 1024. */
    fun check(issuer: Char, code: String): String {
        val value = (fnv1a32("$PREFIX:$issuer:$code") % 1024).toInt()
        return "${ALPHABET[value shr 5]}${ALPHABET[value and 31]}"
    }

    /** `F-01234-56789-S5`, what is printed under the QR. */
    fun shortCode(issuer: Char, code: String): String =
        "$issuer-${code.substring(0, 5)}-${code.substring(5)}-${check(issuer, code)}"

    /** A QR payload or a typed short code. An old `DPW1:` code, or anything else, is NOT_A_CARD. */
    fun parse(raw: String?): Result {
        if (raw == null) return Result.Failed(NOT_A_CARD)
        val cleaned = raw.uppercase().filterNot { it.isWhitespace() || it == '-' || it == '.' }
        if (cleaned.isEmpty()) return Result.Failed(NOT_A_CARD)
        if (cleaned.startsWith("$PREFIX:")) {
            val parts = cleaned.split(":")
            if (parts.size != 4 || parts[1].length != 2) return Result.Failed(NOT_A_CARD)
            val head = parts[1]
            if ('U' in parts[2] || 'U' in parts[3]) return Result.Failed(BAD_CHARACTER)
            val issuer = head[0]
            val version = head[1]
            if (!isIssuer(issuer)) return Result.Failed(BAD_ISSUER)
            if (version !in '1'..'9') return Result.Failed(NOT_A_CARD)
            val body = mapConfusables(parts[2])
            val tag = mapConfusables(parts[3])
            if (body.length != CODE_LENGTH || tag.length != TAG_LENGTH) return Result.Failed(NOT_A_CARD)
            if (!isCrockford(body) || !isCrockford(tag)) return Result.Failed(NOT_A_CARD)
            return Result.Ok(Parsed(issuer, body, version - '0', tag, typed = false))
        }
        if (':' in cleaned) return Result.Failed(NOT_A_CARD)
        if ('U' in cleaned) return Result.Failed(BAD_CHARACTER)
        val mapped = mapConfusables(cleaned)
        if (mapped.length != SHORT_LENGTH || !isCrockford(mapped)) return Result.Failed(NOT_A_CARD)
        val issuer = mapped[0]
        val body = mapped.substring(1, 1 + CODE_LENGTH)
        val given = mapped.substring(1 + CODE_LENGTH)
        if (!isIssuer(issuer)) return Result.Failed(BAD_ISSUER)
        if (check(issuer, body) != given) return Result.Failed(BAD_CHECK)
        return Result.Ok(Parsed(issuer, body, null, null, typed = true))
    }

    /** The sentence for a failed parse: a mistyped card, or not a card at all. */
    fun failureMessage(raw: String?, reason: String): String {
        val meantAsCard = reason == BAD_CHECK || reason == BAD_CHARACTER ||
            (reason == BAD_ISSUER && (raw ?: "").trim().uppercase().startsWith("$PREFIX:"))
        return if (meantAsCard) CHECK_MESSAGE else NOT_A_CARD_MESSAGE
    }

    // ─── The offline register ────────────────────────────────────────────────────────────────

    /**
     * The register row a card belongs to, from the rows this phone already holds: this portal's
     * card by the row's own `card.code`, a designer card by the row's `linkedCardCodes`. Null when
     * no row matches, which offline means "links when you're online".
     */
    fun matchInRegister(card: Parsed, rows: List<ArtisanDto>): ArtisanDto? =
        if (card.issuer == OWN_ISSUER) rows.firstOrNull { it.card?.code == card.code }
        else rows.firstOrNull { card.code in it.linkedCardCodes }

    // ─── Pending cards ("links on sync") ─────────────────────────────────────────────────────

    /**
     * A card scanned offline that no cached row matched is kept in the picker as `card:<shortCode>`
     * in place of an artisan id. The outbox sync resolves it before the record is sent
     * ([substitutePending]); a card the server refuses holds the entry with the server's sentence.
     */
    const val PENDING_PREFIX = "card:"

    fun pendingValue(card: Parsed): String = PENDING_PREFIX + card.shortCode

    fun isPending(value: String?): Boolean = value?.startsWith(PENDING_PREFIX) == true

    /** The chip under a picker holding a pending card. */
    fun pendingChip(value: String): String {
        val short = value.removePrefix(PENDING_PREFIX)
        return "Card ${short.take(7)}… links when you're online."
    }

    private val PENDING_IN_JSON = Regex("\"" + Regex.escape(PENDING_PREFIX) + "([DF]-[0-9A-Z]{5}-[0-9A-Z]{5}-[0-9A-Z]{2})\"")

    /** Every pending card a queued request body holds, as short codes. */
    fun pendingIn(json: String): Set<String> =
        PENDING_IN_JSON.findAll(json).map { it.groupValues[1] }.toSet()

    /** The body with each pending card replaced by the artisan id it resolved to. */
    fun substitutePending(json: String, resolved: Map<String, String>): String =
        PENDING_IN_JSON.replace(json) { match ->
            val id = resolved[match.groupValues[1]] ?: return@replace match.value
            "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
}
