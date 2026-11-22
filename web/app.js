// Cinder Mobile Web Patcher UI controller.
import { PROXY as CONFIG_PROXY, TRACKER, VERIFIED } from './config.js';
import { buildPatchedApk, classifyApkFiles } from './lib/apk.js';
import { decodeIdentity } from './lib/sign.js';

const $ = (id) => document.getElementById(id);

const urlProxy = new URLSearchParams(location.search).get('proxy');
const PROXY = (urlProxy !== null ? urlProxy : CONFIG_PROXY).replace(/\/+$/, '');

const DENSITIES = [
  ['mdpi', 160, 'mdpi (1x)'],
  ['hdpi', 240, 'hdpi (1.5x)'],
  ['xhdpi', 320, 'xhdpi (2x)'],
  ['xxhdpi', 480, 'xxhdpi (3x)'],
  ['xxxhdpi', 640, 'xxxhdpi (4x)'],
];

const LANGS = [
  ['none', 'English only (smaller)'],
  ['ar', 'Arabic (ar)'],
  ['de', 'Deutsch (de)'],
  ['es', 'Español (es)'],
  ['fr', 'Français (fr)'],
  ['hi', 'Hindi (hi)'],
  ['id', 'Indonesian (id)'],
  ['it', 'Italiano (it)'],
  ['ja', '日本語 (ja)'],
  ['ko', '한국어 (ko)'],
  ['nl', 'Nederlands (nl)'],
  ['pl', 'Polski (pl)'],
  ['pt', 'Português (pt)'],
  ['ru', 'Русский (ru)'],
  ['tr', 'Türkçe (tr)'],
  ['uk', 'Українська (uk)'],
  ['vi', 'Tiếng Việt (vi)'],
  ['zh', '中文 (zh)'],
];

const PKG_RE = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/;

let activeTab = 'download'; // 'download' | 'local'
let localPickedFiles = [];
let lastBlobUrl = null;
let cachedIdentityJson = null;
let cachedPayload = null;

// --- Initialize selectors ---------------------------------------------------------------

function initSelectors() {
  // Density: auto-pick nearest to devicePixelRatio * 160
  const devDpi = Math.round((window.devicePixelRatio || 3) * 160);
  const nearestDpi = [...DENSITIES].sort((a, b) => Math.abs(a[1] - devDpi) - Math.abs(b[1] - devDpi))[0][0];
  for (const [id, , label] of DENSITIES) {
    const opt = document.createElement('option');
    opt.value = id;
    opt.textContent = label;
    if (id === nearestDpi) opt.selected = true;
    $('dpi').append(opt);
  }

  // Language: auto-pick from navigator.language unless English
  const navLang = (navigator.language || 'en').toLowerCase().split('-')[0];
  for (const [id, label] of LANGS) {
    const opt = document.createElement('option');
    opt.value = id;
    opt.textContent = label;
    if (navLang !== 'en' && id === navLang) opt.selected = true;
    $('lang').append(opt);
  }

  // Verified builds first; then query tracker index if proxy is configured
  populateBuilds(VERIFIED.map((vc) => ({ vc, label: `${vc} (verified)` })));
  if (PROXY) {
    fetch(`${PROXY}/tracker/index`)
      .then((r) => (r.ok ? r.json() : null))
      .then((idx) => {
        if (!idx || !idx.latest) return;
        const alpha = idx.latest.alpha;
        const seen = new Set();
        const items = [];
        if (alpha) {
          seen.add(alpha);
          items.push({ vc: alpha, label: `${alpha} — latest Alpha${VERIFIED.includes(alpha) ? ' (verified)' : ''}` });
        }
        for (const vc of VERIFIED) {
          if (!seen.has(vc)) {
            seen.add(vc);
            items.push({ vc, label: `${vc} (verified)` });
          }
        }
        if (items.length) populateBuilds(items);
      })
      .catch(() => {});
  }
}

function populateBuilds(items) {
  const prev = $('ver').value;
  $('ver').textContent = '';
  for (const { vc, label } of items) {
    const opt = document.createElement('option');
    opt.value = String(vc);
    opt.textContent = label;
    $('ver').append(opt);
  }
  if (prev && items.some((x) => String(x.vc) === prev)) $('ver').value = prev;
}

// --- Tabs & validation ------------------------------------------------------------------

function switchTab(tab) {
  activeTab = tab;
  $('tab-download').setAttribute('aria-selected', String(tab === 'download'));
  $('tab-local').setAttribute('aria-selected', String(tab === 'local'));
  $('panel-download').hidden = tab !== 'download';
  $('panel-local').hidden = tab !== 'local';
  $('manual').hidden = true;
  $('err').hidden = true;
}

$('tab-download').onclick = () => switchTab('download');
$('tab-local').onclick = () => switchTab('local');

$('mode').onchange = () => {
  $('pkg-row').hidden = $('mode').value === 'replace';
  checkPackage();
};
$('pkg').oninput = checkPackage;

$('local-mode').onchange = () => {
  $('local-pkg-row').hidden = $('local-mode').value !== 'rename';
  checkPackage();
};
$('local-pkg').oninput = checkPackage;

function checkPackage() {
  if (activeTab === 'download') {
    const p = $('pkg').value.trim();
    const bad = $('mode').value === 'side' && (!PKG_RE.test(p) || p === 'com.discord' || p.length > 127);
    $('pkg').setAttribute('aria-invalid', String(bad));
    return !bad;
  }
  if ($('local-mode').value === 'rename') {
    const p = $('local-pkg').value.trim();
    const bad = !PKG_RE.test(p) || p.length > 127;
    $('local-pkg').setAttribute('aria-invalid', String(bad));
    return !bad;
  }
  return true;
}

// Local APK picker + drag & drop
function setLocalFiles(fileList) {
  localPickedFiles = [...fileList];
  const ul = $('local-list');
  ul.textContent = '';
  if (!localPickedFiles.length) {
    ul.hidden = true;
    return;
  }
  for (const f of localPickedFiles) {
    const li = document.createElement('li');
    const code = document.createElement('code');
    code.textContent = f.name;
    const sz = document.createElement('span');
    sz.textContent = mb(f.size);
    li.append(code, sz);
    ul.append(li);
  }
  ul.hidden = false;
  $('err').hidden = true;
}

$('local-files').onchange = (ev) => setLocalFiles(ev.target.files);
const dz = $('dropzone');
dz.addEventListener('dragover', (ev) => {
  ev.preventDefault();
  dz.classList.add('drag');
});
dz.addEventListener('dragleave', () => dz.classList.remove('drag'));
dz.addEventListener('drop', (ev) => {
  ev.preventDefault();
  dz.classList.remove('drag');
  if (ev.dataTransfer?.files?.length) setLocalFiles(ev.dataTransfer.files);
});

// --- Progress & logging helpers ---------------------------------------------------------

function step(label) {
  const li = document.createElement('li');
  li.innerHTML = '<span></span><span class="state">waiting</span><span class="bar"><i></i></span>';
  li.firstChild.textContent = label;
  $('steps').append(li);
  const st = li.querySelector('.state');
  const bar = li.querySelector('i');
  return {
    progress(f, text) {
      bar.style.width = `${Math.max(0, Math.min(100, Math.round(f * 100)))}%`;
      st.textContent = text ?? `${Math.round(f * 100)}%`;
    },
    done(text = 'done') {
      li.className = 'done';
      bar.style.width = '100%';
      st.textContent = text;
    },
    fail(text = 'failed') {
      li.className = 'fail';
      st.textContent = text;
    },
  };
}

function log(msg) {
  $('log').textContent += msg + '\n';
  $('log').scrollTop = 1e9;
}

const mb = (n) => `${(n / 1048576).toFixed(1)} MB`;

function showError(err) {
  console.error(err);
  $('err').textContent = `Error: ${err?.message || err}`;
  $('err').hidden = false;
  $('go').disabled = false;
}

// --- Fetch Cinder payload & signing identity --------------------------------------------

async function loadPayloadAndIdentity() {
  const s = step('Load Cinder runtime & Mana UI SDK');
  try {
    if (!cachedIdentityJson) {
      const r = await fetch('signing-key.json');
      if (!r.ok) throw new Error(`signing-key.json: HTTP ${r.status}`);
      cachedIdentityJson = await r.json();
    }
    if (!cachedPayload) {
      const mr = await fetch('payload/manifest.json');
      if (!mr.ok) throw new Error(`payload/manifest.json: HTTP ${mr.status}`);
      const m = await mr.json();
      const fetchBuf = async (rel) => {
        const res = await fetch(`payload/${rel}`);
        if (!res.ok) throw new Error(`payload/${rel}: HTTP ${res.status}`);
        return new Uint8Array(await res.arrayBuffer());
      };
      s.progress(0.3, 'fetching…');
      const [loaderDex, runtimeSo, ...pluginDatas] = await Promise.all([
        fetchBuf(m.loaderDex),
        fetchBuf(m.runtimeSo),
        ...(m.pluginLibs || []).map((name) => fetchBuf(name)),
      ]);
      cachedPayload = {
        loaderDex,
        runtimeSo,
        pluginLibs: (m.pluginLibs || []).map((name, i) => ({ name, data: pluginDatas[i] })),
      };
    }
    const total =
      cachedPayload.loaderDex.length +
      cachedPayload.runtimeSo.length +
      cachedPayload.pluginLibs.reduce((acc, x) => acc + x.data.length, 0);
    s.done(`${(total / 1024).toFixed(0)} KB`);
    return { identityJson: cachedIdentityJson, payload: cachedPayload };
  } catch (e) {
    s.fail('failed');
    throw e;
  }
}

// --- Streaming tracker downloads --------------------------------------------------------

async function downloadSplit(vc, split, optional = false) {
  const s = step(`Download ${split}`);
  const res = await fetch(`${PROXY}/tracker/download/${vc}/${split}`);
  if (!res.ok) {
    if (optional) {
      s.done('not mirrored');
      return null;
    }
    s.fail(`HTTP ${res.status}`);
    throw new Error(`${split}: HTTP ${res.status}`);
  }
  const total = +res.headers.get('Content-Length') || 0;
  const reader = res.body.getReader();
  let buf = new Uint8Array(total || 1 << 23);
  let n = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    if (n + value.length > buf.length) {
      const grown = new Uint8Array(Math.max(buf.length * 2, n + value.length));
      grown.set(buf.subarray(0, n));
      buf = grown;
    }
    buf.set(value, n);
    n += value.length;
    s.progress(total ? n / total : 0, total ? `${mb(n)} / ${mb(total)}` : mb(n));
  }
  s.done(mb(n));
  return buf.length === n ? buf : buf.slice(0, n);
}

async function downloadDensity(vc) {
  const want = DENSITIES.find(([id]) => id === $('dpi').value)[1];
  const order = [...DENSITIES].sort((a, b) => Math.abs(a[1] - want) - Math.abs(b[1] - want) || b[1] - a[1]);
  for (const [id] of order) {
    const buf = await downloadSplit(vc, `config.${id}`, true);
    if (buf) return buf;
  }
  throw new Error('this Discord build has no density split mirrored on the tracker');
}

function splitNames() {
  const lang = $('lang').value;
  return {
    abi: 'config.arm64_v8a',
    density: `config.${$('dpi').value}`,
    lang: lang === 'none' ? null : `config.${lang}`,
  };
}

// --- Run patcher in Web Worker (with main-thread fallback) ------------------------------

const STAGE_LABEL = {
  reading: 'Reading APK…',
  dex: 'Patching DEX hooks…',
  elf: 'Patching native .so…',
  inject: 'Injecting Cinder runtime…',
  sign: 'Signing APK v2…',
  done: 'Done',
};

function runPatch({ base, abi, configs, identityJson, payload, packageName, label, onProgress }) {
  return new Promise((resolve, reject) => {
    let worker;
    try {
      worker = new Worker(new URL('./patch-worker.js', import.meta.url), { type: 'module' });
    } catch {
      worker = null;
    }

    if (!worker) {
      // Fallback on main thread if browser lacks ES module workers
      buildPatchedApk({
        base,
        abi,
        configs,
        identity: decodeIdentity(identityJson),
        payload,
        packageName,
        label,
        log,
        onProgress,
      })
        .then((res) =>
          resolve({
            blob: new Blob(res.parts, { type: 'application/vnd.android.package-archive' }),
            manifest: res.manifest,
            origPkg: res.origPkg,
            targetPkg: res.targetPkg,
          }),
        )
        .catch(reject);
      return;
    }

    worker.onmessage = (ev) => {
      const d = ev.data;
      if (d.type === 'log') log(d.msg);
      else if (d.type === 'progress') onProgress(d.stage, d.frac);
      else if (d.type === 'done') {
        worker.terminate();
        resolve(d);
      } else if (d.type === 'error') {
        worker.terminate();
        reject(new Error(d.message));
      }
    };
    worker.onerror = (ev) => {
      worker.terminate();
      reject(new Error(ev.message || 'patch worker crashed'));
    };

    // Pass buffers directly (no copy needed if transferred? Keep payload reusable across retries)
    worker.postMessage({ base, abi, configs, identityJson, payload, packageName, label });
  });
}

async function patchAndOffer({ base, abi, configs, packageName, label, fileTag }) {
  const { identityJson, payload } = await loadPayloadAndIdentity();
  const s = step('Patch & sign APK');
  s.progress(0.05, 'starting…');
  try {
    const res = await runPatch({
      base,
      abi,
      configs,
      identityJson,
      payload,
      packageName,
      label,
      onProgress: (stage, frac) => s.progress(frac, STAGE_LABEL[stage] || stage),
    });
    s.done(mb(res.blob.size));

    if (lastBlobUrl) URL.revokeObjectURL(lastBlobUrl);
    lastBlobUrl = URL.createObjectURL(res.blob);

    const isReplace = res.targetPkg === 'com.discord';
    $('note-side').hidden = isReplace;
    $('note-replace').hidden = !isReplace;
    $('res-pkg').textContent = res.targetPkg;

    const a = $('dl');
    a.href = lastBlobUrl;
    a.download = `discord-${fileTag}-cinder${isReplace ? '' : '-side'}.apk`;
    $('result').hidden = false;
    a.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  } catch (e) {
    s.fail('failed');
    throw e;
  }
}

// --- Manual tracker download fallback ---------------------------------------------------

function showManual(vc) {
  const n = splitNames();
  const splits = ['base', n.abi, n.density, n.lang].filter(Boolean);
  $('links').innerHTML = splits
    .map(
      (s) =>
        `<li><a href="${TRACKER}/tracker/download/${vc}/${s}" target="_blank" rel="noopener">${s}.apk</a></li>`,
    )
    .join('');
  $('manual').hidden = false;
  $('go').disabled = false;

  $('manual-files').onchange = async (ev) => {
    try {
      const picked = [...ev.target.files];
      if (!picked.length) return;
      $('go').disabled = true;
      $('err').hidden = true;
      $('result').hidden = true;
      const loaded = await Promise.all(
        picked.map(async (f) => ({ name: f.name, data: new Uint8Array(await f.arrayBuffer()) })),
      );
      const classified = await classifyApkFiles(loaded);
      const targetPkg = $('mode').value === 'replace' ? 'com.discord' : $('pkg').value.trim();
      const side = targetPkg !== 'com.discord';
      await patchAndOffer({
        base: classified.base,
        abi: classified.abi,
        configs: classified.configs,
        packageName: targetPkg,
        label: side ? 'Discord Cinder' : null,
        fileTag: String(vc),
      });
      $('go').disabled = false;
    } catch (e) {
      showError(e);
    }
  };
}

// --- Primary action button --------------------------------------------------------------

$('go').onclick = async () => {
  if (!checkPackage()) {
    $('err').textContent =
      'Package name must look like com.example.app (letters, digits, _; at least two segments) and differ from com.discord for side-by-side install.';
    $('err').hidden = false;
    return;
  }

  $('go').disabled = true;
  $('err').hidden = true;
  $('result').hidden = true;
  $('manual').hidden = true;
  $('steps').textContent = '';
  $('log').textContent = '';
  $('progress').hidden = false;

  if (activeTab === 'local') {
    try {
      if (!localPickedFiles.length) {
        throw new Error('Pick or drop a Discord APK (or split APKs) first.');
      }
      const s = step('Read local APK file(s)');
      const loaded = await Promise.all(
        localPickedFiles.map(async (f) => ({ name: f.name, data: new Uint8Array(await f.arrayBuffer()) })),
      );
      const classified = await classifyApkFiles(loaded);
      s.done(classified.monolithic ? 'monolithic APK' : `base + arm64 + ${classified.configs.length} split(s)`);

      const rename = $('local-mode').value === 'rename';
      const targetPkg = rename ? $('local-pkg').value.trim() : null;
      const label = rename ? 'Discord Cinder' : null;
      const fileTag = classified.manifest?.versionCode ? String(classified.manifest.versionCode) : 'release';

      await patchAndOffer({
        base: classified.base,
        abi: classified.abi,
        configs: classified.configs,
        packageName: targetPkg,
        label,
        fileTag,
      });
      $('go').disabled = false;
    } catch (e) {
      showError(e);
    }
    return;
  }

  // Download mode (tracker.vendetta.rocks via CORS proxy)
  const vc = parseInt($('ver').value, 10);
  if (!PROXY) {
    log('No CORS proxy configured in config.js; showing manual download links.');
    showManual(vc);
    return;
  }
  try {
    const n = splitNames();
    const base = await downloadSplit(vc, 'base');
    const abi = await downloadSplit(vc, n.abi);
    const configs = [await downloadDensity(vc), n.lang && (await downloadSplit(vc, n.lang, true))].filter(Boolean);
    const targetPkg = $('mode').value === 'replace' ? 'com.discord' : $('pkg').value.trim();
    const side = targetPkg !== 'com.discord';
    await patchAndOffer({
      base,
      abi,
      configs,
      packageName: targetPkg,
      label: side ? 'Discord Cinder' : null,
      fileTag: String(vc),
    });
    $('go').disabled = false;
  } catch (e) {
    if (e instanceof TypeError) {
      log(`Automatic download blocked (${e.message}); switching to manual mode.`);
      showManual(vc);
      return;
    }
    showError(e);
  }
};

initSelectors();
