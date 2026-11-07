package com.fieldrepository.app.ui

import android.graphics.Bitmap
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.data.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DrawerFontScaleShotsTest {
    @get:Rule
    val compose = createComposeRule()

    private val master = UserDto(id = "u1", email = "owner@example.org", name = "Aarav Mehta", role = "MASTER_ADMIN")
    private var pushes = 0

    private fun drawer(fontScale: Float) {
        compose.setContent {
            MaterialTheme {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                        Box(Modifier.requiredSize(390.dp, 844.dp).testTag("drawer.frame")) {
                            AppNavigationDrawerContent(
                                user = master,
                                adminMode = true,
                                onNavigate = {},
                                onToggleAdminView = {},
                                onLogout = {},
                                onPushUpdate = { pushes++ },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shot(variant: String) {
        val bitmap = compose.onNodeWithTag("drawer.frame").captureToImage().asAndroidBitmap()
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "design-fidelity/AndroidDrawer")
        root.mkdirs()
        File(root, "impl-android-$variant.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun nameLines(): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("nav.appName").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single().lineCount
    }

    private fun inList(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("nav.list"))).fetchSemanticsNodes().isNotEmpty()

    private fun pushNeedsConfirmation() {
        compose.onNodeWithTag("nav.pushUpdate").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("nav.pushUpdate.dialog").assertIsDisplayed()
        assertEquals(0, pushes)
        compose.onNodeWithTag("nav.pushUpdate.confirm").performClick()
        compose.waitForIdle()
        assertEquals(1, pushes)
    }

    @Test fun atNormalTextTheHeaderAndFooterStayFixedAndSmall() {
        drawer(1f)
        shot("font10")
        assertEquals(1, nameLines())
        assertTrue(!inList("nav.header"))
        assertTrue(inList("nav.pushUpdate"))
        val frame = compose.onNodeWithTag("drawer.frame").fetchSemanticsNode().boundsInRoot.height
        val header = compose.onNodeWithTag("nav.header").fetchSemanticsNode().boundsInRoot.height
        val footer = compose.onNodeWithTag("nav.footer").fetchSemanticsNode().boundsInRoot.height
        assertTrue("header $header + footer $footer of $frame", (header + footer) / frame <= 0.35f)
        pushNeedsConfirmation()
    }

    @Test fun atOneAndAHalfEverythingScrollsTogether() {
        drawer(1.5f)
        shot("font15")
        assertEquals(1, nameLines())
        assertTrue(inList("nav.header"))
        assertTrue(inList("nav.footer"))
        assertTrue(inList("nav.pushUpdate"))
        pushNeedsConfirmation()
    }

    @Test fun atDoubleTextTheNameStaysOnOneLineAndNothingIsPinned() {
        drawer(2f)
        shot("font20")
        assertEquals(1, nameLines())
        assertTrue(inList("nav.header"))
        assertTrue(inList("nav.footer"))
        assertTrue(inList("nav.pushUpdate"))
        pushNeedsConfirmation()
        shot("font20-confirmed")
    }
}
