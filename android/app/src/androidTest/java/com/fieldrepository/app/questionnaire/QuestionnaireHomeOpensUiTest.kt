package com.fieldrepository.app.questionnaire

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.questionnaire.data.QArtisanOption
import com.fieldrepository.app.questionnaire.data.QRespondentInput
import com.fieldrepository.app.questionnaire.data.QSessionCreate
import com.fieldrepository.app.questionnaire.data.QSessionLocation
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import com.fieldrepository.app.questionnaire.ui.QuestionnaireHome
import com.fieldrepository.app.questionnaire.ui.shouldAskBattery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuestionnaireHomeOpensUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun prefs() = context.getSharedPreferences("questionnaire-v2", Context.MODE_PRIVATE)

    private fun shown(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(hasText(text, substring = substring), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun theSessionsHomeOpensOnAFreshInstallAndNotNowClosesTheBatteryPrompt() {
        prefs().edit().remove("batteryAsked").commit()
        val v2 = QuestionnaireV2.get(context)
        val key = "home-opens-${System.nanoTime()}"
        runBlocking {
            v2.outbox.startSession(
                QSessionCreate(
                    clientKey = key, questionnaireId = "q_ctw4", sessionDate = "2026-11-09", startedAt = "2026-11-09T05:30:00Z",
                    place = "Bagru, Jaipur", note = "Fourth workshop sitting",
                    location = QSessionLocation(latitude = 26.8105, longitude = 75.5466, placeName = "Bagru, Jaipur, Rajasthan"),
                    respondents = listOf(QRespondentInput(name = "Visitor"))
                ),
                ownerUserId = null, guestIds = listOf("R:local-$key")
            )
        }
        val asks = shouldAskBattery(context)
        try {
            compose.setContent {
                MaterialTheme {
                    QuestionnaireHome(
                        artisans = listOf(QArtisanOption("a1", "Giriraj Prasad Chhipa", "Bagru")),
                        meId = null, meName = "Nandini",
                        formsTab = { Text("Forms list") },
                        onNewForm = {}, onUpdateApp = {}, onError = {}
                    )
                }
            }
            if (asks) {
                compose.waitUntil(20_000) { shown("Not now") }
                compose.onNodeWithText("Keep recording in the background").assertExists()
                compose.onNodeWithText("Not now").performClick()
                compose.waitUntil(10_000) { !shown("Not now") }
                assertTrue("Not now remembers the answer", prefs().getBoolean("batteryAsked", false))
            }
            compose.waitUntil(20_000) { shown("Fourth workshop sitting", substring = true) || shown("Bagru", substring = true) }
            compose.onNodeWithText("New session", useUnmergedTree = true).assertIsDisplayed().performClick()
            compose.waitUntil(20_000) { shown("Start session") }
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("q-place"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        } finally {
            runBlocking { v2.outbox.forget(key) }
        }
    }
}
