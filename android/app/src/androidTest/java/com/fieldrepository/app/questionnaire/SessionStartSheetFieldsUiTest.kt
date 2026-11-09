package com.fieldrepository.app.questionnaire

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.questionnaire.data.QArtisanOption
import com.fieldrepository.app.questionnaire.data.QFormOption
import com.fieldrepository.app.questionnaire.data.QSessionCreate
import com.fieldrepository.app.questionnaire.data.QSessionLocation
import com.fieldrepository.app.questionnaire.data.QWorkshopOption
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import com.fieldrepository.app.questionnaire.data.StepKind
import com.fieldrepository.app.questionnaire.data.outboxJson
import com.fieldrepository.app.questionnaire.ui.LocalSessionLocationCard
import com.fieldrepository.app.questionnaire.ui.SessionLocationCard
import com.fieldrepository.app.questionnaire.ui.SessionStartSheet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionStartSheetFieldsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val here = QSessionLocation(latitude = 26.8105, longitude = 75.5466, accuracy = 12.0, village = "Bagru", district = "Jaipur", state = "Rajasthan")

    private val card: SessionLocationCard = { value, editing, onChange ->
        Text(if (value == null) "Captured at: waiting" else "Captured at ${value.latitude}, ${value.longitude}", modifier = Modifier.testTag("fake.captured.$editing"))
        LaunchedEffect(Unit) { if (!editing && value == null) onChange(here) }
    }

    @Test fun theStartSheetFillsTheCapturedAtCardAndBothCraftsAndSendsThem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val v2 = QuestionnaireV2.get(context)
        var started: String? = null
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSessionLocationCard provides card) {
                    SessionStartSheet(
                        v2 = v2,
                        workshops = listOf(QWorkshopOption("w-fields", "Bagru workshop", place = "Bagru")),
                        forms = listOf(QFormOption("form-fields-test", "4th Craft Toolkit Workshop", isDefault = true)),
                        artisans = listOf(QArtisanOption("a1", "Giriraj", "Bagru", "Hand Block Printing"), QArtisanOption("a2", "Meena", "Bagru", "Dokra")),
                        meId = null, meName = "Nandini",
                        prefillArtisanIds = listOf("a1", "a2"),
                        defaultWorkshopId = "w-fields",
                        onDismiss = {}, onStarted = { started = it }, onJoined = {}, onError = {}
                    )
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Captured at 26.8105, 75.5466")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Dokra", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("q-crafts").performScrollTo()
        compose.onNodeWithText("Hand Block Printing", substring = true).assertExists()
        compose.onNodeWithText("Start session").performScrollTo().performClick()
        compose.waitUntil(15_000) { started != null }
        val unit = runBlocking { v2.outbox.unit(started!!) }!!
        val body = outboxJson.decodeFromString(QSessionCreate.serializer(), unit.steps.first { it.kind == StepKind.SESSION }.bodyJson!!)
        assertEquals(here, body.location)
        assertEquals(listOf("Hand Block Printing", "Dokra"), body.crafts)
        assertTrue(body.craftsByHand.orEmpty().isEmpty())
        runBlocking { v2.outbox.forget(started!!) }
    }
}
