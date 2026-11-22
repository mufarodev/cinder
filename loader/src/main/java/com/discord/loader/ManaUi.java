package com.discord.loader;

import com.discord.loader.mana.ColorToken;
import com.discord.loader.mana.ManaIcons;
import com.discord.loader.mana.ManaMotion;
import com.discord.loader.mana.ManaPalette;
import com.discord.loader.mana.ThemeEngine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ManaUi {
  private ManaUi() {}

  public static final String ICON_BACK_ARROW = ManaIcons.ARROW_LARGE_LEFT;
  public static final String ICON_CHEVRON_RIGHT = ManaIcons.CHEVRON_SMALL_RIGHT;
  public static final String ICON_SEARCH = ManaIcons.MAGNIFYING_GLASS;
  public static final String ICON_FIRE = ManaIcons.FIRE;
  public static final String ICON_PUZZLE = ManaIcons.PUZZLE_PIECE;
  public static final String ICON_WRENCH = ManaIcons.WRENCH;
  public static final String ICON_SPARKLES = ManaIcons.SPARKLES;

  public static final UiOps.Value SWITCH_SPRING = springConfig(1.0, 300.0, 27.0);

  public static final UiOps.Value FAST_TIMING = timingConfig(180L, "standard");

  public static UiOps.Value springConfig(double mass, double stiffness, double damping) {
    List<UiOps.NamedValue> inner = new ArrayList<UiOps.NamedValue>(3);
    inner.add(new UiOps.NamedValue("mass", UiOps.Value.ofDouble(mass)));
    inner.add(new UiOps.NamedValue("stiffness", UiOps.Value.ofDouble(stiffness)));
    inner.add(new UiOps.NamedValue("damping", UiOps.Value.ofDouble(damping)));
    return UiOps.Value.ofStringMap(
        Collections.singletonList(new UiOps.NamedValue("spring", UiOps.Value.ofStringMap(inner))));
  }

  public static UiOps.Value timingConfig(long ms, String curve) {
    List<UiOps.NamedValue> inner = new ArrayList<UiOps.NamedValue>(2);
    inner.add(new UiOps.NamedValue("ms", UiOps.Value.ofLong(ms)));
    inner.add(
        new UiOps.NamedValue("curve", UiOps.Value.ofString(curve != null ? curve : "standard")));
    return UiOps.Value.ofStringMap(
        Collections.singletonList(new UiOps.NamedValue("timing", UiOps.Value.ofStringMap(inner))));
  }

  public interface IdAllocator {
    int nextId();
  }

  public static final class TrackingAllocator implements IdAllocator {
    private final IdAllocator source;
    private final List<Integer> allocated = new ArrayList<Integer>();

    public TrackingAllocator(IdAllocator source) {
      this.source = source;
    }

    @Override
    public int nextId() {
      int id = source.nextId();
      allocated.add(Integer.valueOf(id));
      return id;
    }

    public List<Integer> allocatedIds() {
      return allocated;
    }

    public int[] toIntArray() {
      int[] out = new int[allocated.size()];
      for (int i = 0; i < out.length; i++) {
        out[i] = allocated.get(i).intValue();
      }
      return out;
    }
  }

  public enum RowPosition {
    SINGLE,
    FIRST,
    MIDDLE,
    LAST;

    public static RowPosition forIndex(int index, int count) {
      if (count <= 1) return SINGLE;
      if (index <= 0) return FIRST;
      if (index >= count - 1) return LAST;
      return MIDDLE;
    }
  }

  public enum ButtonVariant {
    PRIMARY,
    SECONDARY,
    DANGER,
    GHOST,
    LINK
  }

  public enum ButtonSize {
    SM,
    MD,
    LG
  }

  public static int icon(
      UiOps batch, IdAllocator ids, String uri, int size, ColorToken colorToken) {
    return icon(batch, ids, uri, size, size, colorToken, ThemeEngine.currentPalette());
  }

  public static int icon(
      UiOps batch,
      IdAllocator ids,
      String uri,
      int size,
      ColorToken colorToken,
      ManaPalette palette) {
    return icon(batch, ids, uri, size, size, colorToken, palette);
  }

  public static int icon(
      UiOps batch,
      IdAllocator ids,
      String uri,
      int width,
      int height,
      ColorToken colorToken,
      ManaPalette palette) {
    ManaPalette p = palette != null ? palette : ThemeEngine.currentPalette();
    ColorToken tok = colorToken != null ? colorToken : ColorToken.ICON_PRIMARY;
    return icon(batch, ids, uri, width, height, p.color(tok), tok, 0f, 0f, 0f, 0f);
  }

  public static int icon(UiOps batch, IdAllocator ids, String uri, int size, long tintArgb) {
    return icon(batch, ids, uri, size, size, tintArgb, null, 0f, 0f, 0f, 0f);
  }

  public static int icon(
      UiOps batch, IdAllocator ids, String uri, int width, int height, long tintArgb) {
    return icon(batch, ids, uri, width, height, tintArgb, null, 0f, 0f, 0f, 0f);
  }

  public static int icon(
      UiOps batch,
      IdAllocator ids,
      String uri,
      int width,
      int height,
      long tintArgb,
      ColorToken tintToken,
      float marginLeft,
      float marginTop,
      float marginRight,
      float marginBottom) {
    int iconId = ids.nextId();
    String normalizedUri = ManaIcons.uri(uri != null ? uri : ManaIcons.SPARKLES);
    List<UiOps.Prop> iconProps = new ArrayList<UiOps.Prop>(6);
    addProp(iconProps, "uri", UiOps.Value.ofString(normalizedUri));
    if (marginLeft != 0f || marginTop != 0f || marginRight != 0f || marginBottom != 0f) {
      addProp(
          iconProps,
          "margin",
          UiOps.Value.ofFloat4(marginLeft, marginTop, marginRight, marginBottom));
    }
    addProp(iconProps, "width", UiOps.Value.ofInt(width));
    addProp(iconProps, "height", UiOps.Value.ofInt(height));
    addProp(iconProps, "tint", colorVal(tintArgb));
    addProp(iconProps, "scale", UiOps.Value.ofString("fit"));
    batch.createView(iconId, "image", iconProps);
    if (tintToken != null) {
      ThemeEngine.bindActive(iconId, "tint", tintToken);
    }
    return iconId;
  }

  public static final class Theme {
    public static final long DEFAULT_SCREEN_BG = 0xFF1C1D22L;
    public static final long DEFAULT_ROW_BG = 0x1497979FL;
    public static final long DEFAULT_ROW_ACTIVE_BG = 0x12FFFFFFL;
    public static final long DEFAULT_PRIMARY_TEXT = 0xFFFBFBFBL;
    public static final long DEFAULT_HEADER_TEXT = 0xFFABACB2L;
    public static final long DEFAULT_MUTED_TEXT = 0xFF96989EL;
    public static final long DEFAULT_DIVIDER = 0x1F97979FL;
    public static final long DEFAULT_BRAND_ACCENT = 0xFF5865F2L;
    public static final long DEFAULT_DANGER_TEXT = 0xFFF87E7AL;
    public static final long DEFAULT_DANGER_BG = 0xFFD22D39L;
    public static final long DEFAULT_SWITCH_ON_BG = 0xFF5865F2L;
    public static final long DEFAULT_SWITCH_ON_BORDER = 0x1F97979FL;
    public static final long DEFAULT_SWITCH_OFF_BG = 0x1F000000L;
    public static final long DEFAULT_SWITCH_OFF_BORDER = 0x3397979FL;
    public static final long DEFAULT_SWITCH_THUMB_BG = 0xFFFFFFFFL;
    public static final long DEFAULT_RADIO_UNCHECKED_BORDER = 0xA397979FL;
    public static final long DEFAULT_SEGMENT_TRACK_BG = 0x1F97979FL;
    public static final long DEFAULT_SEGMENT_THUMB_BG = 0x3397979FL;
    public static final long DEFAULT_BUTTON_BORDER = 0x14FFFFFFL;
    public static final long DEFAULT_BUTTON_TEXT = 0xFFFFFFFFL;
    public static final long DEFAULT_HANDLE_COLOR = 0x3397979FL;
    public static final double DEFAULT_HEADER_HEIGHT = 94.47618865966797;
    public static final double DEFAULT_STATUS_BAR_HEIGHT = 46.47619;
    public static final double DEFAULT_BOTTOM_INSET = 30.857143;

    public final long screenBg;
    public final long rowBg;
    public final long rowActiveBg;
    public final long primaryText;
    public final long headerText;
    public final long mutedText;
    public final long dividerColor;
    public final long brandAccent;
    public final long dangerText;
    public final long dangerBg;
    public final long switchOnBg;
    public final long switchOnBorder;
    public final long switchOffBg;
    public final long switchOffBorder;
    public final long switchThumbBg;
    public final long radioUncheckedBorder;
    public final long segmentTrackBg;
    public final long segmentThumbBg;
    public final long buttonBorder;
    public final long buttonText;
    public final long handleColor;
    public final double headerHeight;
    public final double statusBarHeight;
    public final double bottomInset;

    public Theme(
        long screenBg,
        long rowBg,
        long rowActiveBg,
        long primaryText,
        long headerText,
        long mutedText,
        long dividerColor,
        long brandAccent,
        long dangerText,
        long dangerBg,
        long switchOnBg,
        long switchOnBorder,
        long switchOffBg,
        long switchOffBorder,
        long switchThumbBg,
        long radioUncheckedBorder,
        long segmentTrackBg,
        long segmentThumbBg,
        long buttonBorder,
        long buttonText,
        long handleColor,
        double headerHeight,
        double statusBarHeight,
        double bottomInset) {
      this.screenBg = screenBg;
      this.rowBg = rowBg;
      this.rowActiveBg = rowActiveBg;
      this.primaryText = primaryText;
      this.headerText = headerText;
      this.mutedText = mutedText;
      this.dividerColor = dividerColor;
      this.brandAccent = brandAccent;
      this.dangerText = dangerText;
      this.dangerBg = dangerBg;
      this.switchOnBg = switchOnBg;
      this.switchOnBorder = switchOnBorder;
      this.switchOffBg = switchOffBg;
      this.switchOffBorder = switchOffBorder;
      this.switchThumbBg = switchThumbBg;
      this.radioUncheckedBorder = radioUncheckedBorder;
      this.segmentTrackBg = segmentTrackBg;
      this.segmentThumbBg = segmentThumbBg;
      this.buttonBorder = buttonBorder;
      this.buttonText = buttonText;
      this.handleColor = handleColor;
      this.headerHeight = headerHeight;
      this.statusBarHeight = statusBarHeight;
      this.bottomInset = bottomInset;
    }

    public static Theme defaults() {
      return new Theme(
          DEFAULT_SCREEN_BG,
          DEFAULT_ROW_BG,
          DEFAULT_ROW_ACTIVE_BG,
          DEFAULT_PRIMARY_TEXT,
          DEFAULT_HEADER_TEXT,
          DEFAULT_MUTED_TEXT,
          DEFAULT_DIVIDER,
          DEFAULT_BRAND_ACCENT,
          DEFAULT_DANGER_TEXT,
          DEFAULT_DANGER_BG,
          DEFAULT_SWITCH_ON_BG,
          DEFAULT_SWITCH_ON_BORDER,
          DEFAULT_SWITCH_OFF_BG,
          DEFAULT_SWITCH_OFF_BORDER,
          DEFAULT_SWITCH_THUMB_BG,
          DEFAULT_RADIO_UNCHECKED_BORDER,
          DEFAULT_SEGMENT_TRACK_BG,
          DEFAULT_SEGMENT_THUMB_BG,
          DEFAULT_BUTTON_BORDER,
          DEFAULT_BUTTON_TEXT,
          DEFAULT_HANDLE_COLOR,
          DEFAULT_HEADER_HEIGHT,
          DEFAULT_STATUS_BAR_HEIGHT,
          DEFAULT_BOTTOM_INSET);
    }

    public static Theme fromPalette(ManaPalette palette) {
      return palette != null ? palette.toLegacyTheme() : defaults();
    }
  }

  public static Theme extractTheme(List<UiOps.Op> opList, Theme previous) {
    Theme base = previous != null ? previous : Theme.defaults();
    if (opList == null || opList.isEmpty()) return base;

    long screenBg = base.screenBg;
    long rowBg = base.rowBg;
    long rowActiveBg = base.rowActiveBg;
    long primaryText = base.primaryText;
    long headerText = base.headerText;
    long mutedText = base.mutedText;
    long dividerColor = base.dividerColor;
    long brandAccent = base.brandAccent;
    long switchOnBg = base.switchOnBg;
    long switchOnBorder = base.switchOnBorder;
    long switchOffBg = base.switchOffBg;
    long switchOffBorder = base.switchOffBorder;
    long switchThumbBg = base.switchThumbBg;
    long radioUncheckedBorder = base.radioUncheckedBorder;
    long segmentTrackBg = base.segmentTrackBg;
    long segmentThumbBg = base.segmentThumbBg;
    double headerHeight = base.headerHeight;
    double statusBarHeight = base.statusBarHeight;
    double bottomInset = base.bottomInset;

    boolean foundScreen = false;
    boolean foundHeader = false;
    boolean foundRow = false;
    boolean foundPrimary = false;
    boolean foundMuted = false;
    boolean foundDivider = false;
    boolean foundHeaderBar = false;
    boolean foundSwitchOn = false;
    boolean foundSwitchOff = false;

    for (int i = 0; i < opList.size(); i++) {
      UiOps.Op op = opList.get(i);
      if (op.opcode() != UiOps.OP_CREATE) continue;

      if (op.viewId() >= UiOps.SYNTHETIC_ID_BASE) continue;

      String type = op.viewType();
      if ("view".equals(type)) {
        if (!foundScreen && "settings-phone".equals(op.propString("accessibilityId"))) {
          screenBg = extractColorProp(op, "background", screenBg);
          foundScreen = true;
        }

        UiOps.Prop sh = op.findProp("stackHeader");
        if (!foundHeaderBar && sh != null && sh.value.asBoolean()) {
          UiOps.Prop hProp = op.findProp("height");
          if (hProp != null) {
            double h = hProp.value.asDouble();
            if (h >= 56.0 && h <= 160.0) headerHeight = h;
          }
          UiOps.Prop padProp = op.findProp("padding");
          if (padProp != null && padProp.value.asFloat4() != null) {
            float top = padProp.value.asFloat4()[1];
            if (top >= 0f && top <= 100f) statusBarHeight = top;
          }
          if (!foundScreen) {
            screenBg = extractColorProp(op, "background", screenBg);
          }
          foundHeaderBar = true;
        }

        if ("column".equals(op.propString("direction"))
            && op.findProp("gap") != null
            && Math.abs(op.findProp("gap").value.asDouble() - 24.0) < 0.5) {
          UiOps.Prop padProp = op.findProp("padding");
          if (padProp != null && padProp.value.asFloat4() != null) {
            float bottom = padProp.value.asFloat4()[3];
            if (bottom >= 12f && bottom <= 80f) bottomInset = bottom;
          }
        }

        if (!foundRow
            && ("button".equals(op.propString("role")) || "switch".equals(op.propString("role")))
            && op.findProp("background") != null
            && op.findProp("activeBackground") != null) {
          rowBg = extractColorProp(op, "background", rowBg);
          rowActiveBg = extractColorProp(op, "activeBackground", rowActiveBg);
          foundRow = true;
        }

        if (!foundDivider
            && op.findProp("height") != null
            && op.findProp("height").value.asDouble() == 1.0
            && op.findProp("background") != null) {
          dividerColor = extractColorProp(op, "background", dividerColor);
          segmentTrackBg = dividerColor;
          foundDivider = true;
        }

        if ("none".equals(op.propString("pressFeedback"))
            && op.findProp("width") != null
            && op.findProp("height") != null
            && Math.abs(op.findProp("width").value.asDouble() - 56.0) < 0.5
            && Math.abs(op.findProp("height").value.asDouble() - 28.0) < 0.5) {
          long bg = extractColorProp(op, "background", -1L);
          long bc = extractColorProp(op, "borderColor", -1L);
          if (bg >= 0L) {

            int alpha = (int) ((bg >>> 24) & 0xFFL);
            if (alpha >= 0xC0 && !foundSwitchOn) {
              switchOnBg = bg;
              brandAccent = bg;
              if (bc >= 0L) switchOnBorder = bc;
              foundSwitchOn = true;
            } else if (alpha < 0xC0 && !foundSwitchOff) {
              switchOffBg = bg;
              if (bc >= 0L) {
                switchOffBorder = bc;
                segmentThumbBg = bc;
              }
              foundSwitchOff = true;
            }
          }
        }

        if (op.findProp("width") != null
            && op.findProp("height") != null
            && Math.abs(op.findProp("width").value.asDouble() - 20.0) < 0.5
            && Math.abs(op.findProp("height").value.asDouble() - 20.0) < 0.5
            && op.findProp("translateX") != null) {
          switchThumbBg = extractColorProp(op, "background", switchThumbBg);
        }

        if (op.findProp("width") != null
            && op.findProp("height") != null
            && Math.abs(op.findProp("width").value.asDouble() - 24.0) < 0.5
            && Math.abs(op.findProp("height").value.asDouble() - 24.0) < 0.5
            && op.findProp("borderColor") != null
            && op.findProp("background") != null
            && extractColorProp(op, "background", 1L) == 0L) {
          radioUncheckedBorder = extractColorProp(op, "borderColor", radioUncheckedBorder);
        }
      } else if ("text".equals(type)) {
        if (!foundHeader && "header".equals(op.propString("role"))) {
          UiOps.Prop fs = op.findProp("fontSize");
          if (fs != null && Math.abs(fs.value.asDouble() - 16.0) < 0.5) {
            headerText = extractColorProp(op, "color", headerText);
            foundHeader = true;
          }
        } else if (!foundPrimary && op.findProp("includeFontPadding") != null) {
          UiOps.Prop fs = op.findProp("fontSize");
          if (fs != null && Math.abs(fs.value.asDouble() - 16.0) < 0.5) {
            primaryText = extractColorProp(op, "color", primaryText);
            foundPrimary = true;
          }
        } else if (!foundMuted && "right".equals(op.propString("textAlign"))) {
          mutedText = extractColorProp(op, "color", mutedText);
          foundMuted = true;
        }
      } else if ("image".equals(type)) {
        if (!foundHeader && ManaIcons.CHEVRON_SMALL_RIGHT.equals(op.propString("uri"))) {
          headerText = extractColorProp(op, "tint", headerText);
          foundHeader = true;
        }
      }
    }

    if (!foundMuted) {
      mutedText = headerText;
    }

    return new Theme(
        screenBg,
        rowBg,
        rowActiveBg,
        primaryText,
        headerText,
        mutedText,
        dividerColor,
        brandAccent,
        base.dangerText,
        base.dangerBg,
        switchOnBg,
        switchOnBorder,
        switchOffBg,
        switchOffBorder,
        switchThumbBg,
        radioUncheckedBorder,
        segmentTrackBg,
        segmentThumbBg,
        base.buttonBorder,
        base.buttonText,
        switchOffBorder,
        headerHeight,
        statusBarHeight,
        bottomInset);
  }

  public static final class PageScaffold {
    public final int rootId;
    public final int contentColumnId;
    public final int backButtonId;
    public final int titleTextId;
    public final int headerBarId;
    public final int screenBoxId;
    public final int topSpacerId;
    public final int topDividerId;
    public final int scrollId;

    PageScaffold(
        int rootId,
        int screenBoxId,
        int topSpacerId,
        int topDividerId,
        int scrollId,
        int contentColumnId,
        int headerBarId,
        int backButtonId,
        int titleTextId) {
      this.rootId = rootId;
      this.screenBoxId = screenBoxId;
      this.topSpacerId = topSpacerId;
      this.topDividerId = topDividerId;
      this.scrollId = scrollId;
      this.contentColumnId = contentColumnId;
      this.headerBarId = headerBarId;
      this.backButtonId = backButtonId;
      this.titleTextId = titleTextId;
    }

    public int[] baseScreenBoxChildren() {
      return new int[] {topSpacerId, topDividerId, scrollId, headerBarId};
    }

    public void finish(UiOps batch, int... sectionIds) {
      batch.setChildren(contentColumnId, sectionIds);
      batch.setChildren(scrollId, contentColumnId);
      batch.setChildren(screenBoxId, topSpacerId, topDividerId, scrollId, headerBarId);
    }
  }

  public static final class NavRowHandle {
    public final int rowId;
    public final int trailingTextId;
    public final int badgeTextId;

    public NavRowHandle(int rowId, int trailingTextId, int badgeTextId) {
      this.rowId = rowId;
      this.trailingTextId = trailingTextId;
      this.badgeTextId = badgeTextId;
    }
  }

  public static final class ValueRowHandle {
    public final int rowId;
    public final int valueTextId;

    public ValueRowHandle(int rowId, int valueTextId) {
      this.rowId = rowId;
      this.valueTextId = valueTextId;
    }
  }

  public static final class SwitchHandle {
    public final int wrapperId;
    public final int trackId;
    public final int thumbId;

    public final int motionSlot;

    public SwitchHandle(int wrapperId, int trackId, int thumbId) {
      this(wrapperId, trackId, thumbId, -1);
    }

    public SwitchHandle(int wrapperId, int trackId, int thumbId, int motionSlot) {
      this.wrapperId = wrapperId;
      this.trackId = trackId;
      this.thumbId = thumbId;
      this.motionSlot = motionSlot;
    }
  }

  public static final class ToggleRowHandle {
    public final int rowId;
    public final SwitchHandle toggle;

    public ToggleRowHandle(int rowId, SwitchHandle toggle) {
      this.rowId = rowId;
      this.toggle = toggle;
    }
  }

  public static final class RadioRowHandle {
    public final int rowId;
    public final int outerCircleId;
    public final int innerDotWrapId;
    public final int innerDotId;

    public final int motionSlot;

    public RadioRowHandle(int rowId, int outerCircleId, int innerDotWrapId, int innerDotId) {
      this(rowId, outerCircleId, innerDotWrapId, innerDotId, -1);
    }

    public RadioRowHandle(
        int rowId, int outerCircleId, int innerDotWrapId, int innerDotId, int motionSlot) {
      this.rowId = rowId;
      this.outerCircleId = outerCircleId;
      this.innerDotWrapId = innerDotWrapId;
      this.innerDotId = innerDotId;
      this.motionSlot = motionSlot;
    }
  }

  public static final class SegmentedHandle {
    public final int rootId;
    public final int[] tabIds;
    public final int[] labelIds;

    public final int trackId;

    public final int pillId;

    public final int motionSlot;

    public SegmentedHandle(int rootId, int[] tabIds, int[] labelIds) {
      this(rootId, tabIds, labelIds, -1, -1, -1);
    }

    public SegmentedHandle(
        int rootId, int[] tabIds, int[] labelIds, int trackId, int pillId, int motionSlot) {
      this.rootId = rootId;
      this.tabIds = tabIds;
      this.labelIds = labelIds;
      this.trackId = trackId;
      this.pillId = pillId;
      this.motionSlot = motionSlot;
    }
  }

  public static final class SearchInputHandle {
    public final int rootId;
    public final int inputId;

    public final int focusMotionSlot;

    public SearchInputHandle(int rootId, int inputId) {
      this(rootId, inputId, -1);
    }

    public SearchInputHandle(int rootId, int inputId, int focusMotionSlot) {
      this.rootId = rootId;
      this.inputId = inputId;
      this.focusMotionSlot = focusMotionSlot;
    }
  }

  public static final class BottomSheetHandle {
    public final int sheetId;
    public final int scrollId;
    public final int bodyColumnId;

    public BottomSheetHandle(int sheetId, int scrollId, int bodyColumnId) {
      this.sheetId = sheetId;
      this.scrollId = scrollId;
      this.bodyColumnId = bodyColumnId;
    }
  }

  public static void appendNavigatorPush(
      UiOps batch, int navigatorId, int[] baseNavChildren, int pageRootId) {
    int[] nextChildren = Arrays.copyOf(baseNavChildren, baseNavChildren.length + 1);
    nextChildren[baseNavChildren.length] = pageRootId;
    batch.updateView(
        navigatorId,
        Collections.singletonList(prop("index", UiOps.Value.ofInt(baseNavChildren.length))));
    batch.setChildren(navigatorId, nextChildren);
  }

  public static UiOps buildNavigatorPop(
      int navigatorId, int[] baseNavChildren, int targetIndex, int[] pageViewIds) {
    UiOps batch = new UiOps();
    batch.updateView(
        navigatorId, Collections.singletonList(prop("index", UiOps.Value.ofInt(targetIndex))));
    if (pageViewIds != null && pageViewIds.length > 0) {
      batch.deleteViews(pageViewIds);
    }
    batch.setChildren(navigatorId, baseNavChildren);
    return batch;
  }

  public static PageScaffold pageScaffold(
      UiOps batch, IdAllocator ids, Theme theme, String title, String titleAccessibilityId) {
    return pageScaffold(batch, ids, theme, title, null, titleAccessibilityId, null);
  }

  public static PageScaffold pageScaffold(
      UiOps batch,
      IdAllocator ids,
      Theme theme,
      String title,
      String subtitle,
      String titleAccessibilityId,
      int[] headerActionIds) {
    int rootId = ids.nextId();
    int outerId = ids.nextId();
    int innerId = ids.nextId();
    int screenBoxId = ids.nextId();
    int topSpacerId = ids.nextId();
    int topDividerId = ids.nextId();
    int scrollId = ids.nextId();
    int contentColumnId = ids.nextId();

    batch.createView(
        rootId,
        "view",
        props(
            prop("fullScreenBack", UiOps.Value.ofBool(true)),
            prop("direction", UiOps.Value.ofString("column")),
            prop("width", UiOps.Value.ofString("fill")),
            prop("height", UiOps.Value.ofString("fill"))));

    batch.createView(
        outerId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("column")),
            prop("padding", UiOps.Value.ofFloat4(0f, 0f, 0f, 0f)),
            prop("width", UiOps.Value.ofString("fill")),
            prop("height", UiOps.Value.ofString("fill")),
            prop("background", colorVal(theme.screenBg)),
            prop("clip", UiOps.Value.ofBool(true)),
            prop("align", UiOps.Value.ofString("stretch"))));
    ThemeEngine.bindActive(outerId, "background", ColorToken.BG_BASE_PRIMARY);

    batch.createView(
        innerId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("column")),
            prop("width", UiOps.Value.ofString("fill")),
            prop("height", UiOps.Value.ofString("fill")),
            prop("align", UiOps.Value.ofString("stretch"))));

    batch.createView(
        screenBoxId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("column")),
            prop("width", UiOps.Value.ofString("fill")),
            prop("height", UiOps.Value.ofString("fill")),
            prop("align", UiOps.Value.ofString("start")),
            prop("background", colorVal(theme.screenBg))));
    ThemeEngine.bindActive(screenBoxId, "background", ColorToken.BG_BASE_PRIMARY);

    batch.createView(
        topSpacerId,
        "view",
        props(
            prop("width", UiOps.Value.ofString("fill")),
            prop("height", UiOps.Value.ofDouble(theme.headerHeight)),
            prop("align", UiOps.Value.ofString("stretch"))));

    batch.createView(
        topDividerId,
        "view",
        props(
            prop("width", UiOps.Value.ofString("fill")),
            prop("height", UiOps.Value.ofInt(1)),
            prop("align", UiOps.Value.ofString("stretch"))));

    batch.createView(
        scrollId,
        "scroll",
        props(
            prop("margin", UiOps.Value.ofFloat4(0f, 0f, 0f, 0f)),
            prop("width", UiOps.Value.ofString("fill")),
            prop("flex", UiOps.Value.ofDouble(1.0)),
            prop("keyboardShouldPersistTaps", UiOps.Value.ofString("never"))));

    batch.createView(
        contentColumnId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("column")),
            prop("padding", UiOps.Value.ofFloat4(16f, 24f, 16f, (float) theme.bottomInset)),
            prop("width", UiOps.Value.ofString("fill")),
            prop("gap", UiOps.Value.ofInt(24)),
            prop("align", UiOps.Value.ofString("start"))));

    int headerBarId = ids.nextId();
    int backSlotId = ids.nextId();
    int backWrapId = ids.nextId();
    int backBtnId = ids.nextId();
    int backStackId = ids.nextId();
    int backIconId = ids.nextId();
    int titleWrapId = ids.nextId();
    int titleTextId = ids.nextId();

    List<UiOps.NamedValue> shadow = new ArrayList<UiOps.NamedValue>(6);
    shadow.add(new UiOps.NamedValue("color", colorVal(0xFF000000L)));
    shadow.add(new UiOps.NamedValue("opacity", UiOps.Value.ofDouble(1.0)));
    shadow.add(new UiOps.NamedValue("radius", UiOps.Value.ofDouble(0.0)));
    shadow.add(new UiOps.NamedValue("offsetX", UiOps.Value.ofDouble(0.0)));
    shadow.add(new UiOps.NamedValue("offsetY", UiOps.Value.ofDouble(0.0)));
    shadow.add(new UiOps.NamedValue("elevation", UiOps.Value.ofDouble(4.0)));

    batch.createView(
        headerBarId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("row")),
            prop("padding", UiOps.Value.ofFloat4(6f, (float) theme.statusBarHeight, 6f, 0f)),
            prop("absolute", UiOps.Value.ofBool(true)),
            prop("paddingBox", UiOps.Value.ofBool(true)),
            prop("gravity", UiOps.Value.ofString("top|start")),
            prop("width", UiOps.Value.ofString("fill")),
            prop("height", UiOps.Value.ofDouble(theme.headerHeight)),
            prop("gap", UiOps.Value.ofInt(6)),
            prop("align", UiOps.Value.ofString("center")),
            prop("background", colorVal(theme.screenBg)),
            prop("shadow", UiOps.Value.ofStringMap(shadow)),
            prop("stackHeader", UiOps.Value.ofBool(true))));
    ThemeEngine.bindActive(headerBarId, "background", ColorToken.HEADER_BG);

    batch.createView(
        backSlotId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("row")),
            prop("gravity", UiOps.Value.ofString("start|top")),
            prop("margin", UiOps.Value.ofFloat4(0f, 3.8095236f, 0f, 0f)),
            prop("align", UiOps.Value.ofString("stretch"))));

    batch.createView(
        backWrapId,
        "view",
        props(
            prop("margin", UiOps.Value.ofFloat4(10f, 0f, -6f, 0f)),
            prop("align", UiOps.Value.ofString("stretch"))));

    batch.createView(
        backBtnId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("column")),
            prop("padding", UiOps.Value.ofFloat4(0f, 8f, 0f, 0f)),
            prop("margin", UiOps.Value.ofFloat4(0f, -8f, 0f, 0f)),
            prop("width", UiOps.Value.ofInt(32)),
            prop("align", UiOps.Value.ofString("stretch")),
            prop("role", UiOps.Value.ofString("button")),
            prop("accessibilityLabel", UiOps.Value.ofString("Back")),
            prop("accessibilityId", UiOps.Value.ofString("Back")),
            prop("accessible", UiOps.Value.ofBool(true)),
            prop("hitSlop", UiOps.Value.ofInt(8)),
            prop("on", stringArray("press"))));

    batch.createView(
        backStackId,
        "stack",
        props(
            prop("width", UiOps.Value.ofInt(40)),
            prop("height", UiOps.Value.ofInt(40)),
            prop("accessibilityHidden", UiOps.Value.ofBool(true))));

    batch.createView(
        backIconId,
        "image",
        props(
            prop("uri", UiOps.Value.ofString(ManaIcons.ARROW_LARGE_LEFT)),
            prop("absolute", UiOps.Value.ofBool(true)),
            prop("paddingBox", UiOps.Value.ofBool(true)),
            prop("gravity", UiOps.Value.ofString("top|start")),
            prop("margin", UiOps.Value.ofFloat4(0f, 8f, 0f, 0f)),
            prop("width", UiOps.Value.ofInt(24)),
            prop("height", UiOps.Value.ofInt(24)),
            prop("tint", colorVal(theme.headerText)),
            prop("scale", UiOps.Value.ofString("fit"))));
    ThemeEngine.bindActive(backIconId, "tint", ColorToken.ICON_MUTED);

    batch.setChildren(backStackId, backIconId);
    batch.setChildren(backBtnId, backStackId);
    batch.setChildren(backWrapId, backBtnId);
    batch.setChildren(backSlotId, backWrapId);

    boolean hasSubtitle = subtitle != null && subtitle.length() > 0;
    batch.createView(
        titleWrapId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("column")),
            prop("gravity", UiOps.Value.ofString("start|top")),
            prop("margin", UiOps.Value.ofFloat4(0f, hasSubtitle ? 7f : 15.238094f, 10f, 0f)),
            prop("flex", UiOps.Value.ofDouble(1.0)),
            prop("align", UiOps.Value.ofString("stretch"))));

    List<UiOps.Prop> titleProps =
        props(
            prop("text", UiOps.Value.ofString(title)),
            prop("color", colorVal(theme.primaryText)),
            prop("fontSize", UiOps.Value.ofInt(18)),
            prop("fontWeight", UiOps.Value.ofInt(600)),
            prop("lineHeight", UiOps.Value.ofInt(22)),
            prop("lineHeightExact", UiOps.Value.ofBool(true)),
            prop("maxLines", UiOps.Value.ofInt(1)),
            prop("padding", UiOps.Value.ofFloat4(8f, 0f, 8f, 0f)),
            prop("role", UiOps.Value.ofString("header")));
    if (titleAccessibilityId != null && titleAccessibilityId.length() > 0) {
      addProp(titleProps, "accessibilityId", UiOps.Value.ofString(titleAccessibilityId));
    }
    batch.createView(titleTextId, "text", titleProps);
    ThemeEngine.bindActive(titleTextId, "color", ColorToken.TEXT_PRIMARY);

    if (hasSubtitle) {
      int subTextId = ids.nextId();
      batch.createView(
          subTextId,
          "text",
          props(
              prop("text", UiOps.Value.ofString(subtitle)),
              prop("color", colorVal(theme.mutedText)),
              prop("fontSize", UiOps.Value.ofInt(12)),
              prop("fontWeight", UiOps.Value.ofInt(500)),
              prop("lineHeight", UiOps.Value.ofInt(16)),
              prop("lineHeightExact", UiOps.Value.ofBool(true)),
              prop("maxLines", UiOps.Value.ofInt(1)),
              prop("padding", UiOps.Value.ofFloat4(8f, 0f, 8f, 0f))));
      ThemeEngine.bindActive(subTextId, "color", ColorToken.TEXT_MUTED);
      batch.setChildren(titleWrapId, titleTextId, subTextId);
    } else {
      batch.setChildren(titleWrapId, titleTextId);
    }

    if (headerActionIds != null && headerActionIds.length > 0) {
      int actionsSlotId = ids.nextId();
      batch.createView(
          actionsSlotId,
          "view",
          props(
              prop("direction", UiOps.Value.ofString("row")),
              prop("gap", UiOps.Value.ofInt(4)),
              prop("padding", UiOps.Value.ofFloat4(0f, 0f, 6f, 0f)),
              prop("align", UiOps.Value.ofString("center"))));
      batch.setChildren(actionsSlotId, headerActionIds);
      batch.setChildren(headerBarId, backSlotId, titleWrapId, actionsSlotId);
    } else {
      batch.setChildren(headerBarId, backSlotId, titleWrapId);
    }
    batch.setChildren(innerId, screenBoxId);
    batch.setChildren(outerId, innerId);
    batch.setChildren(rootId, outerId);

    return new PageScaffold(
        rootId,
        screenBoxId,
        topSpacerId,
        topDividerId,
        scrollId,
        contentColumnId,
        headerBarId,
        backBtnId,
        titleTextId);
  }

  public static int section(
      UiOps batch,
      IdAllocator ids,
      Theme theme,
      String headerTitle,
      int[] rowIds,
      String footerText) {
    return section(batch, ids, theme, headerTitle, rowIds, footerText, false);
  }

  public static int section(
      UiOps batch,
      IdAllocator ids,
      Theme theme,
      String headerTitle,
      int[] rowIds,
      String footerText,
      boolean isRadioGroup) {
    int outerId = ids.nextId();
    int groupId = ids.nextId();
    int cardId = ids.nextId();

    batch.createView(
        outerId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("column")),
            prop("width", UiOps.Value.ofString("fill")),
            prop("align", UiOps.Value.ofString("start"))));

    batch.createView(
        groupId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("column")),
            prop("width", UiOps.Value.ofString("fill")),
            prop("align", UiOps.Value.ofString("stretch"))));

    int headerId = -1;
    if (headerTitle != null && headerTitle.length() > 0) {
      headerId = ids.nextId();
      batch.createView(
          headerId,
          "text",
          props(
              prop("text", UiOps.Value.ofString(headerTitle)),
              prop("color", colorVal(theme.headerText)),
              prop("fontSize", UiOps.Value.ofInt(16)),
              prop("fontWeight", UiOps.Value.ofInt(500)),
              prop("lineHeight", UiOps.Value.ofInt(20)),
              prop("lineHeightExact", UiOps.Value.ofBool(true)),
              prop("margin", UiOps.Value.ofFloat4(0f, 0f, 0f, 8f)),
              prop("role", UiOps.Value.ofString("header"))));
      ThemeEngine.bindActive(headerId, "color", ColorToken.TEXT_SECONDARY);
    }

    List<UiOps.Prop> cardProps =
        props(
            prop("direction", UiOps.Value.ofString("column")),
            prop("width", UiOps.Value.ofString("fill")),
            prop("align", UiOps.Value.ofString("start")),
            prop("radius", UiOps.Value.ofInt(12)),
            prop("clip", UiOps.Value.ofBool(true)));
    if (isRadioGroup) {
      addProp(cardProps, "role", UiOps.Value.ofString("radiogroup"));
    }
    batch.createView(cardId, "view", cardProps);

    int n = rowIds != null ? rowIds.length : 0;
    if (n > 0) {
      int[] cardKids = new int[n * 2 - 1];
      int k = 0;
      for (int i = 0; i < n; i++) {
        if (i > 0) {
          cardKids[k++] = hairlineDivider(batch, ids, theme, 12f);
        }
        cardKids[k++] = rowIds[i];
      }
      batch.setChildren(cardId, cardKids);
    }

    if (headerId >= 0) {
      batch.setChildren(groupId, headerId, cardId);
    } else {
      batch.setChildren(groupId, cardId);
    }

    if (footerText != null && footerText.length() > 0) {
      int footerWrapId = ids.nextId();
      int footerTxtId = ids.nextId();
      batch.createView(
          footerWrapId,
          "view",
          props(
              prop("margin", UiOps.Value.ofFloat4(0f, 8f, 0f, 0f)),
              prop("width", UiOps.Value.ofString("fill")),
              prop("align", UiOps.Value.ofString("stretch"))));
      batch.createView(
          footerTxtId,
          "text",
          props(
              prop("text", UiOps.Value.ofString(footerText)),
              prop("color", colorVal(theme.mutedText)),
              prop("fontSize", UiOps.Value.ofInt(12)),
              prop("fontWeight", UiOps.Value.ofInt(400)),
              prop("lineHeight", UiOps.Value.ofInt(16)),
              prop("lineHeightExact", UiOps.Value.ofBool(true)),
              prop("width", UiOps.Value.ofString("fill"))));
      ThemeEngine.bindActive(footerTxtId, "color", ColorToken.TEXT_MUTED);
      batch.setChildren(footerWrapId, footerTxtId);
      batch.setChildren(outerId, groupId, footerWrapId);
    } else {
      batch.setChildren(outerId, groupId);
    }

    return outerId;
  }

  public static int hairlineDivider(UiOps batch, IdAllocator ids, Theme theme, float leftInset) {
    int wrapId = ids.nextId();
    int lineId = ids.nextId();
    batch.createView(
        wrapId,
        "view",
        props(
            prop("padding", UiOps.Value.ofFloat4(leftInset, 0f, 0f, 0f)),
            prop("margin", UiOps.Value.ofFloat4(0f, -1f, 0f, 0f)),
            prop("width", UiOps.Value.ofString("fill")),
            prop("height", UiOps.Value.ofInt(1)),
            prop("align", UiOps.Value.ofString("stretch"))));
    batch.createView(
        lineId,
        "view",
        props(
            prop("width", UiOps.Value.ofString("fill")),
            prop("height", UiOps.Value.ofInt(1)),
            prop("background", colorVal(theme.dividerColor)),
            prop("align", UiOps.Value.ofString("stretch"))));
    ThemeEngine.bindActive(lineId, "background", ColorToken.DIVIDER);
    batch.setChildren(wrapId, lineId);
    return wrapId;
  }

  public static int navRow(
      UiOps batch,
      IdAllocator ids,
      Theme theme,
      String iconUri,
      String title,
      String subtitle,
      String trailingValue,
      String accessibilityId,
      RowPosition pos) {
    int rowId = ids.nextId();
    int contentId = ids.nextId();

    List<UiOps.Prop> rowProps = baseRowProps(theme, pos, true);
    addProp(rowProps, "role", UiOps.Value.ofString("button"));
    addProp(
        rowProps,
        "accessibilityLabel",
        UiOps.Value.ofString(
            subtitle != null && subtitle.length() > 0 ? (title + ", " + subtitle) : title));
    if (accessibilityId != null && accessibilityId.length() > 0) {
      addProp(rowProps, "accessibilityId", UiOps.Value.ofString(accessibilityId));
    }
    addProp(rowProps, "on", stringArray("press"));
    batch.createView(rowId, "view", rowProps);
    bindRowTheme(rowId, true);

    batch.createView(
        contentId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("row")),
            prop("padding", UiOps.Value.ofFloat4(12f, 12f, 12f, 12f)),
            prop("width", UiOps.Value.ofString("fill")),
            prop("minHeight", UiOps.Value.ofInt(56)),
            prop("align", UiOps.Value.ofString("center"))));

    List<Integer> kids = new ArrayList<Integer>(4);
    if (iconUri != null && iconUri.length() > 0) {
      kids.add(
          Integer.valueOf(
              leadingIcon(batch, ids, iconUri, theme.primaryText, ColorToken.ICON_PRIMARY)));
    }
    kids.add(
        Integer.valueOf(
            labelBlock(
                batch, ids, theme, title, subtitle, theme.primaryText, ColorToken.TEXT_PRIMARY)));
    if (trailingValue != null && trailingValue.length() > 0) {
      kids.add(Integer.valueOf(trailingValueView(batch, ids, theme, trailingValue, 14, 18, null)));
    }
    kids.add(Integer.valueOf(chevronView(batch, ids, theme)));

    batch.setChildren(contentId, toIntArray(kids));
    batch.setChildren(rowId, contentId);
    return rowId;
  }

  public static NavRowHandle navRowHandle(
      UiOps batch,
      IdAllocator ids,
      Theme theme,
      String iconUri,
      String title,
      String subtitle,
      String trailingValue,
      boolean forceTrailingView,
      String badgeText,
      long badgeBg,
      ColorToken badgeBgToken,
      long badgeFg,
      ColorToken badgeFgToken,
      String accessibilityId,
      RowPosition pos) {
    int rowId = ids.nextId();
    int contentId = ids.nextId();

    List<UiOps.Prop> rowProps = baseRowProps(theme, pos, true);
    addProp(rowProps, "role", UiOps.Value.ofString("button"));
    addProp(
        rowProps,
        "accessibilityLabel",
        UiOps.Value.ofString(
            subtitle != null && subtitle.length() > 0 ? (title + ", " + subtitle) : title));
    if (accessibilityId != null && accessibilityId.length() > 0) {
      addProp(rowProps, "accessibilityId", UiOps.Value.ofString(accessibilityId));
    }
    addProp(rowProps, "on", stringArray("press"));
    batch.createView(rowId, "view", rowProps);
    bindRowTheme(rowId, true);

    batch.createView(
        contentId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("row")),
            prop("padding", UiOps.Value.ofFloat4(12f, 12f, 12f, 12f)),
            prop("width", UiOps.Value.ofString("fill")),
            prop("minHeight", UiOps.Value.ofInt(56)),
            prop("align", UiOps.Value.ofString("center"))));

    List<Integer> kids = new ArrayList<Integer>(5);
    if (iconUri != null && iconUri.length() > 0) {
      kids.add(
          Integer.valueOf(
              leadingIcon(batch, ids, iconUri, theme.primaryText, ColorToken.ICON_PRIMARY)));
    }
    kids.add(
        Integer.valueOf(
            labelBlock(
                batch, ids, theme, title, subtitle, theme.primaryText, ColorToken.TEXT_PRIMARY)));

    int badgeTextId = -1;
    if (badgeText != null) {
      int pillWrapId = ids.nextId();
      int pillId = ids.nextId();
      badgeTextId = ids.nextId();
      batch.createView(
          pillWrapId,
          "view",
          props(
              prop("margin", UiOps.Value.ofFloat4(8f, 0f, 0f, 0f)),
              prop("align", UiOps.Value.ofString("center"))));
      batch.createView(
          pillId,
          "view",
          props(
              prop("direction", UiOps.Value.ofString("row")),
              prop("padding", UiOps.Value.ofFloat4(6f, 0f, 6f, 0f)),
              prop("height", UiOps.Value.ofInt(18)),
              prop("minWidth", UiOps.Value.ofInt(18)),
              prop("align", UiOps.Value.ofString("center")),
              prop("justify", UiOps.Value.ofString("center")),
              prop("background", colorVal(badgeBg)),
              prop("radius", UiOps.Value.ofInt(9999))));
      if (badgeBgToken != null) {
        ThemeEngine.bindActive(pillId, "background", badgeBgToken);
      }
      batch.createView(
          badgeTextId,
          "text",
          props(
              prop("text", UiOps.Value.ofString(badgeText)),
              prop("color", colorVal(badgeFg)),
              prop("fontSize", UiOps.Value.ofInt(12)),
              prop("fontWeight", UiOps.Value.ofInt(600)),
              prop("lineHeight", UiOps.Value.ofInt(16)),
              prop("lineHeightExact", UiOps.Value.ofBool(true)),
              prop("textAlign", UiOps.Value.ofString("center"))));
      if (badgeFgToken != null) {
        ThemeEngine.bindActive(badgeTextId, "color", badgeFgToken);
      }
      batch.setChildren(pillId, badgeTextId);
      batch.setChildren(pillWrapId, pillId);
      kids.add(Integer.valueOf(pillWrapId));
    }

    int trailingTextId = -1;
    if (forceTrailingView || (trailingValue != null && trailingValue.length() > 0)) {
      int[] outTxt = new int[] {-1};
      kids.add(
          Integer.valueOf(
              trailingValueView(
                  batch, ids, theme, trailingValue != null ? trailingValue : "", 14, 18, outTxt)));
      trailingTextId = outTxt[0];
    }
    kids.add(Integer.valueOf(chevronView(batch, ids, theme)));

    batch.setChildren(contentId, toIntArray(kids));
    batch.setChildren(rowId, contentId);
    return new NavRowHandle(rowId, trailingTextId, badgeTextId);
  }

  public static int valueRow(
      UiOps batch,
      IdAllocator ids,
      Theme theme,
      String iconUri,
      String title,
      String subtitle,
      String valueText,
      RowPosition pos) {
    return valueRowHandle(batch, ids, theme, iconUri, title, subtitle, valueText, false, false, pos)
        .rowId;
  }

  public static ValueRowHandle valueRowHandle(
      UiOps batch,
      IdAllocator ids,
      Theme theme,
      String iconUri,
      String title,
      String subtitle,
      String valueText,
      boolean forceValueView,
      boolean clickable,
      RowPosition pos) {
    int rowId = ids.nextId();
    int contentId = ids.nextId();

    List<UiOps.Prop> rowProps = baseRowProps(theme, pos, clickable);
    if (clickable) {
      addProp(rowProps, "role", UiOps.Value.ofString("button"));
      addProp(rowProps, "on", stringArray("press"));
    }
    addProp(
        rowProps,
        "accessibilityLabel",
        UiOps.Value.ofString(valueText != null ? (title + ", " + valueText) : title));
    batch.createView(rowId, "view", rowProps);
    bindRowTheme(rowId, clickable);

    batch.createView(
        contentId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("row")),
            prop("padding", UiOps.Value.ofFloat4(12f, 12f, 12f, 12f)),
            prop("width", UiOps.Value.ofString("fill")),
            prop("minHeight", UiOps.Value.ofInt(56)),
            prop("align", UiOps.Value.ofString("center"))));

    List<Integer> kids = new ArrayList<Integer>(3);
    if (iconUri != null && iconUri.length() > 0) {
      kids.add(
          Integer.valueOf(
              leadingIcon(batch, ids, iconUri, theme.headerText, ColorToken.ICON_MUTED)));
    }
    kids.add(
        Integer.valueOf(
            labelBlock(
                batch, ids, theme, title, subtitle, theme.primaryText, ColorToken.TEXT_PRIMARY)));
    int valueTextId = -1;
    if (forceValueView || (valueText != null && valueText.length() > 0)) {
      int[] outTxt = new int[] {-1};
      kids.add(
          Integer.valueOf(
              trailingValueView(
                  batch, ids, theme, valueText != null ? valueText : "", 14, 18, outTxt)));
      valueTextId = outTxt[0];
    }
    batch.setChildren(contentId, toIntArray(kids));
    batch.setChildren(rowId, contentId);
    return new ValueRowHandle(rowId, valueTextId);
  }

  public static int actionRow(
      UiOps batch,
      IdAllocator ids,
      Theme theme,
      String iconUri,
      String title,
      String subtitle,
      boolean destructive,
      String accessibilityId,
      RowPosition pos) {
    int rowId = ids.nextId();
    int contentId = ids.nextId();

    long textColor = destructive ? theme.dangerText : theme.primaryText;
    ColorToken textToken = destructive ? ColorToken.DANGER_TEXT : ColorToken.TEXT_PRIMARY;
    ColorToken iconToken = destructive ? ColorToken.DANGER_TEXT : ColorToken.ICON_PRIMARY;

    List<UiOps.Prop> rowProps = baseRowProps(theme, pos, true);
    addProp(rowProps, "role", UiOps.Value.ofString("button"));
    addProp(
        rowProps,
        "accessibilityLabel",
        UiOps.Value.ofString(
            subtitle != null && subtitle.length() > 0 ? (title + ", " + subtitle) : title));
    if (accessibilityId != null && accessibilityId.length() > 0) {
      addProp(rowProps, "accessibilityId", UiOps.Value.ofString(accessibilityId));
    }
    addProp(rowProps, "on", stringArray("press"));
    batch.createView(rowId, "view", rowProps);
    bindRowTheme(rowId, true);

    batch.createView(
        contentId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("row")),
            prop("padding", UiOps.Value.ofFloat4(12f, 12f, 12f, 12f)),
            prop("width", UiOps.Value.ofString("fill")),
            prop("minHeight", UiOps.Value.ofInt(56)),
            prop("align", UiOps.Value.ofString("center"))));

    List<Integer> kids = new ArrayList<Integer>(3);
    if (iconUri != null && iconUri.length() > 0) {
      kids.add(Integer.valueOf(leadingIcon(batch, ids, iconUri, textColor, iconToken)));
    }
    kids.add(Integer.valueOf(labelBlock(batch, ids, theme, title, subtitle, textColor, textToken)));
    batch.setChildren(contentId, toIntArray(kids));
    batch.setChildren(rowId, contentId);
    return rowId;
  }

  public static SwitchHandle manaSwitch(
      UiOps batch, IdAllocator ids, Theme theme, boolean checked) {
    int wrapperId = ids.nextId();
    int trackId = ids.nextId();
    int thumbId = ids.nextId();

    batch.createView(
        wrapperId,
        "view",
        props(
            prop("margin", UiOps.Value.ofFloat4(18f, 0f, 0f, 0f)),
            prop("align", UiOps.Value.ofString("stretch"))));

    long trackBg = checked ? theme.switchOnBg : theme.switchOffBg;
    long trackBorder = checked ? theme.switchOnBorder : theme.switchOffBorder;

    String scope = ThemeEngine.activeScopeId();
    int motionSlot = -1;
    if (scope != null && ManaMotion.isWorkletSupported()) {
      motionSlot = ManaMotion.allocSlot(scope);
    }

    List<UiOps.Prop> trackProps =
        props(
            prop("direction", UiOps.Value.ofString("row")),
            prop("padding", UiOps.Value.ofFloat4(4f, 4f, 4f, 4f)),
            prop("width", UiOps.Value.ofInt(56)),
            prop("height", UiOps.Value.ofInt(28)),
            prop("background", colorVal(trackBg)),
            prop("radius", UiOps.Value.ofInt(16)),
            prop("borderColor", colorVal(trackBorder)),
            prop("borderWidth", UiOps.Value.ofInt(1)),
            prop("pressFeedback", UiOps.Value.ofString("none")),
            prop("align", UiOps.Value.ofString("stretch")),
            prop("accessibilityHidden", UiOps.Value.ofBool(true)),
            prop("on", stringArray("press")));
    if (motionSlot >= 0) {
      int[] trackWorklet =
          ManaMotion.switchTrackWorklet(
              motionSlot,
              theme.switchOffBg,
              theme.switchOnBg,
              theme.switchOffBorder,
              theme.switchOnBorder);
      addProp(trackProps, "worklet", ManaMotion.workletValue(trackWorklet));
      ManaMotion.bindSwitchThemeWorklet(scope, trackId, motionSlot);
    }
    batch.createView(trackId, "view", trackProps);
    ThemeEngine.bindActive(
        trackId, "background", checked ? ColorToken.SWITCH_ON_BG : ColorToken.SWITCH_OFF_BG);
    ThemeEngine.bindActive(
        trackId,
        "borderColor",
        checked ? ColorToken.SWITCH_ON_BORDER : ColorToken.SWITCH_OFF_BORDER);

    List<UiOps.Prop> thumbProps =
        props(
            prop("direction", UiOps.Value.ofString("row")),
            prop("width", UiOps.Value.ofInt(20)),
            prop("height", UiOps.Value.ofInt(20)),
            prop("align", UiOps.Value.ofString("center")),
            prop("justify", UiOps.Value.ofString("center")),
            prop("background", colorVal(theme.switchThumbBg)),
            prop("radius", UiOps.Value.ofInt(9999)),
            prop("translateX", UiOps.Value.ofInt(checked ? 28 : 0)));
    if (motionSlot >= 0) {
      int[] thumbWorklet = ManaMotion.switchThumbWorklet(motionSlot);
      addProp(thumbProps, "worklet", ManaMotion.workletValue(thumbWorklet));
    }
    batch.createView(thumbId, "view", thumbProps);
    ThemeEngine.bindActive(thumbId, "background", ColorToken.SWITCH_THUMB);

    batch.setChildren(trackId, thumbId);
    batch.setChildren(wrapperId, trackId);
    if (motionSlot >= 0) {
      batch.motion(ManaMotion.initValueWords(motionSlot, checked ? 1f : 0f));
    }
    return new SwitchHandle(wrapperId, trackId, thumbId, motionSlot);
  }

  public static ToggleRowHandle toggleRow(
      UiOps batch,
      IdAllocator ids,
      Theme theme,
      String iconUri,
      String title,
      String subtitle,
      boolean checked,
      String accessibilityId,
      RowPosition pos) {
    int rowId = ids.nextId();
    int contentId = ids.nextId();

    List<UiOps.Prop> rowProps = baseRowProps(theme, pos, true);
    addProp(rowProps, "role", UiOps.Value.ofString("switch"));
    addProp(
        rowProps,
        "accessibilityLabel",
        UiOps.Value.ofString(
            subtitle != null && subtitle.length() > 0
                ? (title + ", " + subtitle)
                : (title + ", ")));
    addProp(rowProps, "accessibilityChecked", UiOps.Value.ofBool(checked));
    if (accessibilityId != null && accessibilityId.length() > 0) {
      addProp(rowProps, "accessibilityId", UiOps.Value.ofString(accessibilityId));
    }
    addProp(rowProps, "on", stringArray("press"));
    batch.createView(rowId, "view", rowProps);
    bindRowTheme(rowId, true);

    batch.createView(
        contentId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("row")),
            prop("padding", UiOps.Value.ofFloat4(12f, 12f, 12f, 12f)),
            prop("width", UiOps.Value.ofString("fill")),
            prop("minHeight", UiOps.Value.ofInt(56)),
            prop("align", UiOps.Value.ofString("center"))));

    SwitchHandle toggle = manaSwitch(batch, ids, theme, checked);

    List<Integer> kids = new ArrayList<Integer>(3);
    if (iconUri != null && iconUri.length() > 0) {
      kids.add(
          Integer.valueOf(
              leadingIcon(batch, ids, iconUri, theme.primaryText, ColorToken.ICON_PRIMARY)));
    }
    kids.add(
        Integer.valueOf(
            labelBlock(
                batch, ids, theme, title, subtitle, theme.primaryText, ColorToken.TEXT_PRIMARY)));
    kids.add(Integer.valueOf(toggle.wrapperId));

    batch.setChildren(contentId, toIntArray(kids));
    batch.setChildren(rowId, contentId);
    return new ToggleRowHandle(rowId, toggle);
  }

  public static void updateToggleRow(
      UiOps batch, ToggleRowHandle handle, boolean checked, Theme theme) {
    batch.updateView(
        handle.rowId, props(prop("accessibilityChecked", UiOps.Value.ofBool(checked))));
    updateSwitch(batch, handle.toggle, checked, theme);
  }

  public static void updateSwitch(UiOps batch, SwitchHandle handle, boolean checked, Theme theme) {
    long trackBg = checked ? theme.switchOnBg : theme.switchOffBg;
    long trackBorder = checked ? theme.switchOnBorder : theme.switchOffBorder;
    batch.updateView(
        handle.trackId,
        props(prop("background", colorVal(trackBg)), prop("borderColor", colorVal(trackBorder))));
    batch.updateView(
        handle.thumbId, props(prop("translateX", UiOps.Value.ofInt(checked ? 28 : 0))));
  }

  public static RadioRowHandle radioRow(
      UiOps batch,
      IdAllocator ids,
      Theme theme,
      String title,
      String subtitle,
      boolean selected,
      String accessibilityId,
      RowPosition pos) {
    int rowId = ids.nextId();
    int contentId = ids.nextId();
    int radioWrapId = ids.nextId();
    int outerCircleId = ids.nextId();
    int innerDotWrapId = ids.nextId();
    int innerDotId = ids.nextId();

    List<UiOps.Prop> rowProps = baseRowProps(theme, pos, true);
    addProp(rowProps, "role", UiOps.Value.ofString("radio"));
    addProp(
        rowProps,
        "accessibilityLabel",
        UiOps.Value.ofString(
            subtitle != null && subtitle.length() > 0
                ? (title + ", " + subtitle)
                : (title + ", ")));
    addProp(rowProps, "accessibilitySelected", UiOps.Value.ofBool(selected));
    addProp(rowProps, "accessibilityChecked", UiOps.Value.ofBool(selected));
    if (accessibilityId != null && accessibilityId.length() > 0) {
      addProp(rowProps, "accessibilityId", UiOps.Value.ofString(accessibilityId));
    }
    addProp(rowProps, "on", stringArray("press"));
    batch.createView(rowId, "view", rowProps);
    bindRowTheme(rowId, true);

    batch.createView(
        contentId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("row")),
            prop("padding", UiOps.Value.ofFloat4(12f, 12f, 12f, 12f)),
            prop("width", UiOps.Value.ofString("fill")),
            prop("minHeight", UiOps.Value.ofInt(56)),
            prop("align", UiOps.Value.ofString("center"))));

    int labelId =
        labelBlock(batch, ids, theme, title, subtitle, theme.primaryText, ColorToken.TEXT_PRIMARY);

    batch.createView(
        radioWrapId,
        "view",
        props(
            prop("margin", UiOps.Value.ofFloat4(18f, 0f, 0f, 0f)),
            prop("align", UiOps.Value.ofString("stretch"))));

    String scope = ThemeEngine.activeScopeId();
    int motionSlot = -1;
    if (scope != null && ManaMotion.isWorkletSupported()) {
      motionSlot = ManaMotion.allocSlot(scope);
    }

    List<UiOps.Prop> outerProps =
        props(
            prop("direction", UiOps.Value.ofString("row")),
            prop("padding", UiOps.Value.ofFloat4(1.2f, 1.2f, 1.2f, 1.2f)),
            prop("width", UiOps.Value.ofInt(24)),
            prop("height", UiOps.Value.ofInt(24)),
            prop("align", UiOps.Value.ofString("center")),
            prop("justify", UiOps.Value.ofString("center")),
            prop("background", colorVal(selected ? theme.brandAccent : 0L)),
            prop("radius", UiOps.Value.ofInt(9999)),
            prop(
                "borderColor", colorVal(selected ? theme.brandAccent : theme.radioUncheckedBorder)),
            prop("borderWidth", UiOps.Value.ofDouble(1.2)));
    if (motionSlot >= 0) {
      int[] outerWorklet =
          ManaMotion.radioOuterWorklet(motionSlot, theme.radioUncheckedBorder, theme.brandAccent);
      addProp(outerProps, "worklet", ManaMotion.workletValue(outerWorklet));
      ManaMotion.bindRadioThemeWorklet(scope, outerCircleId, motionSlot);
    }
    batch.createView(outerCircleId, "view", outerProps);
    if (selected) {
      ThemeEngine.bindActive(outerCircleId, "background", ColorToken.BRAND_500);
      ThemeEngine.bindActive(outerCircleId, "borderColor", ColorToken.BRAND_500);
    } else {
      ThemeEngine.bindActive(outerCircleId, "borderColor", ColorToken.RADIO_UNCHECKED_BORDER);
    }

    List<UiOps.Prop> dotWrapProps =
        props(
            prop("direction", UiOps.Value.ofString("row")),
            prop("align", UiOps.Value.ofString("center")),
            prop("justify", UiOps.Value.ofString("center")),
            prop("opacity", UiOps.Value.ofDouble(selected ? 1.0 : 0.0)));
    if (motionSlot >= 0) {
      addProp(
          dotWrapProps,
          "worklet",
          ManaMotion.workletValue(ManaMotion.radioInnerWrapWorklet(motionSlot)));
    }
    batch.createView(innerDotWrapId, "view", dotWrapProps);

    List<UiOps.Prop> dotProps =
        props(
            prop("width", UiOps.Value.ofInt(8)),
            prop("height", UiOps.Value.ofInt(8)),
            prop("background", colorVal(theme.switchThumbBg)),
            prop("radius", UiOps.Value.ofInt(9999)),
            prop("scaleFactor", UiOps.Value.ofDouble(selected ? 1.0 : 0.5)),
            prop("align", UiOps.Value.ofString("stretch")));
    if (motionSlot >= 0) {
      addProp(
          dotProps,
          "worklet",
          ManaMotion.workletValue(ManaMotion.radioInnerDotWorklet(motionSlot)));
    }
    batch.createView(innerDotId, "view", dotProps);
    ThemeEngine.bindActive(innerDotId, "background", ColorToken.SWITCH_THUMB);

    batch.setChildren(innerDotWrapId, innerDotId);
    batch.setChildren(outerCircleId, innerDotWrapId);
    batch.setChildren(radioWrapId, outerCircleId);
    batch.setChildren(contentId, labelId, radioWrapId);
    batch.setChildren(rowId, contentId);
    if (motionSlot >= 0) {
      batch.motion(ManaMotion.initValueWords(motionSlot, selected ? 1f : 0f));
    }
    return new RadioRowHandle(rowId, outerCircleId, innerDotWrapId, innerDotId, motionSlot);
  }

  public static void updateRadioRow(
      UiOps batch, RadioRowHandle handle, boolean selected, Theme theme) {
    batch.updateView(
        handle.rowId,
        props(
            prop("accessibilitySelected", UiOps.Value.ofBool(selected)),
            prop("accessibilityChecked", UiOps.Value.ofBool(selected))));
    batch.updateView(
        handle.outerCircleId,
        props(
            prop("background", colorVal(selected ? theme.brandAccent : 0L)),
            prop(
                "borderColor",
                colorVal(selected ? theme.brandAccent : theme.radioUncheckedBorder))));
    batch.updateView(
        handle.innerDotWrapId, props(prop("opacity", UiOps.Value.ofDouble(selected ? 1.0 : 0.0))));
    batch.updateView(
        handle.innerDotId, props(prop("scaleFactor", UiOps.Value.ofDouble(selected ? 1.0 : 0.5))));
  }

  public static int button(
      UiOps batch,
      IdAllocator ids,
      Theme theme,
      String label,
      String iconUri,
      ButtonVariant variant,
      String accessibilityId) {
    return button(batch, ids, theme, label, iconUri, variant, true, accessibilityId);
  }

  public static int button(
      UiOps batch,
      IdAllocator ids,
      Theme theme,
      String label,
      String iconUri,
      ButtonVariant variant,
      boolean enabled,
      String accessibilityId) {
    return button(
        batch, ids, theme, label, iconUri, variant, ButtonSize.LG, enabled, accessibilityId);
  }

  public static int button(
      UiOps batch,
      IdAllocator ids,
      Theme theme,
      String label,
      String iconUri,
      ButtonVariant variant,
      ButtonSize size,
      boolean enabled,
      String accessibilityId) {
    int btnId = ids.nextId();
    int rowId = ids.nextId();

    ButtonVariant v = variant != null ? variant : ButtonVariant.PRIMARY;
    ButtonSize sz = size != null ? size : ButtonSize.LG;
    ManaPalette pal = ThemeEngine.currentPalette();

    float padH;
    float padV;
    int minWidth;
    int minHeight;
    int radius;
    int fontSize;
    int lineHeight;
    int iconSize;
    if (sz == ButtonSize.SM) {
      padH = 12f;
      padV = 6f;
      minWidth = 32;
      minHeight = 32;
      radius = 8;
      fontSize = 14;
      lineHeight = 18;
      iconSize = 16;
    } else if (sz == ButtonSize.MD) {
      padH = 16f;
      padV = 8f;
      minWidth = 40;
      minHeight = 40;
      radius = 10;
      fontSize = 14;
      lineHeight = 18;
      iconSize = 16;
    } else {

      padH = 24f;
      padV = 12f;
      minWidth = 48;
      minHeight = 48;
      radius = 12;
      fontSize = 16;
      lineHeight = 20;
      iconSize = 18;
    }

    long bg;
    long activeBg;
    long fg;
    long borderColor = theme.buttonBorder;
    int borderWidth = 1;
    ColorToken bgToken;
    ColorToken activeBgToken = null;
    ColorToken borderToken = ColorToken.BUTTON_BORDER;
    ColorToken fgToken;
    ColorToken iconToken;

    if (v == ButtonVariant.DANGER) {
      bg = theme.dangerBg;
      activeBg = 0xFFB82530L;
      fg = theme.buttonText;
      bgToken = ColorToken.DANGER;
      fgToken = ColorToken.BUTTON_PRIMARY_TEXT;
      iconToken = ColorToken.BUTTON_PRIMARY_TEXT;
    } else if (v == ButtonVariant.SECONDARY) {
      bg = theme.rowBg;
      activeBg = theme.rowActiveBg;
      activeBgToken = ColorToken.BG_ROW_ACTIVE;
      fg = theme.primaryText;
      bgToken = ColorToken.BG_BASE_SECONDARY;
      fgToken = ColorToken.TEXT_PRIMARY;
      iconToken = ColorToken.ICON_PRIMARY;
    } else if (v == ButtonVariant.GHOST) {
      bg = 0L;
      activeBg = theme.rowActiveBg;
      activeBgToken = ColorToken.BG_ROW_ACTIVE;
      fg = theme.primaryText;
      borderColor = theme.dividerColor;
      borderWidth = 1;
      bgToken = null;
      borderToken = ColorToken.DIVIDER;
      fgToken = ColorToken.TEXT_PRIMARY;
      iconToken = ColorToken.ICON_PRIMARY;
    } else if (v == ButtonVariant.LINK) {
      bg = 0L;
      activeBg = theme.rowActiveBg;
      activeBgToken = ColorToken.BG_ROW_ACTIVE;
      fg = pal.color(ColorToken.TEXT_LINK);
      borderColor = 0L;
      borderWidth = 0;
      padH = 8f;
      padV = sz == ButtonSize.SM ? 4f : (sz == ButtonSize.MD ? 6f : 8f);
      minHeight = sz == ButtonSize.SM ? 28 : (sz == ButtonSize.MD ? 32 : 36);
      bgToken = null;
      borderToken = null;
      fgToken = ColorToken.TEXT_LINK;
      iconToken = ColorToken.TEXT_LINK;
    } else {
      bg = theme.brandAccent;
      activeBg = 0xFF4752C4L;
      fg = theme.buttonText;
      bgToken = ColorToken.BUTTON_PRIMARY_BG;
      fgToken = ColorToken.BUTTON_PRIMARY_TEXT;
      iconToken = ColorToken.BUTTON_PRIMARY_TEXT;
    }

    List<UiOps.Prop> btnProps =
        props(
            prop("direction", UiOps.Value.ofString("row")),
            prop("padding", UiOps.Value.ofFloat4(padH, padV, padH, padV)),
            prop("width", UiOps.Value.ofString("fill")),
            prop("minWidth", UiOps.Value.ofInt(minWidth)),
            prop("minHeight", UiOps.Value.ofInt(minHeight)),
            prop("align", UiOps.Value.ofString("center")),
            prop("justify", UiOps.Value.ofString("center")),
            prop("background", colorVal(bg)),
            prop("activeBackground", colorVal(activeBg)),
            prop("radius", UiOps.Value.ofInt(radius)),
            prop("clip", UiOps.Value.ofBool(true)),
            prop("role", UiOps.Value.ofString("button")),
            prop("accessibilityLabel", UiOps.Value.ofString(label)),
            prop("on", stringArray("press")));
    if (borderWidth > 0) {
      addProp(btnProps, "borderColor", colorVal(borderColor));
      addProp(btnProps, "borderWidth", UiOps.Value.ofInt(borderWidth));
    }
    if (!enabled) {
      addProp(btnProps, "enabled", UiOps.Value.ofBool(false));
      addProp(btnProps, "opacity", UiOps.Value.ofDouble(0.5));
    }
    if (accessibilityId != null && accessibilityId.length() > 0) {
      addProp(btnProps, "accessibilityId", UiOps.Value.ofString(accessibilityId));
    }
    batch.createView(btnId, "view", btnProps);
    if (bgToken != null) {
      ThemeEngine.bindActive(btnId, "background", bgToken);
    }
    if (borderToken != null) {
      ThemeEngine.bindActive(btnId, "borderColor", borderToken);
    }
    if (activeBgToken != null) {
      ThemeEngine.bindActive(btnId, "activeBackground", activeBgToken);
    }

    batch.createView(
        rowId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("row")),
            prop("align", UiOps.Value.ofString("center")),
            prop("justify", UiOps.Value.ofString("center"))));

    List<Integer> kids = new ArrayList<Integer>(2);
    if (iconUri != null && iconUri.length() > 0) {
      int iconWrapId = ids.nextId();
      batch.createView(iconWrapId, "view", props(prop("align", UiOps.Value.ofString("stretch"))));
      int iconId = icon(batch, ids, iconUri, iconSize, iconSize, fg, iconToken, 0f, 0f, 0f, 0f);
      batch.setChildren(iconWrapId, iconId);
      kids.add(Integer.valueOf(iconWrapId));
    }

    int txtId = ids.nextId();
    batch.createView(
        txtId,
        "text",
        props(
            prop("text", UiOps.Value.ofString(label)),
            prop("color", colorVal(fg)),
            prop("fontSize", UiOps.Value.ofInt(fontSize)),
            prop("fontWeight", UiOps.Value.ofInt(600)),
            prop("lineHeight", UiOps.Value.ofInt(lineHeight)),
            prop("lineHeightExact", UiOps.Value.ofBool(true)),
            prop("maxLines", UiOps.Value.ofInt(1)),
            prop("padding", UiOps.Value.ofFloat4(iconUri != null ? 6f : 0f, 0f, 0f, 0f)),
            prop("shrink", UiOps.Value.ofBool(true))));
    ThemeEngine.bindActive(txtId, "color", fgToken);
    kids.add(Integer.valueOf(txtId));

    batch.setChildren(rowId, toIntArray(kids));
    batch.setChildren(btnId, rowId);
    return btnId;
  }

  public static SegmentedHandle segmentedControl(
      UiOps batch, IdAllocator ids, Theme theme, String[] labels, int selectedIndex) {
    int rootId = ids.nextId();
    int trackId = ids.nextId();
    int pillId = ids.nextId();
    int rowId = ids.nextId();
    int count = labels != null ? labels.length : 0;
    int[] tabIds = new int[count];
    int[] labelIds = new int[count];

    batch.createView(
        rootId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("column")),
            prop("padding", UiOps.Value.ofFloat4(3f, 3f, 3f, 3f)),
            prop("width", UiOps.Value.ofString("fill")),
            prop("background", colorVal(theme.segmentTrackBg)),
            prop("radius", UiOps.Value.ofInt(10)),
            prop("clip", UiOps.Value.ofBool(true)),
            prop("align", UiOps.Value.ofString("stretch"))));
    ThemeEngine.bindActive(rootId, "background", ColorToken.SEGMENT_TRACK_BG);

    batch.createView(
        trackId,
        "stack",
        props(prop("width", UiOps.Value.ofString("fill")), prop("on", stringArray("layout"))));

    String scope = ThemeEngine.activeScopeId();
    int motionSlot = -1;
    if (scope != null && ManaMotion.isWorkletSupported()) {
      motionSlot = ManaMotion.allocSlot(scope);
    }

    List<UiOps.Prop> pillProps =
        props(
            prop("absolute", UiOps.Value.ofBool(true)),
            prop("paddingBox", UiOps.Value.ofBool(true)),
            prop("gravity", UiOps.Value.ofString("top|start")),
            prop("width", UiOps.Value.ofInt(0)),
            prop("height", UiOps.Value.ofInt(34)),
            prop("background", colorVal(theme.segmentThumbBg)),
            prop("radius", UiOps.Value.ofInt(8)),
            prop("opacity", UiOps.Value.ofDouble(0.0)),
            prop("accessibilityHidden", UiOps.Value.ofBool(true)));
    if (motionSlot >= 0) {
      int[] worklet = ManaMotion.segmentedPillTranslateWorklet(motionSlot);
      addProp(pillProps, "worklet", ManaMotion.workletValue(worklet));
    }
    batch.createView(pillId, "view", pillProps);
    ThemeEngine.bindActive(pillId, "background", ColorToken.SEGMENT_THUMB_BG);

    batch.createView(
        rowId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("row")),
            prop("width", UiOps.Value.ofString("fill")),
            prop("align", UiOps.Value.ofString("center"))));

    for (int i = 0; i < count; i++) {
      boolean active = (i == selectedIndex);
      int tabId = ids.nextId();
      int lblId = ids.nextId();
      tabIds[i] = tabId;
      labelIds[i] = lblId;

      batch.createView(
          tabId,
          "view",
          props(
              prop("direction", UiOps.Value.ofString("row")),
              prop("padding", UiOps.Value.ofFloat4(8f, 8f, 8f, 8f)),
              prop("flex", UiOps.Value.ofDouble(1.0)),
              prop("justify", UiOps.Value.ofString("center")),
              prop("align", UiOps.Value.ofString("center")),
              prop("background", colorVal(0L)),
              prop("radius", UiOps.Value.ofInt(8)),
              prop("pressFeedback", UiOps.Value.ofString("none")),
              prop("role", UiOps.Value.ofString("tab")),
              prop("accessibilityLabel", UiOps.Value.ofString(labels[i])),
              prop("accessibilitySelected", UiOps.Value.ofBool(active)),
              prop("on", stringArray("press"))));

      batch.createView(
          lblId,
          "text",
          props(
              prop("text", UiOps.Value.ofString(labels[i])),
              prop("color", colorVal(active ? theme.primaryText : theme.mutedText)),
              prop("fontSize", UiOps.Value.ofInt(14)),
              prop("fontWeight", UiOps.Value.ofInt(600)),
              prop("lineHeight", UiOps.Value.ofInt(18)),
              prop("lineHeightExact", UiOps.Value.ofBool(true)),
              prop("maxLines", UiOps.Value.ofInt(1))));
      ThemeEngine.bindActive(
          lblId, "color", active ? ColorToken.TEXT_PRIMARY : ColorToken.TEXT_MUTED);

      batch.setChildren(tabId, lblId);
    }

    batch.setChildren(rowId, tabIds);
    batch.setChildren(trackId, pillId, rowId);
    batch.setChildren(rootId, trackId);
    if (motionSlot >= 0) {
      batch.motion(ManaMotion.initValueWords(motionSlot, 0f));
    }
    return new SegmentedHandle(rootId, tabIds, labelIds, trackId, pillId, motionSlot);
  }

  public static void updateSegmentedControl(
      UiOps batch, SegmentedHandle handle, int selectedIndex, Theme theme) {
    for (int i = 0; i < handle.tabIds.length; i++) {
      boolean active = (i == selectedIndex);
      if (handle.pillId < 0) {
        batch.updateView(
            handle.tabIds[i],
            props(
                prop("background", colorVal(active ? theme.segmentThumbBg : 0L)),
                prop("accessibilitySelected", UiOps.Value.ofBool(active))));
      } else {
        batch.updateView(
            handle.tabIds[i], props(prop("accessibilitySelected", UiOps.Value.ofBool(active))));
      }
      batch.updateView(
          handle.labelIds[i],
          props(prop("color", colorVal(active ? theme.primaryText : theme.mutedText))));
    }
  }

  public static SearchInputHandle searchInput(
      UiOps batch,
      IdAllocator ids,
      Theme theme,
      String placeholder,
      String initialValue,
      String accessibilityId) {
    int rootId = ids.nextId();
    int rowId = ids.nextId();
    int iconWrapId = ids.nextId();
    int inputId = ids.nextId();

    ManaPalette palette = ThemeEngine.currentPalette();
    String scope = ThemeEngine.activeScopeId();
    int focusMotionSlot = -1;
    if (scope != null && ManaMotion.isWorkletSupported()) {
      focusMotionSlot = ManaMotion.allocSlot(scope);
    }

    List<UiOps.Prop> rootProps =
        props(
            prop("direction", UiOps.Value.ofString("column")),
            prop("width", UiOps.Value.ofString("fill")),
            prop("background", colorVal(palette.color(ColorToken.INPUT_BG))),
            prop("borderColor", colorVal(palette.color(ColorToken.INPUT_BORDER))),
            prop("borderWidth", UiOps.Value.ofDouble(1.0)),
            prop("radius", UiOps.Value.ofInt(8)),
            prop("align", UiOps.Value.ofString("stretch")));
    if (focusMotionSlot >= 0) {
      int[] focusWorklet =
          ManaMotion.inputFocusWorklet(
              focusMotionSlot,
              palette.color(ColorToken.INPUT_BORDER),
              palette.color(ColorToken.BRAND_500));
      addProp(rootProps, "worklet", ManaMotion.workletValue(focusWorklet));
      ManaMotion.bindInputFocusThemeWorklet(scope, rootId, focusMotionSlot, false);
    }
    batch.createView(rootId, "view", rootProps);
    ThemeEngine.bindActive(rootId, "background", ColorToken.INPUT_BG);
    ThemeEngine.bindActive(rootId, "borderColor", ColorToken.INPUT_BORDER);

    batch.createView(
        rowId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("row")),
            prop("width", UiOps.Value.ofString("fill")),
            prop("align", UiOps.Value.ofString("center"))));

    batch.createView(
        iconWrapId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("row")),
            prop("padding", UiOps.Value.ofFloat4(12f, 12f, 8f, 12f)),
            prop("margin", UiOps.Value.ofFloat4(0f, -12f, 0f, -12f)),
            prop("align", UiOps.Value.ofString("center")),
            prop("justify", UiOps.Value.ofString("center"))));

    int iconId =
        icon(
            batch,
            ids,
            ManaIcons.MAGNIFYING_GLASS,
            16,
            16,
            theme.headerText,
            ColorToken.ICON_MUTED,
            0f,
            0f,
            0f,
            0f);

    List<UiOps.Prop> inputProps =
        props(
            prop("padding", UiOps.Value.ofFloat4(0f, 8f, 12f, 8f)),
            prop("minHeight", UiOps.Value.ofInt(38)),
            prop("flex", UiOps.Value.ofDouble(1.0)),
            prop("placeholderColor", colorVal(palette.color(ColorToken.INPUT_PLACEHOLDER))),
            prop("placeholder", UiOps.Value.ofString(placeholder != null ? placeholder : "Search")),
            prop("autoCorrect", UiOps.Value.ofBool(false)),
            prop("autoCapitalize", UiOps.Value.ofString("none")),
            prop("returnKey", UiOps.Value.ofString("search")),
            prop("value", UiOps.Value.ofString(initialValue != null ? initialValue : "")),
            prop("color", colorVal(palette.color(ColorToken.INPUT_TEXT))),
            prop("fontSize", UiOps.Value.ofInt(16)),
            prop("fontWeight", UiOps.Value.ofInt(400)),
            prop("role", UiOps.Value.ofString("search")),
            prop(
                "accessibilityLabel",
                UiOps.Value.ofString(placeholder != null ? placeholder : "Search")),
            prop("on", stringArray("changeText", "submit", "focus", "blur")));
    if (accessibilityId != null && accessibilityId.length() > 0) {
      addProp(inputProps, "accessibilityId", UiOps.Value.ofString(accessibilityId));
    }
    batch.createView(inputId, "input", inputProps);
    ThemeEngine.bindActive(inputId, "color", ColorToken.INPUT_TEXT);
    ThemeEngine.bindActive(inputId, "placeholderColor", ColorToken.INPUT_PLACEHOLDER);

    batch.setChildren(iconWrapId, iconId);
    batch.setChildren(rowId, iconWrapId, inputId);
    batch.setChildren(rootId, rowId);
    if (focusMotionSlot >= 0) {
      batch.motion(ManaMotion.initValueWords(focusMotionSlot, 0f));
    }
    return new SearchInputHandle(rootId, inputId, focusMotionSlot);
  }

  public static BottomSheetHandle bottomSheet(
      UiOps batch,
      IdAllocator ids,
      Theme theme,
      String title,
      String subtitle,
      int... bodySectionIds) {
    int sheetId = ids.nextId();
    int scrollId = ids.nextId();
    int padWrapId = ids.nextId();
    int bodyColId = ids.nextId();

    batch.createView(
        sheetId,
        "sheet",
        props(
            prop("background", colorVal(theme.screenBg)),
            prop("radiusTopLeft", UiOps.Value.ofInt(24)),
            prop("radiusTopRight", UiOps.Value.ofInt(24)),
            prop("handleColor", colorVal(theme.handleColor)),
            prop("mana", UiOps.Value.ofBool(true)),
            prop("dismissLabel", UiOps.Value.ofString("Dismiss")),
            prop("on", stringArray("dismiss"))));
    ThemeEngine.bindActive(sheetId, "background", ColorToken.SHEET_BG);
    ThemeEngine.bindActive(sheetId, "handleColor", ColorToken.SHEET_HANDLE);

    batch.createView(
        scrollId,
        "scroll",
        props(
            prop("width", UiOps.Value.ofString("fill")),
            prop("keyboardShouldPersistTaps", UiOps.Value.ofString("never")),
            prop("keyboardDismiss", UiOps.Value.ofString("interactive"))));

    batch.createView(
        padWrapId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("column")),
            prop("padding", UiOps.Value.ofFloat4(12f, 0f, 12f, 16f)),
            prop("width", UiOps.Value.ofString("fill")),
            prop("align", UiOps.Value.ofString("stretch"))));

    List<Integer> padKids = new ArrayList<Integer>(2);
    if (title != null && title.length() > 0) {
      int hdrMarginId = ids.nextId();
      int hdrColId = ids.nextId();
      int hdrTitleId = ids.nextId();
      batch.createView(
          hdrMarginId,
          "view",
          props(
              prop("margin", UiOps.Value.ofFloat4(0f, 0f, 0f, 16f)),
              prop("align", UiOps.Value.ofString("stretch"))));
      batch.createView(
          hdrColId,
          "view",
          props(
              prop("direction", UiOps.Value.ofString("column")),
              prop("gap", UiOps.Value.ofInt(2)),
              prop("align", UiOps.Value.ofString("stretch"))));
      batch.createView(
          hdrTitleId,
          "text",
          props(
              prop("text", UiOps.Value.ofString(title)),
              prop("color", colorVal(theme.primaryText)),
              prop("fontSize", UiOps.Value.ofInt(18)),
              prop("fontWeight", UiOps.Value.ofInt(600)),
              prop("lineHeight", UiOps.Value.ofInt(22)),
              prop("lineHeightExact", UiOps.Value.ofBool(true)),
              prop("textAlign", UiOps.Value.ofString("center")),
              prop("maxLines", UiOps.Value.ofInt(2)),
              prop("role", UiOps.Value.ofString("header"))));
      ThemeEngine.bindActive(hdrTitleId, "color", ColorToken.TEXT_PRIMARY);
      if (subtitle != null && subtitle.length() > 0) {
        int hdrSubId = ids.nextId();
        batch.createView(
            hdrSubId,
            "text",
            props(
                prop("text", UiOps.Value.ofString(subtitle)),
                prop("color", colorVal(theme.mutedText)),
                prop("fontSize", UiOps.Value.ofInt(14)),
                prop("fontWeight", UiOps.Value.ofInt(500)),
                prop("lineHeight", UiOps.Value.ofInt(18)),
                prop("lineHeightExact", UiOps.Value.ofBool(true)),
                prop("textAlign", UiOps.Value.ofString("center"))));
        ThemeEngine.bindActive(hdrSubId, "color", ColorToken.TEXT_MUTED);
        batch.setChildren(hdrColId, hdrTitleId, hdrSubId);
      } else {
        batch.setChildren(hdrColId, hdrTitleId);
      }
      batch.setChildren(hdrMarginId, hdrColId);
      padKids.add(Integer.valueOf(hdrMarginId));
    }

    batch.createView(
        bodyColId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("column")),
            prop("width", UiOps.Value.ofString("fill")),
            prop("gap", UiOps.Value.ofInt(16)),
            prop("align", UiOps.Value.ofString("stretch"))));
    if (bodySectionIds != null && bodySectionIds.length > 0) {
      batch.setChildren(bodyColId, bodySectionIds);
    }
    padKids.add(Integer.valueOf(bodyColId));

    batch.setChildren(padWrapId, toIntArray(padKids));
    batch.setChildren(scrollId, padWrapId);
    batch.setChildren(sheetId, scrollId);
    return new BottomSheetHandle(sheetId, scrollId, bodyColId);
  }

  public static int badge(
      UiOps batch, IdAllocator ids, String text, long backgroundColor, long textColor) {
    int pillId = ids.nextId();
    int txtId = ids.nextId();
    batch.createView(
        pillId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("row")),
            prop("padding", UiOps.Value.ofFloat4(6f, 0f, 6f, 0f)),
            prop("height", UiOps.Value.ofInt(18)),
            prop("minWidth", UiOps.Value.ofInt(18)),
            prop("align", UiOps.Value.ofString("center")),
            prop("justify", UiOps.Value.ofString("center")),
            prop("background", colorVal(backgroundColor)),
            prop("radius", UiOps.Value.ofInt(9999))));
    batch.createView(
        txtId,
        "text",
        props(
            prop("text", UiOps.Value.ofString(text)),
            prop("color", colorVal(textColor)),
            prop("fontSize", UiOps.Value.ofInt(12)),
            prop("fontWeight", UiOps.Value.ofInt(600)),
            prop("lineHeight", UiOps.Value.ofInt(16)),
            prop("lineHeightExact", UiOps.Value.ofBool(true)),
            prop("textAlign", UiOps.Value.ofString("center"))));
    batch.setChildren(pillId, txtId);
    return pillId;
  }

  public static List<UiOps.Prop> baseRowProps(Theme theme, RowPosition pos, boolean clickable) {
    RowPosition p = pos != null ? pos : RowPosition.SINGLE;
    List<UiOps.Prop> list =
        props(
            prop("direction", UiOps.Value.ofString("column")),
            prop("width", UiOps.Value.ofString("fill")),
            prop("background", colorVal(theme.rowBg)),
            prop("align", UiOps.Value.ofString("stretch")));
    if (p == RowPosition.SINGLE) {
      addProp(list, "radius", UiOps.Value.ofInt(12));
    } else if (p == RowPosition.FIRST) {
      addProp(list, "radiusTopLeft", UiOps.Value.ofInt(12));
      addProp(list, "radiusTopRight", UiOps.Value.ofInt(12));
    } else if (p == RowPosition.LAST) {
      addProp(list, "radiusBottomLeft", UiOps.Value.ofInt(12));
      addProp(list, "radiusBottomRight", UiOps.Value.ofInt(12));
    }
    if (clickable) {
      addProp(list, "activeBackground", colorVal(theme.rowActiveBg));
    }
    return list;
  }

  public static void bindRowTheme(int rowId, boolean clickable) {
    ThemeEngine.bindActive(rowId, "background", ColorToken.BG_BASE_SECONDARY);
    if (clickable) {
      ThemeEngine.bindActive(rowId, "activeBackground", ColorToken.BG_ROW_ACTIVE);
    }
  }

  public static int leadingIcon(
      UiOps batch, IdAllocator ids, String uri, long tint, ColorToken tintToken) {
    return icon(batch, ids, uri, 24, 24, tint, tintToken, 4f, 4f, 16f, 4f);
  }

  public static int labelBlock(
      UiOps batch,
      IdAllocator ids,
      Theme theme,
      String title,
      String subtitle,
      long titleColor,
      ColorToken titleToken) {
    if (subtitle == null || subtitle.length() == 0) {
      int titleId = ids.nextId();
      batch.createView(
          titleId,
          "text",
          props(
              prop("text", UiOps.Value.ofString(title)),
              prop("color", colorVal(titleColor)),
              prop("fontSize", UiOps.Value.ofInt(16)),
              prop("fontWeight", UiOps.Value.ofInt(500)),
              prop("lineHeight", UiOps.Value.ofInt(20)),
              prop("lineHeightExact", UiOps.Value.ofBool(true)),
              prop("includeFontPadding", UiOps.Value.ofBool(true)),
              prop("flex", UiOps.Value.ofDouble(1.0))));
      ThemeEngine.bindActive(titleId, "color", titleToken);
      return titleId;
    }
    int colId = ids.nextId();
    int titleId = ids.nextId();
    int subId = ids.nextId();
    batch.createView(
        colId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("column")),
            prop("flex", UiOps.Value.ofDouble(1.0)),
            prop("align", UiOps.Value.ofString("stretch"))));
    batch.createView(
        titleId,
        "text",
        props(
            prop("text", UiOps.Value.ofString(title)),
            prop("color", colorVal(titleColor)),
            prop("fontSize", UiOps.Value.ofInt(16)),
            prop("fontWeight", UiOps.Value.ofInt(500)),
            prop("lineHeight", UiOps.Value.ofInt(20)),
            prop("lineHeightExact", UiOps.Value.ofBool(true)),
            prop("includeFontPadding", UiOps.Value.ofBool(true))));
    ThemeEngine.bindActive(titleId, "color", titleToken);
    batch.createView(
        subId,
        "text",
        props(
            prop("text", UiOps.Value.ofString(subtitle)),
            prop("color", colorVal(theme.headerText)),
            prop("fontSize", UiOps.Value.ofInt(12)),
            prop("fontWeight", UiOps.Value.ofInt(500)),
            prop("lineHeight", UiOps.Value.ofInt(16)),
            prop("lineHeightExact", UiOps.Value.ofBool(true)),
            prop("includeFontPadding", UiOps.Value.ofBool(true))));
    ThemeEngine.bindActive(subId, "color", ColorToken.TEXT_SECONDARY);
    batch.setChildren(colId, titleId, subId);
    return colId;
  }

  private static int trailingValueView(
      UiOps batch,
      IdAllocator ids,
      Theme theme,
      String valueText,
      int fontSize,
      int lineHeight,
      int[] outTextId) {
    int wrapId = ids.nextId();
    int txtId = ids.nextId();
    if (outTextId != null && outTextId.length > 0) {
      outTextId[0] = txtId;
    }
    batch.createView(
        wrapId,
        "view",
        props(
            prop("margin", UiOps.Value.ofFloat4(12f, 0f, 0f, 0f)),
            prop("align", UiOps.Value.ofString("stretch"))));
    batch.createView(
        txtId,
        "text",
        props(
            prop("text", UiOps.Value.ofString(valueText)),
            prop("color", colorVal(theme.mutedText)),
            prop("fontSize", UiOps.Value.ofInt(fontSize)),
            prop("fontWeight", UiOps.Value.ofInt(500)),
            prop("lineHeight", UiOps.Value.ofInt(lineHeight)),
            prop("lineHeightExact", UiOps.Value.ofBool(true)),
            prop("textAlign", UiOps.Value.ofString("right")),
            prop("maxLines", UiOps.Value.ofInt(1))));
    ThemeEngine.bindActive(txtId, "color", ColorToken.TEXT_MUTED);
    batch.setChildren(wrapId, txtId);
    return wrapId;
  }

  private static int chevronView(UiOps batch, IdAllocator ids, Theme theme) {
    int wrapId = ids.nextId();
    batch.createView(
        wrapId,
        "view",
        props(
            prop("direction", UiOps.Value.ofString("row")),
            prop("margin", UiOps.Value.ofFloat4(8f, 0f, 8f, 0f)),
            prop("width", UiOps.Value.ofInt(12)),
            prop("height", UiOps.Value.ofInt(24)),
            prop("align", UiOps.Value.ofString("center")),
            prop("justify", UiOps.Value.ofString("center")),
            prop("clip", UiOps.Value.ofBool(true))));
    int imgId =
        icon(
            batch,
            ids,
            ManaIcons.CHEVRON_SMALL_RIGHT,
            12,
            24,
            theme.headerText,
            ColorToken.ICON_MUTED,
            0f,
            0f,
            0f,
            0f);
    batch.setChildren(wrapId, imgId);
    return wrapId;
  }

  public static UiOps.Prop prop(String name, UiOps.Value val) {
    int id = UiOps.propId(name);
    if (id < 0) return null;
    return new UiOps.Prop(id, val);
  }

  public static List<UiOps.Prop> props(UiOps.Prop... entries) {
    List<UiOps.Prop> out = new ArrayList<UiOps.Prop>(entries.length);
    for (int i = 0; i < entries.length; i++) {
      if (entries[i] != null) out.add(entries[i]);
    }
    return out;
  }

  public static void addProp(List<UiOps.Prop> list, String name, UiOps.Value val) {
    UiOps.Prop p = prop(name, val);
    if (p != null) list.add(p);
  }

  public static UiOps.Value colorVal(long argbUnsigned) {
    long u = argbUnsigned & 0xFFFFFFFFL;
    if (u <= Integer.MAX_VALUE) {
      return UiOps.Value.ofInt((int) u);
    }
    return UiOps.Value.ofLong(u);
  }

  public static UiOps.Value stringArray(String... items) {
    List<UiOps.Value> vals = new ArrayList<UiOps.Value>(items.length);
    for (int i = 0; i < items.length; i++) {
      vals.add(UiOps.Value.ofString(items[i]));
    }
    return UiOps.Value.ofArray(vals);
  }

  private static long extractColorProp(UiOps.Op op, String name, long fallback) {
    UiOps.Prop p = op.findProp(name);
    if (p == null || p.value == null) return fallback;
    int tag = p.value.tag();
    if (tag == UiOps.TAG_INT32 || tag == UiOps.TAG_INT64) {
      return p.value.asLong() & 0xFFFFFFFFL;
    }
    return fallback;
  }

  private static int[] toIntArray(List<Integer> list) {
    int[] out = new int[list.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = list.get(i).intValue();
    }
    return out;
  }
}
