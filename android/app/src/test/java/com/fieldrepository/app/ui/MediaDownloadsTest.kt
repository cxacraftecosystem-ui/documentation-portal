package com.fieldrepository.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/**
 * The name a file is saved under when somebody taps "Download" on an attachment, recording or
 * outbox row. The SAME table drives the web twin in
 * `frontend/e2e/local-media-download-unit.spec.ts` — a voice note saved from the phone and from the
 * laptop must land under the same name. Add a row here, add it there.
 */
class MediaDownloadsTest {
    // 2026-03-07 09:05:02 local time — single-digit month, day, hour and minute so padding is exercised.
    private val now = LocalDateTime.of(2026, 3, 7, 9, 5, 2)

    @Test
    fun extensionForMimeType() {
        val rows = listOf(
            "audio/webm" to "webm",
            "audio/webm;codecs=opus" to "webm",
            "AUDIO/MP4" to "m4a",
            "audio/mp4; codecs=mp4a.40.2" to "m4a",
            "audio/ogg" to "ogg",
            "audio/mpeg" to "mp3",
            "audio/x-wav" to "wav",
            "audio/amr" to "amr",
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/heic" to "heic",
            "video/mp4" to "mp4",
            "video/quicktime" to "mov",
            "application/pdf" to "pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
            "image/x-icon" to "x-icon",
            "application/ld+json" to "bin",
            "" to "bin",
            null to "bin",
            "garbage" to "bin"
        )
        rows.forEach { (mime, expected) -> assertEquals("for $mime", expected, mediaExtensionForMimeType(mime)) }
    }

    @Test
    fun fileStampPadsEveryField() {
        assertEquals("2026-03-07-090502", mediaFileStamp(now))
    }

    @Test
    fun localMediaFileName() {
        val rows = listOf(
            Triple("field-recording-1757000000000.webm", "audio/webm", "field-recording-1757000000000.webm"),
            Triple("IMG_0042.JPG", "image/jpeg", "IMG_0042.JPG"),
            Triple("Interview notes.pdf", "application/pdf", "Interview notes.pdf"),
            Triple("Section B clip", "audio/mp4", "Section B clip.m4a"),
            Triple("mystery", "", "mystery"),
            Triple("trailing.", "image/png", "trailing..png"),
            Triple("a/b:c*d?.jpg", "image/jpeg", "a_b_c_d_.jpg"),
            Triple(".hidden.png", "image/png", "hidden.png"),
            Triple("", "audio/webm;codecs=opus", "voice-note-2026-03-07-090502.webm"),
            Triple(null, "audio/mp4", "voice-note-2026-03-07-090502.m4a"),
            Triple("   ", "image/jpeg", "media-2026-03-07-090502.jpg"),
            Triple(null, null, "media-2026-03-07-090502.bin")
        )
        rows.forEach { (name, mime, expected) ->
            assertEquals("for $name + $mime", expected, localMediaFileName(name, mime, now))
        }
    }
}
