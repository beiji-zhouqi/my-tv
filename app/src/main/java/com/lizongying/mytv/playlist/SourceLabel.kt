package com.lizongying.mytv.playlist

import java.net.URI

/** Use subscription metadata; an opaque relay URL cannot identify its upstream provider. */
object SourceLabel {
    private val providers = listOf("央视频", "咪咕", "电信", "联通", "移动")

    fun name(source: M3UChannel): String {
        if (source.sourceName.isNotBlank()) return source.sourceName.trim()
        providers.firstOrNull { source.title.contains(it) || source.group.contains(it) }
            ?.let { return it }
        val host = runCatching { URI(source.url).host }.getOrNull().orEmpty()
        return when {
            host.contains("yangshipin") || host.contains("cctv") -> "央视频"
            host.contains("migu") -> "咪咕"
            source.group.isNotBlank() -> source.group
            host.isNotBlank() -> host
            else -> "直播源"
        }
    }
}
