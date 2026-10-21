package com.fieldrepository.app.data

import com.fieldrepository.app.BuildConfig
import okhttp3.Interceptor
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Clock correction (DESIGN §3.9). Every request carries `X-Client-Time`, `X-App-Version` and
 * `X-App-Version-Code`; every
 * answer's `Date` header gives `clockOffsetMs = serverDate − clientTime`, which the outbox freezes into
 * each clip it queues so the server can correct the capture time.
 *
 * An offset older than 7 days still corrects the time, but the capture is then marked estimated
 * (`UPLOAD_TIME`), as the contract says.
 */
object ClientClock {
    const val CLIENT_TIME_HEADER = "X-Client-Time"
    const val APP_VERSION_HEADER = "X-App-Version"
    /**
     * The build's versionCode, on its own (audit C7, 21 Oct 2026). Version NAMES were reset to 0.0.1
     * while codes keep rising, so the old-build cutoff and the P14 banner compare codes only.
     */
    const val APP_VERSION_CODE_HEADER = "X-App-Version-Code"
    private val FRESH_FOR: Duration = Duration.ofDays(7)

    data class Offset(val offsetMs: Long, val measuredAt: Instant)

    @Volatile
    var latest: Offset? = null
        internal set

    /** Read the offset off one exchange. A missing or unparseable Date changes nothing. */
    fun observe(clientTime: Instant, serverDateHeader: String?, now: Instant = Instant.now()) {
        val server = serverDateHeader?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
        } ?: return
        // HTTP Date has whole seconds; within ±1 s the device clock is treated as right.
        val raw = server.toEpochMilli() - clientTime.toEpochMilli()
        latest = Offset(if (kotlin.math.abs(raw) < 1_000) 0 else raw, now)
    }

    fun offsetMs(): Long = latest?.offsetMs ?: 0

    /** `DEVICE` while the offset is fresh, `UPLOAD_TIME` once it is older than 7 days or never measured. */
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
            .build()
        val response = chain.proceed(request)
        if (!request.cacheControl.onlyIfCached) observe(sentAt, response.header("Date"))
        response
    }
}
