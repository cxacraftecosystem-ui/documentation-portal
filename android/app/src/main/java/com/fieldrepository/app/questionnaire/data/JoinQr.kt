package com.fieldrepository.app.questionnaire.data

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

@Serializable
data class JoinQrPayload(
    val v: Int = 1,
    val clientKey: String,
    val questionnaireId: String,
    val workshopId: String? = null,
    val sessionDate: String,
    val participants: List<String> = emptyList()
)

object JoinQr {
    const val VERSION = 1
    private val CODE_ALPHABET = Regex("^[A-Z0-9]{6}$")

    fun encode(payload: JoinQrPayload): String = outboxJson.encodeToString(JoinQrPayload.serializer(), payload.copy(v = VERSION))

    fun decode(text: String): JoinQrPayload? {
        val payload = runCatching { outboxJson.decodeFromString(JoinQrPayload.serializer(), text.trim()) }.getOrNull() ?: return null
        if (payload.v != VERSION || payload.clientKey.isBlank() || payload.questionnaireId.isBlank() || payload.sessionDate.isBlank()) return null
        return payload
    }

    fun normaliseCode(typed: String): String? = typed.uppercase().filter { !it.isWhitespace() && it != '-' }.takeIf { CODE_ALPHABET.matches(it) }

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

    fun read(argb: IntArray, width: Int, height: Int): String? = runCatching {
        val source = RGBLuminanceSource(width, height, argb)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text
    }.getOrNull()

    fun readLuminance(y: ByteArray, rowStride: Int, width: Int, height: Int): String? = runCatching {
        val source = com.google.zxing.PlanarYUVLuminanceSource(y, rowStride, height, 0, 0, width, height, false)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull()
}
