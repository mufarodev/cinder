# cinder-mobile

Android port of cinder: a native Rust runtime and a Java/Kotlin bridge loader
for the Discord Phoenix Android client (`com.discord.release`). Everything works
on a copy of the APK; the vanilla install is never modified.

## Layout

- `cinder-mobile/` — Rust runtime, C-ABI plugin API, and copy-based APK installer
- `loader/` — Java/Kotlin bridge companion and Mana UI SDK
- `patcher/` — smali patching tool
- `tools/` — Kotlin stdlib shading and DEX verification helpers
- `build.sh` — build, test, and deploy wrapper

## Build

```bash
./build.sh              # full build: loader DEX + Rust .so + patched, signed APK
./build.sh --test       # run Java/Kotlin, Rust, and Python tests
./build.sh --fast       # repackage from cached build products
./build.sh --deploy <serial>   # install and launch over adb
```

Host tools and paths are overridable through environment variables; run
`./build.sh --help` for the full list.
