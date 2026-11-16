package com.fieldrepository.app.data

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class OutboxSyncPolicyTest {
    @Test
    fun `a request during a running pass queues one pass behind it, and a waiting one is replaced`() {
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, OutboxSyncWorker.policyFor(running = true, queuedBehind = false))
        assertEquals(ExistingWorkPolicy.KEEP, OutboxSyncWorker.policyFor(running = true, queuedBehind = true))
        assertEquals(ExistingWorkPolicy.REPLACE, OutboxSyncWorker.policyFor(running = false, queuedBehind = false))
        assertEquals(ExistingWorkPolicy.REPLACE, OutboxSyncWorker.policyFor(running = false, queuedBehind = true))
    }
}
