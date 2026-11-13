package com.fieldrepository.app.transcript.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.Normalizer
import java.util.Locale

data class SpokenLanguage(val code: String, val label: String, val featured: Boolean = false)

@Serializable
data class SpokenLanguageSuggested(val code: String, val label: String = "", val source: String = "")

@Serializable
data class SpokenLanguageSuggestion(val suggested: SpokenLanguageSuggested? = null, val uncertainBelow: Double = SpokenLanguages.UNCERTAIN_BELOW)

@Serializable
data class RetranscribeBody(val spokenLanguage: String)

@Serializable
data class TranscriptSpokenLanguage(
    val chosen: String = SpokenLanguages.AUTO,
    val chosenLabel: String = "",
    val requested: String? = null,
    val requestedLabel: String? = null,
    val detected: String? = null,
    val detectedLabel: String? = null,
    val probability: Double? = null,
    val uncertain: Boolean = false,
    val retranscribing: Boolean = false,
    val estimateMinutes: Double = 0.0,
    val estimateUsd: Double? = null,
)

object SpokenLanguages {
    const val AUTO = "auto"
    const val MIXED = "mixed"
    const val UNCERTAIN_BELOW = 0.8
    const val METADATA_KEY = "spokenLanguage"
    const val SOURCE_ARTISAN = "artisan"
    const val SOURCE_RESEARCHER = "researcher"
    const val SOURCE_DEVICE = "device"

    val languages: List<SpokenLanguage> = listOf(
        SpokenLanguage("as", "Assamese", featured = true),
        SpokenLanguage("bn", "Bengali", featured = true),
        SpokenLanguage("en", "English", featured = true),
        SpokenLanguage("gu", "Gujarati", featured = true),
        SpokenLanguage("hi", "Hindi", featured = true),
        SpokenLanguage("kn", "Kannada", featured = true),
        SpokenLanguage("ml", "Malayalam", featured = true),
        SpokenLanguage("mr", "Marathi", featured = true),
        SpokenLanguage("ne", "Nepali", featured = true),
        SpokenLanguage("or", "Odia", featured = true),
        SpokenLanguage("pa", "Punjabi", featured = true),
        SpokenLanguage("ta", "Tamil", featured = true),
        SpokenLanguage("te", "Telugu", featured = true),
        SpokenLanguage("ur", "Urdu", featured = true),
        SpokenLanguage("af", "Afrikaans", featured = false),
        SpokenLanguage("am", "Amharic", featured = false),
        SpokenLanguage("ar", "Arabic", featured = false),
        SpokenLanguage("hy", "Armenian", featured = false),
        SpokenLanguage("ast", "Asturian", featured = false),
        SpokenLanguage("az", "Azerbaijani", featured = false),
        SpokenLanguage("be", "Belarusian", featured = false),
        SpokenLanguage("bs", "Bosnian", featured = false),
        SpokenLanguage("bg", "Bulgarian", featured = false),
        SpokenLanguage("my", "Burmese", featured = false),
        SpokenLanguage("yue", "Cantonese", featured = false),
        SpokenLanguage("ca", "Catalan", featured = false),
        SpokenLanguage("ceb", "Cebuano", featured = false),
        SpokenLanguage("ny", "Chichewa", featured = false),
        SpokenLanguage("hr", "Croatian", featured = false),
        SpokenLanguage("cs", "Czech", featured = false),
        SpokenLanguage("da", "Danish", featured = false),
        SpokenLanguage("nl", "Dutch", featured = false),
        SpokenLanguage("et", "Estonian", featured = false),
        SpokenLanguage("fil", "Filipino", featured = false),
        SpokenLanguage("fi", "Finnish", featured = false),
        SpokenLanguage("fr", "French", featured = false),
        SpokenLanguage("ff", "Fula", featured = false),
        SpokenLanguage("gl", "Galician", featured = false),
        SpokenLanguage("lg", "Ganda", featured = false),
        SpokenLanguage("ka", "Georgian", featured = false),
        SpokenLanguage("de", "German", featured = false),
        SpokenLanguage("el", "Greek", featured = false),
        SpokenLanguage("ha", "Hausa", featured = false),
        SpokenLanguage("he", "Hebrew", featured = false),
        SpokenLanguage("hu", "Hungarian", featured = false),
        SpokenLanguage("is", "Icelandic", featured = false),
        SpokenLanguage("ig", "Igbo", featured = false),
        SpokenLanguage("id", "Indonesian", featured = false),
        SpokenLanguage("ga", "Irish", featured = false),
        SpokenLanguage("it", "Italian", featured = false),
        SpokenLanguage("ja", "Japanese", featured = false),
        SpokenLanguage("jv", "Javanese", featured = false),
        SpokenLanguage("kea", "Kabuverdianu", featured = false),
        SpokenLanguage("kk", "Kazakh", featured = false),
        SpokenLanguage("km", "Khmer", featured = false),
        SpokenLanguage("ko", "Korean", featured = false),
        SpokenLanguage("ku", "Kurdish", featured = false),
        SpokenLanguage("ky", "Kyrgyz", featured = false),
        SpokenLanguage("lo", "Lao", featured = false),
        SpokenLanguage("lv", "Latvian", featured = false),
        SpokenLanguage("ln", "Lingala", featured = false),
        SpokenLanguage("lt", "Lithuanian", featured = false),
        SpokenLanguage("luo", "Luo", featured = false),
        SpokenLanguage("lb", "Luxembourgish", featured = false),
        SpokenLanguage("mk", "Macedonian", featured = false),
        SpokenLanguage("ms", "Malay", featured = false),
        SpokenLanguage("mt", "Maltese", featured = false),
        SpokenLanguage("zh", "Mandarin Chinese", featured = false),
        SpokenLanguage("mi", "Maori", featured = false),
        SpokenLanguage("mn", "Mongolian", featured = false),
        SpokenLanguage("nso", "Northern Sotho", featured = false),
        SpokenLanguage("no", "Norwegian", featured = false),
        SpokenLanguage("oc", "Occitan", featured = false),
        SpokenLanguage("ps", "Pashto", featured = false),
        SpokenLanguage("fa", "Persian", featured = false),
        SpokenLanguage("pl", "Polish", featured = false),
        SpokenLanguage("pt", "Portuguese", featured = false),
        SpokenLanguage("ro", "Romanian", featured = false),
        SpokenLanguage("ru", "Russian", featured = false),
        SpokenLanguage("sr", "Serbian", featured = false),
        SpokenLanguage("sn", "Shona", featured = false),
        SpokenLanguage("sd", "Sindhi", featured = false),
        SpokenLanguage("sk", "Slovak", featured = false),
        SpokenLanguage("sl", "Slovenian", featured = false),
        SpokenLanguage("so", "Somali", featured = false),
        SpokenLanguage("es", "Spanish", featured = false),
        SpokenLanguage("sw", "Swahili", featured = false),
        SpokenLanguage("sv", "Swedish", featured = false),
        SpokenLanguage("tg", "Tajik", featured = false),
        SpokenLanguage("th", "Thai", featured = false),
        SpokenLanguage("tr", "Turkish", featured = false),
        SpokenLanguage("uk", "Ukrainian", featured = false),
        SpokenLanguage("umb", "Umbundu", featured = false),
        SpokenLanguage("uz", "Uzbek", featured = false),
        SpokenLanguage("vi", "Vietnamese", featured = false),
        SpokenLanguage("cy", "Welsh", featured = false),
        SpokenLanguage("wo", "Wolof", featured = false),
        SpokenLanguage("xh", "Xhosa", featured = false),
        SpokenLanguage("zu", "Zulu", featured = false)
    )

    val options: List<SpokenLanguage> by lazy {
        listOf(
            SpokenLanguage(AUTO, TranscriptStrings.spokenLanguageAuto),
            SpokenLanguage(MIXED, TranscriptStrings.spokenLanguageMixed),
        ) + languages.filter { it.featured } + languages.filterNot { it.featured }
    }

    private val byCode: Map<String, SpokenLanguage> by lazy { options.associateBy { it.code } }

    fun isKnown(code: String?): Boolean = code != null && code.trim().lowercase(Locale.ROOT) in byCode

    fun normalise(code: String?): String {
        val key = code?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_').orEmpty()
        return if (key in byCode) key else AUTO
    }

    fun label(code: String?): String = byCode[normalise(code)]?.label ?: TranscriptStrings.spokenLanguageAuto

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).trim()

    fun filter(query: String): List<SpokenLanguage> {
        val q = fold(query)
        if (q.isEmpty()) return options
        val starts = options.filter { fold(it.label).startsWith(q) || it.code == q }
        val contains = options.filter { it !in starts && fold(it.label).contains(q) }
        return starts + contains
    }

    fun hint(source: String?): String? = when (source) {
        SOURCE_ARTISAN -> TranscriptStrings.spokenLanguageFromArtisan
        SOURCE_RESEARCHER, SOURCE_DEVICE -> TranscriptStrings.spokenLanguageFromResearcher
        else -> null
    }

    fun suggest(server: SpokenLanguageSuggested?, localLast: String?): SpokenLanguageSuggested? {
        if (server != null && isKnown(server.code) && normalise(server.code) != AUTO) {
            val code = normalise(server.code)
            return SpokenLanguageSuggested(code, label(code), server.source.ifBlank { SOURCE_RESEARCHER })
        }
        if (localLast != null && isKnown(localLast) && normalise(localLast) != AUTO) {
            val code = normalise(localLast)
            return SpokenLanguageSuggested(code, label(code), SOURCE_DEVICE)
        }
        return null
    }

    fun metadata(base: JsonObject?, code: String?, typeOrMime: String?): JsonObject? {
        if (code == null || !UploadTranscribe.eligible(typeOrMime)) return base
        return JsonObject((base ?: JsonObject(emptyMap())) + (METADATA_KEY to JsonPrimitive(normalise(code))))
    }

    fun wire(code: String?): String? = code?.let { normalise(it) }

    fun retranscribeStart(view: TranscriptSpokenLanguage?): String {
        val chosen = view?.chosen
        return if (chosen != null && isKnown(chosen)) normalise(chosen) else AUTO
    }
}

object SpokenLanguageChoices {
    private val chosen = androidx.compose.runtime.mutableStateMapOf<String, String>()

    fun forKey(key: String): String? = chosen[key]

    fun set(key: String, code: String) {
        chosen[key] = SpokenLanguages.normalise(code)
    }

    fun forget(key: String) {
        chosen.remove(key)
    }
}

object SpokenLanguageMemory {
    private const val PREFS = "spoken_language"
    private const val LAST = "last"

    fun last(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST, null)

    fun remember(context: Context, code: String?) {
        val value = SpokenLanguages.normalise(code)
        if (value == SpokenLanguages.AUTO) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LAST, value).apply()
    }
}
