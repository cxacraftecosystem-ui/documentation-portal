package com.fieldrepository.app.questionnaire.data

import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * A server in memory for the outbox tests: it records every call in order, keys sessions on
 * `clientKey` like the real `POST /sessions`, and can be told to drop a connection.
 */
class FakeSessionTransport : SessionTransport {
    val calls = mutableListOf<String>()
    val idempotencyKeys = mutableMapOf<String, MutableList<String>>()
    val sessionsByClientKey = linkedMapOf<String, QSession>()
    val bodies = mutableListOf<String>()
    var bytesPut = 0L
    val partsPut = mutableListOf<Int>()
    var inFlight = AtomicInteger(0)
    var maxInFlight = 0
    var putDelayMs = 0L
    /** Clip keys whose complete is refused for good. */
    val refuseComplete = mutableSetOf<String>()
    /** Throw an IOException on the Nth part PUT (1-based), once. */
    var dropOnPart: Int? = null
    private var partCounter = 0
    private val uploads = mutableMapOf<String, QUploadState>()
    private var nextId = 1

    private fun record(call: String, key: String? = null) {
        synchronized(calls) { calls += call }
        if (key != null) synchronized(idempotencyKeys) { idempotencyKeys.getOrPut(call.substringBefore(':')) { mutableListOf() } += key }
    }

    override suspend fun createArtisan(idempotencyKey: String, bodyJson: String): String {
        record("artisan", idempotencyKey); bodies += bodyJson
        return "a-server-${nextId++}"
    }

    override suspend fun createSession(idempotencyKey: String, body: QSessionCreate): QSession {
        record("session", idempotencyKey)
        bodies += outboxJson.encodeToString(QSessionCreate.serializer(), body)
        return sessionsByClientKey.getOrPut(body.clientKey) {
            QSession(
                id = "s-${nextId++}", clientKey = body.clientKey, participants = body.participants.map { QParticipant(it.artisanId) },
                // The server lists its guests in an order of its own: reversed here, so a match by name is proven.
                respondents = body.respondents.map { QRespondent(id = "g-${nextId++}", name = SessionOutbox.tidyName(it.name), kind = it.kind) }.reversed()
            )
        }
    }

    override suspend fun addRespondents(idempotencyKey: String, sessionId: String, respondents: List<QRespondentInput>): List<QRespondent> {
        record("respondent", idempotencyKey)
        bodies += outboxJson.encodeToString(QRespondentsAdd.serializer(), QRespondentsAdd(respondents))
        return respondents.map { QRespondent(id = "g-${nextId++}", sessionId = sessionId, name = SessionOutbox.tidyName(it.name), kind = it.kind) }
    }

    override suspend fun addParticipant(idempotencyKey: String, sessionId: String, artisanId: String) { record("participant:add:$artisanId", idempotencyKey) }
    override suspend fun patchParticipant(sessionId: String, artisanId: String, patch: QParticipantPatch) { record("participant:patch:$artisanId") }

    override suspend fun uploadState(clientClipKey: String): QUploadState? = uploads[clientClipKey]

    override suspend fun presign(body: QPresignRequest): QPresignResponse {
        record("presign:${body.clientClipKey}")
        return QPresignResponse(objectKey = "media/u/${body.clientClipKey}/x.m4a", url = "https://s3/${body.clientClipKey}")
    }

    override suspend fun multipartCreate(body: QPresignRequest): QMultipartCreateResponse {
        record("multipart:${body.clientClipKey}")
        val state = QUploadState(objectKey = "media/u/${body.clientClipKey}/x.m4a", multipartUploadId = "up-${body.clientClipKey}", partSize = PART)
        uploads[body.clientClipKey] = state
        return QMultipartCreateResponse(state.objectKey, state.multipartUploadId!!, PART)
    }

    override suspend fun presignParts(objectKey: String, uploadId: String, partNumbers: List<Int>): Map<Int, String> =
        partNumbers.associateWith { "https://s3/$uploadId/$it" }

    override suspend fun put(url: String, headers: Map<String, String>, file: File, offset: Long, length: Long, contentType: String): String {
        val now = inFlight.incrementAndGet()
        synchronized(this) { maxInFlight = maxOf(maxInFlight, now) }
        try {
            if (putDelayMs > 0) delay(putDelayMs)
            val part = url.substringAfterLast('/').toIntOrNull()
            if (url.contains("/up-") && part != null) {
                partCounter++
                if (dropOnPart == partCounter) { dropOnPart = null; throw IOException("connection dropped") }
                synchronized(partsPut) { partsPut += part }
            }
            synchronized(this) { bytesPut += length }
            return "\"etag-$part\""
        } finally {
            inFlight.decrementAndGet()
        }
    }

    override suspend fun multipartComplete(objectKey: String, uploadId: String, parts: List<QPartDone>) { record("multipart-complete:$uploadId") }

    override suspend fun complete(idempotencyKey: String, body: QCompleteRequest): QRecording {
        record("complete:${body.clientClipKey}", idempotencyKey)
        bodies += outboxJson.encodeToString(QCompleteRequest.serializer(), body)
        if (body.clientClipKey in refuseComplete) throw PermanentStepFailure("Target outside the instrument", QErrorCodes.TARGET_OUTSIDE_INSTRUMENT)
        return QRecording(id = "r-${body.clientClipKey}", clientClipKey = body.clientClipKey, sessionId = body.linkedRecordId)
    }

    override suspend fun putAnswers(body: QAnswersPut): QAnswersPutResponse {
        record("answers")
        bodies += outboxJson.encodeToString(QAnswersPut.serializer(), body)
        return QAnswersPutResponse(body.items.map { QAnswerResult(it.artisanId, it.questionLineageId, "SAVED") })
    }

    override suspend fun finish(sessionId: String) { record("finish") }

    companion object {
        const val PART = 1024L
    }
}

/** A clip of [size] bytes on disk, queued as one take of [targetKey] for [subjects]. */
fun testClip(dir: File, key: String, size: Int, capturedAt: String, subjects: List<String>, sectionId: String = "secD"): ClipPayload {
    val file = File(dir, "$key.m4a").apply { writeBytes(ByteArray(size) { (it % 251).toByte() }) }
    return ClipPayload(
        clientClipKey = key, localPath = file.absolutePath, sizeBytes = size.toLong(), contentSha256 = "sha-$key",
        label = "Section D (7 Jul)",
        recording = QCompleteRecording(
            targets = listOf(QTargetRef(sectionId = sectionId)),
            subjects = subjects.map { QCompleteSubject(it) },
            deviceCapturedAt = capturedAt, durationMs = 60_000, rosterCount = subjects.size
        ),
        subjectArtisanIds = subjects
    )
}
