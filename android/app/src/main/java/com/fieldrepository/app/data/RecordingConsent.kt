package com.fieldrepository.app.data

import android.content.Context

/**
 * The consent rule on the handset: whether an artisan's recordings may be sent for transcription.
 * Consent is assumed by default and can be revoked (owner decision, 19 Oct 2026): the switch is ON
 * unless a refusal is on record, and switching it off records the refusal. The switch is
 * `ui/RecordingConsentRow.kt`; the web twin is `components/forms/RecordingConsentToggle.tsx`.
 *
 * ONE CONSENT STORE (19 Oct 2026): the switch reads and writes the artisan's row in the consent ledger
 * through `GET`/`POST /artisans/{id}/consent` ([QuestionnaireApi.consent] and
 * [QuestionnaireApi.recordConsent]), the same record the questionnaire's consent question answers.
 * Saved when pressed, never queued.
 */
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

/** The switch's words — the web's `RecordingConsentToggle.tsx`, verbatim. */
object RecordingConsentCopy {
    const val LABEL = "Recordings may be transcribed"
    const val DESCRIPTION = "On by default. Switch off if the artisan does not want their recordings sent to a transcription service."
    const val SAVE_FAILED = "Couldn't save. Try again."
    const val OWNER_ATTESTATION = "owner attestation"

    /** The switch's position: on unless the artisan refused. */
    fun granted(consent: QConsentGet): Boolean = consent.state != "REFUSED"

    /** "Agreed on 18 Oct 2026 · confirmed by the owner". [date] is already formatted. */
    fun status(consent: QConsentGet, date: String?): String {
        if (consent.state != "GRANTED" && consent.state != "REFUSED") return "On by default"
        val head = if (consent.state == "GRANTED") "Agreed" else "Declined"
        val by = if (consent.source == OWNER_ATTESTATION) " · confirmed by the owner" else ""
        return head + (date?.let { " on $it" } ?: "") + by
    }
}
