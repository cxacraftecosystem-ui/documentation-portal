package com.fieldrepository.app.ui.trace

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.ui.LocalAppPreferences
import com.fieldrepository.app.ui.Text
import com.fieldrepository.app.ui.field

@Composable
internal fun TracePanelLabel(text: String) {
    Text(text, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
}

@Composable
internal fun TracePanelChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    isChoice: Boolean = true,
    onClick: () -> Unit,
) {
    val announce = if (isChoice) {
        Modifier.semantics { stateDescription = if (selected) "Selected" else "Not selected" }
    } else {
        Modifier
    }
    val shape = Modifier
        .heightIn(min = 40.dp)
        .then(announce)
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = shape) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = shape) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun TracePanelNote(warning: Boolean, text: String, polite: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (warning) MaterialTheme.field.warningContainer else MaterialTheme.field.surface50,
                RoundedCornerShape(8.dp),
            )
            .border(
                1.dp,
                if (warning) MaterialTheme.field.warning else MaterialTheme.field.hairline,
                RoundedCornerShape(8.dp),
            )
            .padding(8.dp)
            .then(if (polite) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = if (warning) MaterialTheme.field.onWarningContainer else MaterialTheme.field.muted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text,
            color = if (warning) MaterialTheme.field.onWarningContainer else MaterialTheme.field.body,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

internal const val TRACE_PANEL_COLLAPSE_WORD = "Close"

@Composable
internal fun TracePanelDisclosureHeader(
    icon: ImageVector,
    title: String,
    expanded: Boolean,
    toggleEnabled: Boolean,
    expandAction: String,
    collapseAction: String,
    onToggle: () -> Unit,
) {
    val reduceMotion = LocalAppPreferences.current.reducedMotion
    val turn by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = 180),
        label = "traceCardChevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { stateDescription = traceDisclosureState(expanded) }
            .clickable(
                enabled = toggleEnabled,
                onClickLabel = if (expanded) collapseAction else expandAction,
                role = Role.Button,
                onClick = onToggle,
            )
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.field.muted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (expanded) TracePanelCollapseButton(prominent = false, title = title, onClick = onToggle)
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.field.muted,
            modifier = Modifier
                .size(20.dp)
                .rotate(turn),
        )
    }
}

@Composable
internal fun TracePanelCollapseButton(prominent: Boolean, title: String, onClick: () -> Unit) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            if (prominent) "$TRACE_PANEL_COLLAPSE_WORD “$title”" else TRACE_PANEL_COLLAPSE_WORD,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (prominent) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            content = content,
        )
    } else {
        TextButton(
            onClick = onClick,
            modifier = Modifier.heightIn(min = 48.dp),
            content = content,
        )
    }
}

internal fun tracePanelReplaceWarning(current: String?): String? {
    val trimmed = current?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return "“$trimmed” is attached here now. This replaces it."
}
