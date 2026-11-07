package com.fieldrepository.app.ui

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class AudioPlayerRecordingUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun wav(name: String, seconds: Int): Uri {
        val rate = 8000
        val samples = rate * seconds
        val data = samples * 2
        val out = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        out.put("data".toByteArray()).putInt(data)
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File(dir, name).apply { writeBytes(out.array()) }
        return Uri.fromFile(file)
    }

    private fun waitForText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun switchingRecordingsLoadsTheNewRecording() {
        val short = wav("player-short.wav", 1)
        val long = wav("player-long.wav", 3)
        var current by mutableStateOf(short)
        var expand by mutableStateOf(AudioExpand("rec-short", "Short"))
        compose.setContent {
            MaterialTheme { AudioPlayer(current, expand = expand) }
        }
        waitForText("0:00 / 0:01")
        compose.runOnIdle {
            current = long
            expand = AudioExpand("rec-long", "Long")
        }
        waitForText("0:00 / 0:03")
        assertEquals(0, compose.onAllNodesWithText("0:00 / 0:01").fetchSemanticsNodes().size)
    }

    @Test
    fun theRecordingKeyFollowsTheRecordingId() {
        val uri = Uri.parse("content://recordings/one")
        assertNotEquals(audioRecordingKey(uri, AudioExpand("a", "A")), audioRecordingKey(uri, AudioExpand("b", "B")))
        assertEquals(audioRecordingKey(uri, AudioExpand("a", "A")), audioRecordingKey(uri, AudioExpand("a", "Renamed")))
        assertEquals(uri.toString(), audioRecordingKey(uri, null))
    }
}
