package com.fieldrepository.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * `AppToggle` — the ONE on/off control on the handset; the web's `Toggle` (components/ui/Toggle.tsx).
 *
 * Owner's ruling (2026-10-17): every setting that is on or off uses this — never a bare "On"/"Off"
 * line, never a Checkbox standing in for a setting. (A Checkbox is still right for PICKING rows in a
 * list: that is a selection, not a setting.)
 *
 * A full-width row: the label, an optional plain-words [description] under it, and a Material 3
 * Switch at the end. The WHOLE ROW toggles (`Modifier.toggleable` with `Role.Switch`), so the target
 * is the row and never only the 52x32 switch; the row is at least 56dp tall (48dp is the floor). The
 * Switch itself takes no clicks of its own (`onCheckedChange = null`), so TalkBack hears ONE node:
 * "<label>, <description>, switch, on/off".
 *
 * [busy] keeps the switch where it is and refuses taps — for a change waiting on the server. While busy,
 * TalkBack hears [busyLabel] as the state ("Working" unless the caller names the wait: two-step
 * verification passes "Setting up" only while its setup is open, and stays Off until the code is verified). Off is an outlined track with
 * an ink thumb, on is a filled purple track with a white thumb: the state reads without colour.
 */
@Composable
fun AppToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyLabel: String = "Working"
) {
    val interactive = enabled && !busy
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = checked,
                enabled = interactive,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .semantics { if (busy) stateDescription = busyLabel }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.field.placeholder,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge
            )
            if (!description.isNullOrBlank()) {
                Text(description, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodyMedium, lineHeight = 18.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = interactive || busy,
            colors = SwitchDefaults.colors(
                checkedTrackColor = FieldPalette.Purple700,
                checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                checkedBorderColor = FieldPalette.Purple700,
                uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                uncheckedThumbColor = MaterialTheme.field.muted,
                uncheckedBorderColor = MaterialTheme.field.muted
            )
        )
    }
}
