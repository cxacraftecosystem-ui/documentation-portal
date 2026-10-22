package com.fieldrepository.app.legacyrecording

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * H21 (audit 2026-10-21): the legacy capture form's take is finalised and attached on every exit,
 * never dropped, and runs under a microphone foreground service.
 */
class LegacyTakeTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeRecorder(private val failStop: Boolean = false) : LegacyTake.Recorder {
        val calls = mutableListOf<String>()
        override fun pause() { calls += "pause" }
        override fun resume() { calls += "resume" }
        override fun stop() { calls += "stop"; if (failStop) throw IllegalStateException("no audio") }
        override fun release() { calls += "release" }
    }

    private fun take(name: String): File = File(tmp.root, name).apply { writeBytes(ByteArray(2048)) }

    @After fun clear() = LegacyTake.reset()

    @Test
    fun `collapsing mid-take stops, releases and hands back the file`() {
        val rec = FakeRecorder()
        val file = take("a.m4a")
        LegacyTake.begin(rec, file)
        assertTrue(LegacyTake.active)
        assertEquals(file, LegacyTake.finalise())
        assertEquals(listOf("stop", "release"), rec.calls)
        assertFalse(LegacyTake.active)
        assertNull(LegacyTake.finalise())
    }

    @Test
    fun `a take the service finalised is claimed by the form afterwards`() {
        val rec = FakeRecorder()
        val file = take("b.m4a")
        LegacyTake.begin(rec, file)
        LegacyTake.finaliseForLater()
        assertFalse(LegacyTake.active)
        assertEquals(listOf("stop", "release"), rec.calls)
        assertEquals(file, LegacyTake.finalise())
        assertNull(LegacyTake.finalise())
    }

    @Test
    fun `a take with no audio is deleted, not attached`() {
        val file = take("c.m4a")
        LegacyTake.begin(FakeRecorder(failStop = true), file)
        assertNull(LegacyTake.finalise())
        assertFalse(file.exists())
    }

    @Test
    fun `starting again never overwrites a running take`() {
        val first = take("d.m4a")
        LegacyTake.begin(FakeRecorder(), first)
        LegacyTake.begin(FakeRecorder(), take("e.m4a"))
        assertEquals(File(tmp.root, "e.m4a"), LegacyTake.finalise())
        assertEquals(first, LegacyTake.finalise())
    }

    @Test
    fun `pause and resume reach the recorder`() {
        val rec = FakeRecorder()
        LegacyTake.begin(rec, take("f.m4a"))
        LegacyTake.pause()
        LegacyTake.resume()
        assertEquals(listOf("pause", "resume"), rec.calls)
    }

    @Test
    fun `the manifest runs the legacy recorder as a microphone foreground service`() {
        val manifest = File("src/main/AndroidManifest.xml").readText().replace("\r\n", "\n")
        assertTrue(
            manifest.contains(
                "android:name=\".recording.LegacyRecordingService\"\n            android:exported=\"false\"\n            android:foregroundServiceType=\"microphone\""
            )
        )
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE"))
    }

    @Test
    fun `the capture form's recorder finalises on dispose and starts the service`() {
        val source = File("src/main/java/com/fieldrepository/app/MainActivity.kt").readText()
        val recorder = source.substringAfter("private fun AudioClipRecorder(").substringBefore("Column(Modifier.fillMaxWidth()")
        assertTrue(recorder.contains("onDispose { runCatching { finaliseTake() } }"))
        assertTrue(recorder.contains("LegacyRecordingService.start(context)"))
        assertTrue(recorder.contains("if (file != null) addClip(uriForFile(context, file))"))
    }
}
