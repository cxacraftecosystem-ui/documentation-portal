package com.fieldrepository.app.ui.trace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.ui.SearchableSelectField
import com.fieldrepository.app.ui.SelectOption
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see ui/FieldText.kt.
import com.fieldrepository.app.ui.Text
import com.fieldrepository.app.ui.field

/**
 * **THE TWO PRESET PICKERS, AND THE SUGGESTION ROW NEITHER CLIENT CURRENTLY FILLS.**
 *
 * ── WHY THESE ARE THE FIRST TWO CONTROLS ON THE SCREEN ────────────────────────────────────────
 *
 * A style is a COMPLETE PARAMETER TREE, not a diff: "a user who switches styles expects the second one
 * to look like itself rather than like a blend of the two". Nothing else in this feature moves
 * thirty-one values in one tap, which on a phone is the difference between a usable surface and a wall
 * of sliders. A subject is a MODIFIER ON A STYLE, not a second style list — it nudges denoise, blob
 * area and engine choice for the MATERIAL while leaving the look the style chose intact — and the
 * material is the one fact the person standing in the workshop knows and the engine cannot.
 *
 * ── ALL TWENTY STYLES SHIP, INCLUDING THE TEN THAT MAKE NO OBVIOUS SENSE HERE ─────────────────
 *
 * `TraceEnginePresets.kt`'s header holds that argument in full. What changes on a handset is ORDER and
 * SEARCHABILITY, not membership — see [traceStyleOptions].
 *
 * ── THE LISTS ARE READ FROM THE ENGINE, NEVER TRANSCRIBED ─────────────────────────────────────
 *
 * [TraceEngineRuntime.presets] returns `Styles.ALL` and `Subjects.ALL` as the engine holds them. There
 * is no Kotlin table of style names in this repository and there must not be one.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Turning the engine's tables into picker rows
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The twenty styles as picker rows, with **the group name inside the label**.
 *
 * "Line art · Clean line", not a "Line art" header above a "Clean line" row, and the difference is the
 * search box. `ui/SearchableSelect.kt` opens its searchable sheet at eight options and matches on label,
 * hint and value with whitespace-split terms, so a group folded into the label means somebody can type
 * "tech" and get the technical styles — which a sticky group header cannot do. The engine's own order
 * is preserved because [styles] arrives in it, and that order is NOT grouped-contiguous, which is the
 * second half of why headers would have been wrong here.
 *
 * The upstream's own [TracePreset.description] becomes the row's trailing hint, so the sentence that
 * explains a style is visible while choosing rather than only after.
 */
fun traceStyleOptions(styles: List<TracePreset>): List<SelectOption> = styles.map { style ->
    SelectOption(
        value = style.id,
        label = if (style.group.isBlank()) style.name else "${style.group} · ${style.name}",
        hint = style.description,
    )
}

/**
 * The subjects as picker rows. A flat list, so there is no group to fold in.
 *
 * The COUNT is the runtime's, not this file's: ten through the TypeScript engine, twelve through the
 * vendored Kotlin one — see `TraceEnginePresets.kt`, which owns that divergence and the sentence a
 * researcher reads about it. This maps whatever arrives.
 */
fun traceSubjectOptions(subjects: List<TracePreset>): List<SelectOption> = subjects.map { subject ->
    SelectOption(value = subject.id, label = subject.name, hint = subject.description)
}

/** A preset's own name, or the raw id when the engine's table has no such row. */
fun tracePresetName(presets: List<TracePreset>, id: String): String =
    presets.firstOrNull { it.id == id }?.name ?: id

/* ────────────────────────────────────────────────────────────────────────────
 * The two controls
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * "Style" — the control that sets every other control at once.
 *
 * @param onPick called with the chosen id. The panel applies the preset through the ENGINE's own
 *   `Styles.byId(id).params`, never by merging a Kotlin copy of it.
 */
@Composable
fun TraceStylePicker(
    styles: List<TracePreset>,
    selectedId: String,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SearchableSelectField(
            label = "Style",
            options = traceStyleOptions(styles),
            selectedValue = selectedId,
            placeholder = "Choose a style",
            // A trace always has a style — every preset writes its own id into the tree, so "nothing
            // selected" is not a state the engine can be in, and offering it would be offering a value
            // nothing accepts.
            includeNone = false,
            enabled = enabled,
        ) { picked -> if (picked.isNotBlank()) onPick(picked) }
        Text(
            styles.firstOrNull { it.id == selectedId }?.description
                ?: "A style sets every control at once. Pick one, then adjust.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

/**
 * "What this is a drawing of" — the subject adjustment.
 *
 * ── NO "NONE" ROW, AND THAT IS THE ENGINE'S SHAPE RATHER THAN AN OMISSION ─────────────────────
 *
 * `adjust` is a one-way modifier applied to the tree that is there, which is what makes "un-picking"
 * one meaningless. Somebody who wants the style back picks the style again, which is one tap and is
 * honest about what it does. Offering an "undo the subject" row would be offering an operation the
 * engine does not have.
 *
 * ── AND ON THIS ENGINE IT COMPOUNDS, WHICH EVERY ROW SAYS ─────────────────────────────────────
 *
 * The web's subject tables are absolute overrides and re-applying one is a no-op; this engine's are
 * relative, so a second tap adjusts a second time. Nothing here "fixes" a vendored judgement —
 * `TraceEnginePresets.kt` carries the measurement and the decision, [TRACE_SUBJECT_COMPOUNDS_NOTE] is
 * appended to every row's hint, and the panel's own overwrite notice then names every setting that
 * actually moved.
 *
 * The panel opens with this seeded from whatever the host knew, if anything. Seeded, VISIBLE and
 * changeable, never silently applied.
 */
@Composable
fun TraceSubjectPicker(
    subjects: List<TracePreset>,
    selectedId: String,
    enabled: Boolean,
    /** True when this selection came from the host's hint rather than from somebody's tap. */
    seededFromHost: Boolean,
    onPick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SearchableSelectField(
            label = "What this is a drawing of",
            options = traceSubjectOptions(subjects),
            selectedValue = selectedId,
            placeholder = "Choose a material",
            includeNone = false,
            enabled = enabled,
        ) { picked -> if (picked.isNotBlank()) onPick(picked) }
        Text(
            buildString {
                append(
                    subjects.firstOrNull { it.id == selectedId }?.description
                        ?: "Adjusts the settings for the material, and leaves the style's look alone.",
                )
                if (seededFromHost) {
                    // NAMED, not hidden. The upstream's contract for work done on somebody's behalf is
                    // "a named suggestion with a one-tap override", and a pre-selection nobody is told
                    // about is not a suggestion — it is a decision somebody else made in their name.
                    append(" Chosen from what this record already says; change it if it is wrong.")
                }
            },
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The suggestion row
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * "The engine read this photograph and suggests X" — one row, for a runtime that can fill it.
 *
 * ── IT IS NEVER DRAWN ON THIS BUILD, AND THAT IS STATED RATHER THAN LEFT TO BE DISCOVERED ─────
 *
 * [TraceResult.suggestedStyleId] is always empty here: the vendored Kotlin classifier answers with a
 * SENTENCE naming a preset from another product's table, where the TypeScript answers with an id from
 * this one's. [TRACE_NO_SUGGESTION_NOTE] holds the three ways of recovering an id and why each is worse
 * than an empty field. So the first line of this composable returns, every time, today.
 *
 * ── SO WHY IS IT HERE ─────────────────────────────────────────────────────────────────────────
 *
 * Because the empty string is a state the panel has to handle anyway — a preview produces one — and
 * because deleting the row would mean that the day the classifier grows an id, somebody writes this
 * surface again from memory: the live region, the "propose, never apply" rule and the wording would all
 * be re-decided, and the second attempt would be the one that applies the style on arrival. The row is
 * nine lines of layout. The argument below is what is actually being kept.
 *
 * ── IT PROPOSES AND NEVER APPLIES ─────────────────────────────────────────────────────────────
 *
 * The button is the application. Nothing happens on arrival. Applying a style is destructive — it
 * replaces every setting — so a suggestion that applied itself would silently discard somebody's tuning
 * at the moment their trace finished.
 *
 * Drawn only when there is something to say: absent when the suggestion is the style already chosen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TraceStyleSuggestion(
    styles: List<TracePreset>,
    suggestedStyleId: String,
    currentStyleId: String,
    enabled: Boolean,
    onApply: (String) -> Unit,
) {
    if (suggestedStyleId.isBlank() || suggestedStyleId == currentStyleId) return
    val suggested = styles.firstOrNull { it.id == suggestedStyleId } ?: return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
            .padding(8.dp)
            // A sentence that appears when a trace finishes, in a panel the researcher may have
            // scrolled away from. Polite rather than assertive: it is worth knowing and never urgent.
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Looking at this photograph, the engine suggests the “${suggested.name}” style. " +
                suggested.description,
            color = MaterialTheme.field.body,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = { onApply(suggested.id) },
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Use the “${suggested.name}” style", fontSize = 13.sp)
            }
        }
    }
}
