package com.openchatcut.android.core

import com.openchatcut.android.model.CaptionCue
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Faithful Kotlin ports of the upstream pure algorithms.
 * - upstream/src/scene-detection/detect.ts
 * - upstream/src/audio/silence.ts (RMS candidate stage)
 * - upstream/src/captions/types.ts (pagination constants and phrase packing)
 */

data class RawSceneCandidate(val timeMs: Long, val score: Double)
data class SceneChange(val timeMs: Long, val score: Double, val kind: String)

const val DEFAULT_SCENE_THRESHOLD = 0.3
const val DEFAULT_MIN_SCENE_MS = 750
const val DEFAULT_MAX_SCENES = 200

fun normalizeSceneThreshold(value: Double?): Double {
    if (value == null || value.isNaN()) return DEFAULT_SCENE_THRESHOLD
    return min(0.95, max(0.05, value))
}

/** Parse FFmpeg `metadata=print` output: pts_time + lavfi.scene_score pairs. */
fun parseSceneMetadata(text: String): List<RawSceneCandidate> {
    val timeRegex = Regex("\\bpts_time:([-+\\d.eE]+)")
    val scoreRegex = Regex("\\blavfi\\.scene_score=([-+\\d.eE]+)")
    val out = mutableListOf<RawSceneCandidate>()
    var timeMs: Long? = null
    for (line in text.lineSequence()) {
        val t = timeRegex.find(line)
        if (t != null) {
            val seconds = t.groupValues[1].toDoubleOrNull()
            timeMs = if (seconds != null && !seconds.isNaN()) (seconds * 1000).roundToInt().toLong() else null
            continue
        }
        val s = scoreRegex.find(line) ?: continue
        val currentTime = timeMs ?: continue
        val score = s.groupValues[1].toDoubleOrNull() ?: continue
        out += RawSceneCandidate(currentTime, score)
        timeMs = null
    }
    return out
}

/** Sort, sanitise and de-bounce candidates exactly like the upstream implementation. */
fun normalizeSceneCandidates(
    input: List<RawSceneCandidate>,
    threshold: Double = DEFAULT_SCENE_THRESHOLD,
    minSceneMs: Int = DEFAULT_MIN_SCENE_MS,
    durationMs: Long? = null,
    maxScenes: Int = DEFAULT_MAX_SCENES,
): List<SceneChange> {
    val t = normalizeSceneThreshold(threshold)
    val minMs = max(100, minSceneMs)
    val duration = durationMs ?: Long.MAX_VALUE
    val max = max(1, min(500, maxScenes))
    val sorted = input
        .asSequence()
        .filter { it.timeMs >= minMs && it.timeMs <= duration - minMs && it.score >= t }
        .sortedWith(compareBy<RawSceneCandidate> { it.timeMs }.thenByDescending { it.score })
        .toList()

    val merged = mutableListOf<RawSceneCandidate>()
    for (c in sorted) {
        val prev = merged.lastOrNull()
        if (prev == null || c.timeMs - prev.timeMs >= minMs) {
            merged += c
        } else if (c.score > prev.score) {
            merged[merged.lastIndex] = c
        }
    }
    return merged.take(max).map { c ->
        val hardCut = max(0.4, t * 1.25)
        SceneChange(c.timeMs, c.score, if (c.score + Double.MIN_VALUE >= hardCut) "cut" else "transition")
    }
}

// ── Silence (upstream RMS stage) ──────────────────────────────────────────────

data class SilenceSpan(val startMs: Long, val endMs: Long)
data class SilenceParams(
    val thresholdDb: Double = -26.0,
    val minSilenceMs: Int = 600,
    val padMs: Int = 150,
)

private const val SIGNAL_REFERENCE_FLOOR_DB = -45.0
private const val ABSOLUTE_SILENCE_DB = -35.0

fun toDb(rms: Double): Double = 20 * log10(max(rms, 1e-8))

/** Windowed RMS envelope with non-overlapping hop = windowMs. */
fun rmsEnvelope(samples: FloatArray, sampleRate: Int, windowMs: Int = 50): FloatArray {
    if (samples.isEmpty() || sampleRate <= 0 || windowMs <= 0) return FloatArray(0)
    val win = max(1, (sampleRate.toDouble() * windowMs / 1000).roundToInt())
    val out = FloatArray(ceil(samples.size.toDouble() / win).toInt())
    for (w in out.indices) {
        val start = w * win
        val end = min(start + win, samples.size)
        var sum = 0.0
        for (i in start until end) sum += samples[i].toDouble() * samples[i]
        out[w] = sqrt(sum / (end - start)).toFloat()
    }
    return out
}

fun detectSilentSpans(
    env: FloatArray,
    windowMs: Int,
    params: SilenceParams = SilenceParams(),
): List<SilenceSpan> {
    if (env.isEmpty() || windowMs <= 0) return emptyList()
    val sorted = env.sorted()
    val idx = min(sorted.lastIndex, max(0, floor(0.9 * (sorted.size - 1)).toInt()))
    val signalRefDb = toDb(sorted[idx].toDouble())
    if (signalRefDb < SIGNAL_REFERENCE_FLOOR_DB) return emptyList()
    val gateDb = min(signalRefDb + params.thresholdDb, ABSOLUTE_SILENCE_DB)

    val spans = mutableListOf<SilenceSpan>()
    var runStart = -1
    for (w in 0..env.size) {
        val silent = w < env.size && toDb(env[w].toDouble()) < gateDb
        if (silent && runStart < 0) runStart = w
        if (!silent && runStart >= 0) {
            val startMs = runStart * windowMs + params.padMs.toLong()
            val endMs = w.toLong() * windowMs - params.padMs
            if (endMs - startMs >= params.minSilenceMs - 2 * params.padMs && endMs > startMs
                && w.toLong() * windowMs - runStart.toLong() * windowMs >= params.minSilenceMs
            ) {
                spans += SilenceSpan(startMs, endMs)
            }
            runStart = -1
        }
    }
    return spans
}

// ── Captions (upstream pagination constants) ──────────────────────────────────

const val MAX_PHRASE_WORDS = 6
const val LINGER_MS = 1500L
const val CAPTION_MAX_VISUAL_LINES = 2
const val CAPTION_MAX_CHARS_PER_LINE = 24

/** Pack transcript words into timed caption cues (upstream paginate + linger behaviour). */
fun paginateCaptions(words: List<CaptionCue>, maxWords: Int = MAX_PHRASE_WORDS): List<CaptionCue> {
    if (words.isEmpty()) return emptyList()
    val out = mutableListOf<CaptionCue>()
    var i = 0
    while (i < words.size) {
        var end = min(i + maxWords, words.size)
        // Do not exceed the two-line character budget.
        var chars = 0
        while (end > i + 1) {
            val candidate = words[end - 1].text.length + 1
            if (chars + candidate <= CAPTION_MAX_CHARS_PER_LINE * CAPTION_MAX_VISUAL_LINES) break
            end--
        }
        val group = words.subList(i, end)
        val last = group.last()
        val lingerEnd = if (end < words.size) min(last.endMs + LINGER_MS, words[end].startMs) else last.endMs + LINGER_MS
        out += CaptionCue(group.first().startMs, max(lingerEnd, last.endMs), group.joinToString(" ") { it.text })
        i = end
    }
    return out
}
