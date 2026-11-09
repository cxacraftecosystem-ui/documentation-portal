package com.fieldrepository.app.explorer.shots

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.explorer.data.ExplorerDownloads
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerLink
import com.fieldrepository.app.explorer.data.ExplorerMoreApi
import com.fieldrepository.app.explorer.data.ExplorerNetwork
import com.fieldrepository.app.explorer.data.ExplorerPlace
import com.fieldrepository.app.explorer.portal.DesignTheme
import com.fieldrepository.app.explorer.portal.ExplorerPortal
import com.fieldrepository.app.explorer.ui.ExplorerScreen
import com.fieldrepository.app.explorer.ui.ExplorerTablesMemory
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileActionsShotsTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val more = ScreenBoards.More()
    private val details = ExplorerEntry(
        name = "details.txt",
        path = "${ScreenBoards.GIRIRAJ}/details.txt",
        sizeBytes = 426,
        content = "Test at IITKGP\nScheme: DCH test\nCraft: Uttarakhand Tamta Product\nPlace: Kharagpur, West Bengal",
    )
    private val id = details.path

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

    private fun shoot(variant: String, widthDp: Int, heightDp: Int, density: Float, fontScale: Float = 1f) {
        ExplorerPlace.write(app, ExplorerPortal.userId(app), ScreenBoards.GIRIRAJ)
        val api = ScreenBoards.Api(app, lead = listOf(details))
        compose.setContent {
            DesignTheme(false) {
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                        Box(Modifier.requiredSize(widthDp.dp, heightDp.dp).background(MaterialTheme.colorScheme.background).testTag("design.frame")) {
                            ExplorerScreen(onBack = {}, api = api, more = more, startPath = ScreenBoards.GIRIRAJ, search = {}, onRecordNow = { _, _ -> }, onOpenSection = { _, _ -> })
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) { api.calls.any { it == "tree:${ScreenBoards.GIRIRAJ}" } && api.calls.any { it.startsWith("stats:") } }
        compose.waitForIdle()
        compose.onNodeWithTag("explorer.tree").performScrollToNode(hasTestTag("explorer.actions.$id"))
        compose.waitForIdle()
        Thread.sleep(800)
        compose.waitForIdle()

        val frame = compose.onNodeWithTag("design.frame").getBoundsInRoot()
        val row = compose.onNodeWithTag("explorer.file.$id").getBoundsInRoot()
        val menu = compose.onNodeWithTag("explorer.actions.$id").getBoundsInRoot()
        val save = compose.onNodeWithTag("explorer.save.$id").getBoundsInRoot()
        val tree = compose.onNodeWithTag("explorer.tree").getBoundsInRoot()
        assertTrue("$variant: the details card reaches the content edge (row ${row.right} vs list ${tree.right})", tree.right - row.right <= 40.dp)
        assertTrue("$variant: the three dots sit at the card's end (${menu.right} vs ${row.right})", row.right - menu.right <= 32.dp)
        assertTrue("$variant: Save and the three dots never overlap", save.right <= menu.left || save.bottom <= menu.top)
        assertTrue("$variant: the three dots are a full touch target", menu.right - menu.left >= 48.dp - 1.dp && menu.bottom - menu.top >= 48.dp - 1.dp)
        assertTrue("$variant: the card spans most of the list", (row.right - row.left) >= (tree.right - tree.left) * 0.85f)
        assertTrue("$variant: inside the frame", row.right <= frame.right + 1.dp)

        val bitmap = compose.onNodeWithTag("design.frame").captureToImage().asAndroidBitmap()
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "design-fidelity/AndroidFileActions")
        root.mkdirs()
        File(root, "impl-android-$variant.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun phone() = shoot("phone", 390, 844, 2f)

    @Test fun width840() = shoot("840", 840, 900, 1f)

    @Test fun tablet() = shoot("tablet", 1280, 800, 1f)

    @Test fun largeText() = shoot("font2", 390, 844, 2f, fontScale = 2f)
}
