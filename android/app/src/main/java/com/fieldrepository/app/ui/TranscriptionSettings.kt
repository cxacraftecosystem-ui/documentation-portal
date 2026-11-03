package com.fieldrepository.app.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT

@Serializable
data class TranscriptionProviderRowDto(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val configured: Boolean = false,
    val inChain: Boolean = false,
    val pricePerHourUsd: Double = 0.0
)

@Serializable
data class TranscriptionUsageDto(
    val provider: String,
    val dayMinutes: Double = 0.0,
    val daySpendUsd: Double = 0.0,
    val monthMinutes: Double = 0.0,
    val monthSpendUsd: Double = 0.0
)

@Serializable
data class TranscriptionBudgetDto(
    val provider: String,
    val period: String,
    val minutesCap: Int? = null,
    val spendCapUsd: Double? = null
)

@Serializable
data class TranscriptionCooldownDto(val provider: String, val until: String)

@Serializable
data class TranscriptionFailureDto(
    val provider: String,
    val outcome: String,
    val httpStatus: Int? = null,
    val at: String = ""
)

@Serializable
data class TranscriptionWindowDto(
    val enabled: Boolean = false,
    val start: String = "02:00",
    val end: String = "05:00",
    val timezone: String = ""
)

@Serializable
data class TranscriptionStateDto(
    val paused: Boolean = true,
    val providers: List<TranscriptionProviderRowDto> = emptyList(),
    val window: TranscriptionWindowDto = TranscriptionWindowDto(),
    val mode: String = "",
    val queue: Map<String, Int> = emptyMap(),
    val usage: List<TranscriptionUsageDto> = emptyList(),
    val cooldowns: List<TranscriptionCooldownDto> = emptyList(),
    val budgets: List<TranscriptionBudgetDto> = emptyList(),
    val failures: List<TranscriptionFailureDto> = emptyList()
)

@Serializable
data class TranscriptionRetryBody(val dryRun: Boolean = true)

@Serializable
data class TranscriptionRetryDto(
    val dryRun: Boolean = true,
    val failed: Int = 0,
    val wouldRequeue: Int = 0,
    val requeued: Int = 0
)

interface TranscriptionSettingsApi {
    @GET("settings/transcription")
    suspend fun transcriptionSettings(): TranscriptionStateDto

    @PUT("settings/transcription")
    suspend fun updateTranscriptionSettings(@Body body: JsonObject): TranscriptionStateDto

    @POST("settings/transcription/retry-failed")
    suspend fun checkFailedTranscriptions(@Body body: TranscriptionRetryBody): TranscriptionRetryDto
}

private val TRANSCRIPTION_OUTCOME_WORDS: Map<String, String> = mapOf(
    "throttled" to "Busy",
    "cooling" to "Cooling down",
    "budget" to "Over budget",
    "key" to "Key not accepted",
    "too_large" to "Too large",
    "failed" to "Failed",
    "unreachable" to "Unreachable"
)

fun transcriptionOutcomeWords(outcome: String): String =
    TRANSCRIPTION_OUTCOME_WORDS[outcome.lowercase()] ?: outcome

fun transcriptionDisabledAfter(
    providers: List<TranscriptionProviderRowDto>,
    id: String,
    enabled: Boolean
): List<String> = providers.filter { if (it.id == id) !enabled else !it.enabled }.map { it.id }

fun transcriptionQueueLine(state: TranscriptionStateDto): String =
    "${state.queue["QUEUED"] ?: 0} queued · ${state.queue["FAILED"] ?: 0} failed"

fun transcriptionPauseDescription(paused: Boolean): String =
    if (paused) "Paused. Recordings wait in the queue." else "Recordings are sent in the order below."

fun transcriptionMonthBudget(state: TranscriptionStateDto, provider: String): Int? =
    state.budgets.firstOrNull { it.provider == provider && it.period == "MONTH" }?.minutesCap

sealed interface BudgetInput {
    data object NoLimit : BudgetInput
    data class Minutes(val value: Int) : BudgetInput
    data object Invalid : BudgetInput
}

fun parseTranscriptionBudget(raw: String): BudgetInput {
    val text = raw.trim()
    if (text.isEmpty()) return BudgetInput.NoLimit
    val number = text.toDoubleOrNull() ?: return BudgetInput.Invalid
    if (!number.isFinite() || number < 0 || number > 1_000_000) return BudgetInput.Invalid
    return BudgetInput.Minutes(Math.round(number).toInt())
}

const val TRANSCRIPTION_BUDGET_INVALID = "Enter whole minutes, or leave it empty for no limit."

fun transcriptionProviderStatus(
    state: TranscriptionStateDto,
    provider: TranscriptionProviderRowDto,
    whenText: (String) -> String
): String? {
    val usage = state.usage.firstOrNull { it.provider == provider.id }
    val cooling = state.cooldowns.firstOrNull { it.provider == provider.id }
    return listOfNotNull(
        if (provider.configured) null else "No key",
        cooling?.let { "Busy until ${whenText(it.until)}" },
        usage?.let { "${formatMinutes(it.monthMinutes)} min this month · $${"%.2f".format(java.util.Locale.ROOT, it.monthSpendUsd)}" }
    ).joinToString(" · ").ifEmpty { null }
}

private fun formatMinutes(value: Double): String =
    if (value == Math.floor(value)) value.toLong().toString() else value.toString()

fun transcriptionRetrySentence(answer: TranscriptionRetryDto, paused: Boolean): String =
    "${answer.wouldRequeue} of ${answer.failed} could be retried${if (paused) " once transcription is on." else "."}"

fun transcriptionWindowLine(window: TranscriptionWindowDto): String =
    if (window.enabled) "Runs ${window.start}–${window.end} (${window.timezone})." else "Runs at any hour when on."
