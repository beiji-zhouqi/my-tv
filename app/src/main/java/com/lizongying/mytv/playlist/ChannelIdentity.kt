package com.lizongying.mytv.playlist

/** Match provider/quality labels without merging different regional or plus channels. */
object ChannelIdentity {
    fun key(title: String): String {
        var name = title.trim().uppercase()
            .replace(Regex("\\s+"), "")
        if (name == "CCTV4K" || name == "CCTV8K") return name
        repeat(3) {
            name = name.replace(Regex("[（(\\[](央视频|咪咕|电信|联通|移动)[）)\\]]"), "")
                .replace(Regex("[-_·]?(央视频|咪咕|电信|联通|移动)$"), "")
                .replace(Regex("^(央视频|咪咕)[-_·:]?"), "")
                .replace(Regex("[-_·]?(超高清|高清|标清|蓝光|HD|SD|4K|8K)$"), "")
        }
        name = name.replace("央视", "CCTV")
        val cctv = Regex("^CCTV-?(\\d+)(\\+|PLUS)?(综合|财经|综艺|中文国际|体育赛事|体育|电影|国防军事|军事农业|电视剧|纪录|科教|戏曲|社会与法|新闻|少儿|音乐|奥林匹克)?$").matchEntire(name)
        return if (cctv != null) "CCTV" + cctv.groupValues[1] +
            (if (cctv.groupValues[2].isNotEmpty()) "+" else "") else name
    }
}
