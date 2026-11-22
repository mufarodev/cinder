// End-to-end in-browser Cinder Mobile APK builder.
// Supports both:
//   - Play Store Discord Alpha split APKs (base.apk + config.arm64_v8a.apk + density/lang splits)
//   - Standalone monolithic Phoenix APK (e.g. discord-mobile.apk)
//
// Injects the complete Cinder Mobile stack with zero plugin-toggle UI:
//   1. Static AArch64 ELF patches on `liblibdiscord_wasm_host.so` (`staff.devtools` .bss runtime
//      flag stub, `experiment.release_access`, Cinder runtime label) + `assets/cinder-patches.json`
//   2. Native runtime & plugin `.so`s (`libcinder_mobile.so`, `libhello_cinder_mobile.so`) aligned 16 KiB
//   3. Cinder Java/Kotlin Mana UI SDK + bridge loader DEX (`classesN.dex`)
//   4. Live-patched `classes.dex` (+ `PhoenixLaunch` unlock on Play Store builds) + extracted
//      Cinder hooks mini-DEX (`classes(N+1).dex`)
//   5. Merged `AndroidManifest.xml` + `resources.arsc` (with optional side-by-side package rename)
//   6. APK Signature Scheme v2 signing (WebCrypto RSA-2048 SHA-256).

import { mergeArsc } from './arsc.js';
import { inspectManifest, patchManifest } from './axml.js';
import { patchDexesForCinder } from './dex.js';
import { patchHostSo } from './elf.js';
import { signV2 } from './sign.js';
import { crc32, deflatedEntry, entryData, readZip, storedEntry, writeZip } from './zip.js';

const V1_SIG = /^META-INF\/([^/]+\.(SF|RSA|DSA|EC)|MANIFEST\.MF)$/i;
const HOST_LIB = 'lib/arm64-v8a/liblibdiscord_wasm_host.so';
const PATCH_MANIFEST_ENTRY = 'assets/cinder-patches.json';
const DEX_RE = /^classes(\d*)\.dex$/;
const SKIP = (name) => V1_SIG.test(name) || name === 'stamp-cert-sha256';
const te = new TextEncoder();

function nextDexIndex(existingNames) {
  let maxN = 1;
  for (const name of existingNames) {
    const m = DEX_RE.exec(name);
    if (!m) continue;
    const n = m[1] ? parseInt(m[1], 10) : 1;
    if (n > maxN) maxN = n;
  }
  return maxN + 1;
}

/**
 * Inspect an uploaded/downloaded set of APK buffers and classify them into
 * `{ base, abi, configs, monolithic, manifest }`.
 */
export async function classifyApkFiles(files) {
  const result = { base: null, abi: null, configs: [], monolithic: false, manifest: null };
  for (const { name, data } of files) {
    const entries = readZip(data);
    const names = new Set(entries.map((e) => e.name));
    const hasDex = names.has('classes.dex');
    const hasHostSo = names.has(HOST_LIB);
    if (hasDex && hasHostSo) {
      result.base = data;
      result.abi = data;
      result.monolithic = true;
    } else if (hasDex) {
      result.base = data;
    } else if (hasHostSo || [...names].some((n) => n.startsWith('lib/arm64-v8a/'))) {
      result.abi = data;
    } else if (names.has('resources.arsc') || [...names].some((n) => n.startsWith('res/'))) {
      result.configs.push(data);
    } else {
      throw new Error(`unrecognized APK file: ${name}`);
    }
  }
  if (!result.base) throw new Error('missing base APK (no classes.dex found)');
  if (!result.abi) throw new Error('missing arm64-v8a native library APK (no lib/arm64-v8a/ found)');

  const manifestEntry = readZip(result.base).find((e) => e.name === 'AndroidManifest.xml');
  if (manifestEntry) {
    result.manifest = inspectManifest(await entryData(manifestEntry));
  }
  return result;
}

/**
 * Build a complete, signed Cinder Mobile APK in memory.
 *
 * @param {object} opts
 * @param {Uint8Array} opts.base                 base.apk (or monolithic APK)
 * @param {Uint8Array} [opts.abi]                config.arm64_v8a.apk (omit or pass same buffer for monolithic)
 * @param {Uint8Array[]} [opts.configs]          density / language config splits
 * @param {{pkcs8:Uint8Array, spki:Uint8Array, cert:Uint8Array}} opts.identity
 * @param {{loaderDex:Uint8Array, runtimeSo:Uint8Array, pluginLibs:Array<{name:string, data:Uint8Array}>}} opts.payload
 * @param {string|null} [opts.packageName]       target package name (null = keep original)
 * @param {string|null} [opts.label]             target launcher label (null = keep original)
 * @param {(msg:string)=>void} [opts.log]
 * @param {(stage:string, frac:number)=>void} [opts.onProgress]
 * @returns {Promise<{parts: Uint8Array[], manifest: object, origPkg: string, targetPkg: string}>}
 */
export async function buildPatchedApk({
  base,
  abi = null,
  configs = [],
  identity,
  payload,
  packageName = null,
  label = null,
  log = () => {},
  onProgress = () => {},
}) {
  if (!payload || !payload.loaderDex || !payload.runtimeSo) {
    throw new Error('missing Cinder payload artifacts (classes2.dex / libcinder_mobile.so)');
  }
  if (packageName && !/^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/.test(packageName)) {
    throw new Error(`invalid package name: ${packageName}`);
  }

  onProgress('reading', 0.05);
  const monolithic = !abi || abi === base;
  const baseEntries = readZip(base).filter((e) => !SKIP(e.name));
  const abiEntries = monolithic ? [] : readZip(abi).filter((e) => !SKIP(e.name));
  const cfgEntries = configs.map((c) => readZip(c).filter((e) => !SKIP(e.name)));

  // Inspect manifest to get original packageName and <application android:name>
  const manifestEntry = baseEntries.find((e) => e.name === 'AndroidManifest.xml');
  if (!manifestEntry) throw new Error('AndroidManifest.xml missing from base APK');
  const rawManifest = await entryData(manifestEntry);
  const manifestInfo = inspectManifest(rawManifest);
  const origPkg = manifestInfo.packageName;
  const targetPkg = packageName || origPkg;
  const renaming = Boolean(targetPkg && origPkg && targetPkg !== origPkg);
  log(
    `input APK: ${origPkg} (${manifestInfo.versionName || 'unknown'}, app=${manifestInfo.applicationName || 'unknown'}, ${monolithic ? 'monolithic' : `base + arm64 + ${configs.length} split(s)`})`,
  );

  // Collect all classes*.dex files from base APK for Cinder DEX patching
  onProgress('dex', 0.2);
  const dexEntries = baseEntries
    .filter((e) => DEX_RE.test(e.name))
    .sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }));
  const dexBuffers = [];
  for (const e of dexEntries) {
    dexBuffers.push({ name: e.name, data: await entryData(e) });
  }
  const { patchedDexes, hooksDex } = await patchDexesForCinder(
    dexBuffers,
    manifestInfo.applicationName,
    log,
  );

  // Locate and patch liblibdiscord_wasm_host.so
  onProgress('elf', 0.45);
  const out = [];
  const have = new Set(baseEntries.map((e) => e.name));
  let cinderManifest = null;
  let hostSoPatched = false;

  const processHostSoEntry = async (e) => {
    // Copy only if compressed; otherwise mutate a copy or in-place safely.
    // Note: if base/abi buffers may be reused across clicks, slice only when needed;
    // on phones we patch the extracted/split buffer directly to avoid extra 100 MB copy.
    const data = e.method === 0 ? e.raw : await entryData(e);
    cinderManifest = patchHostSo(data, log);
    hostSoPatched = true;
    return { name: e.name, method: 0, crc: crc32(data), csize: data.length, usize: data.length, raw: data };
  };

  for (const e of baseEntries) {
    if (e.name === 'AndroidManifest.xml') {
      const m = patchManifest(rawManifest, { packageName: targetPkg, label });
      if (m.removed || m.flipped || m.renamed) {
        log(
          `manifest: removed ${m.removed} split attr(s), splits.required=false: ${m.flipped}` +
            (m.renamed ? `, ${m.renamed} value(s) renamed -> ${targetPkg}${label ? ` ("${label}")` : ''}` : ''),
        );
      }
      out.push(storedEntry(e.name, m.data));
    } else if (e.name === 'resources.arsc') {
      const splits = [];
      for (const z of cfgEntries) {
        const a = z.find((x) => x.name === 'resources.arsc');
        if (a) splits.push(await entryData(a));
      }
      if (splits.length || renaming) {
        const merged = mergeArsc(await entryData(e), splits, renaming ? targetPkg : null);
        out.push(storedEntry(e.name, merged));
        log(`resources: merged ${splits.length} split table(s)${renaming ? ` (pkg -> ${targetPkg})` : ''}`);
      } else {
        out.push(e);
      }
    } else if (patchedDexes.has(e.name)) {
      const patched = patchedDexes.get(e.name);
      // Match original compression method (deflated saves ~4-6 MB on final APK; stored if originally stored)
      out.push(e.method === 8 ? await deflatedEntry(e.name, patched) : storedEntry(e.name, patched));
    } else if (e.name === HOST_LIB) {
      out.push(await processHostSoEntry(e));
    } else if (e.name === PATCH_MANIFEST_ENTRY) {
      // Replaced below with fresh Cinder patch manifest
      continue;
    } else {
      out.push(e);
    }
  }

  // Merge native libraries from ABI split (if split APK mode)
  for (const e of abiEntries) {
    if (!e.name.startsWith('lib/') || have.has(e.name)) continue;
    if (e.name === HOST_LIB) {
      out.push(await processHostSoEntry(e));
    } else {
      out.push(e);
    }
    have.add(e.name);
  }

  if (!hostSoPatched) throw new Error(`${HOST_LIB} not found in APK`);

  // Merge res/* from density/language config splits
  let addedRes = 0;
  for (const z of cfgEntries) {
    for (const e of z) {
      if (e.name.startsWith('res/') && !have.has(e.name)) {
        out.push(e);
        have.add(e.name);
        addedRes++;
      }
    }
  }
  if (addedRes) log(`resources: added ${addedRes} file(s) from config splits`);

  // Plant Cinder DEXes (Mana UI SDK / bridge loader + extracted hooks mini-DEX)
  onProgress('inject', 0.7);
  const firstFreeDex = nextDexIndex(have);
  const loaderDexName = `classes${firstFreeDex}.dex`;
  const hooksDexName = `classes${firstFreeDex + 1}.dex`;
  out.push(await deflatedEntry(loaderDexName, payload.loaderDex));
  out.push(await deflatedEntry(hooksDexName, hooksDex));
  have.add(loaderDexName);
  have.add(hooksDexName);
  log(`injected ${loaderDexName} (Cinder loader + Mana SDK) & ${hooksDexName} (bridge hooks)`);

  // Plant Cinder native runtime + native plugins (stored, 16 KiB aligned by writeZip)
  const nativePayloads = [
    { name: 'libcinder_mobile.so', data: payload.runtimeSo },
    ...(payload.pluginLibs || []),
  ];
  for (const lib of nativePayloads) {
    const entryPath = `lib/arm64-v8a/${lib.name}`;
    if (have.has(entryPath)) {
      const idx = out.findIndex((x) => x.name === entryPath);
      if (idx >= 0) out.splice(idx, 1);
    }
    out.push(storedEntry(entryPath, lib.data));
    have.add(entryPath);
    log(`planted ${entryPath} (${(lib.data.length / 1024).toFixed(0)} KB, 16 KiB aligned)`);
  }

  // Plant assets/cinder-patches.json (uncompressed, 4-byte aligned for zero-zlib native reads)
  const manifestBytes = te.encode(JSON.stringify(cinderManifest, null, 2) + '\n');
  out.push(storedEntry(PATCH_MANIFEST_ENTRY, manifestBytes));
  log(`planted ${PATCH_MANIFEST_ENTRY} (${cinderManifest.patches.length} patch guard(s))`);

  // Assemble aligned ZIP and sign with APK Signature Scheme v2
  onProgress('sign', 0.85);
  const zip = writeZip(out);
  const parts = await signV2(zip, identity, log);
  onProgress('done', 1.0);
  log('APK v2 signature complete');

  return { parts, manifest: cinderManifest, origPkg, targetPkg };
}
