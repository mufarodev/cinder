#!/usr/bin/env bash
# Sync built Cinder Mobile payload artifacts (classes2.dex, libcinder_mobile.so,
# native plugins) into mobile/web/payload/ for the in-browser patcher.
set -euo pipefail

WEB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MOBILE_DIR="$(cd "$WEB_DIR/.." && pwd)"
PAYLOAD_DIR="$WEB_DIR/payload"

LOADER_DEX="${LOADER_DEX:-$MOBILE_DIR/build/loader/classes2.dex}"
if [[ ! -f "$LOADER_DEX" ]]; then
  LOADER_DEX="$MOBILE_DIR/build/cache/classes2.dex"
fi

RUST_RELEASE_DIR="${RUST_RELEASE_DIR:-$MOBILE_DIR/cinder-mobile/target/aarch64-linux-android/release}"
RUNTIME_SO="${RUNTIME_SO:-$RUST_RELEASE_DIR/libcinder_mobile.so}"
if [[ ! -f "$RUNTIME_SO" ]]; then
  RUNTIME_SO="$MOBILE_DIR/build/cache/libcinder_mobile.so"
fi

if [[ ! -f "$LOADER_DEX" ]]; then
  echo "ERROR: loader classes2.dex not found (run mobile/build.sh first)" >&2
  exit 1
fi
if [[ ! -f "$RUNTIME_SO" ]]; then
  echo "ERROR: libcinder_mobile.so not found (run mobile/build.sh first)" >&2
  exit 1
fi

mkdir -p "$PAYLOAD_DIR"
cp "$LOADER_DEX" "$PAYLOAD_DIR/classes2.dex"
cp "$RUNTIME_SO" "$PAYLOAD_DIR/libcinder_mobile.so"

PLUGINS=()
for dir in "$RUST_RELEASE_DIR" "$MOBILE_DIR/build/cache"; do
  [[ -d "$dir" ]] || continue
  for f in "$dir"/libhello_cinder*.so "$dir"/libcinder_*.so "$dir"/libmod_*.so; do
    [[ -f "$f" ]] || continue
    base="$(basename "$f")"
    [[ "$base" == "libcinder_mobile.so" ]] && continue
    if [[ ! -f "$PAYLOAD_DIR/$base" || "$f" -nt "$PAYLOAD_DIR/$base" ]]; then
      cp "$f" "$PAYLOAD_DIR/$base"
    fi
    PLUGINS+=("$base")
  done
done

# Deduplicate plugin names and emit payload/manifest.json
python3 - "$PAYLOAD_DIR" <<'PY'
import hashlib, json, os, sys
pdir = sys.argv[1]
def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

plugins = sorted(
    f for f in os.listdir(pdir)
    if f.endswith(".so") and f != "libcinder_mobile.so"
)
manifest = {
    "loaderDex": "classes2.dex",
    "runtimeSo": "libcinder_mobile.so",
    "pluginLibs": plugins,
    "sha256": {
        "classes2.dex": sha256(os.path.join(pdir, "classes2.dex")),
        "libcinder_mobile.so": sha256(os.path.join(pdir, "libcinder_mobile.so")),
        **{p: sha256(os.path.join(pdir, p)) for p in plugins},
    },
}
with open(os.path.join(pdir, "manifest.json"), "w", encoding="utf-8") as fh:
    json.dump(manifest, fh, indent=2)
    fh.write("\n")
print("Synced mobile/web/payload/:", json.dumps(manifest, indent=2))
PY
