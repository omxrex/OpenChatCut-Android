# OpenChatCut Android

A real native Android 12+ (ARM64) adaptation of the open-source
[OpenChatCut](https://github.com/0xsline/OpenChatCut) desktop video editor.

This is **not a mock or a UI-only demo**. Media import, thumbnails, probing, scene/silence/motion
analysis, timeline edits, captions, transitions, local-model tool calling and MP4 export all run
real code on the device.

> Upstream is a Node/Electron/React app that requires Node `>=24.22.1 <25`, a headless Chromium
> with WebGL2 and desktop FFmpeg binaries. None of that can run inside an Android app. This port
> keeps the **actual editor domain model, algorithms, tool contract and system prompt** from the
> upstream repository, and reimplements only the host-specific parts (UI, render backend, model
> transport) natively.

---

## What is implemented (real)

| Area | Implementation | Upstream source reused |
|---|---|---|
| Domain model | `ProjectDoc`, `TimelineState`, `TimelineItem` with `srcInFrame`, `durationInFrames`, `playbackRate`, fades, transitions, caption cues — same field names/units | `src/editor/{types,timelineTypes,clipTypes,projectTypes,mediaTypes,transitionTypes}.ts` |
| Media import | SAF picker / ACTION_VIEW / ACTION_SEND, copies real bytes, `ffprobe` metadata, real midpoint thumbnail | upstream `probe-media` / `import_media` semantics |
| Scene detection | FFmpeg `select=gt(scene,…)+metadata=print`, parsed/normalised with ported constants `0.3 / 750ms / 200 / hard-cut max(0.4, t*1.25)` | `src/scene-detection/detect.ts`, `apply.ts` |
| Silence detection | 50 ms RMS envelope, 90th-percentile reference, `-26 dB` gate, floor `-45`, absolute `-35`, pad `150 ms`, min `600 ms` | `src/audio/silence.ts` |
| Motion analysis | Real downscaled RGB frames from FFmpeg, frame-to-frame difference scoring | Android-port addition over real footage |
| Captions | SRT parsing, two-line/24-char packing, FFmpeg `subtitles` burn-in | `src/captions/{types,srt}.ts` |
| Timeline edits | add, split, ripple delete, trim, move, duplicate, retime/fades, tighten/reorder, undo/redo | `src/agent/tools/schemas/core-tools.ts`, `edit-item-tools.ts` |
| Transitions | Real `xfade` dissolve/wipe/pan/circle/dip-to-black/flash between adjacent clips | upstream transition model + `remotion` intent |
| Highlight tools | `remove_boring_parts`, `auto_highlight_montage`, `fit_to_duration` driven by measured motion/silence | Android-port additions on real analysis |
| AI loop | Native Ollama `/api/chat` streaming + `/api/tags`, native `tools`/`tool_calls`, bounded 8-turn loop, real tool results fed back | `src/agent/systemPrompt.ts`, `assets/agent/openchatcut-tool-schemas.json` |
| Export | Timeline → FFmpeg filtergraph → H.264/AAC MP4 with captions, transitions, audio mix, real statistics progress, on-disk verification | replaces upstream `remotion/render.mjs` (Chromium) |
| Persistence | JSON project in app-private storage | upstream `~/.openchatcut` project doc |

Every original source file that was ported is also shipped verbatim under
`app/src/main/assets/upstream/` and `upstream/` for auditability.

---

## Architecture

```
UI (MainActivity, programmatic landscape workspace)
 ├── MediaImporter  → SAF copy + FFprobe + thumbnail
 ├── EditorEngine   → immutable ProjectDoc, undo/redo, real edits
 ├── MediaAnalysis  → FFmpeg scene/RGB/RMS + Whisper (optional)
 ├── TimelineRenderer → filtergraph → FFmpegKit → MP4
 └── AgentController
       ├── OllamaClient (native /api/chat, /api/tags)
       └── AgentTools (21 executable tools bound to the engine above)
```

### Why FFmpegKit instead of Remotion

Upstream export uses `renderMedia` + `selectComposition` + headless Chromium
(`remotion/render.mjs`, `remotion/Root.tsx`). Chromium/WebGL2/Node cannot be embedded in an
Android app. `TimelineRenderer` reproduces the same timeline semantics as a native FFmpeg graph.
This is the only substantive backend substitution in the port.

### FFmpeg engine

The app uses `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9`, a maintained drop-in
replacement for the retired `com.arthenica:ffmpeg-kit-full-gpl` with the same `com.arthenica.ffmpegkit`
API. It supplies `libx264`, `aac`, `xfade`, `subtitles`/`libass`, `drawtext`, `fps`, `scale`.
**GPLv3 obligations apply to the resulting app because of this dependency.**

---

## Build

Requirements: JDK 17+, Android SDK platform 35 + build-tools 35 (or 34), Gradle 8.9+
(Gradle wrapper not bundled in this snapshot; use a local Gradle or add a wrapper).

```bash
export ANDROID_HOME=$HOME/android-sdk
export JAVA_HOME=/path/to/jdk-17
./build.sh
# output: app/build/outputs/apk/debug/app-debug.apk
```

`build.sh` is a thin wrapper around `gradle assembleDebug`. On an aarch64 Android
host it automatically passes `-Pandroid.aapt2FromMavenOverride=<native aapt2>`,
because AGP's Maven aapt2 is x86-64 and cannot execute there. On a normal
Linux/macOS/Windows host it just runs the standard build. You can also call
Gradle directly with the same override if needed.

`local.properties` contains this machine's `sdk.dir`; for other machines set it or use
`ANDROID_HOME`.

Install on an ARM64 device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Using the app

1. **Import** — tap `+ Import media` and select real video/audio/image files. Each file is
   probed and a real thumbnail is generated.
2. **Timeline** — tap `+` next to a media item to append it to V1/A1/V2. Tap a clip to select and
   preview it; drag the seek bar to move the playhead.
3. **Manual edits** — Split / Delete / Ripple delete / Trim start / Trim end / Duplicate /
   Dissolve / Wipe / 9:16 / captions / Analyze / Montage / Cut boring / Fit 30s / Undo / Redo.
4. **AI editing** — in *Settings / models*, point the app at a reachable local Ollama endpoint
   (default `http://127.0.0.1:11434`; use a LAN address if Ollama runs elsewhere on your network),
   pick a installed model, then **Save & test**. Ask e.g.:
   - “Analyze every video, then make a 30-second highlight montage with dissolves.”
   - “Remove the boring parts from the selected clip.”
   - “Set the canvas to 9:16 and export a 30-second Short.”
5. **Export** — tap `EXPORT MP4`. Progress comes from real FFmpeg statistics. The status line
   reports the verified output path; the app then plays the exported file in the preview.

Exports are written to `<app external files>/exports/`.

---

## What is NOT implemented (deliberately, not silently)

The upstream project has 124 edit tools and 23 ask tools. This port implements a focused,
fully-working subset (21 tools). The following upstream capabilities are **not** present and the
agent will report a tool error rather than pretend to do them:

- Cloud/API provider backends (Anthropic, OpenAI, Gemini, Kimi, Qwen cloud, etc.) and API keys —
  the user chose local-only Ollama native API.
- Codex / Copilot / Claude-Code CLI backends and their MCP bridges.
- `run_code` / E2B sandbox, web search/map/crawl, stock media, publishing to social networks.
- Motion-graphic/GLSL shader rendering, MG templates, keyframe/transform/effect editors.
- Word-level transcript editing, `clean_script`, translation, bilingual caption styles,
  VAD-gated silence removal (this port uses the upstream RMS stage only).
- Nested timelines/sequences, multicam sync, beat detection, music intelligence.
- `detect_scenes apply=markers` (use `apply=split`), `export_jianying_draft`, FCPXML/EDL import.
- Image/GIF overlay compositing from V2 (V2 image items are added to the model and previewed,
  but the FFmpeg renderer composites V1 + A1 only).
- Visual verification tools `view_timeline_frames` / `view_asset_frames` (the UI shows real
  thumbnails and plays real media, but the model cannot yet receive rendered frames as images).
- GPU/hardware encoders — export uses `libx264` software encoding.

Unimplemented tools are absent from the catalog sent to the model, so it cannot call them.

---

## Verification checklist (do not claim success without these)

1. Import a real file → thumbnail exists and `ffprobe` duration/dimensions are non-zero.
2. Add to timeline → clip card appears with correct duration.
3. Ask the local model for an edit → log shows tool calls with `OK` and real changed timeline.
4. Export → status shows `EXPORT OK`, file exists, size > 1 KiB.
5. `ffprobe <export>` → valid streams, duration matches the timeline; preview plays it.

See `TESTING.md` for the exact reproduction steps and the current verification record.
`verification/` contains a harness that runs the app's compiled classes under ART
when an APK install is not possible from the current shell.

---

## Repository layout

```
app/src/main/java/com/openchatcut/android/
  MainActivity.kt              landscape workspace
  model/EditorModels.kt        ported domain model
  core/AnalysisAlgorithms.kt   ported scene/silence/caption algorithms
  core/EditorEngine.kt         command layer + undo/redo
  core/ProjectStore.kt         JSON persistence
  core/SettingsStore.kt        local Ollama settings
  media/FfmpegRunner.kt        FFmpegKit wrapper (real execution)
  media/MediaImporter.kt       SAF import + probe + thumbnail
  media/MediaAnalysis.kt       scene/motion/silence/Whisper
  media/TimelineRenderer.kt    timeline → FFmpeg filtergraph → MP4
  ai/OllamaClient.kt           native Ollama /api/chat + /api/tags
  ai/AgentTools.kt             21 executable tools
  ai/AgentController.kt        local model tool loop
upstream/                      verbatim upstream sources used for the port
app/src/main/assets/upstream/  same sources packaged for on-device audit
```

## License

Port code: same license as upstream OpenChatCut (see upstream repository).
The FFmpegKit Full GPL dependency makes the distributed binary GPLv3.
