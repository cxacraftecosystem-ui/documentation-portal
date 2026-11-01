package com.fieldrepository.app.questionnaire

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.questionnaire.data.CoverageRecordings
import com.fieldrepository.app.questionnaire.data.QCoverageRecording
import com.fieldrepository.app.questionnaire.data.QGridCell
import com.fieldrepository.app.questionnaire.data.QUserRef
import com.fieldrepository.app.questionnaire.ui.CoverageDot
import com.fieldrepository.app.questionnaire.ui.CoverageName
import com.fieldrepository.app.questionnaire.ui.CoverageTakeChooser
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The coverage grid on a device (0.0.6): a truncated name lifts in full, enlarged, while held, and a
 * screen reader hears it whole; a dot with takes opens them (one directly, several via the chooser);
 * a dot with none takes no tap.
 */
@RunWith(AndroidJUnit4::class)
class CoverageDotUiTest {
    @get:Rule
    val compose = createComposeRule()

    @QFeature("E7")
    @Test fun holdingATruncatedNameShowsItInFull() {
        compose.setContent { MaterialTheme { Column(Modifier.padding(top = 80.dp)) { CoverageName("Giriraj Prasad Chhipa", 60.dp, tag = "name.a1") } } }
        compose.onNodeWithText("Giriraj").assertIsDisplayed()
        compose.onNodeWithContentDescription("Giriraj Prasad Chhipa").assertExists()
        compose.onNodeWithTag("name.a1.full").assertDoesNotExist()
        compose.onNodeWithTag("name.a1").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("name.a1.full").assertIsDisplayed()
        compose.onNodeWithText("Giriraj Prasad Chhipa").assertIsDisplayed()
        compose.onNodeWithTag("name.a1").performTouchInput { up() }
        compose.onNodeWithTag("name.a1.full").assertDoesNotExist()
    }

    @QFeature("E8")
    @Test fun aDotWithNoTakesTakesNoTap() {
        compose.setContent { MaterialTheme { CoverageDot("○", Color.Gray, 88.dp, "Giriraj, A Materials: none", onOpen = null, modifier = Modifier.testTag("dot")) } }
        compose.onNodeWithTag("dot").assertHasNoClickAction()
    }

    @QFeature("E8")
    @Test fun oneTakeOpensDirectlyAndSeveralOpenTheChooser() {
        val one = QGridCell(artisanId = "a1", takes = 1, recordings = listOf(QCoverageRecording("r1", "m1", "s1", "2026-07-07T05:12:00Z", 840_000, QUserRef("u1", "Nandini Rao"))))
        val two = QGridCell(
            artisanId = "a2", takes = 2,
            recordings = listOf(
                QCoverageRecording("r2", "m2", "s1", "2026-07-07T05:12:00Z", 840_000, QUserRef("u1", "Nandini Rao")),
                QCoverageRecording("r3", "m3", "s2", "2026-07-09T08:30:00Z", 300_000, QUserRef("u2", "Arjun Mehta")),
            )
        )
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                var choosing by remember { mutableStateOf<List<CoverageRecordings.Take>?>(null) }
                fun open(cell: QGridCell) {
                    when (val o = CoverageRecordings.opens(CoverageRecordings.ofCell(cell))) {
                        CoverageRecordings.Opens.Nothing -> Unit
                        is CoverageRecordings.Opens.One -> opened += o.take.mediaFileId
                        is CoverageRecordings.Opens.Choose -> choosing = o.takes
                    }
                }
                Column {
                    CoverageDot("●", Color.Black, 88.dp, "one", onOpen = { open(one) }, modifier = Modifier.testTag("dot.one"))
                    CoverageDot("●", Color.Black, 88.dp, "two", onOpen = { open(two) }, modifier = Modifier.testTag("dot.two"))
                }
                choosing?.let { takes -> CoverageTakeChooser(takes, onPick = { opened += it.mediaFileId; choosing = null }, onDismiss = { choosing = null }) }
            }
        }
        compose.onNodeWithTag("dot.one").assertHasClickAction().performClick()
        assertEquals(listOf("m1"), opened)
        compose.onNodeWithTag("dot.two").performClick()
        compose.onNodeWithTag("coverage.chooser").assertIsDisplayed()
        compose.onNodeWithText("2 recordings").assertIsDisplayed()
        compose.onNodeWithText("Arjun", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("coverage.take.r3").performClick()
        compose.waitForIdle()
        assertEquals(listOf("m1", "m3"), opened)
    }
}
