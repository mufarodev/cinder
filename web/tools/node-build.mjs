#!/usr/bin/env node
// Local Node.js CLI harness running the exact same browser patching modules (`mobile/web/lib/*`).
//
// Usage:
//   node mobile/web/tools/node-build.mjs [--pkg com.discord.release] [--label "Discord Cinder"] <out.apk> <apk1.apk> [apk2.apk ...]

import { readFile, writeFile } from 'node:fs/promises';
import { basename, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildPatchedApk, classifyApkFiles } from '../lib/apk.js';
import { decodeIdentity } from '../lib/sign.js';

const WEB_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..');

const args = process.argv.slice(2);
let packageName = null;
let label = null;
const positional = [];

for (let i = 0; i < args.length; i++) {
  if (args[i] === '--pkg') {
    packageName = args[++i];
  } else if (args[i] === '--label') {
    label = args[++i];
  } else if (args[i] === '-h' || args[i] === '--help') {
    console.log('Usage: node mobile/web/tools/node-build.mjs [--pkg <name>] [--label <name>] <out.apk> <input.apk...>');
    process.exit(0);
  } else {
    positional.push(args[i]);
  }
}

if (positional.length < 2) {
  console.error('Usage: node mobile/web/tools/node-build.mjs [--pkg <name>] [--label <name>] <out.apk> <input.apk...>');
  process.exit(1);
}

const [outPath, ...inPaths] = positional;

const t0 = performance.now();
const identity = decodeIdentity(JSON.parse(await readFile(resolve(WEB_DIR, 'signing-key.json'), 'utf8')));
const payloadManifest = JSON.parse(await readFile(resolve(WEB_DIR, 'payload/manifest.json'), 'utf8'));
const payload = {
  loaderDex: new Uint8Array(await readFile(resolve(WEB_DIR, 'payload', payloadManifest.loaderDex))),
  runtimeSo: new Uint8Array(await readFile(resolve(WEB_DIR, 'payload', payloadManifest.runtimeSo))),
  pluginLibs: await Promise.all(
    (payloadManifest.pluginLibs || []).map(async (name) => ({
      name,
      data: new Uint8Array(await readFile(resolve(WEB_DIR, 'payload', name))),
    })),
  ),
};

const files = await Promise.all(
  inPaths.map(async (p) => ({ name: basename(p), data: new Uint8Array(await readFile(p)) })),
);
const classified = await classifyApkFiles(files);

const { parts, manifest, origPkg, targetPkg } = await buildPatchedApk({
  base: classified.base,
  abi: classified.abi,
  configs: classified.configs,
  identity,
  payload,
  packageName,
  label,
  log: (msg) => console.log(`[cinder-web] ${msg}`),
});

const total = parts.reduce((s, p) => s + p.length, 0);
const flat = new Uint8Array(total);
let pos = 0;
for (const p of parts) {
  flat.set(p, pos);
  pos += p.length;
}
await writeFile(outPath, flat);
const elapsed = ((performance.now() - t0) / 1000).toFixed(2);
console.log(
  `[cinder-web] wrote ${outPath} (${(total / 1048576).toFixed(1)} MB, ${origPkg} -> ${targetPkg}, build_id=${manifest.build_id.slice(0, 12)}…) in ${elapsed}s`,
);
