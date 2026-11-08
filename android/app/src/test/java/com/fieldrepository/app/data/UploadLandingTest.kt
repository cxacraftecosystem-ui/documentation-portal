package com.fieldrepository.app.data

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class UploadLandingTest {
    private fun refusal(code: Int): HttpException =
        HttpException(
            Response.error<Any>(
                code,
                "{\"detail\":\"The file didn't reach storage. Upload it again.\"}"
                    .toResponseBody("application/json".toMediaTypeOrNull())
            )
        )

    @Test
    fun aCompletionThatFindsNoFileInStorageAsksForTheUploadAgain() {
        assertTrue(refusal(409).uploadNeverLanded())
    }

    @Test
    fun otherRefusalsAreNotRetriedAsAMissingFile() {
        listOf(400, 403, 404, 413, 422, 500, 503).forEach { code ->
            assertFalse("$code", refusal(code).uploadNeverLanded())
        }
    }
}
