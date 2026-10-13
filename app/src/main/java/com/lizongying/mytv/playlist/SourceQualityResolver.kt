package com.lizongying.mytv.playlist

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.lizongying.mytv.TVList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.coroutines.resume

/** Only a decoded first frame counts as an available source on this device. */
@OptIn(UnstableApi::class)
object SourceQualityResolver {
    data class Quality(
        val pixels: Long = 0,
        val bitrate: Int = 0,
        val failed: Boolean = false,
        val loadMs: Long = Long.MAX_VALUE,
        val width: Int = 0,
        val height: Int = 0,
    )
    data class Ranking(val order: List<Int>, val qualities: List<Quality>)
    private data class Entry(val checkedAt: Long, val quality: Quality)
    private val cache = mutableMapOf<String, Entry>()
    private val probes = Semaphore(1)
    private const val REFRESH_MS = 30 * 60_000L
    private const val FAILURE_MS = 2 * 60_000L

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences("source_quality_v2", Context.MODE_PRIVATE)

    private fun key(url: String) = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun entry(context: Context, url: String): Entry? = synchronized(cache) {
        cache[url] ?: runCatching {
            val json = JSONObject(preferences(context).getString(key(url), null) ?: return@runCatching null)
            // Codec capabilities can change after a system update.
            if (json.getString("device") != android.os.Build.FINGERPRINT) return@runCatching null
            Entry(json.getLong("time"), Quality(json.getLong("pixels"),
                json.getInt("bitrate"), json.getBoolean("failed"), json.getLong("loadMs"),
                json.optInt("width"), json.optInt("height")))
        }.getOrNull()?.also { cache[url] = it }
    }

    private fun fresh(entry: Entry): Boolean {
        val age = System.currentTimeMillis() - entry.checkedAt
        return age >= 0 && age < if (entry.quality.failed) FAILURE_MS else REFRESH_MS
    }

    private fun save(context: Context, url: String, quality: Quality) {
        val entry = Entry(System.currentTimeMillis(), quality)
        synchronized(cache) { cache[url] = entry }
        val json = JSONObject().put("time", entry.checkedAt).put("pixels", quality.pixels)
            .put("bitrate", quality.bitrate).put("failed", quality.failed)
            .put("loadMs", quality.loadMs).put("width", quality.width).put("height", quality.height)
            .put("device", android.os.Build.FINGERPRINT)
        preferences(context).edit().putString(key(url), json.toString()).apply()
    }

    fun markFailed(context: Context, url: String) = save(context, url, Quality(failed = true))

    internal fun ranking(qualities: List<Quality>): Ranking = Ranking(
        qualities.indices.sortedWith(compareBy<Int> { qualities[it].failed }
            .thenByDescending { qualities[it].pixels }
            .thenBy { qualities[it].loadMs }.thenBy { it }), qualities)

    /** Read only: channel selection never waits for a network probe. */
    fun cachedRanking(context: Context, urls: List<String>): Ranking = ranking(urls.map {
        entry(context, it)?.takeIf(::fresh)?.quality ?: Quality()
    })

    suspend fun rank(context: Context, urls: List<String>): Ranking {
        val qualities = urls.map { url ->
            probes.withPermit {
                entry(context, url)?.takeIf { fresh(it) && (it.quality.failed ||
                    (it.quality.width > 0 && it.quality.height > 0)) }?.quality ?: run {
                    val quality = withTimeoutOrNull(8_000) { probe(context, url) }
                        ?: Quality(failed = true)
                    save(context, url, quality)
                    quality
                }
            }
        }
        return ranking(qualities)
    }

    /** Probe one source at a time and yield between channels to limit resource usage. */
    suspend fun refresh(
        context: Context, channels: List<List<String>>,
        onRanking: (List<String>, Ranking) -> Unit,
    ) {
        while (true) {
            channels.filter { it.size > 1 }.distinct().forEach {
                onRanking(it, rank(context, it))
                delay(1_000)
            }
            delay(FAILURE_MS)
        }
    }

    private suspend fun probe(context: Context, url: String): Quality =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                var player: ExoPlayer? = null
                var texture: SurfaceTexture? = null
                var surface: Surface? = null
                var released = false
                fun release() {
                    if (released) return
                    released = true
                    player?.release()
                    surface?.release()
                    texture?.release()
                }
                fun finish(result: Quality) {
                    if (!continuation.isActive) return
                    release()
                    continuation.resume(result)
                }
                continuation.invokeOnCancellation {
                    if (Looper.myLooper() == Looper.getMainLooper()) release()
                    else Handler(Looper.getMainLooper()).post { release() }
                }
                try {
                    val started = SystemClock.elapsedRealtime()
                    val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context)
                    selector.setParameters(selector.buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .setExceedRendererCapabilitiesIfNecessary(false)
                        .setExceedVideoConstraintsIfNecessary(false)
                        .setForceHighestSupportedBitrate(true)
                        .setViewportSize(Int.MAX_VALUE, Int.MAX_VALUE, true))
                    val probe = ExoPlayer.Builder(context.applicationContext).setTrackSelector(selector).build()
                    player = probe
                    texture = SurfaceTexture(false)
                    surface = Surface(texture)
                    probe.setVideoSurface(surface)
                    probe.volume = 0f
                    probe.addListener(object : Player.Listener {
                        override fun onTracksChanged(tracks: Tracks) {
                            val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                            if (video.isNotEmpty() && video.none { group ->
                                    (0 until group.length).any { group.isTrackSelected(it) &&
                                        group.getTrackSupport(it) == C.FORMAT_HANDLED }
                                }) finish(Quality(failed = true))
                        }
                        override fun onRenderedFirstFrame() {
                            val format = probe.videoFormat ?: return
                            val pixels = format.width.coerceAtLeast(0).toLong() * format.height.coerceAtLeast(0)
                            finish(Quality(pixels, format.bitrate.coerceAtLeast(0), pixels == 0L,
                                SystemClock.elapsedRealtime() - started, format.width, format.height))
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            finish(Quality(failed = true))
                        }
                    })
                    val item = MediaItem.Builder().setUri(url)
                    TVList.mimeTypeOf(url)?.let { item.setMimeType(it) }
                    probe.setMediaItem(item.build())
                    probe.prepare()
                    probe.play()
                } catch (_: Exception) {
                    // Unsupported RTSP/DASH modules can throw synchronously at setMediaItem.
                    finish(Quality(failed = true))
                }
            }
        }
}
