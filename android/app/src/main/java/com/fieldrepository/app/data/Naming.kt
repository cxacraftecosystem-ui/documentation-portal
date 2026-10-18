package com.fieldrepository.app.data

import com.anyascii.AnyAscii
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One naming convention for every name either portal generates (owner correction, 18 Oct 2026).
 *
 * The handset's half of `backend/app/services/naming.py` and `frontend/lib/naming.ts`. All three
 * reproduce `shared/naming-golden.json` row for row (`NamingTest`). This file is identical in
 * designer-portal and documentation-portal apart from its package line; the portal word comes from
 * [NAMING_PORTAL].
 *
 *   File names:      Portal_Entity_Date[_Time]_Place_Craft_Subject_What[_Sequence][_n].ext
 *   Display titles:  Entity_Date_Place_Craft_Subject   (generated defaults only)
 *
 * Transliteration is the `anyascii` table, the same one the server and the browser use, so a
 * Devanagari name comes out of all three identically. (ICU's Any-Latin would spell it differently
 * from the other two.) Private storage names stay exempt and are not built here.
 */
object Naming {
    const val MAX_FILE_NAME = 150
    const val MAX_TITLE = 120
    const val MIN_SUBJECT = 40

    val IST: ZoneOffset = ZoneOffset.ofHoursMinutes(5, 30)

    private val RESERVED_STEMS: Set<String> =
        setOf("CON", "PRN", "AUX", "NUL") + (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }
    private val DOUBLE_EXTENSIONS = listOf(".sql.gz", ".tar.gz")
    private val VERSION = Regex("^v\\d+(?:\\.\\d+)*$")
    private val APOSTROPHES = Regex("['’ʼ`]")
    private val KEEP_FORMAT = setOf(0x200c, 0x200d)

    // ── Transliteration ──────────────────────────────────────────────────────────────────────────

    private const val INDIC_FIRST = 0x0900
    private const val INDIC_LAST = 0x0D7F
    private const val FINAL_SCHWA_DROPS_BELOW = 0x0B00
    private val EXTRA_CONSONANTS = setOf(0x09F0, 0x09F1)
    private val NASALS = setOf(0x0A70)
    private val NUKTA_FORMS = mapOf(
        0x0915 to 0x0958, 0x0916 to 0x0959, 0x0917 to 0x095A, 0x091C to 0x095B, 0x0921 to 0x095C,
        0x0922 to 0x095D, 0x092B to 0x095E, 0x092F to 0x095F, 0x09A1 to 0x09DC, 0x09A2 to 0x09DD,
        0x09AF to 0x09DF, 0x0A16 to 0x0A59, 0x0A17 to 0x0A5A, 0x0A1C to 0x0A5B, 0x0A2B to 0x0A5E,
        0x0A32 to 0x0A33, 0x0A38 to 0x0A36, 0x0B21 to 0x0B5C, 0x0B22 to 0x0B5D,
    )

    private fun indicOffset(cp: Int?): Int? =
        if (cp == null || cp < INDIC_FIRST || cp > INDIC_LAST) null else (cp - INDIC_FIRST) % 0x80

    private fun isConsonant(cp: Int?): Boolean {
        val off = indicOffset(cp) ?: return false
        return off in 0x15..0x39 || off in 0x58..0x5F || cp in EXTRA_CONSONANTS
    }

    private fun isNasal(cp: Int?): Boolean {
        val off = indicOffset(cp)
        return off == 0x01 || off == 0x02 || (cp != null && cp in NASALS)
    }

    private fun isSign(cp: Int?): Boolean {
        val off = indicOffset(cp) ?: return false
        return off in 0x3A..0x3B || off in 0x3E..0x4D || off in 0x55..0x57 || off in 0x62..0x63
    }

    private fun isIndicLetter(cp: Int?): Boolean {
        val off = indicOffset(cp) ?: return false
        return off in 0x01..0x63 || cp in EXTRA_CONSONANTS || cp in NASALS
    }

    private fun codePoints(text: String): List<Int> = text.codePoints().toArray().toList()

    private fun fromCodePoints(cps: List<Int>): String =
        buildString { cps.forEach { appendCodePoint(it) } }

    private fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

    private fun composeNukta(cps: List<Int>): List<Int> {
        val out = ArrayList<Int>(cps.size)
        for (cp in cps) {
            val last = out.lastOrNull()
            if (last != null && indicOffset(cp) == 0x3C && last in NUKTA_FORMS) {
                out[out.size - 1] = NUKTA_FORMS.getValue(last)
            } else {
                out.add(cp)
            }
        }
        return out
    }

    /** ASCII for any script, with the Brahmic inherent vowel kept where it is spoken. */
    fun transliterate(text: String?): String {
        val cps = composeNukta(codePoints(nfc(text ?: "")))
        val out = StringBuilder()
        var wordHasVowel = false
        for (i in cps.indices) {
            val cp = cps[i]
            val off = indicOffset(cp)
            if (off != 0x3C && off != 0x3D) {
                out.append(if (isNasal(cp)) "n" else AnyAscii.transliterate(fromCodePoints(listOf(cp))))
            }
            if (!isIndicLetter(cp)) {
                wordHasVowel = false
                continue
            }
            if (!isConsonant(cp)) {
                if (off != 0x4D && !isNasal(cp)) wordHasVowel = true
                continue
            }
            var j = i + 1
            while (j < cps.size && indicOffset(cps[j]) == 0x3C) j++
            val next = cps.getOrNull(j)
            if (isSign(next)) {
                if (indicOffset(next) != 0x4D) wordHasVowel = true
                continue
            }
            val wordEnd = !isIndicLetter(next) || (isNasal(next) && !isIndicLetter(cps.getOrNull(j + 1)))
            val drops = cp < FINAL_SCHWA_DROPS_BELOW && wordHasVowel
            if (!(wordEnd && drops)) {
                out.append('a')
                wordHasVowel = true
            }
        }
        return out.toString()
    }

    // ── Fields ───────────────────────────────────────────────────────────────────────────────────

    private fun cpLength(text: String): Int = text.codePointCount(0, text.length)

    private fun cpTake(text: String, n: Int): String = fromCodePoints(codePoints(text).take(n))

    private fun isUpperWord(word: String): Boolean =
        word == word.uppercase() && word != word.lowercase()

    private fun titleWord(word: String): String {
        if (word.isEmpty() || codePoints(word).any { Character.isDigit(it) || isNumberCategory(it) }) return word
        val cps = codePoints(word)
        val first = fromCodePoints(cps.take(1))
        val rest = fromCodePoints(cps.drop(1))
        if (isUpperWord(word)) return if (cps.size <= 3) word else first + rest.lowercase()
        return first.uppercase() + rest
    }

    private fun isNumberCategory(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

    private fun isMark(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    private fun isAsciiAlnum(cp: Int): Boolean =
        cp in 'A'.code..'Z'.code || cp in 'a'.code..'z'.code || cp in '0'.code..'9'.code

    private fun splitWords(text: String, asciiOnly: Boolean): List<String> {
        val words = ArrayList<String>()
        val current = StringBuilder()
        for (cp in codePoints(text.replace(APOSTROPHES, ""))) {
            val keep = if (asciiOnly) {
                isAsciiAlnum(cp)
            } else {
                Character.isLetter(cp) || isNumberCategory(cp) || isMark(cp) || cp in KEEP_FORMAT
            }
            if (keep) {
                current.appendCodePoint(cp)
            } else if (current.isNotEmpty()) {
                words.add(current.toString())
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) words.add(current.toString())
        return words.map(::titleWord)
    }

    /** One field: words Title-Cased and joined by `-`. ASCII unless [asciiOnly] is false. */
    fun field(value: Any?, asciiOnly: Boolean = true): String {
        if (value == null) return ""
        var text = nfc(value.toString()).trim()
        if (text.isEmpty()) return ""
        if (asciiOnly) text = transliterate(text)
        return splitWords(text, asciiOnly).joinToString("-")
    }

    /** `Locality-District`, else `District`, else `State`. A locality alone is not a place. */
    fun placeField(locality: Any? = null, district: Any? = null, state: Any? = null, asciiOnly: Boolean = true): String {
        val dist = field(district, asciiOnly)
        val loc = field(locality, asciiOnly)
        if (dist.isNotEmpty()) {
            return if (loc.isNotEmpty() && !loc.equals(dist, ignoreCase = true)) "$loc-$dist" else dist
        }
        return field(state, asciiOnly)
    }

    /** `D-Q03`, `D-Q03-T2` (a solo take at position 2 or more), `D-Q03-P2` (part 2). */
    fun questionSequence(section: Any?, question: Any?, take: Int? = null, part: Int? = null): String {
        val sec = field(section).uppercase()
        val q = question?.toString()?.trim().orEmpty()
        if (sec.isEmpty() || q.isEmpty()) return ""
        val number = q.toDoubleOrNull()?.toInt() ?: return ""
        val out = StringBuilder("$sec-Q${number.toString().padStart(2, '0')}")
        if (part != null && part >= 2) out.append("-P$part")
        if (take != null && take >= 2) out.append("-T$take")
        return out.toString()
    }

    /** `D-All`: a recording that covers a whole section. */
    fun sectionSequence(section: Any?): String {
        val sec = field(section).uppercase()
        return if (sec.isEmpty()) "" else "$sec-All"
    }

    private fun sequenceField(value: Any?): String {
        val text = value?.toString()?.trim().orEmpty()
        return if (VERSION.matches(text)) text else field(text)
    }

    // ── Dates (IST) ──────────────────────────────────────────────────────────────────────────────

    data class IstMoment(val date: String, val time: String?)

    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")
    private val DATE_ONLY = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val ZONED = Regex("(?:Z|[+-]\\d{2}:?\\d{2})$", RegexOption.IGNORE_CASE)

    /** The IST day and HHMM of a moment: [Instant], epoch millis, [LocalDate] or an ISO string. */
    fun asIst(value: Any?): IstMoment? {
        val instant: Instant = when (value) {
            null -> return null
            is LocalDate -> return IstMoment(value.format(DAY), null)
            is Instant -> value
            is OffsetDateTime -> value.toInstant()
            is LocalDateTime -> value.toInstant(ZoneOffset.UTC)
            is Long -> Instant.ofEpochMilli(value)
            is java.util.Date -> value.toInstant()
            is String -> {
                val text = value.trim()
                if (text.isEmpty()) return null
                if (DATE_ONLY.matches(text)) return IstMoment(text, null)
                runCatching {
                    if (ZONED.containsMatchIn(text)) OffsetDateTime.parse(text).toInstant()
                    else LocalDateTime.parse(text).toInstant(ZoneOffset.UTC)
                }.getOrNull() ?: return null
            }
            else -> return null
        }
        val local = instant.atOffset(IST)
        return IstMoment(local.format(DAY), local.format(HHMM))
    }

    // ── Assembly and truncation ──────────────────────────────────────────────────────────────────

    private class Cuttable(var place: String, var craft: String, var subject: String) {
        operator fun get(key: String): String = when (key) {
            "place" -> place
            "craft" -> craft
            else -> subject
        }

        operator fun set(key: String, value: String) {
            when (key) {
                "place" -> place = value
                "craft" -> craft = value
                else -> subject = value
            }
        }
    }

    private fun dropLastWord(text: String): String {
        val at = text.lastIndexOf('-')
        return if (at >= 0) text.substring(0, at) else ""
    }

    private fun fit(parts: Cuttable, render: (Cuttable) -> String, limit: Int): String {
        var name = render(parts)
        for ((key, floor) in listOf("subject" to MIN_SUBJECT, "craft" to 0, "place" to 0, "subject" to 0)) {
            while (cpLength(name) > limit && cpLength(parts[key]) > floor) {
                var shorter = dropLastWord(parts[key])
                if (floor > 0 && shorter.isEmpty()) shorter = cpTake(parts[key], floor)
                parts[key] = shorter
                name = render(parts)
            }
        }
        return name
    }

    private fun extension(ext: String?): String {
        var text = ext.orEmpty().trim().lowercase().trimStart('.')
        text = text.replace(Regex("[^a-z0-9.]"), "").replace(Regex("\\.{2,}"), ".").trim('.')
        return if (text.isEmpty()) "" else ".$text"
    }

    /** `Portal_Entity_Date[_Time]_Place_Craft_Subject_What[_Sequence][_n].ext`, ASCII, <= 150. */
    fun fileName(
        entity: Any? = null,
        what: Any? = null,
        ext: String = "",
        portal: String? = null,
        date: Any? = null,
        time: Boolean = false,
        period: Any? = null,
        place: Any? = null,
        craft: Any? = null,
        subject: Any? = null,
        sequence: Any? = null,
        n: Int? = null,
    ): String {
        val hasPeriod = period != null && period.toString().isNotEmpty()
        val moment = if (hasPeriod) null else asIst(date)
        val fixedPortal = field(if (portal.isNullOrEmpty()) NAMING_PORTAL else portal)
        val fixedEntity = field(entity)
        val fixedDate = if (hasPeriod) field(period) else moment?.date.orEmpty()
        val fixedTime = if (time && !hasPeriod) moment?.time.orEmpty() else ""
        val fixedWhat = field(what)
        val fixedSequence = sequenceField(sequence)
        val fixedN = if (n != null && n >= 2) n.toString() else ""
        val extText = extension(ext)
        val parts = Cuttable(field(place), field(craft), field(subject))
        val render = { p: Cuttable ->
            listOf(fixedPortal, fixedEntity, fixedDate, fixedTime, p.place, p.craft, p.subject, fixedWhat, fixedSequence, fixedN)
                .filter { it.isNotEmpty() }
                .joinToString("_") + extText
        }
        return fit(parts, render, MAX_FILE_NAME)
    }

    /** A download, export, report or backup, dated the day it is generated (IST). */
    fun exportFilename(
        entity: Any?,
        what: Any?,
        ext: String,
        at: Any? = null,
        withTime: Boolean = false,
        dated: Boolean = true,
        period: Any? = null,
        place: Any? = null,
        craft: Any? = null,
        subject: Any? = null,
        sequence: Any? = null,
        n: Int? = null,
        portal: String? = null,
    ): String {
        val hasPeriod = period != null && period.toString().isNotEmpty()
        val whenValue = if (dated && !hasPeriod) (at ?: Instant.now()) else null
        return fileName(
            entity = entity, what = what, ext = ext, portal = portal, date = whenValue, time = withTime,
            period = period, place = place, craft = craft, subject = subject, sequence = sequence, n = n,
        )
    }

    /** A capture, dated and timed when it was captured. An estimated time is left out. */
    fun recordingFilename(
        entity: Any?,
        ext: String,
        capturedAt: Any? = null,
        timeKnown: Boolean = true,
        place: Any? = null,
        craft: Any? = null,
        subject: Any? = null,
        sequence: Any? = null,
        what: Any? = "Recording",
        n: Int? = null,
        portal: String? = null,
    ): String = fileName(
        entity = entity, what = what, ext = ext, portal = portal, date = capturedAt, time = timeKnown,
        place = place, craft = craft, subject = subject, sequence = sequence, n = n,
    )

    /**
     * A design workshop's report, cards or list: `..._Workshop_Date_Place_Craft_Code_What.ext`. The
     * server, the web's offline report and this handset name one report identically through this.
     */
    fun workshopFilename(
        workshopCode: String?,
        title: String?,
        clusterName: String?,
        district: String?,
        state: String?,
        craftName: String?,
        what: Any?,
        ext: String,
        at: Any? = null,
        dated: Boolean = true,
        n: Int? = null,
        portal: String? = null,
    ): String = exportFilename(
        entity = "Workshop",
        what = what,
        ext = ext,
        at = at,
        dated = dated,
        place = placeField(clusterName, district, state),
        craft = craftName,
        subject = workshopCode?.takeIf { it.isNotEmpty() } ?: title,
        n = n,
        portal = portal,
    )

    /** One participant of a session: the stored romanised name wins over the name as written. */
    data class Participant(val name: String?, val romanisedName: String? = null) {
        val shown: String get() = (romanisedName?.takeIf { it.isNotEmpty() } ?: name).orEmpty()
    }

    /** `Entity_Date_Place_Craft_Subject` for a generated interview, session or workshop title. */
    fun sessionTitle(
        participants: List<Participant> = emptyList(),
        participantCount: Int? = null,
        date: Any? = null,
        place: Any? = null,
        craft: Any? = null,
        entity: String? = null,
        subject: Any? = null,
    ): String {
        val people = participants.filter { it.shown.isNotEmpty() }
        val count = if (people.isNotEmpty()) people.size else (participantCount ?: 0)
        val fixedEntity = field(if (entity.isNullOrEmpty()) (if (count >= 2) "Group-Interview" else "Interview") else entity)
        var subjectValue: Any? = subject?.takeIf { it.toString().isNotEmpty() }
        if (subjectValue == null) {
            subjectValue = when {
                count >= 2 -> "$count Artisans"
                people.isNotEmpty() -> people.first().shown
                else -> null
            }
        }
        val fixedDate = asIst(date)?.date.orEmpty()
        val parts = Cuttable(field(place, false), field(craft, false), field(subjectValue, false))
        val render = { p: Cuttable ->
            listOf(fixedEntity, fixedDate, p.place, p.craft, p.subject).filter { it.isNotEmpty() }.joinToString("_")
        }
        return fit(parts, render, MAX_TITLE)
    }

    // ── Leaves and collisions ────────────────────────────────────────────────────────────────────

    fun splitExtension(name: String): Pair<String, String> {
        val lower = name.lowercase()
        for (double in DOUBLE_EXTENSIONS) {
            if (lower.endsWith(double) && name.length > double.length) {
                return name.substring(0, name.length - double.length) to name.substring(name.length - double.length)
            }
        }
        val at = name.lastIndexOf('.')
        if (at > 0) {
            val ext = name.substring(at + 1)
            if (ext.length in 1..10 && ext.all { it.code < 128 && it.isLetterOrDigit() }) {
                return name.substring(0, at) to ".$ext"
            }
        }
        return name to ""
    }

    /** Any name made safe as an S3 leaf or a download name: ASCII `[A-Za-z0-9._-]`, <= 150. */
    fun asciiLeaf(name: Any?): String {
        var text = nfc(name?.toString().orEmpty()).trim().replace('\\', '/')
        text = text.substring(text.lastIndexOf('/') + 1)
        val (rawStem, rawExt) = splitExtension(text)
        val ext = extension(rawExt)
        var stem = transliterate(rawStem).replace(APOSTROPHES, "")
        stem = stem.replace(Regex("[^A-Za-z0-9._-]+"), "-").replace(Regex("-{2,}"), "-").replace(Regex("_{2,}"), "_")
        stem = stem.trim('-', '.', '_')
        if (stem.isEmpty()) stem = "File"
        if (stem.substringBefore('.').uppercase() in RESERVED_STEMS) stem = "$stem-File"
        val room = MAX_FILE_NAME - ext.length
        if (stem.length > room) stem = stem.substring(0, room).trimEnd('-', '.', '_').ifEmpty { "File" }
        return stem + ext
    }

    /** The second and later copies of a name get `_2`, `_3`, in the order given (createdAt, then id). */
    fun dedupeNames(names: Iterable<String>): List<String> {
        val used = HashSet<String>()
        val out = ArrayList<String>()
        for (name in names) {
            val (stem, ext) = splitExtension(name)
            var candidate = name
            var n = 1
            while (candidate.lowercase() in used) {
                n += 1
                candidate = "${stem}_$n$ext"
            }
            used.add(candidate.lowercase())
            out.add(candidate)
        }
        return out
    }
}
