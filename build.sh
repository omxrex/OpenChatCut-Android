#!/usr/bin/env bash
# Build the OpenChatCut Android APK.
#
# On a normal Linux/macOS/Windows host the standard AGP toolchain is used.
# When building *on an aarch64 Android device* (this project was developed on
# one), AGP's Maven aapt2 is an x86-64 binary that cannot execute there, so we
# point AGP at the native aarch64 aapt2 shipped by the Termux-style userland.
set -euo pipefail
cd "$(dirname "$0")"

: "${JAVA_HOME:=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
export JAVA_HOME
: "${ANDROID_HOME:=$HOME/android-sdk}"
export ANDROID_HOME

GRADLE="${GRADLE:-$HOME/android-dl/gradle-8.9/bin/gradle}"
if [[ ! -x "$GRADLE" ]]; then
  GRADLE="$(command -v gradle)"
fi

EXTRA=()
AAPT2=""
for candidate in "${PREFIX:-}/bin/aapt2" \
                 "/data/user/0/ai.antigem/files/usr/bin/aapt2" \
                 "${HOME}/../usr/bin/aapt2"; do
  if [[ -x "$candidate" ]] && "$candidate" version >/dev/null 2>&1; then
    AAPT2="$candidate"
    break
  fi
done
if [[ -n "$AAPT2" ]] && [[ "$(uname -m)" == "aarch64" ]]; then
  echo "Using native aarch64 aapt2: $AAPT2" >&2
  EXTRA+=("-Pandroid.aapt2FromMavenOverride=$AAPT2")
fi

exec "$GRADLE" assembleDebug --no-daemon "${EXTRA[@]}" "$@"
