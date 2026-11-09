package com.fieldrepository.app.questionnaire

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.questionnaire.data.QSessionLocation
import com.fieldrepository.app.questionnaire.ui.EditSessionDialog
import com.fieldrepository.app.questionnaire.ui.SessionEdit
import com.fieldrepository.app.questionnaire.ui.SessionPlaceField
import com.fieldrepository.app.questionnaire.ui.SessionWhere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SessionPlaceFieldUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val pin = QSessionLocation(26.8105, 75.5466, placeName = "Bagru, Jaipur, Rajasthan")

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("place.frame").captureToImage().asAndroidBitmap()
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "design-fidelity/AndroidSessionPlace")
        root.mkdirs()
        File(root, "impl-android-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun field(start: SessionWhere, widthDp: Int, fontScale: Float = 1f, onValue: (SessionWhere) -> Unit) {
        var value by mutableStateOf(start)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Box(Modifier.requiredWidth(widthDp.dp).background(MaterialTheme.colorScheme.background).padding(16.dp).testTag("place.frame")) {
                        SessionPlaceField(value = value, onChange = { value = it; onValue(it) })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun theNewSessionPlaceSearchesTypesAndUnpins() {
        var seen: SessionWhere? = null
        field(SessionWhere("Bagru, Jaipur", pin), 390) { seen = it }
        compose.onNodeWithText("Search a place").assertExists()
        compose.onNodeWithText("Pick on map").assertExists()
        compose.onNodeWithText("Pinned at 26.81050, 75.54660").assertExists()
        capture("phone")
        compose.onNodeWithTag("q-place-unpin").performClick()
        compose.waitForIdle()
        assertNull(seen!!.location)
        assertEquals("Bagru, Jaipur", seen!!.place)
        compose.onNodeWithTag("q-place-text").performTextReplacement("Sanganer")
        compose.waitForIdle()
        assertEquals("Sanganer", seen!!.place)
    }

    @Test fun thePlaceFieldAtTabletWidth() {
        field(SessionWhere("Bagru, Jaipur", pin), 840) {}
        compose.onNodeWithText("Pick on map").assertExists()
        capture("tablet")
    }

    @Test fun thePlaceFieldAtLargeText() {
        field(SessionWhere("Bagru, Jaipur", pin), 390, fontScale = 2f) {}
        compose.onNodeWithText("Pick on map").assertExists()
        capture("font2")
    }

    @Test fun theEditDialogPrefillsThePinAndSavesItsRemoval() {
        var saved: SessionEdit? = null
        val initial = SessionEdit.of("2026-11-09", "FIRST_SITTING", "Bagru, Jaipur", "hi", null, "Neem tree", pin)
        compose.setContent { MaterialTheme { EditSessionDialog(initial, onDismiss = {}) { saved = it } } }
        compose.waitForIdle()
        compose.onNodeWithText("Pinned at 26.81050, 75.54660").assertExists()
        compose.onNodeWithTag("q-place-unpin").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertNotNull(saved)
        val patch = saved!!.patch(4, initial)
        assertEquals(true, patch.clearLocation)
        assertNull(patch.location)
    }
}
