package com.fieldrepository.app.explorer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.explorer.data.ExplorerDownloads
import com.fieldrepository.app.explorer.data.ExplorerLink
import com.fieldrepository.app.explorer.data.ExplorerMoreApi
import com.fieldrepository.app.explorer.data.ExplorerNetwork
import com.fieldrepository.app.explorer.portal.DesignTheme
import com.fieldrepository.app.explorer.shots.ScreenBoards
import com.fieldrepository.app.explorer.ui.ExplorerScreen
import com.fieldrepository.app.explorer.ui.ExplorerTablesMemory
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExplorerLargeTextUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val more = ScreenBoards.More()

    @Before fun fakes() {
        ExplorerTablesMemory.clear()
        ExplorerMoreApi.override = more
        ExplorerNetwork.override = ExplorerLink(online = true, metered = false)
        ExplorerDownloads.store(app).clear()
    }

    @After fun reset() {
        ExplorerMoreApi.override = null
        ExplorerNetwork.override = null
        ExplorerDownloads.store(app).clear()
    }

    private fun show(api: ScreenBoards.Api) {
        ExplorerDownloads.store(app).put(ScreenBoards.running(System.currentTimeMillis() + 3_600_000))
        compose.setContent {
            DesignTheme(false) {
                CompositionLocalProvider(LocalDensity provides Density(2f, 2f)) {
                    Box(Modifier.requiredSize(390.dp, 844.dp)) {
                        ExplorerScreen(onBack = {}, api = api, more = more, startPath = ScreenBoards.GIRIRAJ, search = { Text("search", modifier = Modifier.testTag("explorer.search.slot")) })
                    }
                }
            }
        }
        compose.waitUntil(10_000) { api.calls.any { it == "tree:${ScreenBoards.GIRIRAJ}" } && api.calls.any { it.startsWith("stats:") } }
        compose.waitForIdle()
    }

    private fun nothingCut(where: String) {
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("no text at $where", texts.isNotEmpty())
        texts.forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.firstOrNull() ?: return@forEach
            val words = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
            assertTrue("\"$words\" is cut off at fontScale 2 ($where)", !layout.hasVisualOverflow)
        }
    }

    @QFeature("X1")
    @Test fun atDoubleTextSizeNothingOnTheFolderIsCutOffAndButtonsGoFullWidth() {
        val api = ScreenBoards.Api(app)
        show(api)
        listOf(
            "explorer.folder.card", "explorer.details.all", "explorer.download.card", "explorer.download", "explorer.report",
            "explorer.progress", "explorer.progress.now", "explorer.progress.cancel", "explorer.recent", "explorer.tab.files",
            "explorer.file.v1", "explorer.file.a1", "explorer.file.d1",
        ).forEach { tag ->
            compose.onNodeWithTag("explorer.tree").performScrollToNode(hasTestTag(tag))
            compose.waitForIdle()
            nothingCut(tag)
        }
        compose.onNodeWithTag("explorer.tree").performScrollToNode(hasTestTag("explorer.download.card"))
        compose.waitForIdle()
        val card = compose.onNodeWithTag("explorer.download.card").getBoundsInRoot()
        val download = compose.onNodeWithTag("explorer.download").getBoundsInRoot()
        val report = compose.onNodeWithTag("explorer.report").getBoundsInRoot()
        assertTrue("download sits above report", report.top >= download.bottom)
        assertTrue("download spans the card", (download.right - download.left) >= (card.right - card.left) - 40.dp)
    }

    @QFeature("X3")
    @Test fun atDoubleTextSizeTheSelectBarStacksItsButtons() {
        val api = ScreenBoards.Api(app)
        show(api)
        compose.onNodeWithTag("explorer.tree").performScrollToNode(hasTestTag("explorer.file.v1"))
        compose.onNodeWithTag("explorer.file.v1").performTouchInput { longClick() }
        compose.waitForIdle()
        val download = compose.onNodeWithTag("explorer.select.download").getBoundsInRoot()
        val share = compose.onNodeWithTag("explorer.select.share").getBoundsInRoot()
        assertTrue("share sits under download", share.top >= download.bottom)
        nothingCut("selecting")
    }
}
