package com.fieldrepository.app.ui

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TopSearchReturnUiTest {
    @get:Rule
    val compose = createComposeRule()

    private var remoteCalls = 0

    private fun keyboardUp(view: View?): Boolean =
        view != null && ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun focusedNodes(): Int =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true), useUnmergedTree = true)
            .fetchSemanticsNodes().size

    @Test
    fun backFromARecordOpenedInTopSearchReturnsToTheSameQueryAndResultsWithoutTheKeyboard() {
        var view: View? = null
        compose.setContent {
            view = LocalView.current
            MaterialTheme {
                var screen by remember { mutableStateOf("dashboard") }
                var searchOpen by remember { mutableStateOf(false) }
                val session = remember { AppSearchSession() }
                val searchReturn = remember { AppSearchReturn<String>() }
                LaunchedEffect(screen, session.openedResult) { searchReturn.observe(screen, session) }
                BackHandler(enabled = screen != "dashboard") {
                    val origin = searchReturn.backTo(screen)
                    if (origin != null) {
                        searchReturn.returned()
                        screen = origin
                        session.restore()
                        searchOpen = true
                    } else {
                        screen = "dashboard"
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    Text(if (screen == "dashboard") "Dashboard screen" else "Record screen $screen")
                    if (!searchOpen) {
                        Text("Open top search", modifier = Modifier.clickable { session.start(); searchReturn.begin(screen); searchOpen = true })
                    }
                    if (searchOpen) {
                        AppSearchPanel(
                            appName = "the app",
                            logo = {},
                            localItems = emptyList(),
                            remoteSearch = { q ->
                                remoteCalls++
                                listOf(
                                    AppSearchItem(
                                        group = AppSearchGroup.ARTISANS,
                                        key = "a1",
                                        title = "Heera Singh",
                                        icon = Icons.Filled.Person,
                                        subtitle = "match for $q",
                                        onOpen = { screen = "a1" }
                                    )
                                )
                            },
                            onClose = { searchOpen = false },
                            session = session
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Open top search").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithContentDescription("Search the app").performTextInput("heera")
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasClickAction().and(hasText("Heera Singh"))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasClickAction().and(hasText("Heera Singh"))).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Record screen a1")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15_000) { !keyboardUp(view) }
        val callsBeforeBack = remoteCalls

        Espresso.pressBack()

        compose.waitUntil(15_000) {
            compose.onAllNodes(hasClickAction().and(hasText("Heera Singh"))).fetchSemanticsNodes().isNotEmpty()
        }
        val field = compose.onNodeWithContentDescription("Search the app").fetchSemanticsNode()
        assertEquals("heera", field.config.getOrNull(SemanticsProperties.EditableText)?.text)
        assertEquals(0, compose.onAllNodes(hasText("Record screen a1")).fetchSemanticsNodes().size)
        compose.waitForIdle()
        assertEquals(0, focusedNodes())
        assertFalse(keyboardUp(view))
        assertEquals(callsBeforeBack, remoteCalls)

        Espresso.pressBack()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Dashboard screen")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Open top search")).fetchSemanticsNodes().isNotEmpty() }
    }
}
