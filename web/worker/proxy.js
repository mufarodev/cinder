// Cloudflare Worker: serves the Cinder Mobile Web Patcher static assets (via ASSETS)
// and provides a CORS pass-through + edge cache for tracker.vendetta.rocks.
// The tracker redirects to Google's Play CDN which omits CORS headers; this worker allows
// ONLY `/tracker/index` and `/tracker/download/<versionCode>/<split>` and caches immutable
// APK splits at the edge for a year.
//
// Deploy from web/:
//   npx wrangler deploy

const UPSTREAM = 'https://tracker.vendetta.rocks';
const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, OPTIONS',
  'Access-Control-Expose-Headers': 'Content-Length, X-Cache',
};
const SPLIT_RE = /^\/tracker\/download\/\d{6,9}\/(base|config\.[A-Za-z0-9_]+)$/;

export default {
  async fetch(req, env, ctx) {
    if (req.method === 'OPTIONS') return new Response(null, { headers: CORS });
    const { pathname } = new URL(req.url);
    const isSplit = SPLIT_RE.test(pathname);
    if (req.method === 'GET' && (isSplit || pathname === '/tracker/index')) {
      const cache = caches.default;
      const key = new Request(`https://cinder-apk-proxy.cache${pathname}`);
      const hit = await cache.match(key);
      if (hit) return withHeaders(hit, 'HIT');

      const up = await fetch(UPSTREAM + pathname, { redirect: 'follow' });
      const headers = new Headers({
        'Content-Type': up.headers.get('Content-Type') || 'application/octet-stream',
      });
      if (up.headers.get('Content-Length')) {
        headers.set('Content-Length', up.headers.get('Content-Length'));
      }
      headers.set(
        'Cache-Control',
        up.ok && isSplit ? 'public, max-age=31536000, immutable' : 'public, max-age=300',
      );
      const res = new Response(up.body, { status: up.status, headers });
      if (up.ok || up.status === 404) ctx.waitUntil(cache.put(key, res.clone()));
      return withHeaders(res, 'MISS');
    }

    if (env?.ASSETS) {
      return env.ASSETS.fetch(req);
    }
    return new Response('not found', { status: 404, headers: CORS });
  },
};

function withHeaders(res, state) {
  const h = new Headers(res.headers);
  for (const [k, v] of Object.entries(CORS)) h.set(k, v);
  h.set('X-Cache', state);
  return new Response(res.body, { status: res.status, headers: h });
}
