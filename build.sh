#!/usr/bin/env bash

set -euo pipefail

MOBILE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CINDER_DIR="$MOBILE_DIR/cinder-mobile"
LOADER_DIR="$MOBILE_DIR/loader"
TOOLS_DIR="$MOBILE_DIR/tools"
INSTALLER="$CINDER_DIR/installer/install.py"

VANILLA_APK="${VANILLA_APK:-/tmp/opencode/discord-mobile.apk}"
BUILD_DIR="${BUILD_DIR:-$MOBILE_DIR/build}"
CACHE_DIR="${CACHE_DIR:-$BUILD_DIR/cache}"
OUT_APK="${OUT_APK:-$BUILD_DIR/Discord-Cinder.apk}"
WORK_DIR="${WORK_DIR:-/tmp/cinder-mobile-build}"

ANDROID_JAR="${ANDROID_JAR:-/tmp/opencode/android-36/android.jar}"
D8="${D8:-/tmp/opencode/bt/android-16/d8}"
D8_JAR="${D8_JAR:-$(dirname "$D8")/lib/d8.jar}"
DEXDUMP="${DEXDUMP:-$(dirname "$D8")/dexdump}"
APKSIGNER="${APKSIGNER:-/tmp/opencode/bt/android-16/apksigner}"
KEYSTORE="${KEYSTORE:-/tmp/opencode/modloader.jks}"
KS_ALIAS="${KS_ALIAS:-mod}"
KS_PASS="${KS_PASS:-changeit}"
APKTOOL="${APKTOOL:-$(command -v apktool || echo apktool)}"
ANDROID_LINKER="${CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER:-/tmp/opencode/wrappers/aarch64-linux-android24-clang}"
ADB="${ADB:-/tmp/opencode/wrappers/adb}"
PACKAGE_NAME="${PACKAGE_NAME:-com.discord.release}"

JAVA_TARGET="${JAVA_TARGET:-17}"
KOTLINC_HOME="${KOTLINC_HOME:-/tmp/opencode/kotlinc}"
KOTLINC="${KOTLINC:-$KOTLINC_HOME/bin/kotlinc}"
KOTLIN_STDLIB="${KOTLIN_STDLIB:-$KOTLINC_HOME/lib/kotlin-stdlib.jar}"
KOTLIN_COMPILER_JAR="${KOTLIN_COMPILER_JAR:-$KOTLINC_HOME/lib/kotlin-compiler.jar}"
PRIMARY_SMALI="${PRIMARY_SMALI:-/tmp/cinder-mobile-install/decoded/smali}"

FAST=0
LOADER_FAST=0
RUN_TESTS=0
SKIP_LOADER=0
SKIP_RUST=0
NO_LOADER=0
NO_DEFAULT_PATCHES=0
DEPLOY_SERIAL=""
EXTRA_PATCH_ARGS=()

usage() {
  cat <<'EOF'
Usage: mobile/build.sh [options] [-- <extra install.py args>]

Options:
  --apk <path>            Vanilla Discord Phoenix APK (env: VANILLA_APK)
  --out <path>            Output signed APK (default: mobile/build/Discord-Cinder.apk)
  --cache-dir <path>      Cache dir for repatch (default: mobile/build/cache)
  --fast                  Skip kotlinc/javac/d8/cargo/apktool; run `install.py repatch`
  --loader-fast           Rebuild Java+Kotlin loader -> classes2.dex, refresh cache,
    (alias: --reloader)   and run `install.py repatch` (~15-20s)
  --test                  Run mobile/loader Java + Kotlin unit tests and exit
  --skip-loader           Reuse previously compiled classes2.dex
  --skip-rust             Reuse previously compiled Rust release .so artifacts
  --no-loader             Build without Java/Kotlin bridge-loader companion
  --no-default-patches    Do not auto-enable default static patches
                          (defaults: --staff-devtools --release-access --cinder-label)
  --deploy <adb-serial>   Install & launch on device (e.g. 100.121.200.7:45773)
  -h, --help              Show this help

Environment overrides:
  VANILLA_APK, BUILD_DIR, CACHE_DIR, OUT_APK, WORK_DIR, JAVA_TARGET,
  KOTLINC_HOME, KOTLINC, KOTLIN_STDLIB, KOTLIN_COMPILER_JAR,
  ANDROID_JAR, D8, D8_JAR, DEXDUMP, PRIMARY_SMALI, APKSIGNER,
  KEYSTORE, KS_ALIAS, KS_PASS, APKTOOL,
  CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER, ADB
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk)
      VANILLA_APK="$2"
      shift 2
      ;;
    --out)
      OUT_APK="$2"
      shift 2
      ;;
    --cache-dir)
      CACHE_DIR="$2"
      shift 2
      ;;
    --fast)
      FAST=1
      shift
      ;;
    --loader-fast | --reloader)
      LOADER_FAST=1
      shift
      ;;
    --test)
      RUN_TESTS=1
      shift
      ;;
    --skip-loader)
      SKIP_LOADER=1
      shift
      ;;
    --skip-rust)
      SKIP_RUST=1
      shift
      ;;
    --no-loader)
      NO_LOADER=1
      shift
      ;;
    --no-default-patches)
      NO_DEFAULT_PATCHES=1
      shift
      ;;
    --deploy)
      DEPLOY_SERIAL="$2"
      shift 2
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    --)
      shift
      EXTRA_PATCH_ARGS+=("$@")
      break
      ;;
    *)
      EXTRA_PATCH_ARGS+=("$1")
      shift
      ;;
  esac
done

if [[ "$RUN_TESTS" -eq 1 ]]; then
  exec bash "$LOADER_DIR/test.sh"
fi

PATCH_ARGS=()
if [[ "$NO_DEFAULT_PATCHES" -eq 0 ]]; then
  PATCH_ARGS+=(--staff-devtools --release-access --cinder-label)
fi
if [[ ${#EXTRA_PATCH_ARGS[@]} -gt 0 ]]; then
  PATCH_ARGS+=("${EXTRA_PATCH_ARGS[@]}")
fi

mkdir -p "$BUILD_DIR"
UNSIGNED_APK="$BUILD_DIR/Discord-Cinder-unsigned.apk"
LOADER_DEX="$BUILD_DIR/loader/classes2.dex"
RUST_RELEASE_DIR="$CINDER_DIR/target/aarch64-linux-android/release"

if [[ ! -f "$VANILLA_APK" ]]; then
  echo "ERROR: vanilla APK not found at $VANILLA_APK (set --apk or VANILLA_APK)" >&2
  exit 1
fi

build_loader_dex() {
  rm -rf "$BUILD_DIR/loader"
  mkdir -p "$BUILD_DIR/loader/stubs" "$BUILD_DIR/loader/classes" "$BUILD_DIR/loader/shaded" "$BUILD_DIR/loader/dex"

  STUB_LIB_CP=""
  STUB_R8_ARGS=()
  mapfile -t STUB_JAVA_SRCS < <(find "$LOADER_DIR/stubs" -name '*.java' 2>/dev/null | sort)
  if [[ ${#STUB_JAVA_SRCS[@]} -gt 0 ]]; then
    javac -source "$JAVA_TARGET" -target "$JAVA_TARGET" -Xlint:-options \
      -classpath "$ANDROID_JAR" \
      -d "$BUILD_DIR/loader/stubs" \
      "${STUB_JAVA_SRCS[@]}"
    STUB_LIB_CP=":$BUILD_DIR/loader/stubs"
    STUB_R8_ARGS=(--lib "$BUILD_DIR/loader/stubs")
  fi

  mapfile -t LOADER_KT_SRCS < <(
    find "$LOADER_DIR/src/main/kotlin" "$LOADER_DIR/example/src/main/kotlin" \
      -name '*.kt' 2>/dev/null | sort
  )
  mapfile -t LOADER_JAVA_SRCS < <(
    find "$LOADER_DIR/src/main/java" "$LOADER_DIR/example/src/main/java" \
      -name '*.java' 2>/dev/null | sort
  )

  if [[ ${#LOADER_KT_SRCS[@]} -gt 0 ]]; then
    if [[ ! -x "$KOTLINC" ]]; then
      echo "ERROR: kotlinc not found at $KOTLINC (set KOTLINC_HOME or KOTLINC)" >&2
      exit 1
    fi

    "$KOTLINC" -jvm-target "$JAVA_TARGET" \
      -Xlambdas=indy \
      -Xno-param-assertions -Xno-call-assertions -Xno-receiver-assertions \
      -no-reflect \
      -classpath "$ANDROID_JAR$STUB_LIB_CP" \
      -d "$BUILD_DIR/loader/classes" \
      "${LOADER_KT_SRCS[@]}" "${LOADER_JAVA_SRCS[@]}"

    javac -source "$JAVA_TARGET" -target "$JAVA_TARGET" -Xlint:-options \
      -classpath "$ANDROID_JAR$STUB_LIB_CP:$KOTLIN_STDLIB:$BUILD_DIR/loader/classes" \
      -d "$BUILD_DIR/loader/classes" \
      "${LOADER_JAVA_SRCS[@]}"

    java -cp "$KOTLIN_COMPILER_JAR" "$TOOLS_DIR/KotlinShader.java" \
      --in "$BUILD_DIR/loader/classes" \
      --stdlib "$KOTLIN_STDLIB" \
      --out "$BUILD_DIR/loader/shaded"

    mapfile -t SHADED_CLASSES < <(find "$BUILD_DIR/loader/shaded" -name '*.class' | sort)
    if [[ -f "$D8_JAR" && -f "$LOADER_DIR/r8-loader.pro" ]]; then

      java -Xmx2G -cp "$D8_JAR" com.android.tools.r8.R8 \
        --release --min-api 26 --lib "$ANDROID_JAR" "${STUB_R8_ARGS[@]}" \
        --pg-conf "$LOADER_DIR/r8-loader.pro" \
        --output "$BUILD_DIR/loader/dex" \
        "${SHADED_CLASSES[@]}"
    else
      "$D8" --min-api 26 --lib "$ANDROID_JAR" "${STUB_R8_ARGS[@]}" \
        --output "$BUILD_DIR/loader/dex" \
        "${SHADED_CLASSES[@]}"
    fi
  else
    javac -source "$JAVA_TARGET" -target "$JAVA_TARGET" -Xlint:-options \
      -classpath "$ANDROID_JAR$STUB_LIB_CP" \
      -d "$BUILD_DIR/loader/classes" \
      "${LOADER_JAVA_SRCS[@]}"
    mapfile -t LOADER_CLASSES < <(find "$BUILD_DIR/loader/classes" -name '*.class' | sort)
    "$D8" --min-api 26 --lib "$ANDROID_JAR" "${STUB_R8_ARGS[@]}" \
      --output "$BUILD_DIR/loader/dex" \
      "${LOADER_CLASSES[@]}"
  fi

  cp "$BUILD_DIR/loader/dex/classes.dex" "$LOADER_DEX"
  echo "Built $LOADER_DEX ($(stat -c%s "$LOADER_DEX") bytes)"

  if [[ -f "$TOOLS_DIR/verify_dex.py" ]]; then
    local VERIFY_ARGS=(--dex "$LOADER_DEX")
    if [[ -f "$CACHE_DIR/classes.dex" ]]; then
      VERIFY_ARGS+=(--primary-dex "$CACHE_DIR/classes.dex")
    elif [[ -d "$PRIMARY_SMALI" ]]; then
      VERIFY_ARGS+=(--primary-smali "$PRIMARY_SMALI")
    else
      VERIFY_ARGS=()
    fi
    if [[ ${#VERIFY_ARGS[@]} -gt 0 ]]; then
      if [[ -x "$DEXDUMP" ]]; then
        VERIFY_ARGS+=(--dexdump "$DEXDUMP")
      fi
      python3 "$TOOLS_DIR/verify_dex.py" "${VERIFY_ARGS[@]}"
    fi
  fi
}

if [[ "$LOADER_FAST" -eq 1 ]]; then
  echo "== [--loader-fast 1/2] Rebuilding mobile/loader (Java $JAVA_TARGET + Kotlin) -> classes2.dex =="
  if [[ ! -f "$CACHE_DIR/classes.dex" ]]; then
    echo "ERROR: cache dir $CACHE_DIR not populated; run without --loader-fast once first" >&2
    exit 1
  fi
  build_loader_dex
  cp "$LOADER_DEX" "$CACHE_DIR/classes2.dex"

  echo "== [--loader-fast 2/2] Repatching & signing $OUT_APK using cache $CACHE_DIR =="
  REPATCH_CMD=(
    python3 "$INSTALLER" repatch
    --apk "$VANILLA_APK"
    --cache-dir "$CACHE_DIR"
    --out "$UNSIGNED_APK"
    --signed-out "$OUT_APK"
    --work "$WORK_DIR"
    --apksigner "$APKSIGNER"
    --keystore "$KEYSTORE"
    --ks-alias "$KS_ALIAS"
    --ks-pass "$KS_PASS"
  )
  REPATCH_CMD+=("${PATCH_ARGS[@]}")
  "${REPATCH_CMD[@]}"
elif [[ "$FAST" -eq 0 ]]; then

  if [[ "$NO_LOADER" -eq 0 && "$SKIP_LOADER" -eq 0 ]]; then
    echo "== [1/3] Building mobile/loader (Java $JAVA_TARGET + Kotlin) -> classes2.dex =="
    build_loader_dex
  fi

  if [[ "$SKIP_RUST" -eq 0 ]]; then
    echo "== [2/3] Building cinder-mobile Rust runtime (aarch64-linux-android release) =="
    CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ANDROID_LINKER" \
      cargo build --release \
      --manifest-path "$CINDER_DIR/Cargo.toml" \
      --target aarch64-linux-android
  fi

  echo "== [3/3] Installing & signing $OUT_APK =="
  INSTALL_CMD=(
    python3 "$INSTALLER" install
    --apk "$VANILLA_APK"
    --runtime-so "$RUST_RELEASE_DIR/libcinder_mobile.so"
    --plugins "$RUST_RELEASE_DIR"
    --cache-dir "$CACHE_DIR"
    --out "$UNSIGNED_APK"
    --signed-out "$OUT_APK"
    --work "$WORK_DIR"
    --apktool "$APKTOOL"
    --apksigner "$APKSIGNER"
    --keystore "$KEYSTORE"
    --ks-alias "$KS_ALIAS"
    --ks-pass "$KS_PASS"
  )
  if [[ "$NO_LOADER" -eq 0 ]]; then
    INSTALL_CMD+=(--bridge-loader-dex "$LOADER_DEX")
  fi
  INSTALL_CMD+=("${PATCH_ARGS[@]}")
  "${INSTALL_CMD[@]}"
else
  echo "== [--fast] Repatching & signing $OUT_APK using cache $CACHE_DIR =="
  if [[ ! -f "$CACHE_DIR/classes.dex" ]]; then
    echo "ERROR: cache dir $CACHE_DIR not populated; run without --fast once first" >&2
    exit 1
  fi
  REPATCH_CMD=(
    python3 "$INSTALLER" repatch
    --apk "$VANILLA_APK"
    --cache-dir "$CACHE_DIR"
    --out "$UNSIGNED_APK"
    --signed-out "$OUT_APK"
    --work "$WORK_DIR"
    --apksigner "$APKSIGNER"
    --keystore "$KEYSTORE"
    --ks-alias "$KS_ALIAS"
    --ks-pass "$KS_PASS"
  )
  REPATCH_CMD+=("${PATCH_ARGS[@]}")
  "${REPATCH_CMD[@]}"
fi

echo "== Build complete: $OUT_APK =="

if [[ -n "$DEPLOY_SERIAL" ]]; then
  echo "== Deploying to $DEPLOY_SERIAL =="
  ADB_SERVER_PID=""
  cleanup_adb() {
    if [[ -n "$ADB_SERVER_PID" ]]; then
      kill "$ADB_SERVER_PID" 2>/dev/null || true
    fi
  }
  trap cleanup_adb EXIT

  if ! "$ADB" devices >/dev/null 2>&1; then
    "$ADB" nodaemon server >/dev/null 2>&1 &
    ADB_SERVER_PID=$!
    sleep 1
  fi

  if [[ "$DEPLOY_SERIAL" == *:* ]]; then
    "$ADB" connect "$DEPLOY_SERIAL"
  fi
  "$ADB" -s "$DEPLOY_SERIAL" install -r "$OUT_APK"
  "$ADB" -s "$DEPLOY_SERIAL" shell am force-stop "$PACKAGE_NAME" || true
  "$ADB" -s "$DEPLOY_SERIAL" shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1
  echo "== Deployed & launched $PACKAGE_NAME on $DEPLOY_SERIAL =="
fi
