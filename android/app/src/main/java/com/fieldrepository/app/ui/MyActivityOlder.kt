package com.fieldrepository.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

object MyActivityCopy {
    fun showing(shown: Int) = "Newest $shown shown."
    const val older = "Load older records"
    const val loading = "Loading…"
    const val failed = "Older records didn't load. Try again."
}

@Composable
fun MyActivityOlder(shown: Int, loading: Boolean, failed: Boolean, onMore: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("my-activity-older"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            if (failed) MyActivityCopy.failed else MyActivityCopy.showing(shown),
            color = if (failed) MaterialTheme.colorScheme.error else Muted,
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(
            onClick = onMore,
            enabled = !loading,
            modifier = Modifier.heightIn(min = 48.dp).testTag("my-activity-more")
        ) { Text(if (loading) MyActivityCopy.loading else MyActivityCopy.older) }
    }
}
