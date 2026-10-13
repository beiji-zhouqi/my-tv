package com.lizongying.mytv

import android.content.Context
import android.util.Log
import com.lizongying.mytv.models.EPG
import com.lizongying.mytv.models.ProgramType
import com.lizongying.mytv.playlist.SourceLabel
import com.lizongying.mytv.playlist.ChannelIdentity
import com.lizongying.mytv.playlist.EPGParser
import com.lizongying.mytv.playlist.M3UChannel
import com.lizongying.mytv.playlist.PlaylistRepository

/**
 * The channel list, grouped by `group-title` for the Leanback rows.
 *
 * The list used to be hard-coded in this file; it is now loaded from the
 * playlist endpoint — see [PlaylistRepository].
 */
object TVList {

    private const val TAG = "TVList"

    private const val MIME_HLS = "application/x-mpegURL"

    @Volatile
    var list: Map<String, List<TV>> = emptyMap()
        private set

    /** Programme guide, keyed by `tvg-id`. Empty until [loadEPG] succeeds. */
    @Volatile
    var epg: Map<String, List<EPG>> = emptyMap()
        private set

    /** Guide address advertised by the playlist header (`x-tvg-url`). */
    @Volatile
    private var epgUrl: String? = null

    /**
     * Fetches the playlist and rebuilds the channel list.
     *
     * Blocking — call from a background dispatcher.
     */
    data class Snapshot(val groups: Map<String, List<TV>>, val guideUrl: String)

    fun fetch(context: Context, allowCached: Boolean = false): Snapshot {
        val playlist = PlaylistRepository.load(context = context, allowCached = allowCached)
        return Snapshot(build(playlist.channels), playlist.epgUrl ?: PlaylistRepository.DEFAULT_EPG_URL)
    }

    fun apply(snapshot: Snapshot) {
        list = snapshot.groups
        epgUrl = snapshot.guideUrl
        Log.i(TAG, "loaded ${list.values.flatten().distinctBy { it.id }.size} channels in ${list.size} groups")
    }

    var loadedFromCache = false
        private set

    fun load(context: Context): Map<String, List<TV>> {
        val cached = PlaylistRepository.readCached(context, PlaylistRepository.DEFAULT_URL)
            ?.let(com.lizongying.mytv.playlist.M3UParser::parse)
        loadedFromCache = cached?.channels?.isNotEmpty() == true
        if (loadedFromCache && cached != null) {
            apply(Snapshot(build(cached.channels), cached.epgUrl ?: PlaylistRepository.DEFAULT_EPG_URL))
        } else apply(fetch(context, allowCached = true))
        return list
    }

    /**
     * Fetches and parses the programme guide.
     *
     * Blocking, and deliberately separate from [load]: the guide is megabytes
     * of XML, so channels should be on screen long before it arrives.
     * Returns `false` when there is no guide to be had — not an error.
     */
    fun loadEPG(context: Context): Boolean {
        val url = epgUrl ?: PlaylistRepository.DEFAULT_EPG_URL
        val text = PlaylistRepository.loadEPG(url)
        val loaded = text?.let(EPGParser::parse).orEmpty()
        if (loaded.isNotEmpty()) {
            epg = loaded
            PlaylistRepository.saveCached(context, url, text!!)
            return true
        }
        if (epg.isEmpty()) {
            val cached = PlaylistRepository.readCached(context, url)?.let(EPGParser::parse).orEmpty()
            if (cached.isNotEmpty()) epg = cached
        }
        return false
    }

    /** Guide entries for a channel, looked up by `tvg-id` then by title. */
    fun epgFor(tv: TV): List<EPG> {
        return epg[tv.alias] ?: epg[tv.title] ?: emptyList()
    }

    internal fun build(channels: List<M3UChannel>): Map<String, List<TV>> {
        val groups = LinkedHashMap<String, MutableList<TV>>()
        val sourcesByName = channels.groupBy { ChannelIdentity.key(it.title) }
        var id = 0

        val channelsByName = sourcesByName.mapValues { (_, sources) ->
            val channel = sources.first()
            // An empty pid is what makes the player treat this as a direct URL
            // channel; no Yangshipin token exchange happens for these.
            TV(
                id = id++,
                title = channel.title,
                alias = channel.tvgId,
                videoUrl = sources.map { it.url }.distinct(),
                sourceNames = sources.distinctBy { it.url }.map(SourceLabel::name),
                channel = channel.group,
                logo = channel.logo,
                pid = "",
                sid = "",
                programType = ProgramType.M3U,
                needToken = false,
                mustToken = false,
                mimeType = mimeTypeOf(channel.url),
            )
        }

        // Preserve every original category while sharing one channel and its sources.
        val membersByGroup = mutableMapOf<String, MutableSet<String>>()
        channels.forEach { source ->
            val name = ChannelIdentity.key(source.title)
            val group = groups.getOrPut(source.group) { mutableListOf() }
            if (membersByGroup.getOrPut(source.group) { mutableSetOf() }.add(name)) {
                group += channelsByName.getValue(name)
            }
        }

        // IDs index unique view models; category aliases reference the same TV object.
        groups.values.flatten().distinctBy { it.id }
            .forEachIndexed { position, tv -> tv.id = position }
        return groups
    }

    /**
     * Playlist entries point either at a manifest (`.m3u8`) or at an opaque
     * redirect endpoint with no file extension. Both are HLS, but only the
     * first is recognisable to ExoPlayer; anything else is left `null` so the
     * player can still infer it from a real file suffix.
     */
    fun mimeTypeOf(url: String): String? {
        if (url.startsWith("rtsp://", ignoreCase = true)) return null
        if (url.contains("/udp/")) return "video/mp2t"
        val path = url.substringBefore('?').substringBefore('#')
        val lastSegment = path.substringAfterLast('/')
        if (!lastSegment.contains('.')) {
            return MIME_HLS
        }
        return when (lastSegment.substringAfterLast('.').lowercase()) {
            "m3u8", "m3u" -> MIME_HLS
            else -> null
        }
    }
}
