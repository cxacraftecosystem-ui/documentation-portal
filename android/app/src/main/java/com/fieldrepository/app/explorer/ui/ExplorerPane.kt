package com.fieldrepository.app.explorer.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.ui.field
import androidx.compose.material3.MaterialTheme

object ExplorerPaneWidth {
    private const val PREFS = "explorer-pane"
    private const val KEY = "width-dp"
    const val DEFAULT = 340f
    const val MIN = 280f
    const val MAX_SHARE = 0.6f
    const val STEP = 40f

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(context: Context): Float? = runCatching { prefs(context).getFloat(KEY, -1f).takeIf { it > 0f } }.getOrNull()

    fun write(context: Context, dp: Float) {
        runCatching { prefs(context).edit().putFloat(KEY, dp).apply() }
    }

    fun clamp(dp: Float, screen: Float): Float = dp.coerceIn(MIN, maxOf(MIN, screen * MAX_SHARE))
}

internal object ExplorerPaneText {
    const val resize = "Resize folders panel"
    const val wider = "Widen folders panel"
    const val narrower = "Narrow folders panel"
    fun width(dp: Int) = "$dp dp wide"
}

@Composable
internal fun PaneDivider(width: Float, screen: Float, onWidth: (Float) -> Unit, onSettled: () -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val max = maxOf(ExplorerPaneWidth.MIN, screen * ExplorerPaneWidth.MAX_SHARE)
    var dragging by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(width) }
    val drag = rememberDraggableState { delta ->
        live = ExplorerPaneWidth.clamp(live + with(density) { delta.toDp().value }, screen)
        onWidth(live)
    }
    val line = MaterialTheme.field.hairline
    val grip = if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    fun step(by: Float): Boolean {
        val next = ExplorerPaneWidth.clamp(width + by, screen)
        if (next == width) return false
        onWidth(next)
        onSettled()
        return true
    }
    Box(
        modifier
            .width(48.dp)
            .fillMaxHeight()
            .draggable(
                state = drag,
                orientation = Orientation.Horizontal,
                onDragStarted = { live = width; dragging = true },
                onDragStopped = { dragging = false; onSettled() },
            )
            .semantics {
                contentDescription = ExplorerPaneText.resize
                stateDescription = ExplorerPaneText.width(width.toInt())
                progressBarRangeInfo = ProgressBarRangeInfo(width, ExplorerPaneWidth.MIN..max)
                setProgress { target -> step(target - width) }
                customActions = listOf(
                    CustomAccessibilityAction(ExplorerPaneText.wider) { step(ExplorerPaneWidth.STEP) },
                    CustomAccessibilityAction(ExplorerPaneText.narrower) { step(-ExplorerPaneWidth.STEP) },
                )
            }
            .testTag("explorer.pane.divider"),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(line))
        Box(Modifier.size(width = 6.dp, height = 40.dp).background(grip, CircleShape))
    }
}
