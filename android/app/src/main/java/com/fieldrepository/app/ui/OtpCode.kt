package com.fieldrepository.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The rules of a 6-digit authenticator code — the web's `lib/otp.ts`, to the character, and pinned by
 * `OtpCodeTest`. Pure, so the handset and the browser can be held to one contract.
 */
object OtpCode {
    const val LENGTH: Int = 6

    /** Every non-digit removed. Only ASCII 0-9 counts: an Arabic-Indic or full-width digit does not. */
    fun onlyDigits(text: String): String = text.filter { it in '0'..'9' }

    /**
     * What a change to the input leaves in the boxes. Anything that is not a digit is refused; six or
     * more digits arriving at once (a paste, the SMS/authenticator autofill) is a whole code and
     * fills the boxes from the first; more than six keeps the first six.
     */
    fun accept(previous: String, incoming: String): String {
        // What arrived just now. A whole code pasted on top of a half-typed one REPLACES it — the
        // paste is the code — rather than being appended and cut at six.
        val inserted = if (incoming.startsWith(previous)) incoming.substring(previous.length) else incoming
        val insertedDigits = onlyDigits(inserted)
        if (insertedDigits.length >= LENGTH) return insertedDigits.take(LENGTH)
        // A letter, a space or a dash typed into the boxes is simply dropped.
        return onlyDigits(incoming).take(LENGTH)
    }

    fun isComplete(code: String): Boolean = code.length == LENGTH && code.all { it in '0'..'9' }

    /** Backspace: the last digit goes, and the cursor box steps back one. */
    fun backspace(code: String): String = code.dropLast(1)

    /** The box the next digit lands in (the "focused" box), 0-5. */
    fun activeBox(code: String): Int = code.length.coerceAtMost(LENGTH - 1)

    /** What TalkBack hears for box [index]. */
    fun digitLabel(index: Int): String = "Digit ${index + 1} of $LENGTH"
}

/**
 * `OtpCodeField` — the ONE way to type a 6-digit code on the handset; the web's `OtpCodeInput`.
 *
 * Owner's ruling (2026-10-17): EXACTLY six boxes, digits only, in Plus Jakarta Sans
 * ([FieldDisplayFontFamily], bundled in res/font) with tabular figures, large. A whole code pasted
 * or autofilled (the field is tagged `ContentType.SmsOtpCode`) fills all six; typing advances;
 * Backspace steps back; [onComplete] fires the moment the sixth digit lands. [error] paints the boxes
 * red and shakes the row once — not under the app's reduced-motion preference, where the red border
 * and the spoken error are the whole signal.
 *
 * ONE INPUT DRAWN AS SIX BOXES, on purpose: a soft keyboard sends no key event for Backspace in an
 * EMPTY text field, so six separate fields cannot step back reliably on Gboard/Samsung. The single
 * field gets paste, autofill, Backspace and the number pad from the platform, and the boxes are its
 * decoration — each named "Digit n of 6" for TalkBack. Keyboard: [KeyboardType.NumberPassword], the
 * pad with no symbols on it.
 *
 * Recovery codes are a different shape and never go in these boxes; the caller offers a separate
 * text field behind a "Use a recovery code instead" switch.
 */
@Composable
fun OtpCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "6-digit code",
    onComplete: (String) -> Unit = {},
    error: Boolean = false,
    errorNonce: Int = 0,
    enabled: Boolean = true,
    autoFocus: Boolean = false
) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val reducedMotion = LocalAppPreferences.current.reducedMotion

    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }
    LaunchedEffect(error, errorNonce) {
        if (!error || reducedMotion) return@LaunchedEffect
        shake.snapTo(0f)
        shake.animateTo(
            0f,
            keyframes {
                durationMillis = 360
                -14f at 60
                14f at 120
                -9f at 180
                9f at 240
                0f at 360
            }
        )
    }

    val digitStyle = MaterialTheme.typography.headlineSmall.copy(
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        BasicTextField(
            value = TextFieldValue(value, selection = TextRange(value.length)),
            onValueChange = { next ->
                val accepted = OtpCode.accept(value, next.text)
                if (accepted != value) {
                    onValueChange(accepted)
                    if (OtpCode.isComplete(accepted)) onComplete(accepted)
                }
            },
            enabled = enabled,
            singleLine = true,
            cursorBrush = SolidColor(androidx.compose.ui.graphics.Color.Transparent),
            textStyle = digitStyle.copy(color = androidx.compose.ui.graphics.Color.Transparent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (OtpCode.isComplete(value)) onComplete(value) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .offset { IntOffset(shake.value.roundToInt(), 0) }
                .semantics {
                    contentType = ContentType.SmsOtpCode
                    contentDescription = "$label, ${value.length} of ${OtpCode.LENGTH} digits entered"
                    if (error) error("Wrong code")
                },
            decorationBox = { inner ->
                Box {
                    // The real (transparent) text sits underneath so the platform keeps its caret,
                    // selection and IME connection; the six boxes are drawn over it.
                    Box(modifier = Modifier.height(1.dp)) { inner() }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                    ) {
                        repeat(OtpCode.LENGTH) { index ->
                            val digit = value.getOrNull(index)?.toString().orEmpty()
                            val active = focused && index == OtpCode.activeBox(value) && value.length < OtpCode.LENGTH
                            val borderColor = when {
                                error -> MaterialTheme.colorScheme.error
                                active -> FieldPalette.Purple700
                                digit.isNotEmpty() -> FieldPalette.Purple600
                                else -> MaterialTheme.field.muted
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .widthIn(max = 52.dp)
                                    .height(60.dp)
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                    .border(if (active || error) 2.dp else 1.5.dp, borderColor, RoundedCornerShape(12.dp))
                                    .clearAndSetSemantics {
                                        contentDescription = OtpCode.digitLabel(index) +
                                            if (digit.isEmpty()) ", empty" else ", $digit"
                                    }
                            ) {
                                Text(digit, style = digitStyle)
                            }
                        }
                    }
                }
            }
        )
    }
}
