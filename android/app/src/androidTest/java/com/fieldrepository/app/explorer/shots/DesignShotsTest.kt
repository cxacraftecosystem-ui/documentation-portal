package com.fieldrepository.app.explorer.shots

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
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
import com.fieldrepository.app.explorer.data.ExplorerAudio
import com.fieldrepository.app.explorer.data.ExplorerAudioState
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerCache
import com.fieldrepository.app.explorer.data.ExplorerLink
import com.fieldrepository.app.explorer.data.ExplorerMoreApi
import com.fieldrepository.app.explorer.data.ExplorerNetwork
import com.fieldrepository.app.explorer.data.ExplorerPlace
import com.fieldrepository.app.explorer.data.ExplorerSort
import com.fieldrepository.app.explorer.data.ExplorerSortMemory
import com.fieldrepository.app.explorer.portal.DesignTheme
import com.fieldrepository.app.explorer.portal.ExplorerPortal
import com.fieldrepository.app.explorer.ui.ExplorerAudioExpand
import com.fieldrepository.app.explorer.ui.ExplorerMediaContent
import com.fieldrepository.app.explorer.ui.ExplorerMediaLoader
import com.fieldrepository.app.explorer.ui.ExplorerScreen
import com.fieldrepository.app.explorer.ui.ExplorerShapes
import com.fieldrepository.app.explorer.ui.ExplorerSheetHandle
import com.fieldrepository.app.explorer.ui.ExplorerSortSheetBody
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
        ExplorerNetwork.override = null
        ExplorerAudio.stage(ExplorerAudioState())
        ExplorerSortMemory.clear(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    private fun shoot(board: String, variant: String? = null, widthDp: Int = 390, heightDp: Int = 844, density: Float = 2f, fontScale: Float = 1f, dark: Boolean = false, prepare: () -> Unit = {}, content: @Composable () -> Unit) {
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
        prepare()
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
        ExplorerMediaContent(entries, entries.indexOf(DesignBoards.audioEntry), 0.0, 48, onSave = {}, onDismiss = {}, loader = loader, find = "lime")
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
        ExplorerMediaContent(entries, entries.indexOf(DesignBoards.videoEntry), 0.0, 48, onSave = {}, onDismiss = {}, loader = loader, find = "indigo")
    }

    @Composable
    private fun expandedFromRecord() {
        val source = DesignBoards.audioEntry
        val entry = ExplorerAudioExpand.entry(source.mediaId!!, source.name, source.sizeBytes)
        ExplorerMediaContent(listOf(entry), 0, 0.0, 1, onSave = {}, onDismiss = {}, loader = loader, find = "lime", knownDurationMs = DesignBoards.AUDIO_MS, positioned = false)
    }

    @Test fun androidAudioExpand() {
        stageAudio()
        shoot("AndroidAudio", "expand") { expandedFromRecord() }
    }

    @Test fun androidAudioExpandTablet() {
        stageAudio()
        shoot("AndroidAudio", "expand-tablet", widthDp = 1280, heightDp = 800, density = 1f) { expandedFromRecord() }
    }

    @Test fun androidPreview() = shoot("AndroidPreview") { videoPreview() }

    @Test fun androidPreviewTablet() = shoot("AndroidPreview", "tablet", widthDp = 1280, heightDp = 800, density = 1f) { videoPreview() }

    @Test fun androidPreviewDark() = shoot("AndroidPreview", "dark", dark = true) { videoPreview() }

    @Test fun androidAudioPlayerMini() {
        stageAudio()
        shoot("AndroidAudioPlayer") { miniOverList() }
    }

    private fun sortedFolder(variant: String?, tablet: Boolean) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        ExplorerNetwork.override = ExplorerLink(online = true, metered = false)
        ExplorerSortMemory.clear(app)
        ExplorerCache(File(app.filesDir, ExplorerCache.DIR), ExplorerPortal.userId(app)).clear()
        ExplorerPlace.write(app, ExplorerPortal.userId(app), ScreenBoards.GIRIRAJ)
        val api = ScreenBoards.Api(app, sorted = true)
        val screens = ScreenBoards.More()
        shoot(
            "AndroidSort", variant,
            widthDp = if (tablet) 1280 else 390, heightDp = if (tablet) 800 else 844, density = if (tablet) 1f else 2f,
            prepare = {
                compose.waitUntil(10_000) { api.calls.any { it == "tree:${ScreenBoards.GIRIRAJ}" } }
                compose.waitForIdle()
                compose.waitUntil(10_000) {
                    runCatching { compose.onNodeWithTag("explorer.tree").performScrollToNode(hasTestTag("explorer.sort")) }.isSuccess
                }
            },
        ) {
            ExplorerScreen(onBack = {}, api = api, more = screens, startPath = ScreenBoards.GIRIRAJ, onRecordNow = { _, _ -> }, onOpenSection = { _, _ -> })
        }
    }

    @Composable
    private fun sortSheet(tablet: Boolean) {
        Box(Modifier.fillMaxSize().background(Color(0x801E1B2E)), contentAlignment = Alignment.BottomCenter) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = ExplorerShapes.sheet,
                modifier = Modifier.let { if (tablet) it.widthIn(max = 640.dp) else it }.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    ExplorerSheetHandle()
                    ExplorerSortSheetBody(current = ExplorerSort("recorded", ExplorerSort.ASC), keys = ScreenBoards.SORTS, onPick = {}, onDone = {})
                }
            }
        }
    }

    @Test fun androidSort() = sortedFolder(null, tablet = false)

    @Test fun androidSortTablet() = sortedFolder("tablet", tablet = true)

    @Test fun androidSortSheet() = shoot("AndroidSort", "sheet") { sortSheet(tablet = false) }

    @Test fun androidSortSheetTablet() = shoot("AndroidSort", "sheet-tablet", widthDp = 1280, heightDp = 800, density = 1f) { sortSheet(tablet = true) }

    @Test fun androidSortSheetDark() = shoot("AndroidSort", "sheet-dark", dark = true) { sortSheet(tablet = false) }

    @Composable
    private fun miniOverList() {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f))
            MiniPlayer(onOpen = {})
        }
    }
}
