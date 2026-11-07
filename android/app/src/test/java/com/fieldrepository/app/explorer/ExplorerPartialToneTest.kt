package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.data.ExplorerJob
import com.fieldrepository.app.explorer.data.ExplorerJobState
import com.fieldrepository.app.explorer.data.ExplorerRequest
import com.fieldrepository.app.explorer.data.ExplorerResult
import com.fieldrepository.app.explorer.ui.ExplorerStatus
import com.fieldrepository.app.explorer.ui.ExplorerTones
import com.fieldrepository.app.explorer.ui.jobStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ExplorerPartialToneTest {
    private fun done(saved: Int, failed: Int) = ExplorerJob(
        id = "j",
        request = ExplorerRequest(title = "Bagru", fileName = "Bagru.zip"),
        createdAt = 0,
        state = ExplorerJobState.DONE,
        result = ExplorerResult(saved = saved, total = saved + failed, failedCount = failed),
    )

    @Test fun `saved is green, failed is red and partial is never read as success`() {
        assertEquals(ExplorerStatus.SAVED, jobStatus(done(3, 0)))
        assertEquals(ExplorerStatus.PARTIAL, jobStatus(done(2, 1)))
        assertEquals(ExplorerStatus.FAILED, jobStatus(done(0, 3)))
        assertEquals(ExplorerStatus.FAILED, jobStatus(done(0, 0).copy(state = ExplorerJobState.FAILED)))
        assertNotEquals(ExplorerTones.savedLight, ExplorerTones.warnLight)
    }
}
