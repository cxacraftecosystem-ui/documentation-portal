package com.fieldrepository.app.ui

import com.fieldrepository.app.data.InterviewResponseDto
import com.fieldrepository.app.data.MergeAnswerConflict
import com.fieldrepository.app.data.QuestionnaireQuestionDto
import com.fieldrepository.app.data.QuestionnaireSectionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "ALREADY EXISTING ENTRIES AND MEDIA DO NOT SHOW UP" ON AN OPEN EDIT — the owner's first complaint,
 * pinned on the handset.
 *
 * ── WHAT THE DEFECT ACTUALLY WAS ────────────────────────────────────────────────────────────────
 *
 * Not a loading failure, which is what the report sounds like and what makes it easy to chase in the
 * wrong place. Everything was already there: [QuestionnaireForm] seeds `answers` from
 * `editing.responses`, loads `savedMedia` across the whole sibling group, and draws that media
 * read-only under each section with an "Other saved recordings & media" catch-all beneath. TWO
 * DISPLAY GATES hid all of it.
 *
 *  1. `expandedSections` started EMPTY and a section composes its contents only when expanded. So a
 *     researcher opening their own interview saw a column of collapsed headers — not the answers,
 *     not the recordings — with the count line as the only evidence that anything was in there.
 *  2. `hideAnswers` starts TRUE (correct for capture: the screen should be a record button and
 *     nothing else while an interview is being conducted) and stayed true on an edit, so a sitting
 *     that had been TYPED opened with every one of its words in the form's state and none of them on
 *     screen.
 *
 * The second is the same failure `InterviewEditSectionsTest` documents from the other direction —
 * there the boxes were drawn and empty because the wrong instrument's questions were rendered. Both
 * reach the researcher as "my answers are gone", and both are worth a suite because neither loses
 * anything: the record is intact and only the screen is lying.
 *
 * ── WHAT IS ASSERTED HERE AND WHAT IS ASSERTED AT THE SOURCE ───────────────────────────────────
 *
 * The two rules are pure functions in `ui/QuestionnaireEditReveal.kt` precisely so this suite can
 * drive them: Compose is deliberately off this module's unit-test classpath (`app/build.gradle.kts`),
 * so a condition written inline in the composable is a condition only a human opening an interview
 * can check. The second half of the file then scans [QuestionnaireForm]'s own source, the way
 * `QuestionnaireFormWiringTest` and `InterviewEditSectionsTest` do, because a correct rule nobody
 * calls is exactly as invisible on screen as no rule at all.
 */
class QuestionnaireEditRevealTest {

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  1. Which sections an edit opens by itself
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** A typed sitting: the section holding the answer opens, the untouched one stays shut. */
    @Test
    fun `a section the record answers is opened, and its neighbour is not`() {
        val reveal = sectionsToRevealOnEdit(
            sections = listOf(section("s-a", "A", "a1", "a2"), section("s-b", "B", "b1")),
            responses = listOf(answered("a2", "Since my grandfather's time.")),
            sectionsWithSavedMedia = emptySet(),
            alreadyRevealed = emptySet(),
        )
        assertEquals(
            "the section holding a recorded answer must open on an edit — a collapsed header over a " +
                "researcher's own words is the whole of the reported defect",
            setOf("s-a"),
            reveal,
        )
    }

    /**
     * THE CASE THAT MATTERS MOST, AND THE ONE A "does it have answers" RULE WOULD MISS ENTIRELY.
     *
     * These interviews are normally conducted by recording the WHOLE SECTION in one take with the
     * answer boxes hidden — that is what `recordMode` defaults to and what the hidden boxes are for.
     * A correctly captured sitting therefore has ZERO response rows and several media rows. A rule
     * that opened sections by answers alone would leave shut precisely the sections where the work
     * is, on precisely the sittings the researchers actually record.
     */
    @Test
    fun `a section holding only saved recordings is opened`() {
        val reveal = sectionsToRevealOnEdit(
            sections = listOf(section("s-a", "A", "a1"), section("s-b", "B", "b1")),
            responses = emptyList(),
            sectionsWithSavedMedia = setOf("s-b"),
            alreadyRevealed = emptySet(),
        )
        assertEquals(
            "a whole-section audio take is how these sittings are captured; the section holding one " +
                "must open even though it has no typed answer at all",
            setOf("s-b"),
            reveal,
        )
    }

    /**
     * THE SCREEN MUST NOT FIGHT THE READER. `savedMedia` lands asynchronously and across several
     * requests (one per sibling sitting), so the caller's effect re-runs as each batch arrives.
     * Without this, every re-run would re-expand a section the researcher had just deliberately
     * closed — which is worse than the screen that showed them nothing, because it cannot be worked
     * around.
     */
    @Test
    fun `a section this rule has already opened is never opened a second time`() {
        val sections = listOf(section("s-a", "A", "a1"))
        val responses = listOf(answered("a1", "Yes."))
        val first = sectionsToRevealOnEdit(sections, responses, emptySet(), emptySet())
        assertEquals(setOf("s-a"), first)

        val second = sectionsToRevealOnEdit(sections, responses, setOf("s-a"), first)
        assertTrue(
            "once this rule has opened a section, a researcher's decision to close it is final — " +
                "later media arriving must not spring it back open under them: $second",
            second.isEmpty(),
        )
    }

    /**
     * AN EMPTY RESPONSE ROW IS NOT CONTENT. The server creates a row per question the client sends,
     * and a blank one says nothing; opening a section on it shows the researcher an empty box and
     * teaches them that the auto-opening means nothing.
     */
    @Test
    fun `a blank answer does not open a section`() {
        val reveal = sectionsToRevealOnEdit(
            sections = listOf(section("s-a", "A", "a1", "a2")),
            responses = listOf(
                InterviewResponseDto(questionId = "a1", answerText = "", notes = null),
                InterviewResponseDto(questionId = "a2", answerText = "   ", notes = "  "),
            ),
            sectionsWithSavedMedia = emptySet(),
            alreadyRevealed = emptySet(),
        )
        assertTrue("an empty response row is not a reason to open anything: $reveal", reveal.isEmpty())
    }

    /**
     * NOTES COUNT BESIDE ANSWER TEXT, because the server counts them: `_RESPONSE_TEXT_FIELDS` in
     * `backend/app/api/routes/questionnaire.py` is `("answerText", "notes")`, and a merge refuses
     * over a disagreement in EITHER. A row carrying only a note is still a row somebody wrote.
     */
    @Test
    fun `a response carrying only a note opens its section`() {
        val reveal = sectionsToRevealOnEdit(
            sections = listOf(section("s-a", "A", "a1")),
            responses = listOf(InterviewResponseDto(questionId = "a1", answerText = null, notes = "Asked twice.")),
            sectionsWithSavedMedia = emptySet(),
            alreadyRevealed = emptySet(),
        )
        assertEquals(setOf("s-a"), reveal)
    }

    /**
     * A RETIRED QUESTION OPENS NOTHING. The form renders `section.questions.filter { it.isActive }`,
     * so a section whose only answer sits on a deactivated question would open onto a panel that
     * draws nothing — an empty section springing open with no explanation, which reads as a bug
     * rather than as help.
     */
    @Test
    fun `an answer on a question that is no longer active opens nothing`() {
        val retired = QuestionnaireQuestionDto(
            id = "a1",
            sectionId = "s-a",
            sectionCode = "A",
            sectionTitle = "Craft history",
            prompt = "A question that has since been retired",
            sortOrder = 1,
            isActive = false,
        )
        val reveal = sectionsToRevealOnEdit(
            sections = listOf(
                QuestionnaireSectionDto(id = "s-a", code = "A", title = "Craft history", sortOrder = 1, questions = listOf(retired))
            ),
            responses = listOf(answered("a1", "Recorded before the question was retired.")),
            sectionsWithSavedMedia = emptySet(),
            alreadyRevealed = emptySet(),
        )
        assertTrue("a section with nothing left to draw must not open: $reveal", reveal.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  2. Whether the answer box is drawn
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * THE TOGGLE HIDES EMPTY BOXES AND NOT A RESEARCHER'S WORDS — the whole of the rule, and the
     * reason the stored preference never has to be touched to fix the complaint.
     */
    @Test
    fun `a recorded answer is shown even while the hide toggle is on`() {
        assertTrue(
            "an edit carrying a typed answer must SHOW it. Leaving it hidden puts the words in the " +
                "form's state and nothing on the screen, which is indistinguishable from losing them.",
            answerBoxVisible(hideAnswers = true, recordedAnswer = "About forty years."),
        )
        assertFalse(
            "a question the record never answered keeps the toggle's behaviour — the capture screen " +
                "is meant to be a record button and nothing else, and that choice is the reader's",
            answerBoxVisible(hideAnswers = true, recordedAnswer = null),
        )
        assertFalse(
            "a blank stored answer is not an answer",
            answerBoxVisible(hideAnswers = true, recordedAnswer = "   "),
        )
        assertTrue(
            "with the toggle off every box is drawn, answered or not — unchanged behaviour",
            answerBoxVisible(hideAnswers = false, recordedAnswer = null),
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  3. The merge offer's words
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * THE HOLDER IS NAMED. The server's own sentence says only that *an* interview exists and its
     * docstring hands the title over for exactly this — "the naming sentence … is an OFFER with a
     * button on it, and it belongs to the client that owns the button". Asked without the name, the
     * researcher is being invited to fold their afternoon into something they cannot identify.
     */
    @Test
    fun `the offer names the interview that holds the set, and survives an untitled one`() {
        val named = mergeOfferQuestion("D Black Pottery")
        assertTrue("the holder's title must be in the question: $named", named.contains("D Black Pottery"))
        assertTrue("and it must be a question, not a statement: $named", named.trim().endsWith("?"))

        listOf(null, "", "   ").forEach { blank ->
            val fallback = mergeOfferQuestion(blank)
            assertTrue(
                "an untitled holder is still a real row to move into — the offer stands and only its " +
                    "name falls back: $fallback",
                fallback.startsWith("Another interview (untitled)") && fallback.trim().endsWith("?"),
            )
        }
    }

    /**
     * EVERY DISAGREEING QUESTION IS NAMED, not counted. "3 answers disagree" is not something a
     * researcher can act on; a section code and a prompt is what they carry back to the paper.
     */
    @Test
    fun `the conflict list names each question, and falls back to its id`() {
        val lines = mergeConflictLines(
            listOf(
                MergeAnswerConflict("q1", "F", "How many looms are in the workshop?"),
                MergeAnswerConflict("q2", null, "Who taught you?"),
                MergeAnswerConflict("q3", null, null),
            )
        )
        assertEquals(
            listOf(
                "F · How many looms are in the workshop?",
                "Who taught you?",
                "Question q3",
            ),
            lines,
        )
    }

    /**
     * WHAT IS ON THE SCREEN AND NOT IN THE DATABASE, said before the fold takes the row away.
     *
     * The offer is only ever reached from a save that did NOT land, so anything typed or recorded in
     * this form is still only on the screen — and the merge deletes the record it would have been
     * saved to. Counted rather than generic, because "2 recordings" and "17 typed answers" are
     * different decisions about whether to stop and write something down first.
     */
    @Test
    fun `the unsaved warning counts what is pending and is silent when nothing is`() {
        assertNull(
            "the ordinary collision is reached by ticking one missing artisan and pressing save, " +
                "with nothing else pending. A warning printed every time is a warning read none of " +
                "the times — and it would be false besides.",
            unsavedBeforeMergeNotice(typedAnswers = 0, recordings = 0, attachments = 0),
        )
        val one = unsavedBeforeMergeNotice(typedAnswers = 1, recordings = 0, attachments = 0)!!
        assertTrue(
            "one pending item reads as one, in a sentence that agrees with itself: $one",
            one.contains("1 typed answer") && one.contains("screen is still only here"),
        )
        val many = unsavedBeforeMergeNotice(typedAnswers = 3, recordings = 2, attachments = 1)!!
        assertTrue(
            "every kind of pending work is counted and named: $many",
            many.contains("3 typed answers") && many.contains("2 new recordings") &&
                many.contains("1 attached file") && many.contains("screen are still only here"),
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  4. The form actually calls these rules
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    private fun formBody(): String {
        val source = kotlinWithoutComments(
            repoSource(
                "app/src/main/java/com/fieldrepository/app/MainActivity.kt",
                "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt",
            )
        )
        val at = source.indexOf(FORM_ANCHOR)
        assertTrue(
            "$FORM_ANCHOR is no longer in MainActivity.kt. That declaration is how this file finds " +
                "the questionnaire form; if it has been renamed, this parser moves with it.",
            at >= 0,
        )
        val open = source.indexOf('{', source.indexOf(')', at))
        return balancedFrom(source, open, '{', '}')
    }

    /** The expansion rule is called, ON AN EDIT, and its answer is UNIONED rather than assigned. */
    @Test
    fun `the form opens the sections the record holds`() {
        val body = formBody()
        assertTrue(
            "QuestionnaireForm must call `sectionsToRevealOnEdit`. Without it `expandedSections` " +
                "starts empty and stays empty, and an open edit shows a column of collapsed headers " +
                "over answers and recordings it already has in hand.",
            body.contains("sectionsToRevealOnEdit("),
        )
        assertTrue(
            "the result must be UNIONED into `expandedSections`. Assigning over it would close a " +
                "section the researcher had opened by hand while looking for something.",
            Regex("expandedSections\\s*=\\s*expandedSections\\s*\\+\\s*reveal").containsMatchIn(body),
        )
        assertTrue(
            "the rule must be passed what it has already opened (`alreadyRevealed = revealedSections`) " +
                "— the effect re-runs as saved media lands, and without that memory it re-expands " +
                "whatever the researcher has just closed, on every batch.",
            Regex("alreadyRevealed\\s*=\\s*revealedSections").containsMatchIn(body),
        )
        assertTrue(
            "the media half must be attributed with the form's own caption rule, so \"which section " +
                "is this recording under\" has ONE answer on this screen — the one that also decides " +
                "where the clip is drawn and what the collapsed header counts.",
            body.contains("captionBelongsToSection(it.caption, section)"),
        )
    }

    /** The answer box asks the rule, and the stored preference is never written for the reader. */
    @Test
    fun `the form asks whether to draw the answer box, and never flips the preference`() {
        val body = formBody()
        assertTrue(
            "the per-question answer box must be gated by `answerBoxVisible(hideAnswers, …)` rather " +
                "than by `!hideAnswers` alone, or an edit of a typed sitting draws none of its words.",
            Regex("answerBoxVisible\\(\\s*hideAnswers\\s*,").containsMatchIn(body),
        )
        assertTrue(
            "the box must be decided from the RECORD's answer, not the live field — deciding from " +
                "the current value makes the box vanish under the cursor when somebody clears it.",
            body.contains("val recordedAnswer = editing?.responses"),
        )
        assertFalse(
            "the capture preference must never be written on the reader's behalf. \"Do not display " +
                "answer text boxes\" is their choice about the NEXT interview they record; flipping " +
                "it to reveal this one's answers changes a setting they then have to find and undo.",
            Regex("hideAnswers\\s*=\\s*(true|false)\\b").containsMatchIn(body),
        )
        assertTrue(
            "and it must still DEFAULT to on: the capture screen is meant to be a record button and " +
                "nothing else while an interview is being conducted.",
            Regex("var hideAnswers by remember \\{ mutableStateOf\\(true\\) \\}").containsMatchIn(body),
        )
    }

    /** The fold is offered from the refusal's CODE, and only ever happens on a confirmation. */
    @Test
    fun `the form offers the merge on the coded refusal and calls the route only on confirmation`() {
        val body = formBody()
        assertTrue(
            "the refused save must be read with `apiFailure` — `it.message` is Retrofit's, and a " +
                "researcher whose save was refused read \"HTTP 409 Conflict\" while the server's own " +
                "explanation sat unread in the body.",
            body.contains("error.apiFailure(\"Unable to save questionnaire\")"),
        )
        assertTrue(
            "the offer must be branched off `artisanSetHolder()`, which checks the stable code. " +
                "Matching the prose instead stops offering the move the first time somebody improves " +
                "the sentence.",
            body.contains("failure.artisanSetHolder()"),
        )
        assertTrue(
            "the merge route must be reached from the confirmation handler and from nowhere else — " +
                "a save path that called it would weld two researchers' sittings together without " +
                "either of them agreeing to it.",
            Regex("fun confirmMerge\\(holder: InterviewSetHolder\\)[\\s\\S]{0,400}?mergeQuestionnaireInterviewInto\\(")
                .containsMatchIn(body),
        )
        assertEquals(
            "exactly one call to the merge route in this form, inside `confirmMerge`",
            1,
            Regex("mergeQuestionnaireInterviewInto\\(").findAll(body).count(),
        )
        assertTrue(
            "a refused merge must LIST the questions that disagree (`mergeConflictLines`), not just " +
                "report that some do — a count is not something anybody can act on.",
            body.contains("mergeConflictLines(mergeConflicts)"),
        )
        assertTrue(
            "the amber panel must carry its meaning in words as well as in colour, for every reader " +
                "who does not get the colour at all.",
            body.contains("\"Not moved — reconcile these first\""),
        )
    }

    /** The guard every source-scanning suite here keeps: these assertions read code, not prose. */
    @Test
    fun `these assertions read code and not the comments explaining it`() {
        assertFalse(
            "the stripper must remove block comments, or every assertion above is satisfied by the " +
                "paragraphs that argue for them — this repository quotes its own identifiers in prose.",
            kotlinWithoutComments("/* sectionsToRevealOnEdit( */\nval a = 1\n").contains("sectionsToRevealOnEdit("),
        )
        assertFalse(
            "the stripper must remove line comments too.",
            kotlinWithoutComments("// hideAnswers = false\nval a = 1\n").contains("hideAnswers = false"),
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  Fixtures
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    private fun section(id: String, code: String, vararg questionIds: String) = QuestionnaireSectionDto(
        id = id,
        code = code,
        title = "Section $code",
        sortOrder = 1,
        questions = questionIds.mapIndexed { index, questionId ->
            QuestionnaireQuestionDto(
                id = questionId,
                sectionId = id,
                sectionCode = code,
                sectionTitle = "Section $code",
                prompt = "Question ${index + 1}",
                sortOrder = index + 1,
            )
        },
    )

    private fun answered(questionId: String, text: String) =
        InterviewResponseDto(questionId = questionId, answerText = text)

    private companion object {
        const val FORM_ANCHOR = "private fun QuestionnaireForm("
    }
}
