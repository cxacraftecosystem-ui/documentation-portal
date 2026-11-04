package com.fieldrepository.app.data

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class OutboxSyncPolicyTest {
    @Test
    fun `a running pass is kept and a waiting one is replaced`() {
        assertEquals(ExistingWorkPolicy.KEEP, OutboxSyncWorker.policyFor(running = true))
        assertEquals(ExistingWorkPolicy.REPLACE, OutboxSyncWorker.policyFor(running = false))
    }
}
