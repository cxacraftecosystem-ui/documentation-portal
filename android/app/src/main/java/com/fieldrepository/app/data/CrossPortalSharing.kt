package com.fieldrepository.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * C27 on the handset — the artisan's "Share with the Designer Portal" agreement, the wire half.
 * The switch is `ui/SharingConsentRow.kt`; the server is `backend/app/api/routes/cross_portal.py`.
 *
 * Hidden unless `GET /cross-portal/status` says the feature is on (it is off, and every route 404s,
 * until both portals carry the flag and the two shared secrets). A setting saved the moment it is
 * pressed, and shown ON only once the server says so — never queued, because a consent recorded
 * later than it was given is a different record.
 */
@Serializable
data class CrossPortalStatusDto(val enabled: Boolean = false)

@Serializable
data class SharingConsentDto(
    val granted: Boolean = false,
    val recorded: Boolean = false,
    val recordedAt: String? = null,
    val linkedRecords: Int = 0,
)

@Serializable
data class SharingConsentRequest(val granted: Boolean)

interface CrossPortalSharingApi {
    @GET("cross-portal/status")
    suspend fun status(): CrossPortalStatusDto

    @GET("cross-portal/artisans/{id}/consent")
    suspend fun consent(@Path("id") id: String): SharingConsentDto

    @PUT("cross-portal/artisans/{id}/consent")
    suspend fun setConsent(@Path("id") id: String, @Body body: SharingConsentRequest): SharingConsentDto
}

class CrossPortalSharingClient(private val api: CrossPortalSharingApi) {
    suspend fun enabled(): Boolean = runCatching { api.status().enabled }.getOrDefault(false)
    suspend fun consent(id: String): SharingConsentDto = api.consent(id)
    suspend fun setConsent(id: String, granted: Boolean): SharingConsentDto = api.setConsent(id, SharingConsentRequest(granted))

    companion object {
        fun create(context: Context): CrossPortalSharingClient =
            CrossPortalSharingClient(
                ApiClient.retrofit(TokenStore(context.applicationContext)).create(CrossPortalSharingApi::class.java)
            )
    }
}

/** The switch's words — the web's `components/forms/SharingConsentToggle.tsx`, verbatim. */
object SharingConsentCopy {
    const val LABEL = "Share with the Designer Portal"
    const val DESCRIPTION =
        "The artisan agrees designers may look up their name, gender, date of birth and Pehchan. Aadhaar never leaves this portal."
    const val SAVE_FAILED = "Couldn't save. Try again."

    /** "Agreed on 17 Oct 2026 · linked to 2 Designer Portal records". [date] is already formatted. */
    fun status(consent: SharingConsentDto, date: String?): String {
        if (!consent.recorded) return "Not asked yet"
        val head = if (consent.granted) "Agreed" else "Declined"
        val links = if (consent.linkedRecords > 0) {
            " · linked to ${consent.linkedRecords} Designer Portal record${if (consent.linkedRecords == 1) "" else "s"}"
        } else ""
        return head + (date?.let { " on $it" } ?: "") + links
    }
}
