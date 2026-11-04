package com.fieldrepository.app.ui

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.CompletenessDto
import com.fieldrepository.app.data.CompletenessRules
import com.fieldrepository.app.data.RecordCompletenessStore
import com.fieldrepository.app.data.CompletenessFilter
import com.fieldrepository.app.data.CompletenessMissingDto
import com.fieldrepository.app.data.completenessAria
import com.fieldrepository.app.data.completenessSentence
import kotlinx.coroutines.delay

fun completenessChipAction(item: CompletenessMissingDto, canUpdate: Boolean, onUpdate: (String) -> Unit): (() -> Unit)? =
    if (canUpdate) ({ onUpdate(item.key) }) else null

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompletenessMeter(
    block: CompletenessDto,
    canUpdate: Boolean,
    onUpdate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CompletenessRing(percent = block.percent, size = 28.dp, stroke = 4.dp, showLabel = false, description = completenessAria(block))
            Text(
                completenessSentence(block),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!block.complete) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.missing.forEach { item ->
                    val action = completenessChipAction(item, canUpdate, onUpdate)
                    val tone = if (item.required) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    AssistChip(
                        onClick = { action?.invoke() },
                        enabled = action != null,
                        label = { Text(item.label, style = MaterialTheme.typography.labelLarge) },
                        colors = AssistChipDefaults.assistChipColors(labelColor = tone, disabledLabelColor = tone),
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompletenessFilterChips(value: CompletenessFilter, onChange: (CompletenessFilter) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CompletenessFilter.entries.forEach { option ->
            FilterChip(
                selected = option == value,
                onClick = { onChange(option) },
                label = { Text(option.label) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

object CompletenessFilterMemory {
    private const val PREFS = "record_completeness_filter"

    fun read(context: Context, mode: String): CompletenessFilter =
        CompletenessFilter.parse(runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(mode, null) }.getOrNull())

    fun write(context: Context, mode: String, value: CompletenessFilter) {
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(mode, value.name).apply() }
    }
}

data class CompletenessFocusRequest(val key: String, val nonce: Int = 0)

val LocalCompletenessFocus = compositionLocalOf<MutableState<CompletenessFocusRequest?>?> { null }

@Composable
fun ProvideCompletenessFocus(initial: String?, key: Any? = null, content: @Composable () -> Unit) {
    val state = remember(initial, key) { mutableStateOf(initial?.takeIf { it.isNotBlank() }?.let { CompletenessFocusRequest(it) }) }
    CompositionLocalProvider(LocalCompletenessFocus provides state, content = content)
}

fun MutableState<CompletenessFocusRequest?>.request(key: String) {
    value = CompletenessFocusRequest(key, (value?.nonce ?: 0) + 1)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompletenessAnchor(key: String, focus: FocusRequester? = null, content: @Composable ColumnScope.() -> Unit) {
    val channel = LocalCompletenessFocus.current
    val requester = remember { BringIntoViewRequester() }
    val wanted = channel?.value
    LaunchedEffect(wanted) {
        if (wanted?.key != key) return@LaunchedEffect
        delay(150)
        runCatching { requester.bringIntoView() }
        runCatching { focus?.requestFocus() }
    }
    Column(
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(requester),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
fun LiveCompletenessLine(block: CompletenessDto) {
    val channel = LocalCompletenessFocus.current
    CompletenessMeter(block = block, canUpdate = channel != null, onUpdate = { key -> channel?.request(key) })
}

@Composable
fun rememberCompletenessRules(): CompletenessRules? {
    val context = LocalContext.current.applicationContext
    return produceState(RecordCompletenessStore.peek(), context) {
        value = runCatching { RecordCompletenessStore.load(context) }.getOrNull() ?: value
    }.value
}

@Composable
fun rememberImageCount(uris: List<android.net.Uri>): Int {
    val resolver = LocalContext.current.contentResolver
    return remember(uris) { uris.count { uri -> runCatching { resolver.getType(uri) }.getOrNull()?.startsWith("image/") == true } }
}
