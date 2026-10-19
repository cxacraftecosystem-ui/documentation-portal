package com.fieldrepository.app.data

/**
 * The shared standard values (convergence point 10): phone, language, gender, tool tradition and
 * material family. The Android port of `backend/app/services/standard_values.py`.
 *
 * The lists below are `backend/app/data/standard_values.json` written out, and
 * `StandardValuesParityTest` holds them to that file and runs `shared/standard-values-vectors.json`
 * through every function here, the same table the server and the web run. This file is the same in
 * both portals apart from its package line. Change all of them together.
 *
 * Stored: phone digits in `phone` and the code in `phoneCountryCode` (+91 by default); a language
 * code ("bn"), shown by name ("Bengali"); the gender label; the TraditionType code; a material
 * family code.
 */
object StandardValues {
    data class Entry(val code: String, val label: String, val aliases: List<String> = emptyList())

    data class PhoneResult(val code: String?, val digits: String?, val error: String? = null)

    data class Normalised(val value: String?, val error: String? = null)

    const val PHONE_MESSAGE_INDIA = "Enter a 10-digit number for +91."
    const val COUNTRY_CODE_MESSAGE = "Choose a country code, such as +91."
    const val GENDER_MESSAGE = "Choose a gender from the list."
    const val LANGUAGE_MESSAGE = "Choose a language from the list."
    const val TRADITION_MESSAGE = "Choose a tradition type from the list."
    const val MATERIAL_MESSAGE = "Choose a material type from the list."

    const val DEFAULT_COUNTRY_CODE = "+91"
    val NATIONAL_DIGITS: Map<String, Pair<Int, Int>> = mapOf("+91" to (10 to 10))
    val OTHER_NATIONAL_DIGITS: Pair<Int, Int> = 4 to 14
    const val E164_MAX_DIGITS = 15

    /** `label` is the language's name. */
    val LANGUAGES: List<Entry> = listOf(
        Entry("hi", "Hindi", listOf("हिन्दी", "हिंदी", "hindi language")),
        Entry("en", "English", listOf("eng")),
        Entry("bn", "Bengali", listOf("Bangla", "Bengla", "Bangali", "Bengoli", "Bongali", "বাংলা")),
        Entry("mr", "Marathi", listOf("मराठी")),
        Entry("te", "Telugu", listOf("తెలుగు")),
        Entry("ta", "Tamil", listOf("தமிழ்")),
        Entry("gu", "Gujarati", listOf("ગુજરાતી")),
        Entry("ur", "Urdu", listOf("اردو")),
        Entry("kn", "Kannada", listOf("ಕನ್ನಡ")),
        Entry("or", "Odia", listOf("Oriya", "Odiya", "ଓଡ଼ିଆ")),
        Entry("ml", "Malayalam", listOf("മലയാളം")),
        Entry("pa", "Punjabi", listOf("Panjabi", "ਪੰਜਾਬੀ")),
        Entry("as", "Assamese", listOf("Asamiya", "অসমীয়া")),
        Entry("mai", "Maithili", listOf("मैथिली")),
        Entry("sa", "Sanskrit", listOf("संस्कृतम्", "संस्कृत")),
        Entry("kok", "Konkani", listOf("कोंकणी")),
        Entry("ne", "Nepali", listOf("नेपाली")),
        Entry("mni", "Manipuri (Meitei)", listOf("Manipuri", "Meitei", "Meetei")),
        Entry("brx", "Bodo", listOf("बड़ो")),
        Entry("doi", "Dogri", listOf("डोगरी")),
        Entry("ks", "Kashmiri", listOf("کٲشُر")),
        Entry("sat", "Santali", listOf("Santhali", "ᱥᱟᱱᱛᱟᱲᱤ")),
        Entry("sd", "Sindhi", listOf("سنڌي")),
        Entry("mis", "Other"),
    )

    val GENDERS: List<Entry> = listOf(
        Entry("MALE", "Male", listOf("M", "Man", "पुरुष")),
        Entry("FEMALE", "Female", listOf("F", "Woman", "महिला", "स्त्री")),
        Entry("TRANSGENDER", "Transgender", listOf("Third gender", "Trans")),
        Entry("OTHER", "Other"),
    )

    // Tradition (owner decision, 2026-10-19): a form offers "Traditional", "Contemporary" and
    // "Transitional (Hybrid)". UNKNOWN is the unanswered default and is never offered; MODERN and
    // HYBRID are retired codes an older row may still hold, read as their successors.
    val TRADITION_TYPES: List<Entry> = listOf(
        Entry("TRADITIONAL", "Traditional"),
        Entry("CONTEMPORARY", "Contemporary", listOf("MODERN")),
        Entry("TRANSITIONAL", "Transitional (Hybrid)", listOf("HYBRID", "Traditional form, contemporary use")),
        Entry("UNKNOWN", "Unknown", listOf("Not known")),
    )

    /** The stored state of a tradition question nobody answered. Never one of the offered answers. */
    const val TRADITION_UNANSWERED = "UNKNOWN"

    val RETIRED_TRADITION_CODES: Map<String, String> = mapOf("MODERN" to "CONTEMPORARY", "HYBRID" to "TRANSITIONAL")

    val MATERIAL_FAMILIES: List<Entry> = listOf(
        Entry("COTTON", "Cotton"),
        Entry("SILK", "Silk", listOf("Tussar", "Tasar", "Muga", "Eri")),
        Entry("WOOL", "Wool", listOf("Woollen", "Pashmina")),
        Entry("JUTE", "Jute"),
        Entry("LINEN", "Linen"),
        Entry("BLEND", "Blended yarn", listOf("Blend", "Blended")),
        Entry("BAMBOO", "Bamboo / cane", listOf("Bamboo", "Cane", "Rattan")),
        Entry("WOOD", "Wood", listOf("Wooden", "Timber", "Teak", "Sheesham", "Mango wood", "Lakdi", "लकड़ी")),
        Entry("CLAY", "Clay / terracotta", listOf("Clay", "Terracotta", "Mitti", "मिट्टी")),
        Entry(
            "METAL", "Metal",
            listOf("Iron", "Steel", "Brass", "Copper", "Bronze", "Aluminium", "Aluminum", "Bell metal", "Loha", "लोहा"),
        ),
        Entry("STONE", "Stone", listOf("Granite", "Marble", "Sandstone", "Pathar", "पत्थर")),
        Entry("LEATHER", "Leather", listOf("Hide")),
        Entry("GRASS", "Grass / fibre", listOf("Grass", "Fibre", "Fiber", "Sabai", "Moonj", "Coir")),
        Entry("PAPER", "Paper"),
        Entry("NATURAL_DYE", "Natural dye"),
        Entry("CHEMICAL_DYE", "Chemical dye"),
        Entry("OTHER", "Other"),
    )

    // Spelled out, never `\s`: the server and the web must match the same characters.
    private val FOLD_SEPARATORS = Regex("[ \t\r\n ‐-―_./,()\\[\\]-]+")
    private val PHONE_SEPARATORS = Regex("[ \t\r\n ‐-―().-]+")
    private val HEAD_SEPARATOR = Regex("[ \t ‐-―-]")
    private val COUNTRY_CODE = Regex("\\+[1-9][0-9]{0,3}")
    private val LANGUAGE_TAG = Regex("([a-z]{2,3})[-_][a-z]{2}")
    private val ASCII_DIGITS = Regex("[0-9]+")

    /** Lower case, separators collapsed to one space, trimmed. */
    fun fold(text: String?): String = text?.lowercase()?.replace(FOLD_SEPARATORS, " ")?.trim().orEmpty()

    private fun index(entries: List<Entry>, stored: (Entry) -> String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (entry in entries) for (spelling in listOf(entry.code, entry.label) + entry.aliases) out[fold(spelling)] = stored(entry)
        return out
    }

    private val LANGUAGE_INDEX = index(LANGUAGES) { it.code }
    private val GENDER_INDEX = index(GENDERS) { it.label }
    private val TRADITION_INDEX = index(TRADITION_TYPES) { it.code }
    private val MATERIAL_INDEX = index(MATERIAL_FAMILIES) { it.code }

    private fun normalise(index: Map<String, String>, message: String, value: String?, language: Boolean = false): Normalised {
        if (value.isNullOrBlank()) return Normalised(null)
        var found = index[fold(value)]
        if (found == null && language) {
            LANGUAGE_TAG.matchEntire(value.trim().lowercase())?.let { found = index[it.groupValues[1]] }
        }
        return found?.let { Normalised(it) } ?: Normalised(null, message)
    }

    fun normalizeLanguage(value: String?) = normalise(LANGUAGE_INDEX, LANGUAGE_MESSAGE, value, language = true)
    fun normalizeGender(value: String?) = normalise(GENDER_INDEX, GENDER_MESSAGE, value)
    fun normalizeTradition(value: String?) = normalise(TRADITION_INDEX, TRADITION_MESSAGE, value)
    fun normalizeMaterialFamily(value: String?) = normalise(MATERIAL_INDEX, MATERIAL_MESSAGE, value)

    /** The name a stored language code is shown by; a legacy free-text value is shown as stored. */
    fun languageName(code: String?): String? {
        if (code.isNullOrBlank()) return null
        return LANGUAGES.firstOrNull { it.code == code }?.label ?: code
    }

    /** The Language dropdown as (code, name) pairs. A stored legacy value is kept, first. */
    fun languageOptions(current: String?): List<Pair<String, String>> {
        val base = LANGUAGES.map { it.code to it.label }
        val existing = current.orEmpty()
        if (existing.isBlank() || base.any { it.first == existing }) return base
        return listOf(existing to existing) + base
    }

    val GENDER_LABELS: List<String> = GENDERS.map { it.label }
    /** The three answers a form offers, in order. */
    val TRADITION_OPTIONS: List<Pair<String, String>> =
        TRADITION_TYPES.filter { it.code != TRADITION_UNANSWERED }.map { it.code to it.label }

    /** What a tradition dropdown shows for a stored code: its offered answer, or "" when unanswered. */
    fun traditionChoice(code: String?): String {
        val current = if (code.isNullOrBlank()) "" else RETIRED_TRADITION_CODES[code] ?: code
        return if (TRADITION_OPTIONS.any { it.first == current }) current else ""
    }

    /** A stored code's label; a retired code reads as its successor's. */
    fun traditionLabel(code: String?): String? {
        val current = code?.let { RETIRED_TRADITION_CODES[it] ?: it }
        return TRADITION_TYPES.firstOrNull { it.code == current }?.label ?: code?.takeIf { it.isNotBlank() }
    }
    val MATERIAL_FAMILY_OPTIONS: List<Pair<String, String>> = MATERIAL_FAMILIES.map { it.code to it.label }

    fun materialFamilyLabel(code: String?): String? = MATERIAL_FAMILIES.firstOrNull { it.code == code }?.label

    /** The one family a free description names, or null when it names none or several. */
    fun materialFamilyFromText(text: String?): String? {
        val folded = " ${fold(text)} "
        if (folded.isBlank()) return null
        val found = mutableSetOf<String>()
        for ((spelling, code) in MATERIAL_INDEX) {
            if (code == "OTHER" || spelling.isEmpty()) continue
            if (folded.contains(" $spelling ")) found.add(code)
        }
        return found.singleOrNull()
    }

    // ── Phone ──────────────────────────────────────────────────────────────────────────────────

    private fun digitWindow(code: String): Pair<Int, Int> {
        NATIONAL_DIGITS[code]?.let { return it }
        val (low, high) = OTHER_NATIONAL_DIGITS
        return low to minOf(high, E164_MAX_DIGITS - (code.length - 1))
    }

    fun phoneMessage(code: String): String {
        if (code == "+91") return PHONE_MESSAGE_INDIA
        val (low, high) = digitWindow(code)
        return "Enter a valid phone number ($low–$high digits)."
    }

    /** "+91", "91" and "0091" -> "+91"; blank -> the default; null when malformed. */
    fun normalizeCountryCode(value: String?): String? {
        if (value.isNullOrBlank()) return DEFAULT_COUNTRY_CODE
        var text = value.replace(PHONE_SEPARATORS, "")
        text = when {
            text.startsWith("00") -> "+" + text.substring(2)
            !text.startsWith("+") -> "+$text"
            else -> text
        }
        return if (COUNTRY_CODE.matches(text)) text else null
    }

    /** (code, digits) for a number typed in any common form; both null when blank. */
    fun normalizePhone(countryCode: String?, number: String?): PhoneResult {
        if (number.isNullOrBlank()) return PhoneResult(null, null)
        val raw = number.trim()
        var code = normalizeCountryCode(countryCode) ?: return PhoneResult(null, null, COUNTRY_CODE_MESSAGE)
        var compact = raw.replace(PHONE_SEPARATORS, "")
        if (compact.startsWith("00") && compact.length > 2 && compact[2] != '0') compact = "+" + compact.substring(2)
        if (compact.startsWith("+")) {
            when {
                compact.startsWith(code) -> compact = compact.substring(code.length)
                compact.startsWith("+91") -> {
                    code = "+91"
                    compact = compact.substring(3)
                }
                else -> {
                    val head = raw.split(HEAD_SEPARATOR, limit = 2)
                    val embedded = if (head.size == 2) head[0].replace(PHONE_SEPARATORS, "") else ""
                    if (!COUNTRY_CODE.matches(embedded)) return PhoneResult(null, null, phoneMessage(code))
                    code = embedded
                    compact = compact.substring(embedded.length)
                }
            }
        }
        if (!ASCII_DIGITS.matches(compact)) return PhoneResult(null, null, phoneMessage(code))
        var digits = compact
        val codeDigits = code.substring(1)
        val (low, high) = digitWindow(code)
        if (digits.length > high && digits.startsWith(codeDigits)) digits = digits.substring(codeDigits.length)
        if (digits.length > low && digits.startsWith("0")) digits = digits.substring(1)
        if (digits.length < low || digits.length > high) return PhoneResult(null, null, phoneMessage(code))
        return PhoneResult(code, digits)
    }

    /** "+91 98765 43210". A legacy value that does not normalise is shown as stored. */
    fun formatPhone(countryCode: String?, number: String?): String? {
        if (number.isNullOrBlank()) return null
        val result = normalizePhone(countryCode, number)
        val digits = result.digits
        if (result.error != null || digits == null) return number.trim()
        if (result.code == "+91" && digits.length == 10) return "${result.code} ${digits.take(5)} ${digits.drop(5)}"
        return "${result.code} $digits"
    }

    /**
     * The one "+91 9876543210" string the phone box edits, from the two stored columns. A value
     * stored before the country-code column already carries its code, or none (a bare number).
     */
    fun phoneForEditing(countryCode: String?, number: String?): String {
        val text = number?.trim().orEmpty()
        if (text.isEmpty()) return ""
        if (text.startsWith("+") || countryCode.isNullOrBlank()) return text
        return "$countryCode $text"
    }
}
