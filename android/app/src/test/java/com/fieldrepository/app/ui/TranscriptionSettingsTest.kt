package com.fieldrepository.app.ui

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionSettingsTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    private val providers = listOf(
        TranscriptionProviderRowDto(id = "elevenlabs", name = "ElevenLabs", enabled = true, configured = true),
        TranscriptionProviderRowDto(id = "deepgram", name = "Deepgram", enabled = true, configured = false),
        TranscriptionProviderRowDto(id = "muse", name = "Meta Muse", enabled = false, configured = false)
    )

    @Test
    fun aBodyWithoutThePauseReadsAsPaused() {
        val state = json.decodeFromString(TranscriptionStateDto.serializer(), """{"providers":[]}""")
        assertTrue(state.paused)
    }

    @Test
    fun theServerShapeDecodes() {
        val body = """{"paused":true,"providers":[{"id":"muse","name":"Meta Muse","enabled":false,"configured":false,"inChain":false,"pricePerHourUsd":0.6}],
            "window":{"enabled":false,"start":"02:00","end":"05:00","timezone":"Asia/Kolkata"},"mode":"QUEUE",
            "queue":{"QUEUED":3,"FAILED":1},"usage":[{"provider":"muse","dayMinutes":0,"daySpendUsd":0,"monthMinutes":12.5,"monthSpendUsd":0.13}],
            "cooldowns":[{"provider":"muse","keyRef":"k","until":"2026-10-18T10:00:00Z","consecutive":2}],
            "budgets":[{"provider":"muse","period":"MONTH","minutesCap":600,"spendCapUsd":null}],
            "failures":[{"provider":"muse","outcome":"THROTTLED","httpStatus":429,"mediaFileId":"m","at":"2026-10-18T09:00:00Z"}]}"""
        val state = json.decodeFromString(TranscriptionStateDto.serializer(), body)
        assertEquals("3 queued · 1 failed", transcriptionQueueLine(state))
        assertEquals(600, transcriptionMonthBudget(state, "muse"))
        assertEquals("Busy", transcriptionOutcomeWords(state.failures.single().outcome))
        assertEquals(
            "No key · Busy until X · 12.5 min this month · \$0.13",
            transcriptionProviderStatus(state, state.providers.single()) { "X" }
        )
    }

    @Test
    fun switchingAProviderSendsTheWholeDisabledList() {
        assertEquals(listOf("deepgram", "muse"), transcriptionDisabledAfter(providers, "deepgram", false))
        assertEquals(emptyList<String>(), transcriptionDisabledAfter(providers, "muse", true))
    }

    @Test
    fun budgetInputMatchesTheWeb() {
        assertEquals(BudgetInput.NoLimit, parseTranscriptionBudget("  "))
        assertEquals(BudgetInput.Minutes(120), parseTranscriptionBudget("120"))
        assertEquals(BudgetInput.Minutes(13), parseTranscriptionBudget("12.6"))
        assertEquals(BudgetInput.Invalid, parseTranscriptionBudget("-1"))
        assertEquals(BudgetInput.Invalid, parseTranscriptionBudget("lots"))
    }

    @Test
    fun theDryRunSentenceSaysWhenItCanHappen() {
        val answer = TranscriptionRetryDto(dryRun = true, failed = 9, wouldRequeue = 7)
        assertEquals("7 of 9 could be retried once transcription is on.", transcriptionRetrySentence(answer, paused = true))
        assertEquals("7 of 9 could be retried.", transcriptionRetrySentence(answer, paused = false))
    }

    @Test
    fun copyMatchesTheWebPanel() {
        assertEquals("Paused. Recordings wait in the queue.", transcriptionPauseDescription(true))
        assertEquals("Runs at any hour when on.", transcriptionWindowLine(TranscriptionWindowDto()))
        val configured = TranscriptionStateDto(providers = providers)
        assertNull(transcriptionProviderStatus(configured, providers.first()) { it })
    }
}
