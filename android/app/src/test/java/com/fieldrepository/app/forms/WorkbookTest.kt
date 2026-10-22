package com.fieldrepository.app.forms

import com.fieldrepository.app.data.Naming

import com.fieldrepository.app.questionnaire.data.QuestionnaireExport
import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.questionnaire.QFeature
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Workbooks on Field Repository Android (A1-A4): the pro-forma, the export and question set, a new
 * form from an uploaded workbook (with hand-filled answers imported by the server), and a re-upload
 * by the form's author or a content admin. Every file is named by the server or the naming helper.
 */
class WorkbookTest {
    private fun user(id: String, role: String) = UserDto(id = id, email = "$id@x", name = id, role = role)
    private val xlsx = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private fun named(name: String) = FakeServer.Reply(body = "PK", type = xlsx, headers = mapOf("Content-Disposition" to "attachment; filename=\"$name\"; filename*=UTF-8''$name"))

    @QFeature("A1")
    @Test fun `The pro-forma downloads under the server's name, else the helper's undated one`() = runBlocking {
        val server = FakeServer()
        server.on("GET /questionnaires/pro-forma", named("FieldRepository_Questionnaire_Pro-Forma.xlsx"))
        val response = server.forms.proForma()
        assertEquals("FieldRepository_Questionnaire_Pro-Forma.xlsx", QuestionnaireExport.dispositionFileName(response.headers()["Content-Disposition"]))
        assertEquals("FieldRepository_Questionnaire_Pro-Forma.xlsx", Naming.exportFilename(entity = "Questionnaire", what = "Pro-Forma", ext = "xlsx", dated = false, portal = "FieldRepository"))
    }

    @QFeature("A2")
    @Test fun `A workbook uploads as the file part, and the new form and its report come back`() = runBlocking {
        val server = FakeServer()
        server.on("POST /questionnaires/upload", """{"questionnaire":{"id":"f9","title":"Toolkit","sectionCount":24,"questionCount":285},
            "report":{"sections":24,"questions":285,"answersImported":12,"sessionsCreated":3}}""")
        val out = server.forms.uploadWorkbook(workbookPartOf("PK".toByteArray(), "Toolkit (filled).xlsx"))
        assertEquals("f9", out.questionnaire!!.id)
        assertEquals("12", out.report!!["answersImported"].toString())
        val seen = server.last()
        assertTrue(seen.contentType!!.startsWith("multipart/form-data"))
        assertTrue(seen.body.contains("name=\"file\""))
        assertTrue("the leaf is ASCII", Regex("filename=\"[\\x20-\\x7E]+\"").containsMatchIn(seen.body))
        assertTrue(FormRights.mayCreate(user("p", "PROFESSOR")))
    }

    @QFeature("A3")
    @Test fun `A form's workbook and its question set download under the server's names`() = runBlocking {
        val server = FakeServer()
        server.on("GET /questionnaires/f1/xlsx", named("FieldRepository_Questionnaire_2026-10-22_Toolkit_Export.xlsx"))
        server.on("GET /questionnaires/f1/question-set.xlsx", named("FieldRepository_Questionnaire_2026-10-22_Toolkit_Questions.xlsx"))
        assertEquals("FieldRepository_Questionnaire_2026-10-22_Toolkit_Export.xlsx", QuestionnaireExport.dispositionFileName(server.forms.workbook("f1").headers()["Content-Disposition"]))
        assertEquals("FieldRepository_Questionnaire_2026-10-22_Toolkit_Questions.xlsx", QuestionnaireExport.dispositionFileName(server.forms.questionSet("f1").headers()["Content-Disposition"]))
        assertEquals(
            "FieldRepository_Questionnaire_2026-10-22_Toolkit_Questions.xlsx",
            Naming.exportFilename(entity = "Questionnaire", what = "Questions", ext = "xlsx", subject = "Toolkit", day = "2026-10-22", portal = "FieldRepository")
        )
    }

    @QFeature("A4")
    @Test fun `Re-upload is the author's or a content admin's, and the server's report says what it kept`() = runBlocking {
        val form = QForm(id = "f1", title = "Toolkit", createdById = "p1")
        assertTrue(FormActions.of(user("p1", "PROFESSOR"), form, local = false).reupload)
        assertTrue(FormActions.of(user("m", "MINISTRY_ADMIN"), form, local = false).reupload)
        assertFalse(FormActions.of(user("p2", "PROFESSOR"), form, local = false).reupload)
        val server = FakeServer()
        server.on("POST /questionnaires/f1/upload", """{"questionnaire":{"id":"f1","title":"Toolkit"},"report":{"kept":3,"message":"3 questions not in this file were kept. Ask an admin to remove them."}}""")
        val out = server.forms.reuploadWorkbook("f1", workbookPartOf("PK".toByteArray(), "toolkit.xlsx"))
        assertEquals("POST /questionnaires/f1/upload", "${server.last().method} ${server.last().path}")
        assertTrue(out.report.toString().contains("Ask an admin"))
    }
}
