package com.fieldrepository.app.explorer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.explorer.data.ExplorerAudio
import com.fieldrepository.app.explorer.data.ExplorerAudioState
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.portal.DesignTheme
import com.fieldrepository.app.explorer.ui.ExplorerAudioExpand
import com.fieldrepository.app.explorer.ui.ExplorerMediaContent
import com.fieldrepository.app.explorer.ui.ExplorerMediaLoader
import com.fieldrepository.app.transcript.data.Transcript
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExplorerAudioExpandUiTest {
    @get:Rule
    val compose = createComposeRule()

    @After fun reset() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ExplorerAudio.stop()
            ExplorerAudio.stage(ExplorerAudioState())
        }
    }

    private val loader = object : ExplorerMediaLoader {
        override suspend fun source(entry: ExplorerEntry): String? = null
        override suspend fun transcript(mediaId: String): Transcript? = null
    }

    @Test
    fun theExpandedViewFromARecordShowsSizeLengthAndSave() {
        var saved = 0
        val entry = ExplorerAudioExpand.entry("aud-1", "Section-D_Materials.m4a", (14.2 * 1024 * 1024).toLong())
        compose.setContent {
            DesignTheme(false) {
                ExplorerMediaContent(listOf(entry), 0, 0.0, 1, onSave = { saved++ }, onDismiss = {}, loader = loader, knownDurationMs = 2_462_000L, positioned = false)
            }
        }
        compose.onNodeWithText("Audio · 14.2 MB · 41:02").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("1 of 1", substring = true).fetchSemanticsNodes().size)
        compose.onNodeWithTag("explorer.preview.save").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, saved) }
    }
}
