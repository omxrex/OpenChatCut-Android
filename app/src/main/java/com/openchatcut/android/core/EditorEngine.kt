package com.openchatcut.android.core

import com.openchatcut.android.media.MediaAnalysis
import com.openchatcut.android.media.MediaAnalysisResult
import com.openchatcut.android.model.CaptionCue
import com.openchatcut.android.model.MediaKind
import com.openchatcut.android.model.ProjectDoc
import com.openchatcut.android.model.TimelineItem
import com.openchatcut.android.model.TimelineState
import com.openchatcut.android.model.TransitionItem
import com.openchatcut.android.model.TransitionType
import com.openchatcut.android.model.splitItem
import com.openchatcut.android.model.timelineDurationFrames
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class EditResult(val ok: Boolean, val message: String, val changed: Boolean = false)

/** Stateless command layer mirroring the upstream EditorCore: every mutation returns a new document. */
class EditorEngine(initial: ProjectDoc) {
    private val undoStack = ArrayDeque<ProjectDoc>()
    private val redoStack = ArrayDeque<ProjectDoc>()
    var project: ProjectDoc = initial
        private set

    fun snapshot(): ProjectDoc = project

    private fun commit(next: ProjectDoc, message: String, changed: Boolean): EditResult {
        if (!changed) return EditResult(false, message, false)
        undoStack.addLast(project)
        if (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
        project = next
        return EditResult(true, message, true)
    }

    fun undo(): EditResult {
        val previous = undoStack.pollLast() ?: return EditResult(false, "Nothing to undo")
        redoStack.addLast(project)
        project = previous
        return EditResult(true, "Undid last edit", true)
    }

    fun redo(): EditResult {
        val next = redoStack.pollLast() ?: return EditResult(false, "Nothing to redo")
        undoStack.addLast(project)
        project = next
        return EditResult(true, "Redid edit", true)
    }

    private fun withTimeline(fn: (TimelineState) -> TimelineState): TimelineState = fn(project.timeline)

    fun addToTimeline(assetId: String, atFrame: Int? = null): EditResult {
        val asset = project.assets.firstOrNull { it.id == assetId }
            ?: return EditResult(false, "Asset $assetId not found")
        val track = when (asset.kind) {
            MediaKind.AUDIO -> "A1"
            MediaKind.IMAGE -> "V2"
            else -> "V1"
        }
        val fps = project.timeline.fps
        val frames = if (asset.durationInFrames > 0) asset.durationInFrames
        else (asset.durationMs * fps / 1000.0).roundToInt()
        val duration = max(1, frames)
        // Append at the true end of placed content. timelineDurationFrames() enforces a
        // minimum of 1s for rendering, which would otherwise insert a black gap before
        // the very first clip.
        val start = atFrame ?: (project.timeline.items.maxOfOrNull { it.startFrame + it.durationInFrames } ?: 0)
        val item = TimelineItem(
            name = asset.name, assetId = asset.id, track = track,
            startFrame = start, durationInFrames = duration,
        )
        val next = project.copy(
            timeline = withTimeline { it.copy(items = it.items + item, selectedId = item.id) }
        )
        return commit(next, "Added ${asset.name} to $track", true)
    }

    fun split(itemId: String, atFrame: Int): EditResult {
        val item = project.timeline.items.firstOrNull { it.id == itemId }
            ?: return EditResult(false, "Clip $itemId not found")
        val pair = splitItem(item, atFrame, project.timeline.fps)
            ?: return EditResult(false, "Split point must be strictly inside the clip")
        val items = project.timeline.items.flatMap { when (it.id) {
            itemId -> listOf(pair.first, pair.second)
            else -> listOf(it)
        } }
        return commit(
            project.copy(timeline = project.timeline.copy(items = items, selectedId = pair.second.id)),
            "Split clip at frame $atFrame", true,
        )
    }

    fun remove(itemId: String): EditResult {
        val exists = project.timeline.items.any { it.id == itemId }
        if (!exists) return EditResult(false, "Clip $itemId not found")
        val next = project.copy(
            timeline = project.timeline.copy(
                items = project.timeline.items.filter { it.id != itemId },
                transitions = project.timeline.transitions.filter {
                    it.outgoingItemId != itemId && it.incomingItemId != itemId
                },
                selectedId = null,
            )
        )
        return commit(next, "Removed clip", true)
    }

    fun move(itemId: String, startFrame: Int, track: String? = null): EditResult {
        val item = project.timeline.items.firstOrNull { it.id == itemId }
            ?: return EditResult(false, "Clip $itemId not found")
        val target = max(0, startFrame)
        if (item.startFrame == target && (track == null || item.track == track)) {
            return EditResult(false, "Clip already at that position")
        }
        val items = project.timeline.items.map {
            if (it.id == itemId) it.copy(startFrame = target, track = track ?: it.track) else it
        }
        return commit(project.copy(timeline = project.timeline.copy(items = items)), "Moved clip", true)
    }

    fun trim(itemId: String, startFrame: Int, durationInFrames: Int, srcInFrame: Int? = null): EditResult {
        val item = project.timeline.items.firstOrNull { it.id == itemId }
            ?: return EditResult(false, "Clip $itemId not found")
        if (durationInFrames < 1) return EditResult(false, "Duration must be positive")
        val newSrc = max(0, srcInFrame ?: item.srcInFrame)
        val items = project.timeline.items.map {
            if (it.id == itemId) it.copy(
                startFrame = max(0, startFrame), durationInFrames = durationInFrames, srcInFrame = newSrc
            ) else it
        }
        return commit(project.copy(timeline = project.timeline.copy(items = items)), "Trimmed clip", true)
    }

    fun setRate(itemId: String, rate: Double): EditResult {
        if (rate !in 0.25..4.0) return EditResult(false, "Playback rate out of range")
        val item = project.timeline.items.firstOrNull { it.id == itemId }
            ?: return EditResult(false, "Clip $itemId not found")
        val items = project.timeline.items.map {
            if (it.id == itemId) it.copy(playbackRate = rate) else it
        }
        return commit(project.copy(timeline = project.timeline.copy(items = items)), "Set speed to ${rate}x", true)
    }

    /** Upstream set_item_timing semantics: optional start/duration/fades and ripple. */
    fun retime(
        itemId: String,
        startFrame: Int? = null,
        durationInFrames: Int? = null,
        fadeInSeconds: Double? = null,
        fadeOutSeconds: Double? = null,
        ripple: Boolean = false,
    ): EditResult {
        val item = project.timeline.items.firstOrNull { it.id == itemId }
            ?: return EditResult(false, "Clip $itemId not found")
        val fps = project.timeline.fps
        val newStart = max(0, startFrame ?: item.startFrame)
        val newDuration = max(1, durationInFrames ?: item.durationInFrames)
        val fin = fadeInSeconds?.let { max(0, (it * fps).roundToInt()) } ?: item.fadeInFrames
        val fout = fadeOutSeconds?.let { max(0, (it * fps).roundToInt()) } ?: item.fadeOutFrames
        val delta = (newStart + newDuration) - (item.startFrame + item.durationInFrames)

        val items = project.timeline.items.map { current ->
            if (current.id == itemId) {
                current.copy(
                    startFrame = newStart, durationInFrames = newDuration,
                    fadeInFrames = min(fin, newDuration / 2), fadeOutFrames = min(fout, newDuration / 2),
                )
            } else if (ripple && delta != 0 && current.track == item.track && current.startFrame >= item.startFrame + item.durationInFrames) {
                current.copy(startFrame = max(0, current.startFrame + delta))
            } else current
        }
        return commit(
            project.copy(timeline = project.timeline.copy(items = items)),
            "Retimed clip (${newDuration} frames)", true,
        )
    }

    fun duplicate(itemId: String): EditResult {
        val item = project.timeline.items.firstOrNull { it.id == itemId }
            ?: return EditResult(false, "Clip $itemId not found")
        val end = project.timeline.items.filter { it.track == item.track }
            .maxOfOrNull { it.startFrame + it.durationInFrames } ?: item.startFrame
        val copy = item.copy(id = java.util.UUID.randomUUID().toString(), name = "${item.name} copy", startFrame = end)
        return commit(
            project.copy(timeline = project.timeline.copy(items = project.timeline.items + copy, selectedId = copy.id)),
            "Duplicated clip", true,
        )
    }

    fun removeWithRipple(itemId: String, ripple: Boolean): EditResult {
        val item = project.timeline.items.firstOrNull { it.id == itemId }
            ?: return EditResult(false, "Clip $itemId not found")
        var items = project.timeline.items.filter { it.id != itemId }
        if (ripple) {
            items = items.map {
                if (it.track == item.track && it.startFrame >= item.startFrame) {
                    it.copy(startFrame = max(0, it.startFrame - item.durationInFrames))
                } else it
            }
        }
        val next = project.copy(
            timeline = project.timeline.copy(
                items = items,
                transitions = project.timeline.transitions.filter {
                    it.outgoingItemId != itemId && it.incomingItemId != itemId
                },
                selectedId = null,
            )
        )
        return commit(next, if (ripple) "Ripple-deleted clip" else "Removed clip", true)
    }

    /** Rebuild one clip by removing a set of local frame ranges [from, to). */
    fun removeRanges(itemId: String, ranges: List<Pair<Int, Int>>): EditResult {
        if (ranges.isEmpty()) return EditResult(false, "No ranges to remove")
        val clip = project.timeline.items.firstOrNull { it.id == itemId }
            ?: return EditResult(false, "Clip $itemId not found")
        val merged = mutableListOf<Pair<Int, Int>>()
        for (range in ranges.sortedBy { it.first }) {
            val from = max(0, min(clip.durationInFrames, range.first))
            val to = max(from, min(clip.durationInFrames, range.second))
            if (to <= from) continue
            val last = merged.lastOrNull()
            if (last != null && from <= last.second) merged[merged.lastIndex] = last.first to max(last.second, to)
            else merged += from to to
        }
        if (merged.isEmpty()) return EditResult(false, "No valid ranges to remove")

        val kept = mutableListOf<TimelineItem>()
        var cursor = 0
        for ((from, to) in merged) {
            if (from > cursor) kept += clip.copy(
                id = java.util.UUID.randomUUID().toString(),
                startFrame = clip.startFrame + cursor,
                durationInFrames = from - cursor,
                srcInFrame = clip.srcInFrame + cursor,
                fadeInFrames = if (cursor == 0) clip.fadeInFrames else 0,
                fadeOutFrames = 0,
            )
            cursor = max(cursor, to)
        }
        if (cursor < clip.durationInFrames) kept += clip.copy(
            id = java.util.UUID.randomUUID().toString(),
            startFrame = clip.startFrame + cursor,
            durationInFrames = clip.durationInFrames - cursor,
            srcInFrame = clip.srcInFrame + cursor,
            fadeInFrames = 0,
            fadeOutFrames = clip.fadeOutFrames,
        )
        if (kept.isEmpty()) return EditResult(false, "The ranges cover the whole clip; nothing would remain")

        var items = project.timeline.items.flatMap { if (it.id == itemId) kept else listOf(it) }
        val removedFrames = merged.sumOf { it.second - it.first }
        // Ripple: close the gap on the same track, as upstream remove_silence does.
        items = items.map {
            if (it.track == clip.track && it.startFrame >= clip.startFrame + clip.durationInFrames) {
                it.copy(startFrame = max(0, it.startFrame - removedFrames))
            } else it
        }
        val next = project.copy(timeline = project.timeline.copy(items = items))
        return commit(next, "Removed ${merged.size} segment(s) (${removedFrames} frames), ripple-closed", true)
    }

    /** Split a clip at several absolute frames in one undoable commit (scene detection apply=split). */
    fun splitAtFrames(itemId: String, frames: List<Int>): EditResult {
        val clip = project.timeline.items.firstOrNull { it.id == itemId }
            ?: return EditResult(false, "Clip $itemId not found")
        var pieces = listOf(clip)
        var splitCount = 0
        for (frame in frames.sorted().distinct()) {
            val updated = mutableListOf<TimelineItem>()
            for (piece in pieces) {
                val pair = splitItem(piece, frame, project.timeline.fps)
                if (pair == null) updated += piece else { updated += pair.first; updated += pair.second; splitCount++ }
            }
            pieces = updated
        }
        if (splitCount == 0) return EditResult(false, "No valid split frames inside the clip")
        val items = project.timeline.items.flatMap { if (it.id == itemId) pieces else listOf(it) }
        return commit(
            project.copy(timeline = project.timeline.copy(items = items)),
            "Split clip into ${pieces.size} pieces at real scene boundaries", true,
        )
    }

    /** Close gaps on a track while preserving clip order. */
    fun tighten(track: String): EditResult {
        val onTrack = project.timeline.items.filter { it.track == track }.sortedBy { it.startFrame }
        if (onTrack.size < 2) return EditResult(false, "Track $track has fewer than two clips")
        var cursor = onTrack.first().startFrame
        val updates = onTrack.map {
            val updated = it.copy(startFrame = cursor)
            cursor += it.durationInFrames
            updated
        }
        val map = updates.associateBy { it.id }
        val items = project.timeline.items.map { map[it.id] ?: it }
        return commit(project.copy(timeline = project.timeline.copy(items = items)), "Tightened $track", true)
    }

    /** Pack clips of one track in caller-supplied order. */
    fun reorder(track: String, ids: List<String>): EditResult {
        val onTrack = project.timeline.items.filter { it.track == track }
        if (ids.size != onTrack.size || ids.toSet() != onTrack.map { it.id }.toSet()) {
            return EditResult(false, "itemIds must list every clip on $track exactly once")
        }
        val byId = onTrack.associateBy { it.id }
        val firstStart = onTrack.minOf { it.startFrame }
        var cursor = firstStart
        val updates = ids.map { id ->
            val updated = byId.getValue(id).copy(startFrame = cursor)
            cursor += updated.durationInFrames
            updated
        }
        val map = updates.associateBy { it.id }
        val items = project.timeline.items.map { map[it.id] ?: it }
        return commit(project.copy(timeline = project.timeline.copy(items = items)), "Reordered $track", true)
    }

    /** Selection is UI state, not an undoable edit. */
    fun select(itemId: String?) {
        project = project.copy(timeline = project.timeline.copy(selectedId = itemId))
    }

    fun addTransition(outgoingId: String, incomingId: String, type: TransitionType, frames: Int): EditResult {
        val items = project.timeline.items
        val outgoing = items.firstOrNull { it.id == outgoingId }
            ?: return EditResult(false, "Outgoing clip not found")
        val incoming = items.firstOrNull { it.id == incomingId }
            ?: return EditResult(false, "Incoming clip not found")
        if (outgoing.track != incoming.track) return EditResult(false, "Transition requires the same track")
        val maxFrames = min(outgoing.durationInFrames, incoming.durationInFrames) - 1
        if (maxFrames < 1) return EditResult(false, "Clips are too short for a transition")
        val length = min(maxFrames, max(1, frames))
        val transition = TransitionItem(
            type = type, trackId = outgoing.track,
            outgoingItemId = outgoing.id, incomingItemId = incoming.id,
            durationInFrames = length,
        )
        val next = project.copy(
            timeline = project.timeline.copy(transitions = project.timeline.transitions + transition)
        )
        return commit(next, "Added ${type.name} transition", true)
    }

    fun setCaptions(enabled: Boolean, cues: List<CaptionCue>): EditResult {
        val next = project.copy(
            timeline = project.timeline.copy(
                captions = project.timeline.captions.copy(enabled = enabled, cues = cues)
            )
        )
        return commit(next, "Set ${cues.size} real subtitle cues", true)
    }

    fun setAspect(width: Int, height: Int): EditResult {
        if (width < 64 || height < 64) return EditResult(false, "Invalid canvas size")
        return commit(project.copy(timeline = project.timeline.copy(width = width, height = height)), "Canvas ${width}x$height", true)
    }

    /**
     * "Remove boring parts": uses real measured motion + detected silence.
     * Produces actual split/delete operations on the timeline.
     */
    fun removeBoringParts(analysis: MediaAnalysisResult, intensity: Double = 0.35): EditResult {
        val asset = project.assets.firstOrNull { it.id == analysis.assetId }
            ?: return EditResult(false, "Analysis asset is not in the project")
        val clip = project.timeline.items.firstOrNull { it.assetId == asset.id && it.track.startsWith("V") }
            ?: return EditResult(false, "No video clip uses the analyzed asset")
        val fps = project.timeline.fps
        val totalFrames = clip.durationInFrames
        val windowFrames = fps * 2
        val candidateWindows = mutableListOf<Pair<Int, Int>>()
        var f = 0
        while (f + windowFrames <= totalFrames) {
            val startMs = (clip.srcInFrame + f).toLong() * 1000 / fps
            val endMs = (clip.srcInFrame + f + windowFrames).toLong() * 1000 / fps
            val score = MediaAnalysis.windowScore(analysis, startMs, endMs)
            val quiet = analysis.silence.any { it.startMs <= endMs && it.endMs >= startMs && it.endMs - it.startMs >= 600 }
            if (score < intensity && (quiet || analysis.motion.isNotEmpty())) candidateWindows += f to f + windowFrames
            f += windowFrames
        }
        if (candidateWindows.isEmpty()) return EditResult(false, "No low-energy segments found above the threshold")

        // Work on local frame ranges, merge adjacent windows, then remove from the end backwards.
        val merged = mutableListOf<Pair<Int, Int>>()
        for (w in candidateWindows.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && w.first == last.second) merged[merged.lastIndex] = last.first to w.second
            else merged += w
        }
        // Rebuild the clip from the ranges we keep. Each kept range is a real TimelineItem
        // pointing into the same source file with a shifted srcInFrame.
        val kept = mutableListOf<TimelineItem>()
        var cursor = 0
        for ((from, to) in merged) {
            if (from > cursor) {
                kept += clip.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    startFrame = clip.startFrame + cursor,
                    durationInFrames = from - cursor,
                    srcInFrame = clip.srcInFrame + cursor,
                    fadeInFrames = if (cursor == 0) clip.fadeInFrames else 0,
                    fadeOutFrames = 0,
                )
            }
            cursor = max(cursor, to)
        }
        if (cursor < totalFrames) {
            kept += clip.copy(
                id = java.util.UUID.randomUUID().toString(),
                startFrame = clip.startFrame + cursor,
                durationInFrames = totalFrames - cursor,
                srcInFrame = clip.srcInFrame + cursor,
                fadeInFrames = 0,
                fadeOutFrames = clip.fadeOutFrames,
            )
        }
        if (kept.isEmpty()) return EditResult(false, "All segments were below the threshold; nothing removed")
        val items = project.timeline.items.flatMap { if (it.id == clip.id) kept else listOf(it) }
        val next = project.copy(timeline = project.timeline.copy(items = items))
        return commit(next, "Removed ${merged.size} low-energy segment(s) from the real timeline", true)
    }

    /**
     * "Make Minecraft montage" style command: select the highest-motion real source windows,
     * build a new cut timeline and render-ready transitions. Nothing is fabricated.
     */
    fun buildMontage(
        analyses: List<MediaAnalysisResult>,
        maxDurationSec: Int = 30,
        segmentSec: Int = 3,
        segments: Int = 8,
        withTransitions: Boolean = true,
    ): EditResult {
        val fps = project.timeline.fps
        data class Candidate(val assetId: String, val startMs: Long, val score: Double)
        val candidates = mutableListOf<Candidate>()
        for (analysis in analyses) {
            if (analysis.motion.isEmpty()) continue
            val windowMs = segmentSec * 1000L
            var ms = 0L
            while (ms + windowMs <= analysis.durationMs) {
                val score = MediaAnalysis.windowScore(analysis, ms, ms + windowMs)
                // Penalise windows dominated by detected silence.
                val quietRatio = analysis.silence
                    .filter { it.endMs > ms && it.startMs < ms + windowMs }
                    .sumOf { min(it.endMs, ms + windowMs) - max(it.startMs, ms) }
                    .toDouble() / windowMs
                candidates += Candidate(analysis.assetId, ms, score * (1.0 - 0.35 * quietRatio))
                ms += windowMs / 2 // overlapping search windows
            }
        }
        if (candidates.isEmpty()) return EditResult(false, "No analysable footage found for a montage")

        val wanted = min(segments, candidates.size)
        val pick = candidates.sortedByDescending { it.score }
            .take(wanted)
            .sortedBy { it.startMs }

        val items = mutableListOf<TimelineItem>()
        var cursor = 0
        for ((index, c) in pick.withIndex()) {
            val asset = project.assets.firstOrNull { it.id == c.assetId } ?: continue
            val sourceFrames = (c.startMs * fps / 1000.0).roundToInt()
            val frames = min(segmentSec * fps, max(1, asset.durationInFrames - sourceFrames))
            if (frames < fps / 2) continue
            items += TimelineItem(
                name = "${asset.name} · highlight ${index + 1}",
                assetId = asset.id,
                track = "V1",
                startFrame = cursor,
                durationInFrames = frames,
                srcInFrame = sourceFrames,
                volume = if (asset.hasAudio) 1.0 else 0.0,
            )
            cursor += frames
        }
        if (items.isEmpty()) return EditResult(false, "No usable segments found")

        val transitions = if (withTransitions && items.size > 1) {
            val length = min(fps / 2, items.minOf { it.durationInFrames } / 3).coerceAtLeast(1)
            items.zipWithNext().map { (a, b) ->
                TransitionItem(
                    type = TransitionType.CROSS_DISSOLVE,
                    outgoingItemId = a.id, incomingItemId = b.id,
                    durationInFrames = length,
                )
            }
        } else emptyList()

        // Enforce the requested total length by trimming the last clip (real edit).
        val maxFrames = maxDurationSec * fps
        var trimmed: List<TimelineItem> = items
        if (cursor > maxFrames && trimmed.isNotEmpty()) {
            val last = trimmed.last()
            val excess = cursor - maxFrames
            val newDuration = last.durationInFrames - excess
            if (newDuration > fps / 4) {
                trimmed = trimmed.dropLast(1) + last.copy(durationInFrames = newDuration)
            } else {
                trimmed = trimmed.dropLast(1)
            }
        }
        val validIds = trimmed.map { it.id }.toSet()
        val validTransitions = transitions.filter { it.outgoingItemId in validIds && it.incomingItemId in validIds }

        return commit(
            project.copy(
                timeline = project.timeline.copy(
                    items = trimmed, transitions = validTransitions, captions = project.timeline.captions.copy(cues = emptyList()),
                    selectedId = null,
                )
            ),
            "Built ${trimmed.size}-clip montage from analysed footage", true,
        )
    }

    /** "Make a 30-second Short": produce a timeline whose rendered length is exactly N seconds. */
    fun fitToDuration(seconds: Int): EditResult {
        val fps = project.timeline.fps
        val target = seconds * fps
        val items = project.timeline.items.sortedBy { it.startFrame }.toMutableList()
        if (items.isEmpty()) return EditResult(false, "Timeline is empty")
        var total = items.maxOf { it.startFrame + it.durationInFrames }
        if (total == 0) return EditResult(false, "Timeline has zero duration")

        if (total > target) {
            var excess = total - target
            var index = items.lastIndex
            while (excess > 0 && index >= 0) {
                val item = items[index]
                if (item.durationInFrames > fps / 4) {
                    val cut = min(excess, item.durationInFrames - fps / 4)
                    items[index] = item.copy(durationInFrames = item.durationInFrames - cut)
                    excess -= cut
                }
                index--
            }
            if (excess > 0) {
                // Remove whole clips from the end.
                var removed = 0
                while (excess > 0 && items.size > 1) {
                    val last = items.removeAt(items.lastIndex)
                    excess -= last.durationInFrames
                    removed++
                }
                if (excess > 0) return EditResult(false, "Not enough material for ${seconds}s")
            }
            // Compact to remove gaps while keeping order.
            var cursor = 0
            val compacted = items.map {
                val updated = it.copy(startFrame = cursor)
                cursor += it.durationInFrames
                updated
            }
            return commit(
                project.copy(timeline = project.timeline.copy(items = compacted)),
                "Fitted timeline to ${seconds}s", true,
            )
        } else if (total < target) {
            // Loop the available material to fill the requested duration (real repeated clips).
            val original = items.map { it.copy() }
            var cursor = total
            var loops = 0
            while (cursor < target && loops < 20) {
                for (base in original) {
                    if (cursor >= target) break
                    val frames = min(base.durationInFrames, target - cursor)
                    items += base.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "${base.name} (loop ${loops + 1})",
                        startFrame = cursor,
                        durationInFrames = frames,
                        srcInFrame = base.srcInFrame,
                    )
                    cursor += frames
                }
                loops++
            }
            return commit(
                project.copy(timeline = project.timeline.copy(items = items)),
                "Extended timeline to ${seconds}s", true,
            )
        }
        return EditResult(false, "Timeline is already ${seconds}s")
    }
}
