package com.fieldrepository.app.forms

import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.data.QuestionnaireExport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Field Repository Android reads out of the questionnaire as research data: the corpus (B29),
 * the workshop annexure (A12), and the annexure as the workshop export prints it (C10: the export
 * reads live takes and answers). Requests go through the real Retrofit interface to a server in memory.
 */
class ExportsTest {
    private fun user(id: String, role: String) = UserDto(id = id, email = "$id@x", name = id, role = role)

    private val annexureJson = """{"workshopId":"w1","workshopTitle":"Bagru block printing","groups":[
        {"kind":"WORKSHOP_INTERVIEW","label":"Workshop interview","stage":"WORKSHOP_EXPORT_ANNEXURE","forms":[
          {"id":"f1","title":"2nd Craft Toolkit","kind":"WORKSHOP_INTERVIEW","sessionCount":1,"sections":[
            {"code":"A","title":"About","questions":[
              {"displayCode":"A1","prompt":"Which wood?","answers":[{"subject":"Giriraj Prasad","text":"Teak"},{"subject":"Meena","text":"Sal"}]}]}]}]},
        {"kind":null,"label":"Kind not stated","stage":"WORKSHOP_EXPORT_ANNEXURE","forms":[]}]}"""

    @QFeature("B29")
    @Test fun `The corpus downloads behind the dataset gate, named by the server`() = runBlocking {
        assertTrue(FormRights.mayExportCorpus(user("p", "PROFESSOR")))
        assertFalse(FormRights.mayExportCorpus(user("r", "RESEARCHER")))
        val server = FakeServer()
        server.on("GET /questionnaire/corpus.csv", FakeServer.Reply(body = "a,b\n", type = "text/csv",
            headers = mapOf("Content-Disposition" to "attachment; filename=\"FieldRepository_Questionnaire_2026-10-22_Toolkit_Corpus-Answers.csv\"")))
        val response = server.forms.corpusCsv("f1", "recordings")
        assertEquals("/questionnaire/corpus.csv?questionnaireId=f1&kind=recordings&status=APPROVED", server.last().path)
        assertEquals("FieldRepository_Questionnaire_2026-10-22_Toolkit_Corpus-Answers.csv", QuestionnaireExport.dispositionFileName(response.headers()["Content-Disposition"]))
    }

    @QFeature("A12")
    @Test fun `The annexure lists a workshop's forms by kind with each question's answers`() = runBlocking {
        val server = FakeServer()
        server.on("GET /questionnaire/annexure", annexureJson)
        val annexure = server.forms.annexure("w1")
        assertEquals("/questionnaire/annexure?workshopId=w1", server.last().path)
        assertEquals(listOf("Workshop interview", "Kind not stated"), annexure.groups.map { it.label })
        assertEquals("2nd Craft Toolkit · 1 session", AnnexureModel.formLine(annexure.groups[0].forms[0]))
        assertEquals(2, AnnexureModel.answerCount(annexure))
        assertTrue(FormRights.mayReadAnnexure(user("p", "PROFESSOR")))
        assertFalse("research data: not for a researcher without the grants", FormRights.mayReadAnnexure(user("r", "RESEARCHER")))
        assertTrue("Workshop annexure" in com.fieldrepository.app.forms.libraryActions(user("p", "PROFESSOR")))
    }

    @QFeature("C10")
    @Test fun `The annexure the workshop export prints downloads as text under the server's name`() = runBlocking {
        val server = FakeServer()
        server.on("GET /questionnaire/annexure", FakeServer.Reply(
            body = "Questionnaire annexure: Bagru block printing\n\n== Workshop interview ==\n  A1. Which wood?\n     Giriraj Prasad: Teak\n", type = "text/plain",
            headers = mapOf("Content-Disposition" to "attachment; filename=\"FieldRepository_Questionnaire_2026-10-22_Bagru-Block-Printing_Annexure.txt\"")
        ))
        val response = server.forms.annexureText("w1")
        assertEquals("/questionnaire/annexure?workshopId=w1&format=txt", server.last().path)
        assertEquals("FieldRepository_Questionnaire_2026-10-22_Bagru-Block-Printing_Annexure.txt", QuestionnaireExport.dispositionFileName(response.headers()["Content-Disposition"]))
        assertTrue(response.body()!!.string().contains("Giriraj Prasad: Teak"))
    }
}
