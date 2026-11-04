package com.fieldrepository.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.CarryContext
import com.fieldrepository.app.data.CarryContextStore
import com.fieldrepository.app.data.CarryNode

enum class CarryOfferSource { HANDOFF, RESUMED }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarryContextBanner(
    context: CarryContext,
    source: CarryOfferSource,
    savedAt: Long,
    onChange: () -> Unit,
    modifier: Modifier = Modifier,
    origin: CarryNode? = null,
    changeLabel: String = "Change"
) {
    val headline = context.artisanName ?: context.productName ?: context.toolName ?: context.craftName ?: "the last record"
    val chain = remember(context) { CarryContextStore.describeCarryChain(context) }
    val implied = remember(context, origin) { CarryContextStore.describeCarryImplied(context, origin) }
    val originLabel = origin?.label ?: "record"
    val provenance = when (source) {
        CarryOfferSource.HANDOFF -> "Carried from the $originLabel you just saved."
        CarryOfferSource.RESUMED -> "From the $originLabel you documented ${CarryContextStore.describeCarryAge(savedAt)}."
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Filled.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Continuing with $headline",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (chain.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        chain.forEach { (label, value) ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    Text(value, style = MaterialTheme.typography.bodySmall, color = Body)
                                }
                            }
                        }
                    }
                }
                Text(
                    listOf(provenance, implied, "Prefilled below — check it before saving.")
                        .filter { it.isNotEmpty() }
                        .joinToString(" "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
            }
            TextButton(onClick = onChange) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(changeLabel, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}
