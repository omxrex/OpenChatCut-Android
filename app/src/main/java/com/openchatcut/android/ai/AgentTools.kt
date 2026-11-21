package com.openchatcut.android.ai

import android.content.Context
import com.openchatcut.android.core.EditorEngine
import com.openchatcut.android.media.MediaAnalysis
import com.openchatcut.android.media.MediaAnalysisResult
import com.openchatcut.android.media.TimelineRenderer
import com.openchatcut.android.model.CaptionCue
import com.openchatcut.android.model.MediaKind
import com.openchatcut.android.model.TransitionType
import com.openchatcut.android.model.timelineDurationFrames
import com.openchatcut.android.util.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ToolOutcome(val ok: Boolean, val message: String, val data: JSONObject = JSONObject()) {
    fun toJson(): JSONObject = Json.obj(
        "ok" to ok, "success" to ok, "message" to message, "data" to data,
    )
}

/** One canonical tool contract, ported from assets/agent/openchatcut-tool-schemas.json. */
data class ToolSpec(val name: String, val description: String, val schema: String) {
    fun toOllama(): JSONObject = Json.obj(
        "type" to "function",
        "function" to Json.obj(
            "name" to name,
            "description" to description,
            "parameters" to Json.parseObject(schema),
        ),
    )
}

class AgentTools(
    private val androidContext: Context,
    private val engine: EditorEngine,
    private val status: (String) -> Unit,
) {
    /** Real analyses cached per asset for the duration of a tool loop. */
    private val analysisCache = mutableMapOf<String, MediaAnalysisResult>()

    fun catalog(): List<ToolSpec> = listOf(
        ToolSpec("read_timeline",
            "Read the current timeline: fps, canvas size and every clip with exact source window (srcInFrame, durationInFrames, playbackRate), track, volume and fades. Call this first to see current state before editing.",
            "{\"type\":\"object\",\"properties\":{}}"),
        ToolSpec("read_project",
            "Read the current project: name, canvas, all media assets and timeline summary. Omit view for a full overview.",
            "{\"type\":\"object\",\"properties\":{\"view\":{\"type\":\"string\",\"enum\":[\"timeline\",\"assets\"]}}}"),
        ToolSpec("probe_media",
            "Probe a media file with the app's bundled ffprobe. Returns measured duration, dimensions, fps, codecs and quality risks. Accepts an asset id or prefix.",
            "{\"type\":\"object\",\"properties\":{\"source\":{\"type\":\"string\"}},\"required\":[\"source\"]}"),
        ToolSpec("analyze_media",
            "Android-port addition. Run real on-device footage analysis on an asset: FFmpeg scene scores, measured frame-to-frame motion, RMS silence spans, and optional local Whisper transcription. Returns numeric measurements used by montage and boring-part tools.",
            "{\"type\":\"object\",\"properties\":{\"assetId\":{\"type\":\"string\"},\"threshold\":{\"type\":\"number\"},\"withTranscript\":{\"type\":\"boolean\"}},\"required\":[\"assetId\"]}"),
        ToolSpec("set_item_timing",
            "Retime a clip: change its start frame and/or duration (frames), and/or set fade-in / fade-out in SECONDS. 0 clears a fade. ripple:true shifts later same-track clips when the right edge moves.",
            "{\"type\":\"object\",\"properties\":{\"itemId\":{\"type\":\"string\"},\"startFrame\":{\"type\":\"number\"},\"durationInFrames\":{\"type\":\"number\"},\"fadeInSeconds\":{\"type\":\"number\"},\"fadeOutSeconds\":{\"type\":\"number\"},\"ripple\":{\"type\":\"boolean\"},\"srcInFrame\":{\"type\":\"number\"},\"playbackRate\":{\"type\":\"number\"}},\"required\":[\"itemId\"]}"),
        ToolSpec("split_item",
            "Split a clip into two at the given absolute timeline frame. The second piece continues the same source window.",
            "{\"type\":\"object\",\"properties\":{\"itemId\":{\"type\":\"string\"},\"atFrame\":{\"type\":\"number\"}},\"required\":[\"itemId\",\"atFrame\"]}"),
        ToolSpec("move_item",
            "Move a clip to a different track and/or start frame.",
            "{\"type\":\"object\",\"properties\":{\"itemId\":{\"type\":\"string\"},\"track\":{\"type\":\"string\"},\"startFrame\":{\"type\":\"number\"}},\"required\":[\"itemId\"]}"),
        ToolSpec("remove_item",
            "Delete a clip from the timeline. ripple:true closes the gap (later same-track clips shift left); default leaves a gap.",
            "{\"type\":\"object\",\"properties\":{\"itemId\":{\"type\":\"string\"},\"ripple\":{\"type\":\"boolean\"}},\"required\":[\"itemId\"]}"),
        ToolSpec("duplicate_item",
            "Duplicate a clip; the copy is appended to the end of its track.",
            "{\"type\":\"object\",\"properties\":{\"itemId\":{\"type\":\"string\"}},\"required\":[\"itemId\"]}"),
        ToolSpec("add_transition",
            "Add a real xfade transition between two adjacent clips on the same track. Types: CROSS_DISSOLVE, DIP_TO_BLACK, FLASH, SOFT_WIPE, WHIP_PAN, CIRCLE_WIPE.",
            "{\"type\":\"object\",\"properties\":{\"outgoingItemId\":{\"type\":\"string\"},\"incomingItemId\":{\"type\":\"string\"},\"type\":{\"type\":\"string\"},\"durationFrames\":{\"type\":\"integer\"}},\"required\":[\"outgoingItemId\",\"incomingItemId\"]}"),
        ToolSpec("set_aspect_ratio",
            "Retarget the canvas to 16:9, 9:16, 1:1, 4:3 or 3:4 (long-to-short editing).",
            "{\"type\":\"object\",\"properties\":{\"ratio\":{\"type\":\"string\",\"enum\":[\"16:9\",\"9:16\",\"1:1\",\"4:3\",\"3:4\"]}},\"required\":[\"ratio\"]}"),
        ToolSpec("edit_track",
            "Manage tracks: list, tighten (close gaps) or reorder_items (pack clips in a given id order).",
            "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"list\",\"tighten\",\"reorder_items\"]},\"trackId\":{\"type\":\"string\"},\"json\":{\"type\":\"string\"}},\"required\":[\"action\"]}"),
        ToolSpec("detect_scenes",
            "Detect visual scene changes with local FFmpeg. apply=report returns real boundaries; apply=split cuts the clip at accepted boundaries as one undoable edit. threshold 0.2 sensitive, 0.3 balanced, 0.45 conservative.",
            "{\"type\":\"object\",\"properties\":{\"itemId\":{\"type\":\"string\"},\"assetId\":{\"type\":\"string\"},\"threshold\":{\"type\":\"number\"},\"minSceneSeconds\":{\"type\":\"number\"},\"maxScenes\":{\"type\":\"number\"},\"apply\":{\"type\":\"string\",\"enum\":[\"report\",\"split\"]}}}"),
        ToolSpec("remove_silence",
            "Remove dead air using on-device relative RMS detection. dryRun:true previews real cut durations without editing. Omit itemId to sweep every clip. Ripple is per-track.",
            "{\"type\":\"object\",\"properties\":{\"itemId\":{\"type\":\"string\"},\"thresholdDb\":{\"type\":\"number\"},\"minSilenceMs\":{\"type\":\"number\"},\"padMs\":{\"type\":\"number\"},\"dryRun\":{\"type\":\"boolean\"}}}"),
        ToolSpec("read_captions",
            "Read caption state and resolved cues. Use list=true to see caption tracks.",
            "{\"type\":\"object\",\"properties\":{\"list\":{\"type\":\"boolean\"}}}"),
        ToolSpec("edit_captions",
            "Manage captions: enable/disable the burn-in overlay, or import a real SRT file already on the device into the caption track.",
            "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"enable\",\"disable\",\"import_srt\"]},\"path\":{\"type\":\"string\"}},\"required\":[\"action\"]}"),
        ToolSpec("remove_boring_parts",
            "Android-port addition. Remove low-motion / silent segments from a placed clip using real measured motion and RMS audio analysis. Produces actual cuts in the timeline. Requires analyze_media first.",
            "{\"type\":\"object\",\"properties\":{\"assetId\":{\"type\":\"string\"},\"intensity\":{\"type\":\"number\"}},\"required\":[\"assetId\"]}"),
        ToolSpec("auto_highlight_montage",
            "Android-port addition. Build a real highlight montage from analysed footage by selecting the highest-motion windows and adding cross dissolves. Requires analyze_media for the assets first.",
            "{\"type\":\"object\",\"properties\":{\"maxDurationSeconds\":{\"type\":\"number\"},\"segmentSeconds\":{\"type\":\"number\"},\"segments\":{\"type\":\"number\"},\"withTransitions\":{\"type\":\"boolean\"}}}"),
        ToolSpec("fit_to_duration",
            "Android-port addition. Make a Short of an exact length: trim from the end or loop real clips to fill the requested seconds.",
            "{\"type\":\"object\",\"properties\":{\"seconds\":{\"type\":\"number\"}},\"required\":[\"seconds\"]}"),
        ToolSpec("submit_render_job",
            "Render the active timeline as a real MP4 (H.264/AAC) with burned-in captions and transitions. Returns the output path, duration and file size after the native FFmpeg encode actually completes.",
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"resolution\":{\"type\":\"string\",\"enum\":[\"480p\",\"720p\",\"1080p\"]}},\"required\":[]}"),
        ToolSpec("undo_last_change", "Revert the project to its state before the most recent applied edit.", "{\"type\":\"object\",\"properties\":{}}"),
        ToolSpec("redo_last_change", "Re-apply the state undone by the most recent undo.", "{\"type\":\"object\",\"properties\":{}}"),
    )

    fun toOllamaCatalog(): JSONArray {
        val array = JSONArray()
        catalog().forEach { array.put(it.toOllama()) }
        return array
    }

    fun execute(name: String, args: JSONObject): ToolOutcome = try {
        when (name) {
            "read_timeline" -> readTimeline()
            "read_project" -> readProject(args)
            "probe_media" -> probeMedia(args)
            "analyze_media" -> analyzeMedia(args)
            "set_item_timing" -> setItemTiming(args)
            "split_item" -> splitItem(args)
            "move_item" -> moveItem(args)
            "remove_item" -> removeItem(args)
            "duplicate_item" -> duplicateItem(args)
            "add_transition" -> addTransition(args)
            "set_aspect_ratio" -> setAspect(args)
            "edit_track" -> editTrack(args)
            "detect_scenes" -> detectScenes(args)
            "remove_silence" -> removeSilence(args)
            "read_captions" -> readCaptions(args)
            "edit_captions" -> editCaptions(args)
            "remove_boring_parts" -> removeBoringParts(args)
            "auto_highlight_montage" -> montage(args)
            "fit_to_duration" -> fitToDuration(args)
            "submit_render_job" -> render(args)
            "undo_last_change" -> fromEdit(engine.undo())
            "redo_last_change" -> fromEdit(engine.redo())
            else -> ToolOutcome(false, "Unknown tool '$name'. Available: ${catalog().joinToString { it.name }}")
        }
    } catch (e: Throwable) {
        ToolOutcome(false, "Tool '$name' failed: ${e.message ?: e::class.java.simpleName}")
    }

    private fun fromEdit(r: com.openchatcut.android.core.EditResult): ToolOutcome =
        ToolOutcome(r.ok, r.message, Json.obj("changed" to r.changed))

    private fun resolveItem(idOrPrefix: String) = engine.project.timeline.items.firstOrNull {
        it.id == idOrPrefix || it.id.startsWith(idOrPrefix)
    }

    private fun resolveAsset(idOrPrefix: String) = engine.project.assets.firstOrNull {
        it.id == idOrPrefix || it.id.startsWith(idOrPrefix) || it.name.equals(idOrPrefix, true)
    }

    private fun readTimeline(): ToolOutcome {
        val s = engine.project.timeline
        val data = Json.obj(
            "fps" to s.fps,
            "canvas" to "${s.width}x${s.height}",
            "durationFrames" to timelineDurationFrames(s),
            "durationSeconds" to timelineDurationFrames(s).toDouble() / s.fps,
            "items" to JSONArray(s.items.map { it.toJson() }),
            "transitions" to JSONArray(s.transitions.map { it.toJson() }),
            "captions" to s.captions.toJson(),
            "selectedId" to s.selectedId,
        )
        return ToolOutcome(true, "Read ${s.items.size} clips across ${s.items.map { it.track }.distinct().size} tracks", data)
    }

    private fun readProject(args: JSONObject): ToolOutcome {
        val p = engine.project
        val view = Json.optString(args, "view")
        val data = when (view) {
            "assets" -> Json.obj("assets" to JSONArray(p.assets.map { it.toJson() }))
            "timeline" -> Json.obj("timeline" to p.timeline.toJson())
            else -> p.toJson()
        }
        return ToolOutcome(true, "Project ${p.name}: ${p.assets.size} assets, ${p.timeline.items.size} clips", data)
    }

    private fun probeMedia(args: JSONObject): ToolOutcome {
        val source = Json.optString(args, "source")
        val asset = resolveAsset(source)
            ?: return ToolOutcome(false, "Asset '$source' not found. Use read_project view=assets to discover ids.")
        val data = Json.obj(
            "assetId" to asset.id, "name" to asset.name,
            "durationMs" to asset.durationMs, "width" to asset.width, "height" to asset.height,
            "fps" to asset.fps, "hasAudio" to asset.hasAudio, "sizeBytes" to asset.sizeBytes,
            "qualityRisks" to JSONArray(buildList {
                if (asset.width > 0 && asset.width < 720) add("low resolution (${asset.width}x${asset.height})")
                if (!asset.hasAudio) add("no audio track")
                if (asset.durationMs < 1000) add("very short source")
                if (asset.fps > 0 && asset.fps < 24) add("low frame rate ${asset.fps}")
            }),
        )
        return ToolOutcome(true, "Probed ${asset.name}: ${asset.width}x${asset.height}, ${asset.durationMs}ms", data)
    }

    private fun analyzeMedia(args: JSONObject): ToolOutcome {
        val asset = resolveAsset(Json.optString(args, "assetId"))
            ?: return ToolOutcome(false, "Asset not found. Use read_project view=assets first.")
        status.invoke("Analyzing ${asset.name} with FFmpeg on device…")
        val result = MediaAnalysis.analyze(
            context = androidContext,
            asset = asset,
            threshold = Json.optDouble(args, "threshold", 0.3),
            withAudio = true,
            withTranscript = Json.optBool(args, "withTranscript"),
            onStage = { status.invoke(it) },
        )
        analysisCache[asset.id] = result
        val data = Json.obj(
            "assetId" to asset.id,
            "durationMs" to result.durationMs,
            "scenes" to JSONArray(result.scenes.map { Json.obj("ms" to it.timeMs, "score" to it.score, "kind" to it.kind) }),
            "motionWindows" to result.motion.size,
            "topMotion" to JSONArray(result.motion.sortedByDescending { it.score }.take(8)
                .map { Json.obj("ms" to it.startMs, "score" to it.score, "brightness" to it.brightness) }),
            "silence" to JSONArray(result.silence.map { Json.obj("startMs" to it.startMs, "endMs" to it.endMs) }),
            "transcriptCues" to result.transcript.size,
            "notes" to JSONArray(result.notes),
        )
        return ToolOutcome(
            true,
            "Measured ${result.motion.size} motion windows, ${result.scenes.size} scene changes, ${result.silence.size} silence spans",
            data,
        )
    }

    private fun setItemTiming(args: JSONObject): ToolOutcome {
        val item = resolveItem(Json.optString(args, "itemId"))
            ?: return ToolOutcome(false, "Clip not found. Use read_timeline to get item ids.")
        val base = fromEdit(engine.retime(
            itemId = item.id,
            startFrame = if (args.has("startFrame")) args.optInt("startFrame") else null,
            durationInFrames = if (args.has("durationInFrames")) args.optInt("durationInFrames") else null,
            fadeInSeconds = if (args.has("fadeInSeconds")) args.optDouble("fadeInSeconds") else null,
            fadeOutSeconds = if (args.has("fadeOutSeconds")) args.optDouble("fadeOutSeconds") else null,
            ripple = Json.optBool(args, "ripple"),
        ))
        if (!base.ok) return base
        if (args.has("srcInFrame") || args.has("playbackRate")) {
            val updated = engine.project.timeline.items.first { it.id == item.id }
            val r = engine.trim(
                item.id, updated.startFrame, updated.durationInFrames,
                if (args.has("srcInFrame")) args.optInt("srcInFrame") else updated.srcInFrame,
            )
            if (!r.ok) return fromEdit(r)
            if (args.has("playbackRate")) {
                val rate = Json.optDouble(args, "playbackRate", 1.0)
                val r2 = engine.setRate(item.id, rate)
                if (!r2.ok) return fromEdit(r2)
            }
        }
        return ToolOutcome(true, "Retimed ${item.name}", Json.obj("itemId" to item.id))
    }

    private fun splitItem(args: JSONObject): ToolOutcome {
        val item = resolveItem(Json.optString(args, "itemId"))
            ?: return ToolOutcome(false, "Clip not found")
        return fromEdit(engine.split(item.id, args.optInt("atFrame")))
    }

    private fun moveItem(args: JSONObject): ToolOutcome {
        val item = resolveItem(Json.optString(args, "itemId"))
            ?: return ToolOutcome(false, "Clip not found")
        val track = Json.optString(args, "track").ifEmpty { null }
        val start = if (args.has("startFrame")) args.optInt("startFrame") else item.startFrame
        return fromEdit(engine.move(item.id, start, track))
    }

    private fun removeItem(args: JSONObject): ToolOutcome {
        val item = resolveItem(Json.optString(args, "itemId"))
            ?: return ToolOutcome(false, "Clip not found")
        return fromEdit(engine.removeWithRipple(item.id, Json.optBool(args, "ripple")))
    }

    private fun duplicateItem(args: JSONObject): ToolOutcome {
        val item = resolveItem(Json.optString(args, "itemId"))
            ?: return ToolOutcome(false, "Clip not found")
        return fromEdit(engine.duplicate(item.id))
    }

    private fun addTransition(args: JSONObject): ToolOutcome {
        val out = resolveItem(Json.optString(args, "outgoingItemId"))
            ?: return ToolOutcome(false, "Outgoing clip not found")
        val inc = resolveItem(Json.optString(args, "incomingItemId"))
            ?: return ToolOutcome(false, "Incoming clip not found")
        val type = runCatching { TransitionType.valueOf(Json.optString(args, "type").uppercase()) }
            .getOrDefault(TransitionType.CROSS_DISSOLVE)
        return fromEdit(engine.addTransition(out.id, inc.id, type, Json.optInt(args, "durationFrames", 15)))
    }

    private fun setAspect(args: JSONObject): ToolOutcome {
        val (w, h) = when (Json.optString(args, "ratio")) {
            "9:16" -> 1080 to 1920
            "1:1" -> 1080 to 1080
            "4:3" -> 1440 to 1080
            "3:4" -> 1080 to 1440
            else -> 1920 to 1080
        }
        return fromEdit(engine.setAspect(w, h))
    }

    private fun editTrack(args: JSONObject): ToolOutcome = when (Json.optString(args, "action")) {
        "list" -> {
            val tracks = engine.project.timeline.items.map { it.track }.distinct().sorted()
            ToolOutcome(true, "Tracks: ${tracks.joinToString(", ")}", Json.obj("tracks" to JSONArray(tracks)))
        }
        "tighten" -> {
            val track = Json.optString(args, "trackId", "V1")
            fromEdit(engine.tighten(track))
        }
        "reorder_items" -> {
            val track = Json.optString(args, "trackId", "V1")
            val raw = Json.optString(args, "json")
            val ids = try {
                val o = Json.parseObject(raw)
                val a = o.optJSONArray("itemIds") ?: o.optJSONArray("orderedIds") ?: JSONArray()
                Json.strings(a)
            } catch (_: Exception) { emptyList() }
            fromEdit(engine.reorder(track, ids))
        }
        else -> ToolOutcome(false, "edit_track action must be list, tighten or reorder_items")
    }

    private fun detectScenes(args: JSONObject): ToolOutcome {
        val item = Json.optString(args, "itemId").let { if (it.isEmpty()) null else resolveItem(it) }
        val asset = item?.let { engine.project.assets.firstOrNull { a -> a.id == it.assetId } }
            ?: Json.optString(args, "assetId").let { if (it.isEmpty()) null else resolveAsset(it) }
            ?: return ToolOutcome(false, "Provide itemId or assetId")
        status.invoke("Detecting real scene changes in ${asset.name}…")
        val analysis = MediaAnalysis.analyze(
            androidContext, asset,
            threshold = Json.optDouble(args, "threshold", 0.3),
            withAudio = false, withTranscript = false,
            onStage = { status.invoke(it) },
        )
        analysisCache[asset.id] = analysis
        val apply = Json.optString(args, "apply", "report")
        if (item == null && apply != "report") {
            return ToolOutcome(false, "apply=$apply requires a timeline itemId")
        }
        val boundaries = if (item != null) {
            analysis.scenes.map {
                sourceMsToTimelineFrame(item, engine.project.timeline.fps, it.timeMs)
            }.distinct().filter { it > item.startFrame && it < item.startFrame + item.durationInFrames }.sorted()
        } else emptyList()
        if (apply == "split" && item != null) {
            val edit = engine.splitAtFrames(item.id, boundaries)
            if (!edit.ok) return fromEdit(edit)
        }
        val data = Json.obj(
            "assetId" to asset.id, "itemId" to item?.id,
            "sceneCount" to analysis.scenes.size,
            "boundaries" to JSONArray(analysis.scenes.map {
                Json.obj("sourceMs" to it.timeMs, "score" to it.score, "kind" to it.kind,
                    "timelineFrame" to (item?.let { i -> sourceMsToTimelineFrame(i, engine.project.timeline.fps, it.timeMs) } ?: -1))
            }),
            "applied" to apply,
        )
        return ToolOutcome(true, "Detected ${analysis.scenes.size} real scene changes (apply=$apply)", data)
    }

    private fun sourceMsToTimelineFrame(item: com.openchatcut.android.model.TimelineItem, fps: Int, ms: Long): Int {
        val sourceFrame = (ms * fps / 1000.0).roundToInt()
        return item.startFrame + ((sourceFrame - item.srcInFrame) / item.playbackRate).roundToInt()
    }

    private fun removeSilence(args: JSONObject): ToolOutcome {
        val fps = engine.project.timeline.fps
        val params = com.openchatcut.android.core.SilenceParams(
            thresholdDb = Json.optDouble(args, "thresholdDb", -26.0),
            minSilenceMs = Json.optInt(args, "minSilenceMs", 600),
            padMs = Json.optInt(args, "padMs", 150),
        )
        val dryRun = Json.optBool(args, "dryRun")
        val only = Json.optString(args, "itemId").let { if (it.isEmpty()) null else resolveItem(it) }
        val clips = engine.project.timeline.items.filter {
            (only == null || it.id == only.id) && it.playbackRate == 1.0 && it.durationInFrames > fps
        }
        if (clips.isEmpty()) return ToolOutcome(false, "No eligible clips (rate must be 1.0 and clip longer than 1s)")

        val report = JSONArray()
        var totalRemoved = 0
        for (clip in clips) {
            val asset = engine.project.assets.firstOrNull { it.id == clip.assetId } ?: continue
            if (!asset.hasAudio) continue
            val customParams = params.thresholdDb != -26.0 || params.minSilenceMs != 600 || params.padMs != 150
            val spans = if (customParams) {
                status.invoke("Re-analysing audio in ${asset.name} with custom silence gate…")
                MediaAnalysis.analyzeSilence(androidContext, asset, params, status)
            } else {
                val analysis = analysisCache[asset.id] ?: run {
                    status.invoke("Analyzing audio in ${asset.name}…")
                    MediaAnalysis.analyze(androidContext, asset, withAudio = true, withTranscript = false, onStage = status)
                        .also { analysisCache[asset.id] = it }
                }
                analysis.silence
            }
            val ranges = spans.mapNotNull { span ->
                val from = max(0, ((span.startMs * fps / 1000.0) - clip.srcInFrame).roundToInt())
                val to = min(clip.durationInFrames, ((span.endMs * fps / 1000.0) - clip.srcInFrame).roundToInt())
                if (to - from >= fps / 2) from to to else null
            }
            if (ranges.isEmpty()) continue
            val frames = ranges.sumOf { it.second - it.first }
            totalRemoved += frames
            report.put(Json.obj(
                "itemId" to clip.id, "name" to clip.name,
                "ranges" to JSONArray(ranges.map { Json.obj("fromFrame" to it.first, "toFrame" to it.second) }),
                "secondsRemoved" to frames.toDouble() / fps,
            ))
            if (!dryRun) {
                val edit = engine.removeRanges(clip.id, ranges)
                if (!edit.ok) return ToolOutcome(false, "Failed to remove silence from ${clip.name}: ${edit.message}")
            }
        }
        if (report.length() == 0) return ToolOutcome(false, "No silence spans met the threshold; no edits made")
        return ToolOutcome(
            true,
            "${if (dryRun) "Would remove" else "Removed"} ${totalRemoved} frames (${String.format("%.2f", totalRemoved.toDouble() / fps)}s) of detected silence",
            Json.obj("dryRun" to dryRun, "clips" to report, "framesRemoved" to totalRemoved),
        )
    }

    private fun readCaptions(args: JSONObject): ToolOutcome {
        val c = engine.project.timeline.captions
        return ToolOutcome(
            true, "Captions enabled=${c.enabled}, ${c.cues.size} cues",
            Json.obj(
                "tracks" to JSONArray(listOf("C1")),
                "enabled" to c.enabled, "template" to c.template,
                "cues" to JSONArray(c.cues.map { it.toJson() }),
            ),
        )
    }

    private fun editCaptions(args: JSONObject): ToolOutcome {
        return when (Json.optString(args, "action")) {
            "enable" -> fromEdit(engine.setCaptions(true, engine.project.timeline.captions.cues))
            "disable" -> fromEdit(engine.setCaptions(false, engine.project.timeline.captions.cues))
            "import_srt" -> {
                val path = Json.optString(args, "path")
                val file = File(path)
                if (!file.exists() || file.length() < 4) return ToolOutcome(false, "SRT file not found: $path")
                val text = file.readText(Charsets.UTF_8)
                val cues = parseSrt(text)
                if (cues.isEmpty()) return ToolOutcome(false, "No valid cues parsed from $path")
                fromEdit(engine.setCaptions(true, cues))
            }
            else -> ToolOutcome(false, "edit_captions action must be enable, disable or import_srt")
        }
    }

    private fun parseSrt(text: String): List<CaptionCue> {
        val time = Regex("(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})")
        val out = mutableListOf<CaptionCue>()
        for (block in text.replace("\r\n?", "\n").trim().split(Regex("\n[ \t]*\n+"))) {
            val lines = block.lines()
            val idx = lines.indexOfFirst { time.containsMatchIn(it) }
            if (idx < 0) continue
            val m = time.find(lines[idx]) ?: continue
            val caption = lines.drop(idx + 1).joinToString(" ") { it.trim() }.trim()
            if (caption.isEmpty()) continue
            out += CaptionCue(ms(m, 1, 2, 3, 4), ms(m, 5, 6, 7, 8), caption)
        }
        return out
    }

    private fun ms(m: MatchResult, h: Int, min: Int, s: Int, frac: Int): Long =
        ((m.groupValues[h].toLong() * 3600) + (m.groupValues[min].toLong() * 60) + m.groupValues[s].toLong()) * 1000 +
            m.groupValues[frac].padEnd(3, '0').take(3).toLong()

    private fun removeBoringParts(args: JSONObject): ToolOutcome {
        val asset = resolveAsset(Json.optString(args, "assetId"))
            ?: return ToolOutcome(false, "Asset not found; call analyze_media first")
        val analysis = analysisCache[asset.id]
            ?: return ToolOutcome(false, "No analysis for ${asset.name}; call analyze_media first")
        val edit = engine.removeBoringParts(analysis, Json.optDouble(args, "intensity", 0.35))
        return fromEdit(edit)
    }

    private fun montage(args: JSONObject): ToolOutcome {
        if (analysisCache.isEmpty()) return ToolOutcome(false, "No analysed footage; call analyze_media for at least one asset first")
        val edit = engine.buildMontage(
            analyses = analysisCache.values.toList(),
            maxDurationSec = Json.optInt(args, "maxDurationSeconds", 30),
            segmentSec = Json.optInt(args, "segmentSeconds", 3),
            segments = Json.optInt(args, "segments", 8),
            withTransitions = if (args.has("withTransitions")) Json.optBool(args, "withTransitions") else true,
        )
        if (!edit.ok) return fromEdit(edit)
        return ToolOutcome(true, edit.message, Json.obj("timeline" to engine.project.timeline.toJson()))
    }

    private fun fitToDuration(args: JSONObject): ToolOutcome {
        val seconds = Json.optInt(args, "seconds")
        if (seconds < 1 || seconds > 600) return ToolOutcome(false, "seconds must be between 1 and 600")
        return fromEdit(engine.fitToDuration(seconds))
    }

    private fun render(args: JSONObject): ToolOutcome {
        val outDir = File(androidContext.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val rawName = Json.optString(args, "name", "openchatcut-export").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val name = if (rawName.endsWith(".mp4")) rawName else "$rawName.mp4"
        val output = File(outDir, name)
        status.invoke("Starting real FFmpeg render…")
        val result = TimelineRenderer.render(engine.project, output.absolutePath) { pct, msg ->
            status.invoke(if (pct >= 0) "$pct% · $msg" else msg)
        }
        return ToolOutcome(
            result.ok, result.message,
            Json.obj(
                "path" to result.outputPath,
                "durationMs" to result.durationMs,
                "sizeBytes" to result.sizeBytes,
                "verified" to (result.ok && File(result.outputPath).exists()),
            ),
        )
    }
}
