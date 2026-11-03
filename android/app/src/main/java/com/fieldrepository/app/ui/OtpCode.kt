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

object OtpCode {
    const val LENGTH: Int = 6

    fun onlyDigits(text: String): String = text.filter { it in '0'..'9' }

    fun accept(previous: String, incoming: String): String {
        val inserted = if (incoming.startsWith(previous)) incoming.substring(previous.length) else incoming
        val insertedDigits = onlyDigits(inserted)
        if (insertedDigits.length >= LENGTH) return insertedDigits.take(LENGTH)
        return onlyDigits(incoming).take(LENGTH)
    }

    fun isComplete(code: String): Boolean = code.length == LENGTH && code.all { it in '0'..'9' }

    fun backspace(code: String): String = code.dropLast(1)

    fun activeBox(code: String): Int = code.length.coerceAtMost(LENGTH - 1)

    fun digitLabel(index: Int): String = "Digit ${index + 1} of $LENGTH"
}

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
