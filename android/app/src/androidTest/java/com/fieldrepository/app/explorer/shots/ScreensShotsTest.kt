package com.fieldrepository.app.explorer.shots

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.explorer.data.ExplorerAudio
import com.fieldrepository.app.explorer.data.ExplorerAudioState
import com.fieldrepository.app.explorer.data.ExplorerCache
import com.fieldrepository.app.explorer.data.ExplorerDownloads
import com.fieldrepository.app.explorer.data.ExplorerJob
import com.fieldrepository.app.explorer.data.ExplorerLink
import com.fieldrepository.app.explorer.data.ExplorerMoreApi
import com.fieldrepository.app.explorer.data.ExplorerNetwork
import com.fieldrepository.app.explorer.data.ExplorerPlace
import com.fieldrepository.app.explorer.data.ExplorerPreflight
import com.fieldrepository.app.explorer.data.ExplorerSize
import com.fieldrepository.app.explorer.portal.DesignTheme
import com.fieldrepository.app.explorer.portal.ExplorerPortal
import com.fieldrepository.app.explorer.ui.DownloadsSheetBody
import com.fieldrepository.app.explorer.ui.ExplorerSheetHandle
import com.fieldrepository.app.explorer.ui.ExplorerShapes
import com.fieldrepository.app.explorer.ui.audioItem
import com.fieldrepository.app.explorer.ui.audioLines
import com.fieldrepository.app.explorer.ui.ExplorerScreen
import com.fieldrepository.app.explorer.ui.ExplorerTablesMemory
import com.fieldrepository.app.explorer.ui.PreflightBody
import com.fieldrepository.app.explorer.portal.DesignRecordSearch
import com.fieldrepository.app.explorer.portal.DesignUnifiedSearch
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ScreensShotsTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val more = ScreenBoards.More()
    private val later = System.currentTimeMillis() + 3_600_000

    @Before fun fakes() {
        ExplorerMoreApi.override = more
        ExplorerTablesMemory.clear()
        ExplorerAudio.stage(ExplorerAudioState())
        ExplorerDownloads.store(app).clear()
        ExplorerNetwork.override = ExplorerLink(online = true, metered = false)
        ExplorerCache(File(app.filesDir, ExplorerCache.DIR), ExplorerPortal.userId(app)).clear()
    }

    @After fun reset() {
        ExplorerMoreApi.override = null
        ExplorerNetwork.override = null
        ExplorerDownloads.store(app).clear()
    }

    private fun shoot(
        board: String,
        variant: String? = null,
        widthDp: Int = 390,
        heightDp: Int = 844,
        density: Float = 2f,
        fontScale: Float = 1f,
        dark: Boolean = false,
        prepare: () -> Unit = {},
        content: @Composable () -> Unit,
    ) {
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
        Thread.sleep(1500)
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag("design.frame").captureToImage().asAndroidBitmap()
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "design-fidelity/$board")
        root.mkdirs()
        File(root, "impl-android${variant?.let { "-$it" } ?: ""}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun stage(vararg jobs: ExplorerJob) {
        val store = ExplorerDownloads.store(app)
        jobs.reversed().forEach { store.put(it) }
    }

    private fun waitFor(api: ScreenBoards.Api, call: String) {
        compose.waitUntil(10_000) { api.calls.any { it == call } }
        compose.waitForIdle()
    }

    private fun waitTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun treeIndices(keys: List<String>): List<Int>? {
        val tree = compose.onAllNodes(hasTestTag("explorer.tree"), useUnmergedTree = true).fetchSemanticsNodes().singleOrNull() ?: return null
        val indexOf = tree.config.getOrNull(SemanticsProperties.IndexForKey) ?: return null
        return keys.map { indexOf(it) }.takeIf { found -> found.all { it >= 0 } }
    }

    private fun settleTree(vararg keys: String) {
        var last: List<Int>? = null
        var steady = 0
        compose.waitUntil(15_000) {
            val now = treeIndices(keys.toList())
            steady = if (now != null && now == last) steady + 1 else 0
            last = now
            steady >= 3
        }
        compose.waitForIdle()
    }

    private val largeTextTree = arrayOf("folder", "download", "jobs", "tabs")

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("explorer.tree").performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    @Composable
    private fun folder(api: ScreenBoards.Api, start: String = ScreenBoards.GIRIRAJ) {
        ExplorerScreen(
            onBack = {},
            api = api,
            more = more,
            startPath = start,
            search = { onOpen -> DesignRecordSearch(onOpen) },
            onRecordNow = { _, _ -> },
            onOpenSection = { _, _ -> },
        )
    }

    private fun folderShot(board: String, variant: String? = null, dark: Boolean = false, fontScale: Float = 1f, tablet: Boolean = false, start: String = ScreenBoards.GIRIRAJ, mode: ScreenBoards.Mode = ScreenBoards.Mode.NORMAL, after: (ScreenBoards.Api) -> Unit = {}) {
        ExplorerPlace.write(app, ExplorerPortal.userId(app), start)
        val api = ScreenBoards.Api(app, mode)
        shoot(
            board, variant, dark = dark, fontScale = fontScale,
            widthDp = if (tablet) 1280 else 390, heightDp = if (tablet) 800 else 844, density = if (tablet) 1f else 2f,
            prepare = {
                compose.waitUntil(10_000) { api.calls.any { it.startsWith("tree:") } }
                if (mode == ScreenBoards.Mode.NORMAL) {
                    waitFor(api, "tree:$start")
                    if (start != ScreenBoards.ROOT) compose.waitUntil(10_000) { api.calls.any { it.startsWith("stats:") } }
                }
                compose.waitForIdle()
                after(api)
            },
        ) { folder(api, start) }
    }

    @Test fun androidFolder() = folderShot("AndroidFolder")

    @Test fun androidFolderDark() = folderShot("AndroidFolder", "dark", dark = true)

    @Test fun androidFolderLargeText() = folderShot("AndroidFolder", "font2", fontScale = 2f)

    @Test fun androidFolderFiles() = folderShot("AndroidFolder", "files") { scrollTo("explorer.file.d1") }

    @Test fun androidDark() {
        stage(ScreenBoards.saved(later, "Bagru workshop", 212, (3.8 * 1024 * 1024 * 1024).toLong()), ScreenBoards.failed(later + 1))
        folderShot("AndroidDark", dark = true)
    }

    @Test fun androidDarkResults() {
        stage(ScreenBoards.saved(later, "Bagru workshop", 212, (3.8 * 1024 * 1024 * 1024).toLong()), ScreenBoards.failed(later + 1))
        folderShot("AndroidDark", "results", dark = true) { scrollTo("explorer.result") }
    }

    @Test fun androidDownload() {
        stage(ScreenBoards.running(later))
        folderShot("AndroidDownload") { scrollTo("explorer.progress.where") }
    }

    @Test fun androidDownloadDark() {
        stage(ScreenBoards.running(later))
        folderShot("AndroidDownload", "dark", dark = true) { scrollTo("explorer.progress.where") }
    }

    @Test fun androidDownloadLargeText() {
        stage(ScreenBoards.running(later))
        folderShot("AndroidDownload", "font2", fontScale = 2f) { scrollTo("explorer.progress.where") }
    }

    @Test fun androidLargeText() {
        stage(ScreenBoards.runningSmall(later))
        folderShot("AndroidLargeText", fontScale = 2f) { settleTree(*largeTextTree) }
    }

    @Test fun androidLargeTextDownload() {
        stage(ScreenBoards.runningSmall(later))
        folderShot("AndroidLargeText", "download", fontScale = 2f) {
            settleTree(*largeTextTree)
            scrollTo("explorer.progress.where")
        }
    }

    @Test fun androidLargeTextDark() {
        stage(ScreenBoards.runningSmall(later))
        folderShot("AndroidLargeText", "dark", dark = true, fontScale = 2f) {
            settleTree(*largeTextTree)
            scrollTo("explorer.download.card")
        }
    }

    @Test fun androidTablet() = folderShot("AndroidTablet", tablet = true)

    @Test fun androidTabletDark() = folderShot("AndroidTablet", "dark", tablet = true, dark = true)

    private fun select() {
        scrollTo("explorer.file.v1")
        compose.onNodeWithTag("explorer.file.v1").performTouchInput { longClick() }
        compose.waitForIdle()
        scrollTo("explorer.check.i1")
        compose.onNodeWithTag("explorer.check.i1").performClick()
        scrollTo("explorer.check.a1")
        compose.onNodeWithTag("explorer.check.a1").performClick()
        compose.waitForIdle()
        scrollTo("explorer.kind.all")
    }

    @Test fun androidSelect() = folderShot("AndroidSelect") { select() }

    @Test fun androidSelectDark() = folderShot("AndroidSelect", "dark", dark = true) { select() }

    @Test fun androidSelectLargeText() = folderShot("AndroidSelect", "font2", fontScale = 2f) { select() }

    private fun tables(api: ScreenBoards.Api) {
        scrollTo("explorer.tables.toggle")
        compose.onNodeWithTag("explorer.tables.toggle").performClick()
        compose.waitUntil(10_000) { api.reportQueries.isNotEmpty() }
        compose.waitForIdle()
        scrollTo("explorer.tables.search")
        compose.onNodeWithTag("explorer.tables.search").performTextInput("indigo")
        compose.waitUntil(10_000) { api.reportQueries.contains("indigo") }
        compose.waitForIdle()
        scrollTo("explorer.tables")
    }

    @Test fun phoneTables() = folderShot("PhoneTables") { tables(it) }

    @Test fun phoneTablesDark() = folderShot("PhoneTables", "dark", dark = true) { tables(it) }

    @Test fun phoneTablesLargeText() = folderShot("PhoneTables", "font2", fontScale = 2f) { tables(it) }

    @Test fun workshopOverview() {
        more.overviewOf = ScreenBoards.overview()
        folderShot("WorkshopOverview", "phone", start = ScreenBoards.WORKSHOP) { scrollTo("explorer.overview") }
    }

    @Test fun workshopOverviewDark() {
        more.overviewOf = ScreenBoards.overview()
        folderShot("WorkshopOverview", "phone-dark", dark = true, start = ScreenBoards.WORKSHOP) { scrollTo("explorer.overview") }
    }

    @Test fun workshopOverviewTablet() {
        more.overviewOf = ScreenBoards.overview()
        folderShot("WorkshopOverview", "tablet", tablet = true, start = ScreenBoards.WORKSHOP) { scrollTo("explorer.overview") }
    }

    @Test fun statesLoading() = folderShot("States", "loading", mode = ScreenBoards.Mode.HANG)

    @Test fun statesLoadingDark() = folderShot("States", "loading-dark", dark = true, mode = ScreenBoards.Mode.HANG)

    private fun steadyTreeKey(key: String) {
        val indexForKey = androidx.compose.ui.semantics.SemanticsProperties.IndexForKey
        var last = -1
        var steady = 0
        compose.waitUntil(15_000) {
            val tree = compose.onAllNodes(hasTestTag("explorer.tree"), useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
            val now = if (tree != null && indexForKey in tree.config) tree.config[indexForKey](key) else -1
            steady = if (now >= 0 && now == last) steady + 1 else 0
            last = now
            steady >= 3
        }
        compose.waitForIdle()
    }

    @Test fun statesEmpty() = folderShot("States", "empty", start = ScreenBoards.EMPTY) {
        steadyTreeKey("empty")
        scrollTo("explorer.empty")
    }

    @Test fun statesEmptyDark() = folderShot("States", "empty-dark", dark = true, start = ScreenBoards.EMPTY) {
        steadyTreeKey("empty")
        scrollTo("explorer.empty")
    }

    @Test fun statesError() = folderShot("States", "error", mode = ScreenBoards.Mode.FAIL) { waitTag("explorer.error") }

    @Test fun statesErrorDark() = folderShot("States", "error-dark", dark = true, mode = ScreenBoards.Mode.FAIL) { waitTag("explorer.error") }

    private fun offline(dark: Boolean, variant: String) {
        val owner = ExplorerPortal.userId(app)
        val tree = kotlinx.coroutines.runBlocking { ScreenBoards.Api(app).tree(ScreenBoards.GIRIRAJ, null, null, 1) }
        ExplorerCache(File(app.filesDir, ExplorerCache.DIR), owner).write(ExplorerCache.key(ScreenBoards.GIRIRAJ, ExplorerPortal.defaultStatus?.let { ExplorerPortal.statusParam(it) }), tree, System.currentTimeMillis() - 14 * 60_000)
        ExplorerNetwork.override = ExplorerLink(online = false, metered = false)
        ExplorerPlace.write(app, owner, ScreenBoards.GIRIRAJ)
        val api = ScreenBoards.Api(app, ScreenBoards.Mode.FAIL)
        shoot("States", variant, dark = dark, prepare = { waitTag("explorer.offline.card") }) { folder(api) }
    }

    @Test fun statesOffline() = offline(false, "offline")

    @Test fun statesOfflineDark() = offline(true, "offline-dark")

    @Composable
    private fun sheetFrame(scrim: Boolean, top: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(if (scrim) Color(0x801E1B2E) else Color(0xFF1B1830))) {
            if (scrim) Box(Modifier.weight(1f).fillMaxWidth()) { top() } else Box(Modifier.fillMaxWidth()) { top() }
            Surface(color = MaterialTheme.colorScheme.surface, shape = ExplorerShapes.sheet, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    ExplorerSheetHandle()
                    content()
                }
            }
        }
    }

    @Composable
    private fun downloads() {
        val now = System.currentTimeMillis()
        val running = ScreenBoards.running(now)
        val saved = ScreenBoards.saved(now - 1)
        val failed = ScreenBoards.failed(now - 2)
        val jobs = listOf(running, saved, failed, ScreenBoards.report(now - 3))
        sheetFrame(scrim = false, top = { NotificationPreviews(running, saved, failed) }) {
            DownloadsSheetBody(jobs = jobs, onCancel = {}, onRetry = {}, onOpen = {}, onShare = {}, onDismissJob = {}, onClearFinished = {})
        }
    }

    @Test fun androidAudioPlayerBrowsing() {
        val entry = ScreenBoards.audio
        val lines = audioLines(DesignBoards.audioTranscript())
        val item = audioItem(entry, "https://example.invalid/${entry.mediaId}", lines)!!
        ExplorerAudio.stage(ExplorerAudioState(item = item, playing = true, positionMs = 1_100_000, durationMs = DesignBoards.AUDIO_MS, speed = 1f, line = lines.lastOrNull { it.startMs <= 1_100_000 }))
        folderShot("AndroidAudioPlayer", "browsing") { scrollTo("explorer.file.i1"); waitTag("explorer.playing.a1") }
    }

    @Test fun androidDownloads() = shoot("AndroidDownloads") { downloads() }

    @Test fun androidDownloadsDark() = shoot("AndroidDownloads", "dark", dark = true) { downloads() }

    @Test fun androidDownloadsLargeText() = shoot("AndroidDownloads", "font2", fontScale = 2f) { downloads() }

    @Composable
    private fun preflight() {
        val gb = 1024L * 1024L * 1024L
        val size = ExplorerSize(
            files = 926, bytes = ScreenBoards.WORKSHOP_BYTES,
            byKind = ScreenBoards.workshopStats.byType.entries.associate { com.fieldrepository.app.explorer.data.ExplorerKind.ofToken(it.key) to it.value },
            largest = 701L * 1024 * 1024,
        )
        val plan = ExplorerPreflight.of(size = size, link = ExplorerLink(online = true, metered = true), freeBytes = (28.55 * gb).toLong(), speedBps = 6L * 1024 * 1024, location = "Downloads/${com.fieldrepository.app.explorer.portal.ExplorerPortal.DOWNLOAD_FOLDER}/3rd-Craft-Toolkit.zip", totalBytes = 51L * gb)
        sheetFrame(scrim = true) {
            PreflightBody(title = "3rd Craft Toolkit", plan = plan, onStart = { _, _ -> }, onDismiss = {}, onChangeFolder = {})
        }
    }

    @Test fun androidPreflight() = shoot("AndroidPreflight") { preflight() }

    @Test fun androidPreflightDark() = shoot("AndroidPreflight", "dark", dark = true) { preflight() }

    @Test fun androidPreflightLargeText() = shoot("AndroidPreflight", "font2", fontScale = 2f) { preflight() }

    @Composable
    private fun search(initial: String, body: String) = DesignUnifiedSearch(initial, body)

    @Test fun androidSearch() = shoot("AndroidSearch", prepare = { waitTag("data.search.results") }) { search("indigo mud resist", ScreenBoards.SEARCH_LOCAL) }

    @Test fun androidSearchDark() = shoot("AndroidSearch", "dark", dark = true, prepare = { waitTag("data.search.results") }) { search("indigo mud resist", ScreenBoards.SEARCH_LOCAL) }

    @Test fun androidSearchLargeText() = shoot("AndroidSearch", "font2", fontScale = 2f, prepare = { waitTag("data.search.results") }) { search("indigo mud resist", ScreenBoards.SEARCH_LOCAL) }

    private fun both() {
        waitTag("data.search.scope.all")
        compose.onNodeWithTag("data.search.scope.all").performClick()
        waitTag("data.search.badge.fr")
    }

    @Test fun searchBoth() = shoot("SearchBoth", prepare = { both() }) { search("indigo vat", ScreenBoards.SEARCH_BOTH) }

    @Test fun searchBothDark() = shoot("SearchBoth", "dark", dark = true, prepare = { both() }) { search("indigo vat", ScreenBoards.SEARCH_BOTH) }

    @Test fun statesNoResults() = shoot("States", "noresults", prepare = { waitTag("data.search.none") }) { search("indgo", ScreenBoards.SEARCH_NONE) }
}
