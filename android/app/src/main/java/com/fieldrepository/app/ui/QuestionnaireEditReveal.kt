package com.fieldrepository.app.ui

import com.fieldrepository.app.data.InterviewResponseDto
import com.fieldrepository.app.data.MergeAnswerConflict
import com.fieldrepository.app.data.QuestionnaireSectionDto

/**
 * WHAT AN OPEN EDIT MUST SHOW WITHOUT BEING ASKED — the rules behind the owner's first complaint,
 * pulled out of the composable so they can be checked without a renderer.
 *
 * ── THE COMPLAINT, AND WHY IT IS A DISPLAY DEFECT AND NOT A LOADING ONE ─────────────────────────
 *
 * *"when edit page is opened, already existing entries and media do not show up in the respective
 * sections, those should show up while editing as well, on both android and web"*.
 *
 * Nothing is missing from the handset's state. [QuestionnaireForm] seeds `answers` from
 * `editing.responses` and loads `savedMedia` across the whole sibling group the moment the form
 * opens, and it already draws that media read-only under each section and in an "Other saved
 * recordings & media" catch-all. Two gates then hide all of it:
 *
 *  1. `expandedSections` starts EMPTY, and a section composes its contents only when expanded. A
 *     researcher opening their own interview therefore sees a column of collapsed headers — not the
 *     answers, not the recordings — with the count line as the only evidence that anything is in
 *     there at all. On the browser at least the first section is open; here nothing is.
 *  2. `hideAnswers` starts TRUE, which is right for CAPTURE (the screen is meant to be a record
 *     button and nothing else while an interview is being conducted) and wrong for an EDIT of a
 *     sitting that already carries typed answers: the words are in the form's state, the box they
 *     live in is never drawn, and "not drawn" is indistinguishable from "lost" to the person
 *     looking at it. That is the same failure [InterviewEditSectionsTest] documents from the other
 *     direction, where the boxes were drawn and empty.
 *
 * Both rules live here, as pure functions over the record, for the reason `ui/RecordPickers.kt`
 * gives about `craftChangeClearsArtisan`: Compose is deliberately off this module's unit-test
 * classpath (`app/build.gradle.kts`), so a rule that stays inside a `@Composable` is a rule only a
 * human looking at a screen can check — and "did the section I recorded into open by itself" is not
 * something anybody re-checks before a release.
 */

/**
 * The sections an open EDIT should expand, given what the record holds and what this form has
 * already opened once.
 *
 * ── WHAT COUNTS AS CONTENT ──────────────────────────────────────────────────────────────────────
 *
 * A recorded ANSWER on one of the section's active questions, or a saved RECORDING filed under the
 * section. Media has to count on its own and cannot be folded into "has answers": on this
 * instrument a section is normally captured as ONE whole-section audio take with the answer boxes
 * hidden, so a correctly recorded sitting has zero response rows and several media rows. A rule
 * that measured only [responses] would leave exactly those sections shut — the sittings where the
 * work actually is.
 *
 * `notes` counts beside `answerText` because the server treats them as the two columns that hold a
 * researcher's words (`_RESPONSE_TEXT_FIELDS` in `backend/app/api/routes/questionnaire.py`), and a
 * row carrying only a note is still a row somebody wrote.
 *
 * ── WHY [alreadyRevealed] IS A PARAMETER AND NOT SOMETHING THIS FUNCTION COULD DERIVE ───────────
 *
 * `savedMedia` arrives asynchronously and across the whole sibling group, so the caller's effect
 * re-runs as it lands. Without a memory of what has already been auto-opened, every one of those
 * re-runs would re-expand a section the researcher had just deliberately CLOSED — a screen that
 * fights the person using it, which is worse than the screen that showed them nothing. So this
 * returns only the sections that are newly worth opening, the caller adds them to both the expanded
 * set and its memory, and each section is opened by this rule at most once per record.
 *
 * Returns the ids to ADD. Never the full expanded set: a caller that assigned the result over
 * `expandedSections` would close whatever the researcher had opened by hand.
 */
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

/**
 * Whether the typed-answer box is drawn for a question, given the capture toggle and the answer the
 * RECORD already carries for it.
 *
 * ── THE PREFERENCE IS NOT TOUCHED, AND THAT IS THE WHOLE DESIGN ─────────────────────────────────
 *
 * "Do not display answer text boxes" is the reader's own choice about CAPTURE and it stays exactly
 * as they left it — flipping it for them would change what the next interview they record looks
 * like in order to fix the one they are reading, and they would have to find and undo it. What
 * changes is its SCOPE: the toggle hides EMPTY boxes, which is what it was for ("keep the UI to
 * just the record button"), and it does not hide a researcher's words.
 *
 * ── THE BOX AND NOT A READ-ONLY ECHO ────────────────────────────────────────────────────────────
 *
 * The alternative considered was printing the recorded text read-only beside the recorder. It shows
 * the same words and it is wrong here for one reason: this box is the ONLY way to correct an
 * answer on either client. An edit screen exists to fix what is wrong with a record, so a design
 * that reveals the one thing somebody opened the screen to change and simultaneously makes it the
 * one thing they cannot touch turns a display defect into a dead end. The editable box shows the
 * words AND answers the question the researcher came with.
 *
 * ── WHY THE RECORD'S ANSWER AND NOT THE BOX'S CURRENT VALUE ─────────────────────────────────────
 *
 * Deciding from the live value would make the box vanish under the cursor the moment somebody
 * cleared it to retype — visibility flickering off a field's own contents while it is being edited.
 * The record's answer is fixed for as long as the form is open, so a box that appears stays.
 */
fun answerBoxVisible(hideAnswers: Boolean, recordedAnswer: String?): Boolean =
    !hideAnswers || !recordedAnswer.isNullOrBlank()

/**
 * The offer put to the researcher when a save is refused because another interview already covers
 * this exact set of artisans.
 *
 * NAMING THE HOLDER IS THE POINT OF THE WHOLE EXCHANGE. The server's own sentence
 * (`_DUPLICATE_SET_DETAIL`) says only that *an* interview exists, deliberately — its docstring
 * hands the title over and says the naming sentence "is an OFFER with a button on it, and it
 * belongs to the client that owns the button". This is that sentence. Without the title the
 * researcher is being asked to fold their afternoon's work into something they cannot identify.
 *
 * [holderTitle] is null or blank when the holder is untitled, which the server distinguishes from
 * "no holder at all" — an untitled row is still a real row to move into, so the offer stands and
 * only its name falls back.
 */
fun mergeOfferQuestion(holderTitle: String?): String {
    val name = holderTitle?.trim().orEmpty()
    val subject = if (name.isEmpty()) "Another interview (untitled)" else "“$name”"
    return "$subject already covers this exact set of artisans. Move this interview's answers and " +
        "recordings into it?"
}

/**
 * One line per question the two interviews answer differently — the 409 the merge route raises
 * rather than picking a winner.
 *
 * NAMED, NOT COUNTED. The server builds this list with the same argument ("A researcher cannot act
 * on '3 answers disagree'; they can act on the section and the prompt") and it is the client's job
 * not to throw that away on the way to the screen. The section code leads because it is how these
 * sittings are titled and talked about — "the F one" — so it is the fastest way to find the
 * question again.
 *
 * Falls back to the question id when the server could not name the row (a question deleted between
 * the two sittings), because an unresolvable id is still something to search for, where a blank
 * line is nothing at all.
 */
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

/**
 * What is on this screen and NOT in the database, said before a fold takes this interview away.
 *
 * ── WHY THIS SENTENCE HAS TO EXIST ──────────────────────────────────────────────────────────────
 *
 * The offer is reached from a REFUSED save, so by construction nothing the researcher did in this
 * sitting of the form has landed. The merge then moves what the interview holds ON THE SERVER onto
 * the survivor and deletes the row — it knows nothing about the answers typed and the clips
 * recorded since the form opened, and after it runs there is no longer a record for them to be
 * saved to. Offering the move without saying so would let somebody trade an afternoon's typing for
 * a button they pressed to be helpful.
 *
 * NULL WHEN THERE IS NOTHING PENDING, which is the ordinary case and the one that must stay quiet:
 * the collision is normally reached by ticking the one missing artisan and pressing save, with
 * nothing else on screen. A warning printed every time is a warning read none of the times, and it
 * would be false besides.
 *
 * COUNTS AND NOT A GENERIC "you have unsaved changes", because the researcher has to decide whether
 * to copy something down first, and "2 recordings" and "17 typed answers" are different decisions.
 */
fun unsavedBeforeMergeNotice(typedAnswers: Int, recordings: Int, attachments: Int): String? {
    val parts = buildList {
        if (typedAnswers > 0) add(plural(typedAnswers, "typed answer"))
        if (recordings > 0) add(plural(recordings, "new recording"))
        if (attachments > 0) add(plural(attachments, "attached file"))
    }
    if (parts.isEmpty()) return null
    val verb = if (typedAnswers + recordings + attachments == 1) "is" else "are"
    return "The save was refused, so ${joinWithAnd(parts)} on this screen $verb still only here. " +
        "Moving this interview will not carry them across — note them down, or cancel and save them " +
        "somewhere else first."
}

private fun plural(count: Int, noun: String): String =
    if (count == 1) "1 $noun" else "$count ${noun}s"

private fun joinWithAnd(parts: List<String>): String = when (parts.size) {
    1 -> parts[0]
    2 -> "${parts[0]} and ${parts[1]}"
    else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
}
