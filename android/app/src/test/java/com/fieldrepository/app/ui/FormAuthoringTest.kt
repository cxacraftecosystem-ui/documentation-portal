package com.fieldrepository.app.ui

import com.fieldrepository.app.data.QuestionnaireDto
import com.fieldrepository.app.data.UserDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner, 22 Oct 2026, applied strictly (the backend 403s everyone else): a questionnaire form's
 * author OR a content admin edits the form and everything in it, and deletes from it. Every other
 * questionnaire manager -- a Professor, a holder of the canManageQuestionnaire grant -- only reads it.
 * A form with no recorded author is content admins' alone. Deleting sessions and recordings stays
 * content admins only.
 */
class FormAuthoringTest {
    private fun user(id: String, role: String, canManageQuestionnaire: Boolean = false) =
        UserDto(id = id, email = "$id@example.org", name = id, role = role, canManageQuestionnaire = canManageQuestionnaire)

    private val form = QuestionnaireDto(id = "q1", title = "3rd Craft Toolkit", createdById = "prof")
    private val contentAdminRoles = listOf("ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN")

    @Test fun `the author edits their own form`() {
        assertTrue(mayEditQuestionnaire(user("prof", "PROFESSOR"), form.createdById))
        assertTrue("a grant-holding researcher who wrote the form edits it",
            mayEditQuestionnaire(user("res", "RESEARCHER", canManageQuestionnaire = true), "res"))
    }

    @Test fun `every content admin role edits any form`() {
        contentAdminRoles.forEach { role ->
            assertTrue(role, mayEditQuestionnaire(user("someone", role), form.createdById))
        }
    }

    @Test fun `another professor who did not write the form only reads it`() {
        assertFalse(mayEditQuestionnaire(user("other", "PROFESSOR"), form.createdById))
    }

    @Test fun `a canManageQuestionnaire grant holder who did not write the form only reads it`() {
        assertFalse(mayEditQuestionnaire(user("res", "RESEARCHER", canManageQuestionnaire = true), form.createdById))
    }

    @Test fun `a form with no recorded author is content admins only`() {
        contentAdminRoles.forEach { role ->
            assertTrue(role, mayEditQuestionnaire(user("someone", role), null))
            assertTrue(role, mayEditQuestionnaire(user("someone", role), ""))
        }
        assertFalse(mayEditQuestionnaire(user("prof", "PROFESSOR"), null))
        assertFalse(mayEditQuestionnaire(user("res", "RESEARCHER", canManageQuestionnaire = true), null))
        assertFalse("a blank author is no author, not a match for a blank id",
            mayEditQuestionnaire(UserDto(id = "", email = "x@example.org", name = "x", role = "PROFESSOR"), ""))
    }

    @Test fun `nobody signed in edits nothing`() {
        assertFalse(mayEditQuestionnaire(null, form.createdById))
        assertFalse(mayEditQuestionnaire(null, null))
    }

    @Test fun `deleting a take or a session is still content admins only`() {
        assertFalse(mayDelete(user("prof", "PROFESSOR")))
        contentAdminRoles.forEach { role -> assertTrue(role, mayDelete(user("someone", role))) }
    }

    @Test fun `the builder's edit and delete controls follow mayEditQuestionnaire`() {
        val main = File("src/main/java/com/fieldrepository/app/MainActivity.kt").readText()
        val builder = main.substringAfter("private fun QuestionnaireBuilder(").substringBefore("\n@Composable")
        assertFalse("the builder no longer gates on LocalMayDelete", builder.contains("LocalMayDelete"))
        assertFalse("canEdit has no default -- every caller asks the rule", builder.contains("canEdit: Boolean = true"))
        assertTrue(builder.contains("if (canEdit) {"))
        assertTrue(builder.contains("if (canEdit) OutlinedButton("))
        assertTrue("editors never open for a reader", builder.contains("val updating = section.id in updatingSections && canEdit"))
        assertTrue("a reader is told why", builder.contains("QUESTIONNAIRE_READ_ONLY_NOTE"))
        val callers = Regex("""canEdit = com\.fieldrepository\.app\.ui\.mayEditQuestionnaire\(""").findAll(main).count()
        assertTrue("both builder entry points ask mayEditQuestionnaire", callers == 2)
        assertFalse("the old form-object predicate is gone", main.contains("canEditQuestionnaire("))
    }
}
