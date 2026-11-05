package com.fieldrepository.app.explorer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.fieldrepository.app.ui.field

enum class ExplorerStatus { NEUTRAL, SAVED, FAILED }

@Immutable
data class ExplorerTone(
    val container: Color,
    val border: Color,
    val content: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
)

object ExplorerTones {
    val savedLight = ExplorerTone(Color(0xFFDCFCE7), Color(0xFF86EFAC), Color(0xFF14532D), Color(0xFF166534), Color(0xFF15803D), Color.White)
    val failedLight = ExplorerTone(Color(0xFFFEE2E2), Color(0xFFFCA5A5), Color(0xFF7F1D1D), Color(0xFF991B1B), Color(0xFFB91C1C), Color.White)
    val savedDark = ExplorerTone(Color(0xFF123B25), Color(0xFF15803D), Color(0xFFEAFBF0), Color(0xFFC9EED6), Color(0xFF86EFAC), Color(0xFF052E16))
    val failedDark = ExplorerTone(Color(0xFF4A1717), Color(0xFFB91C1C), Color(0xFFFDEAEA), Color(0xFFF5B5B5), Color(0xFFFCA5A5), Color(0xFF450A0A))
    val warnLight = ExplorerTone(Color(0xFFFEF3C7), Color(0xFFFCD34D), Color(0xFF78350F), Color(0xFF92400E), Color(0xFF92400E), Color.White)
    val warnDark = ExplorerTone(Color(0xFF3B2A06), Color(0xFFB45309), Color(0xFFFDE68A), Color(0xFFFCD34D), Color(0xFFFCD34D), Color(0xFF3B2A06))
    val mark = Color(0xFFF2E9FE)
    val onMark = Color(0xFF5F2491)
    val markDark = Color(0xFF3B1F5C)
    val onMarkDark = Color(0xFFE7D5FE)
}

@Composable
@ReadOnlyComposable
fun explorerTone(status: ExplorerStatus): ExplorerTone? {
    val dark = MaterialTheme.field.isDark
    return when (status) {
        ExplorerStatus.SAVED -> if (dark) ExplorerTones.savedDark else ExplorerTones.savedLight
        ExplorerStatus.FAILED -> if (dark) ExplorerTones.failedDark else ExplorerTones.failedLight
        ExplorerStatus.NEUTRAL -> null
    }
}

@Composable
@ReadOnlyComposable
fun explorerWarnTone(): ExplorerTone = if (MaterialTheme.field.isDark) ExplorerTones.warnDark else ExplorerTones.warnLight

@Composable
@ReadOnlyComposable
fun explorerMark(): Pair<Color, Color> =
    if (MaterialTheme.field.isDark) ExplorerTones.markDark to ExplorerTones.onMarkDark else ExplorerTones.mark to ExplorerTones.onMark
