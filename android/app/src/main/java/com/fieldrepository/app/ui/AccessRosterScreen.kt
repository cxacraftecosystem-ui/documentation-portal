package com.fieldrepository.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.data.AccessRosterDto
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.data.apiErrorMessage
import kotlinx.coroutines.launch

/*
 * THE ACCESS ROSTER, ON THE PHONE — who may sign in to this repository at all.
 *
 * WHY THIS SCREEN EXISTS. Until the sign-in gate shipped, this application refused nobody: any
 * address Google would vouch for got an account and a bearer token, automatically, at the lowest
 * tier, and the institution had no say in who was inside its own repository. This is that say. The
 * queue at the top of it is ALSO the notification — there is no email and no push anywhere in this
 * codebase and there never has been, so "the admins and master admins should get a notification to
 * approve or reject the user" is served by a count on the surfaces admins already open plus this
 * queue.
 *
 * THE WEB TWIN IS `frontend/app/(protected)/admin/access-roster/page.tsx` AND THE TWO MUST AGREE.
 * Same sections in the same order (queue, add-an-address, the roster), the same wording for every
 * status and every notice, the same actions on a row. An admin who approves somebody on a laptop
 * and refuses somebody else on a phone is using one feature, and it must not read as two. Every
 * string an admin sees here has a counterpart in `frontend/lib/accessRoster.ts`; the pure functions
 * at the bottom of this file are the Kotlin half of that contract and are unit-tested in
 * `app/src/test/java/com/fieldrepository/app/ui/AccessRosterTest.kt`.
 *
 * THREE RULES THIS SCREEN MUST NOT BREAK, all of them enforced on the server as well
 * (`backend/app/services/access_roster.py` — read it before changing behaviour here):
 *
 * 1. A ROW IS NOT AN ADMISSION. Only `status == "ACTIVE"` admits. A PENDING row is created BY THE
 *    REFUSED CALLER, so "there is a row for this address" and "this person may sign in" are one
 *    clause apart, and a screen that conflates them is showing an admin an authentication bypass
 *    and calling it a roster. Every control below names the status it acts on.
 * 2. THERE IS NO DELETE. `DELETE /access-roster/{id}` is a SUSPENSION that answers with the
 *    suspended row, so the entry stays on screen — dated, and one tap from restored. The roster is
 *    the record that an address was recognised, and that record outlives the access.
 * 3. REFUSED AND SUSPENDED ROWS ARE LISTED BY DEFAULT. An admin opens this because somebody says
 *    they cannot sign in; the row refusing them is the one they need to see.
 *
 * HOSTING. Like every other admin-hub tool this lays out as a plain [Column] and renders into
 * whatever scrolling parent hosts it. It must NOT scroll itself: the hub renders inside the app's
 * shared `verticalScroll` Column, and a nested `verticalScroll` is measured with an infinite height
 * budget, which throws "Vertical viewport was given infinite maximum height" and takes the screen
 * down before it can draw.
 */

/** One page of the roster table. Small, because this is a phone and the rows are tall. */
private const val ROSTER_PAGE_SIZE = 20

/**
 * How much of the pending queue the top section shows at once.
 *
 * Bounded, and the remainder is STATED rather than silently dropped — see [accessQueueNotice]. A
 * list that quietly stops is indistinguishable from a place with no records, which is the most
 * repeated bug class in this repository.
 */
private const val QUEUE_PAGE_SIZE = 25

/** The status filter's options. "" means EVERY status, by absence — see rule 3 above. */
private val STATUS_FILTERS = listOf(
    SelectOption("", "Every status"),
    SelectOption("PENDING", "Awaiting approval"),
    SelectOption("ACTIVE", "May sign in"),
    SelectOption("REJECTED", "Not approved"),
    SelectOption("SUSPENDED", "Suspended")
)

/**
 * The access roster screen. ADMIN and MASTER ADMIN — the caller gates it, and every request it
 * makes is `require_admin` on the server, which is the real authority.
 *
 * [user] is here for one reason: the tiers an admin may hand out are bounded by their own
 * (`users.assert_role`), and offering a tier the API will refuse is a button that only ever fails.
 */
@Composable
fun AccessRosterScreen(
    repository: FieldRepository,
    user: UserDto,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit = {},
    onError: (String) -> Unit = {},
    /**
     * Told after every decision, so the badge that brought the admin here stops being stale the
     * moment they act. Without it the nav would keep showing "3" for up to a minute after the
     * queue was cleared, which reads as "the approval did not work".
     */
    onPendingCountChanged: (Int) -> Unit = {}
) {
    val scope = rememberCoroutineScope()

    // null = still loading, emptyList() = genuinely none. On the QUEUE in particular that
    // distinction is the difference between "we do not know yet" and "nobody is waiting", and the
    // second of those is the one wrong answer this section can give.
    var queue by remember { mutableStateOf<List<AccessRosterDto>?>(null) }
    var queueTotal by remember { mutableStateOf(0) }
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

    // The add-an-address form. Uncontrolled state rather than a dialog: pre-admitting somebody is
    // the second reason an admin opens this screen and burying it behind a "+" would hide it.
    var newEmail by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var newNote by remember { mutableStateOf("") }
    var newRole by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }

    val roleOptions = remember(user.role) { assignableRoleOptions(user.role) }

    suspend fun reload() {
        runCatching { repository.accessRoster(page = 1, pageSize = QUEUE_PAGE_SIZE, status = "PENDING") }
            .onSuccess {
                queue = it.items
                queueTotal = it.total
                onPendingCountChanged(it.total)
            }
            .onFailure {
                // The queue's own failure must not blank the roster below, which may have loaded
                // perfectly well — and must not blank a queue that was true a moment ago, because
                // an empty queue is precisely the wrong thing to say when we do not know.
                error = it.apiErrorMessage("Unable to load the pending requests")
            }
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
                // `rows` is deliberately left standing: replacing a roster the admin can still read
                // with "nobody is on the list" reads, on THIS screen, as "the gate locked everybody
                // out" — which would be a considerably worse thing to believe than a stale list.
            }
    }

    LaunchedEffect(rosterPage, appliedSearch, statusFilter) { reload() }
    LaunchedEffect(error) { error?.let(onError) }
    LaunchedEffect(notice) { notice?.let(onMessage) }

    // Back closes the confirmation first, so a decision is never one stray press from happening or
    // from taking the admin off the screen. With nothing open and no [onBack], stay disabled so the
    // host's own handler gets the press.
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
                        "${updated.email} can sign in, as ${roleLabelFor(updated.grantedRole)}."
                    } else {
                        "${updated.email} is refused. The entry stays on the roster and can be approved later."
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
            "Who may sign in to the repository at all. An address here marked “May sign in” is " +
                "admitted; everybody else is turned away and lands in the queue below.",
            color = Muted,
            fontSize = 12.sp
        )

        error?.let { NoticeLine(it, tone = NoticeTone.ERROR) { error = null } }
        notice?.let { NoticeLine(it, tone = NoticeTone.INFO) { notice = null } }

        // ── The queue. The notification, and the reason an admin opens this screen. ───────────
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
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (queue != null) CountPill(queueTotal)
                }
                Text(
                    "Somebody proved they own one of these addresses and was turned away because it " +
                        "is not on the roster. They have been told they are waiting for an administrator, " +
                        "and nothing else happens until you decide.",
                    color = Muted,
                    fontSize = 12.sp
                )

                val pending = queue
                when {
                    pending == null -> Text("Loading the queue…", color = Muted, fontSize = 12.sp)
                    pending.isEmpty() -> Text(
                        "Nobody is waiting. New requests appear here the moment somebody is turned away.",
                        color = Muted,
                        fontSize = 12.sp
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

                // Truncation is stated, never silent — and the sentence says how to reach the rest.
                accessQueueNotice(shown = pending?.size ?: 0, total = queueTotal)?.let {
                    Text(it, color = MaterialTheme.field.warning, fontSize = 11.sp)
                }
            }
        }

        // ── Pre-admit an address. No account has to exist. ──────────────────────────────────
        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Add an address to the roster",
                    display = true,
                    color = Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Text(
                    "The email address is all that is needed and no account has to exist yet — that " +
                        "is how somebody is admitted before they have ever opened the app. The account " +
                        "is created, at the tier chosen here, the first time they sign in.",
                    color = Muted,
                    fontSize = 12.sp
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
                    label = { Text("Full name (your own note of who this is)") },
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
                    // "All the users by default join as the lowest rung unless promoted there
                    // itself" — this control is the "there itself", and the server bounds it by the
                    // admin's own tier so it can never hand out more than the admin holds.
                    "Everybody joins at the lowest rung unless you promote them here.",
                    color = Muted,
                    fontSize = 11.sp
                )
                OutlinedTextField(
                    value = newNote,
                    onValueChange = { newNote = it },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = !adding && newEmail.isNotBlank(),
                    onClick = {
                        scope.launch {
                            adding = true
                            error = null
                            notice = null
                            runCatching {
                                repository.addToAccessRoster(
                                    email = newEmail,
                                    fullName = newName,
                                    notes = newNote,
                                    grantedRole = newRole
                                )
                            }
                                .onSuccess { created ->
                                    notice = "${created.email} is on the roster as " +
                                        "${roleLabelFor(created.grantedRole)}. They can sign in with that " +
                                        "address — the account is created the first time they do."
                                    newEmail = ""
                                    newName = ""
                                    newNote = ""
                                    newRole = ""
                                    reload()
                                }
                                .onFailure {
                                    // A 409 names the existing row, gives its status and says that
                                    // changing it is an update rather than a second add. Shown
                                    // verbatim, and the search is pointed at the address so the
                                    // admin looks at the row instead of reading about it.
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

        // ── The roster itself. ──────────────────────────────────────────────────────────────
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
            // "" is a real option here (it means EVERY status), so the picker must not add its own
            // "None" alongside it — two rows meaning the same thing is a picker lying about itself.
            includeNone = false,
            onSelect = { statusFilter = it; rosterPage = 1 }
        )

        val list = rows
        when {
            list == null -> Text("Loading the roster…", color = Muted, fontSize = 12.sp)
            list.isEmpty() -> Text(
                if (appliedSearch.isNotBlank() || statusFilter.isNotBlank()) {
                    "No entry matches this search. Clear it to see every address the roster knows " +
                        "about, refused and suspended ones included."
                } else {
                    "Nobody is on the roster yet. Every account that existed when the sign-in gate " +
                        "shipped was admitted automatically, so an empty list here is worth investigating."
                },
                color = Muted,
                fontSize = 12.sp
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
                                    notice = "${it.email} is suspended. The entry stays on the roster."
                                    reload()
                                }
                                // The master admin's row is protected server-side and the 403 explains
                                // why — that it is the break-glass and the gate never applies to it.
                                .onFailure { error = it.apiErrorMessage("Unable to suspend this entry") }
                            busyId = null
                        }
                    },
                    onRestore = {
                        scope.launch {
                            busyId = entry.id
                            error = null
                            runCatching { repository.approveAccessRosterEntry(entry.id, entry.grantedRole) }
                                .onSuccess { notice = "${it.email} can sign in again."; reload() }
                                .onFailure { error = it.apiErrorMessage("Unable to restore this entry") }
                            busyId = null
                        }
                    }
                )
            }
        }

        if (list != null) {
            Text(accessRosterPageNotice(rosterPage, rosterPages, rosterTotal), color = Muted, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = rosterPage > 1, onClick = { rosterPage -= 1 }) { Text("Previous") }
                OutlinedButton(enabled = rosterPage < rosterPages, onClick = { rosterPage += 1 }) { Text("Next") }
            }
        }

        Text(
            "The master admin address is never gated and cannot be taken off this list. The roster " +
                "is a table only an administrator can edit, so an administrator locked out by it would " +
                "be an outage with no remedy inside the app — that one exemption is what makes the rest " +
                "of this screen safe to use.",
            color = Muted,
            fontSize = 11.sp
        )
    }

    confirming?.let { decision ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Refuse access for ${decision.entry.email}?") },
            text = {
                Text(
                    "They will be told this address is not approved, and told to contact an " +
                        "administrator.\n\n" +
                        // The part an admin actually needs, because the alternative design — a
                        // rejection the applicant can undo by signing in again — is the one that
                        // makes this queue unworkable.
                        "Signing in again will NOT put them back in this queue: the entry stays " +
                        "refused and only the attempt counter moves. The entry is kept either way."
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

/** The one decision that asks before it fires. Approving is reversible; refusing sets a verdict. */
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
        // THE ADDRESS IS THE HEADING, and it is the only thing about this person the roster stores.
        // No display name, no picture, no "reason for joining": the pending queue is the one screen
        // in this product where a stranger can cause content to appear, and nothing they control is
        // rendered here beyond the address they proved they own.
        Text(entry.email, color = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(accessRequestLabel(entry.requestCount), color = Muted, fontSize = 11.sp)
        SearchableSelectField(
            label = "Joins as",
            options = roleOptions,
            selectedValue = role,
            includeNone = false,
            enabled = !busy,
            onSelect = { role = it }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { onApprove(role) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (busy) "Working…" else "Approve")
            }
            OutlinedButton(
                enabled = !busy,
                onClick = onRefuse,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.weight(1f)
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
                // A name may be the admin's own note, the account's, or absent — and its absence is
                // STATED rather than left as a blank line an admin cannot tell from a bug.
                entry.fullName ?: entry.accountName ?: "Name not recorded",
                color = Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Text(entry.email, color = Muted, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(entry.status)
                Text(accessInvitationLabel(entry.status, entry.firstSeenAt), color = Muted, fontSize = 11.sp)
            }
            Text(
                accessJoinedLabel(entry.joinedAt) + " · " + roleLabelFor(entry.grantedRole),
                color = Muted,
                fontSize = 11.sp
            )
            // The two disagree the moment somebody is promoted through the users screen, and an
            // admin reading only the roster would be looking at a stale tier and believing it
            // current. So the live one is printed whenever it differs.
            entry.accountRole?.takeIf { it != entry.grantedRole }?.let {
                Text("Account is now ${roleLabelFor(it)}", color = Muted, fontSize = 11.sp)
            }
            Text(accessRequestLabel(entry.requestCount), color = Muted, fontSize = 11.sp)
            entry.notes?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Muted, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (entry.status == "ACTIVE") {
                // Never labelled "Delete". The verb has to say what happens, and what happens is
                // that the row stays.
                OutlinedButton(
                    enabled = !busy,
                    onClick = onSuspend,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Suspend") }
            } else {
                OutlinedButton(enabled = !busy, onClick = onRestore) {
                    Text(if (entry.status == "PENDING") "Approve" else "Restore")
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
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/**
 * The status, as a pill.
 *
 * COLOUR NEVER CARRIES THE MEANING ALONE — the word is always there — so the judgement survives
 * colour-blindness, a greyscale screenshot pasted into a report, and forced-colours mode. PENDING
 * is the warning colour and not the error one: it is not a refusal anybody made, it is work waiting
 * for somebody.
 */
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
            Text(accessStatusLabel(status), color = content, fontSize = 11.sp, fontWeight = FontWeight.Medium)
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
            Text(text, color = content, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Dismiss", color = content, fontSize = 12.sp) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The pure half — the wording contract with the web, asserted in AccessRosterTest
// ---------------------------------------------------------------------------------------------
//
// These are top-level functions rather than `if`s inside the composables above for the same reason
// `ui/RecordPickers.kt` exists: a rule buried in a composable can only be checked by running the
// app against a repository in the right state, and "the right state" here means somebody actually
// being turned away at sign-in. Lifted out, each one is one assertion.
//
// EVERY STRING BELOW HAS A TWIN IN `frontend/lib/accessRoster.ts`. Change one, change both — an
// admin who sees "Awaiting approval" on a laptop and "Pending" on a phone is looking at two
// features, not one.

/**
 * The tier ladder in DISPLAY order, highest first.
 *
 * Only the ORDER lives here. The labels come from [FieldPermissions.label], which is already the
 * one spelling of "Crowdsource Volunteer" in this app and is itself byte-for-byte the server's
 * `ROLE_LABELS`; a second map here would be a second opinion about a name that appears on four
 * screens.
 */
private val ROLE_LADDER = listOf(
    "MASTER_ADMIN",
    "ADMIN",
    "PROFESSOR",
    "RESEARCHER",
    "FIELD_CONTRIBUTOR",
    "CROWDSOURCE_VOLUNTEER"
)

fun roleLabelFor(role: String?): String = FieldPermissions.label(role)

/**
 * The tiers this admin may hand out: at or below their own, highest first.
 *
 * Mirrors `users.assert_role`, which is the authority — offering a tier the API refuses is a button
 * that can only ever fail, and it fails at the worst possible moment, halfway through approving
 * somebody who is standing there waiting.
 */
fun assignableRoleOptions(role: String?): List<SelectOption> {
    val rank = FieldPermissions.rank(role)
    return ROLE_LADDER
        .filter { FieldPermissions.rank(it) <= rank }
        .map { SelectOption(it, FieldPermissions.label(it)) }
}

/** What each status is called, everywhere, on both clients. */
fun accessStatusLabel(status: String): String = when (status) {
    "PENDING" -> "Awaiting approval"
    "ACTIVE" -> "May sign in"
    "REJECTED" -> "Not approved"
    "SUSPENDED" -> "Suspended"
    else -> status
}

/**
 * "Has this admitted address ever actually been used?" — the one question this screen answers that
 * no other screen can.
 *
 * NEVER THE WORD "NEVER". A null `firstSeenAt` is a statement about an INVITATION, not about a
 * person: an admin chasing five addresses added in March needs to tell "they have not opened it
 * yet" from "we never let them in", and an entry that is not admitted has no invitation outstanding
 * at all, so it says so instead.
 */
fun accessInvitationLabel(status: String, firstSeenAt: String?): String = when {
    !firstSeenAt.isNullOrBlank() -> "Signed in"
    status != "ACTIVE" -> "No access to take up"
    else -> "Not signed in yet"
}

/**
 * How the person got here: an admin put them on the list, or they asked and were turned away.
 *
 * `requestCount` is 0 for a row an administrator created and rises by one per refused attempt. A
 * rising count on a refused row is how an admin notices somebody who keeps trying and goes to talk
 * to them — which is the whole reason a rejection stays a rejection rather than re-opening.
 */
fun accessRequestLabel(requestCount: Int): String = when {
    requestCount <= 0 -> "Added by an admin"
    requestCount == 1 -> "Asked once"
    else -> "Asked $requestCount times"
}

/** The date of joining the platform, or the fact that there is not one yet. */
fun accessJoinedLabel(joinedAt: String?): String =
    if (joinedAt.isNullOrBlank()) "Not admitted yet" else "Joined ${joinedAt.take(10)}"

/**
 * What the queue is NOT showing, or null when it is showing everything.
 *
 * A list that quietly stops is indistinguishable from a place with no records. This sentence is the
 * difference, and it names the way to the rest rather than merely admitting there is a rest.
 */
fun accessQueueNotice(shown: Int, total: Int): String? {
    if (total <= shown) return null
    return "Showing the $shown most recent of $total waiting requests. " +
        "Set the status filter below to “Awaiting approval” to page through the rest."
}

/**
 * The roster's own position line. Prints "Page 0 of 0" on an empty list deliberately, exactly as
 * the web's `Pagination` does: an admin comparing the two screens should not have to wonder whether
 * one of them is broken.
 */
fun accessRosterPageNotice(page: Int, pages: Int, total: Int): String =
    "Page ${if (pages > 0) page else 0} of $pages · $total ${if (total == 1) "entry" else "entries"}"
