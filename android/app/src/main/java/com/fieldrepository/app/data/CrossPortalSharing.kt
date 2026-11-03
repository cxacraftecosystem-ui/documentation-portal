package com.fieldrepository.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path

@Serializable
data class CrossPortalStatusDto(
    val enabled: Boolean = false,
    val sharedFields: String? = null,
)

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

    suspend fun status(): CrossPortalStatusDto = runCatching { api.status() }.getOrDefault(CrossPortalStatusDto())
    suspend fun consent(id: String): SharingConsentDto = api.consent(id)
    suspend fun setConsent(id: String, granted: Boolean): SharingConsentDto = api.setConsent(id, SharingConsentRequest(granted))

    companion object {
        fun create(context: Context): CrossPortalSharingClient =
            CrossPortalSharingClient(
                ApiClient.retrofit(TokenStore(context.applicationContext)).create(CrossPortalSharingApi::class.java)
            )
    }
}

object SharingConsentCopy {
    const val LABEL = "Share with the Designer Portal"

    const val SHARED_FIELDS_FALLBACK = "name, local name, gender, date of birth, Pehchan card, phone number, address, place, craft and craft experience"

    fun description(sharedFields: String?): String =
        "The artisan agrees designers may look up their ${sharedFields?.trim()?.ifBlank { null } ?: SHARED_FIELDS_FALLBACK}. Aadhaar never leaves this portal."
    const val SAVE_FAILED = "Couldn't save. Try again."

    fun status(consent: SharingConsentDto, date: String?): String {
        if (!consent.recorded) return "Not asked yet"
        val head = if (consent.granted) "Agreed" else "Declined"
        val links = if (consent.linkedRecords > 0) {
            " · linked to ${consent.linkedRecords} Designer Portal record${if (consent.linkedRecords == 1) "" else "s"}"
        } else ""
        return head + (date?.let { " on $it" } ?: "") + links
    }
}
