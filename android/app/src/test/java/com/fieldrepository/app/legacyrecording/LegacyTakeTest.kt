package com.fieldrepository.app.legacyrecording

import com.fieldrepository.app.recording.MicBusyException
import com.fieldrepository.app.recording.MicLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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

    @After fun clear() {
        LegacyTake.reset()
        MicLock.reset()
    }

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
    fun `a new take stops the running one before its recorder is created`() {
        val events = mutableListOf<String>()
        val first = object : LegacyTake.Recorder {
            override fun pause() {}
            override fun resume() {}
            override fun stop() { events += "first stopped" }
            override fun release() { events += "first released" }
        }
        val a = Any()
        val b = Any()
        LegacyTake.start(a, take("g.m4a")) { events += "first created"; first }
        LegacyTake.start(b, take("h.m4a")) { events += "second created"; FakeRecorder() }
        assertEquals(listOf("first created", "first stopped", "first released", "second created"), events)
        assertTrue(LegacyTake.owns(b))
        assertFalse(LegacyTake.owns(a))
        assertEquals(b, LegacyTake.holder.value)
    }

    @Test
    fun `each owner gets its own take back after another took the microphone`() {
        val a = Any()
        val b = Any()
        LegacyTake.start(a, take("i.m4a")) { FakeRecorder() }
        LegacyTake.start(b, take("j.m4a")) { FakeRecorder() }
        assertEquals(File(tmp.root, "i.m4a"), LegacyTake.finalise(a))
        assertNull(LegacyTake.finalise(a))
        assertTrue(LegacyTake.active)
        assertEquals(File(tmp.root, "j.m4a"), LegacyTake.finalise(b))
        assertFalse(LegacyTake.active)
        assertFalse(MicLock.held)
    }

    @Test
    fun `two takes in a row both start and the microphone is free afterwards`() {
        val a = Any()
        repeat(2) { index ->
            LegacyTake.start(a, take("k$index.m4a")) { FakeRecorder() }
            assertTrue(MicLock.held)
            assertEquals(File(tmp.root, "k$index.m4a"), LegacyTake.finalise(a))
            assertFalse(MicLock.held)
        }
    }

    @Test
    fun `a session recording holds the microphone, so a legacy take refuses by name`() {
        assertTrue(MicLock.acquire(MicLock.SESSION))
        var created = false
        val error = runCatching { LegacyTake.start(Any(), take("l.m4a")) { created = true; FakeRecorder() } }.exceptionOrNull()
        assertTrue(error is MicBusyException)
        assertFalse(created)
        assertFalse(LegacyTake.active)
    }

    @Test
    fun `a recorder that fails to start leaves the microphone free`() {
        val error = runCatching { LegacyTake.start(Any(), take("m.m4a")) { throw IllegalStateException("start failed") } }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertFalse(MicLock.held)
        assertFalse(LegacyTake.active)
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
                "android:name=\".legacyrecording.LegacyRecordingService\"\n            android:exported=\"false\"\n            android:foregroundServiceType=\"microphone\""
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
