package com.fieldrepository.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every transcript card on a screen is read, and polled, through one `GET /media/insights?ids=`. */
class TranscriptInsightBatcherTest {

    private fun view(id: String, state: String = "PROCESSING") = TranscriptInsightViewDto(mediaId = id, state = state)

    @Test
    fun cardsAskingTogetherShareOneRequest() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val batcher = TranscriptInsightBatcher({ ids ->
            synchronized(calls) { calls += ids }
            ids.filter { it != "gone" }.associateWith { view(it) }
        })
        val answers = listOf("a", "b", "c", "gone", "a").map { id -> async { batcher.load(id) } }.awaitAll()
        assertEquals(listOf(listOf("a", "b", "c", "gone")), calls)
        assertEquals("a", answers[0]?.mediaId)
        assertEquals("c", answers[2]?.mediaId)
        assertNull(answers[3])
        assertEquals("a", answers[4]?.mediaId)
    }

    @Test
    fun moreThanFiftyIdsAreSplit() = runBlocking {
        val sizes = mutableListOf<Int>()
        val batcher = TranscriptInsightBatcher({ ids ->
            synchronized(sizes) { sizes += ids.size }
            emptyMap()
        })
        (1..120).map { id -> async { batcher.load("m$id") } }.awaitAll()
        assertEquals(listOf(50, 50, 20), sizes)
    }

    @Test
    fun aFailedBatchHidesEveryCardAndALaterPollAsksAgain() = runBlocking {
        var fail = true
        var calls = 0
        val batcher = TranscriptInsightBatcher({ ids ->
            calls += 1
            if (fail) null else ids.associateWith { view(it, "READY") }
        })
        assertNull(batcher.load("a"))
        fail = false
        assertEquals("READY", batcher.load("a")?.state)
        assertEquals(2, calls)
    }

    @Test
    fun aBatchedItemWithoutItsTextStillOffersTheTranslation() {
        val finished = TranscriptInsightDto(translation = InsightTranslationDto(status = "COMPLETED", text = null))
        assertFalse(TranscriptInsightStates.hasTranslation(finished))
        assertTrue(TranscriptInsightStates.offersTranslation(finished))
        assertFalse(TranscriptInsightStates.offersTranslation(finished.copy(translation = InsightTranslationDto(status = "COMPLETED", text = ""))))
        assertFalse(TranscriptInsightStates.offersTranslation(finished.copy(translation = InsightTranslationDto(status = "NOT_NEEDED"))))
    }
}
