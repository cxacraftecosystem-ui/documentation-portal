package com.fieldrepository.app.explorer.data

import android.content.Context

data class ExplorerSmaller(val bytes: Long, val files: Int, val seconds: Long?, val include: String, val skipped: ExplorerKind)

data class ExplorerPreflightPlan(
    val show: Boolean,
    val metered: Boolean,
    val online: Boolean,
    val bytes: Long,
    val files: Int,
    val seconds: Long?,
    val needed: Long,
    val freeBytes: Long?,
    val freeAfter: Long?,
    val enoughSpace: Boolean,
    val location: String,
    val smaller: ExplorerSmaller?,
    val totalBytes: Long? = null,
)

object ExplorerPreflight {
    const val SHOW_BYTES: Long = 500L * 1024L * 1024L
    const val FALLBACK_WIFI_BPS: Long = 6L * 1024L * 1024L
    const val FALLBACK_MOBILE_BPS: Long = 2L * 1024L * 1024L

    fun of(
        size: ExplorerSize,
        link: ExplorerLink,
        freeBytes: Long?,
        speedBps: Long?,
        location: String,
        parallel: Int = ExplorerEngine.PARALLEL,
        totalBytes: Long? = null,
    ): ExplorerPreflightPlan {
        val speed = speedBps?.takeIf { it > 0 } ?: if (link.metered) FALLBACK_MOBILE_BPS else FALLBACK_WIFI_BPS
        val needed = ExplorerBytes.spaceNeeded(size.bytes, size.largest, parallel)
        val enough = freeBytes == null || size.bytes <= 0 || freeBytes >= needed
        val freeAfter = freeBytes?.let { (it - size.bytes).coerceAtLeast(0) }
        return ExplorerPreflightPlan(
            show = size.bytes > SHOW_BYTES || link.metered || !enough,
            metered = link.metered,
            online = link.online,
            bytes = size.bytes,
            files = size.files,
            seconds = seconds(size.bytes, speed),
            needed = needed,
            freeBytes = freeBytes,
            freeAfter = freeAfter,
            enoughSpace = enough,
            location = location,
            smaller = smaller(size, speed, ExplorerKind.VIDEOS),
            totalBytes = totalBytes?.takeIf { it > 0 },
        )
    }

    fun seconds(bytes: Long, speedBps: Long): Long? = if (bytes <= 0 || speedBps <= 0) null else (bytes + speedBps - 1) / speedBps

    fun smaller(size: ExplorerSize, speedBps: Long, skip: ExplorerKind): ExplorerSmaller? {
        val dropped = size.byKind[skip]?.takeIf { it.files > 0 } ?: return null
        val kept = size.byKind.filter { (kind, stats) -> kind != skip && stats.files > 0 }.keys
        if (kept.isEmpty()) return null
        val bytes = (size.bytes - dropped.bytes).coerceAtLeast(0)
        return ExplorerSmaller(
            bytes = bytes,
            files = (size.files - dropped.files).coerceAtLeast(0),
            seconds = seconds(bytes, speedBps),
            include = include(kept),
            skipped = skip,
        )
    }

    fun include(kinds: Collection<ExplorerKind>): String = ExplorerKind.entries.filter { it in kinds }.joinToString(",") { it.token }
}

object ExplorerSpeedMemory {
    private const val PREFS = "explorer-speed"

    private fun key(metered: Boolean) = if (metered) "mobile" else "wifi"

    fun read(context: Context, metered: Boolean): Long? = runCatching {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(key(metered), 0L).takeIf { it > 0 }
    }.getOrNull()

    fun write(context: Context, metered: Boolean, bps: Long) {
        if (bps <= 0) return
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val was = prefs.getLong(key(metered), 0L)
            val next = if (was > 0) blend(was, bps) else bps
            prefs.edit().putLong(key(metered), next).apply()
        }
    }

    fun blend(was: Long, now: Long): Long = (was * 3 + now * 2) / 5
}
