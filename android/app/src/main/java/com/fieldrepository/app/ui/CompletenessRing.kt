package com.fieldrepository.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The completeness ring — the design workshop list's (46dp, with its percentage) and the record cards'
 * (18dp, no label), one drawing.
 *
 * Drawn rather than approximated with a determinate CircularProgressIndicator because that control
 * leaves a gap at the top for its indeterminate animation, so a record at 100% renders as a ring that
 * is visibly not closed — which reads, on a list somebody is scanning to decide what is finished, as
 * "almost".
 *
 * [description] is the spoken name ("5 of 7 fields filled"); null leaves the ring to the text beside it.
 */
@Composable
fun CompletenessRing(
    percent: Int,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    stroke: Dp = 5.dp,
    showLabel: Boolean = true,
    description: String? = null,
) {
    val track = FieldProgressDefaults.trackColor
    val fill = if (percent >= 100) FieldProgressDefaults.successColor else FieldProgressDefaults.indicatorColor
    val described = if (description != null) modifier.semantics { contentDescription = description } else modifier
    Box(contentAlignment = Alignment.Center, modifier = described.size(size)) {
        Canvas(modifier = Modifier.size(size)) {
            val width = stroke.toPx()
            val inset = width / 2f
            val box = Size(this.size.width - width, this.size.height - width)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = box,
                style = Stroke(width = width)
            )
            drawArc(
                color = fill,
                startAngle = -90f,
                sweepAngle = 360f * (percent.coerceIn(0, 100) / 100f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = box,
                style = Stroke(width = width, cap = StrokeCap.Round)
            )
        }
        if (showLabel) {
            Text("$percent", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
