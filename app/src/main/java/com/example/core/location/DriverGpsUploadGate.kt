package com.example.core.location

/** One upload stream for GPS and network fixes, timed with a monotonic clock. */
class DriverGpsUploadGate(private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L }) {
    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 5
        const val SETTINGS_REFRESH_MILLIS = 30_000L
        fun validInterval(seconds: Int?): Boolean = seconds != null && seconds in 1..60
    }

    var intervalSeconds: Int = DEFAULT_INTERVAL_SECONDS
        private set
    private var lastStartedAt: Long? = null
    private var activeUpload: Long? = null
    private var sequence = 0L

    @Synchronized fun updateInterval(seconds: Int?): Boolean {
        if (!validInterval(seconds)) return false
        intervalSeconds = requireNotNull(seconds)
        return true
    }

    /** Claim before launching; duplicate providers and slow requests never queue stale fixes. */
    @Synchronized fun tryBeginUpload(): Long? {
        if (activeUpload != null) return null
        val now = elapsedMillis()
        if (lastStartedAt?.let { now - it < intervalSeconds * 1_000L } == true) return null
        lastStartedAt = now
        return (++sequence).also { activeUpload = it }
    }

    @Synchronized fun finishUpload(claim: Long) {
        if (activeUpload == claim) activeUpload = null
    }

    @Synchronized fun reset() {
        intervalSeconds = DEFAULT_INTERVAL_SECONDS
        lastStartedAt = null
        activeUpload = null
    }
}
