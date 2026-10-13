package com.lizongying.mytv.playlist

import android.util.Log

/**
 * One channel entry from an M3U playlist.
 */
data class M3UChannel(
    val title: String,
    val url: String,
    val group: String,
    val logo: String,
    val tvgId: String,
    val sourceName: String = "",
)

/**
 * A parsed playlist: the channels plus the EPG guide it points at.
 */
data class Playlist(
    val channels: List<M3UChannel>,
    val epgUrl: String?,
)

/**
 * Parser for the extended M3U format used by IPTV playlists.
 *
 * The format pairs a metadata line with the following URL line:
 * ```
 * #EXTM3U x-tvg-url="http://host/playback.xml"
 * #EXTINF:-1 tvg-id="..." tvg-logo="..." group-title="体育",CCTV5体育
 * http://host/iptvadmin/641886683
 * ```
 */
object M3UParser {

    private const val TAG = "M3UParser"

    private const val DEFAULT_GROUP = "其他"

    /** Matches `key="value"` pairs inside a tag line. */
    private val ATTRIBUTE = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")

    fun parse(text: String): Playlist {
        val channels = mutableListOf<M3UChannel>()
        var epgUrl: String? = null

        var attributes: Map<String, String>? = null
        var title = ""
        var group = ""

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit

                // The header carries the guide URL, so it cannot be skipped.
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    ATTRIBUTE.findAll(line).forEach { match ->
                        if (match.groupValues[1] == "x-tvg-url") {
                            epgUrl = match.groupValues[2]
                        }
                    }
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = HashMap<String, String>()
                    ATTRIBUTE.findAll(line).forEach { attrs[it.groupValues[1]] = it.groupValues[2] }
                    attributes = attrs
                    group = attrs["group-title"].orEmpty()
                    title = displayName(line).ifEmpty { attrs["tvg-name"].orEmpty() }
                }

                // #EXTGRP, #EXTVLCOPT and friends carry no URL of their own.
                line.startsWith("#") -> Unit

                else -> {
                    val attrs = attributes
                    if (attrs != null && title.isNotEmpty()) {
                        channels += M3UChannel(
                            title = title,
                            url = line,
                            group = group.ifEmpty { DEFAULT_GROUP },
                            logo = attrs["tvg-logo"].orEmpty(),
                            tvgId = attrs["tvg-id"].orEmpty(),
                            sourceName = attrs["source-name"] ?: attrs["tvg-source"].orEmpty(),
                        )
                    }
                    attributes = null
                }
            }
        }

        Log.i(TAG, "parsed ${channels.size} channels, epg=$epgUrl")
        return Playlist(channels, epgUrl)
    }

    /**
     * The display name is everything after the first comma that is not inside a
     * quoted attribute — titles are free to contain commas of their own.
     */
    private fun displayName(line: String): String {
        var inQuotes = false
        for (index in line.indices) {
            when (line[index]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return line.substring(index + 1).trim()
            }
        }
        return ""
    }
}
