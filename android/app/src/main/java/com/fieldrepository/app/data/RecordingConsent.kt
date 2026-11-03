package com.fieldrepository.app.data

import com.fieldrepository.app.questionnaire.data.QConsentGet
import com.fieldrepository.app.questionnaire.data.QConsentPost
import com.fieldrepository.app.questionnaire.data.QuestionnaireApi
import com.fieldrepository.app.questionnaire.data.SessionOutbox
import android.content.Context

class RecordingConsentClient(private val api: QuestionnaireApi) {
    suspend fun consent(id: String): QConsentGet = api.consent(id)

    suspend fun setConsent(id: String, granted: Boolean): QConsentGet {
        val body = QConsentPost(state = if (granted) "GRANTED" else "REFUSED", clientKey = SessionOutbox.newKey())
        return api.recordConsent(body.clientKey, id, body)
    }

    companion object {
        fun create(context: Context): RecordingConsentClient =
            RecordingConsentClient(
                ApiClient.retrofit(TokenStore(context.applicationContext)).create(QuestionnaireApi::class.java)
            )
    }
}

object RecordingConsentCopy {
    const val LABEL = "Recordings may be transcribed"
    const val DESCRIPTION = "On by default. Switch off if the artisan does not want their recordings sent to a transcription service."
    const val SAVE_FAILED = "Couldn't save. Try again."
    const val OWNER_ATTESTATION = "owner attestation"

    fun granted(consent: QConsentGet): Boolean = consent.state != "REFUSED"

    fun status(consent: QConsentGet, date: String?): String {
        if (consent.state != "GRANTED" && consent.state != "REFUSED") return "On by default"
        val head = if (consent.state == "GRANTED") "Agreed" else "Declined"
        val by = if (consent.source == OWNER_ATTESTATION) " · confirmed by the owner" else ""
        return head + (date?.let { " on $it" } ?: "") + by
    }
}

private val CONSENT_DAY = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.forLanguageTag("en-IN"))

fun consentDay(recordedAt: String?): String? {
    val raw = recordedAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val instant = runCatching { java.time.OffsetDateTime.parse(raw).toInstant() }.getOrNull()
        ?: runCatching { java.time.LocalDateTime.parse(raw).toInstant(java.time.ZoneOffset.UTC) }.getOrNull()
        ?: return runCatching { java.time.LocalDate.parse(raw.take(10)).format(CONSENT_DAY) }.getOrNull()
    return instant.atZone(Naming.IST).format(CONSENT_DAY)
}
