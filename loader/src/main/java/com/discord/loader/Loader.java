package com.discord.loader;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import com.discord.loader.mana.ManaDslRuntime;
import com.discord.loader.mana.ThemeEngine;
import dalvik.system.DexClassLoader;
import dalvik.system.InMemoryDexClassLoader;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

public final class Loader {
  public static final String TAG = "DiscordLoader";

  private static final int MAX_DEX_BYTES = 64 * 1024 * 1024;
  private static final ModPlugin[] EMPTY_PLUGINS = new ModPlugin[0];

  private static volatile boolean initialized = false;
  private static volatile Context appContext = null;
  private static final List<ModPlugin> pluginList = new ArrayList<ModPlugin>();
  private static volatile ModPlugin[] pluginSnapshot = EMPTY_PLUGINS;

  private static final ConcurrentHashMap<String, Boolean> pluginEnabledCache =
      new ConcurrentHashMap<String, Boolean>();

  private static final ConcurrentHashMap<String, Boolean> bootEnabledSnapshot =
      new ConcurrentHashMap<String, Boolean>();

  private static volatile HostInvoker activeHost = null;

  private static volatile Handler guestHandler = null;
  private static volatile boolean guestHandlerSearched = false;

  private static volatile Executor syntheticExecutorForTests = null;

  private static volatile Runnable restartHookForTests = null;

  private static final ThreadLocal<Boolean> IN_SYNTHETIC_OPS = new ThreadLocal<Boolean>();

  public static final String PLUGIN_ENABLED_PREFIX = "plugin.enabled.";

  private static final String[] BUILTIN_PLUGINS = {
    "com.discord.loader.CinderSettings",
    "com.discord.loader.plugins.PhoenixAccessPlugin",
    "com.discord.loader.plugins.CinderDiagnosticsPlugin",
    "com.discord.loader.plugins.StaffDevToolsPlugin",
    "com.discord.loader.plugins.NoTelemetryPlugin",
    "com.discord.loader.plugins.MessageLoggerPlugin",
    "com.discord.loader.plugins.TextReplacePlugin",
  };

  private Loader() {}

  public static void init(Context context) {
    if (initialized) return;
    synchronized (Loader.class) {
      if (initialized) return;
      initialized = true;
      try {
        Context appCtx = context != null ? context.getApplicationContext() : null;
        if (appCtx == null) appCtx = context;
        appContext = appCtx;
        bootstrapNativeRuntime(appCtx);

        BridgeRegistry.init();
        CinderNative.init(appCtx);
        try {
          ManaDslRuntime.verifyStartup();
        } catch (Throwable t) {
          Log.w(TAG, "Mana Kotlin DSL startup check failed", t);
        }
        loadBuiltins(appCtx);
        if (appCtx != null) {
          discover(appCtx);
        }
        Log.i(TAG, "initialized with " + pluginSnapshot.length + " plugin(s)");
      } catch (Throwable t) {
        Log.e(TAG, "init failed", t);
      }
    }
  }

  public static Context getContext() {
    return appContext;
  }

  public static List<ModPlugin> getPlugins() {
    ModPlugin[] snap = pluginSnapshot;
    if (snap.length == 0) {
      return Collections.emptyList();
    }
    return Collections.unmodifiableList(Arrays.asList(Arrays.copyOf(snap, snap.length)));
  }

  public static ModPlugin[] plugins() {
    ModPlugin[] snap = pluginSnapshot;
    return Arrays.copyOf(snap, snap.length);
  }

  public static String pluginEnabledKey(String pluginId) {
    return PLUGIN_ENABLED_PREFIX + pluginId;
  }

  public static boolean isPluginEnabled(ModPlugin plugin) {
    if (plugin == null) return false;
    if (plugin.isRequired()) return true;
    String id = plugin.id();
    Boolean cached = pluginEnabledCache.get(id);
    if (cached != null) return cached.booleanValue();
    boolean resolved = CinderSettings.getToggleValue(pluginEnabledKey(id), plugin.defaultEnabled());
    pluginEnabledCache.put(id, Boolean.valueOf(resolved));
    return resolved;
  }

  public static boolean isPluginEnabled(String pluginId) {
    if (pluginId == null) return false;
    ModPlugin[] snap = pluginSnapshot;
    for (int i = 0; i < snap.length; i++) {
      if (pluginId.equals(snap[i].id())) {
        return isPluginEnabled(snap[i]);
      }
    }
    Boolean cached = pluginEnabledCache.get(pluginId);
    return cached != null && cached.booleanValue();
  }

  public static boolean setPluginEnabled(ModPlugin plugin, boolean enabled) {
    if (plugin == null) return false;
    if (plugin.isRequired()) {
      Log.w(TAG, "refusing to disable required Core plugin " + plugin.id());
      return false;
    }
    String id = plugin.id();
    boolean prev = isPluginEnabled(plugin);
    pluginEnabledCache.put(id, Boolean.valueOf(enabled));
    CinderSettings.setToggleValue(pluginEnabledKey(id), enabled);
    if (prev != enabled) {
      try {
        plugin.onEnabledChanged(enabled);
      } catch (Throwable t) {
        Log.e(TAG, "plugin onEnabledChanged threw (" + id + ")", t);
      }
      Log.i(
          TAG,
          "plugin "
              + id
              + " "
              + (enabled ? "enabled" : "disabled")
              + (plugin.requiresRestart() ? " (restart recommended)" : ""));
    }
    CinderSettings.notifyPluginStateChanged(id, enabled);
    return true;
  }

  public static boolean hasPendingRestart() {
    ModPlugin[] snap = pluginSnapshot;
    for (int i = 0; i < snap.length; i++) {
      if (isPluginPendingRestart(snap[i])) {
        return true;
      }
    }
    return false;
  }

  public static boolean isPluginPendingRestart(ModPlugin plugin) {
    if (plugin == null || plugin.isRequired() || !plugin.requiresRestart()) return false;
    Boolean bootState = bootEnabledSnapshot.get(plugin.id());
    if (bootState == null) return false;
    return isPluginEnabled(plugin) != bootState.booleanValue();
  }

  public static List<ModPlugin> pendingRestartPlugins() {
    ModPlugin[] snap = pluginSnapshot;
    List<ModPlugin> out = new ArrayList<ModPlugin>();
    for (int i = 0; i < snap.length; i++) {
      if (isPluginPendingRestart(snap[i])) {
        out.add(snap[i]);
      }
    }
    return Collections.unmodifiableList(out);
  }

  public static boolean restartApp() {
    Runnable hook = restartHookForTests;
    if (hook != null) {
      hook.run();
      return true;
    }
    Context ctx = appContext;
    if (ctx == null) {
      Log.w(TAG, "restartApp skipped: no application Context");
      return false;
    }
    try {
      Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
      if (launch == null) {
        Log.w(TAG, "restartApp failed: launch intent unavailable for " + ctx.getPackageName());
        return false;
      }
      launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
      ctx.startActivity(launch);
      Log.i(TAG, "restarting " + ctx.getPackageName());
      Process.killProcess(Process.myPid());
      return true;
    } catch (Throwable t) {
      Log.e(TAG, "restartApp failed", t);
      return false;
    }
  }

  public static void registerHost(HostInvoker host) {
    if (host == null) return;
    if (activeHost != host) {
      activeHost = host;
      guestHandler = null;
      guestHandlerSearched = false;
    }
    if (!guestHandlerSearched) {
      guestHandlerSearched = true;
      guestHandler = discoverGuestHandler(host);
    }
  }

  public static HostInvoker activeHost() {
    return activeHost;
  }

  public static boolean applySyntheticOps(byte[] ops) {
    if (ops == null || ops.length == 0) return false;
    final HostInvoker host = activeHost;
    if (host == null) {
      Log.w(TAG, "applySyntheticOps dropped: no active GuestHost captured yet");
      return false;
    }
    final byte[] copy = Arrays.copyOf(ops, ops.length);
    final Runnable task =
        new Runnable() {
          @Override
          public void run() {
            Boolean prev = IN_SYNTHETIC_OPS.get();
            IN_SYNTHETIC_OPS.set(Boolean.TRUE);
            try {
              host.applyOps(copy, copy.length);
            } catch (Throwable t) {
              Log.e(TAG, "applySyntheticOps execution failed", t);
            } finally {
              if (Boolean.TRUE.equals(prev)) {
                IN_SYNTHETIC_OPS.set(Boolean.TRUE);
              } else {
                IN_SYNTHETIC_OPS.remove();
              }
            }
          }
        };

    Executor testExec = syntheticExecutorForTests;
    if (testExec != null) {
      testExec.execute(task);
      return true;
    }

    Handler h = guestHandler;
    if (h == null && !guestHandlerSearched) {
      guestHandlerSearched = true;
      h = discoverGuestHandler(host);
      guestHandler = h;
    }
    if (h != null) {
      return h.post(task);
    }

    if ("wasm-guest".equals(Thread.currentThread().getName())) {
      task.run();
      return true;
    }
    Log.e(
        TAG,
        "applySyntheticOps refused: wasm-guest Handler unavailable on thread "
            + Thread.currentThread().getName());
    return false;
  }

  public static boolean applySyntheticOps(UiOps ops) {
    if (ops == null || ops.isEmpty()) return false;
    return applySyntheticOps(ops.toBytes());
  }

  private static Handler discoverGuestHandler(Object host) {
    try {
      Looper mainLooper = null;
      try {
        mainLooper = Looper.getMainLooper();
      } catch (Throwable ignored) {
      }
      Handler fallback = null;
      for (Class<?> c = host.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
        Field[] fields = c.getDeclaredFields();
        for (int i = 0; i < fields.length; i++) {
          Field f = fields[i];
          if (!Handler.class.isAssignableFrom(f.getType())) continue;
          f.setAccessible(true);
          Object val = f.get(host);
          if (!(val instanceof Handler)) continue;
          Handler h = (Handler) val;
          Looper looper = h.getLooper();
          if (looper == null) continue;
          Thread t = looper.getThread();
          if (t != null && "wasm-guest".equals(t.getName())) {
            return h;
          }
          if (mainLooper != null && looper != mainLooper && fallback == null) {
            fallback = h;
          }
        }
      }
      return fallback;
    } catch (Throwable t) {
      Log.w(TAG, "discoverGuestHandler failed", t);
      return null;
    }
  }

  private static void initPluginLifecycleState(ModPlugin p) {
    String id = p.id();
    boolean initialEnabled =
        p.isRequired() || CinderSettings.getToggleValue(pluginEnabledKey(id), p.defaultEnabled());
    pluginEnabledCache.put(id, Boolean.valueOf(initialEnabled));
    if (!p.isRequired() && p.requiresRestart()) {
      bootEnabledSnapshot.putIfAbsent(id, Boolean.valueOf(initialEnabled));
    }
  }

  private static void registerPlugin(ModPlugin p, Context context, String source) {
    initPluginLifecycleState(p);
    p.onLoad(context);
    pluginList.add(p);
    pluginSnapshot = pluginList.toArray(new ModPlugin[pluginList.size()]);
    Log.i(
        TAG,
        source
            + " plugin "
            + p.id()
            + " ("
            + p.version()
            + ") loaded ["
            + (p.isRequired() ? "core" : (isPluginEnabled(p) ? "enabled" : "disabled"))
            + "]");
  }

  private static void loadBuiltins(Context context) {
    for (String name : BUILTIN_PLUGINS) {
      try {
        Class<?> c = Class.forName(name);
        Object o = c.getDeclaredConstructor().newInstance();
        if (o instanceof ModPlugin) {
          registerPlugin((ModPlugin) o, context, "builtin");
        }
      } catch (Throwable t) {
        Log.w(TAG, "builtin plugin " + name + " not loaded", t);
      }
    }
  }

  private static void discover(Context context) {

    File mods = context.getExternalFilesDir("mods");
    if (mods == null || !mods.isDirectory()) {
      Log.i(TAG, "no mods dir at " + mods);
      return;
    }

    File[] files = mods.listFiles();
    if (files == null) {
      Log.w(TAG, "mods dir unreadable (check ownership/permissions): " + mods);
      return;
    }
    if (files.length == 0) {
      Log.i(TAG, "mods dir is empty: " + mods);
      return;
    }
    Arrays.sort(
        files,
        new Comparator<File>() {
          @Override
          public int compare(File a, File b) {
            return a.getName().compareTo(b.getName());
          }
        });

    List<ByteBuffer> dexBuffers = new ArrayList<ByteBuffer>();
    List<File> archiveFiles = new ArrayList<File>();
    for (File f : files) {
      if (!f.isFile()) continue;
      String low = f.getName().toLowerCase(Locale.ROOT);
      if (low.endsWith(".dex")) {
        try {
          byte[] bytes = readFileBytes(f, MAX_DEX_BYTES);
          dexBuffers.add(ByteBuffer.wrap(bytes));
        } catch (Throwable t) {
          Log.e(TAG, "failed reading external dex " + f.getName(), t);
        }
      } else if (low.endsWith(".apk") || low.endsWith(".jar")) {
        archiveFiles.add(f);
      }
    }

    if (dexBuffers.isEmpty() && archiveFiles.isEmpty()) {
      Log.i(TAG, "mods dir has no dex/apk/jar files");
      return;
    }

    ClassLoader cl = buildPluginClassLoader(context, dexBuffers, archiveFiles);
    if (cl == null) {
      Log.e(TAG, "could not construct plugin ClassLoader");
      return;
    }

    File list = new File(mods, "mods.txt");
    if (!list.isFile()) {
      Log.w(TAG, "mods.txt missing — loaded dex/archive(s) but no plugins instantiated");
      return;
    }
    BufferedReader r = null;
    try {
      r = new BufferedReader(new FileReader(list));
      String line;
      while ((line = r.readLine()) != null) {
        line = line.trim();
        if (line.length() == 0 || line.startsWith("#")) continue;
        try {
          Class<?> c = Class.forName(line, true, cl);
          Object o = c.getDeclaredConstructor().newInstance();
          if (o instanceof ModPlugin) {
            registerPlugin((ModPlugin) o, context, "external");
          } else {
            Log.w(TAG, line + " does not implement ModPlugin");
          }
        } catch (Throwable t) {
          Log.e(TAG, "could not load plugin " + line, t);
        }
      }
    } catch (IOException e) {
      Log.e(TAG, "reading mods.txt failed", e);
    } finally {
      if (r != null) {
        try {
          r.close();
        } catch (IOException ignored) {
        }
      }
    }
  }

  private static ClassLoader buildPluginClassLoader(
      Context context, List<ByteBuffer> dexBuffers, List<File> archiveFiles) {
    ClassLoader cl = Loader.class.getClassLoader();

    if (!archiveFiles.isEmpty()) {
      try {
        File stageDir = new File(context.getCodeCacheDir(), "loader-mods");
        if (!stageDir.exists()) {
          stageDir.mkdirs();
        }
        StringBuilder stagedPaths = new StringBuilder();
        for (File src : archiveFiles) {
          File dst = new File(stageDir, src.getName());
          try {
            if (dst.exists() && !dst.delete()) {
              dst.setWritable(true);
              dst.delete();
            }
            copyFile(src, dst);
            if (!dst.setReadOnly()) {
              Log.w(TAG, "setReadOnly returned false for " + dst.getAbsolutePath());
            }
            if (stagedPaths.length() > 0) stagedPaths.append(File.pathSeparator);
            stagedPaths.append(dst.getAbsolutePath());
          } catch (Throwable t) {
            Log.e(TAG, "failed staging archive " + src.getName(), t);
          }
        }
        if (stagedPaths.length() > 0) {
          File dexOpt = new File(context.getCodeCacheDir(), "loader-dexopt");
          if (!dexOpt.exists()) {
            dexOpt.mkdirs();
          }
          cl = new DexClassLoader(stagedPaths.toString(), dexOpt.getAbsolutePath(), null, cl);
          Log.i(TAG, "staged " + archiveFiles.size() + " archive(s) into read-only codeCacheDir");
        }
      } catch (Throwable t) {
        Log.e(TAG, "failed loading staged archive(s)", t);
      }
    }

    if (!dexBuffers.isEmpty()) {
      try {
        if (dexBuffers.size() == 1) {
          cl = new InMemoryDexClassLoader(dexBuffers.get(0), cl);
        } else if (Build.VERSION.SDK_INT >= 27) {
          ByteBuffer[] arr = dexBuffers.toArray(new ByteBuffer[dexBuffers.size()]);
          cl = new InMemoryDexClassLoader(arr, cl);
        } else {
          for (int i = 0; i < dexBuffers.size(); i++) {
            cl = new InMemoryDexClassLoader(dexBuffers.get(i), cl);
          }
        }
        Log.i(TAG, "loaded " + dexBuffers.size() + " external dex buffer(s) in-memory");
      } catch (Throwable t) {
        Log.e(TAG, "InMemoryDexClassLoader failed", t);
        if (archiveFiles.isEmpty()) return null;
      }
    }

    return cl;
  }

  private static byte[] readFileBytes(File file, int maxBytes) throws IOException {
    long len = file.length();
    if (len <= 0 || len > maxBytes) {
      throw new IOException("unexpected dex size: " + len);
    }
    FileInputStream in = new FileInputStream(file);
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream((int) len);
      byte[] buf = new byte[16384];
      int n;
      int total = 0;
      while ((n = in.read(buf)) >= 0) {
        total += n;
        if (total > maxBytes) {
          throw new IOException("dex exceeded max size " + maxBytes);
        }
        out.write(buf, 0, n);
      }
      return out.toByteArray();
    } finally {
      try {
        in.close();
      } catch (IOException ignored) {
      }
    }
  }

  private static void copyFile(File src, File dst) throws IOException {
    FileInputStream in = new FileInputStream(src);
    try {
      FileOutputStream out = new FileOutputStream(dst);
      try {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) >= 0) {
          out.write(buf, 0, n);
        }
      } finally {
        try {
          out.close();
        } catch (IOException ignored) {
        }
      }
    } finally {
      try {
        in.close();
      } catch (IOException ignored) {
      }
    }
  }

  public static byte[] dispatchInvoke(HostInvoker host, byte[] frame) {
    if (host != null && activeHost != host) {
      registerHost(host);
    }
    CinderNative.flushPendingIfNeeded();
    ModPlugin[] snapshot = pluginSnapshot;
    if (frame == null || snapshot.length == 0) {
      return host.invokeOrig(frame);
    }

    Frame requestFrame = Frame.parse(frame);
    if (requestFrame == null) {
      return host.invokeOrig(frame);
    }

    byte[] currentReq = frame;
    boolean shortCircuited = false;
    byte[] responseBytes = null;

    for (int i = 0; i < snapshot.length; i++) {
      ModPlugin p = snapshot[i];
      if (!isPluginEnabled(p)) continue;
      try {
        byte[] replacement = p.onHostCall(requestFrame);
        if (replacement == null) {
          continue;
        }
        if (replacement == Frame.DROP) {
          shortCircuited = true;
          responseBytes = null;
          break;
        }
        Frame parsed = Frame.parse(replacement);
        if (parsed == null) {
          Log.w(TAG, "plugin " + p.id() + " returned malformed frame from onHostCall; ignoring");
          continue;
        }
        if (parsed.isResponse()) {
          shortCircuited = true;
          responseBytes = replacement;
          break;
        }
        currentReq = replacement;
        requestFrame = parsed;
      } catch (Throwable t) {
        Log.e(TAG, "plugin host-call hook threw (" + p.id() + ")", t);
      }
    }

    if (!shortCircuited) {
      responseBytes = host.invokeOrig(currentReq);
    }

    if (responseBytes == null) {
      return null;
    }
    Frame responseFrame = Frame.parse(responseBytes);
    if (responseFrame == null) {
      return responseBytes;
    }

    byte[] currentResp = responseBytes;
    for (int i = 0; i < snapshot.length; i++) {
      ModPlugin p = snapshot[i];
      if (!isPluginEnabled(p)) continue;
      try {
        byte[] replacement = p.onHostResponse(requestFrame, responseFrame);
        if (replacement == null) {
          continue;
        }
        if (replacement == Frame.DROP) {
          return null;
        }
        Frame parsed = Frame.parse(replacement);
        if (parsed == null) {
          Log.w(
              TAG, "plugin " + p.id() + " returned malformed frame from onHostResponse; ignoring");
          continue;
        }
        currentResp = replacement;
        responseFrame = parsed;
      } catch (Throwable t) {
        Log.e(TAG, "plugin response hook threw (" + p.id() + ")", t);
      }
    }
    return currentResp;
  }

  public static byte[] onOps(HostInvoker host, byte[] ops, int length) {
    if (host != null && activeHost != host) {
      registerHost(host);
    }
    return onOps(ops, length);
  }

  public static byte[] onOps(byte[] ops, int length) {
    if (Boolean.TRUE.equals(IN_SYNTHETIC_OPS.get())) {
      return ops;
    }
    CinderNative.flushPendingIfNeeded();
    ModPlugin[] snapshot = pluginSnapshot;
    if (ops == null || snapshot.length == 0) return ops;
    byte[] current = ops;
    int currentLen = length;
    try {
      for (int i = 0; i < snapshot.length; i++) {
        ModPlugin p = snapshot[i];
        if (!isPluginEnabled(p)) continue;
        try {
          byte[] replacement = p.onUiOps(current, currentLen);
          if (replacement != null && replacement != current) {
            current = replacement;
            currentLen = replacement.length;
          }
        } catch (Throwable t) {
          Log.e(TAG, "plugin ops hook threw (" + p.id() + ")", t);
        }
      }
    } catch (Throwable t) {
      Log.e(TAG, "onOps failed", t);
    }
    return current;
  }

  public static void onLog(int level, String message) {
    ModPlugin[] snapshot = pluginSnapshot;
    if (snapshot.length == 0) return;
    try {
      for (int i = 0; i < snapshot.length; i++) {
        ModPlugin p = snapshot[i];
        if (!isPluginEnabled(p)) continue;
        try {
          p.onLog(level, message);
        } catch (Throwable t) {
          Log.e(TAG, "plugin log hook threw (" + p.id() + ")", t);
        }
      }
    } catch (Throwable t) {
      Log.e(TAG, "onLog failed", t);
    }
  }

  private static void bootstrapNativeRuntime(Context appCtx) {
    if (appCtx != null) {
      try {
        File dir = new File(appCtx.getFilesDir(), "cinder");
        if (!dir.exists()) {
          dir.mkdirs();
        }
        File cfg = new File(dir, "cinder.toml");
        if (!cfg.exists()) {
          FileOutputStream fos = new FileOutputStream(cfg);
          try {
            fos.write("log = info\nplugins = true\nverify_only = true\n".getBytes("UTF-8"));
          } finally {
            try {
              fos.close();
            } catch (IOException ignored) {
            }
          }
        }
      } catch (Throwable t) {
        Log.w(TAG, "bootstrap cinder.toml failed", t);
      }
    }
    try {
      System.loadLibrary("cinder_mobile");
    } catch (Throwable ignored) {
    }
  }

  public static boolean dispatchNative(long handle, byte[] frame) {
    byte[] filtered = onDispatch(frame);
    if (filtered == null) {
      return true;
    }
    return com.discord.nativeapp.bridge.WasmRuntime.nativeDispatch(handle, filtered);
  }

  public static byte[] onDispatch(byte[] frame) {
    ModPlugin[] snapshot = pluginSnapshot;
    if (frame == null || snapshot.length == 0) return frame;
    try {
      if (isBatchEnvelope(frame)) {
        return dispatchBatch(frame, snapshot);
      }
      byte[] res = dispatchSingleItem(frame, snapshot);
      return res == Frame.DROP ? null : res;
    } catch (Throwable t) {
      Log.e(TAG, "onDispatch failed", t);
      return frame;
    }
  }

  private static boolean isBatchEnvelope(byte[] frame) {
    if (frame.length < Frame.HEADER_SIZE) return false;
    if ((frame[0] & 0xff) != Frame.KIND_BATCH) return false;
    ByteBuffer q = ByteBuffer.wrap(frame, 0, Frame.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    return q.getInt(12) == 0 && frame.length > Frame.HEADER_SIZE;
  }

  private static byte[] dispatchBatch(byte[] batch, ModPlugin[] snapshot) {
    ByteBuffer buf = ByteBuffer.wrap(batch).order(ByteOrder.LITTLE_ENDIAN);
    buf.position(Frame.HEADER_SIZE);

    List<byte[]> items = new ArrayList<byte[]>();
    while (buf.hasRemaining()) {
      if (buf.remaining() < 4) {

        return batch;
      }
      int len = buf.getInt();
      if (len < 0 || len > buf.remaining()) {
        return batch;
      }
      byte[] sub = new byte[len];
      buf.get(sub);
      items.add(sub);
    }

    boolean modified = false;
    List<byte[]> kept = new ArrayList<byte[]>(items.size());
    int bodySize = 0;
    for (int i = 0; i < items.size(); i++) {
      byte[] orig = items.get(i);
      byte[] res = dispatchSingleItem(orig, snapshot);
      if (res == Frame.DROP) {
        modified = true;
        continue;
      }
      if (res != orig) {
        modified = true;
      }
      kept.add(res);
      bodySize += 4 + res.length;
    }

    if (!modified) {
      return batch;
    }
    if (kept.isEmpty()) {
      return null;
    }

    byte[] out = new byte[Frame.HEADER_SIZE + bodySize];
    System.arraycopy(batch, 0, out, 0, Frame.HEADER_SIZE);
    ByteBuffer outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
    outBuf.position(Frame.HEADER_SIZE);
    for (int i = 0; i < kept.size(); i++) {
      byte[] item = kept.get(i);
      outBuf.putInt(item.length);
      outBuf.put(item);
    }
    return out;
  }

  private static byte[] dispatchSingleItem(byte[] item, ModPlugin[] snapshot) {
    if (UiOps.ViewEvent.isViewEventFrame(item)) {
      UiOps.ViewEvent event = UiOps.ViewEvent.parse(item);
      if (event == null) {
        return item;
      }
      if (event.isBackEvent()) {
        for (int i = 0; i < snapshot.length; i++) {
          ModPlugin p = snapshot[i];
          if (!isPluginEnabled(p)) continue;
          try {
            if (p.onBackPress()) {
              return Frame.DROP;
            }
          } catch (Throwable t) {
            Log.e(TAG, "plugin onBackPress hook threw (" + p.id() + ")", t);
          }
        }
      }
      byte[] current = item;
      for (int i = 0; i < snapshot.length; i++) {
        ModPlugin p = snapshot[i];
        if (!isPluginEnabled(p)) continue;
        try {
          byte[] replacement = p.onViewEvent(event);
          if (replacement == null) {
            continue;
          }
          if (replacement == Frame.DROP) {
            return Frame.DROP;
          }
          UiOps.ViewEvent parsed = UiOps.ViewEvent.parse(replacement);
          if (parsed == null) {
            Log.w(
                TAG,
                "plugin " + p.id() + " returned malformed ViewEvent from onViewEvent; ignoring");
            continue;
          }
          current = replacement;
          event = parsed;
        } catch (Throwable t) {
          Log.e(TAG, "plugin view-event hook threw (" + p.id() + ")", t);
        }
      }
      return current;
    }

    Frame parsedFrame = Frame.parse(item);
    if (parsedFrame == null) {
      return item;
    }
    byte[] current = item;
    for (int i = 0; i < snapshot.length; i++) {
      ModPlugin p = snapshot[i];
      if (!isPluginEnabled(p)) continue;
      try {
        byte[] replacement = p.onDispatch(parsedFrame);
        if (replacement == null) {
          continue;
        }
        if (replacement == Frame.DROP) {
          return Frame.DROP;
        }
        Frame reparsed = Frame.parse(replacement);
        if (reparsed == null) {
          Log.w(TAG, "plugin " + p.id() + " returned malformed Frame from onDispatch; ignoring");
          continue;
        }
        current = replacement;
        parsedFrame = reparsed;
      } catch (Throwable t) {
        Log.e(TAG, "plugin dispatch hook threw (" + p.id() + ")", t);
      }
    }
    return current;
  }

  public static synchronized void resetForTests() {
    initialized = false;
    appContext = null;
    pluginList.clear();
    pluginSnapshot = EMPTY_PLUGINS;
    pluginEnabledCache.clear();
    bootEnabledSnapshot.clear();
    activeHost = null;
    guestHandler = null;
    guestHandlerSearched = false;
    syntheticExecutorForTests = null;
    restartHookForTests = null;
    IN_SYNTHETIC_OPS.remove();
    UiOps.resetPropNamesForTests();
    CinderNative.resetForTests();
    CinderSettings.resetForTests();
    ThemeEngine.resetForTests();
  }

  public static synchronized void setPluginsForTests(ModPlugin... plugins) {
    pluginList.clear();
    pluginEnabledCache.clear();
    bootEnabledSnapshot.clear();
    if (plugins != null) {
      for (ModPlugin p : plugins) {
        if (p != null) {
          initPluginLifecycleState(p);
          pluginList.add(p);
        }
      }
    }
    pluginSnapshot = pluginList.toArray(new ModPlugin[pluginList.size()]);
  }

  static void setContextForTests(Context context) {
    appContext = context;
  }

  public static void setSyntheticExecutorForTests(Executor executor) {
    syntheticExecutorForTests = executor;
  }

  public static void setRestartHookForTests(Runnable hook) {
    restartHookForTests = hook;
  }
}
