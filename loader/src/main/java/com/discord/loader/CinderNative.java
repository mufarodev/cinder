package com.discord.loader;

import android.content.Context;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CinderNative {
  public static final String MANIFEST_ASSET = "cinder-patches.json";
  public static final String FLAG_STAFF_DEVTOOLS = "staff.devtools";

  public interface Backend {
    long getHostBase();

    String getHostBuildId();

    boolean writeHostFlag(long vaddr, int value);

    int readHostFlag(long vaddr);
  }

  public static final class PatchManifestInfo {
    public final String buildId;
    public final List<String> patchTags;
    public final Map<String, Long> runtimeFlags;
    public final Map<String, Integer> runtimeFlagDefaults;
    public final boolean loaded;

    public PatchManifestInfo(
        String buildId,
        List<String> patchTags,
        Map<String, Long> runtimeFlags,
        Map<String, Integer> runtimeFlagDefaults,
        boolean loaded) {
      this.buildId = buildId != null ? buildId : "";
      this.patchTags = Collections.unmodifiableList(new ArrayList<String>(patchTags));
      this.runtimeFlags =
          Collections.unmodifiableMap(new LinkedHashMap<String, Long>(runtimeFlags));
      this.runtimeFlagDefaults =
          Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(runtimeFlagDefaults));
      this.loaded = loaded;
    }

    public static PatchManifestInfo empty() {
      return new PatchManifestInfo(
          "",
          Collections.<String>emptyList(),
          Collections.<String, Long>emptyMap(),
          Collections.<String, Integer>emptyMap(),
          false);
    }

    public long flagVaddr(String name) {
      Long v = runtimeFlags.get(name);
      return v != null ? v.longValue() : -1L;
    }
  }

  private static native long nativeGetHostBase();

  private static native String nativeGetHostBuildId();

  private static native boolean nativeWriteHostFlag(long vaddr, int value);

  private static native int nativeReadHostFlag(long vaddr);

  private static final Backend DEFAULT_BACKEND =
      new Backend() {
        @Override
        public long getHostBase() {
          try {
            return nativeGetHostBase();
          } catch (Throwable ignored) {
            return 0L;
          }
        }

        @Override
        public String getHostBuildId() {
          try {
            return nativeGetHostBuildId();
          } catch (Throwable ignored) {
            return null;
          }
        }

        @Override
        public boolean writeHostFlag(long vaddr, int value) {
          try {
            return nativeWriteHostFlag(vaddr, value);
          } catch (Throwable ignored) {
            return false;
          }
        }

        @Override
        public int readHostFlag(long vaddr) {
          try {
            return nativeReadHostFlag(vaddr);
          } catch (Throwable ignored) {
            return -1;
          }
        }
      };

  private static volatile Backend backend = DEFAULT_BACKEND;
  private static volatile PatchManifestInfo manifestInfo = PatchManifestInfo.empty();
  private static volatile boolean desiredStaffDevTools = true;
  private static volatile boolean hasDesiredStaffDevTools = false;
  private static volatile boolean pendingStaffDevTools = false;
  private static volatile boolean buildDriftLogged = false;

  private CinderNative() {}

  public static void init(Context context) {
    if (context == null) return;
    try {
      InputStream in = context.getAssets().open(MANIFEST_ASSET);
      try {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) {
          out.write(buf, 0, n);
        }
        String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
        manifestInfo = parseManifestJson(json);
        Log.i(
            Loader.TAG,
            "CinderNative loaded "
                + MANIFEST_ASSET
                + " (buildId="
                + (manifestInfo.buildId.isEmpty() ? "none" : manifestInfo.buildId)
                + ", patches="
                + manifestInfo.patchTags.size()
                + ", runtimeFlags="
                + manifestInfo.runtimeFlags.size()
                + ")");
      } finally {
        try {
          in.close();
        } catch (Throwable ignored) {
        }
      }
    } catch (Throwable t) {
      Log.w(Loader.TAG, "CinderNative could not read assets/" + MANIFEST_ASSET + ": " + t);
    }
  }

  public static PatchManifestInfo getPatchManifestInfo() {
    return manifestInfo;
  }

  public static long getHostBase() {
    return backend.getHostBase();
  }

  public static String getLiveBuildId() {
    String bid = backend.getHostBuildId();
    return (bid != null && !bid.isEmpty()) ? bid.toLowerCase(Locale.ROOT) : null;
  }

  public static boolean isStaffDevToolsSupported() {
    return manifestInfo.flagVaddr(FLAG_STAFF_DEVTOOLS) > 0L;
  }

  public static long getStaffDevToolsFlagVaddr() {
    return manifestInfo.flagVaddr(FLAG_STAFF_DEVTOOLS);
  }

  public static boolean verifyHostBuildId() {
    String expected = manifestInfo.buildId;
    String live = getLiveBuildId();
    if (expected == null || expected.isEmpty()) {
      return live != null && !live.isEmpty();
    }
    if (live == null || live.isEmpty()) {
      return false;
    }
    return expected.equalsIgnoreCase(live);
  }

  public static int readStaffDevToolsFlag() {
    long vaddr = getStaffDevToolsFlagVaddr();
    if (vaddr <= 0L) return -1;
    if (getHostBase() == 0L) return -1;
    if (!verifyHostBuildId()) return -1;
    return backend.readHostFlag(vaddr);
  }

  public static boolean setStaffDevTools(boolean enabled) {
    desiredStaffDevTools = enabled;
    hasDesiredStaffDevTools = true;
    pendingStaffDevTools = true;
    return tryFlushStaffDevTools();
  }

  public static void flushPendingIfNeeded() {
    if (!pendingStaffDevTools) return;
    tryFlushStaffDevTools();
  }

  public static boolean hasPendingStaffDevTools() {
    return pendingStaffDevTools;
  }

  private static synchronized boolean tryFlushStaffDevTools() {
    if (!hasDesiredStaffDevTools) {
      pendingStaffDevTools = false;
      return false;
    }
    long vaddr = getStaffDevToolsFlagVaddr();
    if (vaddr <= 0L) {
      pendingStaffDevTools = false;
      return false;
    }
    long base = backend.getHostBase();
    if (base == 0L) {

      return false;
    }
    String expected = manifestInfo.buildId;
    String live = getLiveBuildId();
    if (expected != null && !expected.isEmpty()) {
      if (live == null || !expected.equalsIgnoreCase(live)) {
        if (!buildDriftLogged) {
          buildDriftLogged = true;
          Log.w(
              Loader.TAG,
              "CinderNative BUILD_DRIFT: live="
                  + live
                  + " != manifest="
                  + expected
                  + "; refusing to write staff.devtools flag");
        }
        pendingStaffDevTools = false;
        return false;
      }
    }
    int val = desiredStaffDevTools ? 1 : 0;
    boolean ok = backend.writeHostFlag(vaddr, val);
    if (ok) {
      pendingStaffDevTools = false;
      Log.i(
          Loader.TAG,
          "CinderNative: staff.devtools .bss flag @ 0x"
              + Long.toHexString(vaddr)
              + " -> 0x"
              + val
              + " (hostBase=0x"
              + Long.toHexString(base)
              + ", buildId="
              + (live != null ? live : "unverified")
              + ")");
    } else {
      Log.w(
          Loader.TAG, "CinderNative: writeHostFlag failed for vaddr=0x" + Long.toHexString(vaddr));
    }
    return ok;
  }

  public static PatchManifestInfo parseManifestJson(String json) {
    if (json == null || json.isEmpty()) return PatchManifestInfo.empty();
    try {
      JsonScanner sc = new JsonScanner(json);
      if (!sc.expect('{')) return PatchManifestInfo.empty();

      String buildId = "";
      List<String> patchTags = new ArrayList<String>();
      Map<String, Long> runtimeFlags = new LinkedHashMap<String, Long>();
      Map<String, Integer> runtimeFlagDefaults = new LinkedHashMap<String, Integer>();

      if (sc.peek() != '}') {
        while (true) {
          String key = sc.parseString();
          if (key == null || !sc.expect(':')) break;
          if ("build_id".equals(key)) {
            String bid = sc.parseString();
            if (bid != null) buildId = bid.toLowerCase(Locale.ROOT);
          } else if ("patches".equals(key)) {
            parsePatchesArray(sc, patchTags);
          } else if ("runtime_flags".equals(key)) {
            parseRuntimeFlagsObject(sc, runtimeFlags, runtimeFlagDefaults);
          } else {
            sc.skipValue();
          }
          int next = sc.peek();
          if (next == ',') {
            sc.pos++;
          } else {
            break;
          }
        }
      }
      sc.expect('}');
      return new PatchManifestInfo(buildId, patchTags, runtimeFlags, runtimeFlagDefaults, true);
    } catch (Throwable t) {
      Log.w(Loader.TAG, "CinderNative: failed parsing manifest JSON: " + t);
      return PatchManifestInfo.empty();
    }
  }

  private static void parsePatchesArray(JsonScanner sc, List<String> tagsOut) {
    if (!sc.expect('[')) return;
    if (sc.peek() == ']') {
      sc.pos++;
      return;
    }
    while (true) {
      if (sc.expect('{')) {
        if (sc.peek() != '}') {
          while (true) {
            String k = sc.parseString();
            if (k == null || !sc.expect(':')) break;
            if ("tag".equals(k)) {
              String tag = sc.parseString();
              if (tag != null && !tag.isEmpty()) tagsOut.add(tag);
            } else {
              sc.skipValue();
            }
            if (sc.peek() == ',') {
              sc.pos++;
            } else {
              break;
            }
          }
        }
        sc.expect('}');
      } else {
        sc.skipValue();
      }
      if (sc.peek() == ',') {
        sc.pos++;
      } else {
        break;
      }
    }
    sc.expect(']');
  }

  private static void parseRuntimeFlagsObject(
      JsonScanner sc, Map<String, Long> flagsOut, Map<String, Integer> defaultsOut) {
    if (!sc.expect('{')) return;
    if (sc.peek() == '}') {
      sc.pos++;
      return;
    }
    while (true) {
      String flagName = sc.parseString();
      if (flagName == null || !sc.expect(':')) break;
      if (sc.expect('{')) {
        long vaddr = -1L;
        int defVal = 1;
        if (sc.peek() != '}') {
          while (true) {
            String fk = sc.parseString();
            if (fk == null || !sc.expect(':')) break;
            if ("vaddr".equals(fk)) {
              vaddr = sc.parseFlexibleLong(-1L);
            } else if ("default".equals(fk)) {
              defVal = (int) sc.parseFlexibleLong(1L);
            } else {
              sc.skipValue();
            }
            if (sc.peek() == ',') {
              sc.pos++;
            } else {
              break;
            }
          }
        }
        sc.expect('}');
        if (vaddr > 0L) {
          flagsOut.put(flagName, Long.valueOf(vaddr));
          defaultsOut.put(flagName, Integer.valueOf(defVal));
        }
      } else {
        sc.skipValue();
      }
      if (sc.peek() == ',') {
        sc.pos++;
      } else {
        break;
      }
    }
    sc.expect('}');
  }

  private static final class JsonScanner {
    final String s;
    final int len;
    int pos;

    JsonScanner(String s) {
      this.s = s;
      this.len = s.length();
    }

    void skipWs() {
      while (pos < len) {
        char c = s.charAt(pos);
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
          pos++;
        } else {
          break;
        }
      }
    }

    int peek() {
      skipWs();
      return pos < len ? s.charAt(pos) : -1;
    }

    boolean expect(char ch) {
      skipWs();
      if (pos < len && s.charAt(pos) == ch) {
        pos++;
        return true;
      }
      return false;
    }

    String parseString() {
      if (!expect('"')) return null;
      StringBuilder sb = new StringBuilder();
      while (pos < len) {
        char c = s.charAt(pos++);
        if (c == '"') return sb.toString();
        if (c == '\\' && pos < len) {
          char esc = s.charAt(pos++);
          switch (esc) {
            case '"':
              sb.append('"');
              break;
            case '\\':
              sb.append('\\');
              break;
            case '/':
              sb.append('/');
              break;
            case 'n':
              sb.append('\n');
              break;
            case 'r':
              sb.append('\r');
              break;
            case 't':
              sb.append('\t');
              break;
            default:
              sb.append(esc);
              break;
          }
        } else {
          sb.append(c);
        }
      }
      return null;
    }

    long parseFlexibleLong(long fallback) {
      int p = peek();
      String raw;
      if (p == '"') {
        raw = parseString();
      } else {
        int start = pos;
        while (pos < len) {
          char c = s.charAt(pos);
          if (c == ',' || c == '}' || c == ']' || c <= ' ') break;
          pos++;
        }
        raw = s.substring(start, pos);
      }
      if (raw == null || raw.isEmpty()) return fallback;
      raw = raw.trim();
      try {
        if (raw.startsWith("0x") || raw.startsWith("0X")) {
          return Long.parseLong(raw.substring(2), 16);
        }
        return Long.parseLong(raw);
      } catch (NumberFormatException e) {
        return fallback;
      }
    }

    void skipValue() {
      int p = peek();
      if (p == '"') {
        parseString();
      } else if (p == '{') {
        pos++;
        if (peek() == '}') {
          pos++;
          return;
        }
        while (pos < len) {
          parseString();
          expect(':');
          skipValue();
          int n = peek();
          if (n == ',') {
            pos++;
          } else if (n == '}') {
            pos++;
            return;
          } else {
            return;
          }
        }
      } else if (p == '[') {
        pos++;
        if (peek() == ']') {
          pos++;
          return;
        }
        while (pos < len) {
          skipValue();
          int n = peek();
          if (n == ',') {
            pos++;
          } else if (n == ']') {
            pos++;
            return;
          } else {
            return;
          }
        }
      } else {
        while (pos < len) {
          char c = s.charAt(pos);
          if (c == ',' || c == '}' || c == ']' || c <= ' ') break;
          pos++;
        }
      }
    }
  }

  public static void setBackendForTests(Backend testBackend) {
    backend = testBackend != null ? testBackend : DEFAULT_BACKEND;
  }

  public static void setManifestForTests(PatchManifestInfo info) {
    manifestInfo = info != null ? info : PatchManifestInfo.empty();
  }

  public static void resetForTests() {
    backend = DEFAULT_BACKEND;
    manifestInfo = PatchManifestInfo.empty();
    desiredStaffDevTools = true;
    hasDesiredStaffDevTools = false;
    pendingStaffDevTools = false;
    buildDriftLogged = false;
  }
}
