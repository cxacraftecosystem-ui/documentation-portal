package com.fieldrepository.app.questionnaire

import com.fieldrepository.app.data.FormBindings
import com.fieldrepository.app.data.FormKinds
import com.fieldrepository.app.data.FormLibrary
import com.fieldrepository.app.data.FormLibraryCache
import com.fieldrepository.app.data.FormResolution
import com.fieldrepository.app.data.FormRights
import com.fieldrepository.app.data.FormTab
import com.fieldrepository.app.data.QBinding
import com.fieldrepository.app.data.QBoundForm
import com.fieldrepository.app.data.QDefaultPut
import com.fieldrepository.app.data.QForm
import com.fieldrepository.app.data.QFormFor
import com.fieldrepository.app.data.QFormQuestion
import com.fieldrepository.app.data.QTypeDefaultPut
import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.ui.questionnaire.FormActions
import com.fieldrepository.app.ui.questionnaire.libraryActions
import com.fieldrepository.app.ui.questionnaire.questionPatch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The forms library on Field Repository Android (UNIFY 4.2, stream U4): who may do what with a form
 * (owner, 21 and 22 Oct 2026), the tabs, kinds, bindings, the four-step form resolution and the
 * owner-stamped offline copy. Each request is sent through the real Retrofit interface to a server in
 * memory, so the paths and bodies are the ones the app sends.
 */
class FormsLibraryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun user(id: String, role: String, grant: Boolean = false) = UserDto(id = id, email = "$id@x", name = id, role = role, canManageQuestionnaire = grant)
    private val professor = user("p1", "PROFESSOR")
    private val researcher = user("r1", "RESEARCHER")
    private val admin = user("ad", "ADMIN")
    private val ministry = user("mi", "MINISTRY_ADMIN")

    private val mine = QForm(id = "f1", title = "Toolkit", createdById = "p1", sectionCount = 2, questionCount = 5)
    private val shared = QForm(id = "f2", title = "Standard", isShared = true, isDefault = true, kind = FormKinds.WORKSHOP_INTERVIEW)
    private val bound = QForm(id = "f3", title = "Survey", createdById = "x", workshopCount = 2, kind = FormKinds.MARKET_SURVEY)
    private val gone = QForm(id = "f4", title = "Old", createdById = "p1", isActive = false)
    private val all = listOf(mine, shared, bound, gone)

    @QFeature("D3")
    @Test fun `Professors and the questionnaire grant create their own forms; researchers do not`() {
        assertTrue(FormRights.mayCreate(professor))
        assertTrue(FormRights.mayCreate(researcher.copy(canManageQuestionnaire = true)))
        assertFalse(FormRights.mayCreate(researcher))
        assertEquals(listOf("New form", "Upload workbook", "Download pro-forma", "Open bundle"), libraryActions(professor))
        assertEquals(listOf("Download pro-forma"), libraryActions(researcher))
    }

    @QFeature("A6")
    @Test fun `A form is edited by its author or a content admin, and a reword sends only the new words`() = runBlocking {
        assertTrue(FormRights.mayChange(professor, mine))
        assertTrue(FormRights.mayChange(ministry, bound))
        assertFalse(FormRights.mayChange(professor, bound))
        assertFalse("no recorded author: content admins only", FormRights.mayChange(professor, shared))
        assertTrue(FormActions.of(professor, mine, local = false).edit)
        assertFalse(FormActions.of(professor, bound, local = false).edit)

        val q = QFormQuestion(id = "q1", prompt = "Which wood?", helpText = null, isRequired = false)
        assertNull("unchanged: nothing to send", questionPatch(q, "Which wood?", "", false))
        val patch = questionPatch(q, "Which wood, and from where?", "", true)!!
        assertEquals("Which wood, and from where?", patch.prompt)
        assertNull(patch.helpText)
        assertEquals(true, patch.isRequired)

        val server = FakeServer()
        server.on("PATCH /questionnaire/questions/q1", """{"id":"q2","prompt":"Which wood, and from where?","supersededById":null}""")
        val saved = server.forms.patchQuestion("q1", patch)
        assertEquals("the server supersedes an answered question with a new row", "q2", saved.id)
        assertEquals("""{"prompt":"Which wood, and from where?","isRequired":true}""", server.last().body)
    }

    @QFeature("A13")
    @Test fun `Delete is the author's or a content admin's, P16 says admins restore, and Deleted is the content admins' tab`() = runBlocking {
        assertTrue(FormRights.mayDelete(professor, mine))
        assertFalse(FormRights.mayDelete(researcher, mine))
        assertTrue(FormActions.of(admin, bound, local = false).delete)
        assertEquals(listOf(FormTab.MINE, FormTab.SHARED, FormTab.WORKSHOP), FormTab.visible(professor))
        assertEquals(FormTab.entries.toList(), FormTab.visible(ministry))
        assertEquals(listOf("f4"), FormLibrary.tab(all, FormTab.DELETED, "p1").map { it.id })
        assertTrue(FormActions.of(admin, gone, local = false).restore)
        assertFalse(FormActions.of(professor, gone, local = false).restore)
        assertEquals("Admins can restore it from Deleted.", com.fieldrepository.app.data.FormsCopy.P16_BODY)

        val server = FakeServer()
        server.on("DELETE /questionnaires/f1", "{}")
        server.on("POST /questionnaires/f4/restore", """{"id":"f4","title":"Old"}""")
        assertTrue(server.forms.deleteForm("f1").isSuccessful)
        assertEquals("f4", server.forms.restoreForm("f4").id)
        assertEquals(listOf("DELETE /questionnaires/f1", "POST /questionnaires/f4/restore"), server.seen.map { "${it.method} ${it.path}" })
    }

    @QFeature("A7")
    @Test fun `Share with all is an admin act, served on Field Repository Android, and the list badges it`() = runBlocking {
        assertTrue(FormActions.of(ministry, mine, local = false).share)
        assertFalse(FormActions.of(professor, mine, local = false).share)
        assertEquals(listOf("Default", "Shared with all", "Workshop interview"), FormLibrary.badges(shared))
        assertEquals(listOf("f2"), FormLibrary.tab(all, FormTab.SHARED, "p1").map { it.id })
        val server = FakeServer()
        server.on("PATCH /questionnaires/f1", """{"id":"f1","title":"Toolkit","isShared":true}""")
        val out = server.forms.patchForm("f1", buildJsonObject { put("isShared", JsonPrimitive(true)) })
        assertTrue(out.isShared)
        assertEquals("""{"isShared":true}""", server.last().body)
    }

    @QFeature("A8")
    @Test fun `Kinds come from the shared vocabulary, filter the library and group the picker`() {
        assertEquals("Market survey", FormKinds.label(FormKinds.MARKET_SURVEY))
        assertEquals("Kind not stated", FormKinds.label(null))
        assertEquals(listOf("f3"), FormLibrary.tab(all, FormTab.WORKSHOP, "p1", kind = FormKinds.MARKET_SURVEY).map { it.id })
        assertEquals(listOf("Workshop interview", "Market survey", "Kind not stated"), FormKinds.grouped(listOf(bound, mine, shared)).map { it.first })
    }

    @QFeature("A9")
    @Test fun `Several forms per workshop, and one primary, are written as one list of bindings`() = runBlocking {
        var list = FormBindings.set(emptyList(), "w1", bound = true, primary = false)
        list = FormBindings.set(list, "w2", bound = true, primary = true)
        list = FormBindings.set(list, "w1", bound = true, primary = true)
        list = FormBindings.set(list, "w3", bound = true, primary = false)
        list = FormBindings.set(list, "w3", bound = false, primary = false)
        val body = FormBindings.body(list)
        assertEquals(listOf("w2" to true, "w1" to true), body.bindings.map { it.workshopId to it.isPrimary })
        assertTrue(FormActions.of(admin, mine, local = false).bind)
        assertFalse(FormActions.of(professor, mine, local = false).bind)
        val server = FakeServer()
        server.on("PUT /questionnaire/forms/f1/bindings", """{"questionnaireId":"f1","bindings":[{"workshopId":"w2","isPrimary":true}]}""")
        val saved = server.forms.putBindings("f1", body)
        assertEquals(listOf(QBinding(workshopId = "w2", isPrimary = true)), saved.bindings)
        assertTrue(server.last().body.contains(""""workshopId":"w1","isPrimary":true"""))
    }

    @QFeature("B31")
    @Test fun `The start sheet takes the workshop's primary or only binding from the server`() {
        val forms = listOf(mine, shared, bound)
        assertEquals("f3", FormResolution.pick(null, QFormFor("f3", "PRIMARY_BINDING", listOf(QBoundForm("f3", true))), forms))
        assertEquals("f1", FormResolution.pick("f1", QFormFor("f3", "PRIMARY_BINDING"), forms))
        assertNull("several bound and none primary: the picker", FormResolution.pick(null, QFormFor(null, "PICKER", listOf(QBoundForm("f1"), QBoundForm("f3"))), forms))
        assertEquals(listOf("f3", "f2", "f1"), FormResolution.ordered(forms, QFormFor("f3", "ONLY_BINDING", listOf(QBoundForm("f3")))).map { it.id })
    }

    @QFeature("B32")
    @Test fun `With nothing bound the default form is offered, and only an admin sets it`() = runBlocking {
        assertEquals("f2", FormResolution.pick(null, QFormFor("f2", "GLOBAL_DEFAULT"), listOf(mine, shared)))
        assertEquals("offline: the cached default", "f2", FormResolution.pick(null, null, listOf(mine, shared)))
        assertTrue(FormRights.maySetDefault(admin))
        assertFalse("require_admin: Ministry Admin is a content admin, not an admin", FormRights.maySetDefault(ministry))
        val server = FakeServer()
        server.on("PUT /questionnaires/f1/default", """{"id":"f1","title":"Toolkit","isDefault":true}""")
        assertTrue(server.forms.setDefault("f1", QDefaultPut(true)).isDefault)
    }

    @QFeature("D2")
    @Test fun `A workshop type's default form is the step between bindings and the global default`() = runBlocking {
        assertEquals("f3", FormResolution.pick(null, QFormFor("f3", "TYPE_DEFAULT"), listOf(mine, shared, bound)))
        assertTrue(FormActions.of(ministry, mine, local = false).typeDefault)
        val server = FakeServer()
        server.on("PUT /questionnaire/type-defaults", """{"questionnaireId":"f3","workshopType":"DESIGN_PROTOTYPE"}""")
        server.forms.putTypeDefault(QTypeDefaultPut("f3", "DESIGN_PROTOTYPE"))
        assertEquals("""{"questionnaireId":"f3","workshopType":"DESIGN_PROTOTYPE"}""", server.last().body)
        server.on("GET /questionnaire/form-for", """{"questionnaireId":"f3","reason":"TYPE_DEFAULT","bound":[]}""")
        assertEquals("TYPE_DEFAULT", server.forms.formFor("w9").reason)
        assertEquals("/questionnaire/form-for?workshopId=w9", server.last().path)
    }

    @QFeature("A16")
    @Test fun `The library is read offline from a copy stamped with the account that fetched it`() {
        val cache = FormLibraryCache(tmp.newFile("forms.json"))
        cache.putList("p1", listOf(mine, shared))
        cache.putDetail("p1", mine.copy(sections = emptyList(), description = "Craft toolkit"))
        assertEquals(listOf("f1", "f2"), cache.read("p1")!!.forms.map { it.id })
        assertEquals("Craft toolkit", cache.detail("p1", "f1")!!.description)
        assertNull("another account reads nothing", cache.read("r1"))
        assertNull(cache.detail("r1", "f1"))
        assertNull("signed out reads nothing", cache.read(null))
        cache.putList("p1", listOf(shared))
        assertNull("a form gone from the list leaves the copy", cache.detail("p1", "f1"))
    }
}
