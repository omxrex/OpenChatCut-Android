package com.openchatcut.android.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.openchatcut.android.model.MediaAsset
import com.openchatcut.android.model.MediaKind
import com.openchatcut.android.util.Json
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/** Real media import: copies bytes from SAF, probes with ffprobe and generates a real thumbnail. */
object MediaImporter {

    data class Imported(val asset: MediaAsset, val error: String?)

    fun import(context: Context, uri: Uri, projectFps: Int = 30): Imported {
        val name = displayName(context, uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = context.contentResolver.getType(uri).orEmpty()
        val kind = when {
            mime.startsWith("audio/") || ext in setOf("mp3", "wav", "m4a", "aac", "flac", "ogg") -> MediaKind.AUDIO
            mime.startsWith("image/") || ext in setOf("png", "jpg", "jpeg", "webp") -> MediaKind.IMAGE
            else -> MediaKind.VIDEO
        }
        val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        var target = File(mediaDir, safeName)
        var counter = 1
        while (target.exists()) {
            target = File(mediaDir, "${safeName.substringBeforeLast('.', safeName)}_${counter++}.${target.extension}")
        }

        return try {
            context.contentResolver.openInputStream(uri).use { input ->
                FileOutputStream(target).use { output -> input?.copyTo(output) ?: error("Cannot open $uri") }
            }
            if (target.length() == 0L) error("Imported file is empty")

            val probe = FfmpegRunner.probe(target.absolutePath)
            val durationSec = Json.optDouble(probe.optJSONObject("format"), "duration", 0.0)
            val videoStream = probe.optJSONArray("streams")?.let { streams ->
                (0 until streams.length()).map { streams.getJSONObject(it) }
                    .firstOrNull { it.optString("codec_type") == "video" }
            }
            val hasAudio = probe.optJSONArray("streams")?.let { streams ->
                (0 until streams.length()).any { streams.getJSONObject(it).optString("codec_type") == "audio" }
            } ?: false

            val width = Json.optInt(videoStream, "width", 0)
            val height = Json.optInt(videoStream, "height", 0)
            val fps = parseRate(Json.optString(videoStream, "r_frame_rate", "0/0")).takeIf { it > 0 } ?: projectFps.toDouble()
            val durationMs = (durationSec * 1000).roundToInt().toLong()

            val thumbs = File(mediaDir, "thumbs").apply { mkdirs() }
            val thumb = File(thumbs, "${target.nameWithoutExtension}.jpg")
            val thumbResult = if (kind == MediaKind.IMAGE) {
                FfmpegRunner.run(
                    "-y", "-v", "error", "-i", target.absolutePath,
                    "-frames:v", "1", "-vf", "scale=320:-2", thumb.absolutePath,
                )
            } else if (durationSec > 0.1) {
                FfmpegRunner.run(
                    "-y", "-v", "error", "-ss", (durationSec / 2).toString(),
                    "-i", target.absolutePath, "-frames:v", "1", "-vf", "scale=320:-2",
                    thumb.absolutePath,
                )
            } else {
                FfmpegResult(false, -1, "no duration", emptyList())
            }

            val asset = MediaAsset(
                name = name,
                path = target.absolutePath,
                kind = kind,
                durationMs = durationMs,
                durationInFrames = (durationSec * projectFps).roundToInt(),
                width = width, height = height, fps = fps,
                sizeBytes = target.length(),
                sourceFilename = name,
                thumbnailPath = if (thumbResult.success && thumb.exists()) thumb.absolutePath else "",
                hasAudio = hasAudio,
            )
            Imported(asset, null)
        } catch (e: Throwable) {
            target.delete()
            Imported(
                MediaAsset(name = name, path = target.absolutePath, kind = kind, durationMs = 0),
                e.message ?: e::class.java.simpleName,
            )
        }
    }

    /** Import an already-local path (used by the CLI test harness and ACTION_VIEW intents). */
    fun importPath(context: Context, path: String, fps: Int = 30): Imported {
        val file = File(path)
        if (!file.exists()) return Imported(
            MediaAsset(name = file.name, path = path, kind = MediaKind.VIDEO, durationMs = 0), "File does not exist: $path"
        )
        return import(context, Uri.fromFile(file), fps)
    }

    private fun displayName(context: Context, uri: Uri): String {
        var result: String? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) result = c.getString(i)
            }
        }
        return result ?: uri.lastPathSegment ?: "media"
    }

    private fun parseRate(rate: String): Double {
        val parts = rate.split("/")
        if (parts.size != 2) return rate.toDoubleOrNull() ?: 0.0
        val num = parts[0].toDoubleOrNull() ?: return 0.0
        val den = parts[1].toDoubleOrNull() ?: return 0.0
        return if (den == 0.0) 0.0 else num / den
    }
}
