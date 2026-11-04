package com.fieldrepository.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterviewEditSectionsTest {

    private val source =
        kotlinWithoutComments(
            repoSource(
                "app/src/main/java/com/fieldrepository/app/MainActivity.kt",
                "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt",
            )
        )

    private fun loaderBody(): String {
        val at = source.indexOf(LOADER_ANCHOR)
        assertTrue(
            "$LOADER_ANCHOR is no longer in MainActivity.kt. That declaration is how this file finds " +
                "the handset's interview edit screen; if it has been renamed or moved, this test moves " +
                "with it — do not delete it to make a refactor pass.",
            at >= 0,
        )
        val paramsAt = source.indexOf('(', at)
        val params = balancedFrom(source, paramsAt, '(', ')')
        val bodyAt = source.indexOf('{', paramsAt + params.length)
        assertTrue("no body found for $LOADER_ANCHOR", bodyAt > 0)
        return balancedFrom(source, bodyAt, '{', '}')
    }

    @Test
    fun `the edit loader fetches the sections of the interview's own instrument`() {
        val body = loaderBody()
        assertTrue(
            "InterviewEditLoader must ask for the sections of the instrument the RECORD is on, once " +
                "the record has loaded. Without this the form renders whatever instrument the screen " +
                "resolved at startup — the default — while its picker names the record's own.",
            body.contains("onRefreshSections(loaded.questionnaireId)"),
        )
    }

    @Test
    fun `the form is not composed until those sections have arrived`() {
        val body = loaderBody()
        assertTrue(
            "the form must be gated on the record's sections having arrived, not composed as soon as " +
                "the interview does — otherwise the wrong instrument's questions render first and are " +
                "replaced under the researcher.",
            body.contains("!sectionsReady"),
        )
        assertTrue(
            "`sectionsReady` must be set only after the section fetch SUCCEEDS; setting it beside the " +
                "request would open the form on the stale list the fetch was meant to replace.",
            body.contains(".onSuccess { sectionsReady = true }"),
        )
        assertTrue(
            "a failed section fetch must end the wait in a sentence rather than a permanent spinner.",
            body.contains("sectionsError"),
        )
    }

    @Test
    fun `the edit path cannot file a second sitting`() {
        val body = loaderBody()
        assertFalse(
            "InterviewEditLoader must not wire a CREATE into an edit screen. The form branches on " +
                "`editing != null` and PATCHes, so this lambda is unreachable today — but a create " +
                "sitting in an edit screen is one refactor away from silently filing a duplicate for " +
                "the artisan set instead of correcting the record on screen.",
            body.contains("createQuestionnaireInterview"),
        )
    }

    @Test
    fun `the instrument picker still does not chase the workshop while an edit is open`() {
        assertTrue(
            "QuestionnaireForm's workshop→instrument effect must stay guarded by `isEdit`. Removing " +
                "that guard re-points an open edit's questions at the workshop's bound instrument, " +
                "which is the reported defect with the blame moved.",
            source.contains("if (isEdit || instrumentTouched) return@LaunchedEffect"),
        )
    }

    @Test
    fun `these assertions read code and not the comments explaining it`() {
        assertTrue(
            "the stripper must remove block comments, or `the edit path cannot file a second sitting` " +
                "is satisfied by prose rather than by the absence of a call.",
            kotlinWithoutComments("/* createQuestionnaireInterview */\nval a = 1\n")
                .contains("createQuestionnaireInterview")
                .not(),
        )
        assertTrue(
            "the stripper must remove line comments too.",
            kotlinWithoutComments("// createQuestionnaireInterview\nval a = 1\n")
                .contains("createQuestionnaireInterview")
                .not(),
        )
    }

    private companion object {
        const val LOADER_ANCHOR = "private fun InterviewEditLoader("
    }
}
