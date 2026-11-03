package com.fieldrepository.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
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

private const val DIAL_CARET = "▾"

private val DIAL_FIELD_CHROME = 34.dp

private fun phoneDigits(text: String): String = text.filter { it in '0'..'9' }

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

internal fun composeArtisanPhone(dialCode: String, national: String): String {
    val digits = phoneDigits(national)
    return if (digits.isEmpty()) "" else "$dialCode $digits"
}

internal fun artisanPhoneValidationError(stored: String?): String? {
    val (code, national) = parseArtisanPhone(stored ?: "")
    val digits = phoneDigits(national)
    return when {
        digits.isEmpty() -> null
        code == "+91" -> if (digits.length == 10) null else "Enter a 10-digit number for +91."
        else -> if (digits.length in 4..14) null else "Enter a valid phone number (4–14 digits)."
    }
}

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

@Composable
fun ArtisanPhoneField(value: String, error: String?, onValueChange: (String) -> Unit) {
    val initial = remember { parseArtisanPhone(value) }
    var dialCode by remember { mutableStateOf(initial.first) }
    var national by remember { mutableStateOf(initial.second) }
    var showPicker by remember { mutableStateOf(false) }
    var pendingForeign by remember { mutableStateOf<String?>(null) }

    fun applyDialCode(code: String) {
        if (code == dialCode) return
        if (dialCode == "+91" && code != "+91") {
            pendingForeign = code
        } else {
            dialCode = code
            onValueChange(composeArtisanPhone(code, national))
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Phone", color = Muted, style = MaterialTheme.typography.bodySmall)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val dialWidth = rememberDialFieldWidth(maxWidth)
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
                            .focusProperties { canFocus = false }
                            .clearAndSetSemantics {}
                    )
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

private val countryDialOptions: List<SelectOption> =
    Countries.all.map { SelectOption(value = it.dialCode, label = it.name, hint = it.dialCode) }
