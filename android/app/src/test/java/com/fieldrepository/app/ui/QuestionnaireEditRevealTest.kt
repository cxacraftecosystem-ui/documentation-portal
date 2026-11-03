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

class QuestionnaireEditRevealTest {

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
