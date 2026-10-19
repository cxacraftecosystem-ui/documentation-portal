package com.fieldrepository.app.data

import android.content.Context

/**
 * The consent rule (18 Oct 2026) on the handset: whether an artisan's recordings may be sent for
 * transcription. Without it a recording is kept and simply not sent. The switch is
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
    const val DESCRIPTION = "The artisan agrees their recordings may be sent to a transcription service."
    const val SAVE_FAILED = "Couldn't save. Try again."
    const val OWNER_ATTESTATION = "owner attestation"

    fun granted(consent: QConsentGet): Boolean = consent.state == "GRANTED"

    /** "Agreed on 18 Oct 2026 · confirmed by the owner". [date] is already formatted. */
    fun status(consent: QConsentGet, date: String?): String {
        if (consent.state != "GRANTED" && consent.state != "REFUSED") return "Not asked yet"
        val head = if (granted(consent)) "Agreed" else "Declined"
        val by = if (consent.source == OWNER_ATTESTATION) " · confirmed by the owner" else ""
        return head + (date?.let { " on $it" } ?: "") + by
    }
}
