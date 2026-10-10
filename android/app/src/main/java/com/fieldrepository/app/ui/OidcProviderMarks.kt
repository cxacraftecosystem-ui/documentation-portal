package com.fieldrepository.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.OidcProviderId

/**
 * The Microsoft and Yahoo marks, drawn from the same geometry as the web's inline SVGs
 * (`frontend/app/login/page.tsx`), so no drawable resource is needed and the two clients cannot
 * drift apart. Decorative: the button's label names the provider.
 */
@Composable
fun OidcProviderMark(provider: OidcProviderId, size: Dp = 20.dp) {
    when (provider) {
        OidcProviderId.MICROSOFT -> MicrosoftMark(size)
        OidcProviderId.YAHOO -> YahooMark(size)
    }
}

/** Four squares in a 21x21 box, exactly as the web's `MicrosoftMark` lays them out. */
@Composable
private fun MicrosoftMark(size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val unit = this.size.minDimension / 21f
        fun cell(x: Float, y: Float, color: Color) =
            drawRect(color = color, topLeft = Offset(x * unit, y * unit), size = Size(9 * unit, 9 * unit))
        cell(1f, 1f, Color(0xFFF25022))
        cell(11f, 1f, Color(0xFF7FBA00))
        cell(1f, 11f, Color(0xFF00A4EF))
        cell(11f, 11f, Color(0xFFFFB900))
    }
}

/** The web's `YahooMark` path data, verbatim, parsed once. */
private const val YAHOO_PATH =
    "M0 6.71h4.62l2.69 6.88 2.72-6.88h4.5L7.76 22.5H3.23l1.86-4.32L0 6.71zm17.62 5.05h-5.03L17.06 " +
        "1.5h5.02l-4.46 10.26zm-3.03 1.4c1.55 0 2.8 1.26 2.8 2.81a2.8 2.8 0 1 1-5.61 0c0-1.55 " +
        "1.26-2.8 2.81-2.8z"

@Composable
private fun YahooMark(size: Dp) {
    val path = remember { PathParser().parsePathString(YAHOO_PATH).toPath() }
    Canvas(modifier = Modifier.size(size)) {
        val factor = this.size.minDimension / 24f
        scale(factor, factor, pivot = Offset.Zero) {
            drawPath(path, Color(0xFF5F01D1))
        }
    }
}
