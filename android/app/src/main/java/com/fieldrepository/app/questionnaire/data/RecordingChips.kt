package com.fieldrepository.app.questionnaire.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/**
 * Recording chips (owner, 31 Oct 2026): the few facts that make a take findable again. The server
 * sends them as `chips` on every recording and session (`backend/app/questionnaire/chips.py`), as
 * `chips` on inbox rows and as `questionnaireChips` on a media row. [ChipRules.display] turns them into
 * the chips a screen draws, with exactly the labels, order and filters of
 * `shared/questionnaire-chips.json` (the web draws the same from the same vectors).
 */
@Serializable
data class QChipSession(val id: String = "", val title: String = "")

@Serializable
data class QChipForm(val id: String = "", val title: String = "", val kind: String? = null)

@Serializable
data class QChipTarget(
    val kind: String = "SECTION",
    val sectionId: String? = null,
    val sectionCode: String = "",
    val sectionTitle: String = "",
    val questionLineageId: String? = null,
    val displayCode: String = "",
    val prompt: String = ""
)

@Serializable
data class QChipPerson(val id: String = "", val name: String = "", val role: String = "ANSWERED")

@Serializable
data class QChipWorkshop(val id: String = "", val name: String = "", val type: String = "FIELD")

@Serializable
data class QChips(
    val session: QChipSession? = null,
    val questionnaire: QChipForm? = null,
    val targets: List<QChipTarget> = emptyList(),
    val artisans: List<QChipPerson> = emptyList(),
    val respondents: List<QChipPerson> = emptyList(),
    val sessionDate: String? = null,
    val sessionDateEstimated: Boolean = false,
    val capturedOn: String? = null,
    val captureEstimated: Boolean = false,
    val workshop: QChipWorkshop? = null,
    val languageCode: String? = null,
    val durationMs: Long? = null,
    val researcher: QUserRef? = null,
    val source: String = "APP"
)

/** What tapping a chip filters on: a filter key, or `date` (one day, both ends of the range). */
data class ChipFilter(val key: String, val value: String)

/** One chip as drawn. A null [filter] is not tappable (duration, "+N", a guest, "Whole session"). */
data class DisplayChip(val kind: String, val label: String, val filter: ChipFilter?, val title: String? = null)

object ChipRules {
    const val MAX_TARGETS = 2
    const val MAX_PEOPLE = 2
    const val DATE = "date"
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** `chips` (or `questionnaireChips`) from a JSON row; null when absent or unreadable. */
    fun parse(element: JsonElement?): QChips? =
        (element as? JsonObject)?.let { runCatching { outboxJson.decodeFromJsonElement(QChips.serializer(), it) }.getOrNull() }

    /** "7 Jul 2026": English month names on every phone, as on the web. */
    fun dayLabel(iso: String): String = runCatching {
        val d = LocalDate.parse(iso.take(10))
        "${d.dayOfMonth} ${MONTHS[d.monthValue - 1]} ${d.year}"
    }.getOrDefault(iso)

    fun languageLabel(code: String): String = SharedLanguages.labels[code] ?: code.uppercase()

    fun targetLabel(t: QChipTarget): String = if (t.kind == "QUESTION") {
        if (t.sectionTitle.isBlank()) t.displayCode else "${t.displayCode} · ${t.sectionTitle}"
    } else {
        if (t.sectionTitle.isBlank()) QuestionnaireCopy.t("chipSection", "code" to t.sectionCode) else "${t.sectionCode} · ${t.sectionTitle}"
    }

    private fun targetFilter(t: QChipTarget): ChipFilter? = if (t.kind == "QUESTION") t.questionLineageId?.let { ChipFilter("questionLineageId", it) }
        else t.sectionId?.let { ChipFilter("sectionId", it) }

    /** The chips of one recording (or session), in the shared order. */
    fun display(chips: QChips?): List<DisplayChip> {
        if (chips == null) return emptyList()
        val out = mutableListOf<DisplayChip>()
        if (chips.targets.isEmpty()) {
            out += DisplayChip("target", QuestionnaireCopy.t("chipWholeSession"), null)
        } else {
            chips.targets.take(MAX_TARGETS).forEach { out += DisplayChip("target", targetLabel(it), targetFilter(it)) }
            val rest = chips.targets.drop(MAX_TARGETS)
            if (rest.isNotEmpty()) out += DisplayChip("moreTargets", QuestionnaireCopy.t("chipMore", "count" to rest.size), null, rest.joinToString(", ", transform = ::targetLabel))
        }
        chips.questionnaire?.let { out += DisplayChip("questionnaire", it.title, ChipFilter("questionnaireId", it.id)) }
        val people = chips.artisans.map { a ->
            DisplayChip("artisan", if (a.role == "PRESENT") QuestionnaireCopy.t("chipPresent", "name" to a.name) else a.name, ChipFilter("artisanId", a.id))
        } + chips.respondents.map { g -> DisplayChip("guest", QuestionnaireCopy.t("chipGuest", "name" to g.name), null) }
        out += people.take(MAX_PEOPLE)
        val hidden = people.drop(MAX_PEOPLE)
        if (hidden.isNotEmpty()) out += DisplayChip("morePeople", QuestionnaireCopy.t("chipMore", "count" to hidden.size), null, hidden.joinToString(", ") { it.label })
        chips.sessionDate?.let { day ->
            val label = dayLabel(day)
            out += DisplayChip(
                "date",
                if (chips.sessionDateEstimated || chips.captureEstimated) QuestionnaireCopy.t("chipEstimated", "date" to label) else label,
                ChipFilter(DATE, day)
            )
        }
        chips.capturedOn?.let { day -> out += DisplayChip("captured", QuestionnaireCopy.t("chipRecordedOn", "date" to dayLabel(day)), ChipFilter(DATE, day)) }
        chips.workshop?.let { w -> out += DisplayChip("workshop", w.name, ChipFilter(if (w.type == "DESIGN") "designWorkshopId" else "workshopId", w.id)) }
        chips.languageCode?.ifBlank { null }?.let { out += DisplayChip("language", languageLabel(it), ChipFilter("languageCode", it)) }
        chips.durationMs?.let { out += DisplayChip("duration", QuestionnaireCopy.elapsed(it), null) }
        return out
    }

    /** A session's chips: its form, people, date, workshop and language (no target, no duration). */
    fun displaySession(chips: QChips?): List<DisplayChip> = display(chips).filterNot { it.kind == "target" && it.filter == null }

    /** The label a filter value is shown with in the active pills, from the chip it came from. */
    fun labels(chips: QChips?): Map<String, String> = display(chips).mapNotNull { c -> c.filter?.let { it.value to c.label } }.toMap()
}

/** A take found by the app's search: what its row says, its chips, and the list a tap opens. */
data class RecordingHit(val id: String, val title: String, val subtitle: String?, val chips: QChips?, val filters: QuestionnaireFilters)

/**
 * The app search's Recordings group (owner, 31 Oct 2026), from `GET /questionnaire/recordings?q=`:
 * each take reads as what it answers ("A · Materials", "F1 · Tools", "Whole session") over its
 * session's title, with its chips; a tap opens the recordings list searched for the same words, on
 * the take's form, where the take is.
 */
object RecordingSearch {
    fun hits(takes: List<QRecording>, query: String): List<RecordingHit> = takes.map { r ->
        val c = r.chips
        val title = c?.targets?.firstOrNull()?.let(ChipRules::targetLabel) ?: QuestionnaireCopy.t("chipWholeSession")
        val filters = QuestionnaireFilters().withQ(query.trim()).let { f ->
            c?.questionnaire?.let { q -> f.with("questionnaireId", listOf(q.id), mapOf(q.id to q.title)) } ?: f
        }
        RecordingHit(r.id, title, c?.session?.title?.ifBlank { null }, c, filters)
    }
}
