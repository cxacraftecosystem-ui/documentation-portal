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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StaticRadioSwitchUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun pixels(tag: String): IntArray {
        val image = compose.onNodeWithTag(tag).captureToImage()
        return IntArray(image.width * image.height).also { image.readPixels(it) }
    }

    @Test
    fun theSwitchHasNoAnimatedIntermediateState() {
        var on by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.background(Color.White).padding(8.dp).testTag("frame")) {
                    FieldSwitch(checked = on, onCheckedChange = { on = it }, modifier = Modifier.testTag("switch"))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("switch").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        val off = pixels("frame")
        compose.mainClock.autoAdvance = false

        compose.onNodeWithTag("switch").performClick()
        compose.mainClock.advanceTimeByFrame()
        val firstFrame = pixels("frame")
        compose.mainClock.advanceTimeBy(16L)
        val secondFrame = pixels("frame")
        compose.mainClock.advanceTimeBy(1_000L)
        val settled = pixels("frame")
        assertTrue(on)
        assertFalse("the first frame after the tap must already show the thumb on the right", off.contentEquals(firstFrame))
        assertTrue("no frame between the tap and the settled switch may differ", firstFrame.contentEquals(secondFrame))
        assertTrue("no frame between the tap and the settled switch may differ", firstFrame.contentEquals(settled))

        compose.onNodeWithTag("switch").performClick()
        compose.mainClock.advanceTimeByFrame()
        val offFirst = pixels("frame")
        compose.mainClock.advanceTimeBy(1_000L)
        assertFalse(on)
        assertTrue(offFirst.contentEquals(pixels("frame")))
        assertTrue(offFirst.contentEquals(off))
    }

    @Test
    fun theRadioHasNoAnimatedIntermediateState() {
        var picked by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.background(Color.White).padding(8.dp).testTag("frame")) {
                    FieldRadioButton(selected = picked, onClick = { picked = !picked }, modifier = Modifier.testTag("radio"))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("radio").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        val empty = pixels("frame")
        compose.mainClock.autoAdvance = false

        compose.onNodeWithTag("radio").performClick()
        compose.mainClock.advanceTimeByFrame()
        val firstFrame = pixels("frame")
        compose.mainClock.advanceTimeBy(16L)
        val secondFrame = pixels("frame")
        compose.mainClock.advanceTimeBy(1_000L)
        val settled = pixels("frame")
        assertTrue(picked)
        assertFalse("the first frame after the tap must already show the dot", empty.contentEquals(firstFrame))
        assertTrue("no frame between the tap and the settled dot may differ", firstFrame.contentEquals(secondFrame))
        assertTrue("no frame between the tap and the settled dot may differ", firstFrame.contentEquals(settled))
    }
}
