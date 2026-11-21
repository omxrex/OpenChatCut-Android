package com.openchatcut.android.model

import com.openchatcut.android.util.Json
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Ported domain model from upstream OpenChatCut.
 * Source: upstream/src/editor/{timelineTypes,clipTypes,projectTypes,mediaTypes,transitionTypes}.ts
 * Field names and units (frames, srcInFrame, durationInFrames) are kept identical so
 * tool instructions and algorithms from the upstream project remain valid here.
 */

enum class MediaKind { VIDEO, AUDIO, IMAGE }

data class MediaAsset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val path: String,
    val kind: MediaKind,
    val durationMs: Long,
    val durationInFrames: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Double = 0.0,
    val sizeBytes: Long = 0L,
    val sourceFilename: String = "",
    val thumbnailPath: String = "",
    val hasAudio: Boolean = false,
) {
    fun toJson(): JSONObject = Json.obj(
        "id" to id, "name" to name, "path" to path, "kind" to kind.name,
        "durationMs" to durationMs, "durationInFrames" to durationInFrames,
        "width" to width, "height" to height, "fps" to fps, "sizeBytes" to sizeBytes,
        "sourceFilename" to sourceFilename, "thumbnailPath" to thumbnailPath,
        "hasAudio" to hasAudio,
    )

    companion object {
        fun fromJson(o: JSONObject) = MediaAsset(
            id = Json.optString(o, "id", UUID.randomUUID().toString()),
            name = Json.optString(o, "name", "media"),
            path = Json.optString(o, "path"),
            kind = runCatching { MediaKind.valueOf(Json.optString(o, "kind", "VIDEO")) }
                .getOrDefault(MediaKind.VIDEO),
            durationMs = Json.optLong(o, "durationMs"),
            durationInFrames = Json.optInt(o, "durationInFrames"),
            width = Json.optInt(o, "width"), height = Json.optInt(o, "height"),
            fps = Json.optDouble(o, "fps"),
            sizeBytes = Json.optLong(o, "sizeBytes"),
            sourceFilename = Json.optString(o, "sourceFilename"),
            thumbnailPath = Json.optString(o, "thumbnailPath"),
            hasAudio = Json.optBool(o, "hasAudio"),
        )
    }
}

data class TimelineItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val assetId: String,
    val track: String = "V1",
    val startFrame: Int,
    val durationInFrames: Int,
    val srcInFrame: Int = 0,
    val playbackRate: Double = 1.0,
    val volume: Double = 1.0,
    val fadeInFrames: Int = 0,
    val fadeOutFrames: Int = 0,
    val muted: Boolean = false,
) {
    /** Upstream: source end = srcInFrame + duration * playbackRate (rate stretch keeps source span). */
    val sourceEndFrame: Int
        get() = (srcInFrame + durationInFrames * playbackRate).roundToInt()

    fun toJson(): JSONObject = Json.obj(
        "id" to id, "name" to name, "assetId" to assetId, "track" to track,
        "startFrame" to startFrame, "durationInFrames" to durationInFrames,
        "srcInFrame" to srcInFrame, "playbackRate" to playbackRate, "volume" to volume,
        "fadeInFrames" to fadeInFrames, "fadeOutFrames" to fadeOutFrames, "muted" to muted,
    )

    companion object {
        fun fromJson(o: JSONObject) = TimelineItem(
            id = Json.optString(o, "id", UUID.randomUUID().toString()),
            name = Json.optString(o, "name", "clip"),
            assetId = Json.optString(o, "assetId"),
            track = Json.optString(o, "track", "V1"),
            startFrame = Json.optInt(o, "startFrame"),
            durationInFrames = Json.optInt(o, "durationInFrames", 1),
            srcInFrame = Json.optInt(o, "srcInFrame"),
            playbackRate = Json.optDouble(o, "playbackRate", 1.0).takeIf { it > 0 } ?: 1.0,
            volume = Json.optDouble(o, "volume", 1.0),
            fadeInFrames = Json.optInt(o, "fadeInFrames"),
            fadeOutFrames = Json.optInt(o, "fadeOutFrames"),
            muted = Json.optBool(o, "muted"),
        )
    }
}

/** Upstream allows 16 GLSL transitions; on Android these are rendered with ffmpeg xfade. */
enum class TransitionType(val ffmpegName: String?) {
    CROSS_DISSOLVE("fade"),
    DIP_TO_BLACK("fadeblack"),
    FLASH("fadewhite"),
    SOFT_WIPE("wipeleft"),
    WHIP_PAN("slideleft"),
    CIRCLE_WIPE("circleopen"),
    GLITCH_CUT(null),
    PAGE_CURL(null),
    AUDIO_CROSS_FADE(null);

    companion object {
        fun from(value: String?): TransitionType = values().firstOrNull {
            it.name.equals(value, ignoreCase = true) || it.ffmpegName == value
        } ?: CROSS_DISSOLVE
    }
}

data class TransitionItem(
    val id: String = UUID.randomUUID().toString(),
    val type: TransitionType = TransitionType.CROSS_DISSOLVE,
    val trackId: String = "V1",
    val outgoingItemId: String,
    val incomingItemId: String,
    val durationInFrames: Int = 15,
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = Json.obj(
        "id" to id, "type" to type.name, "trackId" to trackId,
        "outgoingItemId" to outgoingItemId, "incomingItemId" to incomingItemId,
        "durationInFrames" to durationInFrames, "enabled" to enabled,
    )

    companion object {
        fun fromJson(o: JSONObject) = TransitionItem(
            id = Json.optString(o, "id", UUID.randomUUID().toString()),
            type = TransitionType.from(Json.optString(o, "type")),
            trackId = Json.optString(o, "trackId", "V1"),
            outgoingItemId = Json.optString(o, "outgoingItemId"),
            incomingItemId = Json.optString(o, "incomingItemId"),
            durationInFrames = Json.optInt(o, "durationInFrames", 15),
            enabled = Json.optBool(o, "enabled", true),
        )
    }
}

data class CaptionCue(val startMs: Long, val endMs: Long, val text: String) {
    fun toJson(): JSONObject = Json.obj("start" to startMs, "end" to endMs, "text" to text)
    companion object {
        fun fromJson(o: JSONObject) = CaptionCue(
            Json.optLong(o, "start"), Json.optLong(o, "end"), Json.optString(o, "text")
        )
    }
}

data class CaptionsData(
    val enabled: Boolean = false,
    val template: String = "bold-outline",
    val cues: List<CaptionCue> = emptyList(),
) {
    fun toJson(): JSONObject = Json.obj(
        "enabled" to enabled, "template" to template,
        "cues" to JSONArray(cues.map { it.toJson() }),
    )

    companion object {
        fun fromJson(o: JSONObject) = CaptionsData(
            enabled = Json.optBool(o, "enabled"),
            template = Json.optString(o, "template", "bold-outline"),
            cues = (o.optJSONArray("cues") ?: JSONArray()).let { a ->
                (0 until a.length()).map { CaptionCue.fromJson(a.getJSONObject(it)) }
            },
        )
    }
}

data class TimelineState(
    val fps: Int = 30,
    val width: Int = 1920,
    val height: Int = 1080,
    val items: List<TimelineItem> = emptyList(),
    val transitions: List<TransitionItem> = emptyList(),
    val captions: CaptionsData = CaptionsData(),
    val selectedId: String? = null,
) {
    fun toJson(): JSONObject = Json.obj(
        "fps" to fps, "width" to width, "height" to height,
        "items" to JSONArray(items.map { it.toJson() }),
        "transitions" to JSONArray(transitions.map { it.toJson() }),
        "captions" to captions.toJson(),
        "selectedId" to selectedId,
    )

    companion object {
        fun fromJson(o: JSONObject) = TimelineState(
            fps = Json.optInt(o, "fps", 30).takeIf { it in 1..120 } ?: 30,
            width = Json.optInt(o, "width", 1920),
            height = Json.optInt(o, "height", 1080),
            items = (o.optJSONArray("items") ?: JSONArray()).let { a ->
                (0 until a.length()).map { TimelineItem.fromJson(a.getJSONObject(it)) }
            },
            transitions = (o.optJSONArray("transitions") ?: JSONArray()).let { a ->
                (0 until a.length()).map { TransitionItem.fromJson(a.getJSONObject(it)) }
            },
            captions = o.optJSONObject("captions")?.let { CaptionsData.fromJson(it) } ?: CaptionsData(),
            selectedId = o.optString("selectedId").takeIf { it.isNotEmpty() },
        )
    }
}

data class ProjectDoc(
    val version: Int = 7,
    val assets: List<MediaAsset> = emptyList(),
    val timeline: TimelineState = TimelineState(),
    val name: String = "Untitled Project",
) {
    fun toJson(): JSONObject = Json.obj(
        "version" to version, "name" to name,
        "assets" to JSONArray(assets.map { it.toJson() }),
        "timeline" to timeline.toJson(),
    )

    companion object {
        fun fromJson(o: JSONObject) = ProjectDoc(
            version = Json.optInt(o, "version", 7),
            name = Json.optString(o, "name", "Untitled Project"),
            assets = (o.optJSONArray("assets") ?: JSONArray()).let { a ->
                (0 until a.length()).map { MediaAsset.fromJson(a.getJSONObject(it)) }
            },
            timeline = o.optJSONObject("timeline")?.let { TimelineState.fromJson(it) } ?: TimelineState(),
        )
    }
}

/** Upstream: total timeline length = last item's end (min 1s). */
fun timelineDurationFrames(s: TimelineState): Int {
    val end = s.items.maxOfOrNull { it.startFrame + it.durationInFrames } ?: 0
    return max(end, s.fps)
}

fun trackEnd(s: TimelineState, track: String): Int = s.items
    .filter { it.track == track }
    .maxOfOrNull { it.startFrame + it.durationInFrames } ?: 0

/** Upstream split_item: two clips, right one continues the same source window. */
fun splitItem(item: TimelineItem, atFrame: Int, fps: Int): Pair<TimelineItem, TimelineItem>? {
    val local = atFrame - item.startFrame
    if (local <= 0 || local >= item.durationInFrames) return null
    val left = item.copy(durationInFrames = local, fadeOutFrames = 0)
    val right = item.copy(
        id = UUID.randomUUID().toString(),
        startFrame = atFrame,
        durationInFrames = item.durationInFrames - local,
        srcInFrame = (item.srcInFrame + local * item.playbackRate).roundToInt(),
        fadeInFrames = 0,
    )
    return left to right
}

/** Source milliseconds → timeline frame (upstream src/scene-detection/apply.ts mapping). */
fun sourceMsToItemFrame(item: TimelineItem, fps: Int, sourceMs: Long): Int {
    val sourceFrame = (sourceMs * fps / 1000.0).roundToInt()
    return item.startFrame + ((sourceFrame - item.srcInFrame) / item.playbackRate).roundToInt()
}
