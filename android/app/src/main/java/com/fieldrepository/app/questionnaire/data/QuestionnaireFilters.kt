package com.fieldrepository.app.questionnaire.data

import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * One filter model for the sessions list, the forms library, a form's sessions, the recordings list
 * and the media lists (owner, 31 Oct 2026), the same as the server's
 * (`backend/app/questionnaire/filters.py`) and the web's. [toQuery] is the canonical string of
 * `shared/questionnaire-chips.json` (`queryCases`): keys in `filterKeys` order, a repeatable key's
 * values comma-joined in the order chosen, values encoded as `encodeURIComponent` does.
 *
 * [labels] remembers how each chosen value reads (an artisan's name for an id) so the active pills
 * can say it; it never reaches the server.
 */
@Serializable
data class QuestionnaireFilters(
    val values: Map<String, List<String>> = emptyMap(),
    val labels: Map<String, String> = emptyMap()
) {
    operator fun get(key: String): List<String> = values[key].orEmpty()
    fun one(key: String): String? = get(key).firstOrNull()

    val q: String get() = one("q").orEmpty()
    val dateFrom: String? get() = one("dateFrom")
    val dateTo: String? get() = one("dateTo")

    /** [key] set to [list] (cleared when empty); a single-valued key keeps the first value only. */
    fun with(key: String, list: List<String>, label: Map<String, String> = emptyMap()): QuestionnaireFilters {
        val clean = list.map { it.trim() }.filter { it.isNotEmpty() }.distinct().let { if (key in MULTI) it else it.take(1) }
        val next = values.toMutableMap()
        if (clean.isEmpty()) next.remove(key) else next[key] = clean
        return copy(values = next, labels = labels + label)
    }

    fun withQ(text: String): QuestionnaireFilters = with("q", listOf(text))
    fun without(vararg keys: String): QuestionnaireFilters = copy(values = values - keys.toSet())

    val isEmpty: Boolean get() = KEYS.none { get(it).isNotEmpty() }

    /** "Filters (n)": every key set except the free text; a date range counts once. */
    val activeCount: Int get() = KEYS.count { it != "q" && it != "dateTo" && get(it).isNotEmpty() } +
        (if (dateFrom == null && dateTo != null) 1 else 0)

    /** On a media list a day chip is no filter (its dates are the upload day); every other chip is. */
    fun appliesToMedia(filter: ChipFilter): Boolean = filter.key != ChipRules.DATE

    /** Whether the chip's filter is already applied (the chip then reads as active). */
    fun isActive(filter: ChipFilter): Boolean =
        if (filter.key == ChipRules.DATE) dateFrom == filter.value && dateTo == filter.value else filter.value in get(filter.key)

    /**
     * Tapping a chip: a repeatable key gains the value, or loses it when it has it; a day sets both
     * ends of the range, or clears both when they are already that day (`applyCases`).
     */
    fun applyChip(filter: ChipFilter, label: String? = null): QuestionnaireFilters {
        val named = label?.let { mapOf(filter.value to it) }.orEmpty()
        if (filter.key == ChipRules.DATE) {
            return if (isActive(filter)) without("dateFrom", "dateTo")
            else with("dateFrom", listOf(filter.value)).with("dateTo", listOf(filter.value)).copy(labels = labels + named)
        }
        val current = get(filter.key)
        return if (filter.value in current) with(filter.key, current - filter.value) else with(filter.key, current + filter.value, named)
    }

    fun toQuery(): String = KEYS.mapNotNull { key ->
        val list = get(key)
        if (list.isEmpty()) null else "$key=" + list.joinToString(",", transform = ::encode)
    }.joinToString("&")

    /**
     * What a media list sends (`GET /media`): every filter but the text and the dates, which a
     * media list reads as its own search box and its upload day.
     */
    fun mediaParams(): Map<String, String> = without("q", "dateFrom", "dateTo").toParams()

    /** The same, as Retrofit's `@QueryMap(encoded = true)` takes it. */
    fun toParams(): Map<String, String> = KEYS.mapNotNull { key ->
        val list = get(key)
        if (list.isEmpty()) null else key to list.joinToString(",", transform = ::encode)
    }.toMap()

    companion object {
        val KEYS = listOf(
            "q", "questionnaireId", "sectionId", "questionLineageId", "artisanId", "researcherId", "workshopId",
            "designWorkshopId", "dateFrom", "dateTo", "kind", "source", "transcript", "languageCode"
        )
        val MULTI = setOf(
            "questionnaireId", "sectionId", "questionLineageId", "artisanId", "researcherId", "workshopId",
            "designWorkshopId", "kind", "source", "transcript", "languageCode"
        )
        val KINDS = listOf("WORKSHOP_INTERVIEW", "MARKET_SURVEY", "NONE")
        val SOURCES = listOf("APP", "UPLOAD", "HANDOFF")
        val TRANSCRIPT = listOf("HAS", "NONE", "PENDING", "FAILED")
        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

        /** `encodeURIComponent`: a space is %20, never +. */
        fun encode(value: String): String = buildString {
            value.toByteArray(StandardCharsets.UTF_8).forEach { b ->
                val c = b.toInt() and 0xff
                if (c < 0x80 && UNRESERVED.indexOf(c.toChar()) >= 0) append(c.toChar()) else append('%').append("%02X".format(c))
            }
        }

        fun decode(value: String): String {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < value.length) {
                val ch = value[i]
                when {
                    ch == '%' && i + 2 < value.length -> {
                        out.write(value.substring(i + 1, i + 3).toInt(16)); i += 3
                    }
                    ch == '+' -> { out.write(' '.code); i++ }
                    else -> { out.write(ch.toString().toByteArray(StandardCharsets.UTF_8)); i++ }
                }
            }
            return out.toString(StandardCharsets.UTF_8.name())
        }

        fun fromQuery(query: String): QuestionnaireFilters {
            var f = QuestionnaireFilters()
            query.removePrefix("?").split("&").filter { it.contains('=') }.forEach { pair ->
                val key = pair.substringBefore('=')
                if (key !in KEYS) return@forEach
                val raw = pair.substringAfter('=')
                val list = if (key in MULTI) raw.split(",").map(::decode) else listOf(decode(raw))
                f = f.with(key, f[key] + list)
            }
            return f
        }
    }

    // ── offline: does a cached row match? ─────────────────────────────────────────────────────

    private fun inRange(day: String?): Boolean {
        if (dateFrom == null && dateTo == null) return true
        val d = day?.take(10) ?: return false
        return (dateFrom == null || d >= dateFrom!!) && (dateTo == null || d <= dateTo!!)
    }

    private fun text(vararg haystack: String?): Boolean =
        q.isBlank() || haystack.any { it != null && it.contains(q.trim(), ignoreCase = true) }

    private fun kindOk(kind: String?): Boolean = get("kind").isEmpty() || (kind ?: "NONE") in get("kind")

    private fun workshopOk(fieldId: String?, designId: String?): Boolean {
        val field = get("workshopId"); val design = get("designWorkshopId")
        if (field.isEmpty() && design.isEmpty()) return true
        return (fieldId != null && fieldId in field) || (designId != null && designId in design)
    }

    private fun transcriptState(status: String?): String = when (status) {
        "COMPLETED", "SKIPPED_DUPLICATE" -> "HAS"
        "FAILED", "EMPTY", "REFUSED_CONSENT" -> "FAILED"
        else -> "PENDING"
    }

    /**
     * A cached take, read on this phone with no signal: every filter is decided from the take and
     * its chips, as the server decides it (a take matches a day on its session's date or its capture day).
     */
    fun matches(r: QRecording): Boolean {
        val c = r.chips
        if (get("questionnaireId").isNotEmpty() && c?.questionnaire?.id !in get("questionnaireId")) return false
        if (!kindOk(c?.questionnaire?.kind)) return false
        val sections = get("sectionId"); val lineages = get("questionLineageId")
        if (sections.isNotEmpty() || lineages.isNotEmpty()) {
            val hit = c?.targets.orEmpty().any { t -> (t.sectionId != null && t.sectionId in sections) || (t.questionLineageId != null && t.questionLineageId in lineages) } ||
                r.targets.any { t -> (t.sectionId != null && t.sectionId in sections) || (t.questionLineageId != null && t.questionLineageId in lineages) }
            if (!hit) return false
        }
        if (get("artisanId").isNotEmpty() && r.subjects.none { it.artisanId in get("artisanId") } && c?.artisans.orEmpty().none { it.id in get("artisanId") }) return false
        if (get("researcherId").isNotEmpty() && r.recordedBy?.id !in get("researcherId")) return false
        if (!workshopOk(c?.workshop?.takeIf { it.type != "DESIGN" }?.id, c?.workshop?.takeIf { it.type == "DESIGN" }?.id)) return false
        if (dateFrom != null || dateTo != null) {
            val captured = c?.capturedOn ?: c?.sessionDate ?: r.capturedAt
            if (!inRange(c?.sessionDate) && !inRange(captured)) return false
        }
        if (get("source").isNotEmpty() && (c?.source ?: "APP") !in get("source")) return false
        if (get("transcript").isNotEmpty()) {
            val state = transcriptState(r.transcript?.status)
            val ok = get("transcript").any { token -> if (token == "NONE") state != "HAS" else token == state }
            if (!ok) return false
        }
        if (get("languageCode").isNotEmpty() && (r.languageCode ?: c?.languageCode) !in get("languageCode")) return false
        return text(
            c?.session?.title, c?.questionnaire?.title, c?.workshop?.name, r.recordedBy?.name, r.transcript?.text,
            *c?.targets.orEmpty().flatMap { listOf(it.sectionCode, it.sectionTitle, it.displayCode, it.prompt) }.toTypedArray(),
            *c?.artisans.orEmpty().map { it.name }.toTypedArray(), *c?.respondents.orEmpty().map { it.name }.toTypedArray()
        )
    }

    /**
     * A cached session, with no signal. A session row does not carry its takes, so a section,
     * question or transcript filter cannot be decided on the phone: such a row is left out rather
     * than shown as if it matched.
     */
    fun matches(s: QSession): Boolean {
        if (get("sectionId").isNotEmpty() || get("questionLineageId").isNotEmpty() || get("transcript").isNotEmpty()) return false
        if (get("questionnaireId").isNotEmpty() && s.questionnaire?.id !in get("questionnaireId")) return false
        if (!kindOk(s.chips?.questionnaire?.kind)) return false
        if (get("artisanId").isNotEmpty() && s.participants.none { it.artisanId in get("artisanId") }) return false
        if (get("researcherId").isNotEmpty() && s.researchers.none { it.userId in get("researcherId") }) return false
        if (!workshopOk(s.workshop?.id, s.designWorkshopId)) return false
        if (!inRange(s.sessionDate)) return false
        if (get("source").isNotEmpty() && s.source !in get("source")) return false
        if (get("languageCode").isNotEmpty() && s.languageCode !in get("languageCode")) return false
        return text(
            s.title, s.note, s.legacyTitle, s.place, s.questionnaire?.title, s.workshop?.name ?: s.workshop?.title, s.chips?.workshop?.name,
            *s.participants.map { it.name }.toTypedArray(), *s.researchers.map { it.name }.toTypedArray(), *s.respondents.map { it.name }.toTypedArray()
        )
    }
}

/**
 * Filters kept per screen, across navigation and a restart, for the account that set them only
 * (the caches' owner rule, DR audit M15): another account signed in on the phone starts clean.
 */
class FilterStore(private val prefs: SharedPreferences, private val owner: () -> String?) {
    @Serializable
    private data class Kept(val ownerId: String? = null, val query: String = "", val labels: Map<String, String> = emptyMap())

    fun load(screen: String): QuestionnaireFilters {
        val raw = prefs.getString(KEY + screen, null) ?: return QuestionnaireFilters()
        val kept = runCatching { outboxJson.decodeFromString(Kept.serializer(), raw) }.getOrNull() ?: return QuestionnaireFilters()
        if (kept.ownerId != owner()) return QuestionnaireFilters()
        return QuestionnaireFilters.fromQuery(kept.query).copy(labels = kept.labels)
    }

    fun save(screen: String, filters: QuestionnaireFilters) {
        val kept = Kept(owner(), filters.toQuery(), filters.labels.filterKeys { id -> filters.values.values.any { id in it } })
        prefs.edit().putString(KEY + screen, outboxJson.encodeToString(Kept.serializer(), kept)).apply()
    }

    companion object {
        private const val KEY = "filters."
        const val SESSIONS = "sessions"
        const val RECORDINGS = "recordings"
        const val FORMS = "forms"
        fun formSessions(questionnaireId: String) = "form-sessions-$questionnaireId"
        const val MEDIA = "media"
    }
}

/**
 * The rows this phone last read, kept for the account that read them, so a list still filters with
 * no signal ("Offline: filtering what is on this device."). Rows merge by id, newest first, capped.
 */
class OfflineRows(private val dir: File, private val owner: () -> String?, private val cap: Int = 500) {
    @Serializable
    private data class Kept(val ownerId: String? = null, val savedAt: String = "", val sessions: List<QSession> = emptyList(), val recordings: List<QRecording> = emptyList())

    private val file get() = File(dir, "questionnaire-rows.json")
    private val lock = Any()

    private fun read(): Kept = synchronized(lock) {
        file.takeIf { it.isFile }?.let { runCatching { outboxJson.decodeFromString(Kept.serializer(), it.readText()) }.getOrNull() }
            ?.takeIf { it.ownerId == owner() } ?: Kept(owner())
    }

    private fun write(k: Kept) = synchronized(lock) {
        writeFileAtomically(file, outboxJson.encodeToString(Kept.serializer(), k).toByteArray())
    }

    fun keepSessions(rows: List<QSession>) {
        if (rows.isEmpty()) return
        val k = read()
        write(k.copy(ownerId = owner(), savedAt = Instant.now().toString(), sessions = mergeById(rows, k.sessions, { it.id }).take(cap)))
    }

    fun keepRecordings(rows: List<QRecording>) {
        if (rows.isEmpty()) return
        val k = read()
        write(k.copy(ownerId = owner(), savedAt = Instant.now().toString(), recordings = mergeById(rows, k.recordings, { it.id }).take(cap)))
    }

    fun sessions(filters: QuestionnaireFilters): List<QSession> = read().sessions.filter(filters::matches)
        .sortedWith(compareByDescending<QSession> { it.sessionDate ?: "" }.thenByDescending { it.id })

    fun recordings(filters: QuestionnaireFilters): List<QRecording> = read().recordings.filter(filters::matches)
        .sortedWith(compareByDescending<QRecording> { it.capturedAt ?: "" }.thenByDescending { it.id })

    companion object {
        fun <T> mergeById(fresh: List<T>, kept: List<T>, id: (T) -> String): List<T> {
            val ids = fresh.map(id).toSet()
            return fresh + kept.filter { id(it) !in ids }
        }
    }
}
