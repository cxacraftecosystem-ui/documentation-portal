package com.fieldrepository.app.data

import com.fieldrepository.app.questionnaire.QFeature
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit C7 (21 Oct 2026): version names were reset to 0.0.1 while versionCodes keep rising, so the
 * old-build cutoff and the P14 banner read the versionCode, sent in its own header.
 */
class AppVersionCodeTest {
    @Test fun `every request carries the versionCode in its own header`() {
        var seen: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(ClientClock.interceptor(appVersion = "0.0.1", versionCode = 27))
            .addInterceptor { chain ->
                seen = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("{}".toResponseBody()).build()
            }
            .build()
        client.newCall(Request.Builder().url("http://localhost/api/questionnaire/config").build()).execute().close()
        assertEquals("27", seen!!.header(ClientClock.APP_VERSION_CODE_HEADER))
        assertEquals("X-App-Version-Code", ClientClock.APP_VERSION_CODE_HEADER)
        assertEquals("0.0.1", seen!!.header(ClientClock.APP_VERSION_HEADER))
    }

    @QFeature("B35")
    @Test fun `the P14 banner compares versionCodes, never names`() {
        // A name minimum that once stopped old builds no longer shows the banner on a new build.
        assertFalse(QConfig(minAppVersionForCreate = "0.0.22").updateRequired(versionCode = 27))
        assertTrue(QConfig(minAndroidVersionCodeForCreate = 28).updateRequired(versionCode = 27))
        assertFalse(QConfig(minAndroidVersionCodeForCreate = 27).updateRequired(versionCode = 27))
        // A bare-number legacy minimum is a versionCode.
        assertTrue(QConfig(minAppVersionForCreate = "30").updateRequired(versionCode = 27))
        assertFalse(QConfig().updateRequired(versionCode = 1))
    }
}
