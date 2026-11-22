package com.discord.loader;

import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class BridgeRegistry {
  private static final String GUEST_HOST_CLASS = "com.discord.nativeapp.bridge.GuestHost";
  private static final String ENQUEUE_PREFIX = "guest.enqueue:";

  private static final class Snapshot {
    final String[] idToName;
    final Map<String, Integer> nameToId;
    final int count;

    Snapshot(String[] idToName, Map<String, Integer> nameToId, int count) {
      this.idToName = idToName;
      this.nameToId = nameToId;
      this.count = count;
    }
  }

  private static final Snapshot EMPTY =
      new Snapshot(new String[0], Collections.<String, Integer>emptyMap(), 0);

  private static volatile Snapshot current = EMPTY;

  private BridgeRegistry() {}

  public static void register(String[] symbols) {
    if (symbols == null || symbols.length == 0) return;
    String[] copy = Arrays.copyOf(symbols, symbols.length);
    Map<String, Integer> map = new HashMap<String, Integer>(copy.length * 2);
    int count = 0;
    for (int i = 0; i < copy.length; i++) {
      String s = copy[i];
      if (s != null && !s.isEmpty()) {
        count++;
        if (!map.containsKey(s)) {
          map.put(s, Integer.valueOf(i));
        }
      }
    }
    current = new Snapshot(copy, Collections.unmodifiableMap(map), count);
  }

  public static void init() {
    if (isReady()) return;
    try {

      Class<?> guestHost = Class.forName(GUEST_HOST_CLASS);
      if (isReady()) {
        Log.i(Loader.TAG, "BridgeRegistry ready via <clinit>: " + size() + " symbols");
        return;
      }

      if (discoverFromGuestHost(guestHost)) {
        Log.i(Loader.TAG, "BridgeRegistry ready via reflection fallback: " + size() + " symbols");
      } else {
        Log.w(Loader.TAG, "BridgeRegistry: no symbol table found on GuestHost");
      }
    } catch (Throwable t) {
      Log.w(Loader.TAG, "BridgeRegistry init failed (fail-soft): " + t);
    }
  }

  static boolean discoverFromGuestHost(Class<?> guestHost) {
    Field[] fields = guestHost.getDeclaredFields();
    for (int i = 0; i < fields.length; i++) {
      Field f = fields[i];
      if (!Modifier.isStatic(f.getModifiers())) continue;
      if (f.getType() != String[].class) continue;
      try {
        f.setAccessible(true);
        Object val = f.get(null);
        if (!(val instanceof String[])) continue;
        String[] arr = (String[]) val;
        if (arr.length < 32) continue;
        int prefixed = 0;
        for (int k = 0; k < arr.length; k++) {
          String s = arr[k];
          if (s != null && s.startsWith(ENQUEUE_PREFIX)) {
            prefixed++;
            if (prefixed >= 8) break;
          }
        }
        if (prefixed < 8) continue;
        String[] stripped = new String[arr.length];
        for (int k = 0; k < arr.length; k++) {
          String s = arr[k];
          if (s != null && s.startsWith(ENQUEUE_PREFIX)) {
            stripped[k] = s.substring(ENQUEUE_PREFIX.length());
          } else {
            stripped[k] = s;
          }
        }
        register(stripped);
        return isReady();
      } catch (Throwable ignored) {
      }
    }
    return false;
  }

  public static boolean isReady() {
    return current.count > 0;
  }

  public static int size() {
    return current.count;
  }

  public static String nameOf(int id) {
    String[] table = current.idToName;
    if (id < 0 || id >= table.length) return null;
    String s = table[id];
    return (s == null || s.isEmpty()) ? null : s;
  }

  public static String methodName(int id) {
    String full = nameOf(id);
    if (full == null) return null;
    int dot = full.indexOf('.');
    return (dot >= 0 && dot + 1 < full.length()) ? full.substring(dot + 1) : full;
  }

  public static int idOf(String name) {
    if (name == null || name.isEmpty()) return -1;
    Integer id = current.nameToId.get(name);
    return id != null ? id.intValue() : -1;
  }

  public static int moduleId(String moduleName) {
    return idOf(moduleName);
  }

  public static int methodId(String moduleName, String methodName) {
    if (moduleName == null || methodName == null) return -1;
    return idOf(moduleName + "." + methodName);
  }

  public static boolean matchesModule(Frame frame, String moduleName) {
    if (frame == null) return false;
    int id = moduleId(moduleName);
    return id >= 0 && frame.moduleId() == id;
  }

  public static boolean matches(Frame frame, String moduleName, String methodName) {
    if (frame == null) return false;
    int modId = moduleId(moduleName);
    if (modId < 0 || frame.moduleId() != modId) return false;
    int methId = methodId(moduleName, methodName);
    return methId >= 0 && frame.methodId() == methId;
  }

  public static boolean matchesEvent(Frame frame, String topicName) {
    if (frame == null || !frame.isEvent()) return false;
    int id = idOf(topicName);
    return id >= 0 && frame.methodId() == id;
  }

  public static String describe(Frame frame) {
    if (frame == null) return "null";
    String meth = nameOf(frame.methodId());
    if (meth != null && meth.indexOf('.') >= 0) {
      return meth;
    }
    String mod = nameOf(frame.moduleId());
    if (mod != null && meth != null) {
      return mod + "." + meth;
    }
    if (mod != null) {
      return mod + "." + frame.methodId();
    }
    return "m" + frame.moduleId() + "." + frame.methodId();
  }

  static void reset() {
    current = EMPTY;
  }
}
