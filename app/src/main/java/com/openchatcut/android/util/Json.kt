package com.openchatcut.android.util

import org.json.JSONArray
import org.json.JSONObject

/** Small helpers around org.json (part of Android), used for project files and Ollama protocol. */
object Json {
    fun obj(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
        pairs.forEach { (k, v) -> if (v != null) put(k, v) }
    }

    fun arr(vararg values: Any?): JSONArray = JSONArray().apply { values.forEach { put(it) } }

    fun parseObject(text: String): JSONObject = JSONObject(text)

    fun optString(o: JSONObject?, key: String, fallback: String = ""): String =
        if (o == null || o.isNull(key)) fallback else o.optString(key, fallback)

    fun optInt(o: JSONObject?, key: String, fallback: Int = 0): Int =
        if (o == null || o.isNull(key)) fallback else o.optInt(key, fallback)

    fun optLong(o: JSONObject?, key: String, fallback: Long = 0L): Long =
        if (o == null || o.isNull(key)) fallback else o.optLong(key, fallback)

    fun optDouble(o: JSONObject?, key: String, fallback: Double = 0.0): Double =
        if (o == null || o.isNull(key)) fallback else o.optDouble(key, fallback)

    fun optBool(o: JSONObject?, key: String, fallback: Boolean = false): Boolean =
        if (o == null || o.isNull(key)) fallback else o.optBoolean(key, fallback)

    fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }

    /** Escape a path for use as a single-quoted ffmpeg filter value. */
    fun ffmpegEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("'", "'\\''")
        .replace(":", "\\:")
        .replace("[", "\\[")
        .replace("]", "\\]")
        .replace(",", "\\,")
}
