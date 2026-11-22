// Cinder Mobile Web Patcher configuration.
//
// PROXY: Cloudflare Worker CORS pass-through for tracker.vendetta.rocks (see worker/proxy.js).
//   - Deployed alongside the static site via `npx wrangler deploy` in web/
//   - Override at runtime via `?proxy=https://...` (or `?proxy=` to disable)
export const PROXY = 'https://cinder-mobile.mufaro.workers.dev';

// Upstream Discord APK tracker (used directly for manual download links when PROXY is empty/blocked).
export const TRACKER = 'https://tracker.vendetta.rocks';

// Play Store Discord Alpha versionCodes verified against Cinder's ELF + DEX patchers.
// Newer Alpha builds are attempted too; the patcher fails loudly if binary anchors shift.
export const VERIFIED = [356207];
