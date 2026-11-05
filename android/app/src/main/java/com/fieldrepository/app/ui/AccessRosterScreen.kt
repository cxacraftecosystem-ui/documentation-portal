package com.fieldrepository.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.AccessRosterDto
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.data.apiErrorMessage
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.testTag
import com.fieldrepository.app.data.PageResponse

private const val ROSTER_PAGE_SIZE = 20

private const val NEW_ACCOUNTS_ONLY = "New accounts only. To change an existing account, use Manage users."

internal fun heldSentence(answer: Any?): String? =
    (answer as? AccessRosterDto)?.accountPromotionHeld?.trim()?.ifBlank { null }

private const val QUEUE_PAGE_SIZE = 25

private val STATUS_FILTERS = listOf(
    SelectOption("", "Every status"),
    SelectOption("PENDING", "Awaiting approval"),
    SelectOption("ACTIVE", "May sign in"),
    SelectOption("REJECTED", "Not approved"),
    SelectOption("SUSPENDED", "Suspended")
)

@Composable
fun AccessRosterScreen(
    repository: FieldRepository,
    user: UserDto,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit = {},
    onError: (String) -> Unit = {},
    onPendingCountChanged: (Int) -> Unit = {}
) {
    val scope = rememberCoroutineScope()

    var queue by remember { mutableStateOf<List<AccessRosterDto>?>(null) }
    var queueTotal by remember { mutableStateOf(0) }
    var queuePages by remember { mutableStateOf(1) }
    var queueLoading by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<AccessRosterDto>?>(null) }
    var rosterTotal by remember { mutableStateOf(0) }
    var rosterPage by remember { mutableStateOf(1) }
    var rosterPages by remember { mutableStateOf(0) }
    var search by remember { mutableStateOf("") }
    var appliedSearch by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("") }
    var busyId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<PendingDecision?>(null) }

    var newEmail by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var newNote by remember { mutableStateOf("") }
    var newRole by remember { mutableStateOf("") }
    var signIn by remember { mutableStateOf(AccessSignIn.PROVIDER) }
    var newPassword by remember { mutableStateOf("") }
    var newMustChange by remember { mutableStateOf(true) }
    var revealNew by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    val roleOptions = remember(user.role) { assignableRoleOptions(user.role) }

    suspend fun loadQueue() {
        queueLoading = true
        runCatching { accessQueuePages(queuePages) { page -> repository.accessRoster(page = page, pageSize = QUEUE_PAGE_SIZE, status = "PENDING") } }
            .onSuccess {
                queue = it.items
                queueTotal = it.total
                onPendingCountChanged(it.total)
            }
            .onFailure {
                error = it.apiErrorMessage("Unable to load the pending requests")
            }
        queueLoading = false
    }

    suspend fun reload() {
        loadQueue()
        runCatching {
            repository.accessRoster(
                page = rosterPage,
                pageSize = ROSTER_PAGE_SIZE,
                search = appliedSearch.ifBlank { null },
                status = statusFilter.ifBlank { null }
            )
        }
            .onSuccess {
                rows = it.items
                rosterTotal = it.total
                rosterPages = it.pages
                error = null
            }
            .onFailure {
                error = it.apiErrorMessage("Unable to load the access roster")
            }
    }

    LaunchedEffect(rosterPage, appliedSearch, statusFilter) { reload() }
    LaunchedEffect(error) { error?.let(onError) }
    LaunchedEffect(notice) { notice?.let(onMessage) }

    BackHandler(enabled = confirming != null || onBack != null) {
        when {
            confirming != null -> confirming = null
            else -> onBack?.invoke()
        }
    }

    fun decide(entry: AccessRosterDto, approve: Boolean, role: String) {
        scope.launch {
            busyId = entry.id
            error = null
            runCatching {
                if (approve) repository.approveAccessRosterEntry(entry.id, role)
                else repository.rejectAccessRosterEntry(entry.id)
            }
                .onSuccess { updated ->
                    notice = if (approve) {
                        heldSentence(updated) ?: "${updated.email} can sign in, as ${roleLabelFor(updated.grantedRole)}."
                    } else {
                        "${updated.email} refused. You can approve them later."
                    }
                    reload()
                }
                .onFailure {
                    error = it.apiErrorMessage(
                        if (approve) "Unable to approve this request" else "Unable to refuse this request"
                    )
                }
            busyId = null
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            Text(
                "Access roster",
                display = true,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            "Who may sign in. Everyone else waits in the queue below.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )

        error?.let { NoticeLine(it, tone = NoticeTone.ERROR) { error = null } }
        notice?.let { NoticeLine(it, tone = NoticeTone.INFO) { notice = null } }

        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Filled.HourglassEmpty,
                        contentDescription = null,
                        tint = MaterialTheme.field.warning,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        "Waiting for a decision",
                        display = true,
                        color = Body,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (queue != null) CountPill(queueTotal)
                }
                Text(
                    "Signed in but not on the roster. They wait until you decide.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )

                val pending = queue
                when {
                    pending == null -> Text("Loading the queue…", color = Muted, style = MaterialTheme.typography.bodySmall)
                    pending.isEmpty() -> Text(
                        "Nobody is waiting.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    else -> pending.forEach { entry ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        PendingRequestRow(
                            entry = entry,
                            roleOptions = roleOptions,
                            busy = busyId == entry.id,
                            onApprove = { role -> decide(entry, approve = true, role = role) },
                            onRefuse = { confirming = PendingDecision(entry) }
                        )
                    }
                }

                accessQueueNotice(shown = pending?.size ?: 0, total = queueTotal)?.let { line ->
                    Text(line, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("access-queue-shown"))
                    OutlinedButton(
                        onClick = {
                            queuePages += 1
                            scope.launch { loadQueue() }
                        },
                        enabled = !queueLoading,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("access-queue-more")
                    ) {
                        Text(if (queueLoading) "Loading…" else accessQueueMore(shown = pending?.size ?: 0, total = queueTotal))
                    }
                }
            }
        }

        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Add an address to the roster",
                    display = true,
                    color = Body,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Only the email is needed. Their account is created at this role when they first sign in.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = newEmail,
                    onValueChange = { newEmail = it },
                    label = { Text("Email address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Full name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                SearchableSelectField(
                    label = "Joins as",
                    options = roleOptions,
                    selectedValue = newRole,
                    placeholder = "Lowest rung (default)",
                    onSelect = { newRole = it }
                )
                Text(
                    "Defaults to the lowest role. $NEW_ACCOUNTS_ONLY",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = newNote,
                    onValueChange = { newNote = it },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(ACCESS_SIGN_IN_QUESTION, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                listOf(
                    AccessSignIn.PROVIDER to ACCESS_SIGN_IN_PROVIDER,
                    AccessSignIn.PASSWORD to ACCESS_SIGN_IN_PASSWORD,
                ).forEach { (option, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .selectable(selected = signIn == option, role = Role.RadioButton, onClick = { signIn = option })
                    ) {
                        RadioButton(selected = signIn == option, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Text(accessSignInNote(signIn), color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                if (signIn == AccessSignIn.PASSWORD) {
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("Password") },
                        supportingText = { Text(passwordRuleLine()) },
                        singleLine = true,
                        visualTransformation = passwordTransformation(revealNew),
                        trailingIcon = { PasswordRevealIcon(revealNew, { revealNew = !revealNew }) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    AppToggle(
                        label = ACCESS_MUST_CHANGE_LABEL,
                        checked = newMustChange,
                        onCheckedChange = { newMustChange = it }
                    )
                }
                Button(
                    enabled = !adding && newEmail.isNotBlank(),
                    onClick = {
                        val refusal = accessAddProblem(signIn, newPassword, newName)
                        if (refusal != null) {
                            error = refusal
                            return@Button
                        }
                        scope.launch {
                            adding = true
                            error = null
                            notice = null
                            runCatching {
                                repository.addToAccessRoster(
                                    email = newEmail,
                                    fullName = newName,
                                    notes = newNote,
                                    grantedRole = newRole,
                                    password = if (signIn == AccessSignIn.PASSWORD) newPassword else null,
                                    mustChangePassword = newMustChange
                                )
                            }
                                .onSuccess { created ->
                                    notice = heldSentence(created) ?: (accessAddedLine(created.email, signIn) + " " +
                                        "Joins as ${roleLabelFor(created.grantedRole)}.")
                                    newEmail = ""
                                    newName = ""
                                    newNote = ""
                                    newRole = ""
                                    newPassword = ""
                                    reload()
                                }
                                .onFailure {
                                    error = it.apiErrorMessage("Unable to add this address to the roster")
                                    search = newEmail.trim()
                                    appliedSearch = newEmail.trim()
                                    statusFilter = ""
                                    rosterPage = 1
                                }
                            adding = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (adding) "Saving…" else "Add to roster")
                }
            }
        }

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Search by email or name") },
            singleLine = true,
            trailingIcon = {
                TextButton(onClick = { appliedSearch = search; rosterPage = 1 }) { Text("Search") }
            },
            modifier = Modifier.fillMaxWidth()
        )
        SearchableSelectField(
            label = "Access status",
            options = STATUS_FILTERS,
            selectedValue = statusFilter,
            placeholder = "Every status",
            includeNone = false,
            onSelect = { statusFilter = it; rosterPage = 1 }
        )

        val list = rows
        when {
            list == null -> Text("Loading the roster…", color = Muted, style = MaterialTheme.typography.bodySmall)
            list.isEmpty() -> Text(
                if (appliedSearch.isNotBlank() || statusFilter.isNotBlank()) {
                    "No matches."
                } else {
                    "The roster is empty."
                },
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            else -> list.forEach { entry ->
                RosterRow(
                    entry = entry,
                    busy = busyId == entry.id,
                    onSuspend = {
                        scope.launch {
                            busyId = entry.id
                            error = null
                            runCatching { repository.suspendAccessRosterEntry(entry.id) }
                                .onSuccess {
                                    notice = "${it.email} suspended."
                                    reload()
                                }
                                .onFailure { error = it.apiErrorMessage("Unable to suspend this entry") }
                            busyId = null
                        }
                    },
                    onRestore = {
                        scope.launch {
                            busyId = entry.id
                            error = null
                            runCatching { repository.approveAccessRosterEntry(entry.id, entry.grantedRole) }
                                .onSuccess { notice = heldSentence(it) ?: "${it.email} can sign in again."; reload() }
                                .onFailure { error = it.apiErrorMessage("Unable to restore this entry") }
                            busyId = null
                        }
                    }
                )
            }
        }

        if (list != null) {
            Text(accessRosterPageNotice(rosterPage, rosterPages, rosterTotal), color = Muted, style = MaterialTheme.typography.bodySmall)
            ActionRow {
                OutlinedButton(enabled = rosterPage > 1, onClick = { rosterPage -= 1 }) { Text("Previous") }
                OutlinedButton(enabled = rosterPage < rosterPages, onClick = { rosterPage += 1 }) { Text("Next") }
            }
        }

        Text(
            "The master admin is always allowed and can't be removed.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
    }

    confirming?.let { decision ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Refuse access for ${decision.entry.email}?") },
            text = {
                Text(
                    "They'll be told to contact an administrator. Signing in again won't re-queue them."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val entry = decision.entry
                    confirming = null
                    decide(entry, approve = false, role = entry.grantedRole)
                }) { Text("Refuse access", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } }
        )
    }
}

private data class PendingDecision(val entry: AccessRosterDto)

@Composable
private fun PendingRequestRow(
    entry: AccessRosterDto,
    roleOptions: List<SelectOption>,
    busy: Boolean,
    onApprove: (String) -> Unit,
    onRefuse: () -> Unit
) {
    var role by remember(entry.id) { mutableStateOf(entry.grantedRole) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(entry.email, color = Body, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(accessRequestLabel(entry.requestCount), color = Muted, style = MaterialTheme.typography.bodySmall)
        SearchableSelectField(
            label = "Joins as",
            options = roleOptions,
            selectedValue = role,
            includeNone = false,
            enabled = !busy,
            onSelect = { role = it }
        )
        Text(NEW_ACCOUNTS_ONLY, color = Muted, style = MaterialTheme.typography.bodySmall)
        ActionRow {
            Button(enabled = !busy, onClick = { onApprove(role) }) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (busy) "Working…" else "Approve")
            }
            OutlinedButton(
                enabled = !busy,
                onClick = onRefuse,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Refuse")
            }
        }
    }
}

@Composable
private fun RosterRow(
    entry: AccessRosterDto,
    busy: Boolean,
    onSuspend: () -> Unit,
    onRestore: () -> Unit
) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                entry.fullName ?: entry.accountName ?: "Name not recorded",
                color = Body,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(entry.email, color = Muted, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(entry.status)
                Text(accessInvitationLabel(entry.status, entry.firstSeenAt), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                accessJoinedLabel(entry.joinedAt) + " · " + roleLabelFor(entry.grantedRole),
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            entry.accountRole?.takeIf { it != entry.grantedRole }?.let {
                Text("Account is now ${roleLabelFor(it)}", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text(accessRequestLabel(entry.requestCount), color = Muted, style = MaterialTheme.typography.bodySmall)
            entry.notes?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (entry.status == "ACTIVE") {
                ActionRow {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = onSuspend,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Suspend") }
                }
            } else {
                ActionRow {
                    OutlinedButton(enabled = !busy, onClick = onRestore) {
                        Text(if (entry.status == "PENDING") "Approve" else "Restore")
                    }
                }
            }
        }
    }
}

@Composable
private fun CountPill(count: Int) {
    Surface(
        color = MaterialTheme.field.warningContainer,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            count.toString(),
            color = MaterialTheme.field.warning,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun StatusPill(status: String) {
    val container = when (status) {
        "ACTIVE" -> MaterialTheme.field.successContainer
        "PENDING" -> MaterialTheme.field.warningContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (status) {
        "ACTIVE" -> MaterialTheme.field.success
        "PENDING" -> MaterialTheme.field.warning
        else -> MaterialTheme.colorScheme.error
    }
    Surface(color = container, shape = RoundedCornerShape(999.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            if (status == "ACTIVE") {
                Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = content, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(accessStatusLabel(status), color = content, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
    }
}

private enum class NoticeTone { INFO, ERROR }

@Composable
private fun NoticeLine(text: String, tone: NoticeTone, onDismiss: () -> Unit) {
    val container = if (tone == NoticeTone.ERROR) MaterialTheme.colorScheme.errorContainer else MaterialTheme.field.surface100
    val content = if (tone == NoticeTone.ERROR) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.field.body
    Surface(color = container, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
        ) {
            Text(text, color = content, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Dismiss", color = content, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

private val ROLE_LADDER = listOf(
    "MASTER_ADMIN",
    "ADMIN",
    "PROFESSOR",
    "RESEARCHER",
    "FIELD_CONTRIBUTOR",
    "CROWDSOURCE_VOLUNTEER"
)

fun roleLabelFor(role: String?): String = FieldPermissions.label(role)

fun assignableRoleOptions(role: String?): List<SelectOption> {
    val rank = FieldPermissions.rank(role)
    return ROLE_LADDER
        .filter { FieldPermissions.rank(it) <= rank }
        .map { SelectOption(it, FieldPermissions.label(it)) }
}

fun accessStatusLabel(status: String): String = when (status) {
    "PENDING" -> "Awaiting approval"
    "ACTIVE" -> "May sign in"
    "REJECTED" -> "Not approved"
    "SUSPENDED" -> "Suspended"
    else -> status
}

fun accessInvitationLabel(status: String, firstSeenAt: String?): String = when {
    !firstSeenAt.isNullOrBlank() -> "Signed in"
    status != "ACTIVE" -> "No access to take up"
    else -> "Not signed in yet"
}

fun accessRequestLabel(requestCount: Int): String = when {
    requestCount <= 0 -> "Added by an admin"
    requestCount == 1 -> "Asked once"
    else -> "Asked $requestCount times"
}

fun accessJoinedLabel(joinedAt: String?): String =
    if (joinedAt.isNullOrBlank()) "Not admitted yet" else "Joined ${joinedAt.take(10)}"

fun accessQueueNotice(shown: Int, total: Int): String? {
    if (total <= shown) return null
    return "Showing $shown of $total."
}

fun accessQueueMore(shown: Int, total: Int): String =
    "Show ${minOf(QUEUE_PAGE_SIZE, (total - shown).coerceAtLeast(0))} more"

internal suspend fun <T> accessQueuePages(pages: Int, fetch: suspend (Int) -> PageResponse<T>): PageResponse<T> {
    val first = fetch(1)
    val rows = first.items.toMutableList()
    var page = 2
    while (page <= pages && rows.size < first.total) {
        val next = fetch(page)
        if (next.items.isEmpty()) break
        rows += next.items
        page += 1
    }
    return first.copy(items = rows, page = 1, pageSize = rows.size)
}

fun accessRosterPageNotice(page: Int, pages: Int, total: Int): String =
    "Page ${if (pages > 0) page else 0} of $pages · $total ${if (total == 1) "entry" else "entries"}"
