# Cinder Mobile — Web Patcher

**Live site:** [https://cinder-mobile.mufaro.workers.dev](https://cinder-mobile.mufaro.workers.dev)

In-browser APK patcher and downloader for **Cinder Mobile** (Discord Phoenix Android, `arm64-v8a`).
Runs 100% client-side in the browser (using an ES Module Web Worker + WebCrypto) — nothing is uploaded.

## What it patches live in the browser

Every build automatically injects the complete Cinder Mobile stack (no plugin-toggle checkboxes):

1. **Native ELF patches (`lib/elf.js`)** on `lib/arm64-v8a/liblibdiscord_wasm_host.so`:
   - `experiment.release_access`: unlocks the Phoenix access gate (supports both standalone Phoenix APKs and Play Store Discord Alpha 356207+ APS2 relocations).
   - `staff.devtools`: patches the staff/devtools predicate with a 12-byte PIC ARM64 `.bss` flag reader stub (`adrp x8; ldrb w0, [x8, #off]; ret`) so Cinder's `StaffDevToolsPlugin` can toggle Staff Settings / DevTools at runtime.
   - Brands the runtime label (`Native runtime · build ` → `Cinder runtime · build `).
   - Emits `assets/cinder-patches.json` with the live ELF `NT_GNU_BUILD_ID`, byte guards, and `.bss` `runtime_flags`.
2. **DEX bytecode hooks (`lib/dex.js`)** via *Extract-and-Emit*:
   - Forces `PhoenixLaunch` on (when patching Play Store Discord Alpha builds).
   - Extracts `<application>` (`MainApplication` / `NativeApp`), `GuestHost`, and all `WasmRuntime.nativeDispatch(J[B)Z` caller classes out of `classes.dex` (zero ID shifts, avoids the 65,535 `method_id` ceiling) and emits a spec-compliant hooks mini-DEX (`classes(N+1).dex`) wired to `Loader.init`, `HostInvoker`, `Loader.onOps`, `Loader.dispatchInvoke`, `Loader.onLog`, `Loader.dispatchNative`, `BridgeRegistry.register`, and `UiOps.registerPropNames`.
3. **Cinder payload injection (`payload/`)**:
   - `classesN.dex`: compiled Java 17 + Kotlin 2.x Mana UI SDK & bridge loader (`classes2.dex`).
   - `lib/arm64-v8a/libcinder_mobile.so` + native plugins (`libhello_cinder_mobile.so`), stored with **16 KiB alignment** (Android 15/16 page-size compliant).
4. **Manifest, resources, and APK v2 signing (`lib/axml.js`, `lib/arsc.js`, `lib/zip.js`, `lib/sign.js`)**:
   - Strips split-APK attributes and merges density/language `resources.arsc` + `res/*` when patching Play Store splits.
   - Optionally renames package, authorities, permissions, task affinities, and launcher label (`Discord Cinder`) for side-by-side installation next to stock Discord.
   - Signs in-browser with WebCrypto RSA-2048 SHA-256 **APK Signature Scheme v2** using the consistent key in `signing-key.json` so future Cinder updates install in place.

## Local testing

Sync freshly built Cinder artifacts into `web/payload/`:

```bash
bash web/tools/sync-payload.sh
```

Run the local dev server (also proxies `/tracker/*` locally, or pass a directory of cached split APKs):

```bash
node web/tools/dev-server.mjs
# Open http://127.0.0.1:8787/?proxy=http://127.0.0.1:8787
```

Or test from the CLI using the exact same browser JS modules:

```bash
# Monolithic APK
node web/tools/node-build.mjs out.apk /path/to/discord-mobile.apk

# Play Store split APKs (merged + side-by-side rename)
node web/tools/node-build.mjs --pkg com.discord.release --label "Discord Cinder" \
  out.apk base.apk config.arm64_v8a.apk config.xxhdpi.apk config.en.apk
```

## Deploying (Cloudflare Workers)

Both the static web app and the `/tracker/*` CORS proxy are deployed together as a single Cloudflare Worker (`wrangler.jsonc`):

```bash
bash web/tools/sync-payload.sh
npx --prefix web wrangler deploy --config web/wrangler.jsonc
# or from inside web/:
# npx wrangler deploy
```

Live deployment: **https://cinder-mobile.mufaro.workers.dev**
