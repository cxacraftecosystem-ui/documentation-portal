package com.fieldrepository.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fieldrepository.app.data.ScanAction
import com.fieldrepository.app.data.ScanCopy
import com.fieldrepository.app.data.ScanOutcome
import com.fieldrepository.app.data.ScanOutcomeKind
import kotlinx.coroutines.launch

/**
 * The top-bar scanner (REQUIREMENTS_2026-10-20 §6.2): the live camera and a picked picture (the
 * [scanner] slot, each app's own reader), the typed code, then `data/ScanDispatch.kt`, then one
 * result card with its button. IDENTICAL in both apps apart from the `package` line.
 *
 * It never navigates on its own: the person presses Open, Redeem, Join, Add or Ask to join, and
 * [onAction] does that act. Nothing scanned is written into the typed box, kept or echoed: not a join
 * card's secret, not a payment or authenticator link, not an identity QR. The box holds only what
 * the person typed, and a scan clears it.
 */

/** The outcomes drawn as an answer rather than as a refusal. */
fun scanOutcomeIsPositive(kind: ScanOutcomeKind): Boolean = when (kind) {
    ScanOutcomeKind.OPEN, ScanOutcomeKind.REDEEM_JOIN_CARD, ScanOutcomeKind.JOIN_SESSION,
    ScanOutcomeKind.ADD_FROM_PEER, ScanOutcomeKind.JOIN_WORKSHOP, ScanOutcomeKind.POINTER -> true
    ScanOutcomeKind.REFUSED, ScanOutcomeKind.NOT_FOUND, ScanOutcomeKind.OFFLINE -> false
}

/**
 * The scanner's body, for the sheet, a full-screen host or a panel.
 *
 * @param dispatch `dispatchScan` over the app's services.
 * @param onAction runs a pressed action; returns the next outcome to show, or null when it left the
 *   sheet (navigated), after which [onDone] runs.
 * @param scanner the app's camera and picture controls: `(enabled, onText, onRefusal)`.
 */
@Composable
fun TopBarScanContent(
    dispatch: suspend (String) -> ScanOutcome,
    onAction: suspend (ScanAction) -> ScanOutcome?,
    onDone: () -> Unit,
    scanner: @Composable (enabled: Boolean, onText: (String) -> Unit, onRefusal: (String) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<ScanOutcome?>(null) }

    fun look(raw: String) {
        if (raw.isBlank() || busy) return
        busy = true
        problem = null
        outcome = null
        scope.launch {
            try {
                outcome = dispatch(raw)
            } finally {
                busy = false
            }
        }
    }

    fun press(action: ScanAction) {
        if (busy) return
        busy = true
        scope.launch {
            val next = try {
                onAction(action)
            } finally {
                busy = false
            }
            if (next == null) onDone() else outcome = next
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        scanner(
            !busy,
            { text ->
                typed = ""
                look(text)
            },
            { message ->
                outcome = null
                problem = message
            },
        )
        OutlinedTextField(
            value = typed,
            onValueChange = {
                typed = it
                outcome = null
                problem = null
            },
            label = { Text(ScanCopy.TYPE_THE_CODE) },
            singleLine = true,
            enabled = !busy,
            leadingIcon = { Icon(Icons.Filled.Keyboard, contentDescription = null, modifier = Modifier.size(18.dp)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { look(typed) }),
            modifier = Modifier.fillMaxWidth().testTag("scan-typed"),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { look(typed) },
                enabled = typed.isNotBlank() && !busy,
                modifier = Modifier.heightIn(min = 48.dp).weight(1f).testTag("scan-lookup"),
            ) { Text(ScanCopy.LOOK_UP) }
            if (busy) FieldCircularProgressIndicator(modifier = Modifier.size(20.dp))
        }
        problem?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        outcome?.let { answer ->
            val positive = scanOutcomeIsPositive(answer.kind)
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("scan-outcome")
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .background(
                        if (positive) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    answer.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (positive) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (positive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer,
                )
                answer.detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                answer.action?.let { action ->
                    Button(
                        onClick = { press(action) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("scan-action"),
                    ) { Text(action.label) }
                }
            }
        }
    }
}

/** The full-screen sheet the header's scan button opens. */
@Composable
fun TopBarScanSheet(
    dispatch: suspend (String) -> ScanOutcome,
    onAction: suspend (ScanAction) -> ScanOutcome?,
    onDismiss: () -> Unit,
    scanner: @Composable (enabled: Boolean, onText: (String) -> Unit, onRefusal: (String) -> Unit) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(modifier = Modifier.fillMaxSize().testTag("top-bar-scan-sheet"), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        ScanCopy.SCAN_A_CODE,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 10.dp).weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
                TopBarScanContent(dispatch = dispatch, onAction = onAction, onDone = onDismiss, scanner = scanner)
            }
        }
    }
}
