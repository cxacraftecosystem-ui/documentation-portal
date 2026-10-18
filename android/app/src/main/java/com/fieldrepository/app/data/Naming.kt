package com.fieldrepository.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The one naming helper for this app (docs/conventions/NAMING_2026-10-18.md, the `naming` skill).
 *
 * Grammar: `Portal_Entity_Date[_Time]_Place_Craft_Subject_What[_Sequence][_n].ext` for file names, and
 * `Entity_Date_Place_Craft_Subject` for generated session titles. `_` separates fields, `-` joins the
 * words inside one. An unknown field is left out, never written as "Unknown".
 *
 * The SERVER mints every questionnaire upload's name at presign, so the app never names an uploaded
 * clip; this helper exists so the start sheet can preview the title the server will store, so an
 * offline session has the same title before it syncs, and so the golden table in
 * `shared/session-title.json` is checked on this runtime too (DESIGN §9 test 60).
 *
 * Transliteration is the shared rule of `shared/session-title.json` (`rules.transliteration`): the
 * Indic inherent-vowel pass, then the anyascii library, the same one the backend (Python anyascii)
 * and the web (npm any-ascii) use, so all three runtimes write the same file name. It is pure Java,
 * so it behaves the same on every Android version and on the JVM test classpath.
 */
object Naming {
    const val PORTAL = "FieldRepository"
    const val TITLE_MAX = 120
    const val FILE_MAX = 150
    private const val SUBJECT_FLOOR = 40

    val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm", Locale.ROOT)

    /** Capture-time sources that state a guessed time; a file name built from one carries no Time. */
    val ESTIMATED_SOURCES = setOf("SESSION_DATE", "UPLOAD_TIME", "UPLOAD_TIME_OUT_OF_WINDOW")

    private val WINDOWS_RESERVED = Regex("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$", RegexOption.IGNORE_CASE)

    /** What a title or a file name is built from. Every field is optional except where noted. */
    data class SessionFields(
        val sessionDate: LocalDate?,
        /** The session's own place text, e.g. "Bagru, Jaipur". */
        val place: String? = null,
        val workshopLocality: String? = null,
        val workshopDistrict: String? = null,
        val workshopState: String? = null,
        /** The workshop's craft; else [participantCrafts] decide. */
        val workshopCraft: String? = null,
        /** One entry per participant (null when that artisan has no craft). */
        val participantCrafts: List<String?> = emptyList(),
        /** Participant names: romanised name when stored, else the name as written. */
        val participantNames: List<String> = emptyList(),
        /** Overrides the mode-derived entity (`Workshop`, `Questionnaire`). */
        val entity: String? = null,
        /** Overrides the participant-derived subject (a code such as `DW:RJ:0012`). */
        val subject: String? = null
    )

    // ── Titles (DESIGN §3.12) ───────────────────────────────────────────────────────────────────

    fun entity(participantCount: Int): String = if (participantCount >= 2) "Group-Interview" else "Interview"

    /** `Interview_2026-07-01_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad-Chhipa`. Original script kept. */
    fun sessionTitle(fields: SessionFields): String {
        val parts = TitleParts(
            head = listOfNotNull(fields.entity ?: entity(fields.participantNames.size), fields.sessionDate?.format(DATE)),
            place = placeField(fields, ascii = false),
            craft = craftField(fields, ascii = false),
            subject = fields.subject?.let(::unicodeWords) ?: subjectField(fields.participantNames, ascii = false),
            tail = emptyList()
        )
        return parts.fit(TITLE_MAX)
    }

    // ── Recording file names (DESIGN §3.13) ─────────────────────────────────────────────────────

    /** A recording target as the name sees it: a section code, or a question display code ("D3"). */
    data class NameTarget(val sectionCode: String? = null, val displayCode: String? = null)

    data class RecordingFields(
        val session: SessionFields,
        val capturedAt: Instant,
        val capturedAtSource: String = "DEVICE",
        val targets: List<NameTarget> = emptyList(),
        /** 1-based take position; only a SOLO take at 2 or more carries `-T<n>`. */
        val position: Int = 1,
        val rosterCount: Int = 1,
        val partTwo: Boolean = false,
        val extension: String = "m4a",
        val what: String = "Recording"
    )

    fun recordingFileName(fields: RecordingFields, transliterate: (String) -> String = ::toAscii): String {
        val session = fields.session
        val day = fields.capturedAt.atZone(IST)
        val head = buildList {
            add(PORTAL)
            add(session.entity ?: entity(session.participantNames.size))
            add(day.format(DATE))
            if (fields.capturedAtSource !in ESTIMATED_SOURCES) add(day.format(TIME))
        }
        val sequence = sequenceField(fields)
        val parts = TitleParts(
            head = head,
            place = placeField(session, ascii = true, transliterate),
            craft = craftField(session, ascii = true, transliterate),
            subject = session.subject?.let { asciiWords(it, transliterate) } ?: subjectField(session.participantNames, ascii = true, transliterate),
            tail = listOfNotNull(asciiWords(fields.what, transliterate).joinToString("-").ifEmpty { null }, sequence)
        )
        val ext = fields.extension.trimStart('.').filter { it.isLetterOrDigit() && it.code < 128 }.lowercase(Locale.ROOT)
        val stem = guardReserved(parts.fit(FILE_MAX - (if (ext.isEmpty()) 0 else ext.length + 1)))
        return if (ext.isEmpty()) stem else "$stem.$ext"
    }

    /** `D3` → `D-Q03`; a section → `D-All`; several questions of one section → `F-Q01-Q06`. */
    fun sequenceField(fields: RecordingFields): String? {
        if (fields.targets.isEmpty()) return null
        val bySection = linkedMapOf<String, MutableList<Pair<Int, String>>>()
        val wholeSections = linkedSetOf<String>()
        for (target in fields.targets) {
            val code = target.displayCode?.let(::parseDisplayCode)
            if (code != null) {
                bySection.getOrPut(code.first) { mutableListOf() }.add(code.second to code.third)
            } else {
                target.sectionCode?.trim()?.takeIf { it.isNotEmpty() }?.let { wholeSections.add(asciiCode(it)) }
            }
        }
        val pieces = mutableListOf<String>()
        wholeSections.forEach { pieces.add("$it-All") }
        bySection.forEach { (section, numbers) ->
            if (section in wholeSections) return@forEach
            val sorted = numbers.sortedBy { it.first }
            val first = "Q" + sorted.first().first.toString().padStart(2, '0') + sorted.first().second
            pieces.add(
                if (sorted.size == 1) "$section-$first"
                else "$section-$first-Q" + sorted.last().first.toString().padStart(2, '0') + sorted.last().second
            )
        }
        if (pieces.isEmpty()) return null
        var sequence = pieces.joinToString("-")
        if (fields.partTwo) sequence += "-P2"
        else if (fields.rosterCount == 1 && fields.position >= 2) sequence += "-T${fields.position}"
        return sequence
    }

    /** "F7" → ("F", 7, ""); "D3a" → ("D", 3, "a"). Null for anything that is not a display code. */
    internal fun parseDisplayCode(code: String): Triple<String, Int, String>? {
        val match = Regex("^([A-Za-z]+)-?Q?(\\d+)([A-Za-z]?)$").matchEntire(code.trim()) ?: return null
        val number = match.groupValues[2].toIntOrNull() ?: return null
        return Triple(match.groupValues[1].uppercase(Locale.ROOT), number, match.groupValues[3].lowercase(Locale.ROOT))
    }

    /** `_2`, `_3` for names that collide inside one folder or zip, in the order given (createdAt, then id). */
    fun dedupeNames(names: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        return names.map { name ->
            val count = (seen[name] ?: 0) + 1
            seen[name] = count
            if (count == 1) name else {
                val dot = name.indexOf('.').takeIf { it > 0 }
                if (dot == null) "${name}_$count" else "${name.substring(0, dot)}_$count${name.substring(dot)}"
            }
        }
    }

    // ── Fields ─────────────────────────────────────────────────────────────────────────────────

    private fun placeField(fields: SessionFields, ascii: Boolean, transliterate: (String) -> String = { it }): List<String> {
        val own = fields.place?.takeIf { it.isNotBlank() }
        val raw = own ?: listOfNotNull(
            fields.workshopLocality?.takeIf { it.isNotBlank() },
            fields.workshopDistrict?.takeIf { it.isNotBlank() }
        ).joinToString(" ").ifBlank { null } ?: fields.workshopState?.takeIf { it.isNotBlank() }
        return raw?.let { words(it, ascii, transliterate) }.orEmpty()
    }

    private fun craftField(fields: SessionFields, ascii: Boolean, transliterate: (String) -> String = { it }): List<String> {
        val craft = fields.workshopCraft?.takeIf { it.isNotBlank() }
            ?: fields.participantCrafts.takeIf { crafts -> crafts.isNotEmpty() && crafts.all { !it.isNullOrBlank() } }
                ?.map { it!!.trim() }
                ?.distinctBy { it.lowercase(Locale.ROOT) }
                ?.singleOrNull()
        return craft?.let { words(it, ascii, transliterate) }.orEmpty()
    }

    private fun subjectField(names: List<String>, ascii: Boolean, transliterate: (String) -> String = { it }): List<String> = when {
        names.size >= 2 -> listOf("${names.size}", "Artisans")
        names.size == 1 -> words(names.single(), ascii, transliterate)
        else -> emptyList()
    }

    private fun words(raw: String, ascii: Boolean, transliterate: (String) -> String): List<String> =
        if (ascii) asciiWords(raw, transliterate) else unicodeWords(raw)

    /** Spaces and punctuation become word breaks; letters of any script are kept (titles only). */
    internal fun unicodeWords(raw: String): List<String> =
        raw.replace(':', '-')
            .split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .map(::titleCaseWord)

    /** The file-name form: transliterated, then ASCII letters and digits only. */
    internal fun asciiWords(raw: String, transliterate: (String) -> String): List<String> {
        val latin = if (raw.all { it.code < 128 }) raw else transliterate(raw)
        return latin.replace(':', '-')
            .split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotEmpty() }
            .map(::titleCaseWord)
    }

    private fun asciiCode(raw: String): String = raw.replace(':', '-').filter { it.code < 128 && (it.isLetterOrDigit() || it == '-') }

    /** Title-Case one word. A code, acronym or version (any digit, or all capitals) keeps its case. */
    internal fun titleCaseWord(word: String): String {
        if (word.any { it.isDigit() }) return word
        if (word.length > 1 && word.all { !it.isLetter() || it.isUpperCase() } && word.any { it.isLetter() }) return word
        val first = word.first()
        return if (first.isLowerCase()) first.titlecase(Locale.ROOT) + word.substring(1) else word
    }

    private fun guardReserved(stem: String): String {
        val first = stem.substringBefore('_')
        return if (WINDOWS_RESERVED.matches(first)) "${first}-File${stem.removePrefix(first)}" else stem
    }

    /** The fields of one name, joined only at the end so truncation can work on words. */
    private class TitleParts(
        val head: List<String>,
        var place: List<String>,
        var craft: List<String>,
        var subject: List<String>,
        val tail: List<String>
    ) {
        fun render(): String = buildList {
            addAll(head)
            place.takeIf { it.isNotEmpty() }?.let { add(it.joinToString("-")) }
            craft.takeIf { it.isNotEmpty() }?.let { add(it.joinToString("-")) }
            subject.takeIf { it.isNotEmpty() }?.let { add(it.joinToString("-")) }
            addAll(tail)
        }.joinToString("_")

        private fun len(field: List<String>) = field.sumOf { it.length } + (field.size - 1).coerceAtLeast(0)

        /** Subject first (down to 40), then Craft, then Place, at word boundaries. Never the head or tail. */
        fun fit(max: Int): String {
            while (render().length > max && subject.size > 1 && len(subject) > SUBJECT_FLOOR) subject = subject.dropLast(1)
            while (render().length > max && craft.size > 1) craft = craft.dropLast(1)
            while (render().length > max && place.size > 1) place = place.dropLast(1)
            while (render().length > max && subject.size > 1) subject = subject.dropLast(1)
            if (render().length > max) craft = emptyList()
            if (render().length > max) place = emptyList()
            if (render().length > max && subject.isNotEmpty()) {
                val room = max - render().length + len(subject)
                subject = if (room > 0) listOf(subject.first().take(room)) else emptyList()
            }
            return render()
        }
    }

    // ── Transliteration (shared/session-title.json rules.transliteration) ──────────────────────

    private val INDIC_BLOCKS = intArrayOf(0x0900, 0x0980, 0x0A00, 0x0A80, 0x0B00, 0x0B80, 0x0C00, 0x0C80, 0x0D00)

    private fun indicBlock(c: Char): Int? = INDIC_BLOCKS.firstOrNull { c.code in it until it + 0x80 }
    private fun isConsonant(off: Int) = off in 0x15..0x39 || off in 0x58..0x5F
    private fun isVowelSignOrVirama(off: Int) = off in 0x3E..0x4D || off in 0x55..0x57 || off == 0x62 || off == 0x63
    private fun isWordLetter(off: Int) = isConsonant(off) || off in 0x05..0x14 || off in 0x01..0x03 || off == 0x60 || off == 0x61

    /**
     * anyascii writes a Brahmic consonant without its inherent vowel (`प्रसाद` → `prsad`). Every nukta
     * is dropped, then an ASCII `a` goes after a consonant followed, in the same block, by a letter that
     * is not a vowel sign or virama. A consonant at the end of a word gets nothing (`गिरिराज` → `giriraj`).
     */
    fun indicInherentVowels(value: String): String {
        val chars = value.filter { c -> indicBlock(c)?.let { c.code - it == 0x3C } != true }
        val out = StringBuilder(chars.length + 8)
        chars.forEachIndexed { i, c ->
            out.append(c)
            val block = indicBlock(c) ?: return@forEachIndexed
            if (!isConsonant(c.code - block)) return@forEachIndexed
            val next = chars.getOrNull(i + 1) ?: return@forEachIndexed
            if (indicBlock(next) != block) return@forEachIndexed
            val off = next.code - block
            if (!isVowelSignOrVirama(off) && isWordLetter(off)) out.append('a')
        }
        return out.toString()
    }

    /** The shared transliteration: NFC, the inherent-vowel pass, then anyascii. */
    fun toAscii(value: String): String =
        if (value.isEmpty()) "" else com.anyascii.AnyAscii.transliterate(indicInherentVowels(java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC)))
}

/**
 * A phone number for display: `+91 98765 43210` (owner correction b). The country code lives in its
 * own column and the national number is digits only; +91 numbers are grouped 5 + 5, others are shown
 * as stored digits after the code. An empty number displays as nothing.
 */
object PhoneDisplay {
    fun format(countryCode: String?, national: String?): String {
        val digits = national.orEmpty().filter { it in '0'..'9' }
        if (digits.isEmpty()) return ""
        val code = countryCode?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.startsWith("+")) it else "+$it" } ?: "+91"
        val body = if (code == "+91" && digits.length == 10) "${digits.take(5)} ${digits.drop(5)}" else digits
        return "$code $body"
    }

    /** Valid national digits for [countryCode]: exactly 10 for +91, 4 to 14 elsewhere. */
    fun isValid(countryCode: String?, national: String?): Boolean {
        val digits = national.orEmpty().filter { it in '0'..'9' }
        if (digits.isEmpty()) return true
        return if ((countryCode ?: "+91").trim().removePrefix("+") == "91") digits.length == 10 else digits.length in 4..14
    }
}
