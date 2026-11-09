package com.fieldrepository.app.forms

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.data.UserDto
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FormDetailInHostUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val user = UserDto(id = "form-host-user", email = "form-host@x", name = "Nandini", role = "PROFESSOR")
    private val formId = "q_ctw4_device"

    private fun fourthWorkshop(): QForm {
        var n = 0
        val sections = (1..23).map { s ->
            val code = "CTW4.${'A' + (s - 1) % 26}.${s.toString().padStart(2, '0')}"
            val count = if (s <= 16) 5 else 4
            QFormSection(
                id = "sec-$s", code = code, title = "Section $s of the fourth workshop", sortOrder = s,
                questions = (1..count).map { q ->
                    n++
                    QFormQuestion(id = "q-$n", sectionId = "sec-$s", sectionCode = code, prompt = "Question $n: what changed in the craft since the third workshop?", sortOrder = q, displayCode = "$code.$q", lineageId = "l-$n")
                }
            )
        }
        return QForm(id = formId, title = "4th Craft Toolkit Workshop", sectionCount = sections.size, questionCount = n, sections = sections, ownerId = user.id)
    }

    private fun open(widthDp: Int, heightDp: Int) {
        val form = fourthWorkshop()
        check(form.sections.size == 23 && form.questionCount == 108)
        FormsV2.get(context).cache.putDetail(user.id, form)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.requiredSize(widthDp.dp, heightDp.dp).verticalScroll(rememberScrollState()).padding(16.dp).testTag("forms.host")) {
                        FormDetailScreen(
                            v2 = FormsV2.get(context), user = user, formId = formId, workshops = emptyList(),
                            onBack = {}, onOpenForm = {}, onOpenSession = {}, onError = {}
                        )
                    }
                }
            }
        }
        val last = hasText("Section 23 of the fourth workshop", substring = true)
        compose.waitUntil(20_000) { compose.onAllNodes(last).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("4th Craft Toolkit Workshop").assertExists()
        compose.onNodeWithTag("forms.host").performScrollToNode(last)
        compose.onNode(last).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("question-q-108"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("forms.host").performScrollToNode(hasTestTag("question-q-108"))
        compose.waitForIdle()
    }

    @Test fun theFourthWorkshopFormOpensInsideTheFormsTabOnATablet() = open(1280, 800)

    @Test fun theFourthWorkshopFormOpensInsideTheFormsTabOnAPhone() = open(390, 844)
}
