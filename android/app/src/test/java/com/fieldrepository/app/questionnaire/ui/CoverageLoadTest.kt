package com.fieldrepository.app.questionnaire.ui

import com.fieldrepository.app.questionnaire.data.QGrid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class CoverageLoadTest {
    @Test
    fun aDesignWorkshopIsSentByItsIdNeverAsAnArtisanList() {
        assertEquals(null to "dw1", coverageQuery("dw1", underDesignWorkshop = true))
        assertEquals("w1" to null, coverageQuery("w1", underDesignWorkshop = false))
        assertEquals(null to null, coverageQuery("", underDesignWorkshop = true))
    }

    @Test
    fun aFailedLoadIsKeptOnTheScreenAndTheNextWorkshopStartsClean() = runBlocking {
        val failed = loadCoverage({ throw IllegalStateException("boom") }) { "Couldn't load coverage" }
        assertNull(failed.grid)
        assertEquals("Couldn't load coverage", failed.error)
        val grid = QGrid()
        val next = loadCoverage({ grid }) { "unused" }
        assertSame(grid, next.grid)
        assertNull(next.error)
    }

    @Test
    fun switchingWorkshopsMidLoadIsNotAnError() = runBlocking {
        try {
            loadCoverage({ throw CancellationException("switched") }) { "should not be shown" }
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertEquals("switched", e.message)
        }
    }
}
