package com.fieldrepository.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The top-bar scanner's one dispatcher: what a scanned or typed code is, and what pressing its
 * button does. IDENTICAL in both apps apart from the `package` line, and the twin of the web's
 * `lib/scanDispatch.ts` (same outcome names, order and copy). Both are held to the shared fixture
 * `scan-dispatch.json` (REQUIREMENTS_2026-10-20 §6).
 *
 * Order, fixed: DPW2 join card, Field Repository session, artisan card, DPW1, DPW3, refusal.
 *
 * It never navigates; an outcome names what was found and offers at most one action. A refusal
 * never echoes the input (an Aadhaar Secure QR, a payment QR or an authenticator link gets one fixed
 * sentence and none of its contents are kept or returned). A join card's secret travels only inside
 * the action, never in a title or a detail line.
 */

enum class ScanPortal(val wire: String) { DESIGNER("designer"), FIELD("field") }

enum class ScanOutcomeKind {
    REDEEM_JOIN_CARD,
    JOIN_SESSION,
    OPEN,
    ADD_FROM_PEER,
    JOIN_WORKSHOP,
    POINTER,
    REFUSED,
    NOT_FOUND,
    OFFLINE,
}

/** Every sentence the dispatcher says. Identical in both apps and on the web. */
object ScanCopy {
    const val JOIN_CARD = "A workshop join card."
    const val REDEEM = "Redeem"
    const val JOINED = "You're in."
    const val OPEN_WORKSHOP = "Open workshop"
    const val JOIN_CARD_ELSEWHERE = "This card opens a Designer Repository workshop."
    const val SESSION = "A questionnaire session."
    const val JOIN_SESSION = "Join"
    const val SESSION_ELSEWHERE = "This is a Field Repository session."
    const val OPEN = "Open"
    const val CARD_REPLACED = "This card was replaced."
    const val FROM_PEER = "From the Field Repository."
    const val ADD_ARTISAN = "Add artisan"
    const val ASK_TO_JOIN = "Ask to join"
    const val THIS_WORKSHOP = "this workshop"
    const val PROTOTYPE = "A prototype tag. Open the workshop's Cards & tags."
    const val FILE_CHECK = "A file check code. Open the received file in Questionnaires to check it."
    const val DESIGNER_CODE = "This code is from the Designer Repository."
    const val UNKNOWN = "This code isn't one this app reads."
    const val NOT_FOUND = "No record matches this code."
    const val OFFLINE = "You're offline. Try again when connected."
    const val EMPTY = "Scan or type a code."
    const val SCAN_A_CODE = "Scan a code"
}

/** The heading word per record type (wire name), the web's `SCAN_RECORD_LABEL` verbatim. */
val SCAN_RECORD_LABEL: Map<String, String> = mapOf(
    "artisan" to "Artisan",
    "craft" to "Craft",
    "workshop" to "Workshop",
    "product" to "Product",
    "process" to "Process",
    "tool" to "Tool",
    "questionnaire" to "Interview",
    "media" to "Media file",
    "designWorkshop" to "Design workshop",
    "prototype" to "Prototype",
)

/** The web's read-only record pages (`lib/recordView.ts`), by wire name. */
private val RECORD_VIEW_BASE: Map<String, String> = mapOf(
    "artisan" to "/artisans",
    "craft" to "/crafts",
    "workshop" to "/workshops",
    "product" to "/products",
    "process" to "/processes",
    "tool" to "/tools",
    "questionnaire" to "/questionnaire/interviews",
    "media" to "/media",
)

/** Where an action lands. [href] is the web route for the same place, so the fixture holds both clients. */
sealed interface ScanTarget {
    val href: String

    data class Record(val recordType: String, val id: String) : ScanTarget {
        override val href: String get() = "${RECORD_VIEW_BASE[recordType] ?: "/$recordType"}/${uriComponent(id)}"
    }

    data class AddFromPeer(val card: String) : ScanTarget {
        override val href: String get() = "/artisans/new?card=${uriComponent(card)}"
    }

    data class DesignWorkshop(val id: String) : ScanTarget {
        override val href: String get() = "/design-workshops/${uriComponent(id)}"
    }

    data object DesignWorkshops : ScanTarget {
        override val href: String get() = "/design-workshops"
    }

    data object Questionnaires : ScanTarget {
        override val href: String get() = "/questionnaires"
    }

    data class Session(val id: String) : ScanTarget {
        override val href: String get() = "/questionnaire/sessions/${uriComponent(id)}"
    }
}

/** A Field Repository session QR: bare v1 JSON, or `FRS1:` + the same JSON (`JoinQr` in the field app). */
data class ScanSessionQr(
    val clientKey: String,
    val questionnaireId: String,
    val workshopId: String?,
    val sessionDate: String,
    val participants: List<String>,
    /** The payload as read, without any `FRS1:` prefix — what the field app's `JoinQr.decode` takes. */
    val json: String,
)

const val SESSION_QR_PREFIX = "FRS1:"

sealed interface ScanAction {
    val label: String

    data class Open(override val label: String, val target: ScanTarget) : ScanAction
    data class Add(override val label: String, val target: ScanTarget) : ScanAction
    data class Redeem(override val label: String, val workshopId: String, val code: String) : ScanAction {
        override fun toString(): String = "Redeem(workshopId=$workshopId)"
    }
    data class JoinSession(override val label: String, val joinCode: String?, val qr: ScanSessionQr?) : ScanAction
    data class RequestAccess(override val label: String, val workshopId: String, val scannedCode: String) : ScanAction
}

data class ScanOutcome(
    val kind: ScanOutcomeKind,
    val title: String,
    val detail: String? = null,
    val action: ScanAction? = null,
)

/** Designer-only decoders: the DPW grammars live only in the designer app. */
interface ScanCodecs {
    fun decodeJoinCard(raw: String): ScanDecoded.JoinCard
    fun decodeRecordCode(raw: String): ScanDecoded.RecordCode
}

sealed interface ScanDecoded {
    sealed interface JoinCard : ScanDecoded
    sealed interface RecordCode : ScanDecoded
    data class JoinCardOk(val workshopId: String, val code: String) : JoinCard {
        override fun toString(): String = "JoinCardOk(workshopId=$workshopId)"
    }
    data class RecordCodeOk(val recordType: String, val id: String, val code: String) : RecordCode
    data class Refused(val message: String) : JoinCard, RecordCode
}

/** How an artisan card reads, by the app's own `ArtisanCardCode`. */
enum class ScanCardKind { VALID, MISTYPED, NONE }

sealed interface ScanCardAnswer {
    data class Found(val artisanId: String, val name: String, val detail: String? = null) : ScanCardAnswer
    data class Replaced(val artisanId: String) : ScanCardAnswer
    /** A card from the other portal with no linked artisan here. */
    data object Peer : ScanCardAnswer
    data class Refused(val message: String) : ScanCardAnswer
    data object Offline : ScanCardAnswer
}

sealed interface ScanRecordAnswer {
    data class Found(val label: String, val detail: String? = null) : ScanRecordAnswer
    data object Missing : ScanRecordAnswer
    data object Offline : ScanRecordAnswer
}

interface ScanServices {
    val portal: ScanPortal
    val codecs: ScanCodecs?
    fun cardKind(raw: String): ScanCardKind
    suspend fun resolveCard(raw: String): ScanCardAnswer
    suspend fun lookUpRecord(recordType: String, id: String): ScanRecordAnswer
}

/** What the pure half decided: a final outcome, or the one lookup still owed. */
sealed interface ScanStep {
    data class Final(val outcome: ScanOutcome) : ScanStep
    data class Card(val raw: String) : ScanStep
    data class Record(val recordType: String, val id: String, val code: String) : ScanStep
}

private fun refused(title: String): ScanStep = ScanStep.Final(ScanOutcome(ScanOutcomeKind.REFUSED, title))

private val DPW = Regex("^DPW\\d*:")
private val JOIN_CARD = Regex("^DPW\\d+:J:")
private val FILE_CHECK = Regex("^DPW3:H:")
private val SESSION_CODE = Regex("^[A-Z0-9]{6}$")
private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = false }

/** A typed 6-character session code, normalised (upper case, no spaces or hyphens), or null. */
fun normaliseSessionCode(raw: String): String? =
    raw.uppercase().filter { !it.isWhitespace() && it != '-' }.takeIf { SESSION_CODE.matches(it) }

/** A session QR's payload, or null for anything else. A missing `v` reads as 1. */
fun decodeSessionQr(raw: String): ScanSessionQr? {
    var text = raw.trim()
    if (text.uppercase().startsWith(SESSION_QR_PREFIX)) text = text.substring(SESSION_QR_PREFIX.length).trim()
    if (!text.startsWith("{")) return null
    val obj = runCatching { lenientJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    fun str(key: String): String = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim().orEmpty()
    val version = obj["v"]
    if (version != null && version !is JsonNull && (version as? JsonPrimitive)?.intOrNull != 1) return null
    if (str("clientKey").isEmpty() || str("questionnaireId").isEmpty() || str("sessionDate").isEmpty()) return null
    val participants = (obj["participants"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        .orEmpty()
    return ScanSessionQr(
        clientKey = str("clientKey"),
        questionnaireId = str("questionnaireId"),
        workshopId = str("workshopId").ifEmpty { null },
        sessionDate = str("sessionDate"),
        participants = participants,
        json = text,
    )
}

/** The pure half: classify in the fixed order. No network, no storage. */
fun classifyScan(raw: String, portal: ScanPortal, codecs: ScanCodecs?, cardKind: (String) -> ScanCardKind): ScanStep {
    val text = raw.trim()
    if (text.isEmpty()) return refused(ScanCopy.EMPTY)
    val upper = text.filterNot { it.isWhitespace() }.uppercase()

    // 1. DPW2 join card.
    if (JOIN_CARD.containsMatchIn(upper)) {
        if (portal == ScanPortal.FIELD || codecs == null) return refused(ScanCopy.JOIN_CARD_ELSEWHERE)
        return when (val card = codecs.decodeJoinCard(text)) {
            is ScanDecoded.Refused -> refused(card.message)
            is ScanDecoded.JoinCardOk -> ScanStep.Final(
                ScanOutcome(
                    ScanOutcomeKind.REDEEM_JOIN_CARD,
                    ScanCopy.JOIN_CARD,
                    action = ScanAction.Redeem(ScanCopy.REDEEM, card.workshopId, card.code),
                ),
            )
        }
    }

    // 2. Field Repository session: the QR (bare or FRS1:) or a typed 6-character code.
    val qr = decodeSessionQr(text)
    val joinCode = if (qr == null) normaliseSessionCode(text) else null
    if (qr != null || joinCode != null) {
        if (portal == ScanPortal.DESIGNER) return refused(ScanCopy.SESSION_ELSEWHERE)
        return ScanStep.Final(
            ScanOutcome(
                ScanOutcomeKind.JOIN_SESSION,
                ScanCopy.SESSION,
                action = ScanAction.JoinSession(ScanCopy.JOIN_SESSION, joinCode, qr),
            ),
        )
    }

    // 3. Artisan card, either portal's.
    when (cardKind(text)) {
        ScanCardKind.VALID -> return ScanStep.Card(text)
        ScanCardKind.MISTYPED -> return refused(ArtisanCardCode.CHECK_MESSAGE)
        ScanCardKind.NONE -> Unit
    }

    // 5 before 4 only because the DPW1 decoder would read DPW3 as "a newer format".
    if (FILE_CHECK.containsMatchIn(upper)) {
        if (portal == ScanPortal.FIELD) return refused(ScanCopy.DESIGNER_CODE)
        return ScanStep.Final(
            ScanOutcome(
                ScanOutcomeKind.POINTER,
                ScanCopy.FILE_CHECK,
                action = ScanAction.Open(ScanCopy.OPEN, ScanTarget.Questionnaires),
            ),
        )
    }

    // 4. DPW1 record codes.
    if (DPW.containsMatchIn(upper)) {
        if (portal == ScanPortal.FIELD || codecs == null) return refused(ScanCopy.DESIGNER_CODE)
        return when (val ref = codecs.decodeRecordCode(text)) {
            is ScanDecoded.Refused -> refused(ref.message)
            is ScanDecoded.RecordCodeOk ->
                if (ref.recordType == "prototype") {
                    ScanStep.Final(
                        ScanOutcome(
                            ScanOutcomeKind.POINTER,
                            ScanCopy.PROTOTYPE,
                            action = ScanAction.Open(ScanCopy.OPEN, ScanTarget.DesignWorkshops),
                        ),
                    )
                } else {
                    ScanStep.Record(ref.recordType, ref.id, ref.code)
                }
        }
    }

    // 6. Anything else: an Aadhaar Secure QR, otpauth://, upi://, a URL. One sentence, nothing kept.
    return refused(ScanCopy.UNKNOWN)
}

/** Classify, then run the one lookup the code owes. A result for every input; never a throw. */
suspend fun dispatchScan(raw: String, services: ScanServices): ScanOutcome {
    when (val step = classifyScan(raw, services.portal, services.codecs, services::cardKind)) {
        is ScanStep.Final -> return step.outcome
        is ScanStep.Card -> {
            val answer = try {
                services.resolveCard(step.raw)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ScanCardAnswer.Offline
            }
            return when (answer) {
                is ScanCardAnswer.Found -> ScanOutcome(
                    ScanOutcomeKind.OPEN,
                    "${SCAN_RECORD_LABEL.getValue("artisan")}: ${answer.name}",
                    answer.detail,
                    ScanAction.Open(ScanCopy.OPEN, ScanTarget.Record("artisan", answer.artisanId)),
                )
                is ScanCardAnswer.Replaced -> ScanOutcome(
                    ScanOutcomeKind.OPEN,
                    ScanCopy.CARD_REPLACED,
                    action = ScanAction.Open(ScanCopy.OPEN, ScanTarget.Record("artisan", answer.artisanId)),
                )
                ScanCardAnswer.Peer ->
                    if (services.portal == ScanPortal.FIELD) {
                        ScanOutcome(ScanOutcomeKind.REFUSED, ScanCopy.NOT_FOUND)
                    } else {
                        ScanOutcome(
                            ScanOutcomeKind.ADD_FROM_PEER,
                            ScanCopy.FROM_PEER,
                            action = ScanAction.Add(ScanCopy.ADD_ARTISAN, ScanTarget.AddFromPeer(step.raw)),
                        )
                    }
                is ScanCardAnswer.Refused -> ScanOutcome(ScanOutcomeKind.REFUSED, answer.message)
                ScanCardAnswer.Offline -> ScanOutcome(ScanOutcomeKind.OFFLINE, ScanCopy.OFFLINE)
            }
        }
        is ScanStep.Record -> {
            val answer = try {
                services.lookUpRecord(step.recordType, step.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ScanRecordAnswer.Offline
            }
            return when (answer) {
                ScanRecordAnswer.Offline -> ScanOutcome(ScanOutcomeKind.OFFLINE, ScanCopy.OFFLINE)
                ScanRecordAnswer.Missing ->
                    if (step.recordType == "designWorkshop") {
                        ScanOutcome(
                            ScanOutcomeKind.JOIN_WORKSHOP,
                            "${ScanCopy.ASK_TO_JOIN} ${ScanCopy.THIS_WORKSHOP}",
                            action = ScanAction.RequestAccess(ScanCopy.ASK_TO_JOIN, step.id, step.code),
                        )
                    } else {
                        ScanOutcome(ScanOutcomeKind.NOT_FOUND, ScanCopy.NOT_FOUND)
                    }
                is ScanRecordAnswer.Found -> ScanOutcome(
                    ScanOutcomeKind.OPEN,
                    "${SCAN_RECORD_LABEL[step.recordType] ?: step.recordType}: ${answer.label}",
                    answer.detail,
                    ScanAction.Open(
                        ScanCopy.OPEN,
                        if (step.recordType == "designWorkshop") ScanTarget.DesignWorkshop(step.id) else ScanTarget.Record(step.recordType, step.id),
                    ),
                )
            }
        }
    }
}

/** JavaScript's `encodeURIComponent`, so a target's [ScanTarget.href] matches the web's byte for byte. */
internal fun uriComponent(value: String): String {
    val keep = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
    val out = StringBuilder()
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val c = byte.toInt().toChar()
        if (byte >= 0 && c in keep) out.append(c) else out.append('%').append("%02X".format(byte.toInt() and 0xFF))
    }
    return out.toString()
}
