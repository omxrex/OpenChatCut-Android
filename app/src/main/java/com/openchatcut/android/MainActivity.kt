package com.openchatcut.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.openchatcut.android.ai.AgentController
import com.openchatcut.android.ai.OllamaClient
import com.openchatcut.android.ai.ToolOutcome
import com.openchatcut.android.core.EditorEngine
import com.openchatcut.android.core.ProjectStore
import com.openchatcut.android.core.SettingsStore
import com.openchatcut.android.media.MediaAnalysis
import com.openchatcut.android.media.MediaImporter
import com.openchatcut.android.media.TimelineRenderer
import com.openchatcut.android.model.MediaAsset
import com.openchatcut.android.model.MediaKind
import com.openchatcut.android.model.ProjectDoc
import com.openchatcut.android.model.TimelineItem
import com.openchatcut.android.model.TransitionType
import com.openchatcut.android.model.timelineDurationFrames
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var client: OllamaClient
    private lateinit var engine: EditorEngine
    private lateinit var agent: AgentController
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "openchatcut-ui-worker") }

    private lateinit var mediaList: LinearLayout
    private lateinit var timelineStrip: LinearLayout
    private lateinit var preview: VideoView
    private lateinit var seek: SeekBar
    private lateinit var seekLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var chatText: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var chatInput: EditText
    private lateinit var sendButton: Button
    private var playheadMs = 0L
    private var previewItemId: String? = null

    private val pickMedia = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        worker.execute {
            val imported = mutableListOf<MediaAsset>()
            val errors = mutableListOf<String>()
            uris.forEach { uri ->
                val result = MediaImporter.import(this@MainActivity, uri)
                if (result.error == null) imported += result.asset else errors += "${result.asset.name}: ${result.error}"
            }
            val added = imported.map { engine.addToTimeline(it.id).ok }.count { it }
            saveProject()
            post {
                refreshAll()
                setStatus("Imported ${imported.size} file(s), added $added to the timeline" +
                    if (errors.isNotEmpty()) " · errors: ${errors.joinToString("; ")}" else "")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.openchatcut.android.media.FfmpegRunner.initialize(this)
        settings = SettingsStore(this)
        client = OllamaClient(settings.baseUrl, settings.model)
        val project = ProjectStore.load(this) ?: ProjectDoc()
        engine = EditorEngine(project)
        agent = AgentController(this, engine, client, object : AgentController.Callbacks {
            override fun onStatus(text: String) { post { setStatus(text) } }
            override fun onDelta(text: String) { post { appendChat("", text) } }
            override fun onTool(name: String, outcome: ToolOutcome) {
                post {
                    appendChat("", "\n[tool:$name ${if (outcome.ok) "OK" else "FAILED"}] ${outcome.message}\n")
                    refreshAll()
                }
            }
            override fun onFinal(text: String) {
                post {
                    appendChat("Assistant", "\n$text\n")
                    sendButton.isEnabled = true
                    saveProject()
                }
            }
            override fun onError(text: String) {
                post {
                    appendChat("System", "\n$text\n")
                    sendButton.isEnabled = true
                    setStatus(text)
                }
            }
            override fun onProjectChanged() { post { refreshAll() } }
        })

        setContentView(buildWorkspace())
        requestPermissionsIfNeeded()
        handleIncomingIntent(intent)
        refreshAll()
        setStatus("Ready · FFmpeg ${com.openchatcut.android.media.FfmpegRunner.version()}")
    }

    // ── UI construction ────────────────────────────────────────────────────────────

    private fun buildWorkspace(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BG) }

        // Left: media pool
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(232), MATCH_PARENT)
            setBackgroundColor(PANEL)
        }
        left += sectionTitle("MEDIA POOL")
        left += button("+ Import media") { pickMedia.launch("*/*") }
        left += button("Download Whisper model") { downloadWhisper() }
        mediaList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left += ScrollView(this).apply {
            addView(mediaList, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        root += left
        root += separator()

        // Center: preview + timeline + controls
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
            setBackgroundColor(BG)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        preview = VideoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.BLACK)
        }
        val controller = MediaController(this).apply { setAnchorView(preview) }
        preview.setMediaController(controller)
        center += preview
        center += LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            seek = SeekBar(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                max = 1000
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val item = selectedVideoItem() ?: return
                        playheadMs = item.srcInFrame.toLong() * 1000 / engine.project.timeline.fps +
                            (p.toLong() * item.durationInFrames * 1000 / engine.project.timeline.fps / 1000)
                        preview.seekTo(playheadMs.toInt())
                        updateSeekLabel()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            addView(seek)
            seekLabel = TextView(this@MainActivity).apply {
                textColor = MUTED; textSize = 11f; layoutParams = LinearLayout.LayoutParams(dp(110), WRAP_CONTENT)
                gravity = Gravity.END
            }
            addView(seekLabel)
        }

        statusText = TextView(this).apply {
            textColor = ACCENT; textSize = 12f; maxLines = 2
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        center += statusText

        timelineStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
        center += HorizontalScrollView(this).apply {
            addView(timelineStrip, ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(74))
            setBackgroundColor(PANEL)
        }

        // Editing tools, two rows
        val edits1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        edits1 += button("Split") { splitAtPlayhead() }
        edits1 += button("Delete") { selectedItem()?.let { applyEdit(engine.removeWithRipple(it.id, false)) } }
        edits1 += button("Ripple del") { selectedItem()?.let { applyEdit(engine.removeWithRipple(it.id, true)) } }
        edits1 += button("Trim start") { trimAtPlayhead(true) }
        edits1 += button("Trim end") { trimAtPlayhead(false) }
        edits1 += button("Duplicate") { selectedItem()?.let { applyEdit(engine.duplicate(it.id)) } }
        center += edits1

        val edits2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        edits2 += button("Dissolve") { addTransition(TransitionType.CROSS_DISSOLVE) }
        edits2 += button("Wipe") { addTransition(TransitionType.SOFT_WIPE) }
        edits2 += button("9:16 Short") { applyEdit(engine.setAspect(1080, 1920)) }
        edits2 += button("16:9") { applyEdit(engine.setAspect(1920, 1080)) }
        edits2 += button("Captions on") { applyEdit(engine.setCaptions(true, engine.project.timeline.captions.cues)) }
        edits2 += button("Analyze") { analyzeSelected() }
        center += edits2

        val edits3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        edits3 += button("Montage") { runMontage() }
        edits3 += button("Cut boring") { removeBoring() }
        edits3 += button("Fit 30s") { applyEdit(engine.fitToDuration(30)) }
        edits3 += button("Undo") { applyEdit(engine.undo()) }
        edits3 += button("Redo") { applyEdit(engine.redo()) }
        edits3 += button("EXPORT MP4") { export() }
        center += edits3

        root += center
        root += separator()

        // Right: AI chat
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(330), MATCH_PARENT)
            setBackgroundColor(PANEL)
        }
        right += sectionTitle("LOCAL AI · OLLAMA")
        right += button("Settings / models") { showSettings() }
        chatText = TextView(this).apply {
            textColor = TEXT; textSize = 13f; movementMethod = ScrollingMovementMethod()
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        chatScroll = ScrollView(this).apply {
            addView(chatText, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            setBackgroundColor(BG)
        }
        right += chatScroll
        val composer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chatInput = EditText(this).apply {
            hint = "Ask the local model to edit…"
            textColor = TEXT; setHintTextColor(MUTED)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            minLines = 2; maxLines = 4
        }
        composer += chatInput
        right += composer
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sendButton = button("Send") {
            val text = chatInput.text.toString()
            if (text.isBlank()) return@button
            appendChat("You", "\n$text\n")
            chatInput.text.clear()
            sendButton.isEnabled = false
            agent.send(text)
        }
        actions += sendButton
        actions += button("Stop") { agent.stop() }
        actions += button("Clear") { agent.clearConversation(); chatText.text = "" }
        right += actions
        root += right
        return root
    }

    // ── Project / UI refresh ───────────────────────────────────────────────────────

    private fun refreshAll() {
        refreshMedia()
        refreshTimeline()
        updateSeekLabel()
    }

    private fun refreshMedia() {
        mediaList.removeAllViews()
        if (engine.project.assets.isEmpty()) {
            mediaList += TextView(this).apply { text = "No media yet.\nTap Import."; textColor = MUTED; textSize = 12f }
        }
        engine.project.assets.forEach { asset ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            val thumb = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(40))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.DKGRAY)
                if (asset.thumbnailPath.isNotEmpty() && File(asset.thumbnailPath).exists()) {
                    try { setImageBitmap(android.graphics.BitmapFactory.decodeFile(asset.thumbnailPath)) } catch (_: Exception) {}
                } else if (asset.kind == MediaKind.AUDIO) setImageResource(android.R.drawable.ic_btn_speak_now)
            }
            row += thumb
            val info = TextView(this).apply {
                text = "${asset.name}\n${asset.kind.name} · ${asset.durationMs / 1000}s · ${asset.width}x${asset.height}"
                textColor = TEXT; textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            row += info
            row += button("+") {
                applyEdit(engine.addToTimeline(asset.id))
                previewAsset(asset, null)
            }
            mediaList += row
        }
    }

    private fun refreshTimeline() {
        timelineStrip.removeAllViews()
        val fps = engine.project.timeline.fps
        engine.project.timeline.items.sortedBy { it.startFrame }.forEach { item ->
            val card = TextView(this).apply {
                text = "${item.name.take(14)}\n${(item.durationInFrames.toDouble() / fps).let { String.format("%.1f", it) }}s · in ${item.srcInFrame}"
                textColor = if (item.id == engine.project.timeline.selectedId) Color.WHITE else TEXT
                textSize = 11f
                gravity = Gravity.CENTER
                setBackgroundColor(if (item.track == "A1") 0xFF28405A.toInt() else if (item.id == engine.project.timeline.selectedId) 0xFF3C5AA6.toInt() else 0xFF263041.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(112), dp(62)).apply { setMargins(dp(2), 0, dp(2), 0) }
                setOnClickListener {
                    engine.select(item.id)
                    previewAsset(engine.project.assets.firstOrNull { it.id == item.assetId }, item)
                    refreshAll()
                }
            }
            timelineStrip += card
        }
        if (engine.project.timeline.items.isEmpty()) {
            timelineStrip += TextView(this).apply { text = "  Timeline empty — import media and tap +"; textColor = MUTED; textSize = 12f }
        }
    }

    private fun previewAsset(asset: MediaAsset?, item: TimelineItem?) {
        if (asset == null || asset.kind == MediaKind.IMAGE) return
        previewItemId = item?.id
        playheadMs = (item?.srcInFrame ?: 0).toLong() * 1000 / engine.project.timeline.fps
        preview.setVideoPath(asset.path)
        preview.setOnPreparedListener { mp ->
            mp.isLooping = true
            preview.seekTo(playheadMs.toInt())
            updateSeekLabel()
        }
        preview.start()
    }

    private fun updateSeekLabel() {
        val item = selectedVideoItem()
        val fps = engine.project.timeline.fps
        if (item == null) {
            seekLabel.text = "—"
            seek.progress = 0
            return
        }
        val total = item.durationInFrames.toLong() * 1000 / fps
        val local = max(0, min(total, playheadMs - item.srcInFrame.toLong() * 1000 / fps))
        seek.progress = if (total > 0) (local * 1000 / total).toInt() else 0
        seekLabel.text = String.format("%.1f / %.1fs", local / 1000f, total / 1000f)
    }

    private fun selectedItem(): TimelineItem? = engine.project.timeline.selectedId?.let { id ->
        engine.project.timeline.items.firstOrNull { it.id == id }
    }

    private fun selectedVideoItem(): TimelineItem? {
        val selected = selectedItem()
        if (selected != null && selected.track.startsWith("V")) return selected
        return engine.project.timeline.items.firstOrNull { it.track.startsWith("V") }
    }

    private fun splitAtPlayhead() {
        val item = selectedVideoItem() ?: return setStatus("Select a video clip first")
        val fps = engine.project.timeline.fps
        val frame = item.startFrame + ((playheadMs - item.srcInFrame.toLong() * 1000 / fps) * fps / 1000.0).roundToInt()
        applyEdit(engine.split(item.id, frame))
    }

    private fun trimAtPlayhead(fromStart: Boolean) {
        val item = selectedVideoItem() ?: return setStatus("Select a video clip first")
        val fps = engine.project.timeline.fps
        val local = ((playheadMs - item.srcInFrame.toLong() * 1000 / fps) * fps / 1000.0).roundToInt()
        if (fromStart) {
            if (local <= 0 || local >= item.durationInFrames - 1) return setStatus("Playhead must be inside the clip")
            applyEdit(engine.trim(item.id, item.startFrame + local, item.durationInFrames - local, item.srcInFrame + local))
        } else {
            if (local < 1) return setStatus("Playhead must be inside the clip")
            applyEdit(engine.trim(item.id, item.startFrame, local, item.srcInFrame))
        }
    }

    private fun addTransition(type: TransitionType) {
        val items = engine.project.timeline.items.filter { it.track == "V1" }.sortedBy { it.startFrame }
        if (items.size < 2) return setStatus("Need at least two video clips")
        val selected = selectedVideoItem()
        val index = items.indexOfFirst { it.id == selected?.id }.let { if (it < 0) 0 else it }
        if (index >= items.size - 1) return setStatus("Select the outgoing clip (not the last one)")
        applyEdit(engine.addTransition(items[index].id, items[index + 1].id, type, engine.project.timeline.fps / 2))
    }

    private fun applyEdit(result: com.openchatcut.android.core.EditResult) {
        setStatus(if (result.ok) result.message else "FAILED: ${result.message}")
        if (result.ok) { saveProject(); refreshAll() }
    }

    // ── Real analysis / export (background threads) ────────────────────────────────

    private fun analyzeSelected() {
        val item = selectedVideoItem() ?: return setStatus("Select a video clip first")
        val asset = engine.project.assets.firstOrNull { it.id == item.assetId } ?: return
        setStatus("Analyzing ${asset.name} on device…")
        worker.execute {
            try {
                val result = MediaAnalysis.analyze(this, asset, onStage = { s -> post { setStatus(s) } })
                post {
                    setStatus("Analysis: ${result.scenes.size} scenes, ${result.motion.size} motion windows, ${result.silence.size} silence spans")
                    appendChat("Analysis", "\n${asset.name}: ${result.scenes.size} scene changes, ${result.motion.size} motion windows, ${result.silence.size} silence spans. ${result.notes.joinToString(" ")}\n")
                }
            } catch (e: Throwable) { post { setStatus("Analysis failed: ${e.message}") } }
        }
    }

    private fun runMontage() {
        setStatus("Analyzing all video assets for a real highlight montage…")
        worker.execute {
            try {
                val analyses = engine.project.assets.filter { it.kind == MediaKind.VIDEO }.mapNotNull { asset ->
                    MediaAnalysis.analyze(this, asset, onStage = { s -> post { setStatus(s) } })
                }
                if (analyses.isEmpty()) { post { setStatus("No analysable video assets") }; return@execute }
                val edit = engine.buildMontage(analyses, maxDurationSec = 30, segmentSec = 3, segments = 8)
                saveProject()
                post { applyEdit(edit) }
            } catch (e: Throwable) { post { setStatus("Montage failed: ${e.message}") } }
        }
    }

    private fun removeBoring() {
        val item = selectedVideoItem() ?: return setStatus("Select a video clip first")
        val asset = engine.project.assets.firstOrNull { it.id == item.assetId } ?: return
        setStatus("Analyzing ${asset.name} for low-energy segments…")
        worker.execute {
            try {
                val analysis = MediaAnalysis.analyze(this, asset, onStage = { s -> post { setStatus(s) } })
                val edit = engine.removeBoringParts(analysis)
                saveProject()
                post { applyEdit(edit) }
            } catch (e: Throwable) { post { setStatus("Remove boring parts failed: ${e.message}") } }
        }
    }

    private fun export() {
        if (engine.project.timeline.items.isEmpty()) return setStatus("Timeline is empty")
        val outDir = File(getExternalFilesDir(null), "exports").apply { mkdirs() }
        val output = File(outDir, "openchatcut_${System.currentTimeMillis()}.mp4")
        setStatus("Rendering real MP4…")
        worker.execute {
            val result = TimelineRenderer.render(engine.project, output.absolutePath) { pct, msg ->
                post { setStatus(if (pct >= 0) "$pct% · $msg" else msg) }
            }
            post {
                if (result.ok) {
                    setStatus("EXPORT OK: ${result.message}")
                    appendChat("Export", "\nRendered ${result.outputPath} (${result.sizeBytes} bytes, ${result.durationMs}ms). Verified on disk.\n")
                    preview.setVideoPath(result.outputPath)
                    preview.start()
                } else {
                    setStatus("EXPORT FAILED: ${result.message}")
                    appendChat("Export", "\nFAILED: ${result.message}\n")
                }
            }
        }
    }

    private fun downloadWhisper() {
        setStatus("Downloading local Whisper tiny.en model (~75 MB)…")
        worker.execute {
            try {
                val file = MediaAnalysis.downloadWhisperModel(this) { p -> post { setStatus("Whisper model $p%") } }
                post { setStatus("Whisper model installed: ${file.length()} bytes") }
            } catch (e: Throwable) { post { setStatus("Whisper download failed: ${e.message}") } }
        }
    }

    // ── Settings dialog ────────────────────────────────────────────────────────────

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val urlInput = EditText(this).apply { setText(settings.baseUrl); hint = "http://127.0.0.1:11434" }
        val modelInput = EditText(this).apply { setText(settings.model); hint = "qwen2.5:7b" }
        val resultText = TextView(this).apply { textColor = MUTED; textSize = 12f }
        box += TextView(this).apply { text = "Ollama base URL"; textColor = TEXT }
        box += urlInput
        box += TextView(this).apply { text = "Model name (see /api/tags)"; textColor = TEXT }
        box += modelInput
        box += resultText
        AlertDialog.Builder(this)
            .setTitle("Local model settings")
            .setView(box)
            .setPositiveButton("Save & test") { _, _ ->
                settings.baseUrl = urlInput.text.toString()
                settings.model = modelInput.text.toString()
                client.baseUrl = settings.baseUrl
                client.model = settings.model
                client.requestTimeoutMs = 20_000
                worker.execute {
                    val text = client.describeConnectivity()
                    post { resultText.text = text; Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show() }
                }
            }
            .setNeutralButton("List models", null)
            .setNegativeButton("Cancel", null)
            .show().apply {
                getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    worker.execute {
                        val text = client.describeConnectivity()
                        post { resultText.text = text }
                    }
                }
            }
    }

    // ── Permissions, intents, helpers ─────────────────────────────────────────────

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED)
                needed += Manifest.permission.READ_MEDIA_VIDEO
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
                needed += Manifest.permission.READ_MEDIA_AUDIO
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
                needed += Manifest.permission.READ_MEDIA_IMAGES
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 42)
    }

    private fun handleIncomingIntent(incoming: Intent) {
        val uri: Uri? = when (incoming.action) {
            Intent.ACTION_VIEW -> incoming.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33)
                incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else incoming.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri == null) return
        worker.execute {
            val result = MediaImporter.import(this, uri)
            if (result.error == null) {
                engine.addToTimeline(result.asset.id)
                saveProject()
                post { refreshAll(); setStatus("Imported shared file: ${result.asset.name}") }
            } else post { setStatus("Import failed: ${result.error}") }
        }
    }

    private fun saveProject() {
        val r = ProjectStore.save(this, engine.snapshot())
        if (r.isFailure) post { setStatus("Project save failed: ${r.exceptionOrNull()?.message}") }
    }

    private fun appendChat(who: String, text: String) {
        chatText.append(if (who.isEmpty()) text else "[$who] $text")
        while (chatText.text.length > 12000) {
            val s = chatText.text.toString()
            chatText.text = s.substring(s.indexOf('\n') + 1)
        }
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setStatus(text: String) { statusText.text = text }

    private fun post(block: () -> Unit) = main.post(block)

    override fun onDestroy() {
        super.onDestroy()
        agent.shutdown()
        worker.shutdownNow()
    }

    // Property shims for concise programmatic UI construction.
    private var TextView.textColor: Int
        get() = currentTextColor
        set(value) { setTextColor(value) }

    private var TextView.textStyle: Int
        get() = typeface?.style ?: Typeface.NORMAL
        set(value) { setTypeface(typeface, value) }

    // Small UI helpers
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private val BG = 0xFF0B0E14.toInt()
    private val PANEL = 0xFF141924.toInt()
    private val TEXT = 0xFFD6DCE8.toInt()
    private val MUTED = 0xFF8792A8.toInt()
    private val ACCENT = 0xFF8FA8FF.toInt()

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text; textColor = ACCENT; textStyle = Typeface.BOLD; textSize = 13f
        setPadding(dp(10), dp(10), dp(8), dp(6))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun separator() = View(this).apply {
        setBackgroundColor(0xFF303A4D.toInt())
        layoutParams = LinearLayout.LayoutParams(dp(1), MATCH_PARENT)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 11f; isAllCaps = false
        setTextColor(0xFFE8ECF5.toInt())
        setBackgroundColor(0xFF1E293B.toInt())
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
        setOnClickListener { onClick() }
    }

    private operator fun LinearLayout.plusAssign(view: View) { addView(view) }
}
