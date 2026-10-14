package com.lizongying.mytv.playlist

import android.content.Context
import android.util.Log
import com.lizongying.mytv.BuildConfig
import java.io.File
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fetches the channel list from the playlist endpoint.
 *
 * Replaces the channel list that used to be hard-coded in `TVList`.
 */
object PlaylistRepository {

    private const val TAG = "PlaylistRepository"

    /** Channel list endpoint. */
    const val DEFAULT_URL = BuildConfig.SUBSCRIPTION_URL
    const val DEFAULT_EPG_URL = BuildConfig.EPG_URL

    private const val TIMEOUT_CONNECT_SECONDS = 10L
    private const val TIMEOUT_READ_SECONDS = 20L
    private const val ATTEMPTS = 3

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_CONNECT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_READ_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Blocking fetch — call from a background dispatcher.
     *
     * Retries a few times because the endpoint is usually a box on the local
     * network and may briefly refuse connections while it warms up.
     */
    @Throws(IOException::class)
    fun load(url: String = DEFAULT_URL, context: Context? = null, allowCached: Boolean = true): Playlist {
        var lastError: IOException? = null

        repeat(ATTEMPTS) { attempt ->
            try {
                val body = get(url)
                val playlist = M3UParser.parse(body)
                if (playlist.channels.isEmpty()) {
                    throw IOException("playlist contains no channels")
                }
                context?.let { saveCached(it, url, body) }
                return playlist
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "load attempt ${attempt + 1}/$ATTEMPTS failed: ${e.message}")
            }
        }

        if (allowCached && context != null) {
            readCached(context, url)?.let {
                val cached = M3UParser.parse(it)
                if (cached.channels.isNotEmpty()) {
                    Log.w(TAG, "using cached subscription after network failure")
                    return cached
                }
            }
        }
        throw lastError ?: IOException("playlist unavailable")
    }

    /**
     * Fetches the XMLTV guide as text. Returns `null` when it cannot be read —
     * a missing guide only costs the programme titles under the cards, so it
     * must never keep the channel list from loading.
     */
    fun loadEPG(url: String): String? {
        return try {
            get(url)
        } catch (e: IOException) {
            Log.w(TAG, "epg unavailable at $url: ${e.message}")
            null
        }
    }

    private fun cacheFile(context: Context, url: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "subscription-$key.cache")
    }

    fun readCached(context: Context, url: String): String? = runCatching {
        cacheFile(context, url).takeIf { it.exists() }?.readText()
    }.getOrNull()

    fun saveCached(context: Context, url: String, text: String) {
        runCatching {
            val file = cacheFile(context, url)
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(text)
            check(temporary.renameTo(file))
        }.onFailure { Log.w(TAG, "could not save subscription cache", it) }
    }

    @Throws(IOException::class)
    private fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code()}")
            }
            return response.body()?.string() ?: throw IOException("empty response body")
        }
    }
}
