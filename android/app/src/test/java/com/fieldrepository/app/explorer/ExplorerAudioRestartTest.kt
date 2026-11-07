package com.fieldrepository.app.explorer

import android.app.Service
import com.fieldrepository.app.explorer.data.EXPLORER_AUDIO_START_MODE
import com.fieldrepository.app.explorer.data.ExplorerAudioService
import com.fieldrepository.app.explorer.data.explorerAudioStopsOnStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExplorerAudioRestartTest {

    @Test
    fun `the system does not bring the audio service back after the process dies`() {
        assertEquals(Service.START_NOT_STICKY, EXPLORER_AUDIO_START_MODE)
    }

    @Test
    fun `a restart with nothing loaded stops at once`() {
        assertTrue(explorerAudioStopsOnStart(redelivered = true, loaded = false))
    }

    @Test
    fun `a start that carries an intent or a loaded player keeps running`() {
        assertFalse(explorerAudioStopsOnStart(redelivered = false, loaded = false))
        assertFalse(explorerAudioStopsOnStart(redelivered = false, loaded = true))
        assertFalse(explorerAudioStopsOnStart(redelivered = true, loaded = true))
    }

    @Test
    fun `the service answers every start with the non-sticky mode`() {
        val service = sourceOf("explorer/data/ExplorerAudio.kt").readText().replace("\r\n", "\n").substringAfter("class ExplorerAudioService")
        val start = service.substringAfter("override fun onStartCommand(", missingDelimiterValue = "")
        assertTrue("ExplorerAudioService does not override onStartCommand", start.isNotEmpty())
        val body = start.substringBefore("\n    }\n")
        assertTrue("onStartCommand does not return the non-sticky mode", body.contains("return EXPLORER_AUDIO_START_MODE"))
        assertFalse("onStartCommand hands back the sticky default", body.contains("return super.onStartCommand"))
    }

    private fun sourceOf(relative: String): File {
        val pkg = ExplorerAudioService::class.java.packageName.removeSuffix(".explorer.data").replace('.', '/')
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (path in listOf("src/main/java", "app/src/main/java", "android/app/src/main/java")) {
                val candidate = File(dir, "$path/$pkg/$relative")
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        error("$relative not found from ${File(".").absolutePath}")
    }
}
