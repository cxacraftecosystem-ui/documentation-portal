package com.fieldrepository.app.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.fieldrepository.app.R

/*
 * Field Repository — sign-in, rebuilt against the web login (frontend/app/login/page.tsx).
 *
 * THE WEB LAYOUT is a two-column brand/auth split: a deep purple (purple-950) brand panel on the
 * left carrying the logo in a cream tile, a gold eyebrow, a gold-accented headline and three
 * value bullets; and on the right a frosted card floating on a mesh backdrop with email + password,
 * then "Continue with Google" (live) plus Microsoft and Yahoo, which raise a "Coming soon" notice
 * rather than fire a dead request. All four sign-in controls are one height (52px) and one radius.
 *
 * ON A PHONE the split becomes a STACK — brand band above, card below — which is the same reading
 * order the web produces when its columns collapse, and the copy, the colours, the type scale and
 * the button treatment are unchanged. Four things are deliberately different, all forced by the
 * platform rather than chosen:
 *
 *  1. The band's headline is [Typography.headlineSmall] (text-2xl) instead of the web's text-3xl.
 *     30sp of Jakarta Bold wraps to four lines at 360dp and pushes the password field off-screen.
 *  2. The web hides the brand panel entirely below `lg` and shows a small logo inside the card
 *     instead. Here the band is always present, so that in-card logo would be the same mark twice
 *     and it is dropped.
 *  3. Google sign-in is Credential Manager, not the GSI web button, so there is no transparent
 *     overlay to align — the button is simply ours and calls [onGoogleLogin].
 *  4. The footer link "No account yet? See what Field Repository does" points at the public
 *     marketing page, which this app does not contain. In its place the screen keeps the guidance
 *     note about Google versus admin-issued password accounts — researchers were typing into the
 *     password fields and locking themselves out, so removing it would be a regression.
 */

/** Web `BRAND_POINTS`, verbatim. */
private val BRAND_POINTS = listOf(
    "Artisans, crafts, workshops, products, tools and interviews — one connected archive.",
    "Recordings transcribed and translated to English automatically.",
    "Six-tier access control; every edit audited."
)

/** Every control on the card is one height and one radius — the web's `size: auth` variant. */
private val AuthControlHeight = 52.dp
private val AuthControlShape = RoundedCornerShape(12.dp) // `rounded-md` is 12px in this config
private val AuthCardShape = RoundedCornerShape(24.dp)    // `rounded-xl`

@Composable
fun AuthScreen(
    error: String?,
    busy: Boolean,
    onLogin: (String, String) -> Unit,
    onGoogleLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    val canSubmit = !busy && email.isNotBlank() && password.isNotBlank()
    fun submit() {
        if (canSubmit) {
            focusManager.clearFocus()
            onLogin(email, password)
        }
    }

    /** Fires the notice and nothing else — these providers have no endpoint behind them yet. */
    fun comingSoon(provider: String) {
        Toast.makeText(
            context,
            "$provider sign-in is coming soon — use Google, or your email and password, for now.",
            Toast.LENGTH_LONG
        ).show()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BrandBand()
        AuthCard(
            error = error,
            busy = busy,
            email = email,
            onEmailChange = { email = it },
            password = password,
            onPasswordChange = { password = it },
            showPassword = showPassword,
            onToggleShowPassword = { showPassword = !showPassword },
            canSubmit = canSubmit,
            onSubmit = ::submit,
            onGoogleLogin = onGoogleLogin,
            onComingSoon = ::comingSoon
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Brand band — the web's left panel
// ---------------------------------------------------------------------------------------------

@Composable
private fun BrandBand() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AuthCardShape)
            .background(FieldPalette.Purple950)
    ) {
        // The two ambient orbs the web paints behind the panel: a purple-500 wash top-left and a
        // faint gold one bottom-right. Radial brushes with a transparent outer stop are the Compose
        // equivalent of the page's `radial-gradient(circle, …, transparent 62%)`.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(FieldPalette.Purple700.copy(alpha = 0.50f), Color.Transparent),
                        center = Offset(0f, 0f),
                        radius = 620f
                    )
                )
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(FieldPalette.Gold500.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                        radius = 560f
                    )
                )
        )

        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldRepoLogo(modifier = Modifier.size(44.dp), cornerRadius = 12.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Field Repository",
                    style = MaterialTheme.typography.titleLarge, // web: font-display text-xl font-bold
                    color = Color.White
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "LIVING CRAFT DOCUMENTATION",
                    style = FieldTextStyles.Eyebrow,
                    color = FieldPalette.Gold300
                )
                Text(
                    // "understanding" carries the web's `.text-gold-gradient` — a 115° sweep across
                    // the gold ramp, applied to that one span and nothing else.
                    text = buildAnnotatedString {
                        append("Every masterpiece begins with ")
                        withStyle(
                            SpanStyle(
                                brush = Brush.linearGradient(
                                    0.00f to FieldPalette.Gold200,
                                    0.42f to FieldPalette.Gold500,
                                    0.58f to FieldPalette.Gold300,
                                    1.00f to FieldPalette.Gold600
                                )
                            )
                        ) { append("understanding") }
                        append(".")
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BRAND_POINTS.forEach { point ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .padding(top = 7.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(FieldPalette.Gold400)
                        )
                        Text(
                            point,
                            // text-sm leading-relaxed text-white/75
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 23.sp),
                            color = Color.White.copy(alpha = 0.75f)
                        )
                    }
                }
            }

            Text(
                "New Google accounts join as Crowdsource Volunteers and are elevated by an admin.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.45f)
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Auth card — the web's frosted panel on the mesh backdrop
// ---------------------------------------------------------------------------------------------

@Composable
private fun AuthCard(
    error: String?,
    busy: Boolean,
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    showPassword: Boolean,
    onToggleShowPassword: () -> Unit,
    canSubmit: Boolean,
    onSubmit: () -> Unit,
    onGoogleLogin: () -> Unit,
    onComingSoon: (String) -> Unit
) {
    val tokens = MaterialTheme.field
    val scheme = MaterialTheme.colorScheme

    Box(modifier = Modifier.fillMaxWidth()) {
        // `grad-mesh`: three radial orbs — two purple, one faint amber — behind a translucent card.
        // Drawn on the card's own footprint because the phone has no separate right-hand column.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(AuthCardShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(FieldPalette.Purple500.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(120f, 160f),
                        radius = 760f
                    )
                )
                .background(
                    Brush.radialGradient(
                        colors = listOf(FieldPalette.Amber500.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(900f, 120f),
                        radius = 640f
                    )
                )
                .background(
                    Brush.radialGradient(
                        colors = listOf(FieldPalette.Purple600.copy(alpha = 0.12f), Color.Transparent),
                        center = Offset(700f, 1500f),
                        radius = 880f
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AuthCardShape)
                // `.glass-card`: rgba(255,255,255,0.72) with a light rim. Compose cannot blur what is
                // behind a composable without a RenderEffect on API 31+, so the frost is expressed as
                // the same translucent fill over the mesh — the surface reads the same either way,
                // which is exactly the fallback the web itself uses on Safari and Firefox.
                .background(scheme.surface.copy(alpha = if (tokens.isDark) 0.80f else 0.72f))
                .border(
                    1.dp,
                    if (tokens.isDark) scheme.outline else Color.White.copy(alpha = 0.60f),
                    AuthCardShape
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Welcome back",
                    style = MaterialTheme.typography.headlineSmall, // font-display text-2xl font-bold
                    color = scheme.onSurface
                )
                Text(
                    "Sign in to your account",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.muted
                )
            }

            if (!error.isNullOrBlank()) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(AuthControlShape)
                        .background(scheme.errorContainer)
                        .border(1.dp, scheme.error.copy(alpha = 0.35f), AuthControlShape)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            AuthField(
                label = "Email address",
                value = email,
                onValueChange = onEmailChange,
                placeholder = "Enter your email",
                leadingIcon = Icons.Filled.Mail,
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            )

            AuthField(
                label = "Password",
                value = password,
                onValueChange = onPasswordChange,
                placeholder = "Enter your password",
                leadingIcon = Icons.Filled.Lock,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                onImeAction = onSubmit,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    IconButton(onClick = onToggleShowPassword, modifier = Modifier.size(40.dp)) {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                            tint = tokens.placeholder,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            )

            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                shape = AuthControlShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary,
                    disabledContainerColor = scheme.outline,
                    disabledContentColor = tokens.muted
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AuthControlHeight)
            ) {
                Text(
                    if (busy) "Signing in…" else "Sign In",
                    // web: font-display text-base font-bold
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // "OR" rule
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Box(Modifier.weight(1f).height(1.dp).background(scheme.outline))
                Text("OR", style = MaterialTheme.typography.bodyMedium, color = tokens.placeholder)
                Box(Modifier.weight(1f).height(1.dp).background(scheme.outline))
            }

            ProviderButton(
                label = "Continue with Google",
                enabled = !busy,
                onClick = onGoogleLogin,
                mark = {
                    Icon(
                        painter = painterResource(R.drawable.ic_google_g),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
            ProviderButton(
                label = "Continue with Microsoft",
                enabled = !busy,
                onClick = { onComingSoon("Microsoft") },
                comingSoon = true,
                mark = { MicrosoftMark() }
            )
            ProviderButton(
                label = "Continue with Yahoo",
                enabled = !busy,
                onClick = { onComingSoon("Yahoo") },
                comingSoon = true,
                mark = { YahooMark() }
            )

            // Android-only guidance (see the file header): most researchers hold Google accounts and
            // the password fields exist for admin-issued accounts alone.
            Text(
                "Researchers: please use \"Continue with Google\" above. The email and password fields " +
                    "are only for special accounts an administrator set up with a password — if you " +
                    "normally use your Google account, do not type a password here.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------------------------

/**
 * `.field-input` at the auth card's 52px height: a 12px-radius box with a line-200 hairline that
 * turns purple-600 on focus, behind a purple-tinted focus ring. The ring's 4dp is reserved whether
 * or not the field has focus, so nothing shifts when the keyboard opens.
 *
 * BasicTextField rather than OutlinedTextField because Material's notched outline and floating label
 * are a different design language from the web's plain label-above-input.
 */
@Composable
private fun AuthField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    onImeAction: () -> Unit = {},
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null
) {
    val tokens = MaterialTheme.field
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            // web: text-sm font-medium text-ink-900
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurface
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    4.dp,
                    if (focused) scheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                    RoundedCornerShape(16.dp)
                )
                .padding(4.dp)
                .clip(AuthControlShape)
                .background(scheme.surface)
                .border(
                    1.dp,
                    if (focused) FieldPalette.Purple600 else scheme.outline,
                    AuthControlShape
                )
                .heightIn(min = AuthControlHeight)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = tokens.muted,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    visualTransformation = visualTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                    keyboardActions = KeyboardActions(onDone = { onImeAction() }),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focused = it.isFocused },
                    // The placeholder has to sit BEHIND the field in a Box. Emitting the two as bare
                    // siblings hands the decoration slot two measurables and the text lands on top
                    // of the hint at whatever position the slot happens to give it.
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty()) {
                                Text(
                                    placeholder,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = tokens.placeholder
                                )
                            }
                            inner()
                        }
                    }
                )
                if (trailing != null) {
                    Spacer(Modifier.width(4.dp))
                    trailing()
                }
            }
        }
    }
}

/**
 * The web's `variant: "provider"` — tinted rather than filled, so it reads as part of the card
 * instead of sitting on top of it. Same 52dp height and 12dp radius as everything else here.
 */
@Composable
private fun ProviderButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    mark: @Composable () -> Unit,
    comingSoon: Boolean = false
) {
    val tokens = MaterialTheme.field
    val scheme = MaterialTheme.colorScheme

    Button(
        onClick = onClick,
        enabled = enabled,
        shape = AuthControlShape,
        border = BorderStroke(1.dp, scheme.outline),
        colors = ButtonDefaults.buttonColors(
            containerColor = scheme.surface.copy(alpha = 0.60f),
            contentColor = scheme.onSurface,
            disabledContainerColor = scheme.surface.copy(alpha = 0.35f),
            disabledContentColor = tokens.placeholder
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(AuthControlHeight)
    ) {
        mark()
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            // web: text-[15px] font-medium
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (comingSoon) {
            Spacer(Modifier.width(8.dp))
            Text(
                "Coming soon",
                // web: rounded-full bg-purple-50 px-2 py-0.5 text-[11px] font-semibold text-purple-700
                style = FieldTextStyles.Badge,
                color = scheme.onPrimaryContainer,
                maxLines = 1,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(scheme.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Provider marks — the official artwork, drawn from the same geometry as the web's inline SVGs so
// no new drawable resources are needed and the two clients cannot drift.
// ---------------------------------------------------------------------------------------------

/** Four squares in a 21×21 box, exactly as `MicrosoftMark` lays them out. */
@Composable
private fun MicrosoftMark(size: androidx.compose.ui.unit.Dp = 20.dp) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        val unit = this.size.minDimension / 21f
        fun cell(x: Float, y: Float, color: Color) = drawRect(
            color = color,
            topLeft = Offset(x * unit, y * unit),
            size = androidx.compose.ui.geometry.Size(9 * unit, 9 * unit)
        )
        cell(1f, 1f, Color(0xFFF25022))
        cell(11f, 1f, Color(0xFF7FBA00))
        cell(1f, 11f, Color(0xFF00A4EF))
        cell(11f, 11f, Color(0xFFFFB900))
    }
}

/** The Yahoo mark, from the identical SVG path data the web ships, parsed at runtime. */
private const val YAHOO_PATH =
    "M0 6.71h4.62l2.69 6.88 2.72-6.88h4.5L7.76 22.5H3.23l1.86-4.32L0 6.71zm17.62 5.05h-5.03L17.06 " +
        "1.5h5.02l-4.46 10.26zm-3.03 1.4c1.55 0 2.8 1.26 2.8 2.81a2.8 2.8 0 1 1-5.61 0c0-1.55 " +
        "1.26-2.8 2.81-2.8z"

@Composable
private fun YahooMark(size: androidx.compose.ui.unit.Dp = 20.dp) {
    val path = remember { PathParser().parsePathString(YAHOO_PATH).toPath() }
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        val scale = this.size.minDimension / 24f
        scale(scale, scale, pivot = Offset.Zero) {
            drawPath(path, Color(0xFF5F01D1))
        }
    }
}
