package com.fieldrepository.app.questionnaire

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import com.fieldrepository.app.questionnaire.data.StepKind
import com.fieldrepository.app.questionnaire.data.outboxJson
import com.fieldrepository.app.questionnaire.ui.LocalSessionLocationCard
import com.fieldrepository.app.questionnaire.ui.SessionLocationCard
import com.fieldrepository.app.questionnaire.ui.SessionStartSheet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun sheet(v2: QuestionnaireV2, onStarted: (String) -> Unit, consentOf: (suspend (String) -> String?)? = null) {
        val lookup: suspend (String) -> String? = consentOf ?: { id -> v2.consentState(id) }
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
                        consentOf = lookup,
                        onDismiss = {}, onStarted = onStarted, onJoined = {}, onError = {}
                    )
                }
            }
        }
    }

    private fun pending(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun waitForLateSlots() {
        compose.waitUntil(15_000) { pending("q-coverage-pending") == 0 && pending("q-provenance-pending") == 0 }
    }

    private fun settled() = compose.onAllNodesWithTag("q-start-settled").fetchSemanticsNodes().isNotEmpty()

    private fun questionSelected() = compose.onAllNodes(hasText("Question") and isSelected()).fetchSemanticsNodes().isNotEmpty()

    private fun tapIgnoredWhileSettling() {
        assertTrue(compose.onAllNodesWithTag("q-start-settling").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Question").performClick()
        compose.waitForIdle()
        assertFalse(questionSelected())
    }

    private fun startAndRead(v2: QuestionnaireV2, started: () -> String?): QSessionCreate {
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Start session") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Start session").performScrollTo().performClick()
        compose.waitUntil(15_000) { started() != null }
        val unit = runBlocking { v2.outbox.unit(started()!!) }!!
        return outboxJson.decodeFromString(QSessionCreate.serializer(), unit.steps.first { it.kind == StepKind.SESSION }.bodyJson!!)
    }

    @QFeature("Q1")
    @Test fun theStartSheetFillsTheCapturedAtCardAndBothCraftsAndSendsThem() {
        val v2 = QuestionnaireV2.get(InstrumentationRegistry.getInstrumentation().targetContext)
        var started: String? = null
        sheet(v2, onStarted = { started = it })
        try {
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Captured at 26.8105, 75.5466")).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Dokra", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            waitForLateSlots()
            compose.onNodeWithTag("q-crafts").performScrollTo()
            compose.onNodeWithText("Hand Block Printing", substring = true).assertExists()
            compose.onNodeWithText("From Giriraj and Meena's record").assertExists()
            compose.onNodeWithText("From this workshop").assertExists()
            compose.onNodeWithTag("q-record-by").performScrollTo()
            compose.onNodeWithText("Record by").assertExists()
            compose.waitUntil(30_000) { settled() }
            compose.onNodeWithText("Question").performClick()
            compose.waitUntil(5_000) { questionSelected() }
            val body = startAndRead(v2) { started }
            assertEquals(here, body.location)
            assertEquals(listOf("Hand Block Printing", "Dokra"), body.crafts)
            assertTrue(body.craftsByHand.orEmpty().isEmpty())
            assertEquals("QUESTION", body.recordBy)
        } finally {
            started?.let { runBlocking { v2.outbox.forget(it) } }
        }
    }

    @QFeature("Q1")
    @Test fun recordByStaysUnderTheTapWhenConsentArrivesLateAndStartWaitsForIt() {
        val v2 = QuestionnaireV2.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val consentArrives = CompletableDeferred<Unit>()
        var started: String? = null
        sheet(v2, onStarted = { started = it }, consentOf = { consentArrives.await(); "NOT_RECORDED" })
        try {
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Dokra", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            waitForLateSlots()
            assertEquals(2, pending("q-consent-pending"))
            compose.onNodeWithTag("q-record-by").performScrollTo()
            compose.onNodeWithText("Start session").assertIsNotEnabled()
            tapIgnoredWhileSettling()
            val before = compose.onNodeWithText("Question").fetchSemanticsNode().boundsInRoot
            consentArrives.complete(Unit)
            compose.waitUntil(10_000) { pending("q-consent-pending") == 0 }
            compose.waitUntil(10_000) { compose.onAllNodes(hasText(QuestionnaireCopy.consentNotice("Giriraj"))).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            assertEquals(before, compose.onNodeWithText("Question").fetchSemanticsNode().boundsInRoot)
            compose.waitUntil(5_000) { settled() }
            compose.onNodeWithText("Question").performClick()
            compose.waitUntil(5_000) { questionSelected() }
            assertEquals("QUESTION", startAndRead(v2) { started }.recordBy)
        } finally {
            started?.let { runBlocking { v2.outbox.forget(it) } }
        }
    }

    @QFeature("Q1")
    @Test fun aTapOnRecordByWhileConsentLoadsIsIgnoredAndTheTapAfterTheGrantedPromptsCollapseSelectsQuestion() {
        val v2 = QuestionnaireV2.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val consentArrives = CompletableDeferred<Unit>()
        var started: String? = null
        sheet(v2, onStarted = { started = it }, consentOf = { consentArrives.await(); "GRANTED" })
        try {
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Dokra", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            waitForLateSlots()
            assertEquals(2, pending("q-consent-pending"))
            compose.onNodeWithTag("q-record-by").performScrollTo()
            compose.onNodeWithText("Start session").assertIsNotEnabled()
            tapIgnoredWhileSettling()
            consentArrives.complete(Unit)
            compose.waitUntil(10_000) { pending("q-consent-pending") == 0 }
            assertTrue(compose.onAllNodes(hasText(QuestionnaireCopy.consentNotice("Giriraj"))).fetchSemanticsNodes().isEmpty())
            compose.waitUntil(5_000) { settled() }
            assertFalse(questionSelected())
            compose.onNodeWithTag("q-record-by").performScrollTo()
            compose.onNodeWithText("Question").performClick()
            compose.waitUntil(5_000) { questionSelected() }
            assertEquals("QUESTION", startAndRead(v2) { started }.recordBy)
        } finally {
            started?.let { runBlocking { v2.outbox.forget(it) } }
        }
    }
}
