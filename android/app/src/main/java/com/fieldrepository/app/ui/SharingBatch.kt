package com.fieldrepository.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.data.DataAccessGrantDto
import com.fieldrepository.app.data.DataAccessScopeItemDto
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.data.apiErrorMessage
import kotlinx.coroutines.launch
import retrofit2.HttpException

// ---------------------------------------------------------------------------------------------
// Sharing, several people at a time.
//
// WHAT WAS WRONG. Granting a colleague access was one colleague per visit to the card: pick, set a
// tier, tap Grant, watch the card reset, pick the next. A researcher handing a workshop's data to
// the four people who worked it did that four times, and the only record of how far they had got
// was their own memory. Requesting access from several researchers had the same shape.
//
// WHY THE PEOPLE PICKERS ARE MULTI AND THE TIER PICKER IS NOT. The tiers are a LADDER, not a set:
// `backend/app/services/access.py` defines `TIER_ORDER = {"DOWNLOAD": 1, "COMMENT": 2, "EDIT": 3}`
// under the comment "a tier includes every action of the tiers below it", and every gate in the
// backend is `tier_at_least(tier, minimum)` — a `>=` against that order, never a set membership
// test. So "DOWNLOAD and COMMENT" is not a thing you can hold; it is COMMENT. A multi-select there
// would offer a choice the server has no way to store and the researcher no way to benefit from.
// One tier applies to everyone in the batch, which is the honest reading of a single ladder rung.
//
// WHY THE SHEET IS NOT DEFINED HERE. `SearchableMultiSelectField` in `SearchableSelect.kt` already
// is the app's picker, lifted out of the phone field's country dialog, and that file argues the
// bottom-sheet choice (the IME covers an anchored menu; a sheet is in thumb reach; swipe to
// dismiss) at length. Two pickers in one app is the thing to avoid, so this file is a CALLER. The
// directory of twenty-odd users is past `SEARCH_THRESHOLD`, so both people fields open the
// searchable sheet; the three tiers are below it and keep the anchored menu, which is right — a
// search box over three rows is furniture.
// ---------------------------------------------------------------------------------------------

/** The ladder, weakest first — the order the picker offers it in. */
val sharingTierOptions: List<Pair<String, String>> = listOf(
    "DOWNLOAD" to "Download (minimum)",
    "COMMENT" to "Comment (medium)",
    "EDIT" to "Edit (maximum)"
)

// ---------------------------------------------------------------------------------------------
// Partial failure
// ---------------------------------------------------------------------------------------------

/**
 * What became of one person in a batch.
 *
 * THREE STATES, NOT TWO, AND THE THIRD IS THE POINT. `POSTED` and `REFUSED` are what a laptop on
 * an office connection produces. On the connection this app is actually used on, the common ending
 * is that the link dies half way down the list — and calling the remaining four "failed" would be
 * a lie in the direction that costs the researcher most: it says the server considered and
 * rejected them, when nothing was ever sent and re-tapping will very likely work.
 */
enum class ShareStatus { POSTED, REFUSED, NOT_ATTEMPTED }

/** One row of a batch result: who, what happened, and — when it failed — the server's own words. */
@Immutable
data class ShareOutcome(
    val userId: String,
    val name: String,
    val status: ShareStatus,
    val reason: String? = null
)

/**
 * Send one call per person and report each one separately.
 *
 * SEQUENTIAL, NOT PARALLEL, and that is a decision rather than the easy way out. Five coroutines
 * against one rural uplink share the same thin pipe, so nothing arrives sooner; on the other end
 * they land on a t3.micro whose Prisma connection pooler is the component this deployment has
 * already exhausted twice. One at a time also means the outcome list reads in the order the
 * researcher ticked the names, and that a link that dies is discovered before four more requests
 * have been thrown at it.
 *
 * A REFUSAL STOPS ONE PERSON; A DEAD LINK STOPS THE RUN. The split is whether the server answered
 * at all. An `HttpException` is an answer — a 409 "you already have an active grant" applies to
 * that one researcher and says nothing about the next, so the loop carries on. Anything else (no
 * signal, DNS, a socket dropped mid-flight, a gateway timeout) is the transport, which will not
 * have healed by the next name; continuing would spend a 30-second timeout per remaining person to
 * learn what the first one already proved. The rest are reported [NOT_ATTEMPTED], honestly.
 *
 * Nothing is retried here. Retrying is the researcher tapping the button again — and because the
 * failures stay selected, that tap re-sends exactly the ones that did not land.
 */
private suspend fun runShareBatch(
    people: List<UserDto>,
    call: suspend (UserDto) -> Unit
): List<ShareOutcome> {
    val outcomes = mutableListOf<ShareOutcome>()
    var linkDown: String? = null
    for (person in people) {
        if (linkDown != null) {
            outcomes += ShareOutcome(person.id, person.name, ShareStatus.NOT_ATTEMPTED, linkDown)
            continue
        }
        runCatching { call(person) }
            .onSuccess { outcomes += ShareOutcome(person.id, person.name, ShareStatus.POSTED) }
            .onFailure { error ->
                val reason = error.apiErrorMessage("The server did not accept this one.")
                if (error is HttpException) {
                    outcomes += ShareOutcome(person.id, person.name, ShareStatus.REFUSED, reason)
                } else {
                    linkDown = reason
                    outcomes += ShareOutcome(person.id, person.name, ShareStatus.REFUSED, reason)
                }
            }
    }
    return outcomes
}

/** The people a batch did not get to, so the field can keep them ticked for the next tap. */
private fun unfinished(outcomes: List<ShareOutcome>): Set<String> =
    outcomes.filter { it.status != ShareStatus.POSTED }.map { it.userId }.toSet()

/**
 * The result of the last batch, per person.
 *
 * KEEPING THE FAILURES ON SCREEN IS THE HOUSE STYLE, not an invention. The offline outbox already
 * settled this: `OfflineOutbox.markFailure` writes the reason onto the entry and KEEPS it — "a
 * refusal is a reason to stop retrying and tell someone, not a reason to destroy the only copy".
 * The same rule, one layer up. What the outbox does with a queued record on disk, this does with a
 * ticked name in a picker: the ones that landed leave the selection, the ones that did not stay in
 * it carrying the server's reason, and the button that failed them is the button that retries
 * them. A researcher who looks away mid-batch comes back to a list of exactly what is outstanding.
 */
@Composable
private fun BatchOutcomePanel(outcomes: List<ShareOutcome>, verb: String) {
    if (outcomes.isEmpty()) return
    val tokens = MaterialTheme.field
    val posted = outcomes.count { it.status == ShareStatus.POSTED }
    val failed = outcomes.size - posted
    val summary = when {
        failed == 0 -> "All $posted $verb."
        posted == 0 -> "None $verb. $failed could not be sent."
        else -> "$posted of ${outcomes.size} $verb. $failed could not be sent, and are still selected."
    }
    val allGood = failed == 0

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (allGood) tokens.successContainer else tokens.warningContainer,
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp)
    ) {
        Text(
            summary,
            color = if (allGood) tokens.onSuccessContainer else tokens.onWarningContainer,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            // Polite: this lands after a button press the researcher already knows they made, so
            // interrupting whatever TalkBack is mid-way through would be rude for no information.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        // Only the ones that did not land are itemised. Listing five successes back at someone who
        // just watched a progress spinner finish is noise, and it buries the two lines that matter
        // — which is the failure mode this panel exists to prevent.
        outcomes.filter { it.status != ShareStatus.POSTED }.forEach { outcome ->
            val prefix = if (outcome.status == ShareStatus.NOT_ATTEMPTED) "Not sent" else "Failed"
            Text(
                "${outcome.name} — $prefix: ${outcome.reason.orEmpty()}",
                color = tokens.onWarningContainer,
                fontSize = 12.sp
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Request access from several researchers
// ---------------------------------------------------------------------------------------------

/**
 * The body of the "Request access" card: tick the researchers, pick one tier, send one request each.
 *
 * [outgoing] is the caller's existing outgoing grants, used only to HINT rows in the picker. A
 * researcher who already holds a GRANTED grant gets a 409 from `POST /data-access/requests` by
 * design — the backend refuses to reset an active grant to PENDING because that would strip their
 * working access while the owner deliberates. Saying so in the row's hint is cheaper than letting
 * them tick five names and reading two rejections afterwards, and the hint is searchable, so
 * typing "already" lists everyone it applies to.
 */
@Composable
fun RequestAccessFields(
    repository: FieldRepository,
    directory: List<UserDto>,
    outgoing: List<DataAccessGrantDto>,
    onChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var tier by remember { mutableStateOf("DOWNLOAD") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var outcomes by remember { mutableStateOf<List<ShareOutcome>>(emptyList()) }

    val held = remember(outgoing) {
        outgoing.filter { it.status == "GRANTED" }.associate { it.ownerId to it.tier }
    }
    val options = remember(directory, held) {
        directory.map { user ->
            SelectOption(
                value = user.id,
                label = "${user.name} · ${user.email}",
                hint = held[user.id]?.let { "already have ${tierWord(it)}" }
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            "Ask other researchers for access to their data. They each decide, and can narrow it to " +
                "a subset. One request is sent per researcher.",
            color = Muted,
            fontSize = 12.sp
        )
        SearchableMultiSelectField(
            label = "Researchers",
            options = options,
            selected = selected,
            placeholder = "Select researchers",
            emptyMessage = "No other researchers to ask.",
            enabled = !busy,
            onSelectedChange = { selected = it }
        )
        SearchableSelectField(
            label = "Tier",
            options = sharingTierOptions.asSelectOptions(),
            selectedValue = tier,
            includeNone = false,
            enabled = !busy,
            onSelect = { tier = it }
        )
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Note (optional)") },
            singleLine = false,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            enabled = !busy && selected.isNotEmpty(),
            modifier = Modifier.heightIn(min = 48.dp),
            onClick = {
                val people = directory.filter { it.id in selected }
                scope.launch {
                    busy = true
                    val results = runShareBatch(people) { person ->
                        repository.requestDataAccess(person.id, tier, note)
                    }
                    outcomes = results
                    selected = unfinished(results)
                    // The note is only cleared once every request carrying it has landed: it is the
                    // sentence the researcher wrote to explain themselves, and wiping it while two
                    // people still need re-sending would make them write it again.
                    if (selected.isEmpty()) note = ""
                    busy = false
                    onChanged()
                }
            }
        ) {
            Text(
                if (busy) {
                    "Sending…"
                } else {
                    "Send ${requestCountLabel(selected.size)}"
                }
            )
        }
        BatchOutcomePanel(outcomes, verb = "sent")
    }
}

/** "request" / "2 requests" — the button says how many calls the tap is about to make. */
private fun requestCountLabel(count: Int): String = if (count <= 1) "request" else "$count requests"

/** The bare tier word for a hint line, where the "(minimum)" parentheses would only add noise. */
private fun tierWord(tier: String): String = when (tier) {
    "DOWNLOAD" -> "download"
    "COMMENT" -> "comment"
    else -> "edit"
}

// ---------------------------------------------------------------------------------------------
// Grant access to several colleagues
// ---------------------------------------------------------------------------------------------

/**
 * The body of the "Grant access to your data" card: tick the colleagues, pick one tier, choose all
 * data or specific records, and grant each of them in one pass.
 *
 * [incoming] is used for the same hinting reason as above, but the warning is the opposite way
 * round and sharper. `POST /data-access/grants` does not fail on someone who already has a grant —
 * `_upsert_grant` finds the existing (owner, grantee) row, UPDATES it, and then does
 * `delete_many` over its scope items and rebuilds them from this call. So re-granting REPLACES:
 * a colleague who had three named records and is included in an "all my data" batch silently ends
 * up with everything. That is a widening the researcher should be told about before they tap, not
 * discover in the list underneath afterwards.
 */
@Composable
fun GrantAccessFields(
    repository: FieldRepository,
    directory: List<UserDto>,
    incoming: List<DataAccessGrantDto>,
    onChanged: () -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val myId = repository.cachedUser()?.id
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var tier by remember { mutableStateOf("DOWNLOAD") }
    var allData by remember { mutableStateOf(true) }
    var myRecords by remember { mutableStateOf<List<SelectOption>?>(null) }
    var scopeKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var outcomes by remember { mutableStateOf<List<ShareOutcome>>(emptyList()) }

    val existing = remember(incoming) {
        incoming.filter { it.status == "GRANTED" }.associateBy { it.granteeId }
    }
    val options = remember(directory, existing) {
        directory.map { user ->
            val grant = existing[user.id]
            SelectOption(
                value = user.id,
                label = "${user.name} · ${user.email}",
                hint = grant?.let {
                    val scopeWord = if (it.allData) "all data" else "${it.scopeItems.size} records"
                    "already has ${tierWord(it.tier)}, $scopeWord — granting replaces it"
                }
            )
        }
    }

    fun loadMine() {
        if (myRecords != null) return
        scope.launch {
            runCatching {
                val recs = mutableListOf<SelectOption>()
                repository.artisans().filter { it.createdById == myId }
                    .forEach { recs += SelectOption("artisan::${it.id}", "Artisan · ${it.name}") }
                repository.products().filter { it.createdById == myId }
                    .forEach { recs += SelectOption("product::${it.id}", "Product · ${it.productName}") }
                repository.tools().filter { it.createdById == myId }
                    .forEach { recs += SelectOption("tool::${it.id}", "Tool · ${it.toolkitName}") }
                repository.workshops().filter { it.createdById == myId }
                    .forEach { recs += SelectOption("workshop::${it.id}", "Workshop · ${it.title}") }
                repository.interviews().filter { it.createdById == myId }
                    .forEach { recs += SelectOption("questionnaire::${it.id}", "Interview · ${it.title}") }
                recs
            }.onSuccess { myRecords = it }
                .onFailure { onError(it.apiErrorMessage("Unable to load your records")) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            "Share all of your data, or pick specific records, with colleagues. Everyone selected " +
                "gets the same tier and the same records.",
            color = Muted,
            fontSize = 12.sp
        )
        SearchableMultiSelectField(
            label = "Colleagues",
            options = options,
            selected = selected,
            placeholder = "Select colleagues",
            emptyMessage = "No colleagues to share with.",
            enabled = !busy,
            onSelectedChange = { selected = it }
        )
        SearchableSelectField(
            label = "Tier",
            options = sharingTierOptions.asSelectOptions(),
            selectedValue = tier,
            includeNone = false,
            enabled = !busy,
            onSelect = { tier = it }
        )
        // A radio pair needs the Row to be the selectable node, not the button: `selectable` gives
        // TalkBack one node reading "All my data, radio button, selected" instead of an unlabelled
        // control and a stranded caption, and it makes the label text part of the 48dp target.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            ScopeChoice(
                label = "All my data",
                selected = allData,
                enabled = !busy,
                onSelect = { allData = true }
            )
            ScopeChoice(
                label = "Selected records",
                selected = !allData,
                enabled = !busy,
                onSelect = { allData = false; loadMine() }
            )
        }
        if (!allData) {
            val recs = myRecords
            when {
                recs == null -> Text("Loading your records…", color = Muted, fontSize = 12.sp)
                recs.isEmpty() -> Text("You have no records to share.", color = Muted, fontSize = 12.sp)
                // The same sheet as the people field above. This used to be every record rendered
                // as a raw checkbox straight into the card — no summary line, so the only way to
                // see what was ticked was to scroll back over it, and no search, so a researcher
                // with two hundred records scrolled for the one they meant.
                else -> SearchableMultiSelectField(
                    label = "Records to share",
                    options = recs,
                    selected = scopeKeys,
                    placeholder = "Select records",
                    enabled = !busy,
                    onSelectedChange = { scopeKeys = it }
                )
            }
        }
        Button(
            enabled = !busy && selected.isNotEmpty() && (allData || scopeKeys.isNotEmpty()),
            modifier = Modifier.heightIn(min = 48.dp),
            onClick = {
                val people = directory.filter { it.id in selected }
                val scopeItems = if (allData) {
                    emptyList()
                } else {
                    scopeKeys.mapNotNull { key ->
                        val parts = key.split("::")
                        if (parts.size == 2) {
                            DataAccessScopeItemDto(recordType = parts[0], recordId = parts[1])
                        } else {
                            null
                        }
                    }
                }
                scope.launch {
                    busy = true
                    val results = runShareBatch(people) { person ->
                        repository.grantDataAccess(person.id, tier, allData, scopeItems)
                    }
                    outcomes = results
                    selected = unfinished(results)
                    // Tier and record scope survive a partial batch on purpose: they are what the
                    // remaining people still need, and re-choosing eleven records to finish the two
                    // that failed is the repetition this whole screen was changed to remove.
                    if (selected.isEmpty()) {
                        scopeKeys = emptySet()
                        allData = true
                    }
                    busy = false
                    onChanged()
                }
            }
        ) {
            Text(if (busy) "Granting…" else "Grant ${grantCountLabel(selected.size)}")
        }
        BatchOutcomePanel(outcomes, verb = "granted")
    }
}

/** "access" / "access to 3 people" — the button says how many calls the tap is about to make. */
private fun grantCountLabel(count: Int): String =
    if (count <= 1) "access" else "access to $count people"

/** One half of the all-data / subset radio pair, as a single 48dp TalkBack node. */
@Composable
private fun ScopeChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 48.dp)
            // `selectable` merges its descendants, so the Row is ONE TalkBack node reading "All my
            // data, radio button, selected" — the caption is absorbed rather than being a second
            // stop. An explicit `contentDescription` here would UNDO that: it was tried, and a
            // uiautomator dump on the handset showed the label twice, once from the description and
            // once from the Text it was meant to replace. The Checkbox in the shared picker's rows
            // is handled the same way, and for the same reason.
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect
            )
            .padding(end = 8.dp)
    ) {
        // A null callback so the button is not a focus stop of its own; the Row above owns the tap.
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            label,
            color = Body,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
