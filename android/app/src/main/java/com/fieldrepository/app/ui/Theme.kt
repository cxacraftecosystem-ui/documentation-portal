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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

import com.fieldrepository.app.R
import androidx.core.view.WindowCompat

object FieldPalette {
    val Purple50 = Color(0xFFF9F5FF)
    val Purple100 = Color(0xFFF2E9FE)
    val Purple200 = Color(0xFFE7D5FE)
    val Purple300 = Color(0xFFD6B6FB)
    val Purple400 = Color(0xFFC090F5)
    val Purple500 = Color(0xFFAA69E9)
    val Purple600 = Color(0xFF9148D2)
    val Purple700 = Color(0xFF762CB1)
    val Purple800 = Color(0xFF5F1C93)
    val Purple900 = Color(0xFF4B1674)
    val Purple950 = Color(0xFF2F0D4B)

    val Gold100 = Color(0xFFFAEECD)
    val Gold200 = Color(0xFFF4DCA1)
    val Gold300 = Color(0xFFEEC976)
    val Gold400 = Color(0xFFE0AF43)
    val Gold500 = Color(0xFFCD9200)
    val Gold600 = Color(0xFFAD7300)
    val Gold700 = Color(0xFF8A5600)

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

    val Success600 = Color(0xFF15803D)
    val Success100 = Color(0xFFDCFCE7)
    val Error600 = Color(0xFFDC2626)
    val Error100 = Color(0xFFFEE2E2)
    val Amber500 = Color(0xFFF59E0B)
    val Amber100 = Color(0xFFFEF3C7)
    val Amber800 = Color(0xFF92400E)

    val Success400 = Color(0xFF4ADE80)
    val Success900 = Color(0xFF14532D)
    val Success950 = Color(0xFF052E16)
    val Error200 = Color(0xFFFECACA)
    val Error400 = Color(0xFFF87171)
    val Error800 = Color(0xFF991B1B)
    val Error900 = Color(0xFF7F1D1D)
    val Error950 = Color(0xFF450A0A)
    val Amber950 = Color(0xFF422006)

    val ShadowInk = Color(0xFF2E1065)
}

object FieldLogoColors {
    val Terracotta = Color(0xFFCC785C)
    val Disc = Color(0xFF181715)
    val Cream = Color(0xFFFAF9F5)
}

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

@Immutable
data class FieldTokens(
    val body: Color,
    val muted: Color,
    val placeholder: Color,
    val surface50: Color,
    val surface100: Color,
    val surface200: Color,
    val surface300: Color,
    val hairline: Color,
    val brandTile: Color,
    val onBrandTile: Color,
    val onBrandTileMuted: Color,
    val accentOnBrandTile: Color,
    val errorOnBrandTile: Color,
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
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
    errorOnBrandTile = FieldPalette.Error200,
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
    errorOnBrandTile = FieldPalette.Error200,
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

val MaterialTheme.field: FieldTokens
    @Composable @ReadOnlyComposable get() = LocalFieldTokens.current

private val FieldLightColorScheme: ColorScheme = lightColorScheme(
    primary = FieldPalette.Purple700,
    onPrimary = Color.White,
    primaryContainer = FieldPalette.Purple100,
    onPrimaryContainer = FieldPalette.Purple800,
    inversePrimary = FieldPalette.Purple300,

    secondary = FieldPalette.Ink500Light,
    onSecondary = Color.White,
    secondaryContainer = FieldPalette.Surface100Light,
    onSecondaryContainer = FieldPalette.Ink900Light,

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

    surfaceBright = FieldPalette.CardLight,
    surfaceDim = FieldPalette.Surface200Light,
    surfaceContainerLowest = FieldPalette.CardLight,
    surfaceContainerLow = FieldPalette.Surface50Light,
    surfaceContainer = FieldPalette.Surface100Light,
    surfaceContainerHigh = FieldPalette.Surface200Light,
    surfaceContainerHighest = FieldPalette.Surface300Light
)

private val FieldDarkColorScheme: ColorScheme = darkColorScheme(
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

@OptIn(ExperimentalTextApi::class)
private fun variableFont(resId: Int, weight: FontWeight): Font = Font(
    resId = resId,
    weight = weight,
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

val FieldBodyFontFamily: FontFamily = FontFamily(
    variableFont(R.font.inter_variable, FontWeight.Normal),
    variableFont(R.font.inter_variable, FontWeight.Medium),
    variableFont(R.font.inter_variable, FontWeight.SemiBold),
    variableFont(R.font.inter_variable, FontWeight.Bold),
)
val FieldDisplayFontFamily: FontFamily = FontFamily(
    variableFont(R.font.jakarta_variable, FontWeight.Normal),
    variableFont(R.font.jakarta_variable, FontWeight.Medium),
    variableFont(R.font.jakarta_variable, FontWeight.SemiBold),
    variableFont(R.font.jakarta_variable, FontWeight.Bold),
    variableFont(R.font.jakarta_variable, FontWeight.ExtraBold),
)

private val TrackingTight = (-0.025).em
private val TrackingNormal = 0.em
private val TrackingWide = 0.025.em
private val TrackingEyebrow = 0.14.em

private fun displayStyle(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Bold,
    tracking: TextUnit = TrackingNormal
) = TextStyle(
    fontFamily = FieldDisplayFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking
)

private fun bodyStyle(size: Int, lineHeight: Int, weight: FontWeight) = TextStyle(
    fontFamily = FieldBodyFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = TrackingNormal
)

private val FieldTypography = Typography(
    displayLarge = displayStyle(72, 72, FontWeight.ExtraBold, TrackingTight),
    displayMedium = displayStyle(60, 60, FontWeight.ExtraBold, TrackingTight),
    displaySmall = displayStyle(34, 40, FontWeight.ExtraBold, TrackingTight),

    headlineLarge = displayStyle(36, 40, tracking = TrackingTight),
    headlineMedium = displayStyle(30, 36, tracking = TrackingTight),
    headlineSmall = displayStyle(24, 32),

    titleLarge = displayStyle(18, 28),
    titleMedium = displayStyle(16, 24),
    titleSmall = displayStyle(14, 20),

    bodyLarge = bodyStyle(16, 24, FontWeight.Normal),
    bodyMedium = bodyStyle(14, 20, FontWeight.Normal),
    bodySmall = bodyStyle(12, 16, FontWeight.Normal),

    labelLarge = bodyStyle(14, 20, FontWeight.Medium),
    labelMedium = bodyStyle(12, 16, FontWeight.Medium),
    labelSmall = bodyStyle(11, 16, FontWeight.Medium)
)

object FieldTextStyles {
    val CardTitle: TextStyle = FieldTypography.titleLarge

    val Eyebrow: TextStyle = TextStyle(
        fontFamily = FieldBodyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = TrackingEyebrow
    )

    val FieldLabel: TextStyle = TextStyle(
        fontFamily = FieldBodyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = TrackingWide
    )

    val Badge: TextStyle = TextStyle(
        fontFamily = FieldBodyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = TrackingNormal
    )

    val Link: TextStyle = TextStyle(
        fontFamily = FieldBodyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = TrackingNormal,
        textDecoration = TextDecoration.Underline
    )
}

private val FieldShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun FieldRepositoryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) FieldDarkColorScheme else FieldLightColorScheme
    val tokens = if (darkTheme) DarkFieldTokens else LightFieldTokens

    if (legacyPalette.value.tokens !== tokens) {
        legacyPalette.value = LegacyPalette(colorScheme, tokens)
    }

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

private class LegacyPalette(val scheme: ColorScheme, val tokens: FieldTokens)

private val legacyPalette = mutableStateOf(LegacyPalette(FieldLightColorScheme, LightFieldTokens))

val Canvas: Color get() = legacyPalette.value.scheme.background

val SurfaceCard: Color get() = legacyPalette.value.scheme.surfaceVariant

val Coral: Color get() = legacyPalette.value.scheme.primary

val CoralActive: Color
    get() = if (legacyPalette.value.tokens.isDark) FieldPalette.Purple300 else FieldPalette.Purple800

val Ink: Color get() = legacyPalette.value.scheme.onBackground

val Body: Color get() = legacyPalette.value.tokens.body

val Muted: Color get() = legacyPalette.value.scheme.onSurfaceVariant

val Hairline: Color get() = legacyPalette.value.scheme.outline

val SuccessInk: Color get() = legacyPalette.value.tokens.success

val ErrorInk: Color get() = legacyPalette.value.scheme.error

val DarkSurface: Color get() = legacyPalette.value.tokens.brandTile
