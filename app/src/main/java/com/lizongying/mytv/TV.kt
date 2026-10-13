package com.lizongying.mytv

import com.lizongying.mytv.models.ProgramType
import java.io.Serializable

data class TV(
    var id: Int = 0,
    var title: String,
    var alias: String = "",
    var videoUrl: List<String>,
    var channel: String = "",
    var sourceNames: List<String> = emptyList(),
    var logo: Any = "",
    var pid: String = "",
    var sid: String = "",
    var programType: ProgramType,
    var needToken: Boolean = false,
    var mustToken: Boolean = false,

    /**
     * Container hint for the player. Playlist URLs are frequently extension
     * less (they redirect to a manifest), and ExoPlayer infers the type from
     * the URL suffix — so we have to tell it explicitly.
     */
    var mimeType: String? = null,

    ) : Serializable {

    override fun toString(): String {
        return "TV{" +
                "id=" + id +
                ", title='" + title + '\'' +
                ", videoUrl='" + videoUrl + '\'' +
                ", logo='" + logo + '\'' +
                ", pid='" + pid + '\'' +
                ", sid='" + sid + '\'' +
                ", programType='" + programType + '\'' +
                '}'
    }
}
