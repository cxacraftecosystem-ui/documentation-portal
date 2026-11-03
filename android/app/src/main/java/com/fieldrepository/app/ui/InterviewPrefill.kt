package com.fieldrepository.app.ui

import com.fieldrepository.app.data.StandardValues
import com.fieldrepository.app.data.ArtisanDetailDto
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.CraftCatalogueCache
import com.fieldrepository.app.data.MediaFileDto
import com.fieldrepository.app.data.QuestionnaireQuestionDto
import java.time.LocalDate

const val TRANSCRIPT_SOURCE = "TRANSCRIPT"

val ARTISAN_FIELD_OPTIONS: List<Pair<String, String>> = listOf(
    "name" to "Name",
    "localName" to "Local name",
    "craft" to "Craft",
    "place" to "Place",
    "gender" to "Gender",
    "phone" to "Phone",
    "email" to "Email",
    "dateOfBirth" to "Date of birth",
    "experienceYears" to "Experience (years)",
    "address" to "Address",
    "interviewDate" to "Today's date",
    "interviewer" to "Interviewer",
)

fun artisanFieldUpdate(stored: String?, picked: String): String? =
    if (picked == stored.orEmpty()) null else picked

fun artisanDetailFromListed(row: ArtisanDto): ArtisanDetailDto =
    ArtisanDetailDto(id = row.id, name = row.name, place = row.place, craftId = row.craftId, craft = row.craft, status = row.status, location = row.location)

fun artisanFieldValue(field: String, artisan: ArtisanDetailDto, interviewer: String?, today: LocalDate = LocalDate.now()): String =
    when (field) {
        "name" -> artisan.name
        "localName" -> artisan.localName.orEmpty()
        "craft" -> CraftCatalogueCache.catalogue.value?.craft(artisan.craftId)?.name ?: artisan.craft?.name.orEmpty()
        "place" -> artisan.place
        "gender" -> artisan.gender.orEmpty()
        "phone" -> StandardValues.formatPhone(artisan.phoneCountryCode, artisan.phone).orEmpty()
        "email" -> artisan.email.orEmpty()
        "dateOfBirth" -> artisan.dateOfBirth?.take(10).orEmpty()
        "experienceYears" -> artisan.experienceYears?.toString().orEmpty()
        "address" -> artisan.address.orEmpty()
        "interviewDate" -> "${today.dayOfMonth}/${today.monthValue}/${today.year}"
        "interviewer" -> interviewer.orEmpty()
        else -> ""
    }.trim()

fun guessedArtisanField(prompt: String): String? {
    val text = prompt.lowercase()
    return when {
        "name" in text -> "name"
        "craft" in text -> "craft"
        "state" in text || "district" in text || "village" in text -> "place"
        "gender" in text -> "gender"
        "contact" in text -> "phone"
        "date" in text -> "interviewDate"
        "interviewer" in text -> "interviewer"
        else -> null
    }
}

fun artisanPrefill(
    questions: List<QuestionnaireQuestionDto>,
    artisan: ArtisanDetailDto,
    interviewer: String?,
    today: LocalDate = LocalDate.now()
): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (question in questions) {
        val mapped = question.artisanField?.takeIf { it.isNotBlank() }
        val field = mapped ?: if (question.sectionCode == "RESP") guessedArtisanField(question.prompt) else null
        if (field == null) continue
        var value = artisanFieldValue(field, artisan, interviewer, today)
        if (value.isEmpty() && field == "phone" && mapped == null) value = artisan.email.orEmpty().trim()
        if (value.isNotEmpty()) out[question.id] = value
    }
    return out
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

fun automaticInterviewTitle(artisanName: String?, artisanCount: Int, questionnaireTitle: String?, day: LocalDate = LocalDate.now()): String {
    val who = artisanName?.trim()?.takeIf { it.isNotEmpty() }?.let { if (artisanCount > 1) "$it and others" else it }.orEmpty()
    return listOf(who, questionnaireTitle?.trim().orEmpty(), "${day.dayOfMonth} ${MONTHS[day.monthValue - 1]}")
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
        .take(220)
}

fun transcriptAnswerText(transcript: String?): String =
    (transcript ?: "")
        .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
        .replace(Regex("""(?m)^\s*-{3,}\s*$"""), "")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

fun transcriptOffer(currentAnswer: String?, transcript: String?): String? {
    if (!currentAnswer.isNullOrBlank()) return null
    return transcriptAnswerText(transcript).takeIf { it.isNotEmpty() }
}

private val CLIP_QUESTION_CAPTION = Regex("""^question\s+audio:\s*([A-Za-z]{1,3})\s*(\d+)\b""", RegexOption.IGNORE_CASE)

fun clipQuestionId(caption: String?, questions: List<QuestionnaireQuestionDto>): String? {
    val found = CLIP_QUESTION_CAPTION.find(caption?.trim().orEmpty()) ?: return null
    val code = found.groupValues[1].uppercase()
    val number = found.groupValues[2].toIntOrNull() ?: return null
    return questions.firstOrNull { it.sectionCode.uppercase() == code && it.sortOrder == number }?.id
}

fun questionTranscripts(media: List<MediaFileDto>, questions: List<QuestionnaireQuestionDto>): Map<String, String> {
    val newest = LinkedHashMap<String, Pair<String, String>>()
    for (clip in media) {
        val questionId = clipQuestionId(clip.caption, questions) ?: continue
        val text = transcriptAnswerText(clip.transcriptText)
        if (text.isEmpty()) continue
        val stamp = clip.createdAt.orEmpty()
        val held = newest[questionId]
        if (held == null || stamp > held.first) newest[questionId] = stamp to text
    }
    return newest.mapValues { it.value.second }
}
