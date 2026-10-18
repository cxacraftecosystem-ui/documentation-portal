package com.fieldrepository.app.ui

import com.fieldrepository.app.data.ArtisanDetailDto
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.MediaFileDto
import com.fieldrepository.app.data.QuestionnaireQuestionDto
import java.time.LocalDate

/*
 * THE INTERVIEW FORM FILLS WHAT IT ALREADY KNOWS (B8, A7 — 2026-10-17).
 *
 * The handset's twin of the web's `lib/questionArtisanFields.ts` and `lib/transcriptAnswer.ts`, rule
 * for rule, so a sitting taken on either device is filled the same way:
 *
 *  - B8: a question may say which ARTISAN FIELD answers it (`QuestionnaireQuestion.artisanField`,
 *    set in the question builder). Picking the artisan fills those answers when they are empty.
 *    Questions in the respondent block (RESP) that nobody has mapped yet still fall back to the old
 *    keyword guess, so an instrument imported before the builder had the setting fills as before.
 *  - B8: the title is no longer typed: "Ramesh Chhipa · Craft questionnaire · 17 Oct", the same
 *    words the server writes for a sitting saved without one.
 *  - A7: a question's transcribed clip can become its answer in one tap ("Use as answer"), with the
 *    answer stored as `answerSource = "TRANSCRIPT"`. A typed answer is never overwritten.
 */

/** The value `QuestionnaireResponse.answerSource` takes for words taken from the question's clip. */
const val TRANSCRIPT_SOURCE = "TRANSCRIPT"

/** The fields a question may be answered from — the server's `ARTISAN_FIELD_KEYS`, same order, same words. */
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

/**
 * The `artisanField` a question builder save sends: null (omitted from the body) when the mapping
 * was not touched, so an ordinary prompt save keeps the body shape servers without B8 accept; the
 * picked key, or "" to clear, only when it changed.
 */
fun artisanFieldUpdate(stored: String?, picked: String): String? =
    if (picked == stored.orEmpty()) null else picked

/**
 * The artisan as far as their listed row knows them, for when the detail read fails offline: name,
 * craft and place fill; every other field reads empty and its question is left for the researcher.
 */
fun artisanDetailFromListed(row: ArtisanDto): ArtisanDetailDto =
    ArtisanDetailDto(id = row.id, name = row.name, place = row.place, craftId = row.craftId, craft = row.craft, status = row.status, location = row.location)

/** What one mapped field reads for this artisan, or "" when their record holds nothing for it. */
fun artisanFieldValue(field: String, artisan: ArtisanDetailDto, interviewer: String?, today: LocalDate = LocalDate.now()): String =
    when (field) {
        "name" -> artisan.name
        "localName" -> artisan.localName.orEmpty()
        "craft" -> artisan.craft?.name.orEmpty()
        "place" -> artisan.place
        "gender" -> artisan.gender.orEmpty()
        "phone" -> artisan.phone.orEmpty()
        "email" -> artisan.email.orEmpty()
        "dateOfBirth" -> artisan.dateOfBirth?.take(10).orEmpty()
        "experienceYears" -> artisan.experienceYears?.toString().orEmpty()
        "address" -> artisan.address.orEmpty()
        // The browser's `toLocaleDateString("en-IN")`: day/month/year, unpadded.
        "interviewDate" -> "${today.dayOfMonth}/${today.monthValue}/${today.year}"
        "interviewer" -> interviewer.orEmpty()
        else -> ""
    }.trim()

/** The older keyword guess, kept ONLY for a respondent ("RESP") question nobody has mapped yet. */
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

/**
 * The answers the picked artisan supplies: every MAPPED question, plus the respondent block's
 * unmapped ones through the keyword guess. An unmapped "contact" falls back from phone to email.
 */
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

/** "Ramesh Chhipa · Craft questionnaire · 17 Oct" — the server's automatic title, drawn ahead of it. */
fun automaticInterviewTitle(artisanName: String?, artisanCount: Int, questionnaireTitle: String?, day: LocalDate = LocalDate.now()): String {
    val who = artisanName?.trim()?.takeIf { it.isNotEmpty() }?.let { if (artisanCount > 1) "$it and others" else it }.orEmpty()
    return listOf(who, questionnaireTitle?.trim().orEmpty(), "${day.dayOfMonth} ${MONTHS[day.monthValue - 1]}")
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
        .take(220)
}

/** A transcript as an answer box's text: Markdown bold markers and `---` rules out, the words kept. */
fun transcriptAnswerText(transcript: String?): String =
    (transcript ?: "")
        .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
        .replace(Regex("""(?m)^\s*-{3,}\s*$"""), "")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

/** What a clip offers its question, or null: no usable transcript, or the box already has words. */
fun transcriptOffer(currentAnswer: String?, transcript: String?): String? {
    if (!currentAnswer.isNullOrBlank()) return null
    return transcriptAnswerText(transcript).takeIf { it.isNotEmpty() }
}

private val CLIP_QUESTION_CAPTION = Regex("""^question\s+audio:\s*([A-Za-z]{1,3})\s*(\d+)\b""", RegexOption.IGNORE_CASE)

/** The ONE question a clip was recorded against, from the caption both clients write; null for a section take. */
fun clipQuestionId(caption: String?, questions: List<QuestionnaireQuestionDto>): String? {
    val found = CLIP_QUESTION_CAPTION.find(caption?.trim().orEmpty()) ?: return null
    val code = found.groupValues[1].uppercase()
    val number = found.groupValues[2].toIntOrNull() ?: return null
    return questions.firstOrNull { it.sectionCode.uppercase() == code && it.sortOrder == number }?.id
}

/** Question id → the answer text of its NEWEST transcribed clip, for clips filed to one question. */
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
