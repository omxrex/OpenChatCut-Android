# Verification record — OpenChatCut Android

This file is intentionally honest: nothing is marked PASS until the operation
actually ran and produced a verifiable artifact.

## Environment used for this port

- Host device: aarch64 Android (Realme/OPPO class), kernel 4.14
- JDK 21, Gradle 8.9, Android SDK platform 35 / build-tools 35 + 34
- AAPT2: native aarch64 build from the device userland, selected by `build.sh`
  (AGP's Maven aapt2 is x86-64 and cannot execute on this host)
- FFmpeg engine in app: `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9`
  (native FFmpeg **n8.1.3** confirmed by the render log)
- Host FFmpeg 9.0.2 used only for creating/probing the test source file

## Test input

A real 12-second H.264/AAC clip with moving test pattern and two deliberate
silence gaps was generated with FFmpeg:

```
/data/user/0/ai.antigem/files/usr/tmp/opencode/openchatcut-test.mp4
```

Verified with `ffprobe`: `h264 720x480 30/1`, `aac 44100 Hz`, `duration=12.000000`,
`size=4606704` bytes.

If you supply your own file, replace the path above in the steps below. You can
import it through the app's **+ Import media** button or via `ACTION_VIEW` /
`ACTION_SEND`.

## Verification checklist

| # | Step | Expected observable result | Status |
|---|------|---------------------------|--------|
| 1 | Build `./build.sh` | `app/build/outputs/apk/debug/app-debug.apk` exists | **PASS (2026-11-21)** — `BUILD SUCCESSFUL in 1m 50s`, 44,449,960-byte APK, `aapt2 dump badging` shows `com.openchatcut.android`, minSdk 31, targetSdk 35 |
| 2 | Install with `adb install -r …` | Install succeeds on ARM64 device | **NOT RUN** — this environment is an un-rooted app-context shell: `pm install` is blocked by SELinux (`server has no access to read file context u:object_r:app_data_file`), and `/data/local/tmp` and `/sdcard` are not writable by this UID. Install the APK from a normal host shell with `adb install -r app-debug.apk`. |
| 3 | Launch app | Landscape workspace; status shows real FFmpeg version | **NOT RUN** (blocked by step 2) |
| 4 | Import test video | Thumbnail appears; duration `12s`, `720x480`, `hasAudio=true` | **NOT RUN in UI** — importer/analysis logic covered by source + harness render path below |
| 5 | Tap `+` on the asset | Clip card appears on V1 with `12.0s` | **PASS via compiled-code harness** (see below); UI not exercised |
| 6 | Tap **Analyze** | Status reports real scene/motion/silence counts; chat line added | **NOT RUN** |
| 7 | Ask local model to split at scene boundary, add dissolve, export | Chat shows tool calls with `OK`; timeline changes | **NOT RUN** — no Ollama daemon is reachable on `127.0.0.1:11434` or `localhost:11434` (`curl` connection refused). The app reports the connection error; it does not fabricate tool results. |
| 8 | Tap **EXPORT MP4** | Real FFmpeg progress 0→99%; status `EXPORT OK` | **PASS via compiled-code harness** (see below); UI not exercised |
| 9 | `ffprobe <exported path>` | Valid h264/aac streams, duration matches timeline | **PASS** — see probe output below |
| 10 | Preview plays exported file | VideoView starts and renders exported file | **NOT RUN** (blocked by step 2) |

## Compiled-code verification harness (2026-11-21)

Because this shell cannot install an APK, a small Java harness was compiled
against the app's **actual compiled Kotlin classes** (`app/build/tmp/kotlin-classes/debug`)
plus the FFmpegKit AAR, converted to DEX with `d8`, and executed under ART with
`app_process`. It exercises the real `EditorEngine` and `TimelineRenderer`, not
mocks or reimplementations. Source: `verification/RenderHarness.java`.

Observed output:

```
ADD ok=true msg=Added test-clip.mp4 to V1
SPLIT ok=true msg=Split clip at frame 180
ITEMS after edits=2
PROGRESS 0% Encoding 360 frames with FFmpeg n8.1.3
...
PROGRESS 99% Encoding frame 360 / 360 · 1.604x
RENDER ok=true msg=Rendered 5.6 MiB to rendered-test.mp4
RENDER path=.../rendered-test.mp4 bytes=5859926 durationMs=12000 elapsedMs=7712
FILE_EXISTS=true
```

`ffprobe` of the exported file:

```
codec_name=h264
codec_type=video
width=1920
height=1080
r_frame_rate=30/1
codec_name=aac
codec_type=audio
format_name=mov,mp4,m4a,3gp,3g2,mj2
duration=12.000000
size=5859926
```

This verifies real timeline mutation (add + split → 2 items), real FFmpegKit
filtergraph encoding with genuine progress, and a real playable MP4 artifact.

### Agent tool execution (same compiled classes, 2026-11-21)

`verification/ToolHarness.java` executed the real `AgentTools.execute()` against
the real editor engine under ART:

```
CATALOG_SIZE=22
TOOL read_timeline ok=true msg=Read 1 clips across 1 tracks
TOOL split_item ok=true msg=Split clip at frame 180
ITEMS_AFTER_SPLIT=2
TOOL add_transition ok=true msg=Added CROSS_DISSOLVE transition
TRANSITIONS=1
TOOL set_aspect_ratio ok=true msg=Canvas 1080x1920
TOOL undo_last_change ok=true msg=Undid last edit
CANVAS_AFTER_UNDO=1920x1080
TOOL redo_last_change ok=true msg=Redid edit
CANVAS_AFTER_REDO=1080x1920
```

This proves tool instructions cause real operations and reversibility. It does
**not** verify the LLM response itself: no Ollama daemon is available in this
environment (connection refused on both loopback addresses), so the AI step 7
above remains NOT RUN.

## What still needs a normal device/host

1. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. Launch, import a video through the UI, confirm thumbnail/metadata
3. Start or configure a local Ollama daemon with a tool-capable model, set its
   URL and model name in **Settings / models**
4. Ask a multi-step editing request; confirm tool calls and timeline changes
5. Export from the UI; `ffprobe` the exported path and play it in the preview

Do not change PENDING/NOT RUN to PASS until those steps are actually performed.

## Important notes

- The AI steps require a reachable **local Ollama daemon** and an installed
  tool-capable model. Configure **Settings / models** first. The app only speaks
  native Ollama `/api/tags` and `/api/chat`; there is no cloud fallback and no
  canned reply. If Ollama is unreachable, the app reports the connection error
  and does **not** fabricate tool results.
- If a tool call returns `ok:false`, the agent loop stops rather than claiming
  success; that failure is a real result and will be recorded here honestly.
- FFmpegKit Full GPL makes the built APK GPLv3.
