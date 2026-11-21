package com.openchatcut.android.media

import com.arthenica.ffmpegkit.Statistics
import com.openchatcut.android.model.CaptionCue
import com.openchatcut.android.model.MediaKind
import com.openchatcut.android.model.ProjectDoc
import com.openchatcut.android.model.TimelineItem
import com.openchatcut.android.model.TransitionItem
import com.openchatcut.android.model.timelineDurationFrames
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class RenderResult(
    val ok: Boolean,
    val outputPath: String,
    val message: String,
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
)

/**
 * Real FFmpeg filtergraph renderer for the ported OpenChatCut timeline model.
 *
 * Upstream renders with Remotion + headless Chromium, which cannot run on Android. This class
 * reimplements the same timeline semantics (source in/out, playback rate, fades, xfade
 * transitions, burned-in subtitles, audio mix) as a native FFmpeg graph. Nothing is faked.
 */
object TimelineRenderer {

    private data class VideoStream(val label: String, val item: TimelineItem)
    private data class AudioStream(val label: String, val item: TimelineItem)
    private data class Group(val stream: String, val durationFrames: Int)
    private data class Boundary(val transition: TransitionItem?, val incomingId: String)

    fun render(
        project: ProjectDoc,
        outputPath: String,
        onProgress: ((Int, String) -> Unit)? = null,
    ): RenderResult {
        val state = project.timeline
        val fps = state.fps
        val width = state.width
        val height = state.height

        val videoItems = state.items.filter { it.track == "V1" }.sortedBy { it.startFrame }
        val audioItems = state.items.filter { it.track == "A1" }.sortedBy { it.startFrame }

        if (videoItems.isEmpty() && audioItems.isEmpty()) {
            return RenderResult(false, outputPath, "Timeline is empty — nothing to render")
        }

        // One -i per distinct source file; reused assets share an input.
        val assetIds = (videoItems.map { it.assetId } + audioItems.map { it.assetId }).distinct()
        val assets = assetIds.mapNotNull { id -> project.assets.firstOrNull { it.id == id } }
        val indexByAsset = assets.mapIndexed { i, asset -> asset.id to i }.toMap()

        val filters = mutableListOf<String>()
        val videoStreams = mutableListOf<VideoStream>()

        videoItems.forEach { item ->
            val asset = project.assets.firstOrNull { it.id == item.assetId } ?: return@forEach
            if (asset.kind == MediaKind.AUDIO || asset.kind == MediaKind.IMAGE) return@forEach
            val idx = indexByAsset.getValue(item.assetId)
            val label = "[v${safe(item.id)}]"
            val startSec = item.srcInFrame.toDouble() / fps
            val srcDurationSec = max(0.04, item.durationInFrames.toDouble() * item.playbackRate / fps)
            val timelineDurationSec = item.durationInFrames.toDouble() / fps
            val fin = item.fadeInFrames.toDouble() / fps
            val fout = item.fadeOutFrames.toDouble() / fps

            val chain = mutableListOf<String>(
                "trim=start=${fmt(startSec)}:duration=${fmt(srcDurationSec)}",
                "setpts=(PTS-STARTPTS)/${fmt(item.playbackRate)}",
                "fps=$fps",
                "scale=$width:$height:force_original_aspect_ratio=decrease",
                "pad=$width:$height:(ow-iw)/2:(oh-ih)/2:color=black",
                "setsar=1",
            )
            if (fin > 0) chain += "fade=t=in:st=0:d=${fmt(fin)}"
            if (fout > 0) chain += "fade=t=out:st=${fmt(max(0.0, timelineDurationSec - fout))}:d=${fmt(fout)}"
            chain += "format=yuv420p"
            filters += "[$idx:v]${chain.joinToString(",")}$label"
            videoStreams += VideoStream(label, item)
        }

        // Audio: dedicated A1 clips plus the source audio of visible video clips.
        val audioStreams = mutableListOf<AudioStream>()
        audioItems.forEach { item ->
            val asset = project.assets.firstOrNull { it.id == item.assetId } ?: return@forEach
            if (asset.kind == MediaKind.IMAGE) return@forEach
            val idx = indexByAsset.getValue(item.assetId)
            val label = "[a${safe(item.id)}]"
            filters += "[$idx:a]${audioChain(item, fps)}$label"
            audioStreams += AudioStream(label, item)
        }
        videoItems.forEach { item ->
            val asset = project.assets.firstOrNull { it.id == item.assetId } ?: return@forEach
            if (!asset.hasAudio || item.muted) return@forEach
            val idx = indexByAsset.getValue(item.assetId)
            val label = "[va${safe(item.id)}]"
            filters += "[$idx:a]${audioChain(item, fps)}$label"
            audioStreams += AudioStream(label, item)
        }

        val totalFrames = timelineDurationFrames(state)

        // ── Video graph ──────────────────────────────────────────────────────────────
        var videoOut: String
        if (videoStreams.isNotEmpty()) {
            val transitionByIncoming = state.transitions
                .filter { it.enabled }
                .associateBy { it.incomingItemId }

            // Group consecutive clips with no transition boundary; concat inside a group.
            val groups = mutableListOf<Group>()
            val boundaries = mutableListOf<Boundary>() // boundaries[i] joins groups[i-1] → groups[i]
            var pending = mutableListOf<VideoStream>()
            var pendingFrames = 0

            videoStreams.forEachIndexed { index, stream ->
                pending += stream
                pendingFrames += stream.item.durationInFrames
                val next = videoStreams.getOrNull(index + 1)
                val boundaryTransition = next?.let { transitionByIncoming[it.item.id] }
                if (next == null || boundaryTransition != null) {
                    val groupStream = if (pending.size == 1) {
                        pending.first().label
                    } else {
                        val label = "[grp${groups.size}]"
                        filters += "${pending.joinToString("") { it.label }}concat=n=${pending.size}:v=1:a=0$label"
                        label
                    }
                    groups += Group(groupStream, pendingFrames)
                    if (next != null) boundaries += Boundary(boundaryTransition, next.item.id)
                    pending = mutableListOf()
                    pendingFrames = 0
                }
            }

            videoOut = groups[0].stream
            var accDuration = groups[0].durationFrames
            groups.drop(1).forEachIndexed { i, group ->
                val transition = boundaries[i].transition
                val type = transition?.type?.ffmpegName ?: "fade"
                val frames = transition?.durationInFrames ?: 1
                val xf = min(max(1, frames), max(1, min(accDuration, group.durationFrames) - 1))
                val offset = max(0.0, (accDuration - xf).toDouble() / fps)
                val out = "[xf$i]"
                filters += "${videoOut}${group.stream}xfade=transition=$type:duration=${fmt(xf.toDouble() / fps)}:offset=${fmt(offset)}$out"
                videoOut = out
                accDuration = accDuration + group.durationFrames - xf
            }
        } else {
            val durationSec = totalFrames.toDouble() / fps
            videoOut = "[blackvideo]"
            filters += "color=c=black:s=${width}x${height}:r=$fps:d=${fmt(durationSec)}$videoOut"
        }

        // ── Burned-in captions from real SRT cues ────────────────────────────────────
        if (state.captions.enabled && state.captions.cues.isNotEmpty()) {
            val workDir = File(outputPath).absoluteFile.parentFile ?: File(System.getProperty("java.io.tmpdir"))
            val srtFile = File(workDir, "openchatcut_captions.srt")
            srtFile.writeText(buildSrt(state.captions.cues))
            val escaped = FfmpegRunner.ffFilterPath(srtFile.absolutePath)
            val style = "Fontname=Roboto,Fontsize=42,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000," +
                "BorderStyle=1,Outline=3,Shadow=1,Alignment=2,MarginV=60"
            val next = "[captioned]"
            filters += "$videoOut" + "subtitles=filename=$escaped:force_style=${filterQuote(style)}$next"
            videoOut = next
        }

        // ── Audio graph ──────────────────────────────────────────────────────────────
        val audioOut = "[mixedaudio]"
        if (audioStreams.isNotEmpty()) {
            val joined = audioStreams.joinToString("") { it.label }
            val mix = if (audioStreams.size == 1) joined else
                "${joined}amix=inputs=${audioStreams.size}:duration=longest:dropout_transition=0"
            filters += "$mix,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo$audioOut"
        } else {
            val durationSec = totalFrames.toDouble() / fps
            filters += "anullsrc=r=48000:cl=stereo,atrim=0:${fmt(durationSec)},asetpts=PTS-STARTPTS$audioOut"
        }

        // ── Assemble the real FFmpeg invocation ─────────────────────────────────────
        val args = mutableListOf("-y", "-hide_banner", "-loglevel", "error")
        assets.forEach { args += listOf("-i", it.path) }
        args += listOf("-filter_complex", filters.joinToString(";"))
        args += listOf("-map", videoOut, "-map", audioOut)
        args += listOf(
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
            "-r", fps.toString(), "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2",
            "-movflags", "+faststart",
        )
        args += outputPath

        onProgress?.invoke(0, "Encoding $totalFrames frames with FFmpeg ${FfmpegRunner.version()}")
        val result = FfmpegRunner.run(
            *args.toTypedArray(),
            onStats = { s: Statistics ->
                val frame = s.videoFrameNumber
                val pct = if (totalFrames > 0) min(99, max(0, frame * 100 / totalFrames)) else 0
                onProgress?.invoke(pct, "Encoding frame $frame / $totalFrames · ${s.speed}x")
            },
        )

        val outFile = File(outputPath)
        if (!result.success) {
            return RenderResult(false, outputPath, "FFmpeg failed (exit ${result.exitCode}): ${result.logs.takeLast(1500)}")
        }
        if (!outFile.exists() || outFile.length() < 1024) {
            return RenderResult(false, outputPath, "FFmpeg exited successfully but produced no valid output file")
        }
        return RenderResult(
            true, outputPath, "Rendered ${String.format(Locale.US, "%.1f", outFile.length() / 1048576.0)} MiB to ${outFile.name}",
            totalFrames * 1000L / fps, outFile.length(),
        )
    }

    private fun audioChain(item: TimelineItem, fps: Int): String {
        val startSec = item.srcInFrame.toDouble() / fps
        val durationSec = max(0.04, item.durationInFrames.toDouble() * item.playbackRate / fps)
        val timelineStartMs = (item.startFrame.toDouble() * 1000 / fps).roundToInt()
        val chain = mutableListOf<String>(
            "atrim=start=${fmt(startSec)}:duration=${fmt(durationSec)}",
            "asetpts=PTS-STARTPTS",
        )
        if (item.playbackRate != 1.0) chain += "atempo=${fmt(item.playbackRate)}"
        chain += "volume=${fmt(item.volume)}"
        if (timelineStartMs > 0) chain += "adelay=$timelineStartMs|$timelineStartMs"
        return chain.joinToString(",")
    }

    private fun buildSrt(cues: List<CaptionCue>): String =
        cues.sortedBy { it.startMs }.mapIndexed { i, cue ->
            "${i + 1}\n${srtTime(cue.startMs)} --> ${srtTime(cue.endMs)}\n${cue.text}\n"
        }.joinToString("\n")

    private fun srtTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms % 1000)
    }

    private fun safe(id: String): String = id.filter { it.isLetterOrDigit() }.take(12)

    private fun fmt(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(Locale.US, "%.4f", value)

    private fun filterQuote(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "'\\''") + "'"
}
