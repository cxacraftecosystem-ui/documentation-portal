package com.fieldrepository.app.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.questionnaire.data.QParticipant
import com.fieldrepository.app.questionnaire.data.QSession
import com.fieldrepository.app.questionnaire.data.QSessionCounts
import com.fieldrepository.app.questionnaire.ui.SessionRow
import com.fieldrepository.app.questionnaire.ui.StatusPill
import com.fieldrepository.app.questionnaire.ui.serverRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DarkContrastUiTest {
    @get:Rule
    val compose = createComposeRule()

    private var content = Color.Unspecified
    private var onBackground = Color.Unspecified

    @Composable
    private fun Probe() {
        content = LocalContentColor.current
        onBackground = MaterialTheme.colorScheme.onBackground
        Text("Plain text")
        StatusPill("All 3", selected = true) {}
        SessionRow(
            serverRow(
                QSession(
                    id = "s1", sessionDate = "2026-11-15", crafts = listOf("Stone carving"),
                    participants = listOf(QParticipant("a1", "Meera Devi")),
                    counts = QSessionCounts(answeredQuestions = 18, totalQuestions = 108)
                )
            )
        ) {}
    }

    @Test fun textWithoutAColourIsLightInkInDarkMode() {
        compose.setContent { FieldRepositoryTheme(darkTheme = true) { Probe() } }
        compose.onNodeWithText("Meera Devi").assertExists()
        compose.onNodeWithText("Stone carving · 15 Nov · 18 of 108").assertExists()
        compose.runOnIdle {
            assertEquals(onBackground, content)
            assertTrue("content colour ${content.luminance()} is light on the dark background", content.luminance() > 0.5f)
        }
    }

    @Test fun textWithoutAColourIsDarkInkInLightMode() {
        compose.setContent { FieldRepositoryTheme(darkTheme = false) { Probe() } }
        compose.runOnIdle {
            assertEquals(onBackground, content)
            assertTrue("content colour ${content.luminance()} is dark on the light background", content.luminance() < 0.5f)
        }
    }
}
