package com.fieldrepository.app.questionnaire.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.fieldrepository.app.questionnaire.data.CoverageRecordings
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.ui.field

/**
 * A coverage column's head (0.0.6): the first name, truncated to the column; touch-and-hold (or
 * keyboard focus) lifts the full name, enlarged, over it, and a screen reader hears the full name.
 */
@Composable
fun CoverageName(fullName: String, width: Dp, modifier: Modifier = Modifier, tag: String = "coverage.name") {
    var lifted by remember { mutableStateOf(false) }
    val short = QuestionnaireCopy.firstName(fullName).ifBlank { fullName }
    Box(
        modifier
            .widthIn(max = width)
            .semantics { contentDescription = fullName }
            .onFocusChanged { lifted = it.isFocused }
            .focusable()
            .pointerInput(fullName) {
                detectTapGestures(
                    onPress = { tryAwaitRelease(); lifted = false },
                    onLongPress = { lifted = true },
                )
            }
            .testTag(tag)
    ) {
        Text(short, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.field.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (lifted) {
            val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
            Popup(popupPositionProvider = remember(gap) { OverAnchor(gap) }) {
                Surface(shape = RoundedCornerShape(10.dp), tonalElevation = 3.dp, shadowElevation = 6.dp, modifier = Modifier.testTag("$tag.full")) {
                    Text(
                        fullName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * One dot of the coverage grid. With takes behind it, a tap opens them; with none it is plain text
 * that takes no tap and draws no ripple (no dead click).
 */
@Composable
fun CoverageDot(glyph: String, color: Color, width: Dp, describe: String, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
    Text(
        glyph,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = modifier
            .widthIn(min = width, max = width)
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClickLabel = QuestionnaireCopy.s("coverage.openTake"), onClick = onOpen) else Modifier)
            .semantics { contentDescription = describe }
    )
}

/** Several takes behind one dot: a short list by date, researcher and length; one tap opens it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverageTakeChooser(takes: List<CoverageRecordings.Take>, onPick: (CoverageRecordings.Take) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("coverage.chooser")) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                QuestionnaireCopy.s("coverage.takes").replace("{count}", takes.size.toString()),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            takes.forEach { r ->
                Text(
                    CoverageRecordings.label(r),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(r) }
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                        .testTag("coverage.take.${r.recordingId}")
                )
                HorizontalDivider(color = MaterialTheme.field.hairline, modifier = Modifier.padding(start = 20.dp))
            }
        }
    }
}

/** Centred on its anchor, a little above it, kept inside the window. */
private class OverAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val above = anchorBounds.top - gap - popupContentSize.height
        return IntOffset(x, if (above >= 0) above else anchorBounds.bottom + gap)
    }
}
