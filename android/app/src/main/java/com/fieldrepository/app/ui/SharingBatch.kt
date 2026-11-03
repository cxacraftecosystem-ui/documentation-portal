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
import com.fieldrepository.app.data.DataAccessGrantDto
import com.fieldrepository.app.data.DataAccessScopeItemDto
import com.fieldrepository.app.data.EVERY_WORKSHOP
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.data.apiErrorMessage
import kotlinx.coroutines.launch
import retrofit2.HttpException

val sharingTierOptions: List<Pair<String, String>> = listOf(
    "DOWNLOAD" to "Download (minimum)",
    "COMMENT" to "Comment (medium)",
    "EDIT" to "Edit (maximum)"
)

enum class ShareStatus { POSTED, REFUSED, NOT_ATTEMPTED }

@Immutable
data class ShareOutcome(
    val userId: String,
    val name: String,
    val status: ShareStatus,
    val reason: String? = null
)

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
                val reason = error.apiErrorMessage("This one couldn't be shared.")
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

private fun unfinished(outcomes: List<ShareOutcome>): Set<String> =
    outcomes.filter { it.status != ShareStatus.POSTED }.map { it.userId }.toSet()

@Composable
private fun BatchOutcomePanel(outcomes: List<ShareOutcome>, verb: String) {
    if (outcomes.isEmpty()) return
    val tokens = MaterialTheme.field
    val posted = outcomes.count { it.status == ShareStatus.POSTED }
    val failed = outcomes.size - posted
    val summary = when {
        failed == 0 -> "All $posted $verb."
        posted == 0 -> "None $verb. $failed failed."
        else -> "$posted of ${outcomes.size} $verb. $failed failed and are still selected."
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
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        outcomes.filter { it.status != ShareStatus.POSTED }.forEach { outcome ->
            val prefix = if (outcome.status == ShareStatus.NOT_ATTEMPTED) "Not sent" else "Failed"
            Text(
                "${outcome.name} — $prefix: ${outcome.reason.orEmpty()}",
                color = tokens.onWarningContainer,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

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
            "Ask researchers for access to their data. Each decides separately.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
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
        ActionRow {
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
        }
        BatchOutcomePanel(outcomes, verb = "sent")
    }
}

private fun requestCountLabel(count: Int): String = if (count <= 1) "request" else "$count requests"

private fun tierWord(tier: String): String = when (tier) {
    "DOWNLOAD" -> "download"
    "COMMENT" -> "comment"
    else -> "edit"
}

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
                    "has ${tierWord(it.tier)}, $scopeWord — this replaces it"
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
                repository.interviews(EVERY_WORKSHOP).filter { it.createdById == myId }
                    .forEach { recs += SelectOption("questionnaire::${it.id}", "Interview · ${it.title}") }
                recs
            }.onSuccess { myRecords = it }
                .onFailure { onError(it.apiErrorMessage("Unable to load your records")) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            "Share all your data or chosen records. Everyone selected gets the same access.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
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
                recs == null -> Text("Loading your records…", color = Muted, style = MaterialTheme.typography.bodySmall)
                recs.isEmpty() -> Text("You have no records to share.", color = Muted, style = MaterialTheme.typography.bodySmall)
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
        ActionRow {
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
        }
        BatchOutcomePanel(outcomes, verb = "granted")
    }
}

private fun grantCountLabel(count: Int): String =
    if (count <= 1) "access" else "access to $count people"

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
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect
            )
            .padding(end = 8.dp)
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            label,
            color = Body,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
