#!/usr/bin/env bash

set -euo pipefail

LOADER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOBILE_DIR="$(cd "$LOADER_DIR/.." && pwd)"

ANDROID_JAR="${ANDROID_JAR:-/tmp/opencode/android-36/android.jar}"
KOTLINC_HOME="${KOTLINC_HOME:-/tmp/opencode/kotlinc}"
KOTLINC="${KOTLINC:-$KOTLINC_HOME/bin/kotlinc}"
KOTLIN_STDLIB="${KOTLIN_STDLIB:-$KOTLINC_HOME/lib/kotlin-stdlib.jar}"
KOTLIN_COMPILER_JAR="${KOTLIN_COMPILER_JAR:-$KOTLINC_HOME/lib/kotlin-compiler.jar}"
JAVA_TARGET="${JAVA_TARGET:-17}"

if [[ ! -f "$ANDROID_JAR" ]]; then
  echo "ERROR: android.jar not found at $ANDROID_JAR (set ANDROID_JAR)" >&2
  exit 1
fi
if [[ ! -x "$KOTLINC" ]]; then
  echo "ERROR: kotlinc not found at $KOTLINC (set KOTLINC_HOME or KOTLINC)" >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

MAIN_CLS="$TMP_DIR/main_classes"
TEST_CLS="$TMP_DIR/test_classes"
SHADED_CLS="$TMP_DIR/shaded_classes"
mkdir -p "$MAIN_CLS" "$TEST_CLS" "$SHADED_CLS"

mapfile -t MAIN_KT < <(
  find "$LOADER_DIR/src/main/kotlin" "$LOADER_DIR/example/src/main/kotlin" \
    -name '*.kt' 2>/dev/null | sort
)
mapfile -t MAIN_JAVA < <(
  find "$LOADER_DIR/src/main/java" "$LOADER_DIR/example/src/main/java" \
    -name '*.java' 2>/dev/null | sort
)
mapfile -t TEST_KT < <(
  find "$LOADER_DIR/src/test/kotlin" -name '*.kt' 2>/dev/null | sort
)
mapfile -t TEST_JAVA < <(
  find "$LOADER_DIR/src/test/java" -name '*.java' 2>/dev/null | sort
)

echo "== [1/4] Compiling main sources (Java $JAVA_TARGET + Kotlin) =="
if [[ ${#MAIN_KT[@]} -gt 0 ]]; then
  "$KOTLINC" -jvm-target "$JAVA_TARGET" \
    -Xlambdas=indy \
    -Xno-param-assertions -Xno-call-assertions -Xno-receiver-assertions \
    -no-reflect \
    -classpath "$ANDROID_JAR" \
    -d "$MAIN_CLS" \
    "${MAIN_KT[@]}" "${MAIN_JAVA[@]}"
fi
javac -source "$JAVA_TARGET" -target "$JAVA_TARGET" -Xlint:-options \
  -classpath "$ANDROID_JAR:$KOTLIN_STDLIB:$MAIN_CLS" \
  -d "$MAIN_CLS" \
  "${MAIN_JAVA[@]}"

echo "== [2/4] Compiling test sources (Java $JAVA_TARGET + Kotlin) =="
if [[ ${#TEST_KT[@]} -gt 0 ]]; then
  "$KOTLINC" -jvm-target "$JAVA_TARGET" \
    -Xlambdas=indy \
    -Xno-param-assertions -Xno-call-assertions -Xno-receiver-assertions \
    -no-reflect \
    -classpath "$MAIN_CLS:$ANDROID_JAR" \
    -d "$TEST_CLS" \
    "${TEST_KT[@]}" "${TEST_JAVA[@]}"
fi
javac -source "$JAVA_TARGET" -target "$JAVA_TARGET" -Xlint:-options \
  -classpath "$TEST_CLS:$MAIN_CLS:$KOTLIN_STDLIB:$ANDROID_JAR" \
  -d "$TEST_CLS" \
  "${TEST_JAVA[@]}"

echo "== [3/4] Running Java + Kotlin unit tests =="
TEST_CP="$TEST_CLS:$MAIN_CLS:$KOTLIN_STDLIB:$ANDROID_JAR"
java -cp "$TEST_CP" com.discord.loader.LoaderTest
java -cp "$TEST_CP" com.discord.loader.UiOpsTest
java -cp "$TEST_CP" com.discord.loader.CinderSettingsTest
if [[ ${#TEST_KT[@]} -gt 0 ]]; then
  java -cp "$TEST_CP" com.discord.loader.mana.ManaDslSmokeTest
  java -cp "$TEST_CP" com.discord.loader.mana.ThemeAndIconsTest
  java -cp "$TEST_CP" com.discord.loader.mana.MotionNativeTest
  java -cp "$TEST_CP" com.discord.loader.mana.ManaSdkCoreTest
  java -cp "$TEST_CP" com.discord.loader.mana.ManaWidgetsAndRegistryTest
  java -cp "$TEST_CP" com.discord.loader.plugins.BuiltinPluginsTest
  java -cp "$TEST_CP" com.discord.loader.plugins.MessageLoggerPluginTest
fi

if [[ ${#MAIN_KT[@]} -gt 0 || ${#TEST_KT[@]} -gt 0 ]]; then
  echo "== [4/4] Verifying ART-shaded cinder/kotlin/* execution (no kotlin-stdlib.jar on classpath) =="
  java -cp "$KOTLIN_COMPILER_JAR" "$MOBILE_DIR/tools/KotlinShader.java" \
    --in "$MAIN_CLS" --in "$TEST_CLS" \
    --stdlib "$KOTLIN_STDLIB" \
    --out "$SHADED_CLS"
  SHADED_CP="$SHADED_CLS:$ANDROID_JAR"
  java -cp "$SHADED_CP" com.discord.loader.LoaderTest
  java -cp "$SHADED_CP" com.discord.loader.CinderSettingsTest
  java -cp "$SHADED_CP" com.discord.loader.mana.ManaDslSmokeTest
  java -cp "$SHADED_CP" com.discord.loader.mana.ThemeAndIconsTest
  java -cp "$SHADED_CP" com.discord.loader.mana.MotionNativeTest
  java -cp "$SHADED_CP" com.discord.loader.mana.ManaSdkCoreTest
  java -cp "$SHADED_CP" com.discord.loader.mana.ManaWidgetsAndRegistryTest
  java -cp "$SHADED_CP" com.discord.loader.plugins.BuiltinPluginsTest
  java -cp "$SHADED_CP" com.discord.loader.plugins.MessageLoggerPluginTest
fi

echo "== All mobile/loader Java + Kotlin tests passed =="
