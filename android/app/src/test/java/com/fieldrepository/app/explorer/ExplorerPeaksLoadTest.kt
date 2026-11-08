package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.data.ExplorerPeaks
import com.fieldrepository.app.explorer.ui.loadPeaks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ExplorerPeaksLoadTest {

    @Test
    fun `a failed waveform load falls back to no peaks`() = runBlocking {
        assertEquals(ExplorerPeaks(), loadPeaks { throw IOException("offline") })
    }

    @Test
    fun `a loaded waveform is returned`() = runBlocking {
        val peaks = ExplorerPeaks(listOf(0.1f, 0.9f), 2_000L, isolated = true)
        assertEquals(peaks, loadPeaks { peaks })
    }

    @Test
    fun `cancellation is rethrown, not swallowed`() {
        assertThrows(CancellationException::class.java) {
            runBlocking { loadPeaks { throw CancellationException("left the screen") } }
        }
    }
}
