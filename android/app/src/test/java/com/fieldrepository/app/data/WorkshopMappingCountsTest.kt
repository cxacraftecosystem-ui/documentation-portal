package com.fieldrepository.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The "Records not filed under a workshop" card's counts (A6, 2026-10-20) — the web's
 * `components/settings/workshopMapping.ts`, rule for rule and word for word. The server files the
 * PARENT-rung rows itself each night, so the card leads with what is left for a person; against an
 * older server (no `clearCut`, no `autoMap`) every count falls back to the old fields.
 */
class WorkshopMappingCountsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun bucket(
        unassigned: Int,
        resolved: Int,
        unresolved: Int,
        clearCut: Int? = null,
        needsReview: Int? = null,
        byRung: List<WorkshopMappingRungCountDto> = emptyList()
    ) = WorkshopMappingBucketDto(
        bucket = "media",
        singular = "media file",
        plural = "media files",
        unassigned = unassigned,
        resolved = resolved,
        unresolved = unresolved,
        clearCut = clearCut,
        needsReview = needsReview,
        byRung = byRung
    )

    @Test
    fun countsComeFromTheNewFieldsOrFallBackToTheParentRung() {
        val fresh = bucket(10, 8, 2, clearCut = 6, needsReview = 4)
        assertEquals(4, WorkshopMappingCounts.needsReview(fresh))
        assertEquals(2, WorkshopMappingCounts.suggested(fresh))
        val old = bucket(
            10, 8, 2,
            byRung = listOf(
                WorkshopMappingRungCountDto("PARENT", "the record it belongs to already names that workshop", 6),
                WorkshopMappingRungCountDto("WINDOW", "it was recorded inside that workshop's dates", 2)
            )
        )
        assertEquals(4, WorkshopMappingCounts.needsReview(old))
        assertEquals(listOf("WINDOW"), WorkshopMappingCounts.reviewRungs(old).map { it.rung })
    }

    @Test
    fun theNightlyLineMatchesTheWeb() {
        val status = WorkshopAutoMapDto(enabled = true, runsAt = "02:00", lastRunAt = "2026-10-20T20:30:00Z", lastFiled = 12)
        assertEquals("Every night at 02:00. 3 records tonight. Last run filed 12.", WorkshopMappingCounts.autoMapLine(status, 3))
        assertEquals("Every night at 02:00.", WorkshopMappingCounts.autoMapLine(status.copy(lastRunAt = null), 0))
        assertEquals("1 record waiting to be filed.", WorkshopMappingCounts.autoMapLine(status.copy(enabled = false), 1))
        assertEquals("", WorkshopMappingCounts.autoMapLine(null, 5))
    }

    @Test
    fun anOlderServersPayloadStillDecodes() {
        val plan = json.decodeFromString<WorkshopMappingPlanDto>(
            """{"workshops":[],"buckets":[{"bucket":"media","unassigned":2,"resolved":1,"unresolved":1}],
               "totals":{"unassigned":2,"resolved":1,"unresolved":1,"applied":null}}"""
        )
        assertNull(plan.autoMap)
        assertEquals(2, WorkshopMappingCounts.totalNeedsReview(plan))
    }

    @Test
    fun theNewPayloadDecodes() {
        val plan = json.decodeFromString<WorkshopMappingPlanDto>(
            """{"workshops":[],"buckets":[],"totals":{"unassigned":5,"resolved":4,"unresolved":1,"clearCut":3,"needsReview":2,"applied":null},
               "autoMap":{"enabled":false,"runsAt":"02:00","timezone":"Asia/Kolkata","lastRunAt":null,"lastFiled":0}}"""
        )
        assertEquals(false, plan.autoMap?.enabled)
        assertEquals(2, WorkshopMappingCounts.totalNeedsReview(plan))
        assertEquals(3, WorkshopMappingCounts.totalClearCut(plan))
    }
}
