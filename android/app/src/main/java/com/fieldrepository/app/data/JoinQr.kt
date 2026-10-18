package com.fieldrepository.app.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Joining a session (DESIGN §3.7). The 6-character code works online only (`POST /sessions/join`).
 * The QR code also works OFFLINE: it carries the session's `clientKey` and what a second phone needs
 * to create the same session locally. When that phone's outbox syncs, `POST /sessions` with the same
 * `clientKey` answers with the existing row, so two phones end up in one session.
 */
@Serializable
data class JoinQrPayload(
    val v: Int = 1,
    val clientKey: String,
    val questionnaireId: String,
    val workshopId: String? = null,
    val sessionDate: String,
    /** Server artisan ids, or an offline-registered artisan's local id. */
    val participants: List<String> = emptyList()
)

object JoinQr {
    const val VERSION = 1
    private val CODE_ALPHABET = Regex("^[A-Z0-9]{6}$")

    fun encode(payload: JoinQrPayload): String = outboxJson.encodeToString(JoinQrPayload.serializer(), payload.copy(v = VERSION))

    /** Null for anything that is not a v1 join payload (another app's QR, a URL, a newer version). */
    fun decode(text: String): JoinQrPayload? {
        val payload = runCatching { outboxJson.decodeFromString(JoinQrPayload.serializer(), text.trim()) }.getOrNull() ?: return null
        if (payload.v != VERSION || payload.clientKey.isBlank() || payload.questionnaireId.isBlank() || payload.sessionDate.isBlank()) return null
        return payload
    }

    /** A typed join code, normalised: upper case, no spaces; null when it cannot be one. */
    fun normaliseCode(typed: String): String? = typed.uppercase().filter { !it.isWhitespace() && it != '-' }.takeIf { CODE_ALPHABET.matches(it) }

    /** The session create a scanning phone queues: same clientKey, so the server returns one session. */
    fun sessionCreate(payload: JoinQrPayload, me: String?, now: Instant = Instant.now()): QSessionCreate = QSessionCreate(
        clientKey = payload.clientKey,
        questionnaireId = payload.questionnaireId,
        workshopId = payload.workshopId,
        sessionDate = payload.sessionDate,
        startedAt = now.toString(),
        participants = payload.participants.map { QArtisanRef(it) },
        researchers = listOfNotNull(me?.let { QResearcherRef(it, "CONTRIBUTOR") })
    )

    fun matrix(text: String, size: Int = 512): BitMatrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8")
    )

    /** Read a QR from ARGB pixels (a photo or a picked image). Null when no QR is found. */
    fun read(argb: IntArray, width: Int, height: Int): String? = runCatching {
        val source = RGBLuminanceSource(width, height, argb)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text
    }.getOrNull()
}
