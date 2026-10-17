package com.fieldrepository.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * C18 (owner decision 2026-10-17) — FIND THE ARTISAN BEFORE TYPING THEM IN.
 *
 * A duplicate used to surface only at the Aadhaar box, about ten fields in. A new artisan now opens
 * on this card: an Aadhaar number goes to the exact lookup (`GET /artisans/lookup/aadhaar`), a name
 * to the list's own search, and a match opens the record already there. "Record a new artisan" is
 * always one tap away and carries what was typed into the form. The web's `ArtisanLookup`, rule for
 * rule (`artisanLookupRules.ts`). The number is held in memory only, never stored.
 */
enum class LookupKind { EMPTY, AADHAAR, NAME }

/** Twelve digits (spaces allowed) are an Aadhaar number; anything else is a name to search for. */
fun lookupKind(query: String): LookupKind {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return LookupKind.EMPTY
    val digits = trimmed.replace(Regex("""\s"""), "")
    return if (Regex("""^[\d\s]{12,14}$""").matches(trimmed) && digits.length == 12) LookupKind.AADHAAR else LookupKind.NAME
}

/** What "Record a new artisan" carries into the form: the typed name, or the typed Aadhaar digits. */
data class LookupStart(val name: String = "", val aadhaar: String = "")

fun lookupStart(query: String): LookupStart = when (lookupKind(query)) {
    LookupKind.AADHAAR -> LookupStart(aadhaar = query.replace(Regex("""\s"""), ""))
    LookupKind.NAME -> LookupStart(name = query.trim())
    LookupKind.EMPTY -> LookupStart()
}

/**
 * "Not shared by artisan" is offered only while no Aadhaar is on file. It records a refusal; it
 * never retracts a stored number, which would be data loss under a 200. The web's
 * `notSharedOffered` in ArtisanForm.tsx, rule for rule.
 */
fun aadhaarNotSharedOffered(storedAadhaar: String?): Boolean = storedAadhaar.isNullOrBlank()

/** The Aadhaar the body sends: blank only for an honoured "not shared" on a record without one. */
fun aadhaarForBody(typed: String, notShared: Boolean, storedAadhaar: String?): String =
    if (notShared && aadhaarNotSharedOffered(storedAadhaar)) "" else typed.trim()

private data class LookupMatch(val id: String, val name: String, val detail: String)

@Composable
fun ArtisanLookupCard(
    repository: FieldRepository,
    /** An existing artisan's record, opened read-only. */
    onOpen: (String) -> Unit,
    onStart: (LookupStart) -> Unit,
    /** The Aadhaar shape check the form itself uses, so the two cannot disagree. */
    aadhaarProblem: (String) -> String?,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var matches by remember { mutableStateOf<List<LookupMatch>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun find() {
        val kind = lookupKind(query)
        if (kind == LookupKind.EMPTY || busy) return
        busy = true
        problem = null
        scope.launch {
            try {
                matches = if (kind == LookupKind.AADHAAR) {
                    val digits = query.replace(Regex("""\s"""), "")
                    val invalid = aadhaarProblem(digits)
                    if (invalid != null) {
                        problem = invalid
                        null
                    } else {
                        val found = repository.lookupArtisanByAadhaar(digits)
                        listOfNotNull(found.artisan?.takeIf { found.found }?.let { a ->
                            LookupMatch(a.id, a.name, listOfNotNull(a.craft, a.place).filter { it.isNotBlank() }.joinToString(" · "))
                        })
                    }
                } else {
                    repository.artisansSearchPage(query.trim(), 1).items.take(10).map { a ->
                        LookupMatch(a.id, a.name, listOfNotNull(a.craft?.name, a.place).filter { it.isNotBlank() }.joinToString(" · "))
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                problem = error.userMessage("Couldn't search. You can still record a new artisan.")
                matches = null
            } finally {
                busy = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Find the artisan first", display = true, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
        Text("Search by Aadhaar number or name.", color = MaterialTheme.field.muted, fontSize = 12.sp)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; matches = null; problem = null },
            label = { Text("Aadhaar number or name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { find() }),
            modifier = Modifier.fillMaxWidth()
        )
        ActionRow {
            OutlinedButton(onClick = { find() }, enabled = !busy && lookupKind(query) != LookupKind.EMPTY) {
                Text(if (busy) "Searching…" else "Find")
            }
            Button(onClick = { onStart(lookupStart(query)) }) { Text("Record a new artisan") }
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        matches?.let { found ->
            if (found.isEmpty()) {
                Text("No match. Record them as a new artisan.", color = MaterialTheme.field.body, fontSize = 13.sp)
            } else {
                found.forEach { match ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(match.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            if (match.detail.isNotEmpty()) Text(match.detail, color = MaterialTheme.field.muted, fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = { onOpen(match.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open") }
                    }
                }
            }
        }
    }
}
