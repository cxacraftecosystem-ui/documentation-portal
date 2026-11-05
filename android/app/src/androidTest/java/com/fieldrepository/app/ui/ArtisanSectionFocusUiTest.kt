package com.fieldrepository.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.data.ArtisanAnswerDto
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtisanSectionFocusUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val answers = listOf(
        ArtisanAnswerDto(responseId = "r1", questionId = "q1", prompt = "Which clay?", sectionCode = "B", sectionTitle = "Materials", answerText = "Pond clay"),
        ArtisanAnswerDto(responseId = "r2", questionId = "q2", prompt = "How long?", sectionCode = "C", sectionTitle = "Process", answerText = "Three days"),
    )

    @Test fun theSectionFromTheOverviewIsMarked() {
        compose.setContent { MaterialTheme { ArtisanQuestionnairePanel(answers = answers, loading = false, focusSection = "C") } }
        compose.waitForIdle()
        compose.onNodeWithTag("artisan.questionnaire.focus").assertExists()
        compose.onNodeWithText("C Process").assertExists()
    }

    @Test fun aSectionWithNoAnswersStillShowsWhenAskedFor() {
        compose.setContent { MaterialTheme { ArtisanQuestionnairePanel(answers = answers, loading = false, focusSection = "D") } }
        compose.waitForIdle()
        compose.onNodeWithTag("artisan.questionnaire.focus").assertExists()
        compose.onNodeWithText("Nothing answered in this section yet.").assertExists()
    }
}
