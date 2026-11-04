package com.fieldrepository.app.questionnaire

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.questionnaire.data.KeptPages
import com.fieldrepository.app.questionnaire.data.ListPaging
import com.fieldrepository.app.questionnaire.data.QRecording
import com.fieldrepository.app.questionnaire.data.QuestionnaireFilters
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import com.fieldrepository.app.questionnaire.portal.QuestionnairePortal
import com.fieldrepository.app.questionnaire.ui.LocalScreenMemory
import com.fieldrepository.app.questionnaire.ui.RecordingsList
import com.fieldrepository.app.questionnaire.ui.ScreenMemories
import com.fieldrepository.app.questionnaire.ui.ScreenMemory
import com.fieldrepository.app.questionnaire.ui.rememberKept
import com.fieldrepository.app.questionnaire.ui.rememberKeptValue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListRestoreUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val source = (0 until 100).map { "Row $it" }

    private fun page(query: String, cursor: String?): Pair<List<String>, String?> {
        val matching = source.filter { query.isBlank() || it.contains(query) }
        val from = cursor?.toInt() ?: 0
        val to = (from + 30).coerceAtMost(matching.size)
        return matching.subList(from, to) to (if (to < matching.size) to.toString() else null)
    }

    @Composable
    private fun PagedList(onOpen: (String) -> Unit) {
        var query by rememberKept("list.query") { "" }
        var kept by rememberKept("list.pages") { KeptPages<String>() }
        val listState = rememberKeptValue("list.scroll") { LazyListState() }
        LaunchedEffect(query) {
            val filters = QuestionnaireFilters().withQ(query)
            val (first, cursor) = page(query, null)
            kept = ListPaging.arrive(kept, filters, first, cursor) { it }
        }
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search") }, modifier = Modifier.testTag("list.search"))
            LazyColumn(Modifier.fillMaxWidth().height(400.dp).testTag("list.rows"), state = listState) {
                items(kept.rows, key = { it }) { row ->
                    Text(row, Modifier.fillMaxWidth().clickable { onOpen(row) }.padding(16.dp))
                }
                val cursor = kept.cursor
                if (cursor != null) item(key = "more") {
                    Button(onClick = { val (next, c) = page(query, cursor); kept = ListPaging.more(kept, next, c) { it } }) { Text("Load more") }
                }
            }
        }
    }

    @Test fun backFromARecordOnPageTwoLandsOnThatRowWithTheSearchKept() {
        val memories = ScreenMemories<String>()
        var screen by mutableStateOf("list")
        var restoring by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                val memory = key(screen) { remember { memories.arrive(screen, restoring) } }
                CompositionLocalProvider(LocalScreenMemory provides memory) {
                    if (screen == "list") PagedList(onOpen = { restoring = false; screen = "record:$it" })
                    else Text("Opened ${screen.removePrefix("record:")}")
                }
            }
        }
        compose.onNodeWithTag("list.search").performTextReplacement("Row")
        compose.waitForIdle()
        compose.onNodeWithTag("list.rows").performScrollToNode(hasText("Load more"))
        compose.onNodeWithText("Load more").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("list.rows").performScrollToNode(hasText("Row 45"))
        compose.onNodeWithText("Row 45").performClick()
        compose.onNodeWithText("Opened Row 45").assertIsDisplayed()

        restoring = true
        screen = "list"
        compose.waitForIdle()
        compose.onNodeWithText("Row 45").assertIsDisplayed()
        compose.onNodeWithTag("list.search").assertTextContains("Row")
        compose.onAllNodesWithText("Row 0").assertCountEquals(0)

        restoring = false
        screen = "elsewhere"
        compose.waitForIdle()
        screen = "list"
        compose.waitForIdle()
        compose.onNodeWithText("Row 0").assertIsDisplayed()
        compose.onAllNodesWithText("Row 45").assertCountEquals(0)
    }

    private val takes = listOf(
        """{"id":"restore-r1","subjects":[{"artisanId":"restore-a1"}],"chips":{"session":{"id":"restore-s1","title":"Restore interview one"},"artisans":[{"id":"restore-a1","name":"Giriraj"}]}}""",
        """{"id":"restore-r2","subjects":[{"artisanId":"restore-a2"}],"chips":{"session":{"id":"restore-s2","title":"Restore interview two"},"artisans":[{"id":"restore-a2","name":"Ravi"}]}}"""
    ).map { QuestionnairePortal.json.decodeFromString(QRecording.serializer(), it) }

    @Test fun theRecordingsListKeepsWhatTheReaderChangedAfterAChipOpenedIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val v2 = QuestionnaireV2.get(context)
        v2.offlineRows.keepRecordings(takes)
        v2.filterStore.save("restore-test", QuestionnaireFilters())
        val chip = QuestionnaireFilters().with("artisanId", listOf("restore-a1"), mapOf("restore-a1" to "Giriraj"))
        var memory by mutableStateOf(ScreenMemory())
        var showList by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalScreenMemory provides memory) {
                    if (showList) RecordingsList(v2, initial = chip, screen = "restore-test") else Text("A session")
                }
            }
        }
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Restore interview one").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Restore interview two").assertCountEquals(0)
        compose.onNodeWithTag("filter-pill").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Restore interview two").fetchSemanticsNodes().isNotEmpty() }

        showList = false
        compose.waitForIdle()
        showList = true
        compose.waitForIdle()
        compose.onNodeWithText("Restore interview two").assertIsDisplayed()

        showList = false
        compose.waitForIdle()
        memory = ScreenMemory()
        showList = true
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Restore interview two").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Restore interview one").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Restore interview one").assertExists()
    }
}
