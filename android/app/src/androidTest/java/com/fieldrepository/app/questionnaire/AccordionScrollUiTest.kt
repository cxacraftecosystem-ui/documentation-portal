package com.fieldrepository.app.questionnaire

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.questionnaire.ui.InstrumentQuestion
import com.fieldrepository.app.questionnaire.ui.InstrumentSection
import com.fieldrepository.app.questionnaire.ui.OverflowItem
import com.fieldrepository.app.questionnaire.ui.QuestionRow
import com.fieldrepository.app.questionnaire.ui.QuestionState
import com.fieldrepository.app.questionnaire.ui.QuestionStatus
import com.fieldrepository.app.questionnaire.ui.ScrollOpenSectionToTop
import com.fieldrepository.app.questionnaire.ui.SectionCard
import com.fieldrepository.app.questionnaire.ui.SectionStatus
import com.fieldrepository.app.questionnaire.ui.accordionTail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccordionScrollUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val sections = ('A'..'L').map { c ->
        InstrumentSection("sec$c", "$c", "Section $c title", (1..4).map { InstrumentQuestion("l$c$it", "$c$it", "Question $c$it?", "sec$c") })
    }

    private lateinit var list: LazyListState
    private var open: String? by mutableStateOf(null)

    private fun accordion(byQuestion: Boolean = false) {
        compose.setContent {
            MaterialTheme {
                list = rememberLazyListState()
                var opened by remember { mutableStateOf<String?>(null) }
                open = opened
                ScrollOpenSectionToTop(list, opened, sections.indexOfFirst { it.id == opened }.takeIf { it >= 0 }?.let { it + 1 })
                LazyColumn(state = list, modifier = Modifier.testTag("list")) {
                    item("top") { Box(Modifier.fillMaxWidth().height(160.dp)) { Text("18 of 108 questions") } }
                    sections.forEach { s ->
                        item("sec-${s.id}") {
                            SectionCard(
                                section = s, status = SectionStatus(s.id, 0, s.questions.size, inSectionTake = false, typedOnly = false),
                                open = opened == s.id, byQuestion = byQuestion,
                                onToggle = { opened = if (opened == s.id) null else s.id }, pair = {}
                            ) {
                                s.questions.forEach { q ->
                                    QuestionRow(
                                        question = q, status = QuestionStatus(QuestionState.NOT_ASKED), byQuestion = byQuestion, locked = false,
                                        typedPreview = null, typing = false,
                                        overflow = listOf(OverflowItem("Type an answer", Icons.Filled.Keyboard) {}),
                                        showPair = byQuestion, showUpload = true, onRecord = {}, onUpload = {}, onOpenTyped = {}, onOpenTakes = {}
                                    ) {}
                                }
                            }
                        }
                    }
                    accordionTail(list, opened != null)
                }
            }
        }
    }

    @QFeature("Q2")
    @Test fun openingASectionClosesTheOthersAndPutsItsHeaderAtTheTop() {
        accordion()
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("questionnaire.section.H"))
        compose.onNodeWithTag("questionnaire.section.H").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { list.firstVisibleItemIndex == 8 && list.firstVisibleItemScrollOffset == 0 }
        assertEquals("secH", open)
        val top = compose.onNodeWithTag("list").fetchSemanticsNode().boundsInRoot.top
        val header = compose.onNodeWithTag("questionnaire.section.H").fetchSemanticsNode().boundsInRoot.top
        assertEquals(top, header, 2f)
        compose.onNodeWithText("Question H1?").assertIsDisplayed()

        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("questionnaire.section.C"))
        compose.onNodeWithTag("questionnaire.section.C").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { list.firstVisibleItemIndex == 3 && list.firstVisibleItemScrollOffset == 0 }
        assertEquals("secC", open)
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasText("Question H1?")).fetchSemanticsNodes().size)
        compose.onNodeWithText("Question C1?").assertIsDisplayed()
    }

    @QFeature("Q3")
    @Test fun byQuestionEveryQuestionCarriesItsOwnRecordAndUploadPairOfEqualWidth() {
        accordion(byQuestion = true)
        compose.onNodeWithTag("questionnaire.section.A").performClick()
        compose.waitForIdle()
        val record = compose.onAllNodes(hasTestTag("questionnaire.pair.record")).fetchSemanticsNodes()
        val upload = compose.onAllNodes(hasTestTag("questionnaire.pair.upload")).fetchSemanticsNodes()
        assertEquals(4, record.size)
        assertEquals(4, upload.size)
        val r = record.first().boundsInRoot
        val u = upload.first().boundsInRoot
        assertEquals(r.width, u.width, 1f)
        assertEquals(r.height, u.height, 1f)
        assertTrue(u.left > r.right)
    }
}
