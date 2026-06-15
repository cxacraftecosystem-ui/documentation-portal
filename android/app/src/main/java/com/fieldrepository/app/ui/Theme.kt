package com.fieldrepository.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Canvas = Color(0xFFFAF9F5)
val SurfaceCard = Color(0xFFEFE9DE)
val Coral = Color(0xFFCC785C)
val CoralActive = Color(0xFFA9583E)
val Ink = Color(0xFF141413)
val Body = Color(0xFF3D3D3A)
val Muted = Color(0xFF6C6A64)
val Hairline = Color(0xFFE6DFD8)
val DarkSurface = Color(0xFF181715)

private val ColorScheme = lightColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    secondary = DarkSurface,
    onSecondary = Canvas,
    background = Canvas,
    onBackground = Ink,
    surface = Canvas,
    onSurface = Ink,
    surfaceVariant = SurfaceCard,
    outline = Hairline,
    error = Color(0xFFC64545)
)

@Composable
fun FieldRepositoryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        content = content
    )
}
