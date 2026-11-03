package com.fieldrepository.app.ui

import com.fieldrepository.app.data.InterviewResponseDto
import com.fieldrepository.app.data.MergeAnswerConflict
import com.fieldrepository.app.data.QuestionnaireSectionDto

fun sectionsToRevealOnEdit(
    sections: List<QuestionnaireSectionDto>,
    responses: List<InterviewResponseDto>,
    sectionsWithSavedMedia: Set<String>,
    alreadyRevealed: Set<String>
): Set<String> {
    val answered = responses
        .filter { !it.answerText.isNullOrBlank() || !it.notes.isNullOrBlank() }
        .map { it.questionId }
        .toSet()
    return sections
        .filter { section ->
            section.id !in alreadyRevealed && (
                section.id in sectionsWithSavedMedia ||
                    section.questions.any { it.isActive && it.id in answered }
                )
        }
        .map { it.id }
        .toSet()
}

fun answerBoxVisible(hideAnswers: Boolean, recordedAnswer: String?): Boolean =
    !hideAnswers || !recordedAnswer.isNullOrBlank()

fun mergeOfferQuestion(holderTitle: String?): String {
    val name = holderTitle?.trim().orEmpty()
    val subject = if (name.isEmpty()) "Another interview (untitled)" else "“$name”"
    return "$subject already covers this exact set of artisans. Move this interview's answers and " +
        "recordings into it?"
}

fun mergeConflictLines(conflicts: List<MergeAnswerConflict>): List<String> = conflicts.map { row ->
    val code = row.sectionCode?.trim().orEmpty()
    val prompt = row.prompt?.trim().orEmpty()
    when {
        code.isNotEmpty() && prompt.isNotEmpty() -> "$code · $prompt"
        prompt.isNotEmpty() -> prompt
        code.isNotEmpty() -> "$code · question ${row.questionId}"
        else -> "Question ${row.questionId}"
    }
}

fun unsavedBeforeMergeNotice(typedAnswers: Int, recordings: Int, attachments: Int): String? {
    val parts = buildList {
        if (typedAnswers > 0) add(plural(typedAnswers, "typed answer"))
        if (recordings > 0) add(plural(recordings, "new recording"))
        if (attachments > 0) add(plural(attachments, "attached file"))
    }
    if (parts.isEmpty()) return null
    val verb = if (typedAnswers + recordings + attachments == 1) "is" else "are"
    return "Unsaved: ${joinWithAnd(parts)} on this screen $verb still only here and won't move with the interview."
}

internal fun plural(count: Int, noun: String): String =
    if (count == 1) "1 $noun" else "$count ${noun}s"

private fun joinWithAnd(parts: List<String>): String = when (parts.size) {
    1 -> parts[0]
    2 -> "${parts[0]} and ${parts[1]}"
    else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
}
