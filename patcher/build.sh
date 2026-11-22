#!/usr/bin/env bash

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
BUILD="${BUILD_DIR:-$HERE/build}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"

if [[ -z "$SDK" ]]; then
  echo "Set ANDROID_SDK_ROOT to an SDK with build-tools (d8) and platforms/android-*/android.jar" >&2
  exit 1
fi

D8=$(find "$SDK/build-tools" -name d8 -type f 2>/dev/null | sort -V | tail -1 || true)
AJAR=$(find "$SDK/platforms" -path '*/android.jar' -type f 2>/dev/null | sort -V | tail -1 || true)
[[ -n "$D8" && -n "$AJAR" ]] || {
  echo "Could not find d8 / android.jar under $SDK" >&2
  exit 1
}

rm -rf "$BUILD" && mkdir -p "$BUILD/classes"

javac -source 17 -target 17 -classpath "$AJAR" \
  -d "$BUILD/classes" \
  $(find "$ROOT/loader/src/main/java" -name '*.java')

"$D8" --lib "$AJAR" --output "$BUILD" $(find "$BUILD/classes" -name '*.class')
echo "Wrote $BUILD/classes.dex -> copy to classes2.dex for patch.py"

mkdir -p "$BUILD/example-classes"
javac -source 17 -target 17 -classpath "$AJAR:$BUILD/classes" \
  -d "$BUILD/example-classes" \
  $(find "$ROOT/loader/example/src/main/java" -name '*.java')
"$D8" --lib "$AJAR:$BUILD/classes.dex" --output "$BUILD/example-dex" \
  $(find "$BUILD/example-classes" -name '*.class')
echo "Example plugin dex: $BUILD/example-dex/classes.dex"
