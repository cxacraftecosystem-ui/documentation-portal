package com.fieldrepository.app.data

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A record saved now must not wait out the backoff of a pass that failed earlier: a waiting pass is
 * replaced by a fresh one, and only a pass that is uploading right now is left alone.
 */
class OutboxSyncPolicyTest {
    @Test
    fun `a running pass is kept and a waiting one is replaced`() {
        assertEquals(ExistingWorkPolicy.KEEP, OutboxSyncWorker.policyFor(running = true))
        assertEquals(ExistingWorkPolicy.REPLACE, OutboxSyncWorker.policyFor(running = false))
    }
}
