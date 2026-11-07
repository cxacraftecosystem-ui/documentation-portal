package com.fieldrepository.app.explorer

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.explorer.ui.ExplorerUiText
import com.fieldrepository.app.explorer.ui.VideoPlaybackError
import com.fieldrepository.app.transcript.ui.PlaybackErrors
import com.fieldrepository.app.transcript.ui.TranscriptPlayback
import com.fieldrepository.app.transcript.ui.rememberTranscriptPlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExplorerVideoErrorUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun aPlaybackErrorSaysSoAndOffersRetryOpenAndDownload() {
        val taps = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(260.dp)) {
                    VideoPlaybackError(
                        message = PlaybackErrors.DECODER,
                        onRetry = { taps += "retry" },
                        onOpenElsewhere = { taps += "open" },
                        onDownload = { taps += "download" },
                    )
                }
            }
        }
        compose.onNodeWithText(PlaybackErrors.DECODER).assertIsDisplayed()
        compose.onNodeWithText(ExplorerUiText.retry).performClick()
        compose.onNodeWithText(ExplorerUiText.openElsewhere).performClick()
        compose.onNodeWithText(ExplorerUiText.download).performClick()
        compose.waitForIdle()
        assertEquals(listOf("retry", "open", "download"), taps)
    }

    @Test fun aRealPlayerThatFailsReportsTheErrorInsteadOfStayingSilent() {
        var playback: TranscriptPlayback? = null
        compose.setContent {
            MaterialTheme {
                val p = rememberTranscriptPlayback(Uri.parse("file:///data/local/tmp/explorer-missing-video.mp4"))
                playback = p
                Text(p.error ?: "", modifier = Modifier.testTag("error"))
            }
        }
        compose.waitUntil(15_000) { playback?.error != null }
        val line = playback?.error
        assertTrue(line, line == PlaybackErrors.SOURCE || line == PlaybackErrors.OTHER)
        compose.runOnIdle { playback?.toggle() }
        compose.waitUntil(15_000) { playback?.error != null }
        compose.onNodeWithTag("error").assertIsDisplayed()
    }

    @Test fun decoderFailuresReadAsAFormatProblem() {
        assertEquals(PlaybackErrors.DECODER, PlaybackErrors.line(4001))
        assertEquals(PlaybackErrors.DECODER, PlaybackErrors.line(4003))
        assertEquals(PlaybackErrors.SOURCE, PlaybackErrors.line(2005))
        assertEquals(PlaybackErrors.OTHER, PlaybackErrors.line(1000))
    }
}
