#!/usr/bin/env node
// Local development server for `mobile/web/`.
// Serves static web assets AND acts as a local CORS proxy for `/tracker/*`
// (serving from a local split directory if passed on CLI, otherwise forwarding to tracker.vendetta.rocks).
//
// Usage:
//   node mobile/web/tools/dev-server.mjs [local-splits-dir] [port]
// Then open:
//   http://127.0.0.1:8787/?proxy=http://127.0.0.1:8787

import { createReadStream, existsSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const LOCAL_SPLITS = process.argv[2] ? resolve(process.argv[2]) : null;
const PORT = parseInt(process.argv[3] || '8787', 10);
const UPSTREAM = 'https://tracker.vendetta.rocks';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.dex': 'application/octet-stream',
  '.so': 'application/octet-stream',
  '.apk': 'application/vnd.android.package-archive',
};

const SPLIT_RE = /^\/tracker\/download\/(\d{6,9})\/(base|config\.[A-Za-z0-9_]+)$/;

const server = createServer(async (req, res) => {
  const url = new URL(req.url || '/', `http://127.0.0.1:${PORT}`);
  const pathname = url.pathname;

  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  res.setHeader('Access-Control-Expose-Headers', 'Content-Length');
  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  // Local/proxied tracker endpoints
  if (pathname === '/tracker/index') {
    if (LOCAL_SPLITS) {
      const body = JSON.stringify({ latest: { alpha: 356207 } });
      res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
      res.end(body);
      return;
    }
    const up = await fetch(`${UPSTREAM}/tracker/index`);
    const body = Buffer.from(await up.arrayBuffer());
    res.writeHead(up.status, { 'Content-Type': up.headers.get('content-type') || 'application/json', 'Content-Length': body.length });
    res.end(body);
    return;
  }

  const m = SPLIT_RE.exec(pathname);
  if (m) {
    const split = m[2];
    if (LOCAL_SPLITS) {
      const fp = join(LOCAL_SPLITS, `${split}.apk`);
      if (!existsSync(fp)) {
        res.writeHead(404);
        res.end('split not in local dir');
        return;
      }
      const st = statSync(fp);
      res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': st.size });
      createReadStream(fp).pipe(res);
      return;
    }
    const up = await fetch(`${UPSTREAM}${pathname}`, { redirect: 'follow' });
    const headers = { 'Content-Type': up.headers.get('content-type') || 'application/octet-stream' };
    if (up.headers.get('content-length')) headers['Content-Length'] = up.headers.get('content-length');
    res.writeHead(up.status, headers);
    if (!up.body) {
      res.end();
      return;
    }
    for await (const chunk of up.body) res.write(chunk);
    res.end();
    return;
  }

  // Static files from mobile/web/
  const rel = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '');
  const full = resolve(WEB_DIR, rel);
  if (!full.startsWith(WEB_DIR) || !existsSync(full) || statSync(full).isDirectory()) {
    res.writeHead(404);
    res.end('not found');
    return;
  }
  const st = statSync(full);
  res.writeHead(200, {
    'Content-Type': MIME[extname(full)] || 'application/octet-stream',
    'Content-Length': st.size,
    'Cache-Control': 'no-cache',
  });
  createReadStream(full).pipe(res);
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`Cinder Mobile web patcher listening at:`);
  console.log(`  http://127.0.0.1:${PORT}/?proxy=http://127.0.0.1:${PORT}`);
  if (LOCAL_SPLITS) console.log(`  serving tracker splits from: ${LOCAL_SPLITS}`);
});
