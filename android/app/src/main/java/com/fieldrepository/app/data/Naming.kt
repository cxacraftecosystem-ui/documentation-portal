package com.fieldrepository.app.data

import com.anyascii.AnyAscii
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * THE one naming convention for generated names (owner correction a, 18 Oct 2026).
 *
 * The handset's half of `backend/app/services/naming.py` and `frontend/lib/naming.ts`, rule for
 * rule. All three reproduce two golden tables row for row (`NamingTest`): `shared/session-title.json`
 * (the frozen questionnaire contract: titles, recording names, transliteration, exports, collisions)
 * and `shared/naming-golden.json` (every other generated name). This file is identical in
 * designer-portal and documentation-portal apart from its package line; the portal word comes from
 * [NAMING_PORTAL].
 *
 *   title      Entity_Date_Place_Craft_Subject                                  (<= 120, any script)
 *   file name  Portal_Entity_Date[_Time]_Place_Craft_Subject_What[_Sequence][_n].ext   (<= 150, ASCII)
 *
 * Transliteration is the `anyascii` table plus the contract's Indic inherent-vowel rule, the same as
 * the server and the browser, so a Devanagari name comes out of all three identically (ICU's
 * Any-Latin would spell it differently). The questionnaire screens use [SessionFields] and
 * [RecordingFields]; the rest of the app passes plain fields. Both are one implementation.
 */
object Naming {
    const val PORTAL = NAMING_PORTAL
    const val TITLE_MAX = 120
    const val FILE_MAX = 150
    const val SUBJECT_MIN = 40
    const val MAX_FILE_NAME = FILE_MAX
    const val MAX_TITLE = TITLE_MAX
    const val MIN_SUBJECT = SUBJECT_MIN

    val IST: ZoneOffset = ZoneOffset.ofHoursMinutes(5, 30)

    /** Capture-time sources that state a guessed time; a file name built from one carries no Time. */
    val ESTIMATED_SOURCES = setOf("SESSION_DATE", "UPLOAD_TIME", "UPLOAD_TIME_OUT_OF_WINDOW")

    private val RESERVED_STEMS: Set<String> =
        setOf("CON", "PRN", "AUX", "NUL") + (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }
    private val DOUBLE_EXTENSIONS = listOf(".sql.gz", ".tar.gz")
    private val VERSION = Regex("^v\\d+(?:\\.\\d+)*$")
    // A question number is read from the display code AFTER its own section code: "F21" in section F2
    // is question 1, not section F question 21. The section is never guessed from the display code.
    private val CODE_GAP = Regex("[ \\t\\r\\n._-]+")
    private val NUMBER_TAIL = Regex("^[A-Z]*([0-9]+)$")
    private val DIGITS = Regex("^[0-9]+$")
    private val ZW = setOf(0x200c, 0x200d)

    // ── Indic inherent vowel (session-title.json rules.transliteration) ─────────────────────────

    private val INDIC_BLOCKS = intArrayOf(0x0900, 0x0980, 0x0A00, 0x0A80, 0x0B00, 0x0B80, 0x0C00, 0x0C80, 0x0D00)

    private fun indic(cp: Int): Pair<Int, Int>? {
        for (base in INDIC_BLOCKS) if (cp >= base && cp < base + 0x80) return base to (cp - base)
        return null
    }

    private fun isConsonant(off: Int) = off in 0x15..0x39 || off in 0x58..0x5F
    private fun isVowelSignOrVirama(off: Int) = off in 0x3E..0x4D || off in 0x55..0x57 || off == 0x62 || off == 0x63
    private fun isWordLetter(off: Int) = isConsonant(off) || off in 0x05..0x14 || off in 0x01..0x03 || off == 0x60 || off == 0x61

    private fun codePoints(text: String): List<Int> = text.codePoints().toArray().toList()
    private fun fromCodePoints(cps: List<Int>): String = buildString { cps.forEach { appendCodePoint(it) } }
    private fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
    private fun cpLength(text: String): Int = text.codePointCount(0, text.length)

    /** Drop every nukta, then insert the Indic inherent "a" that anyascii leaves out (shared rules). */
    fun indicInherentVowels(value: String): String {
        val chars = codePoints(value).filter { indic(it)?.second != 0x3C }
        val out = ArrayList<Int>(chars.size * 2)
        for (i in chars.indices) {
            out.add(chars[i])
            val info = indic(chars[i]) ?: continue
            if (isConsonant(info.second) && i + 1 < chars.size) {
                val next = indic(chars[i + 1])
                if (next != null && next.first == info.first && !isVowelSignOrVirama(next.second) && isWordLetter(next.second)) {
                    out.add('a'.code)
                }
            }
        }
        return fromCodePoints(out)
    }

    /** Transliterate to ASCII: the inherent-vowel pass, then anyascii (the table Python and npm use). */
    fun toAscii(value: String?): String =
        if (value.isNullOrEmpty()) "" else AnyAscii.transliterate(indicInherentVowels(nfc(value)))

    /** ASCII for any script: the same as [toAscii]. */
    fun transliterate(text: String?): String = toAscii(text)

    // ── Words and fields ─────────────────────────────────────────────────────────────────────────

    private fun isWordChar(cp: Int): Boolean {
        if (cp in ZW) return true
        return when (Character.getType(cp).toByte()) {
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
            Character.MODIFIER_LETTER, Character.OTHER_LETTER,
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK,
            Character.DECIMAL_DIGIT_NUMBER -> true
            else -> false
        }
    }

    private fun text(value: Any?): String = value?.toString().orEmpty()

    /** Words end at every character that is not a letter, a mark, a decimal digit, ZWNJ or ZWJ. */
    fun splitWords(value: Any?): List<String> {
        val words = ArrayList<String>()
        val current = StringBuilder()
        for (cp in codePoints(nfc(text(value)))) {
            if (isWordChar(cp)) {
                current.appendCodePoint(cp)
            } else if (current.isNotEmpty()) {
                words.add(current.toString())
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) words.add(current.toString())
        return words
    }

    private val ACRONYM = Regex("^[A-Z]{2,3}$")

    /** Title-Case one word; a word with a digit, or a 2-3 letter upper-case Latin word, keeps its case. */
    fun titleCaseWord(word: String): String {
        val cps = codePoints(word)
        if (cps.any { Character.getType(it).toByte() == Character.DECIMAL_DIGIT_NUMBER }) return word
        if (ACRONYM.matches(word)) return word
        val first = cps.firstOrNull() ?: return word
        val head = fromCodePoints(listOf(first))
        if (Character.isLetter(first) && head.uppercase(Locale.ROOT) != head.lowercase(Locale.ROOT)) {
            return head.uppercase(Locale.ROOT) + fromCodePoints(cps.drop(1)).lowercase(Locale.ROOT)
        }
        return word
    }

    private fun isAsciiAlnum(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9'

    fun fieldWords(value: Any?, asciiOnly: Boolean): List<String> {
        val raw = text(value)
        var words = splitWords(if (asciiOnly) toAscii(raw) else raw)
        if (asciiOnly) words = words.map { w -> w.filter(::isAsciiAlnum) }.filter { it.isNotEmpty() }
        return words.map(::titleCaseWord)
    }

    private fun joinField(words: List<String>) = words.joinToString("-")
    private fun fields(vararg parts: String?) = parts.filter { !it.isNullOrEmpty() }.joinToString("_")

    /** One field: words Title-Cased and joined by `-`. ASCII unless [asciiOnly] is false. */
    fun field(value: Any?, asciiOnly: Boolean = true): String = joinField(fieldWords(value, asciiOnly))

    /** The same words, ignoring case; also when one is written in another script (Majuli, माजुली). */
    private fun same(a: Any?, b: Any?): Boolean {
        if (splitWords(a).joinToString(" ").lowercase(Locale.ROOT) == splitWords(b).joinToString(" ").lowercase(Locale.ROOT)) return true
        val fa = field(a).lowercase(Locale.ROOT)
        return fa.isNotEmpty() && fa == field(b).lowercase(Locale.ROOT)
    }

    private fun placeText(locality: Any?, district: Any?, state: Any?): String? {
        val loc = text(locality).trim()
        val dist = text(district).trim()
        val st = text(state).trim()
        if (loc.isNotEmpty() && dist.isNotEmpty() && !same(loc, dist)) return "$loc $dist"
        return loc.ifEmpty { null } ?: dist.ifEmpty { null } ?: st.ifEmpty { null }
    }

    /** The Place field: `Locality-District`, else `Locality`, else `District`, else `State`. */
    fun placeField(locality: Any? = null, district: Any? = null, state: Any? = null, asciiOnly: Boolean = true): String =
        field(placeText(locality, district, state), asciiOnly)

    // ── The inputs ───────────────────────────────────────────────────────────────────────────────

    /** The questionnaire shape of a title's inputs. */
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
        /** Each participant's name in its own script, when [participantNames] holds the romanised one. */
        val participantLocalNames: List<String?> = emptyList(),
        /** Override (designer portal: "Workshop"); else derived from the participants. */
        val entity: String? = null,
        /** Override (a design workshop's code). */
        val subject: String? = null,
    )

    /** One participant in the plain-field shape: the stored romanised name wins over the name as written. */
    data class Participant(val name: String?, val romanisedName: String? = null) {
        val shown: String get() = (romanisedName?.takeIf { it.isNotEmpty() } ?: name).orEmpty()
    }

    private class Person(val name: String?, val localName: String? = null)

    private class Input(
        val sessionDate: LocalDate?,
        val place: String?,
        val locality: String? = null,
        val district: String? = null,
        val state: String? = null,
        val craft: String? = null,
        val people: List<Person> = emptyList(),
        val crafts: List<String?> = emptyList(),
        val entity: String? = null,
        val subject: String? = null,
        val participantCount: Int? = null,
    )

    private fun SessionFields.input() = Input(
        sessionDate = sessionDate, place = place, locality = workshopLocality, district = workshopDistrict,
        state = workshopState, craft = workshopCraft, crafts = participantCrafts, entity = entity, subject = subject,
        people = participantNames.mapIndexed { i, name -> Person(name, participantLocalNames.getOrNull(i)) },
    )

    private fun placeSource(inp: Input): String? =
        inp.place?.takeIf { it.isNotBlank() } ?: placeText(inp.locality, inp.district, inp.state)

    private fun craftSource(inp: Input): String? {
        inp.craft?.takeIf { it.isNotBlank() }?.let { return it }
        val crafts = inp.crafts.map { it?.trim().orEmpty() }
        if (crafts.isNotEmpty() && crafts.all { it.isNotEmpty() } && crafts.all { same(it, crafts[0]) }) return crafts[0]
        return null
    }

    private fun count(inp: Input): Int = if (inp.people.isNotEmpty()) inp.people.size else (inp.participantCount ?: 0)

    /** Interview for 0 or 1 participant; Group-Interview for 2 or more. */
    fun entity(participantCount: Int): String = if (participantCount >= 2) "Group-Interview" else "Interview"

    private fun entityOf(inp: Input): String = inp.entity?.takeIf { it.isNotEmpty() } ?: entity(count(inp))

    private fun subjectSource(inp: Input, asciiOnly: Boolean): String? {
        inp.subject?.takeIf { it.isNotEmpty() }?.let { return it }
        val n = count(inp)
        if (n >= 2) return "$n Artisans"
        if (n == 1 && inp.people.isNotEmpty()) {
            val person = inp.people[0]
            if (asciiOnly) {
                // A stored romanised name wins over transliteration.
                for (candidate in listOf(person.name, person.localName)) {
                    if (!candidate.isNullOrBlank() && candidate.all { it.code < 128 }) return candidate
                }
            }
            return person.name?.takeIf { it.isNotEmpty() } ?: person.localName
        }
        return null
    }

    /** Subject first (to 40, at a word boundary), then Craft, Place, Subject again, drop Craft, Place, cut Subject. */
    private fun fit(
        render: (List<String>, List<String>, List<String>) -> String,
        placeWords: List<String>,
        craftWords: List<String>,
        subjectWords: List<String>,
        limit: Int,
    ): String {
        val place = placeWords.toMutableList()
        val craft = craftWords.toMutableList()
        val subject = subjectWords.toMutableList()
        fun tooLong() = cpLength(render(place, craft, subject)) > limit
        while (tooLong() && subject.size > 1 && cpLength(joinField(subject)) > SUBJECT_MIN) subject.removeAt(subject.size - 1)
        while (tooLong() && craft.size > 1) craft.removeAt(craft.size - 1)
        while (tooLong() && place.size > 1) place.removeAt(place.size - 1)
        while (tooLong() && subject.size > 1) subject.removeAt(subject.size - 1)
        if (tooLong() && craft.isNotEmpty()) craft.clear()
        if (tooLong() && place.isNotEmpty()) place.clear()
        while (tooLong() && subject.isNotEmpty() && cpLength(subject[0]) > 1) {
            val cps = codePoints(subject[0])
            subject[0] = fromCodePoints(cps.dropLast(1))
        }
        if (tooLong()) subject.clear()
        return render(place, craft, subject)
    }

    // ── Titles ───────────────────────────────────────────────────────────────────────────────────

    private fun title(inp: Input): String {
        val entityField = joinField(fieldWords(entityOf(inp), false))
        val day = inp.sessionDate?.format(DAY)
        return fit(
            { p, c, s -> fields(entityField, day, joinField(p), joinField(c), joinField(s)) },
            fieldWords(placeSource(inp), false),
            fieldWords(craftSource(inp), false),
            fieldWords(subjectSource(inp, false), false),
            TITLE_MAX,
        )
    }

    /** `Interview_2026-07-01_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad-Chhipa`. Original script kept. */
    fun sessionTitle(fields: SessionFields): String = title(fields.input())

    /** `Entity_Date_Place_Craft_Subject` for a generated interview, session or workshop title. */
    fun sessionTitle(
        participants: List<Participant> = emptyList(),
        participantCount: Int? = null,
        date: Any? = null,
        place: Any? = null,
        craft: Any? = null,
        entity: String? = null,
        subject: Any? = null,
    ): String = title(
        Input(
            sessionDate = asIst(date)?.let { LocalDate.parse(it.date) },
            place = place?.toString(),
            craft = craft?.toString(),
            people = participants.filter { it.shown.isNotEmpty() }.map { p ->
                if (!p.romanisedName.isNullOrEmpty()) Person(p.romanisedName, p.name) else Person(p.name)
            },
            entity = entity,
            subject = subject?.toString()?.takeIf { it.isNotEmpty() },
            participantCount = participantCount?.takeIf { it > 0 },
        )
    )

    // ── Dates (IST) ──────────────────────────────────────────────────────────────────────────────

    data class IstMoment(val date: String, val time: String?)

    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm", Locale.ROOT)
    private val DATE_ONLY = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val ZONED = Regex("(?:Z|[+-]\\d{2}:?\\d{2})$", RegexOption.IGNORE_CASE)

    /** The IST day and HHMM of a moment: [Instant], epoch millis, [LocalDate] or an ISO string. Naive = UTC. */
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
                val t = value.trim()
                if (t.isEmpty()) return null
                if (DATE_ONLY.matches(t)) return IstMoment(t, null)
                runCatching {
                    if (ZONED.containsMatchIn(t)) OffsetDateTime.parse(t).toInstant()
                    else LocalDateTime.parse(t).toInstant(ZoneOffset.UTC)
                }.getOrNull() ?: return null
            }
            else -> return null
        }
        val local = instant.atOffset(IST)
        return IstMoment(local.format(DAY), local.format(HHMM))
    }

    // ── File names ───────────────────────────────────────────────────────────────────────────────

    private fun guard(stem: String) = if (stem.uppercase(Locale.ROOT) in RESERVED_STEMS) "$stem-File" else stem

    private fun extension(ext: Any?): String =
        text(ext).trim().trimStart('.').filter { isAsciiAlnum(it) || it == '.' }.lowercase(Locale.ROOT)
            .replace(Regex("\\.{2,}"), ".").trim('.')

    private fun sequenceText(value: Any?): String? {
        val t = text(value).trim()
        if (t.isEmpty()) return null
        if (VERSION.matches(t)) return t
        return joinField(fieldWords(t, true)).ifEmpty { null }
    }

    /**
     * `Portal_Entity_Date[_Time]_Place_Craft_Subject_What[_Sequence][_n].ext`, ASCII, <= 150. The Date
     * field is [period] (a field such as `FY2026-27`), else the IST day of [date]; [time] adds that
     * moment's HHMM, [clock] gives an HHMM directly. An empty [ext] gives a bare stem.
     */
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
        clock: String? = null,
    ): String {
        val hasPeriod = period != null && period.toString().isNotEmpty()
        val moment = if (hasPeriod) null else asIst(date)
        val day = if (hasPeriod) joinField(fieldWords(period, true)) else moment?.date
        val hhmm = when {
            !clock.isNullOrEmpty() -> clock
            time && !hasPeriod -> moment?.time
            else -> null
        }
        val extText = extension(ext)
        val portalField = (if (portal.isNullOrEmpty()) PORTAL else portal).filter { isAsciiAlnum(it) || it == '-' }
        val entityField = joinField(fieldWords(entity, true))
        val whatField = joinField(fieldWords(what, true))
        val sequenceField = sequenceText(sequence)
        val nField = if (n != null && n > 1) n.toString() else null
        return fit(
            { p, c, s ->
                val stem = guard(fields(portalField, entityField, day, hhmm, joinField(p), joinField(c), joinField(s), whatField, sequenceField, nField))
                if (extText.isEmpty()) stem else "$stem.$extText"
            },
            fieldWords(place, true),
            fieldWords(craft, true),
            fieldWords(subject, true),
            FILE_MAX,
        )
    }

    /**
     * A recording target. [sectionCode] is the question's own section and is the only source of the
     * section piece. A question carries [questionNumber], or a [displayCode] read relative to the
     * section code; neither one means the whole section.
     */
    data class NameTarget(val sectionCode: String? = null, val displayCode: String? = null, val questionNumber: Int? = null)

    private fun compactCode(text: String?): String = CODE_GAP.replace(text.orEmpty().trim(), "").uppercase(Locale.ROOT)

    /**
     * The question number in [displayCode] once [sectionCode] is taken off its front: ("F2", "F21") -> 1,
     * ("F2", "F2.13") -> 13, ("D", "D03") -> 3. A display code that does not start with the section code
     * gives its trailing digits (("F2", "Q4") -> 4).
     */
    fun questionNumber(sectionCode: String?, displayCode: String?): Int? {
        val code = compactCode(displayCode)
        val sec = compactCode(sectionCode)
        val rest = if (sec.isNotEmpty() && code.startsWith(sec)) code.substring(sec.length) else ""
        if (DIGITS.matches(rest)) return rest.toIntOrNull()
        return NUMBER_TAIL.matchEntire(code)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** `D-Q03`, `B-All`, `F-Q01-Q06`, plus `-P2` for a continuation or `-T2` for a solo repeat. */
    fun recordingSequence(targets: List<NameTarget>, part: Int? = null, position: Int? = null, rosterCount: Int? = null): String? {
        val bySection = LinkedHashMap<String, Pair<Boolean, MutableList<Int>>>()
        for (t in targets) {
            val code = t.sectionCode?.takeIf { it.isNotEmpty() }?.let { joinField(fieldWords(it, true)).uppercase(Locale.ROOT) }.orEmpty()
            if (code.isEmpty()) continue
            val entry = bySection.getOrPut(code) { false to mutableListOf() }
            if (t.questionNumber != null || !t.displayCode.isNullOrEmpty()) {
                (t.questionNumber ?: questionNumber(t.sectionCode, t.displayCode))?.let { entry.second.add(it) }
            } else {
                bySection[code] = true to entry.second
            }
        }
        fun q(n: Int) = "Q" + n.toString().padStart(2, '0')
        val pieces = bySection.map { (code, entry) ->
            val numbers = entry.second.distinct().sorted()
            when {
                entry.first || numbers.isEmpty() -> "$code-All"
                numbers.size == 1 -> "$code-${q(numbers[0])}"
                else -> "$code-${q(numbers.first())}-${q(numbers.last())}"
            }
        }
        val suffix = when {
            part != null && part >= 2 -> "P$part"
            rosterCount == 1 && position != null && position >= 2 -> "T$position"
            else -> null
        }
        var sequence: String? = if (pieces.isEmpty()) null else pieces.joinToString("-")
        if (suffix != null) sequence = if (sequence != null) "$sequence-$suffix" else suffix
        return sequence
    }

    /** `D-Q03`; `-P2` for part 2, else `-T2` for a solo take at position 2 or more. */
    fun questionSequence(section: Any?, question: Any?, take: Int? = null, part: Int? = null): String {
        val sec = joinField(fieldWords(section, true)).uppercase(Locale.ROOT)
        val q = question?.toString()?.trim().orEmpty()
        if (sec.isEmpty() || q.isEmpty()) return ""
        val number = q.toDoubleOrNull()?.toInt() ?: return ""
        return recordingSequence(listOf(NameTarget(sec, questionNumber = number)), part = part, position = take, rosterCount = 1).orEmpty()
    }

    /** `D-All`: a recording that covers a whole section. */
    fun sectionSequence(section: Any?): String {
        val sec = joinField(fieldWords(section, true)).uppercase(Locale.ROOT)
        return if (sec.isEmpty()) "" else recordingSequence(listOf(NameTarget(sec))).orEmpty()
    }

    /** The questionnaire shape of a recording's inputs. */
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
        val what: String = "Recording",
        /** A continuation's part number (2 or more); [partTwo] is the older spelling of part 2. */
        val part: Int? = null,
    )

    /** `D3` → `D-Q03`; a section → `D-All`; several questions of one section → `F-Q01-Q06`. */
    fun sequenceField(fields: RecordingFields): String? =
        recordingSequence(
            fields.targets, part = fields.part ?: if (fields.partTwo) 2 else null, position = fields.position,
            rosterCount = fields.rosterCount,
        )

    /**
     * §3.13: the recording, transcript or subtitle file name of one questionnaire take. [transliterate]
     * turns Place, Craft and Subject into Latin first; the default is the shared rule ([toAscii]).
     */
    fun recordingFileName(fields: RecordingFields, transliterate: (String) -> String = ::toAscii): String {
        val inp = fields.session.input()
        fun latin(value: String?): String? = value?.let { if (it.all { c -> c.code < 128 }) it else transliterate(it) }
        val moment = asIst(fields.capturedAt)
        return fileName(
            entity = entityOf(inp),
            what = fields.what,
            ext = fields.extension,
            date = moment?.date,
            clock = if (fields.capturedAtSource.uppercase(Locale.ROOT) in ESTIMATED_SOURCES) null else moment?.time,
            place = latin(placeSource(inp)),
            craft = latin(craftSource(inp)),
            subject = latin(subjectSource(inp, true)),
            sequence = sequenceField(fields),
        )
    }

    /** A capture with its fields passed directly, dated and timed when it was captured. An estimated time is left out. */
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
        capturedAtSource: String? = null,
    ): String = fileName(
        entity = entity, what = what, ext = ext, portal = portal, date = capturedAt,
        time = timeKnown && capturedAtSource?.uppercase(Locale.ROOT) !in ESTIMATED_SOURCES,
        place = place, craft = craft, subject = subject, sequence = sequence, n = n,
    )

    /** A file about a whole session (the zip of its recordings): no time, no sequence. */
    fun sessionFilename(session: SessionFields, what: String, ext: String): String {
        val inp = session.input()
        return fileName(
            entity = entityOf(inp), what = what, ext = ext, date = inp.sessionDate,
            place = placeSource(inp), craft = craftSource(inp), subject = subjectSource(inp, true),
        )
    }

    /**
     * A download, export, report or backup, dated the day it is generated (IST) unless [day] or [at]
     * says otherwise. [withTime] adds HHMM for snapshots that can repeat within a day; `dated = false`
     * is for templates (pro formas), which carry no date at all.
     */
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
        day: String? = null,
    ): String {
        val hasPeriod = period != null && period.toString().isNotEmpty()
        val whenValue: Any? = when {
            !dated || hasPeriod -> null
            !day.isNullOrBlank() -> day.trim().take(10)
            else -> at ?: Instant.now()
        }
        return fileName(
            entity = entity, what = what, ext = ext, portal = portal, date = whenValue, time = withTime,
            period = period, place = place, craft = craft, subject = subject, sequence = sequence, n = n,
        )
    }

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
        place = placeText(clusterName, district, state),
        craft = craftName,
        subject = workshopCode?.takeIf { it.isNotEmpty() } ?: title,
        n = n,
        portal = portal,
    )

    // ── Leaves and collisions ────────────────────────────────────────────────────────────────────

    fun splitExtension(name: String): Pair<String, String> {
        val lower = name.lowercase(Locale.ROOT)
        for (double in DOUBLE_EXTENSIONS) {
            if (lower.endsWith(double) && name.length > double.length) {
                return name.substring(0, name.length - double.length) to name.substring(name.length - double.length)
            }
        }
        val at = name.lastIndexOf('.')
        if (at > 0) {
            val ext = name.substring(at + 1)
            if (ext.length in 1..10 && ext.all(::isAsciiAlnum)) return name.substring(0, at) to ".$ext"
        }
        return name to ""
    }

    private val NOT_LEAF = Regex("[^A-Za-z0-9._-]+")
    private val HYPHENS = Regex("-{2,}")

    /** Any file name as an ASCII leaf: `[A-Za-z0-9._-]`, at most 150 characters, never empty or reserved. */
    fun asciiLeaf(name: Any?, fallback: String = "upload.bin"): String {
        val raw = text(name).replace('\\', '/').substringAfterLast('/').trim()
        val cleaned = toAscii(raw).replace(NOT_LEAF, "-").replace(HYPHENS, "-").trim('-', '.')
        if (cleaned.isEmpty()) return fallback
        val dot = cleaned.lastIndexOf('.')
        var stem = if (dot >= 0) cleaned.substring(0, dot) else cleaned
        var ext = if (dot >= 0) cleaned.substring(dot + 1) else ""
        stem = guard(stem.trim('-', '.').ifEmpty { "upload" })
        ext = ext.take(16).lowercase(Locale.ROOT)
        val budget = FILE_MAX - (if (ext.isEmpty()) 0 else ext.length + 1)
        stem = stem.take(budget).trimEnd('-', '.').ifEmpty { "upload" }
        return if (ext.isEmpty()) stem else "$stem.$ext"
    }

    /** `..._D-Q03.m4a` → `..._D-Q03_2.m4a`; `.sql.gz` counts as one extension. */
    fun withN(name: String, n: Int): String {
        if (n <= 1) return name
        if (name.endsWith(".sql.gz")) return "${name.dropLast(7)}_$n.sql.gz"
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) "${name.substring(0, dot)}_$n${name.substring(dot)}" else "${name}_$n"
    }

    /** `_2`, `_3` for colliding names, in the order given (callers sort by createdAt, then id). */
    fun dedupeNames(names: Iterable<String>): List<String> {
        val seen = HashMap<String, Int>()
        val taken = HashSet<String>()
        return names.map { name ->
            val key = name.lowercase(Locale.ROOT)
            var count = (seen[key] ?: 0) + 1
            seen[key] = count
            var candidate = withN(name, count)
            while (candidate.lowercase(Locale.ROOT) in taken) {
                count += 1
                seen[key] = count
                candidate = withN(name, count)
            }
            taken.add(candidate.lowercase(Locale.ROOT))
            candidate
        }
    }
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
