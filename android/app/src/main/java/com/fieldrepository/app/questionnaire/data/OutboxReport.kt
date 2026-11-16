package com.fieldrepository.app.questionnaire.data

import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID

data class OutboxSnapshot(val pending: Int, val failed: Int, val oldest: Instant?) {
    companion object {
        val EMPTY = OutboxSnapshot(0, 0, null)

        fun of(units: List<SessionUnit>): OutboxSnapshot {
            val held = units.flatMap { it.steps }.filter { it.kind == StepKind.CLIP && it.clip != null && it.state != StepState.DONE }
            val oldest = held.mapNotNull { step ->
                parse(step.clip?.recording?.deviceCapturedAt) ?: parse(step.deviceTime)
            }.minOrNull()
            return OutboxSnapshot(held.size, held.count { it.state == StepState.FAILED }, oldest)
        }

        private fun parse(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
    }
}

object OutboxReport {
    const val INSTALL_ID_HEADER = "X-Install-Id"
    const val PENDING_HEADER = "X-Outbox-Pending"
    const val FAILED_HEADER = "X-Outbox-Failed"
    const val OLDEST_HEADER = "X-Outbox-Oldest"
    const val INSTALL_ID_PREF = "installId"

    @Volatile
    private var installId: String? = null

    @Volatile
    private var source: (suspend () -> OutboxSnapshot)? = null

    fun attach(installId: String, source: suspend () -> OutboxSnapshot) {
        this.installId = installId
        this.source = source
    }

    internal fun detach() {
        installId = null
        source = null
    }

    fun installIdFrom(stored: String?, store: (String) -> Unit): String =
        stored?.takeIf { runCatching { UUID.fromString(it) }.isSuccess } ?: UUID.randomUUID().toString().also(store)

    fun headersOf(installId: String, snapshot: OutboxSnapshot): Map<String, String> = buildMap {
        put(INSTALL_ID_HEADER, installId)
        put(PENDING_HEADER, snapshot.pending.toString())
        put(FAILED_HEADER, snapshot.failed.toString())
        snapshot.oldest?.let { put(OLDEST_HEADER, it.toString()) }
    }

    fun headers(): Map<String, String> {
        val id = installId ?: return emptyMap()
        val snapshot = source?.let { read -> runCatching { runBlocking { read() } }.getOrNull() } ?: return mapOf(INSTALL_ID_HEADER to id)
        return headersOf(id, snapshot)
    }
}
