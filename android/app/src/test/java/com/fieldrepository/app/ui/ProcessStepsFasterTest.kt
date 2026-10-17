package com.fieldrepository.app.ui

import com.fieldrepository.app.data.ProcessCreateRequest
import com.fieldrepository.app.data.ProcessStepRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C22: steps from photos, a template from the craft's last process, and unnamed steps flagged. */
class ProcessStepsFasterTest {

    private val main = repoSource(
        "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt",
        "src/main/java/com/fieldrepository/app/MainActivity.kt",
    )

    @Test
    fun `a step is no longer refused for having no name, and says so on its card`() {
        val form = main.substringAfter("private fun ProcessForm(").substringBefore("private val viewDataModes")
        assertFalse("no step-name requirement in submit", form.contains("step.nameError = REQUIRED_FIELD_SENTENCE"))
        assertTrue(form.contains("Unnamed step. Name it now or later."))
        assertFalse(form.contains("RequiredInput(\"Name of the step\""))
    }

    @Test
    fun `the three faster ways in are offered`() {
        assertTrue(main.contains("Text(\"Steps from photos\")"))
        assertTrue(main.contains("Text(\"Photograph steps\")"))
        assertTrue(main.contains("Start from this craft's last process"))
        // Names only: the copied steps carry no media of the other process.
        assertTrue(main.contains("ProcessStepUi(java.util.UUID.randomUUID().toString(), null, source.name, source.stepType)"))
    }

    @Test
    fun `an unnamed step travels as an empty name, which the server now accepts`() {
        val body = ProcessCreateRequest(
            name = "Block printing a scarf",
            productId = "p1",
            steps = listOf(ProcessStepRequest(name = "", stepType = "SEQUENTIAL", sortOrder = 1))
        )
        assertTrue(Json { encodeDefaults = true }.encodeToString(body).contains("\"name\":\"\""))
    }

    @Test
    fun `the craft's processes are asked of the server by craft`() {
        val api = repoSource(
            "android/app/src/main/java/com/fieldrepository/app/data/FieldRepositoryApi.kt",
            "src/main/java/com/fieldrepository/app/data/FieldRepositoryApi.kt",
        )
        assertTrue(api.contains("@Query(\"craftId\") craftId: String? = null\n    ): PageResponse<ProcessDetailDto>"))
    }

    @Test
    fun `a photo the media store cannot date falls back to the provider's modified time`() {
        assertEquals(1_000L, captureTimeMillis(1_000L, 2_000L, 3L))
        assertEquals("a document provider's last-modified", 2_000L, captureTimeMillis(null, 2_000L, 3L))
        assertEquals("DATE_MODIFIED is seconds", 3_000L, captureTimeMillis(null, null, 3L))
        assertEquals(2_000L, captureTimeMillis(0L, 2_000L, null))
        assertNull(captureTimeMillis(null, 0L, null))
    }

    @Test
    fun `photos sort oldest first and an undated one keeps its tap place after the dated`() {
        assertEquals(listOf(2, 0, 3, 1), captureOrder(listOf(5_000L, null, 1_000L, 9_000L)))
        assertEquals("all undated keeps tap order", listOf(0, 1, 2), captureOrder(listOf(null, null, null)))
        assertTrue(main.contains("orderByCaptureTime(context, uris)"))
    }
}
