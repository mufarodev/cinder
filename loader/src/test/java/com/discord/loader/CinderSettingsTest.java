package com.discord.loader;

import android.content.SharedPreferences;
import com.discord.loader.mana.ManaMotion;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class CinderSettingsTest {
  private static int passed = 0;

  private static void check(boolean cond, String msg) {
    if (!cond) throw new AssertionError("FAIL: " + msg);
  }

  private static void checkEq(Object actual, Object expected, String msg) {
    if (actual == null ? expected != null : !actual.equals(expected)) {
      throw new AssertionError(
          "FAIL: " + msg + " (expected=" + expected + ", actual=" + actual + ")");
    }
  }

  private static void runTest(String name, Runnable r) {
    Loader.resetForTests();
    BridgeRegistry.reset();
    r.run();
    passed++;
    System.out.println("PASS " + name);
  }

  private static void registerSettingsProps() {
    String[] names = {
      "",
      "absolute",
      "accessibilityChecked",
      "accessibilityHidden",
      "accessibilityId",
      "accessibilityLabel",
      "accessibilitySelected",
      "accessible",
      "activeBackground",
      "align",
      "animate",
      "appeared",
      "autoCapitalize",
      "autoCorrect",
      "background",
      "blur",
      "borderColor",
      "borderWidth",
      "changeText",
      "clip",
      "color",
      "direction",
      "dismiss",
      "dismissLabel",
      "dismissible",
      "exitEnd",
      "expanded",
      "flex",
      "focus",
      "fontSize",
      "fontWeight",
      "fullScreenBack",
      "fullscreen",
      "gap",
      "gravity",
      "handleColor",
      "height",
      "hitSlop",
      "includeFontPadding",
      "index",
      "justify",
      "keyboardDismiss",
      "keyboardShouldPersistTaps",
      "layout",
      "lineHeight",
      "lineHeightExact",
      "mana",
      "margin",
      "maxLines",
      "minHeight",
      "minWidth",
      "navigate",
      "on",
      "opacity",
      "padding",
      "paddingBox",
      "panWorklet",
      "panWorkletAxis",
      "panWorkletConfig",
      "placeholder",
      "placeholderColor",
      "presentation",
      "press",
      "pressFeedback",
      "radius",
      "radiusBottomLeft",
      "radiusBottomRight",
      "radiusTopLeft",
      "radiusTopRight",
      "returnKey",
      "role",
      "scale",
      "scaleFactor",
      "shadow",
      "shrink",
      "stackHeader",
      "submit",
      "text",
      "textAlign",
      "tint",
      "translateX",
      "uri",
      "value",
      "width",
      "worklet",
    };
    UiOps.registerPropNames(names);
  }

  private static List<UiOps.Prop> p(Object... kv) {
    List<UiOps.Prop> out = new ArrayList<UiOps.Prop>(kv.length / 2);
    for (int i = 0; i + 1 < kv.length; i += 2) {
      out.add(UiOps.Prop.named((String) kv[i], (UiOps.Value) kv[i + 1]));
    }
    return out;
  }

  private static UiOps.Value strArr(String... items) {
    List<UiOps.Value> vals = new ArrayList<UiOps.Value>(items.length);
    for (String s : items) vals.add(UiOps.Value.ofString(s));
    return UiOps.Value.ofArray(vals);
  }

  private static UiOps.Value colorVal(long argb) {
    long u = argb & 0xFFFFFFFFL;
    return u > Integer.MAX_VALUE ? UiOps.Value.ofLong(u) : UiOps.Value.ofInt((int) u);
  }

  private static byte[] buildSettingsOverviewBatch(
      int phoneId,
      int scrollColId,
      long screenBg,
      long headerColor,
      long rowBg,
      long activeBg,
      long primaryTextColor,
      long mutedColor,
      long dividerColor,
      double headerHeight) {
    int modalWrapperId = phoneId - 1;
    int inner1 = phoneId + 1;
    int inner2 = phoneId + 2;
    int spacerId = phoneId + 3;
    int topDivId = phoneId + 4;
    int scrollId = phoneId + 5;
    int headerBarId = scrollColId + 20;
    int catWrapId = scrollColId + 1;
    int catHeaderId = scrollColId + 2;
    int cardId = scrollColId + 3;
    int acctRowId = scrollColId + 4;
    int acctInnerId = scrollColId + 5;
    int acctIconId = scrollColId + 6;
    int acctLabelId = scrollColId + 7;
    int chevBoxId = scrollColId + 8;
    int chevImgId = scrollColId + 9;
    int divWrapId = scrollColId + 10;
    int divLineId = scrollColId + 11;

    return UiOps.create()
        .updateView(5, p("index", UiOps.Value.ofInt(2)))
        .createView(
            modalWrapperId,
            "view",
            p(
                "presentation", UiOps.Value.ofString("modal"),
                "direction", UiOps.Value.ofString("column"),
                "width", UiOps.Value.ofString("fill"),
                "height", UiOps.Value.ofString("fill")))
        .createView(
            phoneId,
            "view",
            p(
                "direction", UiOps.Value.ofString("column"),
                "padding", UiOps.Value.ofFloat4(0f, 0f, 0f, 0f),
                "width", UiOps.Value.ofString("fill"),
                "height", UiOps.Value.ofString("fill"),
                "background", colorVal(screenBg),
                "clip", UiOps.Value.ofBool(true),
                "align", UiOps.Value.ofString("stretch"),
                "accessibilityId", UiOps.Value.ofString("settings-phone")))
        .createView(
            inner1,
            "view",
            p(
                "direction", UiOps.Value.ofString("column"),
                "width", UiOps.Value.ofString("fill"),
                "height", UiOps.Value.ofString("fill")))
        .createView(
            inner2,
            "view",
            p(
                "direction", UiOps.Value.ofString("column"),
                "width", UiOps.Value.ofString("fill"),
                "height", UiOps.Value.ofString("fill"),
                "background", colorVal(screenBg)))
        .createView(
            spacerId,
            "view",
            p(
                "width", UiOps.Value.ofString("fill"),
                "height", UiOps.Value.ofDouble(headerHeight),
                "align", UiOps.Value.ofString("stretch")))
        .createView(
            topDivId,
            "view",
            p(
                "width", UiOps.Value.ofString("fill"),
                "height", UiOps.Value.ofInt(1),
                "align", UiOps.Value.ofString("stretch")))
        .createView(
            scrollId,
            "scroll",
            p(
                "margin", UiOps.Value.ofFloat4(0f, 0f, 0f, 0f),
                "width", UiOps.Value.ofString("fill"),
                "flex", UiOps.Value.ofInt(1)))
        .createView(
            scrollColId,
            "view",
            p(
                "direction", UiOps.Value.ofString("column"),
                "padding", UiOps.Value.ofFloat4(16f, 24f, 16f, 30.857f),
                "width", UiOps.Value.ofString("fill"),
                "gap", UiOps.Value.ofInt(24),
                "align", UiOps.Value.ofString("start")))
        .createView(
            catWrapId,
            "view",
            p(
                "direction", UiOps.Value.ofString("column"),
                "width", UiOps.Value.ofString("fill"),
                "align", UiOps.Value.ofString("stretch")))
        .createView(
            catHeaderId,
            "text",
            p(
                "text", UiOps.Value.ofString("Account Settings"),
                "color", colorVal(headerColor),
                "fontSize", UiOps.Value.ofInt(16),
                "fontWeight", UiOps.Value.ofInt(500),
                "role", UiOps.Value.ofString("header")))
        .createView(
            cardId,
            "view",
            p(
                "direction", UiOps.Value.ofString("column"),
                "width", UiOps.Value.ofString("fill"),
                "radius", UiOps.Value.ofInt(12),
                "clip", UiOps.Value.ofBool(true)))
        .createView(
            acctRowId,
            "view",
            p(
                "direction", UiOps.Value.ofString("column"),
                "width", UiOps.Value.ofString("fill"),
                "background", colorVal(rowBg),
                "activeBackground", colorVal(activeBg),
                "role", UiOps.Value.ofString("button"),
                "accessibilityId", UiOps.Value.ofString("settings.row.account"),
                "on", strArr("press")))
        .createView(
            acctInnerId,
            "view",
            p(
                "direction", UiOps.Value.ofString("row"),
                "width", UiOps.Value.ofString("fill"),
                "minHeight", UiOps.Value.ofInt(56),
                "align", UiOps.Value.ofString("center")))
        .createView(
            acctIconId,
            "image",
            p(
                "uri", UiOps.Value.ofString("res://ic_mana_user_circle"),
                "tint", colorVal(primaryTextColor)))
        .createView(
            acctLabelId,
            "text",
            p(
                "text", UiOps.Value.ofString("Account"),
                "color", colorVal(primaryTextColor),
                "fontSize", UiOps.Value.ofInt(16),
                "includeFontPadding", UiOps.Value.ofBool(true)))
        .createView(
            chevBoxId,
            "view",
            p(
                "direction", UiOps.Value.ofString("row"),
                "width", UiOps.Value.ofInt(12),
                "height", UiOps.Value.ofInt(24)))
        .createView(
            chevImgId,
            "image",
            p(
                "uri", UiOps.Value.ofString("res://ic_mana_chevron_small_right"),
                "tint", colorVal(mutedColor)))
        .createView(
            divWrapId,
            "view",
            p(
                "padding", UiOps.Value.ofFloat4(56f, 0f, 0f, 0f),
                "width", UiOps.Value.ofString("fill"),
                "height", UiOps.Value.ofInt(1)))
        .createView(
            divLineId,
            "view",
            p(
                "width", UiOps.Value.ofString("fill"),
                "height", UiOps.Value.ofInt(1),
                "background", colorVal(dividerColor)))
        .createView(
            headerBarId,
            "view",
            p(
                "direction", UiOps.Value.ofString("row"),
                "padding", UiOps.Value.ofFloat4(6f, 44f, 6f, 0f),
                "height", UiOps.Value.ofDouble(headerHeight),
                "background", colorVal(screenBg),
                "stackHeader", UiOps.Value.ofBool(true)))
        .setChildren(divWrapId, divLineId)
        .setChildren(chevBoxId, chevImgId)
        .setChildren(acctInnerId, acctIconId, acctLabelId, chevBoxId)
        .setChildren(acctRowId, acctInnerId)
        .setChildren(cardId, acctRowId, divWrapId)
        .setChildren(catWrapId, catHeaderId, cardId)
        .setChildren(scrollColId, catWrapId)
        .setChildren(scrollId, scrollColId)
        .setChildren(inner2, spacerId, topDivId, scrollId, headerBarId)
        .setChildren(inner1, inner2)
        .setChildren(phoneId, inner1)
        .setChildren(modalWrapperId, phoneId)
        .setChildren(5, 6, 997, modalWrapperId)
        .toBytes();
  }

  private static byte[] buildSettingsSubpageBatch(long customSwitchOnBg, long customSwitchOffBg) {
    return UiOps.create()
        .updateView(5, p("index", UiOps.Value.ofInt(3)))
        .createView(
            10865,
            "view",
            p(
                "fullScreenBack", UiOps.Value.ofBool(true),
                "direction", UiOps.Value.ofString("column"),
                "width", UiOps.Value.ofString("fill"),
                "height", UiOps.Value.ofString("fill")))
        .createView(
            10866,
            "view",
            p(
                "direction", UiOps.Value.ofString("column"),
                "width", UiOps.Value.ofString("fill"),
                "height", UiOps.Value.ofString("fill"),
                "background", colorVal(0xFF1C1D22L),
                "accessibilityId", UiOps.Value.ofString("settings-phone")))
        .createView(10871, "scroll", p("width", UiOps.Value.ofString("fill")))
        .createView(
            10872,
            "view",
            p(
                "direction", UiOps.Value.ofString("column"),
                "gap", UiOps.Value.ofInt(24)))
        .createView(
            10881,
            "view",
            p(
                "width", UiOps.Value.ofInt(56),
                "height", UiOps.Value.ofInt(28),
                "background", colorVal(customSwitchOnBg),
                "borderColor", colorVal(0x1F97979FL),
                "pressFeedback", UiOps.Value.ofString("none")))
        .createView(
            10882,
            "view",
            p(
                "width", UiOps.Value.ofInt(56),
                "height", UiOps.Value.ofInt(28),
                "background", colorVal(customSwitchOffBg),
                "borderColor", colorVal(0x3397979FL),
                "pressFeedback", UiOps.Value.ofString("none")))
        .setChildren(10872, 10881, 10882)
        .setChildren(10871, 10872)
        .setChildren(10866, 10871)
        .setChildren(10865, 10866)
        .setChildren(5, 6, 997, 4244, 10865)
        .toBytes();
  }

  private static byte[] packBatch(byte[]... items) {
    int bodyLen = 0;
    for (byte[] item : items) bodyLen += 4 + item.length;
    byte[] out = new byte[Frame.HEADER_SIZE + bodyLen];
    ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
    buf.put((byte) Frame.KIND_BATCH);
    buf.put((byte) 0);
    buf.putShort((short) 0);
    buf.putShort((short) 0);
    buf.putShort((short) 0);
    buf.putInt(0);
    buf.putInt(0);
    for (byte[] item : items) {
      buf.putInt(item.length);
      buf.put(item);
    }
    return out;
  }

  private static List<byte[]> unpackBatch(byte[] batch) {
    ByteBuffer buf = ByteBuffer.wrap(batch).order(ByteOrder.LITTLE_ENDIAN);
    buf.position(Frame.HEADER_SIZE);
    List<byte[]> items = new ArrayList<byte[]>();
    while (buf.hasRemaining()) {
      int len = buf.getInt();
      byte[] sub = new byte[len];
      buf.get(sub);
      items.add(sub);
    }
    return items;
  }

  private static final class RecordingHost implements HostInvoker {
    final List<UiOps> appliedSynthetic = new ArrayList<UiOps>();

    @Override
    public byte[] invokeOrig(byte[] frame) {
      return null;
    }

    @Override
    public void applyOps(byte[] ops, int length) {
      byte[] hooked = Loader.onOps(this, ops, length);
      UiOps parsed = UiOps.parse(hooked, hooked == ops ? length : hooked.length);
      check(parsed != null, "every synthetic op batch must parse cleanly");
      appliedSynthetic.add(parsed);
    }
  }

  private static RecordingHost installSyncHost() {
    RecordingHost host = new RecordingHost();
    Loader.registerHost(host);
    Loader.setSyntheticExecutorForTests(
        new Executor() {
          @Override
          public void execute(Runnable command) {
            command.run();
          }
        });
    return host;
  }

  private static SharedPreferences createInMemoryPrefs(final Map<String, Object> backing) {
    return (SharedPreferences)
        Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(),
            new Class<?>[] {SharedPreferences.class},
            new InvocationHandler() {
              @Override
              public Object invoke(Object proxy, Method method, Object[] args) {
                String mn = method.getName();
                if ("contains".equals(mn)) {
                  return Boolean.valueOf(backing.containsKey(args[0]));
                }
                if ("getBoolean".equals(mn)) {
                  Object v = backing.get(args[0]);
                  return v instanceof Boolean ? v : args[1];
                }
                if ("edit".equals(mn)) {
                  return createEditor(backing);
                }
                return null;
              }
            });
  }

  private static SharedPreferences.Editor createEditor(final Map<String, Object> backing) {
    final Map<String, Object> pending = new HashMap<String, Object>();
    return (SharedPreferences.Editor)
        Proxy.newProxyInstance(
            SharedPreferences.Editor.class.getClassLoader(),
            new Class<?>[] {SharedPreferences.Editor.class},
            new InvocationHandler() {
              @Override
              public Object invoke(Object proxy, Method method, Object[] args) {
                String mn = method.getName();
                if ("putBoolean".equals(mn)) {
                  pending.put((String) args[0], args[1]);
                  return proxy;
                }
                if ("apply".equals(mn)) {
                  backing.putAll(pending);
                  return null;
                }
                if ("commit".equals(mn)) {
                  backing.putAll(pending);
                  return Boolean.TRUE;
                }
                return proxy;
              }
            });
  }

  private static void testSettingsOverviewInjectionAndSubpageExclusion() {
    registerSettingsProps();
    CinderSettings cs = new CinderSettings();
    cs.onLoad(null);
    Loader.setPluginsForTests(cs);

    byte[] unrelated =
        UiOps.create()
            .createView(100, "view", p("width", UiOps.Value.ofString("fill")))
            .requestFrame()
            .toBytes();
    check(
        Loader.onOps(unrelated, unrelated.length) == unrelated,
        "unrelated batch returns original reference");

    byte[] darkBatch =
        buildSettingsOverviewBatch(
            4245,
            4251,
            0xFF1C1D22L,
            0xFFABACB2L,
            0x1497979FL,
            0x12FFFFFFL,
            0xFFFBFBFBL,
            0xFF96989EL,
            0x1F97979FL,
            94.476);
    byte[] modified = Loader.onOps(darkBatch, darkBatch.length);
    check(modified != null && modified != darkBatch, "Settings Overview batch must be rewritten");
    checkEq(cs.settingsNavIdForTests(), 5, "discovered Settings navigator id=5");

    UiOps parsed = UiOps.parse(modified);
    check(parsed != null, "modified Settings batch parses cleanly");

    Set<Integer> createdBeforeScrollChildren = new HashSet<Integer>();
    int[] scrollChildren = null;
    boolean foundFireIcon = false;
    boolean foundCinderRow = false;
    for (UiOps.Op op : parsed.ops()) {
      if (op.opcode() == UiOps.OP_CREATE) {
        if (scrollChildren == null) {
          createdBeforeScrollChildren.add(Integer.valueOf(op.viewId()));
        }
        if ("res://ic_mana_fire".equals(op.propString("uri"))) {
          foundFireIcon = true;
        }
        if ("settings.row.cinder".equals(op.propString("accessibilityId"))) {
          foundCinderRow = true;
          check(UiOps.isSyntheticId(op.viewId()), "Cinder row has synthetic viewId");
          checkEq(op.viewId(), cs.activeCinderRowIdForTests(), "tracked cinderRowId matches");
        }
      } else if (op.opcode() == UiOps.OP_CHILDREN && op.viewId() == 4251) {
        scrollChildren = op.childIds();
      }
    }
    check(foundFireIcon, "injected Cinder row uses res://ic_mana_fire");
    check(foundCinderRow, "injected Cinder row has accessibilityId=settings.row.cinder");
    check(scrollChildren != null && scrollChildren.length == 2, "scroll column now has 2 children");
    check(
        createdBeforeScrollChildren.contains(Integer.valueOf(scrollChildren[1])),
        "Cinder category created before scroll column OP_CHILDREN");

    byte[] subpageBatch = buildSettingsSubpageBatch(0xFF5865F2L, 0x22112233L);
    byte[] subpageResult = Loader.onOps(subpageBatch, subpageBatch.length);
    check(
        subpageResult == subpageBatch,
        "Settings subpage must NOT be injected into (bugfix regression test)");

    checkEq(
        Long.valueOf(cs.activeThemeForTests().switchOffBg),
        Long.valueOf(0x22112233L),
        "subpage still updates live ManaUi.Theme switchOffBg");

    byte[] lightBatch =
        buildSettingsOverviewBatch(
            5245,
            5251,
            0xFFF2F3F5L,
            0xFF4E5058L,
            0xFFFFFFFFL,
            0x14000000L,
            0xFF060607L,
            0xFF5C5E66L,
            0x1F060607L,
            88.0);
    byte[] modifiedLight = Loader.onOps(lightBatch, lightBatch.length);
    check(modifiedLight != lightBatch, "light Settings Overview injected");
    ManaUi.Theme lightTheme = cs.activeThemeForTests();
    checkEq(Long.valueOf(lightTheme.screenBg), Long.valueOf(0xFFF2F3F5L), "light screenBg");
    checkEq(Long.valueOf(lightTheme.primaryText), Long.valueOf(0xFF060607L), "light primaryText");
    checkEq(Long.valueOf(lightTheme.headerText), Long.valueOf(0xFF4E5058L), "light headerText");
    checkEq(
        Double.valueOf(lightTheme.statusBarHeight), Double.valueOf(44.0), "live statusBarHeight");
  }

  private static void testNavigatorSlidePushPopAndSwipeBack() {
    registerSettingsProps();
    RecordingHost host = installSyncHost();

    CinderSettings cs = new CinderSettings();
    ModPlugin extraPlugin =
        new ModPlugin() {
          @Override
          public String id() {
            return "custom-plugin";
          }

          @Override
          public String version() {
            return "9.8.7";
          }
        };
    cs.onLoad(null);
    Loader.setPluginsForTests(cs, extraPlugin);

    final AtomicBoolean actionPressed = new AtomicBoolean(false);
    CinderSettings.registerToggle("custom.feature", "Custom Feature", "From plugin", true, null);
    CinderSettings.registerAction(
        "custom.act",
        "Run Diagnostics",
        "Health check",
        "res://ic_mana_beaker",
        new Runnable() {
          @Override
          public void run() {
            actionPressed.set(true);
          }
        });

    byte[] settings =
        buildSettingsOverviewBatch(
            4245,
            4251,
            0xFF1C1D22L,
            0xFFABACB2L,
            0x1497979FL,
            0x12FFFFFFL,
            0xFFFBFBFBL,
            0xFF96989EL,
            0x1F97979FL,
            94.476);
    Loader.onOps(settings, settings.length);

    byte[] pressCinder =
        UiOps.ViewEvent.buildViewEvent(cs.activeCinderRowIdForTests(), UiOps.propId("press"), null);
    check(Loader.onDispatch(pressCinder) == null, "Cinder row press swallowed");
    check(cs.isPageOpenForTests(), "Cinder page is open");
    checkEq(host.appliedSynthetic.size(), 1, "1 synthetic push batch applied");

    UiOps pushBatch = host.appliedSynthetic.get(0);
    int pageRootId = cs.pageRootIdForTests();
    boolean foundFullScreenBack = false;
    boolean foundStackHeader = false;
    boolean foundNavIndex3 = false;
    int[] pushedNavKids = null;
    boolean foundCustomPlugin = false;
    boolean foundAction = false;
    for (UiOps.Op op : pushBatch.ops()) {
      if (op.opcode() == UiOps.OP_CREATE) {
        if (op.viewId() == pageRootId
            && op.findProp("fullScreenBack") != null
            && op.findProp("fullScreenBack").value.asBoolean()) {
          foundFullScreenBack = true;
        }
        if (op.findProp("stackHeader") != null && op.findProp("stackHeader").value.asBoolean()) {
          foundStackHeader = true;
        }
        if ("Custom Plugin".equals(op.propString("text"))) foundCustomPlugin = true;
        if ("Run Diagnostics".equals(op.propString("text"))) foundAction = true;
      } else if (op.opcode() == UiOps.OP_UPDATE && op.viewId() == 5) {
        UiOps.Prop idx = op.findProp("index");
        if (idx != null && idx.value.asInt() == 3) foundNavIndex3 = true;
      } else if (op.opcode() == UiOps.OP_CHILDREN && op.viewId() == 5) {
        pushedNavKids = op.childIds();
      }
    }
    check(foundFullScreenBack, "page root has fullScreenBack=true for native swipe-back");
    check(foundStackHeader, "page uses native stackHeader=true bar");
    check(foundNavIndex3, "navigator id=5 updated to index=3");
    check(
        pushedNavKids != null && pushedNavKids.length == 4 && pushedNavKids[3] == pageRootId,
        "navigator id=5 children appended pageRootId");
    check(foundCustomPlugin, "lists loaded plugins");
    check(foundAction, "lists registered action");

    int actionRowId = cs.actionRowIdForTests("custom.act");
    check(
        Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(actionRowId, UiOps.propId("press"), null))
            == null,
        "action press dropped");
    check(actionPressed.get(), "action Runnable invoked");

    int activeSlotsBeforeClose = ManaMotion.activeSlotCount(CinderSettings.THEME_SCOPE_PAGE);
    check(activeSlotsBeforeClose >= 1, "registered toggle allocated scoped native motion slot");
    int backBtnId = cs.backButtonIdForTests();
    check(
        Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(backBtnId, UiOps.propId("press"), null))
            == null,
        "Back arrow press dropped");
    check(!cs.isPageOpenForTests(), "Cinder page closed after Back arrow");
    checkEq(
        ManaMotion.activeSlotCount(CinderSettings.THEME_SCOPE_PAGE),
        0,
        "all page motion slots released on close");

    UiOps popBatch = host.appliedSynthetic.get(host.appliedSynthetic.size() - 1);
    checkEq(popBatch.size(), 4, "exact 4-op native navigator pop + OP_MOTION slot free batch");
    checkEq(popBatch.get(0).opcode(), UiOps.OP_UPDATE, "op 0 is update navigator index");
    checkEq(popBatch.get(0).viewId(), 5, "op 0 targets navigator 5");
    checkEq(popBatch.get(0).findProp("index").value.asInt(), 2, "restores index=2");
    checkEq(popBatch.get(1).opcode(), UiOps.OP_DELETE, "op 1 deletes all synthetic views");
    checkEq(popBatch.get(2).opcode(), UiOps.OP_CHILDREN, "op 2 restores navigator children");
    check(
        Arrays.equals(popBatch.get(2).childIds(), new int[] {6, 997, 4244}),
        "restored exact base navigator children [6,997,4244]");
    checkEq(
        popBatch.get(3).opcode(), UiOps.OP_MOTION, "op 3 frees page motion slots via OP_FREE_SLOT");
    int[] freeWords = popBatch.get(3).motionWords();
    checkEq(
        freeWords.length, activeSlotsBeforeClose * 2, "OP_FREE_SLOT emitted for every page slot");
    for (int i = 0; i < freeWords.length; i += 2) {
      checkEq(freeWords[i], ManaMotion.OP_FREE_SLOT, "free command opcode is 9 (OP_FREE_SLOT)");
      check(
          freeWords[i + 1] >= ManaMotion.SLOT_MIN && freeWords[i + 1] <= ManaMotion.SLOT_MAX,
          "freed slot in safe synthetic range");
    }

    byte[] appearedAfterPop = UiOps.ViewEvent.buildViewEvent(5, UiOps.propId("appeared"), null);
    check(Loader.onDispatch(appearedAfterPop) == null, "post-pop appeared event swallowed");

    Loader.onDispatch(pressCinder);
    check(cs.isPageOpenForTests(), "reopened Cinder page");
    byte[] swipeBackNav = UiOps.ViewEvent.buildViewEvent(5, UiOps.propId("navigate"), null);
    check(Loader.onDispatch(swipeBackNav) == null, "swipe-back navigate event swallowed");
    check(!cs.isPageOpenForTests(), "swipe-back closed Cinder page cleanly");
  }

  private static void testRealManaSwitchTrackAndRowToggle() {
    registerSettingsProps();
    RecordingHost host = installSyncHost();

    Map<String, Object> backingPrefs = new HashMap<String, Object>();
    CinderSettings.prefsOverrideForTests = createInMemoryPrefs(backingPrefs);

    CinderSettings cs = new CinderSettings();
    cs.onLoad(null);
    Loader.setPluginsForTests(cs);

    final List<Boolean> listenerLog = new ArrayList<Boolean>();
    CinderSettings.registerToggle(
        "test.switch",
        "Test Mana Switch",
        "Verifies track tap vs row tap",
        false,
        new CinderSettings.ToggleListener() {
          @Override
          public void onChanged(boolean value) {
            listenerLog.add(Boolean.valueOf(value));
          }
        });

    byte[] settings =
        buildSettingsOverviewBatch(
            4245,
            4251,
            0xFF1C1D22L,
            0xFFABACB2L,
            0x1497979FL,
            0x12FFFFFFL,
            0xFFFBFBFBL,
            0xFF96989EL,
            0x1F97979FL,
            94.476);
    Loader.onOps(settings, settings.length);
    Loader.onDispatch(
        UiOps.ViewEvent.buildViewEvent(
            cs.activeCinderRowIdForTests(), UiOps.propId("press"), null));

    int rowId = cs.toggleRowIdForTests("test.switch");
    int trackId = cs.toggleTrackIdForTests("test.switch");
    int thumbId = cs.toggleThumbIdForTests("test.switch");
    check(rowId != -1 && trackId != -1 && thumbId != -1, "switch row/track/thumb IDs bound");

    int motionSlot = cs.toggleMotionSlotForTests("test.switch");
    check(
        motionSlot >= ManaMotion.SLOT_MIN && motionSlot <= ManaMotion.SLOT_MAX,
        "switch allocated native motionSlot in safe range");

    UiOps openBatch = host.appliedSynthetic.get(0);
    UiOps.Op trackOp = null;
    UiOps.Op thumbOp = null;
    boolean foundSlotInit = false;
    for (UiOps.Op op : openBatch.ops()) {
      if (op.opcode() == UiOps.OP_CREATE && op.viewId() == trackId) trackOp = op;
      if (op.opcode() == UiOps.OP_CREATE && op.viewId() == thumbId) thumbOp = op;
      if (op.opcode() == UiOps.OP_MOTION) {
        int[] w = op.motionWords();
        if (w != null
            && w.length == 3
            && w[0] == ManaMotion.OP_INIT_VALUE
            && w[1] == motionSlot
            && w[2] == ManaMotion.f32(0f)) {
          foundSlotInit = true;
        }
      }
    }
    check(trackOp != null && thumbOp != null, "found track and thumb OP_CREATE");
    check(foundSlotInit, "OP_MOTION initialized switch progressSlot to 0.0f via OP_INIT_VALUE (8)");
    checkEq(trackOp.findProp("width").value.asInt(), 56, "Mana switch width=56");
    checkEq(trackOp.findProp("height").value.asInt(), 28, "Mana switch height=28");
    checkEq(trackOp.findProp("radius").value.asInt(), 16, "Mana switch radius=16");
    checkEq(trackOp.findProp("borderWidth").value.asInt(), 1, "Mana switch borderWidth=1");
    checkEq(
        trackOp.propString("pressFeedback"),
        "none",
        "track has pressFeedback=none (no row highlight!)");
    int[] trackWorklet = trackOp.findProp("worklet").value.asWordList();
    check(
        trackWorklet != null
            && trackWorklet.length == 14
            && trackWorklet[0] == ManaMotion.WORKLET_SAMPLED
            && trackWorklet[1] == motionSlot,
        "track has 14-word builtin::sampled worklet");
    checkEq(thumbOp.findProp("width").value.asInt(), 20, "thumb width=20");
    checkEq(thumbOp.findProp("translateX").value.asInt(), 0, "OFF thumb translateX=0");
    int[] thumbWorklet = thumbOp.findProp("worklet").value.asWordList();
    check(
        thumbWorklet != null
            && thumbWorklet.length == 10
            && thumbWorklet[0] == ManaMotion.WORKLET_SAMPLED
            && thumbWorklet[1] == motionSlot,
        "thumb has 10-word builtin::sampled worklet");

    byte[] tapTrack = UiOps.ViewEvent.buildViewEvent(trackId, UiOps.propId("press"), null);
    check(Loader.onDispatch(tapTrack) == null, "track press dropped");
    check(CinderSettings.getToggleValue("test.switch", false), "track tap flipped to true");
    checkEq(backingPrefs.get("test.switch"), Boolean.TRUE, "persisted true");

    UiOps updOn = host.appliedSynthetic.get(host.appliedSynthetic.size() - 1);
    checkEq(updOn.size(), 2, "native switch toggle is exact 2-op batch (a11y + OP_MOTION)");
    checkEq(updOn.get(0).opcode(), UiOps.OP_UPDATE, "op 0 updates row accessibilityChecked");
    checkEq(updOn.get(0).viewId(), rowId, "op 0 targets toggle row");
    check(
        updOn.get(0).findProp("accessibilityChecked").value.asBoolean(),
        "accessibilityChecked=true");
    checkEq(
        updOn.get(1).opcode(), UiOps.OP_MOTION, "op 1 drives native 120Hz spring via OP_MOTION");
    int[] onWords = updOn.get(1).motionWords();
    checkEq(onWords.length, 13, "13-word REDUCED_MOTION(SPRING(1,300,27)) command");
    checkEq(onWords[0], ManaMotion.OP_ANIMATE, "OP_ANIMATE (2)");
    checkEq(onWords[1], motionSlot, "targets switch progressSlot");
    checkEq(onWords[3], ManaMotion.ANIM_REDUCED_MOTION, "REDUCED_MOTION (7)");
    checkEq(onWords[4], ManaMotion.ANIM_SPRING, "ANIM_SPRING (2)");
    checkEq(onWords[5], ManaMotion.f32(1f), "springs to 1.0f");

    byte[] tapRow = UiOps.ViewEvent.buildViewEvent(rowId, UiOps.propId("press"), null);
    check(Loader.onDispatch(tapRow) == null, "row press dropped");
    check(!CinderSettings.getToggleValue("test.switch", true), "row tap flipped back to false");
    checkEq(listenerLog, Arrays.asList(Boolean.TRUE, Boolean.FALSE), "listener got [true, false]");
    UiOps updOff = host.appliedSynthetic.get(host.appliedSynthetic.size() - 1);
    checkEq(updOff.size(), 2, "OFF toggle is exact 2-op batch");
    check(
        !updOff.get(0).findProp("accessibilityChecked").value.asBoolean(),
        "accessibilityChecked=false");
    int[] offWords = updOff.get(1).motionWords();
    checkEq(offWords[5], ManaMotion.f32(0f), "springs back to 0.0f");
  }

  private static void testHardwareBackAndMixedBatchIsolation() {
    registerSettingsProps();
    RecordingHost host = installSyncHost();

    CinderSettings cs = new CinderSettings();
    cs.onLoad(null);
    Loader.setPluginsForTests(cs);

    byte[] settings =
        buildSettingsOverviewBatch(
            4245,
            4251,
            0xFF1C1D22L,
            0xFFABACB2L,
            0x1497979FL,
            0x12FFFFFFL,
            0xFFFBFBFBL,
            0xFF96989EL,
            0x1F97979FL,
            94.476);
    Loader.onOps(settings, settings.length);

    byte[] backEvent = UiOps.ViewEvent.buildBackEvent();
    check(Loader.onDispatch(backEvent) == backEvent, "back passes through when page closed");

    Loader.onDispatch(
        UiOps.ViewEvent.buildViewEvent(
            cs.activeCinderRowIdForTests(), UiOps.propId("press"), null));
    check(cs.isPageOpenForTests(), "page open");

    byte[] realEvent = UiOps.ViewEvent.buildViewEvent(42, UiOps.propId("press"), null);
    byte[] backBtnEvent =
        UiOps.ViewEvent.buildViewEvent(cs.backButtonIdForTests(), UiOps.propId("press"), null);
    byte[] mixed = packBatch(realEvent, backBtnEvent);
    byte[] out = Loader.onDispatch(mixed);
    check(out != null, "real Discord event preserved in mixed batch");
    List<byte[]> kept = unpackBatch(out);
    checkEq(kept.size(), 1, "only real event survived");
    check(Arrays.equals(kept.get(0), realEvent), "real event bytes untouched");
    check(!cs.isPageOpenForTests(), "synthetic back button closed Cinder page");

    Loader.onDispatch(
        UiOps.ViewEvent.buildViewEvent(
            cs.activeCinderRowIdForTests(), UiOps.propId("press"), null));
    check(cs.isPageOpenForTests(), "reopened");
    check(Loader.onDispatch(backEvent) == null, "hardware back consumed when page open");
    check(!cs.isPageOpenForTests(), "hardware back closed page");
    check(host.appliedSynthetic.size() >= 4, "push + pop cycles recorded");
  }

  private static void testAllManaUiComponentsRoundTrip() {
    registerSettingsProps();
    ManaUi.Theme theme = ManaUi.Theme.defaults();
    final AtomicInteger seq = new AtomicInteger(UiOps.SYNTHETIC_ID_BASE + 50000);
    ManaUi.TrackingAllocator ids =
        new ManaUi.TrackingAllocator(
            new ManaUi.IdAllocator() {
              @Override
              public int nextId() {
                return seq.getAndIncrement();
              }
            });

    UiOps batch = new UiOps();
    ManaUi.PageScaffold page =
        ManaUi.pageScaffold(batch, ids, theme, "Mana Showcase", "mana-showcase");

    ManaUi.SearchInputHandle search =
        ManaUi.searchInput(batch, ids, theme, "Filter...", "", "showcase-search");
    ManaUi.SegmentedHandle seg =
        ManaUi.segmentedControl(
            batch, ids, theme, new String[] {"Follow", "Force On", "Force Off"}, 0);
    ManaUi.RadioRowHandle r0 =
        ManaUi.radioRow(
            batch,
            ids,
            theme,
            "Option A",
            "First choice",
            true,
            "radio-a",
            ManaUi.RowPosition.FIRST);
    ManaUi.RadioRowHandle r1 =
        ManaUi.radioRow(
            batch, ids, theme, "Option B", null, false, "radio-b", ManaUi.RowPosition.LAST);
    int radioSection =
        ManaUi.section(
            batch,
            ids,
            theme,
            "Radio Group",
            new int[] {r0.rowId, r1.rowId},
            "Pick one option.",
            true);
    int primaryBtn =
        ManaUi.button(
            batch,
            ids,
            theme,
            "Apply",
            ManaUi.ICON_SPARKLES,
            ManaUi.ButtonVariant.PRIMARY,
            "btn-apply");
    int dangerBtn =
        ManaUi.button(batch, ids, theme, "Reset", null, ManaUi.ButtonVariant.DANGER, "btn-reset");
    int pillBadge = ManaUi.badge(batch, ids, "NEW", theme.brandAccent, theme.buttonText);
    ManaUi.BottomSheetHandle sheet =
        ManaUi.bottomSheet(batch, ids, theme, "Confirm", "Details", radioSection);

    page.finish(batch, search.rootId, seg.rootId, radioSection, primaryBtn, dangerBtn, pillBadge);

    ManaUi.updateRadioRow(batch, r0, false, theme);
    ManaUi.updateRadioRow(batch, r1, true, theme);
    ManaUi.updateSegmentedControl(batch, seg, 2, theme);

    byte[] encoded = batch.toBytes();
    UiOps reparsed = UiOps.parse(encoded);
    check(reparsed != null, "full ManaUi component catalog round-trips through ViewWire v1");
    checkEq(reparsed.size(), batch.size(), "exact op count preserved on round-trip");
    check(sheet.sheetId >= UiOps.SYNTHETIC_ID_BASE, "bottom sheet created with synthetic ID");
    check(ids.allocatedIds().size() > 30, "allocated all component views");

    double[] pv = new double[] {0.0, 0.0};
    boolean settled = false;
    int frames = 0;
    for (int f = 0; f < 60; f++) {
      frames++;
      settled =
          ManaAnimator.stepSpring(
              pv,
              1.0,
              ManaAnimator.DEFAULT_MASS,
              ManaAnimator.DEFAULT_STIFFNESS,
              ManaAnimator.DEFAULT_DAMPING,
              1.0 / 60.0);
      if (settled) break;
    }
    check(settled, "ManaAnimator spring must settle within 60 frames");
    check(
        frames >= 10 && frames <= 45, "spring settles in natural duration (frames=" + frames + ")");
    checkEq(Double.valueOf(pv[0]), Double.valueOf(1.0), "spring settles exactly at 1.0");
    checkEq(
        Long.valueOf(ManaAnimator.lerpArgb(0x00000000L, 0xFFFFFFFFL, 0.0)),
        Long.valueOf(0x00000000L),
        "lerp 0");
    checkEq(
        Long.valueOf(ManaAnimator.lerpArgb(0x00000000L, 0xFFFFFFFFL, 1.0)),
        Long.valueOf(0xFFFFFFFFL),
        "lerp 1");
    checkEq(
        Long.valueOf(ManaAnimator.lerpArgb(0xFF000000L, 0xFF64C864L, 0.5)),
        Long.valueOf(0xFF326432L),
        "lerp 0.5");
  }

  private static void testBuiltinTogglesNotAutoRegisteredOnCinderRoot() {
    registerSettingsProps();
    RecordingHost host = installSyncHost();

    CinderSettings cs = new CinderSettings();
    cs.onLoad(null);
    Loader.setPluginsForTests(cs);

    checkEq(
        CinderSettings.snapshotToggles().size(),
        0,
        "no built-in toggles auto-registered into global CinderSettings.TOGGLES");

    Map<String, Object> backing = new HashMap<String, Object>();
    CinderSettings.prefsOverrideForTests = createInMemoryPrefs(backing);
    check(!CinderSettings.isVerboseBridgeLogsEnabled(), "verbose default false");
    check(!CinderSettings.isDumpSettingsOpsEnabled(), "dump default false");
    CinderSettings.setToggleValue(CinderSettings.KEY_VERBOSE_BRIDGE_LOGS, true);
    CinderSettings.setToggleValue(CinderSettings.KEY_DUMP_SETTINGS_OPS, true);
    check(CinderSettings.isVerboseBridgeLogsEnabled(), "verbose persisted true");
    check(CinderSettings.isDumpSettingsOpsEnabled(), "dump persisted true");
    checkEq(
        CinderSettings.snapshotToggles().size(),
        0,
        "persisting CinderDiagnosticsPlugin keys still leaves global TOGGLES empty");

    byte[] settings =
        buildSettingsOverviewBatch(
            4245,
            4251,
            0xFF1C1D22L,
            0xFFABACB2L,
            0x1497979FL,
            0x12FFFFFFL,
            0xFFFBFBFBL,
            0xFF96989EL,
            0x1F97979FL,
            94.476);
    Loader.onOps(settings, settings.length);
    Loader.onDispatch(
        UiOps.ViewEvent.buildViewEvent(
            cs.activeCinderRowIdForTests(), UiOps.propId("press"), null));
    UiOps openBatch = host.appliedSynthetic.get(host.appliedSynthetic.size() - 1);
    for (UiOps.Op op : openBatch.ops()) {
      if (op.opcode() == UiOps.OP_CREATE && "text".equals(op.viewType())) {
        String txt = op.propString("text");
        check(!"Options".equals(txt), "Options section hidden when no external toggles registered");
        check(
            !"Verbose Bridge Logs".equals(txt),
            "Verbose Bridge Logs not duplicated on Cinder root");
        check(!"Dump Settings Ops".equals(txt), "Dump Settings Ops not duplicated on Cinder root");
      }
    }
  }

  public static void main(String[] args) {
    runTest(
        "settingsOverviewInjectionAndSubpageExclusion",
        new Runnable() {
          @Override
          public void run() {
            testSettingsOverviewInjectionAndSubpageExclusion();
          }
        });
    runTest(
        "navigatorSlidePushPopAndSwipeBack",
        new Runnable() {
          @Override
          public void run() {
            testNavigatorSlidePushPopAndSwipeBack();
          }
        });
    runTest(
        "realManaSwitchTrackAndRowToggle",
        new Runnable() {
          @Override
          public void run() {
            testRealManaSwitchTrackAndRowToggle();
          }
        });
    runTest(
        "hardwareBackAndMixedBatchIsolation",
        new Runnable() {
          @Override
          public void run() {
            testHardwareBackAndMixedBatchIsolation();
          }
        });
    runTest(
        "allManaUiComponentsRoundTrip",
        new Runnable() {
          @Override
          public void run() {
            testAllManaUiComponentsRoundTrip();
          }
        });
    runTest(
        "builtinTogglesNotAutoRegisteredOnCinderRoot",
        new Runnable() {
          @Override
          public void run() {
            testBuiltinTogglesNotAutoRegisteredOnCinderRoot();
          }
        });
    System.out.println("ALL " + passed + " CinderSettingsTest TESTS PASSED");
  }
}
