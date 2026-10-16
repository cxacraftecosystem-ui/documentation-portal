package com.fieldrepository.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/**
 * THE EYE TOGGLE FOR THE HANDSET'S THREE SECRET BOXES — the twin of the web's
 * `components/ui/PasswordReveal.tsx`, added the same day and for the same reason.
 *
 * Until 2026-08-30 all three of them — the sign-in password (`MainActivity`), the repository API
 * key (`ApiKeysScreen`) and a person's own AI key (`MyAiKeysScreen`) — were a hardcoded
 * `PasswordVisualTransformation()` with no way to look at what had been typed. On a phone that is
 * worse than it is on a laptop, not better: the value is entered on a soft keyboard with
 * autocorrect, often outdoors, often a fifty-character key pasted from a clipboard that may have
 * truncated it, and the failure surfaces later as "the provider rejected this".
 *
 * ── WHAT THIS FILE IS ─────────────────────────────────────────────────────────────────────────
 *
 * Two tiny helpers and no wrapper around `OutlinedTextField`. The three call sites differ in almost
 * every other argument — one has keyboard actions and a placeholder, one has a label, one has
 * neither — so a wrapper would have grown a parameter per site. Each keeps its own field and passes
 * :func:`passwordTransformation` and :func:`PasswordRevealIcon`.
 *
 * ── THE TWO RULES THAT ARE EASY TO GET WRONG ON THIS PLATFORM ────────────────────────────────
 *
 * * **The content description names the ACTION the press will take** ("Show password" while it is
 *   hidden), matching the web and matching every system keyboard's own reveal. `stateDescription`
 *   carries the current state separately, because TalkBack announcing only a changing label reads
 *   as two different buttons rather than one toggle — the platform's counterpart of `aria-pressed`.
 * * **The reveal is `remember`ed by the CALLER and never persisted.** Not to preferences, not to a
 *   saved-state bundle. A phone is handed around a workshop, and a reveal that survived the screen
 *   being reopened would put the next person's key in front of whoever is holding it.
 */

/** `VisualTransformation.None` while revealed, the mask otherwise. Spelled once so the three sites
 *  cannot end up with three different ideas of what "revealed" looks like. */
fun passwordTransformation(revealed: Boolean): VisualTransformation =
    if (revealed) VisualTransformation.None else PasswordVisualTransformation()

/**
 * The trailing icon for a secret box.
 *
 * [noun] is what the box holds, for the spoken name — two of the three call sites hold an API KEY,
 * and "Show password" on a field labelled "Paste your key" describes a control that is not there.
 */
@Composable
fun PasswordRevealIcon(revealed: Boolean, onToggle: () -> Unit, noun: String = "password") {
    IconButton(
        onClick = onToggle,
        modifier = Modifier.semantics {
            stateDescription = if (revealed) "Shown" else "Hidden"
        }
    ) {
        Icon(
            imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            contentDescription = if (revealed) "Hide $noun" else "Show $noun"
        )
    }
}
