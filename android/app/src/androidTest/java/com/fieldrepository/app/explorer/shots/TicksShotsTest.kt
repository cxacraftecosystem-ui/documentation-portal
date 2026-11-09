package com.fieldrepository.app.explorer.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import com.fieldrepository.app.CompletionCellMark
import com.fieldrepository.app.explorer.data.ExplorerDownloads
import com.fieldrepository.app.explorer.data.ExplorerLink
import com.fieldrepository.app.explorer.data.ExplorerMoreApi
import com.fieldrepository.app.explorer.data.ExplorerNetwork
import com.fieldrepository.app.explorer.data.ExplorerPlace
import com.fieldrepository.app.explorer.portal.DesignTheme
import com.fieldrepository.app.explorer.portal.ExplorerPortal
import com.fieldrepository.app.explorer.ui.ExplorerMarkBadge
import com.fieldrepository.app.explorer.ui.ExplorerMarkKind
import com.fieldrepository.app.explorer.ui.ExplorerPill
import com.fieldrepository.app.explorer.ui.ExplorerScreen
import com.fieldrepository.app.explorer.ui.ExplorerStatus
import com.fieldrepository.app.explorer.ui.ExplorerTablesMemory
import com.fieldrepository.app.explorer.ui.ExplorerTickText
import com.fieldrepository.app.explorer.ui.explorerMark
import com.fieldrepository.app.explorer.ui.explorerTone
import com.fieldrepository.app.questionnaire.ui.CoverageTick
import com.fieldrepository.app.ui.FieldCheckbox
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TicksShotsTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val more = ScreenBoards.More()

    @Before fun fakes() {
        ExplorerMoreApi.override = more
        ExplorerTablesMemory.clear()
        ExplorerNetwork.override = ExplorerLink(online = true, metered = false)
        ExplorerDownloads.store(app).clear()
    }

    @After fun reset() {
        ExplorerMoreApi.override = null
        ExplorerNetwork.override = null
        ExplorerDownloads.store(app).clear()
    }

    private fun save(name: String) {
        val bitmap = compose.onNodeWithTag("design.frame").captureToImage().asAndroidBitmap()
        val root = File(app.getExternalFilesDir(null), "design-fidelity/AndroidTicks")
        root.mkdirs()
        File(root, "impl-android-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun assertNoFontTick() {
        listOf("\u2713", "\u2714", "\u2611").forEach { glyph ->
            assertEquals("no font tick $glyph", 0, compose.onAllNodesWithText(glyph, substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
        }
    }

    private fun includeChips(name: String, widthDp: Int, heightDp: Int, density: Float) {
        ExplorerPlace.write(app, ExplorerPortal.userId(app), ScreenBoards.GIRIRAJ)
        val api = ScreenBoards.Api(app)
        compose.setContent {
            DesignTheme(false) {
                CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                        Box(Modifier.requiredSize(widthDp.dp, heightDp.dp).background(MaterialTheme.colorScheme.background).testTag("design.frame")) {
                            ExplorerScreen(onBack = {}, api = api, more = more, startPath = ScreenBoards.GIRIRAJ, search = {}, onRecordNow = { _, _ -> }, onOpenSection = { _, _ -> })
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) { api.calls.any { it.startsWith("stats:") } }
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("explorer.tree").performScrollToNode(hasTestTag("explorer.include")) }.isSuccess }
        compose.waitForIdle()
        Thread.sleep(600)
        compose.waitForIdle()
        assertTrue("$name: a ticked include chip draws the straight mark", compose.onAllNodesWithTag("explorer.mark.saved", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertNoFontTick()
        save(name)
    }

    private fun gallery(name: String, dark: Boolean) {
        compose.setContent {
            DesignTheme(dark) {
                CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                        Column(
                            Modifier.requiredSize(390.dp, 760.dp).background(MaterialTheme.colorScheme.background).padding(16.dp).testTag("design.frame"),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            val saved = explorerTone(ExplorerStatus.SAVED)!!
                            val ink = MaterialTheme.colorScheme.onSurface
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ExplorerPill("Videos 3.4 GB", selected = true, ticked = true, onClick = {})
                                ExplorerPill("Images", selected = false, onClick = {})
                                ExplorerPill("Clear voice", selected = true, ticked = true, onClick = {})
                            }
                            ExplorerTickText("Link copied", ticked = true, color = saved.accent)
                            ExplorerTickText("2 sheets · 14 columns", ticked = true, color = saved.accent)
                            ExplorerTickText("Follows video", ticked = true, color = saved.accent, fontWeight = FontWeight.SemiBold)
                            Box(Modifier.background(saved.container, MaterialTheme.shapes.extraLarge).padding(horizontal = 10.dp, vertical = 4.dp)) {
                                ExplorerTickText("Ready", ticked = true, color = saved.accent, fontWeight = FontWeight.SemiBold)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                val (markBg, markFg) = explorerMark()
                                ExplorerMarkBadge(ExplorerMarkKind.SAVED, background = markBg, tint = markFg)
                                Box(Modifier.size(52.dp, 48.dp), contentAlignment = Alignment.Center) { CompletionCellMark("COMPLETED", false) }
                                FieldCheckbox(checked = true, onCheckedChange = {})
                                ExplorerTickText("Saved", ticked = true, color = ink)
                            }
                            CoverageTick.Line("2/3 " + CoverageTick.TOKEN, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            CoverageTick.Line(CoverageTick.TOKEN + " Marked complete", style = MaterialTheme.typography.labelSmall, color = ink)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTrue("$name: every tick is the straight mark", compose.onAllNodesWithTag("explorer.mark.saved", useUnmergedTree = true).fetchSemanticsNodes().size >= 9)
        assertNoFontTick()
        save(name)
    }

    @Test fun includePhone() = includeChips("include-phone", 390, 844, 2f)

    @Test fun includeTablet() = includeChips("include-tablet", 1280, 800, 1f)

    @Test fun marksLight() = gallery("marks", false)

    @Test fun marksDark() = gallery("marks-dark", true)
}
