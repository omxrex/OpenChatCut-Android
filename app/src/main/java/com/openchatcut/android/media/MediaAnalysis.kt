package com.openchatcut.android.media

import android.content.Context
import com.arthenica.ffmpegkit.WhisperKit
import com.openchatcut.android.core.RawSceneCandidate
import com.openchatcut.android.core.SceneChange
import com.openchatcut.android.core.SilenceParams
import com.openchatcut.android.core.SilenceSpan
import com.openchatcut.android.core.detectSilentSpans
import com.openchatcut.android.core.normalizeSceneCandidates
import com.openchatcut.android.core.parseSceneMetadata
import com.openchatcut.android.core.rmsEnvelope
import com.openchatcut.android.model.CaptionCue
import com.openchatcut.android.model.MediaAsset
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

data class MotionWindow(val startMs: Long, val score: Double, val brightness: Double)

data class MediaAnalysisResult(
    val assetId: String,
    val durationMs: Long,
    val scenes: List<SceneChange>,
    val motion: List<MotionWindow>,
    val silence: List<SilenceSpan>,
    val transcript: List<CaptionCue> = emptyList(),
    val notes: List<String> = emptyList(),
)

/** Real footage analysis using FFmpeg scene scores, raw frame differences, RMS audio and Whisper ASR. */
object MediaAnalysis {

    const val MOTION_FPS = 2
    const val MOTION_W = 64
    const val MOTION_H = 36
    private const val WHISPER_MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin"

    fun whisperModel(context: Context): File = File(context.filesDir, "models/ggml-tiny.en.bin")

    fun downloadWhisperModel(context: Context, onProgress: ((Int) -> Unit)? = null): File {
        val target = whisperModel(context)
        if (target.exists() && target.length() > 10_000_000) return target
        target.parentFile?.mkdirs()
        val partial = File(target.absolutePath + ".part")
        val connection = URL(WHISPER_MODEL_URL).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 20000
        connection.readTimeout = 60000
        connection.connect()
        val total = connection.contentLength
        connection.inputStream.use { input ->
            partial.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var read: Int
                var downloaded = 0L
                while (input.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                    downloaded += read
                    if (total > 0) onProgress?.invoke((downloaded * 100 / total).toInt())
                }
            }
        }
        if (!partial.renameTo(target)) error("Cannot install Whisper model")
        return target
    }

    fun analyze(
        context: Context,
        asset: MediaAsset,
        threshold: Double = 0.3,
        minSceneMs: Int = 750,
        withAudio: Boolean = true,
        withTranscript: Boolean = false,
        onStage: ((String) -> Unit)? = null,
    ): MediaAnalysisResult {
        val work = File(context.filesDir, "analysis").apply { mkdirs() }
        val tag = asset.id.take(8)
        val notes = mutableListOf<String>()
        val durationMs = asset.durationMs

        onStage?.invoke("Detecting scene changes")
        val sceneFile = File(work, "scenes_$tag.txt")
        val sceneFilter = "fps=4,scale=320:-2,select=gt(scene\\,${threshold}),metadata=print:file=${sceneFile.absolutePath}"
        val sceneResult = FfmpegRunner.run(
            "-y", "-v", "error", "-i", asset.path, "-vf", sceneFilter, "-an", "-f", "null", "-",
        )
        val rawScenes = if (sceneResult.success && sceneFile.exists()) {
            parseSceneMetadata(sceneFile.readText())
        } else {
            notes += "Scene detection failed: ${sceneResult.logs.takeLast(400)}"
            emptyList()
        }
        val scenes = normalizeSceneCandidates(
            rawScenes.map { RawSceneCandidate(it.timeMs, it.score) },
            threshold = threshold,
            minSceneMs = minSceneMs,
            durationMs = durationMs,
        )

        onStage?.invoke("Measuring motion")
        val rgbFile = File(work, "motion_$tag.rgb")
        val frameBytes = MOTION_W * MOTION_H * 3
        val motionResult = FfmpegRunner.run(
            "-y", "-v", "error", "-i", asset.path,
            "-vf", "fps=$MOTION_FPS,scale=$MOTION_W:$MOTION_H",
            "-pix_fmt", "rgb24", "-f", "rawvideo", rgbFile.absolutePath,
        )
        val motion = if (motionResult.success && rgbFile.exists()) computeMotion(rgbFile, frameBytes, durationMs) else {
            notes += "Motion analysis failed: ${motionResult.logs.takeLast(400)}"
            emptyList()
        }

        val silence = mutableListOf<SilenceSpan>()
        if (withAudio && asset.hasAudio && durationMs > 0) {
            onStage?.invoke("Analyzing audio silence")
            val pcmFile = File(work, "audio_$tag.f32")
            val pcmResult = FfmpegRunner.run(
                "-y", "-v", "error", "-i", asset.path, "-vn", "-ac", "1", "-ar", "16000",
                "-f", "f32le", pcmFile.absolutePath,
            )
            if (pcmResult.success && pcmFile.exists()) {
                val bytes = pcmFile.readBytes()
                val samples = FloatArray(bytes.size / 4)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
                val env = rmsEnvelope(samples, 16000, 50)
                silence.addAll(detectSilentSpans(env, 50, SilenceParams()))
            } else {
                notes += "Audio analysis failed: ${pcmResult.logs.takeLast(400)}"
            }
        }

        var transcript = emptyList<CaptionCue>()
        if (withTranscript && asset.hasAudio) {
            val model = whisperModel(context)
            if (model.exists() && model.length() > 10_000_000) {
                onStage?.invoke("Transcribing with local Whisper")
                try {
                    val pcmFile = File(work, "audio_$tag.f32")
                    if (!pcmFile.exists()) {
                        FfmpegRunner.runChecked(
                            "-y", "-v", "error", "-i", asset.path, "-vn", "-ac", "1", "-ar", "16000",
                            "-f", "f32le", pcmFile.absolutePath,
                        )
                    }
                    val bytes = pcmFile.readBytes()
                    val samples = FloatArray(bytes.size / 4)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
                    WhisperKit.createFromFile(model.absolutePath).use { kit ->
                        val srt = kit.transcribeToSrt(samples, "en")
                        transcript = parseSrt(srt)
                    }
                } catch (e: Throwable) {
                    notes += "Local transcription failed: ${e.message}"
                }
            } else {
                notes += "Transcription needs the local Whisper model (download it in Settings)."
            }
        }

        return MediaAnalysisResult(asset.id, durationMs, scenes, motion, silence, transcript, notes)
    }

    private fun computeMotion(file: File, frameBytes: Int, durationMs: Long): List<MotionWindow> {
        val out = mutableListOf<MotionWindow>()
        val frameCount = file.length().toInt() / frameBytes
        if (frameCount < 2) return out
        var previous = ByteArray(frameBytes)
        val current = ByteArray(frameBytes)
        FileInputStream(file).use { input ->
            for (frame in 0 until frameCount) {
                var read = 0
                while (read < frameBytes) {
                    val n = input.read(current, read, frameBytes - read)
                    if (n < 0) break
                    read += n
                }
                if (read < frameBytes) break
                var diff = 0.0
                var bright = 0.0
                var i = 0
                while (i < frameBytes) {
                    val dr = abs(current[i].toInt() - previous[i].toInt())
                    val dg = abs(current[i + 1].toInt() - previous[i + 1].toInt())
                    val db = abs(current[i + 2].toInt() - previous[i + 2].toInt())
                    diff += (dr + dg + db) / 3.0
                    bright += (current[i].toInt() + current[i + 1].toInt() + current[i + 2].toInt()) / 3.0
                    i += 3
                }
                val pixels = frameBytes / 3
                out += MotionWindow(
                    startMs = frame.toLong() * 1000 / MOTION_FPS,
                    score = diff / pixels,
                    brightness = bright / pixels,
                )
                System.arraycopy(current, 0, previous, 0, frameBytes)
            }
        }
        return out
    }

    /** Parse SRT produced by WhKit into timed cues (same shape as upstream parseSrt). */
    private fun parseSrt(source: String): List<CaptionCue> {
        val time = Regex("(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})")
        val cues = mutableListOf<CaptionCue>()
        val normalized = source.replace("\r\n?", "\n").trim()
        for (block in normalized.split(Regex("\n[ \t]*\n+"))) {
            val lines = block.lines()
            val idx = lines.indexOfFirst { time.containsMatchIn(it) } ?: continue
            if (idx < 0) continue
            val m = time.find(lines[idx]) ?: continue
            val text = lines.drop(idx + 1).joinToString(" ") { it.trim() }.trim()
            if (text.isEmpty()) continue
            cues += CaptionCue(ms(m, 1, 2, 3, 4), ms(m, 5, 6, 7, 8), text)
        }
        return cues.sortedBy { it.startMs }
    }

    private fun ms(m: MatchResult, h: Int, min: Int, s: Int, frac: Int): Long {
        val fraction = m.groupValues[frac].padEnd(3, '0').take(3).toInt()
        return ((m.groupValues[h].toLong() * 3600) + (m.groupValues[min].toLong() * 60) + m.groupValues[s].toLong()) * 1000 + fraction
    }

    /** Re-run relative RMS silence detection with custom parameters on real decoded PCM. */
    fun analyzeSilence(
        context: Context,
        asset: MediaAsset,
        params: SilenceParams,
        onStage: ((String) -> Unit)? = null,
    ): List<SilenceSpan> {
        if (!asset.hasAudio) return emptyList()
        onStage?.invoke("Decoding audio for silence analysis")
        val work = File(context.filesDir, "analysis").apply { mkdirs() }
        val pcmFile = File(work, "silence_${asset.id.take(8)}.f32")
        val result = FfmpegRunner.run(
            "-y", "-v", "error", "-i", asset.path, "-vn", "-ac", "1", "-ar", "16000",
            "-f", "f32le", pcmFile.absolutePath,
        )
        if (!result.success || !pcmFile.exists()) return emptyList()
        val bytes = pcmFile.readBytes()
        val samples = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
        return detectSilentSpans(rmsEnvelope(samples, 16000, 50), 50, params)
    }

    fun transcriptToCues(srtText: String): List<CaptionCue> = parseSrt(srtText)

    /** Energy score 0..1 for a source-time window, derived from real measured motion. */
    fun windowScore(analysis: MediaAnalysisResult, startMs: Long, endMs: Long): Double {
        val windows = analysis.motion.filter { it.startMs in startMs until endMs }
        if (windows.isEmpty()) return 0.0
        val maxMotion = analysis.motion.maxOfOrNull { it.score } ?: 1.0
        return windows.sumOf { it.score / max(1e-6, maxMotion) } / windows.size
    }
}
