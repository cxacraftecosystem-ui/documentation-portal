package com.fieldrepository.app.explorer.shots

import com.fieldrepository.app.explorer.data.ExplorerBundle
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerGoMedia
import com.fieldrepository.app.explorer.data.ExplorerGoOpen
import com.fieldrepository.app.explorer.data.ExplorerGoTranscript
import com.fieldrepository.app.explorer.data.ExplorerMore
import com.fieldrepository.app.explorer.data.ExplorerOverview
import com.fieldrepository.app.explorer.data.ExplorerPeaks
import com.fieldrepository.app.explorer.data.ExplorerReportOptionsBody
import com.fieldrepository.app.explorer.data.ExplorerReportSheets
import com.fieldrepository.app.explorer.data.ExplorerShare
import com.fieldrepository.app.explorer.data.ExplorerShareBody
import com.fieldrepository.app.explorer.data.ExplorerShares
import com.fieldrepository.app.explorer.data.ExplorerTranscriptList
import com.fieldrepository.app.explorer.data.ExplorerView
import com.fieldrepository.app.explorer.data.ExplorerViewBody
import com.fieldrepository.app.explorer.data.ExplorerViews
import com.fieldrepository.app.explorer.data.ExplorerVisit
import com.fieldrepository.app.explorer.data.ExplorerVisitBody
import com.fieldrepository.app.transcript.data.Transcript
import com.fieldrepository.app.transcript.data.TranscriptAudio
import com.fieldrepository.app.transcript.data.TranscriptLanguage
import com.fieldrepository.app.transcript.data.TranscriptSpeaker
import com.fieldrepository.app.transcript.data.TranscriptTurn
import com.fieldrepository.app.transcript.data.TurnTranslation
import retrofit2.Response
import kotlin.math.cos
import kotlin.math.sin

object DesignBoards {
    const val AUDIO_ID = "aud-d"
    const val AUDIO_MS = 2_462_000L
    const val GIRIRAJ = "workshops/3rd/giriraj"
    private const val MB = 1024L * 1024L

    val audioEntry = ExplorerEntry(
        name = "Section-D_Materials_Group-of-3.m4a",
        path = "$GIRIRAJ/Section-D_Materials_Group-of-3.m4a",
        mediaType = "AUDIO",
        mediaId = AUDIO_ID,
        sizeBytes = (14.2 * MB).toLong(),
        transcriptAvailable = true,
        previewPath = "/data/media/$AUDIO_ID/preview",
        downloadPath = "/data/media/$AUDIO_ID/download",
        capturedAt = "2026-09-22T10:00:00Z",
    )

    const val VIDEO_ID = "vid-dabu"

    val videoEntry = ExplorerEntry(
        name = "Interview_Dabu-mud-resist_Take-1.mp4",
        path = "$GIRIRAJ/Interview_Dabu-mud-resist_Take-1.mp4",
        mediaType = "VIDEO",
        mediaId = VIDEO_ID,
        sizeBytes = 701 * MB,
        transcriptAvailable = true,
        previewPath = "/data/media/$VIDEO_ID/preview",
        capturedAt = "2026-09-22T10:00:00Z",
    )

    fun videoFiles(): List<ExplorerEntry> {
        val rest = files().filter { it.mediaId != AUDIO_ID }
        return rest.take(2) + videoEntry + rest.drop(2).take(45)
    }

    fun videoTranscript(): Transcript {
        val lines = listOf(
            Line("11:32", "speaker_0", "What do you mix into the mud before it goes on the cloth?", ""),
            Line("11:41", "speaker_1", "Lime, gum and the husk of wheat. It has to sit overnight.", ""),
            Line("12:04", "speaker_1", "Then the mud resists the indigo where we press the block, so the pattern stays pale.", ""),
            Line("12:31", "speaker_0", "How many dips in the indigo vat?", ""),
            Line("12:38", "speaker_1", "Three to seven, depending on how dark the buyer wants it.", ""),
        )
        val names = mapOf("speaker_0" to "Interviewer", "speaker_1" to "Giriraj")
        val turns = lines.mapIndexed { i, line ->
            val start = ms(line.at)
            val end = lines.getOrNull(i + 1)?.let { ms(it.at) - 500 } ?: (start + 20_000)
            TranscriptTurn(id = "v$i", index = i, speakerKey = line.key, speakerName = names.getValue(line.key), startMs = start, endMs = end, language = "hi", text = line.original, translation = TurnTranslation(text = line.original, status = "TRANSLATED"), colorKey = "s${line.key.last()}")
        }
        return Transcript(
            mediaId = VIDEO_ID,
            title = "Interview_Dabu-mud-resist_Take-1",
            status = "READY",
            languages = listOf(TranscriptLanguage("hi", "Hindi"), TranscriptLanguage("en", "English")),
            durationMs = 1_458_000,
            speakerCount = 2,
            speakers = listOf(TranscriptSpeaker("speaker_0", "Speaker 1", "Interviewer", colorKey = "s0"), TranscriptSpeaker("speaker_1", "Speaker 2", "Giriraj", colorKey = "s1")),
            turns = turns,
            audio = TranscriptAudio(durationMs = 1_458_000, video = true),
        )
    }

    fun files(): List<ExplorerEntry> {
        val before = (1..6).map { i ->
            ExplorerEntry(name = "Section-${'A' + i}_Answers_0$i.m4a", path = "$GIRIRAJ/a$i", mediaType = "AUDIO", mediaId = "aud-g$i", sizeBytes = 14 * MB, transcriptAvailable = true)
        }
        val after = (7 until 48).map { i ->
            ExplorerEntry(name = "Block-carving_detail_$i.jpg", path = "$GIRIRAJ/i$i", mediaType = "IMAGE", mediaId = "img-g$i", sizeBytes = 3 * MB)
        }
        return before + audioEntry + after
    }

    private val speakers = mapOf("speaker_0" to "Interviewer", "speaker_1" to "Sunita", "speaker_2" to "Kamla", "speaker_3" to "Meera")

    private data class Line(val at: String, val key: String, val original: String, val english: String)

    private val boardLines = listOf(
        Line("17:42", "speaker_0", "छापने से पहले मिट्टी में क्या मिलाते हो?", "What goes into the mud before you print?"),
        Line("17:49", "speaker_1", "तालाब की काळी माटी, चूनो, बबूल रो गोंद अर गेहूं रो भूसो।", "Black clay from the pond, lime, gum and wheat husk."),
        Line("18:14", "speaker_1", "चूना बिना सूखतां ही फाट जावै, इण वास्ते रात नै भिगो देवां।", "Without the lime it cracks when it dries, so we soak it the night before."),
        Line("18:36", "speaker_2", "म्हारी मां कम गोंद लगावती। धुपै तो सोरो पण किनारो इतो तीखो कोनी।", "My mother used less gum. It washes out easier but the edge is not as sharp."),
        Line("18:58", "speaker_0", "चूना कहाँ से खरीदते हो?", "Where do you buy the lime?"),
        Line("19:03", "speaker_1", "सांगानेर रै बजार सूं, महीना में एक बार।", "From the market in Sanganer, once a month."),
    )

    private fun ms(clock: String): Long = clock.split(":").let { (m, s) -> (m.toLong() * 60 + s.toLong()) * 1000 }

    fun audioTranscript(): Transcript {
        val turns = boardLines.mapIndexed { i, line ->
            val start = ms(line.at)
            val end = boardLines.getOrNull(i + 1)?.let { ms(it.at) - 500 } ?: (start + 15_000)
            TranscriptTurn(
                id = "d$i", index = i, speakerKey = line.key, speakerName = speakers.getValue(line.key), startMs = start, endMs = end,
                language = "mwr", text = line.original, translation = TurnTranslation(text = line.english, status = "TRANSLATED"),
                colorKey = "s${line.key.last()}",
            )
        }
        return Transcript(
            mediaId = AUDIO_ID,
            title = "Section D · Materials · Group of 3",
            status = "READY",
            languages = listOf(TranscriptLanguage("mwr", "Marwari"), TranscriptLanguage("en", "English")),
            durationMs = AUDIO_MS,
            speakerCount = 4,
            speakers = listOf(
                TranscriptSpeaker("speaker_0", "Speaker 1", "Interviewer", colorKey = "s0"),
                TranscriptSpeaker("speaker_1", "Speaker 2", "Sunita", colorKey = "s1"),
                TranscriptSpeaker("speaker_2", "Speaker 3", "Kamla", colorKey = "s2"),
                TranscriptSpeaker("speaker_3", "Speaker 4", "Meera", colorKey = "s3"),
            ),
            turns = turns,
            audio = TranscriptAudio(durationMs = AUDIO_MS),
        )
    }

    fun silentWav(seconds: Long): ByteArray {
        val rate = 3000
        val samples = (rate * seconds).toInt()
        val out = java.nio.ByteBuffer.allocate(44 + samples).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + samples).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        out.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate).putShort(1).putShort(8)
        out.put("data".toByteArray()).putInt(samples)
        repeat(samples) { out.put(128.toByte()) }
        return out.array()
    }

    fun peaks(): List<Float> = (0 until 1600).map { i ->
        (0.55f + 0.3f * sin(i / 7f) * cos(i / 19f) + 0.15f * sin(i / 3.3f)).coerceIn(0.12f, 1f)
    }

    class More : ExplorerMore {
        override suspend fun reportSheets(path: String, status: String?, q: String?) = ExplorerReportSheets(path = path)
        override suspend fun createShare(body: ExplorerShareBody) = ExplorerShare(id = "s1", url = "https://portal.example.org/report-share/tok", expiresAt = "2026-11-12T00:00:00Z")
        override suspend fun shares(path: String) = ExplorerShares()
        override suspend fun revokeShare(id: String): Response<Unit> = Response.success(Unit)
        override suspend fun goMedia(id: String, form: String) = ExplorerGoMedia(fileName = "$id.m4a", mediaId = id)
        override suspend fun goOpen(kind: String, id: String) = ExplorerGoOpen(kind, id)
        override suspend fun goTranscript(id: String, format: String) = ExplorerGoTranscript(id, format)
        override suspend fun bundle(id: String) = ExplorerBundle(id = id, label = "Bundle $id", path = GIRIRAJ, fileCount = 48, bytes = 1_331_439_861)
        override suspend fun transcripts(path: String, q: String?, status: String?, offset: Int?, sort: String?) = ExplorerTranscriptList()
        override suspend fun reportOptions() = ExplorerReportOptionsBody(null)
        override suspend fun saveReportOptions(body: ExplorerReportOptionsBody) = body
        override suspend fun visit(body: ExplorerVisitBody) = ExplorerVisit(path = body.path, previousVisitAt = "2026-11-01T00:00:00Z", newFiles = 38, newTranscripts = 4)
        override suspend fun overview(path: String, status: String?) = ExplorerOverview(path = path)
        override suspend fun peaks(id: String) = ExplorerPeaks(peaks(), AUDIO_MS, isolated = true)
        override suspend fun views() = ExplorerViews()
        override suspend fun view(id: String): ExplorerView = throw java.io.IOException("missing")
        override suspend fun saveView(body: ExplorerViewBody) = ExplorerView(id = "v1", name = body.name, path = body.path)
    }
}
