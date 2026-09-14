package com.fieldrepository.app.ui.trace

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.ui.LocalAppPreferences
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see ui/FieldText.kt for why a
// bare Material `Text` here would quietly set this panel's headings in the body face.
import com.fieldrepository.app.ui.Text
import com.fieldrepository.app.ui.field

/**
 * **THE FIVE SMALL THINGS EVERY SURFACE IN THIS PANEL IS BUILT OUT OF.**
 *
 * A label, a chip, a note, a disclosure header and a way out. They are here rather than inlined at
 * their call sites so that the panel, the frame chooser, the comparator and the export card have ONE
 * idea of what each of them is — a chip that announces its state on one surface and not on another is
 * two controls a screen reader describes differently, and the four surfaces stack in one column.
 *
 * Everything in this file is `internal` and scoped to this package. It is deliberately NOT a
 * contribution to the app's general component set: `ui/FieldComponents.kt` is where a shared primitive
 * would go, and putting these there would be editing a file this work is not permitted to touch — and
 * would also claim, wrongly, that the rest of the app had agreed to them.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * The small ones
 * ──────────────────────────────────────────────────────────────────────────── */

/** The small caption above a group of controls. */
@Composable
internal fun TracePanelLabel(text: String) {
    Text(text, color = MaterialTheme.field.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
}

/**
 * A chip, drawn selected or not — and SAYING which it is.
 *
 * ── THE STATE IS ANNOUNCED, NOT ONLY FILLED ───────────────────────────────────────────────────
 *
 * A chip whose selection is carried by the fill and only by the fill is invisible in greyscale,
 * invisible to somebody who cannot tell two purples apart in direct sunlight, and **silent to
 * TalkBack**, which reads out a row of identically-shaped buttons with nothing to say which one is in
 * force. That matters most on the comparator, where the chips answer *which picture am I looking at*
 * and a screen reader was otherwise given no way to hear the answer off the control that sets it.
 *
 * `stateDescription`, exactly as [TracePanelDisclosureHeader] announces "Expanded" / "Collapsed": one
 * string TalkBack reads after the label, no role change, and it cannot fall out of step with the fill
 * because both are read off this one parameter.
 *
 * **"Selected" IS THE RIGHT NOUN HERE AND THE WRONG ONE THERE**, which is worth stating because the
 * disclosure header argues against it. A disclosure is not a choice among options, so "selected" tells
 * a reader nothing about what pressing it reveals. A chip in a row of chips IS a choice among options,
 * and "selected" is what it is.
 *
 * @param isChoice false for a chip that ACTS rather than chooses. Announcing "not selected" on one of
 *   those would be a state description for a state it does not have, and would tell somebody they had
 *   failed to select something that cannot be selected.
 */
@Composable
internal fun TracePanelChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    isChoice: Boolean = true,
    onClick: () -> Unit,
) {
    val announce = if (isChoice) {
        Modifier.semantics { stateDescription = if (selected) "Selected" else "Not selected" }
    } else {
        Modifier
    }
    // 40dp AND NOT THE APP'S 48, DELIBERATELY: chips come in rows of four to eight inside an already
    // tall card, they are separated by 6dp of their own, and Material's own chip metrics stop here. The
    // floor still grows with the font scale, because it is a minimum rather than a height.
    val shape = Modifier
        .heightIn(min = 40.dp)
        .then(announce)
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = shape) {
            Text(label, fontSize = 12.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = shape) {
            Text(label, fontSize = 12.sp)
        }
    }
}

/**
 * The assumption card, and the refusal card. Same shape; the colour says how loud it is.
 *
 * The icon is DECORATIVE and carries no `contentDescription`: the sentence carries the meaning, so it
 * survives greyscale, colour-blindness and a screen reader that never sees the icon. [polite] makes it
 * a live region, for the notes that APPEAR in answer to something the researcher did — a reader who
 * cannot see the layout gets nothing at all from a panel that quietly materialises below the button
 * they just activated.
 */
@Composable
internal fun TracePanelNote(warning: Boolean, text: String, polite: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (warning) MaterialTheme.field.warningContainer else MaterialTheme.field.surface50,
                RoundedCornerShape(8.dp),
            )
            .border(
                1.dp,
                if (warning) MaterialTheme.field.warning else MaterialTheme.field.hairline,
                RoundedCornerShape(8.dp),
            )
            .padding(8.dp)
            .then(if (polite) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = if (warning) MaterialTheme.field.onWarningContainer else MaterialTheme.field.muted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text,
            color = if (warning) MaterialTheme.field.onWarningContainer else MaterialTheme.field.body,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The card's own header, and its two doors
 * ──────────────────────────────────────────────────────────────────────────── */

/** The one word both doors say. A constant so the two can never come to be labelled differently. */
internal const val TRACE_PANEL_COLLAPSE_WORD = "Close"

/**
 * The card's title row, which IS the control that opens and closes it.
 *
 * ── THREE SEPARATE THINGS A SCREEN READER IS OWED, AND A CHEVRON CARRIES NONE OF THEM ─────────
 *
 * A disclosure is not a choice among options: `selectable` makes TalkBack announce "selected" / "not
 * selected", which for a section that opens and closes is the wrong noun and gives a reader no idea
 * that pressing it reveals something. `stateDescription` announces "Expanded" / "Collapsed",
 * `onClickLabel` says what the press will DO in the verb grammar TalkBack speaks it in, and
 * [Role.Button] says what kind of thing it is.
 *
 * ── THE TITLE IS THE SAME IN BOTH STATES ──────────────────────────────────────────────────────
 *
 * A control whose label changes when you press it reads as a different control. One title per card,
 * chosen once — see [TRACE_CARD_TITLE] for this card's, and why it is the portal's spelling.
 *
 * ── THE CHEVRON TURNS, AND IT IS NOT THE ONLY THING SAYING WHICH WAY IS OUT ───────────────────
 *
 * The rotation is decoration on top of the state description and, when collapsed, on top of the summary
 * line underneath it — nothing here exists only as motion, and nothing here is carried by colour. The
 * turn collapses to [snap] under `LocalAppPreferences.current.reducedMotion`, which is this app's own
 * stillness switch and is read the same way `MapScreen` and `AppNavigation` read it.
 */
@Composable
internal fun TracePanelDisclosureHeader(
    /** Decorative, always: the title beside it is what carries which card this is. */
    icon: ImageVector,
    title: String,
    expanded: Boolean,
    toggleEnabled: Boolean,
    /** What TalkBack is told the press will DO when the card is shut. */
    expandAction: String,
    /** The other direction of the same sentence. */
    collapseAction: String,
    onToggle: () -> Unit,
) {
    val reduceMotion = LocalAppPreferences.current.reducedMotion
    val turn by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = 180),
        label = "traceCardChevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { stateDescription = traceDisclosureState(expanded) }
            .clickable(
                enabled = toggleEnabled,
                onClickLabel = if (expanded) collapseAction else expandAction,
                role = Role.Button,
                onClick = onToggle,
            )
            // The 48dp touch floor this app applies wherever a control was thought about. It is a touch
            // target, not decoration.
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.field.muted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            title,
            // `weight(1f)` AND NOT A FIXED WIDTH, which is what keeps this row honest at the largest
            // font scale: the title wraps to two or three lines, the row grows with it, and the chevron
            // and the close button stay on it rather than being pushed off the edge.
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        // THE HEADER'S DOOR. A button is a merging semantics node of its own, so it stays a separate
        // TalkBack target inside this clickable row instead of being swallowed by it, and it consumes
        // its own taps so pressing it cannot also fire the row.
        if (expanded) TracePanelCollapseButton(prominent = false, title = title, onClick = onToggle)
        Icon(
            Icons.Filled.KeyboardArrowDown,
            // Decorative: the row's own state description already says which state this is, in words.
            contentDescription = null,
            tint = MaterialTheme.field.muted,
            modifier = Modifier
                .size(20.dp)
                .rotate(turn),
        )
    }
}

/**
 * The way out, drawn TWICE on purpose — once in the header and once at the foot of the contents.
 *
 * The report this answers is not "there is no close button"; there is one. It is that the only one sits
 * at the top of a card the researcher has just scrolled to the bottom of. This panel's open half is a
 * frame chooser, a comparator, two dozen control rows, a preview switch, three buttons and an export
 * card tall. So the second door is placed after the work ends, and BOTH are this one composable saying
 * [TRACE_PANEL_COLLAPSE_WORD], so the two can never come to be labelled differently.
 *
 * ── AND THE FOOT'S COPY NAMES THE CARD, WHICH THE HEADER'S DOES NOT NEED TO ───────────────────
 *
 * At the foot of a long panel there is no heading in view to say what would be closing. The header's
 * copy stays bare, deliberately: it sits ON the title row, inside the same `Row` as the name it would
 * be repeating, so naming the card there would print it twice on one line.
 *
 * @param prominent the foot's copy is full width and outlined, because it has to be findable at the end
 *   of a long card; the header's is a compact text button. The SHAPE differs, and so does whether the
 *   card is NAMED; the VERB does not.
 * @param title the card's own name, for the foot's copy. Unused by the header's, which is drawn beside
 *   it.
 */
@Composable
internal fun TracePanelCollapseButton(prominent: Boolean, title: String, onClick: () -> Unit) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            if (prominent) "$TRACE_PANEL_COLLAPSE_WORD “$title”" else TRACE_PANEL_COLLAPSE_WORD,
            fontSize = 12.sp,
        )
    }
    if (prominent) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            content = content,
        )
    } else {
        TextButton(
            onClick = onClick,
            modifier = Modifier.heightIn(min = 48.dp),
            content = content,
        )
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The warning before a destructive write
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * **"THERE IS SOMETHING HERE ALREADY, AND THIS BUTTON REPLACES IT."**
 *
 * ── WHY THE PANEL SAYS THIS AT ALL WHEN IT CANNOT SEE THE FIELD ───────────────────────────────
 *
 * It cannot, and that is exactly why the host passes `currentFileName`. The panel knows only that it is
 * about to hand one derived file to `onAttach`; whether that lands beside an existing one or over it is
 * the host's business, and the host is the half that can say. So the panel prints what it is TOLD and
 * claims nothing else.
 *
 * **THE WARNING BEFORE A DESTRUCTIVE WRITE IS THE LAST PLACE TO BE INVENTIVE.** Somebody deciding
 * whether to overwrite something they cannot get back needs a sentence they can check at a glance, and
 * a sentence they have already read once is that sentence the second time.
 *
 * ── NULL RATHER THAN AN EMPTY STRING ──────────────────────────────────────────────────────────
 *
 * Nothing there is not a quieter warning, it is the absence of one — the same contract
 * [traceCardSummary] and [traceOverwriteNotice] hold, so a caller cannot accidentally render an empty
 * warning box. Blank-checked rather than only null-checked because a display name can arrive as
 * whitespace from a content resolver.
 *
 * Pure, so `TracePanelTest` can pin the wording on the JVM with no composition to run it in.
 */
internal fun tracePanelReplaceWarning(current: String?): String? {
    val trimmed = current?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return "“$trimmed” is attached here now. This replaces it."
}
