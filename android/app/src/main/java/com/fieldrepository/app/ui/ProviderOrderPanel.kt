package com.fieldrepository.app.ui

import com.fieldrepository.app.explorer.ui.ExplorerIdentity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private enum class BannerTone { WARN, INFO }

@Stable
class ProviderOrderState internal constructor(
    private val api: SttProviderApi,
    private val scope: CoroutineScope
) {
    var providers by mutableStateOf(BUILT_IN_STT_PROVIDERS)
        private set

    var saved by mutableStateOf(BUILT_IN_STT_PROVIDERS)
        private set

    var loading by mutableStateOf(true)
        private set

    var saving by mutableStateOf(false)
        private set

    var testing by mutableStateOf<String?>(null)
        private set

    var trouble by mutableStateOf<SttTrouble?>(null)
        private set

    var actionTrouble by mutableStateOf<SttTrouble?>(null)
        private set

    var notice by mutableStateOf<String?>(null)
        private set

    var warning by mutableStateOf<String?>(null)
        private set

    var announcement by mutableStateOf("")
        private set

    val live: Boolean get() = trouble == null

    val dirty: Boolean get() = !sameSttOrder(providers, saved)

    private fun apply(state: SttProviderOrderDto) {
        val rows = state.providers.ifEmpty { BUILT_IN_STT_PROVIDERS }
        providers = rows
        saved = rows
        trouble = null
    }

    private fun mergeVerdicts(state: SttProviderOrderDto) {
        val fresh = state.providers.associateBy { it.id }
        providers = providers.map { fresh[it.id] ?: it }
        saved = saved.map { fresh[it.id] ?: it }
        trouble = null
    }

    fun load() {
        scope.launch {
            loading = true
            actionTrouble = null
            runCatching { api.providerOrder() }
                .onSuccess { apply(it) }
                .onFailure { error ->
                    providers = BUILT_IN_STT_PROVIDERS
                    saved = BUILT_IN_STT_PROVIDERS
                    trouble = describeSttTrouble(error, "load")
                }
            loading = false
        }
    }

    fun move(from: Int, to: Int) {
        val list = providers
        if (to < 0 || to >= list.size || from == to) return
        if (!sttMovePermitted(list, from, to)) {
            val blocked = list[from]
            val reason = blocked.frozenReason ?: "It has not passed a provider test."
            announcement = "${blocked.name} cannot move there yet. $reason"
            warning = "${blocked.name} stays below tested engines. $reason"
            return
        }
        val moved = list[from]
        providers = sttReorder(list, from, to)
        notice = null
        warning = null
        announcement = "${moved.name} moved to position ${to + 1} of ${list.size}."
    }

    fun save() {
        scope.launch {
            saving = true
            actionTrouble = null
            notice = null
            warning = null
            runCatching { api.setProviderOrder(SttProviderOrderBody(providers.map { it.id })) }
                .onSuccess { updated ->
                    apply(updated)
                    val note = updated.normalisedNote
                    if (updated.normalised && note != null) {
                        warning = note
                        announcement = "Saved with changes. $note"
                    } else {
                        notice = "Saved."
                        announcement = "Order saved."
                    }
                }
                .onFailure { actionTrouble = describeSttTrouble(it, "save") }
            saving = false
        }
    }

    fun test(provider: SttProviderDto) {
        scope.launch {
            testing = provider.id
            actionTrouble = null
            notice = null
            warning = null
            runCatching { api.testProvider(provider.id) }
                .onSuccess { updated ->
                    mergeVerdicts(updated)
                    val after = updated.providers.firstOrNull { it.id == provider.id }
                    if (after?.keyState == STT_KEY_PASSING) {
                        notice = "${provider.name} works — rank it anywhere."
                        announcement = "${provider.name} passed its test and is now rankable."
                    } else {
                        val why = after?.testError ?: "the provider refused the key"
                        warning = "${provider.name} failed: $why."
                        announcement = "${provider.name} failed its test."
                    }
                }
                .onFailure { actionTrouble = describeSttTrouble(it, "test") }
            testing = null
        }
    }

    fun reset() {
        providers = saved
        notice = null
        warning = null
        announcement = "Order reset to the last saved ranking."
    }

    fun dismissNotice() {
        notice = null
    }

    fun dismissWarning() {
        warning = null
    }
}

@Composable
fun rememberProviderOrderState(api: SttProviderApi): ProviderOrderState {
    val scope = rememberCoroutineScope()
    return remember(ExplorerIdentity(api), scope) { ProviderOrderState(api, scope) }
}

@Composable
fun ProviderOrderPanel(
    modifier: Modifier = Modifier,
    api: SttProviderApi = SttProviderClient.get(LocalContext.current),
    onMessage: (String) -> Unit = {},
    onError: (String) -> Unit = {}
) {
    val state = rememberProviderOrderState(api)
    val tokens = MaterialTheme.field

    LaunchedEffect(state) { state.load() }
    LaunchedEffect(state.notice) { state.notice?.let(onMessage) }
    LaunchedEffect(state.actionTrouble) { state.actionTrouble?.let { onError(it.headline) } }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Transcription provider order",
                display = true,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Top is tried first, then the next. Test an engine to rank it. Applies to everyone.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.muted
            )

            state.trouble?.let { trouble ->
                ProviderTroubleCard(trouble = trouble, busy = state.loading, onRetry = state::load)
                Text(
                    "Default order — loading the saved one…",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.muted
                )
            }

            if (state.loading && state.trouble == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FieldCircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Loading the current order…",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.muted
                    )
                }
            } else {
                Column(
                    modifier = Modifier.alpha(if (state.live) 1f else 0.6f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    state.providers.forEachIndexed { index, provider ->
                        ProviderRow(provider = provider, index = index, state = state)
                    }
                }
            }

            if (state.announcement.isNotBlank()) {
                Text(
                    state.announcement,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.muted
                )
            }

            providerBanners(state).forEach { (tone, body) ->
                ProviderBanner(tone = tone, text = body)
            }

            state.actionTrouble?.let { trouble ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        trouble.headline,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        trouble.advice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            state.warning?.let {
                ProviderNoticeCard(
                    text = it,
                    container = tokens.warningContainer,
                    content = tokens.onWarningContainer,
                    onDismiss = state::dismissWarning
                )
            }
            state.notice?.let {
                ProviderNoticeCard(
                    text = it,
                    container = tokens.successContainer,
                    content = tokens.onSuccessContainer,
                    onDismiss = state::dismissNotice
                )
            }

            ActionRow {
                Button(
                    onClick = state::save,
                    enabled = state.live && state.dirty && !state.saving
                ) {
                    if (state.saving) {
                        FieldCircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (state.saving) "Saving…" else "Save order")
                }
                if (state.dirty && state.live) {
                    TextButton(onClick = state::reset) { Text("Reset") }
                }
            }

            val runnable = state.saved.filter { it.configured }
            if (state.live && !state.dirty && !state.loading && runnable.isNotEmpty()) {
                Text(
                    "Currently transcribing with ${runnable.joinToString(" → ") { it.name }}.",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.muted
                )
            }
        }
    }
}

@Composable
private fun ProviderRow(provider: SttProviderDto, index: Int, state: ProviderOrderState) {
    val tokens = MaterialTheme.field
    val list = state.providers
    val live = state.live
    val upBlocked = index > 0 && !sttMovePermitted(list, index, index - 1)
    val downBlocked = index < list.size - 1 && !sttMovePermitted(list, index, index + 1)
    val busy = state.testing == provider.id

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.surface50, MaterialTheme.shapes.medium)
            .border(1.dp, tokens.hairline, MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(
                        if (provider.rankable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        CircleShape
                    )
                    .border(
                        1.dp,
                        if (provider.rankable) MaterialTheme.colorScheme.primary else tokens.hairline,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (provider.rankable) MaterialTheme.colorScheme.onPrimary else tokens.muted
                )
            }
            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        provider.name,
                        display = true,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (index == 0 && provider.configured && live) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Tried first",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.shapes.extraSmall
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (live) {
                    ProviderKeyStateLine(provider)
                } else {
                    Text(
                        "Key state unknown",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.muted
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ProviderMoveArrow(
                    provider = provider,
                    up = true,
                    atEnd = index == 0,
                    blocked = upBlocked,
                    live = live,
                    onMove = { state.move(index, index - 1) }
                )
                ProviderMoveArrow(
                    provider = provider,
                    up = false,
                    atEnd = index == list.size - 1,
                    blocked = downBlocked,
                    live = live,
                    onMove = { state.move(index, index + 1) }
                )
            }
        }

        Text(
            STT_PROVIDER_BLURBS[provider.id] ?: provider.keyLabel,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.muted
        )

        if (live) {
            provider.frozenReason?.takeIf { it.isNotBlank() }?.let { reason ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                        .padding(8.dp)
                ) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = tokens.muted,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(reason, style = MaterialTheme.typography.labelSmall, color = tokens.body)
                }
            }
        }

        ActionRow {
            OutlinedButton(
                onClick = { state.test(provider) },
                enabled = live && state.testing == null
            ) {
                if (busy) {
                    FieldCircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (busy) "Testing ${provider.name}…" else "Test ${provider.name}", maxLines = 1)
            }
        }
    }
}

@Composable
private fun ProviderMoveArrow(
    provider: SttProviderDto,
    up: Boolean,
    atEnd: Boolean,
    blocked: Boolean,
    live: Boolean,
    onMove: () -> Unit
) {
    val direction = if (up) "up" else "down"
    val label = if (blocked) {
        "Move ${provider.name} $direction — test it first"
    } else {
        "Move ${provider.name} $direction"
    }
    IconButton(
        onClick = onMove,
        enabled = live && !atEnd,
        modifier = Modifier.size(40.dp)
    ) {
        Icon(
            if (up) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = label,
            tint = if (blocked) MaterialTheme.field.placeholder else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun ProviderKeyStateLine(provider: SttProviderDto) {
    val tokens = MaterialTheme.field
    val icon: ImageVector
    val tint: Color
    val label: String
    when (provider.keyState) {
        STT_KEY_PASSING -> {
            icon = Icons.Filled.CheckCircle; tint = tokens.success; label = "Tested and working"
        }
        STT_KEY_FAILING -> {
            icon = Icons.Filled.Cancel; tint = MaterialTheme.colorScheme.error; label = "Test failed"
        }
        STT_KEY_UNTESTED -> {
            icon = Icons.AutoMirrored.Filled.HelpOutline; tint = tokens.warning; label = "Key present, never tested"
        }
        else -> {
            icon = Icons.Filled.RadioButtonUnchecked; tint = tokens.muted; label = "No API key"
        }
    }
    val ago = sttTestedAgo(provider.testedAt)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            if (ago.isBlank()) label else "$label · $ago",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = tint
        )
    }
}

@Composable
private fun ProviderTroubleCard(trouble: SttTrouble, busy: Boolean, onRetry: () -> Unit) {
    val tokens = MaterialTheme.field
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.warningContainer, MaterialTheme.shapes.small)
            .border(1.dp, tokens.warning, MaterialTheme.shapes.small)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = tokens.onWarningContainer,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                trouble.headline,
                style = MaterialTheme.typography.labelLarge,
                color = tokens.onWarningContainer
            )
        }
        Text(
            trouble.advice,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.onWarningContainer
        )
        if (trouble.retryable) {
            ActionRow {
                OutlinedButton(onClick = onRetry, enabled = !busy) {
                    if (busy) {
                        FieldCircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Try again")
                }
            }
        }
    }
}

@Composable
private fun ProviderBanner(tone: BannerTone, text: String) {
    val tokens = MaterialTheme.field
    val container = if (tone == BannerTone.WARN) tokens.warningContainer else tokens.surface100
    val content = if (tone == BannerTone.WARN) tokens.onWarningContainer else tokens.body
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(container, MaterialTheme.shapes.small)
            .padding(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = content)
    }
}

@Composable
private fun ProviderNoticeCard(text: String, container: Color, content: Color, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(container, MaterialTheme.shapes.small)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = content,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onDismiss) {
            Text("Dismiss", style = MaterialTheme.typography.labelMedium, color = content)
        }
    }
}

private fun providerBanners(state: ProviderOrderState): List<Pair<BannerTone, String>> {
    if (!state.live || state.loading) return emptyList()
    val saved = state.saved
    val out = mutableListOf<Pair<BannerTone, String>>()
    val runnable = saved.filter { it.configured }
    val verified = saved.filter { it.rankable }

    if (runnable.isEmpty()) {
        out += BannerTone.WARN to (
            "No engine has a key, so nothing can be transcribed yet."
            )
    } else if (verified.isEmpty()) {
        out += BannerTone.INFO to (
            "Nothing tested yet. Test the engine you want first."
            )
    } else if (saved.firstOrNull()?.configured == false) {
        out += BannerTone.WARN to (
            "${saved.first().name} has no key, so ${runnable.first().name} is used. Add the ${saved.first().keyLabel} key."
            )
    }

    if (sttOrderViolations(saved) > 0) {
        val stragglers = saved.filterNot { it.rankable }
        out += BannerTone.WARN to (
            "Untested ${joinSttNames(stragglers.map { it.name })} will move below ${joinSttNames(verified.map { it.name })} on the next save."
            )
    }

    val failing = saved.filter { it.keyState == STT_KEY_FAILING }
    if (failing.isNotEmpty()) {
        out += BannerTone.WARN to (
            "${joinSttNames(failing.map { it.name })} keep${if (failing.size == 1) "s" else ""} failing and slows every job. Replace or remove the key."
            )
    }
    return out
}
