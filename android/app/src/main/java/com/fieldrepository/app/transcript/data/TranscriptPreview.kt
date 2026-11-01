package com.fieldrepository.app.transcript.data

import kotlinx.serialization.Serializable

/** One speaker line of a list preview: the label apart from the words, never markdown. */
@Serializable
data class TranscriptPreviewLine(
    val speaker: String? = null,
    val speakerKey: String? = null,
    val colorKey: String = TranscriptPreview.NEUTRAL,
    val text: String = "",
)

/** `TranscriptPreview` (`shared/transcript-contract.json`): the first few things said. */
@Serializable
data class TranscriptPreviewDto(val text: String = "", val lines: List<TranscriptPreviewLine> = emptyList())

/**
 * LIST PREVIEWS AND SPEAKER COLOURS (0.0.6), the server's `transcript/preview.py` in Kotlin.
 *
 * A list row shows what was said as plain text with each speaker as a separate label, so no screen
 * draws `Transcript **Speaker 1:** Wow!`. The server sends the preview with the row; this rebuilds
 * the same thing from the stored markdown for a row read before the server did, or with no signal.
 * `TranscriptPreviewTest` holds it to the contract's `fixtures.preview` and `fixtures.speakerColor`.
 *
 * A speaker's colour is a function of the speaker KEY only (`speaker_0` -> `s0`), so it never moves
 * when someone renames a speaker or opens another version.
 */
object TranscriptPreview {
    const val PALETTE_SIZE = 8
    const val NEUTRAL = "neutral"
    const val PREVIEW_LINES = 3
    const val LINE_CHARS = 160
    private const val ELLIPSIS = "…"

    // No (?U) flag: Android's ICU regex rejects UNICODE_CHARACTER_CLASS, and its \s and \d are
    // already Unicode-aware there.
    private val NUMBERED_KEY = Regex("^speaker_(\\d+)$")
    private val NUMBERED_LABEL = Regex("^Speaker (\\d+)$")
    private val WHITESPACE = Regex("\\s+")

    /** `s0` .. `s7` for a speaker, `neutral` for none (the contract's rule, case for case). */
    fun colorKey(speakerKey: String?, label: String? = null): String {
        val key = speakerKey.orEmpty().trim()
        if (key.isNotEmpty()) {
            NUMBERED_KEY.find(key)?.let { return "s${it.groupValues[1].toBigInteger().mod(PALETTE_SIZE.toBigInteger())}" }
            return "s${fnv1a(key) % PALETTE_SIZE}"
        }
        val name = squash(label.orEmpty())
        if (name.isEmpty()) return NEUTRAL
        NUMBERED_LABEL.find(name)?.let { m ->
            val n = m.groupValues[1].toBigInteger()
            if (n >= java.math.BigInteger.ONE) return "s${(n - java.math.BigInteger.ONE).mod(PALETTE_SIZE.toBigInteger())}"
        }
        return "s${fnv1a(name) % PALETTE_SIZE}"
    }

    /** 32-bit FNV-1a over UTF-8. */
    fun fnv1a(text: String): Long {
        var value = 0x811C9DC5L
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            value = value xor (byte.toLong() and 0xFF)
            value = (value * 0x01000193L) and 0xFFFFFFFFL
        }
        return value
    }

    private fun squash(text: String): String = text.split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")

    private val HEADING = Regex("^\\s*#{1,6}\\s+")
    private val RULE = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")
    private val TABLE = Regex("^\\s*\\|")
    private val QUOTE = Regex("^\\s*>")
    private val INLINE_LABEL = Regex("^\\s*\\*\\*\\s*([^*\\n]{1,80}?)\\s*(?::\\s*\\*\\*|\\*\\*\\s*:)\\s*(.*)$")
    private val BLOCK_LABEL = Regex("^\\s*\\*\\*\\s*([^*\\n]{1,80}?)\\s*\\*\\*\\s*(?:·\\s*`[^`]*`)?\\s*$")
    private val LEAD_WORD = Regex("(?i)^\\s*transcript\\s*[:\\-–—]?\\s*(?=\\*\\*|$)")
    private val BULLET = Regex("^\\s*[-*+]\\s+(?=\\S)")
    private val CODE = Regex("`([^`\\n]*)`")
    private val EMPHASIS = Regex("\\*\\*([^*\\n]+?)\\*\\*|\\*([^*\\n]+?)\\*")
    private val STRAY = Regex("[*`]+")

    /** One line with markdown emphasis, code ticks and runs of whitespace removed. */
    fun plain(text: String?): String {
        var line = CODE.replace(text.orEmpty()) { it.groupValues[1] }
        line = EMPHASIS.replace(line) { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
        line = STRAY.replace(line, "")
        return squash(line)
    }

    /** [text] cut to [limit] code points at a word boundary, with an ellipsis when cut. */
    fun clip(text: String, limit: Int = LINE_CHARS): String {
        if (text.codePointCount(0, text.length) <= limit) return text
        var cut = text.substring(0, text.offsetByCodePoints(0, limit - 1))
        val space = cut.lastIndexOf(' ')
        if (space >= 0 && cut.codePointCount(0, space) >= limit / 2) cut = cut.substring(0, space)
        return cut.trimEnd(' ', ',', ';', ':', '.', '-', '–', '—') + ELLIPSIS
    }

    data class Turn(val speakerKey: String?, val speaker: String?, val text: String)

    /** A stored markdown transcript (legacy `**Speaker 1:** …` or the rendered blocks) as turns. */
    fun turnsFromMarkdown(markdown: String?): List<Turn> {
        val out = mutableListOf<Turn>()
        var speaker: String? = null
        val current = mutableListOf<String>()
        var started = false
        fun flush() {
            val text = plain(current.joinToString(" "))
            if (text.isNotEmpty()) out += Turn(null, speaker, text)
            current.clear()
        }
        for (raw in markdown.orEmpty().replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
            val line = if (started) raw else LEAD_WORD.replaceFirst(raw, "")
            if (line.isBlank()) continue
            if (HEADING.containsMatchIn(line) || RULE.containsMatchIn(line) || TABLE.containsMatchIn(line) || QUOTE.containsMatchIn(line)) continue
            started = true
            val block = BLOCK_LABEL.find(line)
            val inline = if (block != null) null else INLINE_LABEL.find(line)
            val label = block ?: inline
            if (label != null) {
                flush()
                speaker = plain(label.groupValues[1]).trimEnd(':').trim().ifEmpty { null }
                if (inline != null && inline.groupValues[2].isNotBlank()) current += inline.groupValues[2]
                continue
            }
            current += BULLET.replaceFirst(line, "")
        }
        flush()
        return out
    }

    /** The preview of [turns], or null when nothing was said. */
    fun preview(turns: List<Turn>, maxLines: Int = PREVIEW_LINES): TranscriptPreviewDto? {
        val lines = mutableListOf<TranscriptPreviewLine>()
        for (turn in turns) {
            val text = plain(turn.text)
            if (text.isEmpty()) continue
            val speaker = plain(turn.speaker).ifEmpty { null }
            lines += TranscriptPreviewLine(speaker, turn.speakerKey, colorKey(turn.speakerKey, speaker), clip(text))
            if (lines.size >= maxLines) break
        }
        if (lines.isEmpty()) return null
        return TranscriptPreviewDto(lines.joinToString(" ") { l -> l.speaker?.let { "$it: ${l.text}" } ?: l.text }, lines)
    }

    fun fromMarkdown(markdown: String?): TranscriptPreviewDto? = preview(turnsFromMarkdown(markdown))

    /** A loaded transcript's opening turns under their shown (mapped) names. */
    fun fromTranscript(view: Transcript): TranscriptPreviewDto? =
        preview(view.turns.sortedBy { it.index }.map { Turn(it.speakerKey, TranscriptFormat.cleanSpeaker(it.speakerName).ifEmpty { null }, it.text) })

    /** The colour key a turn is drawn in: the server's when sent, else the same rule here. */
    fun colorKeyOf(turn: TranscriptTurn): String = turn.colorKey ?: colorKey(turn.speakerKey)

    /**
     * A search snippet or any other short text that may be cut from a stored transcript: read as
     * speech when it carries transcript markdown, otherwise left exactly as it came.
     */
    fun cleanSnippet(text: String): String =
        if (text.contains("**") || text.trimStart().startsWith("Transcript", ignoreCase = true)) fromMarkdown(text)?.text ?: plain(text) else text
}
