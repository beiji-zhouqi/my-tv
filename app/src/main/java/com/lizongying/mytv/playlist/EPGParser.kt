package com.lizongying.mytv.playlist

import android.util.Log
import com.lizongying.mytv.models.EPG
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.TimeZone

/**
 * Parser for the XMLTV guide the playlist points at (`x-tvg-url`).
 *
 * Only the pieces the UI shows are read: the latest programme title still
 * on air for each channel.
 */
object EPGParser {

    private const val TAG = "EPGParser"

    /**
     * XMLTV timestamps look like `20261012013000 +0800`, and the seconds field
     * is commonly written as `013000` rather than `0130`.
     */
    private val TIMESTAMP = Regex("""(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*([+-]\d{4})?""")

    fun parse(text: String): Map<String, MutableList<EPG>> {
        val result = HashMap<String, MutableList<EPG>>()
        val channelNames = HashMap<String, String>()

        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(text))

            var channelId: String? = null
            var title: String? = null
            var start: Int? = null
            var stop: Int? = null
            var guideChannelId: String? = null

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "channel" -> guideChannelId = parser.getAttributeValue(null, "id")
                        "display-name" -> {
                            val name = parser.nextText().trim()
                            guideChannelId?.let { channelNames[name] = it }
                        }
                        "programme" -> {
                            channelId = parser.getAttributeValue(null, "channel")
                            start = parseTimestamp(parser.getAttributeValue(null, "start"))
                            stop = parseTimestamp(parser.getAttributeValue(null, "stop"))
                            title = null
                        }

                        "title" -> title = parser.nextText()
                    }

                    XmlPullParser.END_TAG -> if (parser.name == "programme") {
                        val id = channelId
                        val name = title
                        val begin = start
                        if (id != null && !name.isNullOrEmpty() && begin != null) {
                            result.getOrPut(id) { mutableListOf() } += EPG(name, begin, stop)
                        }
                        channelId = null
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "parse failed", e)
            return emptyMap()
        }

        result.values.forEach { it.sortBy { epg -> epg.beginTime } }
        channelNames.forEach { (name, id) ->
            result[id]?.let { result.putIfAbsent(name, it) }
        }
        Log.i(TAG, "parsed ${result.size} channels of EPG")
        return result
    }

    private fun parseTimestamp(value: String?): Int? {
        if (value == null) return null
        val match = TIMESTAMP.find(value.trim()) ?: return null
        val (year, month, day, hour, minute, second, zone) = match.destructured

        val calendar = java.util.Calendar.getInstance(
            zone.takeIf { it.isNotEmpty() }
                ?.let { TimeZone.getTimeZone("GMT$it") }
                ?: TimeZone.getTimeZone("GMT+0800")
        )
        calendar.clear()
        calendar.set(
            year.toInt(),
            month.toInt() - 1,
            day.toInt(),
            hour.toInt(),
            minute.toInt(),
            if (second.isEmpty()) 0 else second.toInt(),
        )
        return (calendar.timeInMillis / 1000).toInt()
    }
}
