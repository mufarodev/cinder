package com.discord.loader;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.discord.loader.mana.ManaIcons;
import com.discord.loader.mana.ManaMotion;
import com.discord.loader.mana.ManaRouter;
import com.discord.loader.mana.ManaRuntime;
import com.discord.loader.mana.ManaSettingsSearch;
import com.discord.loader.mana.ThemeEngine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class CinderSettings implements ModPlugin {
  public static final String PREF_FILE = "cinder.settings";

  public static final String THEME_SCOPE_OVERVIEW = "cinder-settings-overview";

  public static final String THEME_SCOPE_PAGE = "cinder-settings-page";

  public static final String KEY_VERBOSE_BRIDGE_LOGS = "cinder.verbose_bridge_logs";

  public static final String KEY_DUMP_SETTINGS_OPS = "cinder.dump_settings_ops";

  public interface ToggleListener {
    void onChanged(boolean value);
  }

  public static final class ToggleEntry {
    public final String key;
    public final String title;
    public final String subtitle;
    public final boolean defaultValue;
    final ToggleListener listener;

    ToggleEntry(
        String key, String title, String subtitle, boolean defaultValue, ToggleListener listener) {
      this.key = key;
      this.title = title;
      this.subtitle = subtitle;
      this.defaultValue = defaultValue;
      this.listener = listener;
    }
  }

  public static final class ActionEntry {
    public final String id;
    public final String title;
    public final String subtitle;
    public final String iconUri;
    final Runnable onPress;

    ActionEntry(String id, String title, String subtitle, String iconUri, Runnable onPress) {
      this.id = id;
      this.title = title;
      this.subtitle = subtitle;
      this.iconUri = (iconUri != null && iconUri.length() > 0) ? iconUri : ManaIcons.SPARKLES;
      this.onPress = onPress;
    }
  }

  private static final Object REGISTRY_LOCK = new Object();
  private static final LinkedHashMap<String, ToggleEntry> TOGGLES =
      new LinkedHashMap<String, ToggleEntry>();
  private static final LinkedHashMap<String, ActionEntry> ACTIONS =
      new LinkedHashMap<String, ActionEntry>();
  private static final ConcurrentHashMap<String, Boolean> TOGGLE_VALUES =
      new ConcurrentHashMap<String, Boolean>();
  private static volatile Context cachedContext = null;

  static volatile SharedPreferences prefsOverrideForTests = null;

  public static void registerToggle(
      String key, String title, String subtitle, boolean defaultValue, ToggleListener listener) {
    if (key == null || key.length() == 0) {
      throw new IllegalArgumentException("toggle key must be non-empty");
    }
    String safeTitle = (title != null && title.length() > 0) ? title : key;
    synchronized (REGISTRY_LOCK) {
      TOGGLES.put(key, new ToggleEntry(key, safeTitle, subtitle, defaultValue, listener));
      Boolean persisted = readPersistedBoolean(key);
      if (persisted != null) {
        TOGGLE_VALUES.put(key, persisted);
      } else if (!TOGGLE_VALUES.containsKey(key)) {
        TOGGLE_VALUES.put(key, Boolean.valueOf(defaultValue));
      }
    }
  }

  public static void registerAction(
      String id, String title, String subtitle, String iconUri, Runnable onPress) {
    if (id == null || id.length() == 0) {
      throw new IllegalArgumentException("action id must be non-empty");
    }
    String safeTitle = (title != null && title.length() > 0) ? title : id;
    synchronized (REGISTRY_LOCK) {
      ACTIONS.put(id, new ActionEntry(id, safeTitle, subtitle, iconUri, onPress));
    }
  }

  public static boolean getToggleValue(String key, boolean defaultValue) {
    if (key == null) return defaultValue;
    Boolean v = TOGGLE_VALUES.get(key);
    if (v != null) return v.booleanValue();
    Boolean persisted = readPersistedBoolean(key);
    if (persisted != null) {
      TOGGLE_VALUES.put(key, persisted);
      return persisted.booleanValue();
    }
    return defaultValue;
  }

  public static void setToggleValue(String key, boolean value) {
    if (key == null || key.length() == 0) return;
    TOGGLE_VALUES.put(key, Boolean.valueOf(value));
    writePersistedBoolean(key, value);
    ToggleListener listener = null;
    synchronized (REGISTRY_LOCK) {
      ToggleEntry entry = TOGGLES.get(key);
      if (entry != null) {
        listener = entry.listener;
      }
    }
    if (listener != null) {
      try {
        listener.onChanged(value);
      } catch (Throwable t) {
        Log.e(Loader.TAG, "CinderSettings toggle listener threw for " + key, t);
      }
    }
  }

  public static boolean isVerboseBridgeLogsEnabled() {
    return getToggleValue(KEY_VERBOSE_BRIDGE_LOGS, false);
  }

  public static boolean isDumpSettingsOpsEnabled() {
    return getToggleValue(KEY_DUMP_SETTINGS_OPS, false);
  }

  public static List<ToggleEntry> snapshotToggles() {
    synchronized (REGISTRY_LOCK) {
      return new ArrayList<ToggleEntry>(TOGGLES.values());
    }
  }

  public static List<ActionEntry> snapshotActions() {
    synchronized (REGISTRY_LOCK) {
      return new ArrayList<ActionEntry>(ACTIONS.values());
    }
  }

  private static SharedPreferences getPrefs() {
    SharedPreferences override = prefsOverrideForTests;
    if (override != null) return override;
    Context ctx = cachedContext;
    if (ctx == null) ctx = Loader.getContext();
    if (ctx == null) return null;
    try {
      return ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static Boolean readPersistedBoolean(String key) {
    SharedPreferences sp = getPrefs();
    if (sp == null) return null;
    try {
      if (sp.contains(key)) {
        return Boolean.valueOf(sp.getBoolean(key, false));
      }
    } catch (Throwable ignored) {
    }
    return null;
  }

  private static void writePersistedBoolean(String key, boolean value) {
    SharedPreferences sp = getPrefs();
    if (sp == null) return;
    try {
      sp.edit().putBoolean(key, value).apply();
    } catch (Throwable t) {
      Log.w(Loader.TAG, "CinderSettings failed persisting " + key, t);
    }
  }

  static void resetForTests() {
    synchronized (REGISTRY_LOCK) {
      TOGGLES.clear();
      ACTIONS.clear();
      TOGGLE_VALUES.clear();
    }
    cachedContext = null;
    prefsOverrideForTests = null;
    ManaRuntime.resetForTests();
    ThemeEngine.unbindScope(THEME_SCOPE_OVERVIEW);
    ThemeEngine.unbindScope(THEME_SCOPE_PAGE);
    ManaMotion.resetForTests();
    ManaSettingsSearch.clearEntriesForTests();
    CinderSettingsPlugin.resetReactiveStateForTests();
  }

  public static void notifyPluginStateChanged(String pluginId, boolean enabled) {
    CinderSettingsPlugin.onPluginStateChanged(pluginId, enabled);
  }

  private final CinderSettingsPlugin delegate = new CinderSettingsPlugin();

  public ManaRouter getRouter() {
    return delegate.getRouter();
  }

  public ManaSettingsSearch getSettingsSearch() {
    return delegate.getSettingsSearch();
  }

  @Override
  public String id() {
    return "cinder-settings";
  }

  @Override
  public String version() {
    return "3.1.0";
  }

  @Override
  public String description() {
    return "Built-in Cinder Settings, plugin registry & Settings Search integration";
  }

  @Override
  public String icon() {
    return ManaIcons.FIRE;
  }

  @Override
  public boolean isRequired() {
    return true;
  }

  @Override
  public void onLoad(Context context) {
    cachedContext = context;
    synchronized (REGISTRY_LOCK) {
      for (ToggleEntry entry : TOGGLES.values()) {
        Boolean persisted = readPersistedBoolean(entry.key);
        if (persisted != null) {
          TOGGLE_VALUES.put(entry.key, persisted);
        }
      }
    }
    Log.i(
        Loader.TAG,
        "CinderSettings v" + version() + " loaded (toggles=" + snapshotToggles().size() + ")");
  }

  @Override
  public byte[] onUiOps(byte[] opsBuf, int length) {
    return delegate.onUiOps(opsBuf, length);
  }

  @Override
  public byte[] onViewEvent(UiOps.ViewEvent event) {
    return delegate.onViewEvent(event);
  }

  @Override
  public boolean onBackPress() {
    return delegate.onBackPress();
  }

  int activeCinderRowIdForTests() {
    return delegate.getOverviewRowViewId();
  }

  ManaUi.Theme activeThemeForTests() {
    return delegate.getCurrentTheme();
  }

  boolean isPageOpenForTests() {
    return delegate.isPageOpenForTests();
  }

  int pageRootIdForTests() {
    return delegate.pageRootIdForTests();
  }

  int backButtonIdForTests() {
    return delegate.backButtonIdForTests();
  }

  int settingsNavIdForTests() {
    return delegate.settingsNavIdForTests();
  }

  int toggleRowIdForTests(String key) {
    return delegate.toggleRowIdForTests(key);
  }

  int toggleTrackIdForTests(String key) {
    return delegate.toggleTrackIdForTests(key);
  }

  int toggleThumbIdForTests(String key) {
    return delegate.toggleThumbIdForTests(key);
  }

  int toggleMotionSlotForTests(String key) {
    return delegate.toggleMotionSlotForTests(key);
  }

  int actionRowIdForTests(String id) {
    return delegate.actionRowIdForTests(id);
  }

  int pluginRowIdForTests(String id) {
    return delegate.pluginRowIdForTests(id);
  }

  int pluginSwitchTrackIdForTests(String id) {
    return delegate.pluginSwitchTrackIdForTests(id);
  }

  int restartButtonIdForTests() {
    return delegate.restartButtonIdForTests();
  }

  int showcaseRowIdForTests() {
    return delegate.showcaseRowIdForTests();
  }
}
