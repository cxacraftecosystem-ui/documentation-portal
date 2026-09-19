package com.fieldrepository.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "THE SECTIONS OF THE 2ND WORKSHOP ARE RENDERING EVEN THOUGH THE 3RD IS SELECTED" — the defect,
 * pinned.
 *
 * ── WHAT WAS ON SCREEN ──────────────────────────────────────────────────────────────────────────
 *
 * `InterviewEditLoader` takes `sections` as a PARAMETER. They are hoisted to the screen and filled
 * once, at startup, by a `questionnaireSections()` call that names no instrument — so the server
 * resolves it by the three-step rule in `services/questionnaire_instruments.py` and lands on the
 * DEFAULT, which is still the 2nd Craft Toolkit Workshop's instrument.
 *
 * Nothing then moved them onto the record's own instrument. The loader never asked, and the effect
 * inside `QuestionnaireForm` that normally follows the instrument is guarded by
 * `if (isEdit || instrumentTouched) return@LaunchedEffect` — DELIBERATELY, because on an edit the
 * picker must not chase the workshop's binding (a sitting cannot change instrument; the API has no
 * field for it). So the form drew its PICKER from the record — `editing?.questionnaireId`, correctly
 * the 3rd — and its QUESTIONS from the screen — the 2nd. One form, two instruments.
 *
 * ── WHY NOTHING CAUGHT IT ───────────────────────────────────────────────────────────────────────
 *
 * The two instruments' section codes collide completely (22 of them; see
 * `backend/tests/test_questionnaire_seed.py`), so the screen looked like a questionnaire and read
 * like one. What actually happened to the researcher is worse than a mislabelled heading:
 *
 *  * `answers` is seeded by matching `editing.responses` against the id of each RENDERED question.
 *    The record's answers carry the 3rd instrument's question ids; the rendered questions were the
 *    2nd's. Nothing matched, so every box came up BLANK — their answers were not lost, they were
 *    simply not on screen, which is indistinguishable from lost to the person looking at it.
 *  * Typing into those boxes and saving sent the 2nd instrument's question ids for a 3rd-instrument
 *    sitting. `upsert_responses` refuses that with a 422 — "These questions belong to a different
 *    questionnaire than this interview … Reload the questionnaire and try again" — a thing this
 *    screen offered no way to do. The backend guard is why no data was corrupted. It is not a
 *    reason to leave the client sending the wrong ids.
 *
 * ── EVERY ASSERTION HERE THAT WOULD HAVE FAILED BEFORE THE FIX ──────────────────────────────────
 *
 *  1. **"the loader fetches the record's own instrument"** — `onRefreshSections` was forwarded to
 *     the form and never called by the loader itself. Against the old source this is absent.
 *  2. **"the form is held until those sections arrive"** — there was no `sectionsReady` gate; the
 *     form was composed the moment the interview landed, on whatever sections the screen held.
 *  3. **"the edit path cannot create"** — the loader passed
 *     `onSubmit = { repository.createQuestionnaireInterview(it).id }`. Unreachable today, because
 *     the form branches on `editing != null` — but a create wired into an EDIT screen is one
 *     refactor away from filing a second sitting, which is exactly what the web page did for
 *     months while answering 201.
 *  4. **"the picker still does not chase the workshop on an edit"** — a REGRESSION GUARD rather
 *     than a bite test: it passes before and after, and exists so the obvious "fix" (deleting the
 *     `isEdit` guard so sections follow the picker) goes red. That would re-point an open edit's
 *     questions at the workshop's bound instrument, which is the same defect wearing a fix's
 *     clothes.
 */
class InterviewEditSectionsTest {

    private val source =
        kotlinWithoutComments(
            repoSource(
                "app/src/main/java/com/fieldrepository/app/MainActivity.kt",
                "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt",
            )
        )

    /** The composable's own body, brace-balanced, so no assertion here can read the file at large. */
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
        // Held, not patched afterwards: swapping 24 blank boxes for 22 different ones underneath a
        // researcher who has begun typing is its own defect.
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
        // REGRESSION GUARD. Passes before and after the fix; it exists so that "just let the sections
        // follow the picker" goes red. A sitting is half of @@unique([questionnaireId, artisanSetKey])
        // and its answers are keyed to one instrument's question ids, so an open edit must never be
        // re-pointed at the workshop's bound instrument.
        assertTrue(
            "QuestionnaireForm's workshop→instrument effect must stay guarded by `isEdit`. Removing " +
                "that guard re-points an open edit's questions at the workshop's bound instrument, " +
                "which is the reported defect with the blame moved.",
            source.contains("if (isEdit || instrumentTouched) return@LaunchedEffect"),
        )
    }

    @Test
    fun `these assertions read code and not the comments explaining it`() {
        // The guard every source-scanning suite in this package keeps: this repository argues about
        // `createQuestionnaireInterview` and `sectionsReady` in prose directly above the code, so a
        // scanner reading raw source would find them whatever the code did.
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
