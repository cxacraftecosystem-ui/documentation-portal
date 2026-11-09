package com.fieldrepository.app.questionnaire

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.questionnaire.data.QArtisanOption
import com.fieldrepository.app.questionnaire.ui.QuestionnaireHome
import com.fieldrepository.app.questionnaire.ui.QuestionnaireTab
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HomeFabClearanceUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val reserve = 96.dp

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun capture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val root = File(context.getExternalFilesDir(null), "design-fidelity/AndroidHomeFab")
        root.mkdirs()
        File(root, "impl-android-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun home(tab: QuestionnaireTab, fontScale: Float = 1f) {
        context.getSharedPreferences("questionnaire-v2", Context.MODE_PRIVATE).edit().putBoolean("batteryAsked", true).commit()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        Column(Modifier.fillMaxSize().padding(bottom = reserve)) {
                            QuestionnaireHome(
                                artisans = listOf(QArtisanOption("a1", "Giriraj Prasad Chhipa", "Bagru")),
                                meId = null, meName = "Nandini",
                                formsTab = { Column { repeat(40) { Text("Form ${it + 1}", Modifier.padding(vertical = 12.dp)) } } },
                                onNewForm = {}, onUpdateApp = {}, onError = {},
                                initialTab = tab,
                                modifier = Modifier.fillMaxWidth().fillMaxHeight()
                            )
                        }
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(reserve - 8.dp).background(MaterialTheme.colorScheme.surfaceVariant).testTag("nav"))
                    }
                }
            }
        }
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("questionnaire.fab"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun assertClear(name: String) {
        compose.onNodeWithTag("questionnaire.fab").assertIsDisplayed()
        val fab = bounds("questionnaire.fab")
        val nav = bounds("nav")
        assertTrue("the FAB ($fab) ends above the bar ($nav)", fab.bottom <= nav.top)
        capture("$name-top")
        compose.onNodeWithTag("questionnaire.listEnd", useUnmergedTree = true).performScrollTo()
        compose.waitForIdle()
        val end = bounds("questionnaire.listEnd")
        val after = bounds("questionnaire.fab")
        assertTrue("the last row ends (${end.top}) above the FAB (${after.top})", end.top <= after.top)
        assertTrue("the list's tail (${end.height}) is taller than the FAB (${after.height})", end.height >= after.height)
        capture("$name-end")
    }

    @Test fun newSessionClearsTheBarAndTheLastSession() {
        home(QuestionnaireTab.SESSIONS)
        assertClear("sessions")
    }

    @Test fun newFormClearsTheBarAndTheLastForm() {
        home(QuestionnaireTab.FORMS)
        assertClear("forms")
    }

    @Test fun newFormClearsTheBarAtLargeText() {
        home(QuestionnaireTab.FORMS, fontScale = 2f)
        assertClear("forms-font2")
    }
}
