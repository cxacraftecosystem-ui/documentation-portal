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
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.data.CarryContext
import com.fieldrepository.app.data.CarryContextStore
import com.fieldrepository.app.data.CarryNode

/**
 * "Continuing with Giriraj Prasad Chhipa · Bagru Block Printing" — the visible half of
 * [CarryContextStore].
 *
 * THE RULE THIS EXISTS TO ENFORCE: a prefill the researcher does not notice is how a product ends
 * up attached to the wrong artisan, and in a research corpus that is a falsified row, not an
 * inconvenience. So a carried context is never quietly poured into the fields. Whenever something
 * was prefilled, this sits at the top of the form naming EVERY record it brought — each one labelled
 * by what it is — where they came from and how old they are, with one control that clears the lot.
 *
 * Naming every record is what makes the ladder in [CarryContextStore] safe to have. The bag implies
 * parents: pick up a product and its artisan and craft come with it. An implication nobody can see
 * is indistinguishable from a guess, so the chain is printed in full and the provenance line says
 * out loud which record was chosen and which ones tagged along.
 *
 * That is also why there is no "dismiss" that keeps the values. Hiding the banner while leaving the
 * fields filled would recreate the exact hazard it was put there to remove. It goes away on one
 * event only — the researcher picking a record themselves, at which point the values are their
 * choice rather than our guess.
 *
 * Word-for-word parity with the web's `components/forms/CarryContextBanner.tsx`.
 */

/** Where an offer came from: a tap straight off the save screen, or a form opened cold. */
enum class CarryOfferSource { HANDOFF, RESUMED }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarryContextBanner(
    context: CarryContext,
    source: CarryOfferSource,
    /** Epoch ms the context was recorded; "now" for a handoff. */
    savedAt: Long,
    onChange: () -> Unit,
    modifier: Modifier = Modifier,
    /** The record the context was last built around — what the provenance sentence is about. */
    origin: CarryNode? = null,
    changeLabel: String = "Change"
) {
    // The headline names the subject the researcher thinks in — the person — and falls back down
    // the ladder only when there is no artisan to name.
    val headline = context.artisanName ?: context.productName ?: context.toolName ?: context.craftName ?: "the last record"
    val chain = remember(context) { CarryContextStore.describeCarryChain(context) }
    val implied = remember(context, origin) { CarryContextStore.describeCarryImplied(context, origin) }
    val originLabel = origin?.label ?: "record"
    val provenance = when (source) {
        CarryOfferSource.HANDOFF -> "Carried from the $originLabel you just saved."
        // Recomputed per composition on purpose: the phrase ages while the form is open.
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
                    fontSize = 14.sp,
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
                                    Text(label.uppercase(), fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
                                    Text(value, fontSize = 11.sp, color = Body)
                                }
                            }
                        }
                    }
                }
                Text(
                    listOf(provenance, implied, "Prefilled below — check it before saving.")
                        .filter { it.isNotEmpty() }
                        .joinToString(" "),
                    fontSize = 12.sp,
                    color = Muted
                )
            }
            TextButton(onClick = onChange) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(changeLabel, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}
