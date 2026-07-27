package com.fieldrepository.app.ui

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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * The transcription provider ladder on a phone — ADMIN (50) and above.
 *
 * Mirrors `frontend/components/settings/ProviderOrderPanel.tsx` in behaviour and wording; the
 * transport, the four key states and the freeze arithmetic live in TranscriptionProviders.kt. Three
 * engines can transcribe field audio and they are not interchangeable: ElevenLabs is the most
 * accurate on Indian-language speech, Deepgram is the fastest and cheapest, Whisper is the fallback
 * that always works. Which one is tried FIRST is a judgement the people running the workshops make.
 *
 * WHY THIS SITS ON THE API KEYS SCREEN AND NOT ON ONE OF ITS OWN
 * -------------------------------------------------------------
 * The two halves are gated differently — the ranking is `require_admin`, every `/secrets` route is
 * `require_master_admin` — but they are about the same three providers, and a phone with two
 * near-identical entries in the admin hub ("API keys" and "Provider order") makes the reader do the
 * disambiguation the app should have done. So it is one screen with two stacked sections, ranking
 * first, exactly as the web page orders them, and the gate is DISCOVERED rather than asserted: the
 * key list answers 403 for an ordinary admin and renders the "master admin only" card in its place,
 * while this panel above it stays fully live. An admin therefore gets the control they are entitled
 * to and nothing they are not, without the app having to be told which of the two they are.
 *
 * REORDERING: ARROWS, NOT DRAG
 * ----------------------------
 * Material3 ships no reorderable list, so a drag here would be hand-rolled — and this panel renders
 * inside the admin hub's `verticalScroll`, which owns the vertical gesture. A long-press drag would
 * have to intercept that axis, then re-implement edge auto-scroll on a scroll state this composable
 * does not own; get it slightly wrong and the page scrolls while the user believes they are dragging
 * a row, which is worse than having no drag at all. Against that: three rows, so any order is at
 * most two taps from any other, and a tap target is reliable on a handset held one-handed in the
 * field, where a drag on a 6dp gap is not. Arrows are the primary path on the web too — they exist
 * there because drag is unreachable by keyboard — so this is the same control, not a lesser one.
 *
 * The one place the freeze needs care: a move refused by the freeze keeps its button ENABLED and
 * announces the reason when tapped. `enabled = false` in Compose also removes the control from
 * TalkBack's actionable set, which would hide the explanation from the person most likely to need
 * it. Only the true ends of the list are really disabled, because "you are already at the top" is
 * evident from the position and needs no sentence.
 */

/** Which tone a banner carries. Warnings are amber; the rest are the tinted neutral panel. */
private enum class BannerTone { WARN, INFO }

/**
 * State + actions for [ProviderOrderPanel]. Every action is fire-and-forget on the composition's own
 * scope, so leaving the screen cancels whatever was in flight.
 */
@Stable
class ProviderOrderState internal constructor(
    private val api: SttProviderApi,
    private val scope: CoroutineScope
) {
    /** The order on screen, which is the saved one until the admin moves something. */
    var providers by mutableStateOf(BUILT_IN_STT_PROVIDERS)
        private set

    /** The last order the server confirmed. Reset goes back to this; Save compares against it. */
    var saved by mutableStateOf(BUILT_IN_STT_PROVIDERS)
        private set

    var loading by mutableStateOf(true)
        private set

    var saving by mutableStateOf(false)
        private set

    /** The provider whose test is running, or null. */
    var testing by mutableStateOf<String?>(null)
        private set

    /** Set while the panel is showing the built-in default because the server could not be asked. */
    var trouble by mutableStateOf<SttTrouble?>(null)
        private set

    /** A failure that did NOT cost us the live data — a save or a test that was refused. */
    var actionTrouble by mutableStateOf<SttTrouble?>(null)
        private set

    var notice by mutableStateOf<String?>(null)
        private set

    var warning by mutableStateOf<String?>(null)
        private set

    /** The one-line "what just happened", spoken by TalkBack and readable by everyone else. */
    var announcement by mutableStateOf("")
        private set

    /** False when the last load failed: the list is drawn, but nothing on it does anything. */
    val live: Boolean get() = trouble == null

    val dirty: Boolean get() = !sameSttOrder(providers, saved)

    private fun apply(state: SttProviderOrderDto) {
        val rows = state.providers.ifEmpty { BUILT_IN_STT_PROVIDERS }
        providers = rows
        saved = rows
        trouble = null
    }

    /**
     * Fold a fresh verdict into the rows WITHOUT touching the order on screen.
     *
     * Testing a provider is not a reason to throw away a reorder the admin has not saved yet, and
     * replacing the list wholesale with the server's stored order would do exactly that. Only the
     * per-engine facts are taken; positions stay where the admin left them.
     */
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
            // Refusing silently reads as a broken button. Say which engine is stuck, and why.
            val blocked = list[from]
            val reason = blocked.frozenReason ?: "It has not passed a provider test."
            announcement = "${blocked.name} cannot move there yet. $reason"
            warning = "${blocked.name} stays below the engines that have passed a test. $reason"
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
                    // The server applies the same freeze on write, because a rule only the client
                    // enforces is a suggestion. When it had to move something it says so — surfaced
                    // here rather than swallowed, or the admin's list would silently disagree with
                    // the one the next recording is transcribed against.
                    if (updated.normalised && note != null) {
                        warning = note
                        announcement = "Saved with changes. $note"
                    } else {
                        notice = "Saved. The next transcription job uses this order — nothing needs restarting."
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
                        // The thaw is the point, and it has already happened in the rows above by
                        // the time this line is read — no reload, no save.
                        notice = "${provider.name} answered. It is verified now and can be ranked wherever you like."
                        announcement = "${provider.name} passed its test and is now rankable."
                    } else {
                        val why = after?.testError ?: "the provider refused the key"
                        warning = "${provider.name} did not pass: $why. It stays below the engines that did."
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

/** Remembers a [ProviderOrderState] bound to this composition's scope. */
@Composable
fun rememberProviderOrderState(api: SttProviderApi): ProviderOrderState {
    val scope = rememberCoroutineScope()
    return remember(api, scope) { ProviderOrderState(api, scope) }
}

/**
 * The ranking panel. Renders into whatever scrolling parent hosts it and never scrolls itself — the
 * admin hub already owns a `verticalScroll`, and a nested one is measured with an infinite height
 * budget, which throws "Vertical viewport was given infinite maximum height" and takes the screen
 * down before it can draw.
 *
 * [onMessage] and [onError] mirror what the panel already shows inline, for a host that also wants a
 * snackbar. A load failure is deliberately NOT sent to [onError]: it is a state this panel explains
 * at length in place, and a snackbar would reduce that explanation back to the one-line "Not Found"
 * this whole treatment exists to replace.
 */
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
                "The engine at the top is tried first; if it fails, or has no API key, the next one down " +
                    "takes the job. An engine can only be ranked once it has passed a test — tap Test to ask " +
                    "the provider whether its key works. This applies to everyone, not just this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.muted
            )

            // A failed load does not empty the screen. It explains itself and leaves the list visible.
            state.trouble?.let { trouble ->
                ProviderTroubleCard(trouble = trouble, busy = state.loading, onRetry = state::load)
                Text(
                    "Showing the app's built-in default order. This is not the live ranking, and nothing " +
                        "below will do anything until the panel can reach the server.",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.muted
                )
            }

            if (state.loading && state.trouble == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Loading the current order…",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.muted
                    )
                }
            } else {
                // Inert rather than absent: an admin who cannot change the order can still read it.
                // Every control inside is separately disabled, which is what carries the state to
                // TalkBack — dimming a container means nothing to a screen reader.
                Column(
                    modifier = Modifier.alpha(if (state.live) 1f else 0.6f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    state.providers.forEachIndexed { index, provider ->
                        ProviderRow(provider = provider, index = index, state = state)
                    }
                }
            }

            // Every reorder — and every refusal — is announced, because a TalkBack user tapping an
            // arrow otherwise gets no confirmation that anything happened at all. Visible as well as
            // spoken: on a phone the moved row can be off screen, so the sighted user needs it too.
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
                    Text(
                        trouble.technical,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = state::save,
                    enabled = state.live && state.dirty && !state.saving
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(
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

// ---------------------------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------------------------

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
            // Purple only for an engine that has earned its place; an unproven one gets the neutral
            // ring, so the ladder can be read at a glance without reading a word of it.
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
                        style = MaterialTheme.typography.titleSmall,
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

        // The freeze has to be readable ON THE ROW. A control that refuses without saying why is
        // indistinguishable from one that is broken, and this reason is also the fix.
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

        OutlinedButton(
            onClick = { state.test(provider) },
            enabled = live && state.testing == null
        ) {
            if (busy) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(6.dp))
            // The provider's name is IN the label rather than in a separate description, so TalkBack
            // reads "Test ElevenLabs, button" once instead of reading a description and then the
            // word "Test" after it.
            Text(if (busy) "Testing ${provider.name}…" else "Test ${provider.name}", maxLines = 1)
        }
    }
}

/** One reorder arrow. */
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
        "Move ${provider.name} $direction — not allowed until it passes a provider test"
    } else {
        // Just the direction. Naming the destination ("up to position 0" on the first row) reads as
        // nonsense; where it actually landed is announced afterwards instead.
        "Move ${provider.name} $direction"
    }
    IconButton(
        onClick = onMove,
        // A move refused by the FREEZE stays enabled deliberately — see the file header. Tapping it
        // announces the reason. Only the ends of the list, and a dead panel, are truly disabled.
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

/** The key-state line: which of the four states this engine is in, and when it was last asked. */
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
        Text(
            trouble.technical,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = tokens.onWarningContainer
        )
        if (trouble.retryable) {
            OutlinedButton(onClick = onRetry, enabled = !busy) {
                if (busy) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("Try again")
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

/**
 * The standing warnings about the SAVED ranking, in the web panel's order.
 *
 * These are about what the pipeline will actually do, which is not always what the list says — an
 * unconfigured leader, an order that went illegal on its own when a key expired, a failing key still
 * costing every job a round trip. None of them is a reason to block anything; all of them are things
 * an admin would otherwise only discover from a transcript that never arrived.
 */
private fun providerBanners(state: ProviderOrderState): List<Pair<BannerTone, String>> {
    if (!state.live || state.loading) return emptyList()
    val saved = state.saved
    val out = mutableListOf<Pair<BannerTone, String>>()
    val runnable = saved.filter { it.configured }
    val verified = saved.filter { it.rankable }

    if (runnable.isEmpty()) {
        out += BannerTone.WARN to (
            "None of these engines has an API key, so recordings cannot be transcribed at all yet. The " +
                "ranking is still saved and starts applying the moment a key is added and passes its test."
            )
    } else if (verified.isEmpty()) {
        out += BannerTone.INFO to (
            "Nothing here has been tested yet, so nothing is frozen — rank the engines however you like. " +
                "Tap Test on the one you want first: until an engine has answered, this list is a " +
                "preference rather than a promise."
            )
    } else if (saved.firstOrNull()?.configured == false) {
        out += BannerTone.WARN to (
            "${saved.first().name} is ranked first but has no API key, so the next recording actually goes " +
                "to ${runnable.first().name}. Add the ${saved.first().keyLabel} key to make this ranking count."
            )
    }

    if (sttOrderViolations(saved) > 0) {
        val stragglers = saved.filterNot { it.rankable }
        out += BannerTone.WARN to (
            "This saved order puts ${joinSttNames(stragglers.map { it.name })} above " +
                "${joinSttNames(verified.map { it.name })}, which ${if (verified.size == 1) "has" else "have"} " +
                "passed a test. The next save moves the unverified ${if (stragglers.size == 1) "one" else "ones"} " +
                "below — the pipeline already prefers whatever actually answers, so the list will simply stop " +
                "disagreeing with it."
            )
    }

    val failing = saved.filter { it.keyState == STT_KEY_FAILING }
    if (failing.isNotEmpty()) {
        out += BannerTone.WARN to (
            "${joinSttNames(failing.map { it.name })} still ${if (failing.size == 1) "has" else "have"} a key, " +
                "so every job keeps trying ${if (failing.size == 1) "it" else "them"} and waiting for the " +
                "refusal before moving on. Replacing or removing the key is worth doing even though " +
                "transcription still finishes."
            )
    }
    return out
}
