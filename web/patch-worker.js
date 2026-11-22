// ES Module Web Worker: runs Cinder APK patching + APK v2 signing off the main UI thread.
import { buildPatchedApk } from './lib/apk.js';
import { decodeIdentity } from './lib/sign.js';

self.onmessage = async (ev) => {
  const { base, abi, configs, identityJson, payload, packageName, label } = ev.data;
  try {
    const identity = decodeIdentity(identityJson);
    const result = await buildPatchedApk({
      base,
      abi,
      configs,
      identity,
      payload,
      packageName,
      label,
      log: (msg) => self.postMessage({ type: 'log', msg }),
      onProgress: (stage, frac) => self.postMessage({ type: 'progress', stage, frac }),
    });
    const blob = new Blob(result.parts, { type: 'application/vnd.android.package-archive' });
    self.postMessage({
      type: 'done',
      blob,
      manifest: result.manifest,
      origPkg: result.origPkg,
      targetPkg: result.targetPkg,
    });
  } catch (err) {
    self.postMessage({
      type: 'error',
      message: err && err.message ? err.message : String(err),
      stack: err && err.stack ? err.stack : '',
    });
  }
};
