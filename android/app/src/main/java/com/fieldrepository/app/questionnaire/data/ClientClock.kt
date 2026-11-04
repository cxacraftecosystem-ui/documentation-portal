package com.fieldrepository.app.questionnaire.data

import com.fieldrepository.app.BuildConfig
import okhttp3.Interceptor
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object ClientClock {
    const val CLIENT_TIME_HEADER = "X-Client-Time"
    const val APP_VERSION_HEADER = "X-App-Version"
    const val APP_VERSION_CODE_HEADER = "X-App-Version-Code"
    const val APP_PLATFORM_HEADER = "X-App-Platform"
    const val PLATFORM = "android"
    private val FRESH_FOR: Duration = Duration.ofDays(7)

    data class Offset(val offsetMs: Long, val measuredAt: Instant)

    @Volatile
    var latest: Offset? = null
        internal set

    fun observe(clientTime: Instant, serverDateHeader: String?, now: Instant = Instant.now()) {
        val server = serverDateHeader?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
        } ?: return
        val raw = server.toEpochMilli() - clientTime.toEpochMilli()
        latest = Offset(if (kotlin.math.abs(raw) < 1_000) 0 else raw, now)
    }

    fun offsetMs(): Long = latest?.offsetMs ?: 0

    fun measuredAt(): String? = latest?.measuredAt?.toString()

    fun captureSource(now: Instant = Instant.now()): String {
        val offset = latest ?: return "DEVICE"
        return if (Duration.between(offset.measuredAt, now) <= FRESH_FOR) "DEVICE" else "UPLOAD_TIME"
    }

    fun interceptor(appVersion: String = BuildConfig.VERSION_NAME, versionCode: Int = BuildConfig.VERSION_CODE): Interceptor = Interceptor { chain ->
        val sentAt = Instant.now()
        val request = chain.request().newBuilder()
            .header(CLIENT_TIME_HEADER, sentAt.toString())
            .header(APP_VERSION_HEADER, appVersion)
            .header(APP_VERSION_CODE_HEADER, versionCode.toString())
            .header(APP_PLATFORM_HEADER, PLATFORM)
            .build()
        val response = chain.proceed(request)
        if (!request.cacheControl.onlyIfCached) observe(sentAt, response.header("Date"))
        response
    }
}
