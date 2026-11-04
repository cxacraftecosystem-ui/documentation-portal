package com.fieldrepository.app.questionnaire.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

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

data class ChipFilter(val key: String, val value: String)

data class DisplayChip(val kind: String, val label: String, val filter: ChipFilter?, val title: String? = null)

object ChipRules {
    const val MAX_TARGETS = 2
    const val MAX_PEOPLE = 2
    const val DATE = "date"
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun parse(element: JsonElement?): QChips? =
        (element as? JsonObject)?.let { runCatching { outboxJson.decodeFromJsonElement(QChips.serializer(), it) }.getOrNull() }

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

    fun displaySession(chips: QChips?): List<DisplayChip> = display(chips).filterNot { it.kind == "target" && it.filter == null }

    fun labels(chips: QChips?): Map<String, String> = display(chips).mapNotNull { c -> c.filter?.let { it.value to c.label } }.toMap()
}

data class RecordingHit(val id: String, val title: String, val subtitle: String?, val chips: QChips?, val filters: QuestionnaireFilters)

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
