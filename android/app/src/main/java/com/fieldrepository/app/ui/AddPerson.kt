package com.fieldrepository.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.data.AccountAlreadyExists
import com.fieldrepository.app.data.PasswordMode
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.apiErrorMessage
import kotlinx.coroutines.launch

/**
 * "Add a person" on Manage users: one step makes the account, lets the address in at that role and
 * puts them on their role's own list — all on the server, all or nothing (`POST /users`).
 *
 * The temporary password a "make one for me" answer carries exists only in this card's state until
 * it is dismissed: not logged, not stored. A 409 for an address that already has an account offers
 * [onOpenExisting] instead of a second account.
 */
@Composable
fun AddPersonCard(
    repository: FieldRepository,
    actorRole: String?,
    actorIsMaster: Boolean,
    onCreated: () -> Unit,
    onOpenExisting: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val roles = remember(actorRole, actorIsMaster) { creatableRoles(actorRole, actorIsMaster) }
    if (roles.isEmpty()) return

    var open by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var role by remember(roles) { mutableStateOf(defaultNewRole(roles)) }
    var mode by remember { mutableStateOf(PasswordMode.SET) }
    var password by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var mustChange by remember { mutableStateOf(true) }
    var sendLink by remember { mutableStateOf(true) }
    var mailAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { mailAvailable = repository.mailAvailable() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var existingId by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    var temporary by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    fun reset() {
        name = ""; email = ""; password = ""; mode = PasswordMode.SET; mustChange = true; sendLink = true
        role = defaultNewRole(roles); error = null; existingId = null
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            done?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            temporary?.let { secret ->
                OutlinedTextField(
                    value = secret,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Temporary password") },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(ADD_PERSON_TEMPORARY_NOTE, color = MaterialTheme.field.muted, fontSize = 12.sp)
                ActionRow {
                    Button(
                        onClick = {
                            copied = runCatching {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Temporary password", secret))
                            }.isSuccess
                        },
                    ) { Text(if (copied) "Copied" else "Copy") }
                    OutlinedButton(
                        onClick = { temporary = null; copied = false; done = null },
                    ) { Text("Done") }
                }
            }
            if (!open) {
                OutlinedButton(onClick = { open = true; done = null }, modifier = Modifier.fillMaxWidth()) {
                    Text(ADD_PERSON_HEADING)
                }
                return@Column
            }
            Text(ADD_PERSON_HEADING, display = true, fontWeight = FontWeight.SemiBold)
            Text(ADD_PERSON_INTRO, color = MaterialTheme.field.muted, fontSize = 12.sp)
            OutlinedTextField(
                value = name, onValueChange = { name = it; error = null },
                label = { Text("Full name *") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = email, onValueChange = { email = it; error = null; existingId = null },
                label = { Text("Email *") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            // The shared one-of-many (SearchableSelect.SearchableSelectField), never a bare menu.
            SearchableSelectField(
                label = "Role",
                options = roles.map { SelectOption(it, FieldPermissions.label(it)) },
                selectedValue = role.orEmpty(),
                placeholder = "Choose a role",
                includeNone = false,
                onSelect = { picked -> role = picked.ifBlank { null } }
            )
            Text(ADD_PERSON_SIGN_IN_QUESTION, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            listOf(
                PasswordMode.SET to ADD_PERSON_MODE_SET,
                PasswordMode.GENERATE to ADD_PERSON_MODE_GENERATE,
                PasswordMode.NONE to ADD_PERSON_MODE_NONE,
            ).forEach { (option, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .selectable(selected = mode == option, role = Role.RadioButton, onClick = { mode = option })
                ) {
                    RadioButton(selected = mode == option, onClick = null)
                    Text(label, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
            when (mode) {
                PasswordMode.SET -> {
                    OutlinedTextField(
                        value = password, onValueChange = { password = it; error = null },
                        label = { Text("Password *") },
                        supportingText = { Text(passwordRuleLine()) },
                        singleLine = true,
                        visualTransformation = passwordTransformation(reveal),
                        trailingIcon = { PasswordRevealIcon(reveal, { reveal = !reveal }) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    CheckRow(ADD_PERSON_MUST_CHANGE, mustChange) { mustChange = it }
                }
                PasswordMode.NONE -> if (mailAvailable) CheckRow(ADD_PERSON_SEND_LINK, sendLink) { sendLink = it }
                else -> Unit
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            }
            existingId?.let { id ->
                OutlinedButton(
                    onClick = { onOpenExisting(id); open = false; reset() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(OPEN_THEIR_ACCOUNT) }
            }
            ActionRow {
                Button(
                    enabled = !busy,
                    onClick = {
                        val problem = addPersonProblem(name, email, role, mode, password)
                        if (problem != null) { error = problem; return@Button }
                        val chosen = mode
                        val body = addPersonBody(name, email, role!!, chosen, password, mustChange, sendLink, mailAvailable)
                        scope.launch {
                            busy = true
                            error = null
                            existingId = null
                            runCatching { repository.createAccount(body) }
                                .onSuccess { created ->
                                    done = addPersonDoneLine(
                                        created.name.ifBlank { body.name },
                                        chosen,
                                        linkEmailed = created.passwordLink != null,
                                        linkError = created.passwordLinkError
                                    )
                                    temporary = created.temporaryPassword?.takeIf { chosen == PasswordMode.GENERATE }
                                    copied = false
                                    open = false
                                    reset()
                                    onCreated()
                                }
                                .onFailure { failure ->
                                    if (failure is AccountAlreadyExists) {
                                        error = failure.message
                                        existingId = failure.existingId
                                    } else {
                                        error = failure.apiErrorMessage("The account could not be created. Try again.")
                                    }
                                }
                            busy = false
                        }
                    },
                ) { Text(if (busy) "Adding…" else "Add") }
                TextButton(enabled = !busy, onClick = { open = false; reset() }) {
                    Text("Cancel")
                }
            }
        }
    }
}

/** An on/off choice on the add-person form: the shared switch ([AppToggle]), never a checkbox. */
@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    AppToggle(label = label, checked = checked, onCheckedChange = onChange)
}
