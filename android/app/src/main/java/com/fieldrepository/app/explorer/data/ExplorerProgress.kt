package com.fieldrepository.app.explorer.data

class ExplorerRate(private val windowMs: Long = WINDOW_MS) {
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var smoothed: Double? = null

    @Synchronized
    fun sample(now: Long, bytes: Long): Long? {
        if (samples.isNotEmpty() && bytes < samples.last().second) {
            samples.clear()
            smoothed = null
        }
        samples.addLast(now to bytes)
        while (samples.size > 2 && now - samples.first().first > windowMs) samples.removeFirst()
        val first = samples.first()
        val span = now - first.first
        if (span < MIN_SPAN_MS) return smoothed?.toLong()
        val rate = (bytes - first.second).toDouble() * 1000.0 / span
        smoothed = smoothed?.let { it + ALPHA * (rate - it) } ?: rate
        return smoothed?.toLong()?.coerceAtLeast(0)
    }

    @Synchronized
    fun reset() {
        samples.clear()
        smoothed = null
    }

    companion object {
        const val WINDOW_MS = 5_000L
        const val MIN_SPAN_MS = 800L
        const val ALPHA = 0.35
    }
}

object ExplorerProgress {
    const val RECENT = 6

    fun secondsLeft(job: ExplorerJob, now: Long = System.currentTimeMillis()): Long? {
        if (job.totalBytes <= 0 || job.doneBytes >= job.totalBytes) return null
        val left = job.totalBytes - job.doneBytes
        val speed = job.speedBps
        if (speed != null && speed > 0) return left / speed
        val started = job.startedAt ?: return null
        val elapsed = now - started
        if (job.doneBytes <= 0 || elapsed < 3_000) return null
        val rate = job.doneBytes.toDouble() / elapsed
        return (left / rate / 1000.0).toLong()
    }

    fun running(recent: List<ExplorerRecent>, name: String, sizeBytes: Long?, fraction: Float?): List<ExplorerRecent> {
        val leaf = leaf(name)
        val without = recent.filterNot { it.name == leaf && (it.status == ExplorerRecentStatus.RUNNING || it.status == ExplorerRecentStatus.RETRYING) }
        val wasRetry = recent.any { it.name == leaf && it.status == ExplorerRecentStatus.RETRYING }
        val status = if (wasRetry && fraction == null) ExplorerRecentStatus.RETRYING else ExplorerRecentStatus.RUNNING
        return (listOf(ExplorerRecent(leaf, sizeBytes, status, fraction)) + without).take(RECENT)
    }

    fun finished(recent: List<ExplorerRecent>, name: String, sizeBytes: Long?, status: ExplorerRecentStatus): List<ExplorerRecent> {
        val leaf = leaf(name)
        val without = recent.filterNot { it.name == leaf }
        return (listOf(ExplorerRecent(leaf, sizeBytes, status, if (status == ExplorerRecentStatus.SAVED) 1f else null)) + without).take(RECENT)
    }

    fun leaf(path: String): String = path.substringAfterLast('/').ifBlank { path }

    fun percent(job: ExplorerJob): Int? = when {
        job.totalBytes > 0 -> (job.doneBytes.coerceAtLeast(0) * 100 / job.totalBytes).toInt().coerceIn(0, 100)
        job.totalFiles > 0 -> (job.doneFiles.coerceAtLeast(0) * 100 / job.totalFiles).coerceIn(0, 100)
        else -> null
    }
}
