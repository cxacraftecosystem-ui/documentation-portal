package com.fieldrepository.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.data.CoverageCounts
import com.fieldrepository.app.data.CoverageResponse
import com.fieldrepository.app.data.CoverageUnit
import com.fieldrepository.app.data.CoverageUnitRecords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two hand-drawn maps on a device: a two-finger pinch zooms, Reset appears and resets, one finger
 * at rest still scrolls the page, Places | Coverage switches, and a tap on a district opens its sheet.
 */
@RunWith(AndroidJUnit4::class)
class CoverageMapUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val outline by lazy { loadIndiaGeometry(context) }
    private val shapes by lazy { loadDistrictShapes(context.resources) }

    @Test
    fun aPinchZoomsAndResetGoesBack() {
        val zoom = MapZoomState()
        compose.setContent {
            MaterialTheme {
                CoverageMapCanvas(
                    outline = outline,
                    stateBorders = null,
                    shapes = shapes,
                    fills = emptyMap(),
                    breaks = emptyList(),
                    noWorkshop = emptySet(),
                    markers = emptyList(),
                    selectedLgd = null,
                    focusStateLgd = null,
                    onSelectDistrict = {},
                    zoom = zoom
                )
            }
        }
        compose.onNodeWithContentDescription("Reset zoom").assertDoesNotExist()
        compose.onNodeWithTag("coverage-map").performTouchInput {
            pinch(
                start0 = center - Offset(20f, 0f),
                end0 = center - Offset(width / 3f, 0f),
                start1 = center + Offset(20f, 0f),
                end1 = center + Offset(width / 3f, 0f)
            )
        }
        compose.waitForIdle()
        assertTrue("scale ${zoom.scale}", zoom.scale > 1.5f)
        compose.onNodeWithContentDescription("Reset zoom").performClick()
        compose.waitForIdle()
        assertEquals(1f, zoom.scale, 0.001f)
        compose.onNodeWithContentDescription("Reset zoom").assertDoesNotExist()
    }

    @Test
    fun oneFingerAtRestScrollsThePage() {
        val scroll = androidx.compose.foundation.ScrollState(0)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
                    CoverageMapCanvas(
                        outline = outline,
                        stateBorders = null,
                        shapes = shapes,
                        fills = emptyMap(),
                        breaks = emptyList(),
                        noWorkshop = emptySet(),
                        markers = emptyList(),
                        selectedLgd = null,
                        focusStateLgd = null,
                        onSelectDistrict = {}
                    )
                    Spacer(Modifier.height(2000.dp))
                }
            }
        }
        compose.onNodeWithTag("coverage-map").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue("page scroll ${scroll.value}", scroll.value > 0)
    }

    @Test
    fun placesAndCoverageSwitch() {
        var view by mutableStateOf(MAP_VIEW_PLACES)
        compose.setContent {
            MaterialTheme { MapViewSwitch(view = view, onChange = { view = it }) }
        }
        compose.onNodeWithTag("map-view-places").assertIsSelected()
        compose.onNodeWithText("Coverage").performClick()
        compose.waitForIdle()
        assertEquals(MAP_VIEW_COVERAGE, view)
        compose.onNodeWithTag("map-view-coverage").assertIsSelected()
    }

    @Test
    fun tappingADistrictOpensItsSheet() {
        val target = shapes.first { it.contains(it.centroid) }
        val response = CoverageResponse(
            units = listOf(
                CoverageUnit(
                    stateLgd = target.stateLgd,
                    districtLgd = target.lgd,
                    state = "Test State",
                    district = "Test District",
                    counts = CoverageCounts(workshops = 2, artisans = 5)
                )
            )
        )
        var width = 0f
        compose.setContent {
            MaterialTheme {
                val scroll = rememberScrollState()
                Column(Modifier.verticalScroll(scroll)) {
                    CoverageScreen(
                        fetchCoverage = { response },
                        fetchUnitRecords = { _, _, _, _, _ -> CoverageUnitRecords() },
                        errorMessage = { _, fallback -> fallback },
                        onError = {},
                        onOpenRecord = null
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = 20_000) {
            runCatching { compose.onNodeWithTag("coverage-map").fetchSemanticsNode() }.isSuccess
        }
        compose.onNodeWithTag("coverage-map").performTouchInput {
            width = this.width.toFloat()
            val aspect = outline.aspect
            val pad = 8.dp.toPx()
            val frame = CoverageFrame(Size(width, width * aspect), Rect(0f, 0f, 1f, aspect), pad)
            click(frame.toFrame(target.centroid))
        }
        compose.waitForIdle()
        compose.onNodeWithText("Show records").assertExists()
    }
}
