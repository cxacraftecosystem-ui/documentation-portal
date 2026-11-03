package com.fieldrepository.app.ui

import com.fieldrepository.app.ui.richtext.Align
import com.fieldrepository.app.ui.richtext.BlockKind
import com.fieldrepository.app.ui.richtext.EMPTY_RICH_DOC
import com.fieldrepository.app.ui.richtext.RichDoc
import com.fieldrepository.app.ui.richtext.fromJson
import com.fieldrepository.app.ui.richtext.isEmptyDoc
import com.fieldrepository.app.ui.richtext.toJson
import com.fieldrepository.app.ui.richtext.toPlain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class RecordDictationRung {
    ON_DEVICE_PACK,

    NETWORK_RECOGNISER,
}

data class RecordDictationConditions(
    val languageLabel: String,
    val onDeviceEngine: Boolean,
    val networkRecogniser: Boolean,
    val online: Boolean,
    val deviceRefusedLanguage: Boolean,
)

fun recordDictationRungs(conditions: RecordDictationConditions): List<RecordDictationRung> =
    buildList {
        if (conditions.onDeviceEngine && !conditions.deviceRefusedLanguage) {
            add(RecordDictationRung.ON_DEVICE_PACK)
        }
        if (conditions.networkRecogniser && conditions.online) {
            add(RecordDictationRung.NETWORK_RECOGNISER)
        }
    }

fun recordDictationNothingLeftSentence(conditions: RecordDictationConditions): String {
    val label = conditions.languageLabel
    return when {

        !conditions.online && conditions.onDeviceEngine ->
            "$label isn't available offline. Type, pick another language, or dictate later with signal."

        !conditions.online ->
            "$label dictation needs a connection. Try your keyboard's mic, or type."

        !conditions.networkRecogniser && !conditions.onDeviceEngine -> RECORD_DICTATION_UNAVAILABLE

        conditions.deviceRefusedLanguage ->
            "$label isn't supported here. Pick another language or type."

        else ->
            "$label dictation is unavailable right now. Type, or try another language."
    }
}

const val RECORD_DICTATION_UNAVAILABLE: String =
    "No speech recogniser on this phone. Type instead."

fun appendSpokenToRecord(existing: String, spoken: String): String = when {
    spoken.isBlank() -> existing
    existing.isBlank() -> spoken
    existing.last().isWhitespace() -> existing + spoken
    else -> "$existing $spoken"
}

internal fun looksLikeRichDocument(stored: String): Boolean {
    val trimmed = stored.trim()
    return trimmed.startsWith("{") && trimmed.endsWith("}") && trimmed.contains("\"blocks\"")
}

fun recordDocFromStored(stored: String?): RichDoc {
    val text = stored?.takeIf { it.isNotBlank() } ?: return EMPTY_RICH_DOC
    if (looksLikeRichDocument(text)) {
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        if (parsed is JsonObject) return fromJson(parsed)
    }
    return fromJson(JsonPrimitive(text))
}

internal fun isPlainProse(doc: RichDoc): Boolean =
    doc.blocks.all { block ->
        block.kind == BlockKind.PARAGRAPH &&
            block.level == 0 &&
            block.align == Align.LEFT &&
            block.media.isBlank() &&
            block.rows.isEmpty() &&
            block.spans.all { span -> span.marks.isEmpty() }
    }

fun recordStoredFromDoc(doc: RichDoc): String? = when {
    isEmptyDoc(doc) -> null
    !isPlainProse(doc) -> toJson(doc).toString()
    else -> toPlain(doc).takeIf { it.isNotBlank() }
}

data class RecordDictationLanguage(val tag: String, val label: String)

val RECORD_DICTATION_LANGUAGES: List<RecordDictationLanguage> = listOf(
    RecordDictationLanguage("en-IN", "English (India)"),
    RecordDictationLanguage("hi-IN", "हिन्दी — Hindi"),
    RecordDictationLanguage("bn-IN", "বাংলা — Bengali"),
    RecordDictationLanguage("mr-IN", "मराठी — Marathi"),
    RecordDictationLanguage("te-IN", "తెలుగు — Telugu"),
    RecordDictationLanguage("ta-IN", "தமிழ் — Tamil"),
    RecordDictationLanguage("gu-IN", "ગુજરાતી — Gujarati"),
    RecordDictationLanguage("kn-IN", "ಕನ್ನಡ — Kannada"),
    RecordDictationLanguage("ml-IN", "മലയാളം — Malayalam"),
    RecordDictationLanguage("or-IN", "ଓଡ଼ିଆ — Odia"),
    RecordDictationLanguage("pa-IN", "ਪੰਜਾਬੀ — Punjabi"),
)

fun recordDictationLabel(tag: String): String =
    RECORD_DICTATION_LANGUAGES.firstOrNull { it.tag == tag }?.label ?: tag
