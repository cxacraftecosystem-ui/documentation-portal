package com.fieldrepository.app.transcript

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.transcript.data.CopyTrack
import com.fieldrepository.app.transcript.data.PipelineStatus
import com.fieldrepository.app.transcript.data.Transcript
import com.fieldrepository.app.transcript.data.TranscriptFormat
import com.fieldrepository.app.transcript.data.TranscriptPermissions
import com.fieldrepository.app.transcript.data.TranscriptPreview
import com.fieldrepository.app.transcript.data.TranscriptTurn
import com.fieldrepository.app.transcript.data.TranscriptUiText
import com.fieldrepository.app.transcript.data.TurnTranslation
import com.fieldrepository.app.transcript.ui.ManualPlayback
import com.fieldrepository.app.transcript.ui.TranscriptHost
import com.fieldrepository.app.transcript.ui.TranscriptPreviewText
import com.fieldrepository.app.transcript.ui.TranscriptViewer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import com.fieldrepository.app.questionnaire.QFeature
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * 0.0.6 on a device: reading mode, the all-marks toggle, the mark bubble on touch-and-hold, marks
 * centred on the seek bar, auto-follow that a touch stops and "Follow along" resumes, and the list
 * preview with speakers as labels.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptReadingUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun clearMarks() {
        context.getSharedPreferences("transcript_marks", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun turn(i: Int, key: String, name: String, text: String, english: String?) = TranscriptTurn(
        id = "t$i", index = i, speakerKey = key, speakerName = name, startMs = i * 5_000L, endMs = i * 5_000L + 4_500, language = "hi", text = text,
        translation = TurnTranslation(english, if (english == null) "PENDING" else "TRANSLATED"),
    )

    private val short = Transcript(
        mediaId = "reading_1", title = "Interview · Giriraj · 24 Jun 2026", status = PipelineStatus.READY, speakerCount = 2,
        turns = listOf(
            turn(0, "speaker_0", "Nandini (researcher)", "आप ये रंग", "How do you"),
            turn(1, "speaker_0", "Nandini (researcher)", "कैसे बनाते हैं?", "make these colours?"),
            turn(2, "speaker_1", "Giriraj", "मिट्टी और गोंद", "Clay and gum."),
        ),
        permissions = TranscriptPermissions(edit = true),
    )

    private val long = short.copy(
        mediaId = "reading_2",
        turns = (0 until 40).map { i -> turn(i, "speaker_${i % 2}", if (i % 2 == 0) "Nandini" else "Giriraj", "Line $i of the conversation about block printing and dyes.", "English $i") },
    )

    private class Host : TranscriptHost() {
        val copies = mutableListOf<String>()
        val downloads = mutableListOf<Triple<String, CopyTrack, Boolean>>()
        override fun copy(text: String) { copies += text }
        override fun download(format: String, track: CopyTrack, timestamps: Boolean) { downloads += Triple(format, track, timestamps) }
    }

    private fun show(view: Transcript, playback: ManualPlayback = ManualPlayback(available = true, duration = 200.0)): Host {
        val host = Host()
        compose.setContent { MaterialTheme { TranscriptViewer(view, playback, host) } }
        return host
    }

    @Test fun readingModeIsOneFlowingTranscriptWithNoTimestamps() {
        val host = show(short)
        compose.onNodeWithTag("transcript.reading").performClick()
        // A speaker's run is one paragraph, under one label; no turn carries a timestamp.
        compose.onNodeWithTag("reading.t0").assertIsDisplayed()
        compose.onNodeWithTag("reading.t1").assertDoesNotExist()
        compose.onNodeWithTag("reading.t2").assertIsDisplayed()
        compose.onNodeWithText("आप ये रंग कैसे बनाते हैं?").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("turn.time.t0").fetchSemanticsNodes().size)
        // English, then Both (original and its English).
        compose.onNodeWithTag("transcript.tab.english").performClick()
        compose.onNodeWithText("How do you make these colours?").assertIsDisplayed()
        compose.onNodeWithTag("transcript.tab.side_by_side").performClick()
        compose.onNodeWithText(TranscriptUiText.both).assertIsDisplayed()
        compose.onNodeWithText("मिट्टी और गोंद").assertIsDisplayed()
        compose.onNodeWithText("Clay and gum.").assertIsDisplayed()
        // Copy and download are the view's own: no timestamps, the tab's track.
        compose.onNodeWithTag("transcript.reading.copy").performClick()
        assertEquals(TranscriptFormat.all(short, CopyTrack.BOTH, timestamps = false), host.copies.single())
        compose.onNodeWithTag("transcript.reading.download").performClick()
        compose.onNodeWithTag("transcript.reading.download.docx").performClick()
        assertEquals(Triple("docx", CopyTrack.BOTH, false), host.downloads.single())
        // And back to turns.
        compose.onNodeWithTag("transcript.reading").performClick()
        compose.onNodeWithTag("turn.time.t0").assertIsDisplayed()
    }

    @Test fun oneButtonTurnsEveryMarkOnAndOff() {
        show(short)
        assertEquals(0, compose.onAllNodesWithTag("timeline.mark.t0").fetchSemanticsNodes().size)
        compose.onNodeWithTag("transcript.marks.all").performClick()
        listOf("t0", "t1", "t2").forEach { compose.onNodeWithTag("timeline.mark.$it").assertExists() }
        compose.onNodeWithText(TranscriptUiText.clearMarks).assertIsDisplayed()
        compose.onNodeWithTag("transcript.marks.all").performClick()
        listOf("t0", "t1", "t2").forEach { compose.onNodeWithTag("timeline.mark.$it").assertDoesNotExist() }
        // The per-turn box still marks one.
        compose.onNodeWithTag("turn.mark.t2").performClick()
        compose.onNodeWithTag("timeline.mark.t2").assertExists()
    }

    @Test fun marksSitCentredOnTheSeekBar() {
        show(short)
        compose.onNodeWithTag("transcript.marks.all").performClick()
        val bar = compose.onNodeWithTag("transcript.seek").getBoundsInRoot()
        val barCentre = (bar.top + bar.bottom) / 2
        listOf("t0", "t1", "t2").forEach { id ->
            val mark = compose.onNodeWithTag("timeline.mark.$id").getBoundsInRoot()
            assertTrue("$id is centred", abs(((mark.top + mark.bottom) / 2 - barCentre).value) <= 1f)
            assertTrue("$id is on the bar", mark.left >= bar.left - 8.dp && mark.right <= bar.right + 8.dp)
        }
    }

    @Test fun holdingAMarkShowsWhatWasSaid() {
        show(short)
        compose.onNodeWithTag("transcript.marks.all").performClick()
        compose.onNodeWithTag("timeline.mark.t2").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("timeline.bubble").assertIsDisplayed()
        val inBubble = hasAnyAncestor(hasTestTag("timeline.bubble"))
        compose.onNode(hasText("Giriraj") and inBubble).assertIsDisplayed()
        compose.onNode(hasText("मिट्टी और गोंद") and inBubble).assertIsDisplayed()
        compose.onNodeWithTag("timeline.bubble.english").assertIsDisplayed()
        compose.onNodeWithTag("timeline.mark.t2").performTouchInput { up() }
        compose.onNodeWithTag("timeline.bubble").assertDoesNotExist()
    }

    @Test fun aTapOnAMarkPlaysFromIt() {
        val playback = ManualPlayback(available = true, duration = 200.0)
        show(short, playback)
        compose.onNodeWithTag("transcript.marks.all").performClick()
        compose.onNodeWithTag("timeline.mark.t2").performClick()
        assertEquals(10.0, playback.positionSeconds, 0.001)
    }

    @Test fun autoFollowStopsOnATouchAndFollowAlongResumesIt() {
        val playback = ManualPlayback(available = true, duration = 200.0)
        playback.playing = true
        show(long, playback)
        playback.positionSeconds = 180.0
        compose.waitForIdle()
        compose.onNodeWithTag("turn.t36").assertIsDisplayed()
        compose.onNodeWithTag("transcript.followAlong").assertDoesNotExist()
        // The reader drags the transcript: it stops following and offers to resume.
        compose.onNodeWithTag("transcript.list").performTouchInput { swipeDown() }
        compose.onNodeWithTag("transcript.followAlong").assertIsDisplayed()
        playback.positionSeconds = 20.0
        compose.waitForIdle()
        compose.onNodeWithTag("turn.t4").assertDoesNotExist()
        compose.onNodeWithTag("transcript.followAlong").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("turn.t4").assertIsDisplayed()
        compose.onNodeWithTag("transcript.followAlong").assertDoesNotExist()
        // A press held on the transcript stops it too.
        compose.onNodeWithTag("transcript.list").performTouchInput { down(center) }
        compose.onNodeWithTag("transcript.followAlong").assertIsDisplayed()
        compose.onNodeWithTag("transcript.list").performTouchInput { up() }
    }

    @Test fun autoFollowWorksInReadingModeToo() {
        val playback = ManualPlayback(available = true, duration = 200.0)
        show(long, playback)
        compose.onNodeWithTag("transcript.reading").performClick()
        compose.onNodeWithTag("transcript.list").performTouchInput { swipeUp() }
        playback.positionSeconds = 190.0
        // Pressing play follows again.
        compose.onNodeWithTag("transcript.play").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("reading.t38").assertIsDisplayed()
    }

    @QFeature("E9")
    @Test fun aListPreviewShowsSpeakersAsLabelsWithNoMarkdown() {
        val preview = TranscriptPreview.fromMarkdown("Transcript **Speaker 1:** Wow! This is **really** good.\n\n**Speaker 2:** Yes, indigo.\n**Speaker 1:** And then?\n**Speaker 2:** Later.")!!
        compose.setContent { MaterialTheme { TranscriptPreviewText(preview, maxLines = 3) } }
        compose.onNodeWithText("Speaker 1  Wow! This is really good.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("**", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Later.", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("transcript.preview").assertIsDisplayed()
    }
}
