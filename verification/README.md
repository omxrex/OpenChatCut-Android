# Compiled-code verification harness

This harness exercises the **actual compiled Kotlin classes** of the app under
ART on an aarch64 Android device. It is useful when the APK cannot be installed
from the current shell (for example, an app-context shell blocked by SELinux).
It does **not** replace on-device UI testing; see `../TESTING.md`.

## Prerequisites

- A debug build: `cd .. && ./build.sh`
- `javac`, `jar`, `d8` (`$ANDROID_HOME/build-tools/35.0.0/lib/d8.jar`)
- `app_process` available on the device
- A real input video (the harness does not fabricate media)

## Steps

```bash
set -e
T=$(pwd)                       # this verification directory
ROOT=..
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
K="$ROOT/app/build/tmp/kotlin-classes/debug"
KS=$(find ~/.gradle/caches/modules-2/files-2.1 -name 'kotlin-stdlib-2.0.21.jar' | head -1)
AAR=$(find ~/.gradle/caches/modules-2/files-2.1 -name 'ffmpeg-kit-full-gpl-8.1.9.aar' | head -1)
D8="$ANDROID_HOME/build-tools/35.0.0/lib/d8.jar"

mkdir -p ffaar && (cd ffaar && unzip -o -q "$AAR" classes.jar)
CP="$K:$KS:$T/ffaar/classes.jar:$ANDROID_HOME/platforms/android-35/android.jar"

rm -rf classes && mkdir -p classes
javac -cp "$CP" -d classes RenderHarness.java
jar cf render-harness-classes.jar -C classes .
java -cp "$D8" com.android.tools.r8.D8 --output render-harness.jar \
    render-harness-classes.jar --min-api 31

mkdir -p libs
unzip -o -q "$APK" 'lib/arm64-v8a/*' -d libs
export LD_LIBRARY_PATH="$T/libs/lib/arm64-v8a:${LD_LIBRARY_PATH:-}"

CLASSPATH="$T/render-harness.jar:$APK" \
    /system/bin/app_process -Xnoimage-dex2oat / RenderHarness \
    /absolute/path/to/input.mp4 "$T/rendered-test.mp4"

ffprobe -v error -show_entries format=duration,size \
    -show_entries stream=codec_name,codec_type,width,height,r_frame_rate \
    -of default=noprint_wrappers=1 "$T/rendered-test.mp4"
```

Expected: `ADD ok=true`, `SPLIT ok=true`, `ITEMS after edits=2`, progress from
0% to 99%, `RENDER ok=true`, `FILE_EXISTS=true`, and an `ffprobe` report showing
real h264/aac streams with a duration matching the edited timeline.

Generated files (`classes/`, `*.jar`, `libs/`, `rendered-test.mp4`) are ignored
by git.
