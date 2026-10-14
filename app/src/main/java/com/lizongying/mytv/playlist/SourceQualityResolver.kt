package com.lizongying.mytv.playlist

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/** Passive history only: this class never creates a player or requests a source URL. */
object SourceQualityResolver {
    data class Quality(
        val pixels: Long = 0,
        val bitrate: Int = 0,
        val failed: Boolean = false,
        val loadMs: Long = Long.MAX_VALUE,
        val width: Int = 0,
        val height: Int = 0,
        val playbackFailed: Boolean = false,
    )
    data class Ranking(val order: List<Int>, val qualities: List<Quality>)
    private data class Entry(val checkedAt: Long, val quality: Quality)
    private val cache = mutableMapOf<String, Entry>()
    private const val FAILURE_MS = 10 * 60_000L

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences("source_playback_history_v3", Context.MODE_PRIVATE)

    private fun key(url: String) = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun entry(context: Context, url: String): Entry? = synchronized(cache) {
        cache[url] ?: runCatching {
            val json = JSONObject(preferences(context).getString(key(url), null) ?: return@runCatching null)
            // Codec capabilities can change after a system update.
            if (json.getString("device") != android.os.Build.FINGERPRINT) return@runCatching null
            Entry(json.getLong("time"), Quality(json.getLong("pixels"),
                json.getInt("bitrate"), json.getBoolean("failed"), json.getLong("loadMs"),
                json.optInt("width"), json.optInt("height"), json.optBoolean("playbackFailed")))
        }.getOrNull()?.also { cache[url] = it }
    }

    private fun save(context: Context, url: String, quality: Quality) {
        val entry = Entry(System.currentTimeMillis(), quality)
        synchronized(cache) { cache[url] = entry }
        val json = JSONObject().put("time", entry.checkedAt).put("pixels", quality.pixels)
            .put("bitrate", quality.bitrate).put("failed", quality.failed)
            .put("loadMs", quality.loadMs).put("width", quality.width).put("height", quality.height)
            .put("playbackFailed", quality.playbackFailed).put("device", android.os.Build.FINGERPRINT)
        preferences(context).edit().putString(key(url), json.toString()).apply()
    }

    fun markFailed(context: Context, url: String, reason: String) {
        android.util.Log.w("SourceQualityResolver", "foreground playback failed: $reason")
        save(context, url, (entry(context, url)?.quality ?: Quality()).copy(failed = true, playbackFailed = true))
    }

    internal fun recoveredQuality(measured: Quality, previous: Quality?): Quality = measured.copy(
        failed = false, playbackFailed = false, loadMs = measured.loadMs.takeIf { it != Long.MAX_VALUE } ?: previous?.loadMs ?: Long.MAX_VALUE,
    )

    fun markPlaying(context: Context, url: String, measured: Quality) {
        if (measured.width <= 0 || measured.height <= 0) return
        save(context, url, recoveredQuality(measured, entry(context, url)?.quality))
    }

    internal fun ranking(qualities: List<Quality>): Ranking = Ranking(
        qualities.indices.sortedWith(compareBy<Int> { qualities[it].failed }
            .thenByDescending { qualities[it].pixels }
            .thenBy { qualities[it].loadMs }.thenBy { it }), qualities)

    /** Preserve historical quality order within each provider preference. */
    internal fun preferYangshipin(ranking: Ranking, names: List<String>): Ranking = ranking.copy(
        order = ranking.order.sortedBy { index ->
            if (names.getOrNull(index)?.contains("央视频") == true) 0 else 1
        },
    )

    internal fun historicalQuality(quality: Quality, ageMs: Long): Quality =
        quality.copy(failed = quality.failed && ageMs >= 0 && ageMs < FAILURE_MS,
            playbackFailed = quality.playbackFailed && ageMs >= 0 && ageMs < FAILURE_MS)

    /** Historical resolution remains useful; a failure suppresses automatic retries for ten minutes. */
    fun cachedRanking(context: Context, urls: List<String>): Ranking = ranking(urls.map { url ->
        entry(context, url)?.let { historicalQuality(it.quality, System.currentTimeMillis() - it.checkedAt) }
            ?: Quality()
    })

    fun isCoolingDown(context: Context, url: String): Boolean = entry(context, url)?.let {
        historicalQuality(it.quality, System.currentTimeMillis() - it.checkedAt).failed
    } ?: false
}
