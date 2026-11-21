package com.openchatcut.android.media

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.Log
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.openchatcut.android.util.Json
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class FfmpegResult(
    val success: Boolean,
    val exitCode: Int,
    val logs: String,
    val statistics: List<Statistics>,
)

/** Thin wrapper around the real FFmpegKit native engine. No simulated execution or progress. */
object FfmpegRunner {

    fun initialize(context: Context) {
        // libass/subtitles and drawtext need a real font directory.
        val fontDir = File(context.filesDir, "fonts").apply { mkdirs() }
        FFmpegKitConfig.setFontDirectory(context, fontDir.absolutePath, emptyMap())
        FFmpegKitConfig.setFontDirectory(context, "/system/fonts", emptyMap())
    }

    fun version(): String = runCatching { FFmpegKitConfig.getFFmpegVersion() }.getOrDefault("unknown")

    fun run(
        vararg args: String,
        onLog: ((String) -> Unit)? = null,
        onStats: ((Statistics) -> Unit)? = null,
        timeoutMs: Long = 0,
    ): FfmpegResult {
        val stats = mutableListOf<Statistics>()
        val latch = CountDownLatch(1)
        var sessionRef: FFmpegSession? = null
        sessionRef = FFmpegKit.executeWithArgumentsAsync(
            args,
            { sessionRef = it; latch.countDown() },
            { log: Log? -> log?.message?.let { msg -> onLog?.invoke(msg) } },
            { s: Statistics? -> if (s != null) { stats += s; onStats?.invoke(s) } },
        )
        val completed = if (timeoutMs > 0) latch.await(timeoutMs, TimeUnit.MILLISECONDS) else { latch.await(); true }
        val session = sessionRef ?: FFmpegKit.listSessions().lastOrNull()
        if (!completed || session == null) {
            session?.let { FFmpegKit.cancel(it.sessionId) }
            return FfmpegResult(false, -1, "ffmpeg timed out after ${timeoutMs}ms", stats)
        }
        val code = session.returnCode?.value ?: -1
        val logs = session.allLogsAsString.orEmpty()
        return FfmpegResult(ReturnCode.isSuccess(session.returnCode), code, logs, stats)
    }

    fun runChecked(vararg args: String, timeoutMs: Long = 0): String {
        val result = run(*args, timeoutMs = timeoutMs)
        if (!result.success) {
            throw RuntimeException(
                "ffmpeg failed (exit ${result.exitCode}): ${result.logs.takeLast(3000)}"
            )
        }
        return result.logs
    }

    /** ffprobe -print_format json -show_streams -show_format (same flags as upstream probe-media.ts). */
    fun probe(path: String): JSONObject {
        val session = FFprobeKit.executeWithArguments(
            arrayOf("-v", "error", "-print_format", "json", "-show_streams", "-show_format", path)
        )
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw RuntimeException("ffprobe failed (exit ${session.returnCode?.value}): ${session.allLogsAsString?.takeLast(2000)}")
        }
        val output = session.output ?: throw RuntimeException("ffprobe returned no output")
        return Json.parseObject(output)
    }

    /** FFmpeg filter-graph escaping for a literal file path. */
    fun ffFilterPath(path: String): String = path
        .replace("\\", "\\\\")
        .replace(":", "\\:")
        .replace("'", "'\\''")
        .replace("[", "\\[")
        .replace("]", "\\]")
        .replace(",", "\\,")
}
