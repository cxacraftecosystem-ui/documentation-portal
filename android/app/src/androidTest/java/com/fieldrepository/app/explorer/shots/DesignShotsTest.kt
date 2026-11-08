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
import com.fieldrepository.app.explorer.ExplorerFiltersUiTest
import com.fieldrepository.app.explorer.data.ExplorerFacetKeys
import com.fieldrepository.app.explorer.data.ExplorerFilterKey
import com.fieldrepository.app.explorer.data.ExplorerFilters
import com.fieldrepository.app.explorer.ui.ExplorerMultiSelectPanel
import com.fieldrepository.app.explorer.ui.ExplorerPaneWidth
import com.fieldrepository.app.explorer.ui.ExplorerState
import com.fieldrepository.app.explorer.ui.ExplorerTab
import com.fieldrepository.app.explorer.ui.ExplorerTablesMemory
import com.fieldrepository.app.explorer.ui.rememberExplorerState
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

    private fun filtered(variant: String, tablet: Boolean, pane: Float? = null, setUp: (ExplorerState) -> Unit = {}, ready: () -> Unit = {}) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        ExplorerNetwork.override = ExplorerLink(online = true, metered = false)
        ExplorerCache(File(app.filesDir, ExplorerCache.DIR), ExplorerPortal.userId(app)).clear()
        ExplorerTablesMemory.clear()
        ExplorerPaneWidth.write(app, pane ?: ExplorerPaneWidth.DEFAULT)
        val api = ExplorerFiltersUiTest.Tree()
        val facets = ExplorerFiltersUiTest.Facets()
        var held: ExplorerState? = null
        shoot(
            "AndroidFilters", variant,
            widthDp = if (tablet) 1280 else 390, heightDp = if (tablet) 800 else 844, density = if (tablet) 1f else 2f,
            prepare = {
                compose.waitUntil(10_000) { api.calls.any { it.startsWith("tree:${ExplorerFiltersUiTest.ROOT}:") } }
                compose.waitForIdle()
                held?.let(setUp)
                compose.waitForIdle()
                Thread.sleep(400)
                compose.waitForIdle()
                ready()
            },
        ) {
            val state = rememberExplorerState(api)
            held = state
            ExplorerScreen(onBack = {}, api = api, more = more, filterApi = facets, startPath = ExplorerFiltersUiTest.ROOT, held = state, onOpenRemote = { _, _ -> })
        }
        ExplorerPaneWidth.write(app, ExplorerPaneWidth.DEFAULT)
    }

    private val craftFilter = ExplorerFilters().with(ExplorerFilterKey.CRAFT, listOf("c1"), mapOf("c1" to "Bamboo")).with(ExplorerFilterKey.MEDIA_TYPE, listOf("VIDEO", "IMAGE"), mapOf("VIDEO" to "Video", "IMAGE" to "Image"))

    private val designFilter = ExplorerFilters().with(ExplorerFilterKey.DESIGN_WORKSHOP, listOf("d1", "d2"), mapOf("d1" to "Kutch design week", "d2" to "Bagru studio"))

    private val otherPortal = ExplorerFilters().with(ExplorerFilterKey.PORTAL, listOf("this", "other"))

    private fun reach(tag: String) {
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("explorer.tree").performScrollToNode(hasTestTag(tag)) }.isSuccess }
    }

    @Test fun androidFiltersFolder() = filtered("folder", tablet = false, setUp = { it.changeFilters(craftFilter) })

    @Test fun androidFiltersFolderTablet() = filtered("folder-tablet", tablet = true, setUp = { it.changeFilters(craftFilter) })

    @Test fun androidFiltersDesignWorkshop() = filtered("design-workshop", tablet = false, setUp = { it.changeFilters(designFilter) })

    @Test fun androidFiltersDesignWorkshopTablet() = filtered("design-workshop-tablet", tablet = true, setUp = { it.changeFilters(designFilter) })

    @Test fun androidFiltersOtherPortal() = filtered("other-portal", tablet = false, setUp = { it.changeFilters(otherPortal) }, ready = { reach("explorer.peer") })

    @Test fun androidFiltersOtherPortalTablet() = filtered("other-portal-tablet", tablet = true, setUp = { it.changeFilters(otherPortal) }, ready = { reach("explorer.peer") })

    @Test fun androidFiltersPaneMin() = filtered("pane-min-tablet", tablet = true, pane = 0f, setUp = { it.changeFilters(craftFilter) })

    @Test fun androidFiltersPaneMax() = filtered("pane-max-tablet", tablet = true, pane = 5_000f, setUp = { it.changeFilters(craftFilter) })

    @Test fun androidFiltersPaneMaxSmallTablet() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        ExplorerNetwork.override = ExplorerLink(online = true, metered = false)
        ExplorerPaneWidth.write(app, 5_000f)
        val api = ExplorerFiltersUiTest.Tree()
        shoot("AndroidFilters", "pane-max-840", widthDp = 840, heightDp = 1180, density = 1f, prepare = {
            compose.waitUntil(10_000) { api.calls.any { it.startsWith("tree:") } }
        }) {
            ExplorerScreen(onBack = {}, api = api, more = more, filterApi = ExplorerFiltersUiTest.Facets(), startPath = ExplorerFiltersUiTest.ROOT)
        }
        ExplorerPaneWidth.write(app, ExplorerPaneWidth.DEFAULT)
    }

    @Test fun androidFiltersTables() = filtered("tables-bengali", tablet = false, setUp = { it.tab = ExplorerTab.TABLES }, ready = {
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("explorer.tables.grid")).fetchSemanticsNodes().isNotEmpty() }
    })

    @Test fun androidFiltersTablesTablet() = filtered("tables-bengali-tablet", tablet = true, setUp = { it.tab = ExplorerTab.TABLES }, ready = { reach("explorer.tables.grid") })

    private val openDetails: (ExplorerState) -> Unit = { state -> state.files.firstOrNull { it.name == "details.txt" }?.let { state.toggleText(it) } }

    @Test fun androidFiltersDetails() = filtered("details-table", tablet = false, setUp = openDetails, ready = { reach("explorer.text.table") })

    @Test fun androidFiltersDetailsTablet() = filtered("details-table-tablet", tablet = true, setUp = openDetails, ready = { reach("explorer.text.table") })

    @Composable
    private fun multiSelect(tablet: Boolean) {
        Box(Modifier.fillMaxSize().background(Color(0x801E1B2E)), contentAlignment = Alignment.BottomCenter) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = ExplorerShapes.sheet,
                modifier = Modifier.let { if (tablet) it.widthIn(max = 640.dp) else it }.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    ExplorerSheetHandle()
                    ExplorerMultiSelectPanel(
                        key = ExplorerFilterKey.DESIGN_WORKSHOP,
                        filters = ExplorerFilters().with(ExplorerFilterKey.DESIGN_WORKSHOP, listOf("d1"), mapOf("d1" to "Kutch design week")),
                        source = { key, _ -> ExplorerFacetKeys.options(ExplorerFiltersUiTest.FACETS, key) },
                        onBack = {},
                        onApply = { _, _ -> },
                    )
                }
            }
        }
    }

    @Test fun androidFiltersMultiSelect() = shoot("AndroidFilters", "multiselect") { multiSelect(tablet = false) }

    @Test fun androidFiltersMultiSelectTablet() = shoot("AndroidFilters", "multiselect-tablet", widthDp = 1280, heightDp = 800, density = 1f) { multiSelect(tablet = true) }

    @Test fun androidFiltersMultiSelectDark() = shoot("AndroidFilters", "multiselect-dark", dark = true) { multiSelect(tablet = false) }
}
