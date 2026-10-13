package com.lizongying.mytv.playlist

/** A source must advance continuously, with spare buffer, before replacing live playback. */
class UpgradeStability(private val durationMs: Long = 5_000) {
    private var startedAt = -1L
    private var startedPosition = 0L
    private var previousPosition = -1L

    fun sample(now: Long, position: Long, playing: Boolean, bufferedMs: Long): Boolean {
        if (!playing || (previousPosition >= 0 && position <= previousPosition)) {
            startedAt = -1L
        } else if (startedAt < 0) {
            startedAt = now
            startedPosition = position
        }
        previousPosition = position
        return startedAt >= 0 && now - startedAt >= durationMs &&
            position - startedPosition >= durationMs * 4 / 5 && bufferedMs >= 2_000
    }
}
