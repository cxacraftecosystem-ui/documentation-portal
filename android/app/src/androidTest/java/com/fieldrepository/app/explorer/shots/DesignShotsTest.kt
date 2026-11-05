package com.fieldrepository.app.explorer.shots

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.fieldrepository.app.explorer.data.ExplorerAudio
import com.fieldrepository.app.explorer.data.ExplorerAudioState
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerMoreApi
import com.fieldrepository.app.explorer.portal.DesignTheme
import com.fieldrepository.app.explorer.ui.ExplorerMediaContent
import com.fieldrepository.app.explorer.ui.ExplorerMediaLoader
import com.fieldrepository.app.explorer.ui.MiniPlayer
import com.fieldrepository.app.explorer.ui.audioItem
import com.fieldrepository.app.explorer.ui.audioLines
import com.fieldrepository.app.transcript.data.Transcript
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DesignShotsTest {
    @get:Rule
    val compose = createComposeRule()

    private val more = DesignBoards.More()

    @Before fun fakes() {
        ExplorerMoreApi.override = more
    }

    @After fun reset() {
        ExplorerMoreApi.override = null
        ExplorerAudio.stage(ExplorerAudioState())
    }

    private fun shoot(board: String, variant: String? = null, widthDp: Int = 390, heightDp: Int = 844, density: Float = 2f, fontScale: Float = 1f, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            DesignTheme(dark) {
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                        Box(Modifier.requiredSize(widthDp.dp, heightDp.dp).background(MaterialTheme.colorScheme.background).testTag("design.frame")) {
                            content()
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        Thread.sleep(600)
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag("design.frame").captureToImage().asAndroidBitmap()
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "design-fidelity/$board")
        root.mkdirs()
        File(root, "impl-android${variant?.let { "-$it" } ?: ""}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val loader = object : ExplorerMediaLoader {
        override suspend fun source(entry: ExplorerEntry): String? = "https://example.invalid/${entry.mediaId}"
        override suspend fun transcript(mediaId: String): Transcript? = when (mediaId) {
            DesignBoards.AUDIO_ID -> DesignBoards.audioTranscript()
            DesignBoards.VIDEO_ID -> DesignBoards.videoTranscript()
            else -> null
        }
    }

    private fun stageAudio() {
        val entry = DesignBoards.audioEntry
        val lines = audioLines(DesignBoards.audioTranscript())
        val item = audioItem(entry, "https://example.invalid/${entry.mediaId}", lines)!!
        ExplorerAudio.stage(
            ExplorerAudioState(item = item, playing = true, positionMs = 1_100_000, durationMs = DesignBoards.AUDIO_MS, speed = 1.25f, line = lines.lastOrNull { it.startMs <= 1_100_000 }),
        )
    }

    @Composable
    private fun audioPreview() {
        val entries = DesignBoards.files()
        ExplorerMediaContent(entries, entries.indexOf(DesignBoards.audioEntry), 0.0, 48, onSave = {}, onDismiss = {}, loader = loader)
    }

    @Test fun androidAudio() {
        stageAudio()
        shoot("AndroidAudio") { audioPreview() }
    }

    @Test fun androidAudioDark() {
        stageAudio()
        shoot("AndroidAudio", "dark", dark = true) { audioPreview() }
    }

    @Test fun androidAudioLargeText() {
        stageAudio()
        shoot("AndroidAudio", "font2", fontScale = 2f) { audioPreview() }
    }

    @Test fun androidAudioTablet() {
        stageAudio()
        shoot("AndroidAudio", "tablet", widthDp = 1280, heightDp = 800, density = 1f) { audioPreview() }
    }

    @Composable
    private fun videoPreview() {
        val entries = DesignBoards.videoFiles()
        ExplorerMediaContent(entries, entries.indexOf(DesignBoards.videoEntry), 0.0, 48, onSave = {}, onDismiss = {}, loader = loader)
    }

    @Test fun androidPreview() = shoot("AndroidPreview") { videoPreview() }

    @Test fun androidPreviewDark() = shoot("AndroidPreview", "dark", dark = true) { videoPreview() }

    @Test fun androidAudioPlayerMini() {
        stageAudio()
        shoot("AndroidAudioPlayer") { miniOverList() }
    }

    @Test fun androidAudioNotification() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        runCatching { instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close() }
        compose.setContent { DesignTheme(false) { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) } }
        compose.waitForIdle()
        val wav = File(context.cacheDir, "design-board.wav")
        if (!wav.exists()) wav.writeBytes(DesignBoards.silentWav(DesignBoards.AUDIO_MS / 1000))
        val entry = DesignBoards.audioEntry
        val item = audioItem(entry, wav.toURI().toString(), audioLines(DesignBoards.audioTranscript()))!!
        instrumentation.runOnMainSync { ExplorerAudio.play(context, item, 1_100_000) }
        Thread.sleep(4_000)
        val device = UiDevice.getInstance(instrumentation)
        device.openNotification()
        Thread.sleep(2_500)
        val root = File(context.getExternalFilesDir(null), "design-fidelity/AndroidAudioPlayer")
        root.mkdirs()
        device.takeScreenshot(File(root, "impl-android-notification.png"))
        device.pressBack()
        instrumentation.runOnMainSync { ExplorerAudio.stop() }
    }

    @Composable
    private fun miniOverList() {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f))
            MiniPlayer(onOpen = {})
        }
    }
}
