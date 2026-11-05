package com.fieldrepository.app.questionnaire

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.ui.FocusedSection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SectionFocusUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun onlyTheAskedForSectionIsTinted() {
        compose.setContent {
            MaterialTheme {
                val focus = remember { BringIntoViewRequester() }
                Column {
                    FocusedSection("A", null) { Text("Pond clay") }
                    FocusedSection("C", focus) { Text(QuestionnaireCopy.s("record.sectionEmpty")) }
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag("questionnaire.record.focus").assertCountEquals(1)
        compose.onNodeWithText("Section C").assertExists()
        compose.onNodeWithText("Nothing answered in this section yet.").assertExists()
    }
}
