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

/*
 * `Text`, shadowing `androidx.compose.material3.Text`, so that the two-typeface rule is decided the
 * same way the web decides it.
 *
 * THE PROBLEM THIS SOLVES. The web pairs `font-sans` (Inter) with `font-display` (Plus Jakarta
 * Sans). [FieldTypography] encodes both — its display/headline/title slots are Jakarta, its
 * body/label slots are Inter — but a slot only applies to a `Text` that ASKS for it, and this app
 * has ~360 call sites that pass a bare `fontSize`/`fontWeight` and no `style`. Every one of those
 * inherits `LocalTextStyle`, which is `bodyLarge`, which is Inter. So both fonts were loaded and
 * correctly configured and almost every heading in the app was still set in Inter.
 *
 * WHY THE PREVIOUS FIX WAS ALSO WRONG. It guessed the family from size and weight — Jakarta when
 * bold and >=15sp, or anything >=20sp. That is not a rule the web has, and the counts in Theme.kt's
 * type-scale comment show it failing in both directions: the web sets `font-display` at 12px and at
 * semibold (so ~30 real headings were rendered in Inter), while 88 semibold/bold elements carry no
 * `font-display` at all (so loosening the threshold would have promoted every status pill, count
 * line and `<strong>` to Jakarta). No function of size and weight reproduces the web, because on
 * the web the face is not a function of size and weight — it is a class somebody wrote.
 *
 * SO THIS WRAPPER NEVER INFERS. It mirrors CSS's own three states exactly:
 *
 *   [display] `null`  — no font-* class on this element. Inherit: whatever the resolved
 *                       [TextStyle] says, i.e. whichever [FieldTypography] slot is in scope. That
 *                       is `bodyLarge`/Inter unless an ancestor provided something else, which is
 *                       what `<body class="font-sans">` gives you on the web.
 *   [display] `true`  — `font-display`. Plus Jakarta Sans.
 *   [display] `false` — `font-sans`. Inter, even inside a Jakarta subtree — the opt-OUT, for a body
 *                       line sitting under a heading style.
 *
 * The default is `null`, and that choice matters. A bare `Text("…", fontSize = 20.sp)` in this app
 * is often a heading, so defaulting to Jakarta would be right more often at the 12 or so large call
 * sites — but it would be a guess again, and it would be wrong loudly (a serif-ish display face on
 * a caption is the error you notice). Inheriting is wrong only in the way the web is wrong when you
 * forget a class: you get the document default, Inter, which is a face the design system chose.
 * Getting it back is one named argument at one call site, and `display = true` greps.
 *
 * A call site can no longer get the face wrong SILENTLY, because there is no longer anywhere for a
 * silent decision to happen. The family comes from exactly one of three places — an explicit
 * `fontFamily`, the [display] flag, or the resolved style — and all three are written down. The
 * flag also sits at parameter position 3, where its type cannot be confused with `color`,
 * `fontSize` or `modifier`: a call site that tries to pass it positionally fails to compile rather
 * than shifting a face by one argument.
 */

/** The family this text is set in, or null to leave the resolved style's own family in place. */
private fun resolveFamily(
    explicitFamily: FontFamily?,
    display: Boolean?,
    style: TextStyle
): FontFamily? = when {
    // The caller named a family outright. Nothing here gets a vote.
    explicitFamily != null -> explicitFamily
    display == true -> FieldDisplayFontFamily
    display == false -> FieldBodyFontFamily
    // Inherit. Returning null leaves the resolved style's own family in place.
    style.fontFamily != null -> null
    // A hand-built TextStyle that named no family would otherwise fall through to whatever the OEM
    // ships as sans-serif, which on some devices is not the same shape twice. Pin it to Inter — the
    // web's document default — so nothing can wander in.
    else -> FieldBodyFontFamily
}

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    display: Boolean? = null,
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
        fontFamily = resolveFamily(fontFamily, display, style),
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

/** The `AnnotatedString` overload, so a call site with inline spans resolves the same way. */
@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    display: Boolean? = null,
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
        fontFamily = resolveFamily(fontFamily, display, style),
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
