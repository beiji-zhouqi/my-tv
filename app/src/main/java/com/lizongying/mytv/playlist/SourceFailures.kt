package com.lizongying.mytv.playlist

/** At most three failed foreground attempts per round; never cross into another channel. */
internal class SourceFailures {
    private val playback = mutableSetOf<Int>()
    fun clear() { playback.clear() }
    fun playbackFailed(source: Int) { playback.add(source) }
    fun retryDelayMs(): Long = (3_000L * playback.size).coerceAtMost(9_000L)
    fun nextPlayback(order: List<Int>, sourceCount: Int,
                     eligible: Set<Int> = (0 until sourceCount).toSet()): Int? =
        if (playback.size >= 3) null else
            (order + (0 until sourceCount)).distinct()
                .firstOrNull { it in 0 until sourceCount && it in eligible && it !in playback }
}
