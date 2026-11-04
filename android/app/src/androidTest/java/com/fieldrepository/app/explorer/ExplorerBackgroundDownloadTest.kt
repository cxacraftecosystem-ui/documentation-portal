package com.fieldrepository.app.explorer

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.fieldrepository.app.explorer.data.ExplorerDownloads
import com.fieldrepository.app.explorer.data.ExplorerItem
import com.fieldrepository.app.explorer.data.ExplorerJob
import com.fieldrepository.app.explorer.data.ExplorerJobKind
import com.fieldrepository.app.explorer.data.ExplorerJobState
import com.fieldrepository.app.explorer.data.ExplorerRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ExplorerBackgroundDownloadTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var server: SlowServer
    private val started = mutableListOf<String>()

    private class SlowServer(private val size: Int, private val bytesPerSecond: Int) : AutoCloseable {
        private val socket = ServerSocket(0)
        private val open = AtomicBoolean(true)
        val port: Int get() = socket.localPort

        init {
            thread(isDaemon = true, name = "explorer-slow-server") {
                while (open.get()) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { serve(client) }
                }
            }
        }

        private fun serve(client: Socket) {
            runCatching {
                client.use { c ->
                    val reader = c.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val out = c.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: $size\r\nConnection: close\r\n\r\n".toByteArray())
                    val chunk = ByteArray(bytesPerSecond / 10) { 7 }
                    var sent = 0
                    while (sent < size && open.get()) {
                        val n = minOf(chunk.size, size - sent)
                        out.write(chunk, 0, n)
                        out.flush()
                        sent += n
                        Thread.sleep(100)
                    }
                }
            }
        }

        override fun close() {
            open.set(false)
            runCatching { socket.close() }
        }
    }

    @Before fun setUp() {
        server = SlowServer(size = 1_600_000, bytesPerSecond = 200_000)
    }

    @After fun tearDown() {
        started.forEach { id ->
            ExplorerDownloads.cancel(context, id)
            ExplorerDownloads.store(context).get(id)?.result?.uri?.let { uri ->
                runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
            }
            ExplorerDownloads.dismiss(context, id)
        }
        server.close()
    }

    private fun request(name: String, zip: Boolean) = ExplorerRequest(
        title = name,
        fileName = if (zip) "$name.zip" else "$name.bin",
        kind = if (zip) ExplorerJobKind.FOLDER else ExplorerJobKind.FILE,
        single = !zip,
        items = listOf(ExplorerItem(path = "$name.bin", url = "http://127.0.0.1:${server.port}/$name.bin", sizeBytes = 1_600_000)),
        expectedBytes = 1_600_000,
    )

    private fun job(id: String): ExplorerJob? = ExplorerDownloads.store(context).get(id)

    private fun waitFor(timeoutMs: Long, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (check()) return true
            Thread.sleep(100)
        }
        return check()
    }

    private fun keepsGoingWhenLeft(zip: Boolean, leave: (ActivityScenario<ComponentActivity>) -> Unit) {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        val id = ExplorerDownloads.start(context, request(if (zip) "bg-zip" else "bg-file", zip))
        started += id
        assertTrue("the download never started", waitFor(30_000) { (job(id)?.doneBytes ?: 0) > 0 })
        leave(scenario)
        val before = job(id)!!.doneBytes
        Thread.sleep(3_000)
        val after = job(id)!!.doneBytes
        assertTrue("progress stopped in the background: $before -> $after", after > before)
        assertTrue("the download did not finish in the background", waitFor(60_000) { job(id)?.state == ExplorerJobState.DONE })
        assertEquals(1, job(id)!!.result!!.saved)
        scenario.close()
    }

    @QFeature("X10")
    @Test fun aZipKeepsDownloadingAfterHome() = keepsGoingWhenLeft(zip = true) { device.pressHome() }

    @QFeature("X10")
    @Test fun aFileKeepsDownloadingWhenTheTaskMovesToTheBack() = keepsGoingWhenLeft(zip = false) { scenario ->
        scenario.onActivity { it.moveTaskToBack(true) }
    }

    @Test fun cancelStopsTheDownloadAndLeavesNoPartialFile() {
        val id = ExplorerDownloads.start(context, request("bg-cancel", zip = true))
        started += id
        assertTrue(waitFor(30_000) { (job(id)?.doneBytes ?: 0) > 0 })
        ExplorerDownloads.cancel(context, id)
        assertTrue(waitFor(10_000) { job(id)?.state == ExplorerJobState.CANCELLED })
        Thread.sleep(1_000)
        assertTrue(File(ExplorerDownloads.workRoot(context), id).let { !it.exists() || it.listFiles().isNullOrEmpty() })
    }
}
