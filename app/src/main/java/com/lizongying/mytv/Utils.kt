package com.lizongying.mytv

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Utils {

    fun getDateFormat(format: String): String {
        return SimpleDateFormat(format, Locale.CHINA).format(Date())
    }

    fun getDateTimestamp(): Long {
        return System.currentTimeMillis() / 1000
    }

    fun isTmallDevice() = android.os.Build.MANUFACTURER.equals("Tmall", ignoreCase = true)
}
