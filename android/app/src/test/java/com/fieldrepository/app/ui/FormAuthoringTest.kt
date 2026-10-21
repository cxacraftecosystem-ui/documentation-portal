package com.fieldrepository.app.ui

import com.fieldrepository.app.data.QuestionnaireDto
import com.fieldrepository.app.data.UserDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner amendment, 22 Oct 2026: a questionnaire form's author OR a content admin edits and deletes the
 * form and everything in it; everyone else reads it. Deleting sessions and recordings stays content
 * admins only.
 */
class FormAuthoringTest {
    private fun user(id: String, role: String) = UserDto(id = id, email = "$id@example.org", name = id, role = role)
    private val form = QuestionnaireDto(id = "q1", title = "3rd Craft Toolkit", createdById = "prof")

    @Test fun `the author edits their own form`() {
        assertTrue(canEditQuestionnaire(user("prof", "PROFESSOR"), form))
    }

    @Test fun `a content admin edits any form, the author's or not`() {
        listOf("ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN").forEach { role ->
            assertTrue(role, canEditQuestionnaire(user("someone", role), form))
            assertTrue(role, canEditQuestionnaire(user("someone", role), form.copy(createdById = null)))
        }
    }

    @Test fun `anyone else only reads it, a manager included`() {
        assertFalse(canEditQuestionnaire(user("other", "PROFESSOR"), form))
        assertFalse(canEditQuestionnaire(user("prof", "PROFESSOR"), form.copy(createdById = null)))
        assertFalse(canEditQuestionnaire(null, form))
        assertFalse("deleting a take or a session is still content admins only", mayDelete(user("prof", "PROFESSOR")))
    }

    @Test fun `the builder's edit and delete controls follow the rule, not the delete permission`() {
        val main = File("src/main/java/com/fieldrepository/app/MainActivity.kt").readText()
        val builder = main.substringAfter("private fun QuestionnaireBuilder(").substringBefore("\n@Composable")
        assertFalse("the builder no longer gates on LocalMayDelete", builder.contains("LocalMayDelete"))
        assertTrue(builder.contains("if (canEdit) OutlinedButton("))
        assertTrue(main.contains("canEdit = com.fieldrepository.app.ui.canEditQuestionnaire(user,"))
    }
}
