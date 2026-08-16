package com.fieldrepository.app.ui.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt.
import com.fieldrepository.app.ui.Text
import com.fieldrepository.app.ui.field

/**
 * The contextual formatting bar — one bar, belonging to the editor that currently has the caret.
 *
 * ── WHY CONTEXTUAL AND NOT PERMANENT ──────────────────────────────────────────────────────────
 *
 * A product form carries four narrative boxes and a craft form one. A formatting bar drawn
 * permanently above each of them is four identical bars stacked down one phone screen, which pushes
 * the next actual question below the fold and gives no clue which bar acts on which field. The
 * browser solves it the same way (`frontend/components/richtext/RichTextEditor.tsx` renders the bar
 * only while the field is active), and the two clients agreeing on that is what keeps a researcher
 * who uses both from having to learn the control twice.
 *
 * (Ported verbatim otherwise, from an application whose stages carry a dozen such fields — where the
 * same rule was worth three times as much. The comments below that name a designer or a stage are
 * that application's; the rule they argue for is unchanged here.)
 *
 * ── THE ONE LINE THAT MAKES A FORMATTING BAR WORK AT ALL ──────────────────────────────────────
 *
 * `focusProperties { canFocus = false }` on the container. Every control in here acts on the
 * SELECTION inside a text field that is somewhere else on the screen, and a control that takes the
 * focus when it is pressed destroys that selection on the way in: by the time the click handler
 * runs there is nothing selected to embolden, the soft keyboard has closed, and the press appears to
 * do nothing whatsoever. That is the single most reported bug in every hand-rolled editor, on every
 * platform, and it is the reason the web version cancels `mousedown` on the bar and on each button.
 * Android needs the same guarantee stated differently — Compose moves focus through the focus
 * system, not through pointer defaults, so refusing focus for the whole subtree is the exact
 * equivalent and it cannot be forgotten on a button added later.
 *
 * Refusing Compose focus does NOT make the bar unreachable to a screen reader: TalkBack navigates
 * accessibility nodes, which `clickable` still publishes, and each button below carries a role, a
 * label and — for the toggles — an on/off state description.
 *
 * ── THE BUTTON SET, AND WHAT DECIDES ITS ACTIVE STATE ─────────────────────────────────────────
 *
 * The set is the web's, minus find-and-replace and full screen (see the header of
 * `RichTextEditor.kt` for why those two are absent rather than pending). Everything here is driven
 * by [RichToolbarState], which `RichTextOps.toolbarState` computes, and the rules it encodes are not
 * negotiable at this layer:
 *
 *  - a MARK is lit only when EVERY character in the selection carries it, so a half-bold selection
 *    shows Bold as off and the next press bolds the whole of it rather than clearing it;
 *  - `kind`, `level` and `align` are NULL for a selection spanning blocks that disagree, and a null
 *    lights nothing at all. A bar reading "Heading 2" over a mixed selection would be telling the
 *    designer something untrue about their own document;
 *  - indent and outdent are ANY-of-selection and are DISABLED rather than hidden when they cannot
 *    apply, because a control that vanishes when the caret moves is a control nobody finds twice.
 *
 * The bar never decides what a press MEANS — pressing Heading 2 on a heading 2 returning it to a
 * paragraph is the editor's rule, applied in `RichTextEditor.kt` where the document is. This file
 * reports the press and draws the state it is given.
 */
@Composable
internal fun RichTextToolbar(
    state: RichToolbarState,
    canUndo: Boolean,
    canRedo: Boolean,
    onMark: (String) -> Unit,
    onBlockKind: (String, Int) -> Unit,
    onIndent: (Int) -> Unit,
    onAlign: (String) -> Unit,
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Place a photograph at the caret, or null when this field cannot.
     *
     * NULL IS THE ORDINARY STATE HERE, AND IN THIS APPLICATION IT IS THE ONLY STATE. Placing a
     * picture needs somewhere to put the bytes and a record to file the descriptor against, and a
     * record form's prose box has neither: photographs on these forms belong to the record's own
     * media section, which uploads them, lists them and can delete them again. So `RecordProseField`
     * passes nothing and the button is not drawn. The parameter itself is kept — rather than the
     * button being deleted from the bar — because a document written in the browser can contain a
     * photograph, this editor has to render and preserve one, and the day this app grows a place to
     * put the bytes the wiring is one argument rather than a re-port.
     */
    onInsertImage: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // See the header. Nothing inside this subtree may ever take the focus.
            .focusProperties { canFocus = false }
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(10.dp))
            // Twenty-one controls do not fit across a phone, and wrapping them onto three rows makes
            // the bar taller than the field it is formatting. One scrolling row keeps the five marks
            // — which is what almost every press is — visible without any scrolling at all.
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RteToolButton("Bold", Icons.Filled.FormatBold, state.marks.contains("BOLD")) { onMark("BOLD") }
        RteToolButton("Italic", Icons.Filled.FormatItalic, state.marks.contains("ITALIC")) { onMark("ITALIC") }
        RteToolButton("Underline", Icons.Filled.FormatUnderlined, state.marks.contains("UNDERLINE")) {
            onMark("UNDERLINE")
        }
        RteToolButton(
            "Strikethrough",
            Icons.Filled.FormatStrikethrough,
            state.marks.contains("STRIKETHROUGH"),
        ) { onMark("STRIKETHROUGH") }
        // Inline code is stored faithfully and PRINTS IN THE BODY FACE, because `report.Run` has no
        // monospace flag. The note under the editor says so in words; the button is still offered
        // because the mark survives the round trip and the web offers it.
        RteToolButton("Inline code", Icons.Filled.Code, state.marks.contains("CODE")) { onMark("CODE") }

        RteSeparator()

        RteToolLabelButton("¶", "Paragraph", state.kind == "PARAGRAPH") { onBlockKind("PARAGRAPH", 0) }
        for (level in 1..4) {
            RteToolLabelButton(
                "H$level",
                "Heading $level",
                state.kind == "HEADING" && state.level == level,
            ) { onBlockKind("HEADING", level) }
        }
        RteToolButton("Quote", Icons.Filled.FormatQuote, state.kind == "QUOTE") { onBlockKind("QUOTE", 0) }

        RteSeparator()

        RteToolButton(
            "Bulleted list",
            Icons.AutoMirrored.Filled.FormatListBulleted,
            state.kind == "BULLET_ITEM",
        ) { onBlockKind("BULLET_ITEM", 0) }
        RteToolButton(
            "Numbered list",
            Icons.Filled.FormatListNumbered,
            state.kind == "ORDERED_ITEM",
        ) { onBlockKind("ORDERED_ITEM", 0) }
        RteToolButton(
            "Outdent",
            Icons.AutoMirrored.Filled.FormatIndentDecrease,
            active = null,
            enabled = state.canOutdent,
        ) { onIndent(-1) }
        RteToolButton(
            "Indent",
            Icons.AutoMirrored.Filled.FormatIndentIncrease,
            active = null,
            enabled = state.canIndent,
        ) { onIndent(1) }

        RteSeparator()

        RteToolButton("Align left", Icons.AutoMirrored.Filled.FormatAlignLeft, state.align == "LEFT") {
            onAlign("LEFT")
        }
        RteToolButton("Align centre", Icons.Filled.FormatAlignCenter, state.align == "CENTER") {
            onAlign("CENTER")
        }
        RteToolButton("Align right", Icons.AutoMirrored.Filled.FormatAlignRight, state.align == "RIGHT") {
            onAlign("RIGHT")
        }
        RteToolButton("Justify", Icons.Filled.FormatAlignJustify, state.align == "JUSTIFY") {
            onAlign("JUSTIFY")
        }

        if (onInsertImage != null) {
            RteSeparator()
            // A ONE-SHOT COMMAND, so `active = null` and no state is announced. There is no such
            // thing as "the caret is inside a photograph" to light up — the caret inside an IMAGE
            // block is inside its CAPTION, which is prose, and lighting this there would say the
            // designer had a picture selected when what they have is a line of text.
            RteToolButton("Photograph", Icons.Filled.Image, active = null) { onInsertImage() }
        }

        RteSeparator()

        RteToolButton("Clear formatting", Icons.Filled.FormatClear, active = null) { onClear() }
        RteToolButton("Undo", Icons.AutoMirrored.Filled.Undo, active = null, enabled = canUndo) { onUndo() }
        RteToolButton("Redo", Icons.AutoMirrored.Filled.Redo, active = null, enabled = canRedo) { onRedo() }
    }
}

/**
 * One control.
 *
 * [active] is deliberately nullable and the three states are different things: `true` and `false`
 * are the two halves of a TOGGLE and are announced as "on"/"off", while `null` means this is a
 * one-shot command — Undo, Clear formatting, Indent — that has no state to report. Announcing "off"
 * for Undo would be a lie a screen-reader user cannot see past.
 *
 * The active look is carried by the FILL AND THE BORDER together, not by colour alone, for the same
 * reason every status chip in this app is: the bar is used outdoors on a phone at midday.
 */
@Composable
private fun RteToolButton(
    label: String,
    icon: ImageVector,
    active: Boolean?,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    RteToolShell(label, active, enabled, onClick) { tint ->
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/**
 * A control whose icon is a letterform.
 *
 * Material ships no Heading-1 glyph, and the four heading levels have to be told apart at a glance
 * on a 360dp-wide bar. "H1".."H4" and "¶" are what a designer already reads in every word processor,
 * and inventing four look-alike icons would be four things to learn instead of none. The
 * `contentDescription` on the shell still speaks the full name, so nothing is lost to a reader.
 */
@Composable
private fun RteToolLabelButton(
    glyph: String,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    RteToolShell(label, active, enabled = true, onClick = onClick) { tint ->
        Text(glyph, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RteToolShell(
    label: String,
    active: Boolean?,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit,
) {
    val isActive = active == true
    val tint = when {
        !enabled -> MaterialTheme.field.placeholder
        isActive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.field.body
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(34.dp)
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .border(
                1.dp,
                if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                if (active != null) stateDescription = if (active) "on" else "off"
            },
    ) {
        content(tint)
    }
}

@Composable
private fun RteSeparator() {
    Box(
        Modifier
            .padding(horizontal = 3.dp)
            .width(1.dp)
            .height(18.dp)
            .background(MaterialTheme.field.hairline)
    )
}
