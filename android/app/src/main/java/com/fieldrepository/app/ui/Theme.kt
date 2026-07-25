package com.fieldrepository.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

import com.fieldrepository.app.R
import androidx.core.view.WindowCompat

/*
 * Field Repository — Android theme.
 *
 * Single source of truth for colour is the WEB design system (frontend/tailwind.config.ts +
 * frontend/app/globals.css). Both clients are the same product, so the same tokens are restated
 * here as sRGB constants:
 *
 *   - Action colour is the purple ramp, OKLCH with hue locked at 305. purple-700 is THE action
 *     colour in light mode. The hex values below are real OKLab -> linear sRGB -> sRGB
 *     conversions of the oklch() values in tailwind.config.ts (the transform was validated
 *     against three exact anchors: oklch(1 0 0)=#FFFFFF, oklch(.7017 .3225 328.36)=#FF00FF,
 *     oklch(.628 .2577 29.23)=#FF0000), not eyeballed approximations.
 *   - Neutrals are purple-tinted, never grey; the light/dark triplets are copied verbatim from
 *     the `:root` and `:root[data-theme="dark"]` blocks of globals.css.
 *   - Terracotta/cream (the launcher-icon palette that used to drive this file) is now confined
 *     to [FieldLogoColors] and is NOT an app colour.
 */

// ---------------------------------------------------------------------------------------------
// Raw palette
// ---------------------------------------------------------------------------------------------

/**
 * Literal design tokens. The purple ramp does not invert between themes (brand colour is brand
 * colour); the neutral ladders do, which is why they carry Light/Dark suffixes.
 */
object FieldPalette {
    // Purple ramp — oklch(L C 305) converted to sRGB.
    val Purple50 = Color(0xFFF9F5FF)   // oklch(0.977 0.013 305)
    val Purple100 = Color(0xFFF2E9FE)  // oklch(0.946 0.03  305)
    val Purple200 = Color(0xFFE7D5FE)  // oklch(0.9   0.058 305)
    val Purple300 = Color(0xFFD6B6FB)  // oklch(0.828 0.1   305)
    val Purple400 = Color(0xFFC090F5)  // oklch(0.738 0.15  305)
    val Purple500 = Color(0xFFAA69E9)  // oklch(0.648 0.19  305)
    val Purple600 = Color(0xFF9148D2)  // oklch(0.56  0.205 305)
    val Purple700 = Color(0xFF762CB1)  // oklch(0.47  0.198 305)  <- ACTION
    val Purple800 = Color(0xFF5F1C93)  // oklch(0.4   0.18  305)
    val Purple900 = Color(0xFF4B1674)  // oklch(0.34  0.15  305)
    val Purple950 = Color(0xFF2F0D4B)  // oklch(0.255 0.108 305)

    /*
     * Gold ramp — oklch(L C ~85) converted with the same transform as the purple ramp above (the
     * converter reproduces all eleven committed purple hexes exactly, so these are real conversions).
     *
     * MARKETING SURFACES ONLY, exactly as the web system says: the hero and the AUTH screen, at most
     * a few percent of the viewport. Gold never becomes an action colour, never replaces purple, and
     * never appears on a data screen. Gold200/300 are the readable rungs on deep purple; Gold700 is
     * the only gold that may sit on a light background.
     */
    val Gold100 = Color(0xFFFAEECD) // oklch(0.95 0.045 90)
    val Gold200 = Color(0xFFF4DCA1) // oklch(0.9  0.08  88)
    val Gold300 = Color(0xFFEEC976) // oklch(0.85 0.11  86)
    val Gold400 = Color(0xFFE0AF43) // oklch(0.78 0.135 84)
    val Gold500 = Color(0xFFCD9200) // oklch(0.7  0.145 80)
    val Gold600 = Color(0xFFAD7300) // oklch(0.6  0.13  75)
    val Gold700 = Color(0xFF8A5600) // oklch(0.5  0.11  70)

    // Light neutrals — globals.css :root
    val Bg0Light = Color(0xFFF7F6FB)
    val CardLight = Color(0xFFFFFFFF)
    val Surface50Light = Color(0xFFFAF9FD)
    val Surface100Light = Color(0xFFF3F1FA)
    val Surface200Light = Color(0xFFE9E6F5)
    val Surface300Light = Color(0xFFDCD7EE)
    val Line200Light = Color(0xFFE4E2EF)
    val Ink900Light = Color(0xFF1E1B2E)
    val Ink700Light = Color(0xFF3A3651)
    val Ink500Light = Color(0xFF615D7A)
    val Ink300Light = Color(0xFFA7A3BC)

    // Dark neutrals — globals.css :root[data-theme="dark"]
    val Bg0Dark = Color(0xFF110F19)
    val CardDark = Color(0xFF1A1725)
    val Surface50Dark = Color(0xFF201C2D)
    val Surface100Dark = Color(0xFF262135)
    val Surface200Dark = Color(0xFF2E2840)
    val Surface300Dark = Color(0xFF3A3350)
    val Line200Dark = Color(0xFF342E47)
    val Ink900Dark = Color(0xFFF2F0F9)
    val Ink700Dark = Color(0xFFD0CBDF)
    val Ink500Dark = Color(0xFF9E98B2)
    val Ink300Dark = Color(0xFF6E6884)

    // Semantic
    val Success600 = Color(0xFF15803D)
    val Success100 = Color(0xFFDCFCE7)
    val Error600 = Color(0xFFDC2626)
    val Error100 = Color(0xFFFEE2E2)
    val Amber500 = Color(0xFFF59E0B)
    val Amber100 = Color(0xFFFEF3C7)
    val Amber800 = Color(0xFF92400E)

    /*
     * Derived shades. The published system names only the pairs above, but Material needs a
     * readable "on" colour for every container and a light-enough semantic hue for dark mode
     * (success-600 and error-600 both fall under 4.5:1 against the dark canvas). These stay in
     * the same hue family as their token and are used ONLY for those slots.
     */
    val Success400 = Color(0xFF4ADE80)
    val Success900 = Color(0xFF14532D)
    val Success950 = Color(0xFF052E16)
    val Error400 = Color(0xFFF87171)
    val Error800 = Color(0xFF991B1B)
    val Error900 = Color(0xFF7F1D1D)
    val Error950 = Color(0xFF450A0A)
    val Amber950 = Color(0xFF422006)

    /** Purple-tinted shadow ink — rgba(46,16,101,*) in the web system. Apply your own alpha. */
    val ShadowInk = Color(0xFF2E1065)
}

/**
 * THE LOGO IS THE EXCEPTION. The 8-point star keeps its native terracotta on a near-black disc
 * over cream. These three values belong to the logo mark ONLY — never to buttons, links,
 * accents, progress bars or any other product surface. On a purple surface, put the mark in a
 * cream rounded tile (that is what [Cream] is for).
 */
object FieldLogoColors {
    val Terracotta = Color(0xFFCC785C)
    val Disc = Color(0xFF181715)
    val Cream = Color(0xFFFAF9F5)
}

/**
 * THE Field Repository mark, drawn from `res/drawable/ic_launcher.xml` — the launcher icon itself,
 * so the app, the web app's `components/FieldRepoLogo.tsx` and this all render one identical shape.
 *
 * The drawable already paints the cream field behind the star, so clipping it to [cornerRadius] IS
 * the cream tile the design system asks for on purple surfaces; [FieldLogoColors.Cream] backs it as
 * well in case the vector is ever cropped. No tint is applied anywhere: the terracotta is native.
 */
@Composable
fun FieldRepoLogo(modifier: Modifier = Modifier, cornerRadius: Dp = 12.dp) {
    Image(
        painter = painterResource(R.drawable.ic_launcher),
        contentDescription = null,
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(FieldLogoColors.Cream)
    )
}

// ---------------------------------------------------------------------------------------------
// Extra roles Material 3 has no slot for
// ---------------------------------------------------------------------------------------------

/**
 * Design-system roles that do not exist in [ColorScheme]: the body/placeholder rungs of the ink
 * ladder, the tinted surface ladder, success/warning semantics, and the "brand tile" pairing
 * (a dark purple chip carrying light content) that the web dashboard uses for icon tiles and the
 * totals card.
 *
 * Read via `LocalFieldTokens.current` or `MaterialTheme.field`.
 */
@Immutable
data class FieldTokens(
    /** Body copy — ink-700. One rung softer than [ColorScheme.onSurface]. */
    val body: Color,
    /** Muted/secondary copy — ink-500. Same value as [ColorScheme.onSurfaceVariant]. */
    val muted: Color,
    /** Placeholders and disabled copy — ink-300. */
    val placeholder: Color,
    /** Tinted panel ladder — surface-50 to surface-300. */
    val surface50: Color,
    val surface100: Color,
    val surface200: Color,
    val surface300: Color,
    /** Hairline borders — line-200. Same value as [ColorScheme.outline]. */
    val hairline: Color,
    /** Dark purple chip for icon tiles / stat cards. Pair with [onBrandTile]. */
    val brandTile: Color,
    /** Primary content on [brandTile]. */
    val onBrandTile: Color,
    /** Secondary content on [brandTile]. */
    val onBrandTileMuted: Color,
    /** Accent (links, progress, emphasis) sitting ON [brandTile] — primary is too dark there. */
    val accentOnBrandTile: Color,
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    /** Purple-tinted shadow ink. */
    val shadow: Color,
    val isDark: Boolean
)

private val LightFieldTokens = FieldTokens(
    body = FieldPalette.Ink700Light,
    muted = FieldPalette.Ink500Light,
    placeholder = FieldPalette.Ink300Light,
    surface50 = FieldPalette.Surface50Light,
    surface100 = FieldPalette.Surface100Light,
    surface200 = FieldPalette.Surface200Light,
    surface300 = FieldPalette.Surface300Light,
    hairline = FieldPalette.Line200Light,
    brandTile = FieldPalette.Purple800,
    onBrandTile = FieldPalette.Purple50,
    onBrandTileMuted = FieldPalette.Purple200,
    accentOnBrandTile = FieldPalette.Purple300,
    success = FieldPalette.Success600,
    onSuccess = Color.White,
    successContainer = FieldPalette.Success100,
    onSuccessContainer = FieldPalette.Success900,
    warning = FieldPalette.Amber800,
    onWarning = Color.White,
    warningContainer = FieldPalette.Amber100,
    onWarningContainer = FieldPalette.Amber800,
    shadow = FieldPalette.ShadowInk,
    isDark = false
)

private val DarkFieldTokens = FieldTokens(
    body = FieldPalette.Ink700Dark,
    muted = FieldPalette.Ink500Dark,
    placeholder = FieldPalette.Ink300Dark,
    surface50 = FieldPalette.Surface50Dark,
    surface100 = FieldPalette.Surface100Dark,
    surface200 = FieldPalette.Surface200Dark,
    surface300 = FieldPalette.Surface300Dark,
    hairline = FieldPalette.Line200Dark,
    brandTile = FieldPalette.Purple900,
    onBrandTile = FieldPalette.Purple50,
    onBrandTileMuted = FieldPalette.Purple200,
    accentOnBrandTile = FieldPalette.Purple300,
    success = FieldPalette.Success400,
    onSuccess = FieldPalette.Success950,
    successContainer = FieldPalette.Success900,
    onSuccessContainer = FieldPalette.Success100,
    warning = FieldPalette.Amber500,
    onWarning = FieldPalette.Amber950,
    warningContainer = FieldPalette.Amber800,
    onWarningContainer = FieldPalette.Amber100,
    shadow = Color.Black,
    isDark = true
)

val LocalFieldTokens = staticCompositionLocalOf { LightFieldTokens }

/** `MaterialTheme.field.body`, `MaterialTheme.field.brandTile`, … */
val MaterialTheme.field: FieldTokens
    @Composable @ReadOnlyComposable get() = LocalFieldTokens.current

// ---------------------------------------------------------------------------------------------
// Colour schemes — every slot mapped deliberately, so nothing falls back to a Material default.
// ---------------------------------------------------------------------------------------------

private val FieldLightColorScheme: ColorScheme = lightColorScheme(
    primary = FieldPalette.Purple700,
    onPrimary = Color.White,
    primaryContainer = FieldPalette.Purple100,
    onPrimaryContainer = FieldPalette.Purple800,
    inversePrimary = FieldPalette.Purple300,

    // Secondary is the muted ink rung — the web has no second brand hue, and inventing one
    // would put a colour on screen that belongs to no design system.
    secondary = FieldPalette.Ink500Light,
    onSecondary = Color.White,
    secondaryContainer = FieldPalette.Surface100Light,
    onSecondaryContainer = FieldPalette.Ink900Light,

    // Tertiary carries the amber "warning/attention" semantic rather than a stray accent.
    tertiary = FieldPalette.Amber800,
    onTertiary = Color.White,
    tertiaryContainer = FieldPalette.Amber100,
    onTertiaryContainer = FieldPalette.Amber800,

    background = FieldPalette.Bg0Light,
    onBackground = FieldPalette.Ink900Light,
    surface = FieldPalette.CardLight,
    onSurface = FieldPalette.Ink900Light,
    surfaceVariant = FieldPalette.Surface100Light,
    onSurfaceVariant = FieldPalette.Ink500Light,
    surfaceTint = FieldPalette.Purple700,
    inverseSurface = FieldPalette.Purple950,
    inverseOnSurface = FieldPalette.Purple50,

    error = FieldPalette.Error600,
    onError = Color.White,
    errorContainer = FieldPalette.Error100,
    onErrorContainer = FieldPalette.Error800,

    outline = FieldPalette.Line200Light,
    outlineVariant = FieldPalette.Line200Light,
    scrim = Color.Black,

    // Surface ladder: cards are pure white, panels climb the tinted surface scale.
    surfaceBright = FieldPalette.CardLight,
    surfaceDim = FieldPalette.Surface200Light,
    surfaceContainerLowest = FieldPalette.CardLight,
    surfaceContainerLow = FieldPalette.Surface50Light,
    surfaceContainer = FieldPalette.Surface100Light,
    surfaceContainerHigh = FieldPalette.Surface200Light,
    surfaceContainerHighest = FieldPalette.Surface300Light
)

private val FieldDarkColorScheme: ColorScheme = darkColorScheme(
    // The ramp does not invert, but the tonal POSITION has to: purple-700 on a #110F19 canvas is
    // 2.3:1, so Material's foreground role (TextButton labels, selected states, focus rings, all
    // of which read `primary`) moves up the same ramp to purple-400 (7.7:1 on the canvas, 6.7:1
    // against onPrimary). Filled buttons therefore invert to light-purple-on-deep-purple, which
    // is the Material dark contract; the hue and the ramp are unchanged.
    primary = FieldPalette.Purple400,
    onPrimary = FieldPalette.Purple950,
    primaryContainer = FieldPalette.Purple900,
    onPrimaryContainer = FieldPalette.Purple200,
    inversePrimary = FieldPalette.Purple700,

    secondary = FieldPalette.Ink500Dark,
    onSecondary = FieldPalette.Bg0Dark,
    secondaryContainer = FieldPalette.Surface100Dark,
    onSecondaryContainer = FieldPalette.Ink900Dark,

    tertiary = FieldPalette.Amber500,
    onTertiary = FieldPalette.Amber950,
    tertiaryContainer = FieldPalette.Amber800,
    onTertiaryContainer = FieldPalette.Amber100,

    background = FieldPalette.Bg0Dark,
    onBackground = FieldPalette.Ink900Dark,
    surface = FieldPalette.CardDark,
    onSurface = FieldPalette.Ink900Dark,
    surfaceVariant = FieldPalette.Surface100Dark,
    onSurfaceVariant = FieldPalette.Ink500Dark,
    surfaceTint = FieldPalette.Purple400,
    inverseSurface = FieldPalette.Ink900Dark,
    inverseOnSurface = FieldPalette.Bg0Dark,

    error = FieldPalette.Error400,
    onError = FieldPalette.Error950,
    errorContainer = FieldPalette.Error900,
    onErrorContainer = FieldPalette.Error100,

    outline = FieldPalette.Line200Dark,
    outlineVariant = FieldPalette.Line200Dark,
    scrim = Color.Black,

    surfaceBright = FieldPalette.Surface300Dark,
    surfaceDim = FieldPalette.Bg0Dark,
    surfaceContainerLowest = FieldPalette.Bg0Dark,
    surfaceContainerLow = FieldPalette.CardDark,
    surfaceContainer = FieldPalette.Surface50Dark,
    surfaceContainerHigh = FieldPalette.Surface100Dark,
    surfaceContainerHighest = FieldPalette.Surface200Dark
)

// ---------------------------------------------------------------------------------------------
// Typography
// ---------------------------------------------------------------------------------------------

/*
 * The web pairs Inter (body/UI) with Plus Jakarta Sans (display/headings). Both ship with the web
 * app as .woff2, which Android cannot load, so both were converted to VARIABLE .ttf and now ship in
 * res/font: inter_variable.ttf (Inter, wght 100-900) and jakarta_variable.ttf (Plus Jakarta Sans,
 * wght 200-800). One file per family covers every weight -- minSdk here is 26 and Android 8+ reads
 * variable ttf.
 *
 * A VARIABLE font only renders at the requested weight if the 'wght' AXIS IS SET. Compose does not
 * derive it from the FontWeight you pass: the stable factory
 *
 *     Font(resId, weight, style, loadingStrategy)
 *
 * builds `ResourceFont(..., FontVariation.Settings(), ...)` -- settings EMPTY -- and
 * TypefaceCompatApi26.setFontVariationSettings early-returns on an empty list
 * (compose ui-text 1.7.5, PlatformTypefaces.android.kt:247). So all four declarations resolved to
 * the file's DEFAULT instance and every weight rendered identically at Regular. Font synthesis could
 * not rescue it either: the declared weight equalled the requested weight, so Compose saw an exact
 * match and had nothing to fake-bold. That was the "families match but the weight is wrong" symptom.
 *
 * [variableFont] therefore sets 'wght' explicitly for each declared weight, which is the only way to
 * get a real instance out of a variable file.
 *
 * Never serif: both families are real sans files, so no OEM default can wander in.
 */
@OptIn(ExperimentalTextApi::class)
private fun variableFont(resId: Int, weight: FontWeight): Font = Font(
    resId = resId,
    weight = weight,
    style = FontStyle.Normal,
    // Only 'wght'. FontVariation.Settings(weight, style) would also write 'ital', an axis neither
    // file has (both are upright-only), and an unsupported axis is dead weight in the settings list.
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

val FieldBodyFontFamily: FontFamily = FontFamily(
    variableFont(R.font.inter_variable, FontWeight.Normal),   // 400 — body
    variableFont(R.font.inter_variable, FontWeight.Medium),   // 500 — font-medium (buttons, labels)
    variableFont(R.font.inter_variable, FontWeight.SemiBold), // 600 — font-semibold (eyebrow, badges)
    variableFont(R.font.inter_variable, FontWeight.Bold),     // 700 — font-bold
)
val FieldDisplayFontFamily: FontFamily = FontFamily(
    variableFont(R.font.jakarta_variable, FontWeight.Normal),   // 400
    variableFont(R.font.jakarta_variable, FontWeight.Medium),   // 500
    variableFont(R.font.jakarta_variable, FontWeight.SemiBold), // 600 — font-semibold sub-heads
    variableFont(R.font.jakarta_variable, FontWeight.Bold),     // 700 — .display-title / headings
    variableFont(R.font.jakarta_variable, FontWeight.ExtraBold),// 800 — top of the Jakarta axis
)

/*
 * ── The web type scale, restated ───────────────────────────────────────────────────────────────
 *
 * Sizes are the Tailwind steps the web actually uses (tailwind.config.ts extends colour and radius
 * only, so the type ramp is stock Tailwind v3), and every slot below is one real step — no invented
 * in-between sizes:
 *
 *   text-xs 12/16   text-sm 14/20   text-base 16/24   text-lg 18/28   text-xl 20/28
 *   text-2xl 24/32  text-3xl 30/36  text-4xl 36/40    text-5xl 48/1   text-6xl 60/1   text-7xl 72/1
 *
 * Weights come from the classes in use: headings are `font-display font-bold` (`.display-title` =
 * font-display font-bold tracking-tight), sub-heads `font-semibold`, buttons/labels `font-medium`
 * (`.field-button`, `.field-input`, buttonVariants base = text-sm font-medium), body plain 400.
 *
 * Letter spacing is in **em**, not sp, because Tailwind's `tracking-*` is em-based and therefore
 * scales with the size — tracking-tight is -0.025em at every step. The previous sp values (-1sp on
 * displayLarge, +0.1/+0.2/+0.4sp across body and label) were Material's defaults, not the web's:
 * the web sets `letter-spacing: normal` on all body and UI text and only tightens headings.
 */
private val TrackingTight = (-0.025).em // Tailwind `tracking-tight`
private val TrackingNormal = 0.em       // Tailwind default — body, labels, buttons
private val TrackingWide = 0.025.em     // Tailwind `tracking-wide` — .field-label
private val TrackingEyebrow = 0.14.em   // .eyebrow — tracking-[0.14em]

/** Jakarta headings: `font-display font-bold tracking-tight`. */
private fun displayStyle(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Bold) = TextStyle(
    fontFamily = FieldDisplayFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = TrackingTight
)

/** Inter body/UI: no tracking, exactly as the web leaves it. */
private fun bodyStyle(size: Int, lineHeight: Int, weight: FontWeight) = TextStyle(
    fontFamily = FieldBodyFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = TrackingNormal
)

private val FieldTypography = Typography(
    // Display — text-7xl / 6xl / 5xl, all `leading-none` on the web's hero spans.
    displayLarge = displayStyle(72, 72),
    displayMedium = displayStyle(60, 60),
    displaySmall = displayStyle(48, 48),

    // Headline — text-4xl / 3xl / 2xl. headlineSmall is the login card's
    // `font-display text-2xl font-bold` h1 exactly.
    headlineLarge = displayStyle(36, 40),
    headlineMedium = displayStyle(30, 36),
    headlineSmall = displayStyle(24, 32),

    // Title — text-xl / base / sm. titleMedium is `.display-title` on a card header
    // (font-display font-bold, 16px); titleSmall is the `text-sm font-semibold` sub-head.
    titleLarge = displayStyle(20, 28),
    titleMedium = displayStyle(16, 24),
    titleSmall = displayStyle(14, 20, FontWeight.SemiBold),

    // Body — Inter 400 at text-base / sm / xs.
    bodyLarge = bodyStyle(16, 24, FontWeight.Normal),
    bodyMedium = bodyStyle(14, 20, FontWeight.Normal),
    bodySmall = bodyStyle(12, 16, FontWeight.Normal),

    // Label — the web's `font-medium` controls and meta: `.field-button` / `.field-input` /
    // buttonVariants base are all `text-sm font-medium`, `.field-label` is `text-xs font-medium`.
    // The whole family stays at 500: labelSmall carries running muted meta lines across this app,
    // not badges, and 600 there would embolden two dozen sentences that the web sets in medium.
    // The one genuinely semibold 11px thing on the web is the auth pill — [FieldTextStyles.Badge].
    labelLarge = bodyStyle(14, 20, FontWeight.Medium),
    labelMedium = bodyStyle(12, 16, FontWeight.Medium),
    labelSmall = bodyStyle(11, 16, FontWeight.Medium)
)

/**
 * Web recipes that are a class combination rather than a size step, so they have no Material slot:
 * `.eyebrow`, `.field-label` and the inline text link. Kept here so a screen never re-invents them.
 */
object FieldTextStyles {
    /** `.eyebrow` — text-[0.8125rem] font-semibold uppercase tracking-[0.14em]. Caller uppercases. */
    val Eyebrow: TextStyle = TextStyle(
        fontFamily = FieldBodyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = TrackingEyebrow
    )

    /** `.field-label` — text-xs font-medium uppercase tracking-wide. Caller uppercases. */
    val FieldLabel: TextStyle = TextStyle(
        fontFamily = FieldBodyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = TrackingWide
    )

    /** The auth card's status pill — `text-[11px] font-semibold`, on a purple-50 rounded-full chip. */
    val Badge: TextStyle = TextStyle(
        fontFamily = FieldBodyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = TrackingNormal
    )

    /** Inline text link — `font-medium text-purple-700 hover:underline`. Colour is the caller's. */
    val Link: TextStyle = TextStyle(
        fontFamily = FieldBodyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = TrackingNormal,
        textDecoration = TextDecoration.Underline
    )
}

// ---------------------------------------------------------------------------------------------
// Shapes — the web radius scale: sm 8, md 12, lg 16, xl 24.
// ---------------------------------------------------------------------------------------------

private val FieldShapes = Shapes(
    // The published scale starts at "sm"; extraSmall (menus, tooltips, snackbars) is kept a half
    // step tighter than sm so those never look rounder than a card.
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

// ---------------------------------------------------------------------------------------------
// Theme entry point
// ---------------------------------------------------------------------------------------------

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * @param darkTheme defaults to the SYSTEM setting. The web additionally stores a per-user
 *   preference (system/light/dark + reduce-motion, larger-text, high-contrast) at
 *   GET/PUT /preferences/me; wiring that in needs a settings surface and is deliberately left as
 *   a follow-up rather than half-built here — pass [darkTheme] explicitly once it exists.
 */
@Composable
fun FieldRepositoryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) FieldDarkColorScheme else FieldLightColorScheme
    val tokens = if (darkTheme) DarkFieldTokens else LightFieldTokens

    // Point the legacy top-level palette names at the active theme. Written here rather than in a
    // SideEffect so the value is already correct before any descendant reads it — nothing has read
    // it yet in this composition pass, so the write invalidates nothing and cannot loop.
    if (legacyPalette.value.tokens !== tokens) {
        legacyPalette.value = LegacyPalette(colorScheme, tokens)
    }

    // System bars follow the theme instead of staying cream. res/values(-night)/styles.xml paints
    // them correctly for the very first frame (before Compose runs); this keeps them in step when
    // the theme flips at runtime. On API 35+ (targetSdk 35) the platform ignores the two colour
    // setters and enforces edge-to-edge — the icon-contrast flags below are what still matter
    // there, and the window background from styles.xml shows through.
    val view = LocalView.current
    if (!view.isInEditMode) {
        val barColor = colorScheme.background.toArgb()
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            @Suppress("DEPRECATION")
            window.statusBarColor = barColor
            @Suppress("DEPRECATION")
            window.navigationBarColor = barColor
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalFieldTokens provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FieldTypography,
            shapes = FieldShapes,
            content = content
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Legacy palette names
// ---------------------------------------------------------------------------------------------

/*
 * MainActivity.kt, ui/FieldComponents.kt and ui/MediaPlayers.kt (owned by other work in flight)
 * import these nine names ~90 times. They used to be hardwired launcher-icon colours. They are now
 * THEME-AWARE aliases onto the schemes above, so the whole app follows light/dark without touching
 * those files. Each maps to its dominant role:
 *
 *   Canvas       -> background        SurfaceCard -> surfaceVariant   Coral -> primary
 *   CoralActive  -> pressed action    Ink         -> onBackground     Body  -> ink-700
 *   Muted        -> onSurfaceVariant  Hairline    -> outline          DarkSurface -> brand tile
 *
 * They are backed by a snapshot state set by [FieldRepositoryTheme] rather than by a
 * CompositionLocal, because a handful of PLAIN (non-@Composable) helpers read them —
 * MainActivity's `taskStatusColor` and `workshopAccessStatusColor` map a status string to a colour
 * outside composition, and @Composable getters do not compile there. Reading snapshot state still
 * registers with whatever recomposition scope is running, so a theme flip repaints correctly.
 *
 * They are transitional: new code should read MaterialTheme.colorScheme / MaterialTheme.field
 * directly. Two roles could not be preserved by aliasing alone and need call-site edits — see the
 * follow-ups filed with this change:
 *   - `Canvas` and `SurfaceCard` are also used as FOREGROUNDS on the hardcoded dark chips
 *     (ColorCompat.darkElevated = #252320 in MainActivity, Color(0xFF181715) in MediaPlayers).
 *     Those chips must become FieldTokens.brandTile with onBrandTile / onBrandTileMuted content.
 *   - `Coral` on those same chips must become FieldTokens.accentOnBrandTile.
 */

private class LegacyPalette(val scheme: ColorScheme, val tokens: FieldTokens)

private val legacyPalette = mutableStateOf(LegacyPalette(FieldLightColorScheme, LightFieldTokens))

/** Page canvas — bg-0. Prefer `MaterialTheme.colorScheme.background`. */
val Canvas: Color get() = legacyPalette.value.scheme.background

/** Tinted panel — surface-100. Prefer `MaterialTheme.colorScheme.surfaceVariant`. */
val SurfaceCard: Color get() = legacyPalette.value.scheme.surfaceVariant

/** THE action colour (purple-700 light / purple-400 dark). Name is historical — it is not coral. */
val Coral: Color get() = legacyPalette.value.scheme.primary

/** Pressed/hover step of the action colour. */
val CoralActive: Color
    get() = if (legacyPalette.value.tokens.isDark) FieldPalette.Purple300 else FieldPalette.Purple800

/** Heading ink — ink-900. Prefer `MaterialTheme.colorScheme.onBackground`. */
val Ink: Color get() = legacyPalette.value.scheme.onBackground

/** Body copy — ink-700. Prefer `MaterialTheme.field.body`. */
val Body: Color get() = legacyPalette.value.tokens.body

/** Muted copy — ink-500. Prefer `MaterialTheme.colorScheme.onSurfaceVariant`. */
val Muted: Color get() = legacyPalette.value.scheme.onSurfaceVariant

/** Hairline border — line-200. Prefer `MaterialTheme.colorScheme.outline`. */
val Hairline: Color get() = legacyPalette.value.scheme.outline

/** Dark brand chip (was near-black; now purple-800/900). Content: [FieldTokens.onBrandTile]. */
val DarkSurface: Color get() = legacyPalette.value.tokens.brandTile
