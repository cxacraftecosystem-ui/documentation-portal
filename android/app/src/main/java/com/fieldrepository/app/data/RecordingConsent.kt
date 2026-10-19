package com.fieldrepository.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * The consent rule (18 Oct 2026) on the handset: whether an artisan's recordings may be sent for
 * transcription. Without it a recording is kept and simply not sent. The switch is
 * `ui/RecordingConsentRow.kt`; the web twin is `components/forms/RecordingConsentToggle.tsx`; the
 * server is `backend/app/api/routes/recording_consent.py`. Saved when pressed, never queued.
 */
@Serializable
data class RecordingConsentDto(
    val decision: String = "NOT_RECORDED",
    val recorded: Boolean = false,
    val granted: Boolean = false,
    val source: String? = null,
    val attestedAt: String? = null,
)

@Serializable
data class RecordingConsentRequest(val decision: String)

interface RecordingConsentApi {
    @GET("artisans/{id}/recording-consent")
    suspend fun consent(@Path("id") id: String): RecordingConsentDto

    @PUT("artisans/{id}/recording-consent")
    suspend fun setConsent(@Path("id") id: String, @Body body: RecordingConsentRequest): RecordingConsentDto
}

class RecordingConsentClient(private val api: RecordingConsentApi) {
    suspend fun consent(id: String): RecordingConsentDto = api.consent(id)
    suspend fun setConsent(id: String, granted: Boolean): RecordingConsentDto =
        api.setConsent(id, RecordingConsentRequest(if (granted) "GRANTED" else "REFUSED"))

    companion object {
        fun create(context: Context): RecordingConsentClient =
            RecordingConsentClient(
                ApiClient.retrofit(TokenStore(context.applicationContext)).create(RecordingConsentApi::class.java)
            )
    }
}

/** The switch's words — the web's `RecordingConsentToggle.tsx`, verbatim. */
object RecordingConsentCopy {
    const val LABEL = "Recordings may be transcribed"
    const val DESCRIPTION = "The artisan agrees their recordings may be sent to a transcription service."
    const val SAVE_FAILED = "Couldn't save. Try again."
    const val OWNER_ATTESTATION = "owner attestation"

    /** "Agreed on 18 Oct 2026 · confirmed by the owner". [date] is already formatted. */
    fun status(consent: RecordingConsentDto, date: String?): String {
        if (!consent.recorded) return "Not asked yet"
        val head = if (consent.granted) "Agreed" else "Declined"
        val by = if (consent.source == OWNER_ATTESTATION) " · confirmed by the owner" else ""
        return head + (date?.let { " on $it" } ?: "") + by
    }
}
