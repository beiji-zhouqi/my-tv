package com.lizongying.mytv.models

enum class ProgramType {
    Y_PROTO,
    Y_JCE,
    F,

    /** Channel from the M3U playlist — the URL plays as-is, no token exchange. */
    M3U,
}
