package com.fieldrepository.app.transcript

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.transcript.data.CopyTrack
import com.fieldrepository.app.transcript.data.PipelineStatus
import com.fieldrepository.app.transcript.data.Transcript
import com.fieldrepository.app.transcript.data.TranscriptAudio
import com.fieldrepository.app.transcript.data.TranscriptBadge
import com.fieldrepository.app.transcript.data.TranscriptFormat
import com.fieldrepository.app.transcript.data.TranscriptLanguage
import com.fieldrepository.app.transcript.data.TranscriptPermissions
import com.fieldrepository.app.transcript.data.TranscriptSpeaker
import com.fieldrepository.app.transcript.data.TranscriptStrings
import com.fieldrepository.app.transcript.data.TranscriptTurn
import com.fieldrepository.app.transcript.data.TranscriptWord
import com.fieldrepository.app.transcript.data.TurnTranslation
import com.fieldrepository.app.transcript.ui.ManualPlayback
import com.fieldrepository.app.transcript.ui.TranscriptHost
import com.fieldrepository.app.transcript.ui.TranscriptViewer
import com.fieldrepository.app.transcript.ui.copyToClipboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The viewer drawn on a device: tabs, the copy menus (into the real ClipboardManager), select →
 * copy selected, and karaoke following a player the test moves by hand. The transcript is the
 * contract's example (`shared/transcript-contract.json`), written out here because an instrumented
 * test cannot read the repository.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptViewerUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val view = Transcript(
        mediaId = "cm_media_1",
        title = "Interview · Giriraj Prasad Chhipa · 24 Jun 2026",
        status = PipelineStatus.READY,
        version = 1,
        languages = listOf(TranscriptLanguage("hi", "Hindi"), TranscriptLanguage("en", "English")),
        durationMs = 1_524_000,
        speakerCount = 2,
        badges = listOf(TranscriptBadge("stt", "elevenlabs", "scribe_v2", "Scribe v2"), TranscriptBadge("translation", "gemini", "gemini-3.8-flash", "Gemini 3.8 Flash")),
        speakers = listOf(
            TranscriptSpeaker("speaker_0", "Nandini (researcher)", "Nandini", "RESEARCHER", "u_1"),
            TranscriptSpeaker("speaker_1", "Giriraj", "Giriraj", "ARTISAN", "a_1"),
        ),
        turns = listOf(
            TranscriptTurn(
                id = "turn_a", index = 0, speakerKey = "speaker_0", speakerName = "Nandini (researcher)", startMs = 4000, endMs = 7600, language = "hi",
                text = "आप ये रंग कैसे बनाते हैं?",
                words = listOf(
                    TranscriptWord("आप", 4000, 4300, 0), TranscriptWord("ये", 4350, 4600, 3), TranscriptWord("रंग", 4650, 5100, 6),
                    TranscriptWord("कैसे", 5150, 5700, 10), TranscriptWord("बनाते", 5750, 6400, 15), TranscriptWord("हैं?", 6450, 7600, 21),
                ),
                translation = TurnTranslation("How do you make these colours?", "TRANSLATED", "MACHINE", "gemini-3.8-flash"),
                revision = 1,
            ),
            TranscriptTurn(
                id = "turn_b", index = 1, speakerKey = "speaker_1", speakerName = "Giriraj", startMs = 9000, endMs = 15200, language = "hi",
                text = "हम dabu के लिए मिट्टी और गोंद मिलाते हैं", lowConfidence = listOf(listOf(3, 7)),
                translation = TurnTranslation("We mix clay and gum for dabu (mud-resist printing).", "TRANSLATED", "MACHINE", "gemini-3.8-flash"),
                revision = 1,
            ),
        ),
        audio = TranscriptAudio(null, 1_524_000),
        permissions = TranscriptPermissions(edit = true, retry = true, ask = true, quoteCard = true, mapSpeakers = true),
        revision = 3,
    )

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class RecordingHost(private val context: Context) : TranscriptHost() {
        val copies = mutableListOf<String>()
        override fun copy(text: String) {
            copies += text
            copyToClipboard(context, text)
        }
    }

    private fun clipboardText(): String {
        var text = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            text = manager.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        }
        return text
    }

    private fun show(playback: ManualPlayback = ManualPlayback(available = true, duration = 1524.0)): RecordingHost {
        val host = RecordingHost(context)
        compose.setContent { MaterialTheme { TranscriptViewer(view, playback, host) } }
        return host
    }

    @Test fun headerShowsTheBadges() {
        show()
        compose.onNodeWithText("Interview · Giriraj Prasad Chhipa · 24 Jun 2026").assertIsDisplayed()
        compose.onNodeWithText("Scribe v2").assertIsDisplayed()
        compose.onNodeWithText("Gemini 3.8 Flash").assertIsDisplayed()
        compose.onNodeWithText("Hindi, English").assertIsDisplayed()
        compose.onNodeWithText("25:24").assertIsDisplayed()
        compose.onNodeWithTag("transcript.status").assertIsDisplayed()
    }

    @Test fun tabsSwitchBetweenOriginalEnglishAndSideBySide() {
        show()
        compose.onNodeWithText("आप ये रंग कैसे बनाते हैं?").assertIsDisplayed()
        compose.onNodeWithText("How do you make these colours?").assertDoesNotExist()
        compose.onNodeWithTag("transcript.tab.english").performClick()
        compose.onNodeWithText("How do you make these colours?").assertIsDisplayed()
        compose.onNodeWithText("आप ये रंग कैसे बनाते हैं?").assertDoesNotExist()
        compose.onNodeWithTag("transcript.tab.side_by_side").performClick()
        compose.onNodeWithText("आप ये रंग कैसे बनाते हैं?").assertIsDisplayed()
        compose.onNodeWithText("How do you make these colours?").assertIsDisplayed()
    }

    @Test fun copyAllBothPutsTheContractTextOnTheClipboardAndSaysCopied() {
        val host = show()
        compose.onNodeWithTag("transcript.copyAll").performClick()
        compose.onNodeWithTag("transcript.copyAll.both").performClick()
        compose.onNodeWithText(TranscriptStrings.copied).assertIsDisplayed()
        val expected = TranscriptFormat.all(view, CopyTrack.BOTH)
        assertEquals(listOf(expected), host.copies)
        assertEquals(expected, clipboardText())
        assertTrue(expected.contains("\n> How do you make these colours?"))
    }

    @Test fun aTurnsCopyMenuCopiesItsEnglish() {
        val host = show()
        compose.onNodeWithTag("turn.copy.turn_a").performClick()
        compose.onNodeWithText(TranscriptStrings.copyEnglish).performClick()
        assertEquals(listOf("How do you make these colours?"), host.copies)
        assertEquals("How do you make these colours?", clipboardText())
    }

    @Test fun selectTurnsThenCopySelected() {
        val host = show()
        compose.onNodeWithTag("transcript.select").performClick()
        compose.onNodeWithTag("turn.select.turn_b").performClick()
        compose.onNodeWithText(TranscriptStrings.selectedCount(1)).assertIsDisplayed()
        compose.onNodeWithTag("transcript.copySelected").performClick()
        compose.onNodeWithTag("transcript.copySelected.both").performClick()
        assertEquals(
            listOf("**Giriraj** · `00:00:09`\nहम dabu के लिए मिट्टी और गोंद मिलाते हैं\n> We mix clay and gum for dabu (mud-resist printing)."),
            host.copies
        )
    }

    @Test fun karaokeLightsTheWordBeingSaidAndATapSeeks() {
        val playback = ManualPlayback(available = true, duration = 1524.0)
        show(playback)
        playback.seek(4.7)
        compose.waitForIdle()
        compose.onNodeWithTag("turn.text.turn_a").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "रंग"))
        playback.seek(5.2)
        compose.waitForIdle()
        compose.onNodeWithTag("turn.text.turn_a").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "कैसे"))
        // The timestamp plays from the turn's start.
        compose.onNodeWithTag("turn.time.turn_b").performClick()
        assertEquals(9.0, playback.positionSeconds, 0.001)
        // A tap at the very start of the first turn's text is on its first word.
        compose.onNodeWithTag("turn.text.turn_a").performTouchInput { click(androidx.compose.ui.geometry.Offset(4f, centerY)) }
        assertEquals(4.0, playback.positionSeconds, 0.001)
        assertTrue(playback.playing)
    }
}
