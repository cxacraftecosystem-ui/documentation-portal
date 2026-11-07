package com.fieldrepository.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StaticControlsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun pixels(tag: String): IntArray {
        val image = compose.onNodeWithTag(tag).captureToImage()
        return IntArray(image.width * image.height).also { image.readPixels(it) }
    }

    @Test
    fun theCheckboxHasNoAnimatedIntermediateState() {
        var checked by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.background(Color.White).padding(8.dp).testTag("frame")) {
                    FieldCheckbox(checked = checked, onCheckedChange = { checked = it }, modifier = Modifier.testTag("check"))
                }
            }
        }
        compose.waitForIdle()
        val unchecked = pixels("frame")
        compose.mainClock.autoAdvance = false

        compose.onNodeWithTag("check").performClick()
        compose.mainClock.advanceTimeByFrame()
        val firstFrame = pixels("frame")
        compose.mainClock.advanceTimeBy(16L)
        val secondFrame = pixels("frame")
        compose.mainClock.advanceTimeBy(1_000L)
        val settled = pixels("frame")
        assertTrue(checked)
        assertFalse("the first frame after the tap must already show the tick", unchecked.contentEquals(firstFrame))
        assertTrue("no frame between the tap and the settled tick may differ", firstFrame.contentEquals(secondFrame))
        assertTrue("no frame between the tap and the settled tick may differ", firstFrame.contentEquals(settled))

        compose.onNodeWithTag("check").performClick()
        compose.mainClock.advanceTimeByFrame()
        val clearedFirst = pixels("frame")
        compose.mainClock.advanceTimeBy(1_000L)
        val clearedSettled = pixels("frame")
        assertFalse(checked)
        assertTrue(clearedFirst.contentEquals(clearedSettled))
        assertTrue(clearedFirst.contentEquals(unchecked))
    }

    @Test
    fun theTickIsAStraightTwoSegmentPath() {
        assertEquals(3, FieldCheckboxDefaults.tickPoints.size)
        val (start, corner, end) = FieldCheckboxDefaults.tickPoints
        assertTrue(corner.y > start.y && corner.y > end.y)
        assertTrue(start.x < corner.x && corner.x < end.x)
    }

    @Test
    fun everyMultiSelectOffersSelectAllAndClearAll() {
        compose.setContent {
            MaterialTheme {
                SearchableMultiSelectField(
                    label = "Record types",
                    options = listOf("Artisans", "Workshops", "Products", "Tools", "Media").map { SelectOption(it.lowercase(), it) },
                    selected = emptySet(),
                    onSelectedChange = {},
                )
            }
        }
        compose.onNode(hasContentDescription("Record types", substring = true)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Select all 5 shown").assertIsDisplayed()
        compose.onNodeWithText("Clear all").assertIsDisplayed()
    }
}
