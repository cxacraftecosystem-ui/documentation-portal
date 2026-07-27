package com.fieldrepository.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Artisan phone entry: an ISD-prefix picker beside the national number.
//
// The two controls have to read as ONE field, which means the code and the digits must sit on the
// same line of text — not merely inside boxes whose tops happen to touch. The first version paired
// an OutlinedButton with an OutlinedTextField, and those two never agree: Material 3 gives the
// button a pill shape and a 40dp minimum against the field's 4dp-cornered 56dp box, and it centres
// labelLarge text in one while the field centres bodyLarge in the other. The result was a squat
// pill floating a few dp above the baseline of the number beside it — the "unbalanced" pair.
//
// The fix is structural rather than a nudge: BOTH controls are now the same composable, an
// OutlinedTextField with a label, so their heights, their internal padding and the position of the
// text inside them are computed by the same measure policy from the same type ramp. Anything that
// moves one moves the other by exactly as much — a larger system font, a wider screen, a different
// locale. A hand-tuned offset would have held only at the font scale it was tuned at.
// ---------------------------------------------------------------------------

/** The caret that marks the prefix box as a picker. Same glyph the app's other dropdowns use. */
private const val DIAL_CARET = "▾"

/**
 * The horizontal space an outlined field spends on itself: 16dp of padding at each end plus the 2dp
 * Material puts between the value and a suffix. Added to the measured glyph widths so the prefix box
 * is sized from its actual contents rather than from a dp that only happens to fit at font scale 1.
 *
 * The caret is a `suffix` and not a `trailingIcon` for the sake of this sum. Material treats a
 * trailing icon as a tappable target and reserves a 48dp slot for it whatever it contains, which on
 * this field is most of the box: budgeting for a caret glyph and getting a 48dp slot is what clipped
 * "+91" to "+9" the first time round. As a suffix the caret costs its own width and sits against the
 * code, which is also the better reading of it — one token, "+91 ▾", rather than a code marooned at
 * the far end of a box.
 */
private val DIAL_FIELD_CHROME = 34.dp

/**
 * The 0-9 in [text] and nothing else — the phone column's one definition of a digit.
 *
 * Emphatically NOT `Char.isDigit()`, which this field used to filter with: it is true of the
 * Devanagari "१" and the fullwidth "２" an Indic or CJK IME will happily produce, so a number typed on
 * a phone with such a keyboard passed straight through into storage. The web's PhoneField parses the
 * same column with `/\D/g`, which is ASCII-only, and therefore read that artisan as having no phone
 * number at all — the record looked complete on the device that captured it and blank in the browser.
 * The Aadhaar and pincode fields have always taken this line; the phone field is the one that did not.
 */
private fun phoneDigits(text: String): String = text.filter { it in '0'..'9' }

/**
 * Split a stored phone into its ISD dial code and national digits. Handles the "+CC number" format
 * this field writes, a compact "+CCnumber", and legacy bare numbers (assumed Indian, +91). Longest
 * known dial code wins so multi-digit codes aren't misread as a shorter one.
 */
internal fun parseArtisanPhone(stored: String): Pair<String, String> {
    val compact = stored.trim().replace("\\s".toRegex(), "")
    if (compact.isEmpty()) return "+91" to ""
    if (compact.startsWith("+")) {
        val code = Countries.dialCodes.firstOrNull { compact.startsWith(it) }
        if (code != null) return code to phoneDigits(compact.removePrefix(code))
        return "+91" to phoneDigits(compact)
    }
    return "+91" to phoneDigits(compact)
}

/** Combine a dial code and national digits into the stored "+CC number" form (blank when empty). */
internal fun composeArtisanPhone(dialCode: String, national: String): String {
    val digits = phoneDigits(national)
    return if (digits.isEmpty()) "" else "$dialCode $digits"
}

/**
 * Inline validation for a stored phone: null when empty or valid. +91 must be exactly 10 digits;
 * other codes accept 4–14 digits (loose enough for the range of national number lengths worldwide).
 */
internal fun artisanPhoneValidationError(stored: String?): String? {
    val (code, national) = parseArtisanPhone(stored ?: "")
    val digits = phoneDigits(national)
    return when {
        digits.isEmpty() -> null
        code == "+91" -> if (digits.length == 10) null else "Enter a 10-digit number for +91."
        else -> if (digits.length in 4..14) null else "Enter a valid phone number (4–14 digits)."
    }
}

/**
 * How wide the prefix box has to be to hold the widest code the picker can put in it, caret and all.
 *
 * Measured rather than guessed: the codes run from "+1" to four characters, glyph widths are not
 * uniform, and every one of those widths doubles when the reader has set a 2× system font. A frozen
 * dp would either ellipsize "+998" on a large-font device or waste a third of the row on a small
 * one. Capped at 45% of the row so the number field — the one the researcher actually types into —
 * keeps the larger share whatever the font scale.
 */
@Composable
private fun rememberDialFieldWidth(available: Dp): Dp {
    val measurer = rememberTextMeasurer()
    val style: TextStyle = LocalTextStyle.current
    val density: Density = LocalDensity.current
    val natural = remember(measurer, style, density) {
        val widestCode = Countries.dialCodes.maxOf { measurer.measure(it, style).size.width }
        val caret = measurer.measure(DIAL_CARET, style).size.width
        with(density) { (widestCode + caret).toDp() } + DIAL_FIELD_CHROME
    }
    return natural.coerceAtMost(available * 0.45f)
}

/**
 * Artisan phone entry with an ISD-prefix selector. The prefix box opens a searchable list of every
 * country (name + dial code); the default is +91. Changing away from +91 asks to confirm the artisan
 * is a foreign resident (cancel reverts). The combined value is emitted as a single "+CC number"
 * string in the existing phone field, and parsed back on edit.
 */
@Composable
fun ArtisanPhoneField(value: String, error: String?, onValueChange: (String) -> Unit) {
    val initial = remember { parseArtisanPhone(value) }
    var dialCode by remember { mutableStateOf(initial.first) }
    var national by remember { mutableStateOf(initial.second) }
    var showPicker by remember { mutableStateOf(false) }
    // A dial code chosen away from +91 that is awaiting the foreign-resident confirmation.
    var pendingForeign by remember { mutableStateOf<String?>(null) }

    fun applyDialCode(code: String) {
        if (code == dialCode) return
        // Leaving +91 marks the artisan as a foreign resident — confirm before applying.
        if (dialCode == "+91" && code != "+91") {
            pendingForeign = code
        } else {
            dialCode = code
            onValueChange(composeArtisanPhone(code, national))
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Phone", color = Muted, fontSize = 12.sp)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val dialWidth = rememberDialFieldWidth(maxWidth)
            // Top, not CenterVertically: an error hangs its supporting text BELOW the number field's
            // box, so centring would push the number's text up while the prefix stayed put and the
            // line would break exactly when the researcher is trying to read it. Anchored at the top,
            // the two boxes are identical in height and the error only grows the row downwards.
            //
            // And Top rather than alignByBaseline, which is the obvious-looking answer and the wrong
            // one. A text field declares no baseline of its own, so Compose hands up the smallest one
            // among its children — which is the LABEL's whenever the label has floated to the border.
            // The prefix box always holds a value so its label always floats; the number field's label
            // rests on the input line until something is typed. Aligning those two "baselines" would
            // hold the pair level only while the number field had content and jump them apart the
            // moment it was cleared. Measured on the device rather than deduced: a field reports
            // FirstBaseline 36px with its label floated and 118px with the label resting — 82px, most
            // of the box. (An error changes nothing: still 36px, because supporting text hangs below
            // the box and never moves the input line, which is what keeps the pair level when one
            // side is in error and the other is not.) What actually keeps
            // the two runs of text on one line is that both controls are the same composable with the
            // same label treatment, so the text sits at the same offset inside each — top-align the
            // boxes and the text follows.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(modifier = Modifier.width(dialWidth)) {
                    OutlinedTextField(
                        value = dialCode,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text("Code") },
                        suffix = { Text(DIAL_CARET, color = Muted) },
                        modifier = Modifier
                            .fillMaxWidth()
                            // The box is a button wearing a text field's clothes. Taking it out of the
                            // focus order and clearing its semantics leaves TalkBack and the keyboard
                            // one target — the overlay below — instead of an edit box that refuses to
                            // be edited.
                            .focusProperties { canFocus = false }
                            .clearAndSetSemantics {}
                    )
                    // Drawn over the field, so the tap opens the picker instead of landing in a
                    // read-only text box. matchParentSize borrows the field's own 56dp height, which
                    // is what keeps the target above 48dp at every font scale.
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(OutlinedTextFieldDefaults.shape)
                            .clickable(
                                onClickLabel = "Change country calling code",
                                role = Role.DropdownList
                            ) { showPicker = true }
                            .semantics { contentDescription = "Country calling code, $dialCode" }
                    )
                }
                OutlinedTextField(
                    value = national,
                    onValueChange = { input ->
                        val digits = phoneDigits(input)
                        national = digits
                        onValueChange(composeArtisanPhone(dialCode, digits))
                    },
                    label = { Text("Number") },
                    isError = error != null,
                    supportingText = error?.let { msg -> { Text(msg) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showPicker) {
        // The country list was this app's first and only searchable picker; it is now one caller of
        // the shared one in SearchableSelect.kt, so the researcher meets the same "tap, type,
        // commit" here as on every artisan, tool and craft field. Nothing is ticked because the
        // stored value is a DIAL CODE and a dial code is not a country — twenty entries share "+1",
        // and marking all twenty as the current selection would be a lie about what is stored.
        SearchableSelectSheet(
            title = "Country code",
            options = countryDialOptions,
            onDismiss = { showPicker = false },
            onSelect = ::applyDialCode
        )
    }
    pendingForeign?.let { code ->
        AlertDialog(
            onDismissRequest = { pendingForeign = null },
            title = { Text("Foreign resident?") },
            text = { Text("This marks the artisan as a foreign resident. Continue?") },
            confirmButton = {
                TextButton(onClick = {
                    dialCode = code
                    onValueChange(composeArtisanPhone(code, national))
                    pendingForeign = null
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { pendingForeign = null }) { Text("Cancel") } }
        )
    }
}

/**
 * Every country as a picker row: the name reads, the dial code sits at the row's end and is what
 * gets stored. Built once — the list is a compile-time constant, so rebuilding 200 rows on every
 * recomposition of a phone field would be work done for nothing.
 */
private val countryDialOptions: List<SelectOption> =
    Countries.all.map { SelectOption(value = it.dialCode, label = it.name, hint = it.dialCode) }
