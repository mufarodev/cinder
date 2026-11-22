package com.discord.loader;

import android.content.Context;
import com.discord.loader.mana.ManaContext;
import com.discord.loader.mana.ManaPage;

public interface ModPlugin {

  default String id() {
    return getClass().getSimpleName();
  }

  default String name() {
    String id = id();
    if (id == null || id.isEmpty()) return "";
    StringBuilder out = new StringBuilder(id.length());
    boolean capitalizeNext = true;
    for (int i = 0; i < id.length(); i++) {
      char c = id.charAt(i);
      if (c == '-' || c == '_' || c == '.' || c == ' ') {
        if (out.length() > 0) out.append(' ');
        capitalizeNext = true;
      } else {
        out.append(capitalizeNext ? Character.toUpperCase(c) : c);
        capitalizeNext = false;
      }
    }
    return out.toString();
  }

  default String version() {
    return "0.1.0";
  }

  default String description() {
    return "";
  }

  default String icon() {
    return null;
  }

  default boolean isRequired() {
    return false;
  }

  default boolean defaultEnabled() {
    return true;
  }

  default boolean requiresRestart() {
    return false;
  }

  default void onEnabledChanged(boolean enabled) {}

  default ManaPage createSettingsPage(ManaContext ctx) {
    return null;
  }

  default void onLoad(Context context) {}

  default byte[] onHostCall(Frame frame) {
    return null;
  }

  default byte[] onHostResponse(Frame request, Frame response) {
    return null;
  }

  default byte[] onUiOps(byte[] ops, int length) {
    return null;
  }

  default byte[] onDispatch(Frame frame) {
    return null;
  }

  default byte[] onViewEvent(UiOps.ViewEvent event) {
    return null;
  }

  default boolean onBackPress() {
    return false;
  }

  default void onLog(int level, String message) {}
}
