package com.fieldrepository.app.ui

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp

/**
 * `Text`, with the two-typeface rule applied automatically.
 *
 * THE PROBLEM THIS SOLVES. The web sets `font-display` (Plus Jakarta Sans) on headings, card
 * titles, dashboard tile labels and stat numbers, and `font-sans` (Inter) on everything else.
 * [FieldTypography] encodes exactly that — its display/headline/title slots are Jakarta, its
 * body/label slots are Inter — but a slot only applies to a `Text` that ASKS for it, and this app
 * has ~330 call sites that pass a bare `fontSize`/`fontWeight` and no `style`. Every one of those
 * silently inherits `LocalTextStyle`, which is `bodyLarge`, which is Inter. So the fonts were both
 * loaded and correctly configured, and almost every heading in the app was still set in Inter.
 *
 * Fixing that by hand at 330 call sites is 330 chances to miss one, and the next screen someone
 * writes misses it again. Shadowing `Text` in this package fixes all of them at the import line and
 * keeps them fixed: the files below import `com.fieldrepository.app.ui.Text` instead of
 * `androidx.compose.material3.Text`, and nothing else about the call sites changes.
 *
 * THE RULE, and why it is this rule:
 *
 *  - An explicit `fontFamily`, or a `style` that already names a family (every
 *    `MaterialTheme.typography.*` does), is left completely alone. The wrapper only decides when
 *    nobody else has.
 *  - Otherwise: **Jakarta** when the text is bold at 15sp or larger, or 20sp or larger at any
 *    weight. That is the web's own boundary — its headings are all `font-bold` and `text-base`
 *    (16px) or above, its big figures are `text-3xl`, and its body copy is `text-base` at 400.
 *  - Everything else is **Inter**: body copy, muted meta lines, field labels, button text. The
 *    user's instruction, exactly — "Inter strictly for the content only".
 *
 * A 16sp *normal-weight* paragraph therefore stays Inter, which is right; the same 16sp turned bold
 * becomes a heading and turns Jakarta, which is also right.
 */
private val JAKARTA_MIN_BOLD_SIZE = 15.sp
private val JAKARTA_MIN_ANY_SIZE = 20.sp

/** The family this text should be set in, or null to leave the resolved style untouched. */
@Composable
private fun resolveFamily(
    explicitFamily: FontFamily?,
    style: TextStyle,
    fontSize: TextUnit,
    fontWeight: FontWeight?
): FontFamily? {
    if (explicitFamily != null) return explicitFamily
    if (style.fontFamily != null) return null // a typography slot already decided

    val size = if (fontSize.isUnspecified) style.fontSize else fontSize
    val weight = fontWeight ?: style.fontWeight ?: FontWeight.Normal
    val effective = if (size.isUnspecified) 14.sp else size

    val isHeading =
        (weight.weight >= FontWeight.Bold.weight && effective.value >= JAKARTA_MIN_BOLD_SIZE.value) ||
            effective.value >= JAKARTA_MIN_ANY_SIZE.value
    return if (isHeading) FieldDisplayFontFamily else FieldBodyFontFamily
}

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current
) {
    androidx.compose.material3.Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = resolveFamily(fontFamily, style, fontSize, fontWeight),
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
        style = style
    )
}

/** The `AnnotatedString` overload, so a call site with inline spans resolves to the same rule. */
@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    // The AnnotatedString overload in Material3 declares this NON-nullable (unlike the String one),
    // and takes an inlineContent map the String overload does not have.
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current
) {
    androidx.compose.material3.Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = resolveFamily(fontFamily, style, fontSize, fontWeight),
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        inlineContent = inlineContent,
        onTextLayout = onTextLayout,
        style = style
    )
}
