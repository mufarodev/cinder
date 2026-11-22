package com.discord.loader;

import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import com.discord.loader.mana.ThemeEngine;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ManaAnimator {
  private ManaAnimator() {}

  public static final double DEFAULT_MASS = 1.0;
  public static final double DEFAULT_STIFFNESS = 300.0;
  public static final double DEFAULT_DAMPING = 27.0;

  private static final double MAX_FRAME_DT = 0.032;
  private static final double SUBSTEP_DT = 0.004;
  private static final double REST_POS_EPS = 0.002;
  private static final double REST_VEL_EPS = 0.01;

  private interface FrameEmitter {
    void emit(UiOps batch, double pos, boolean isFinal);
  }

  private static final class ActiveSpring {
    final int keyViewId;
    final double mass;
    final double stiffness;
    final double damping;
    volatile double target;
    volatile FrameEmitter emitter;
    double pos;
    double vel;
    long lastFrameNanos;
    volatile boolean cancelled;

    ActiveSpring(
        int keyViewId,
        double startPos,
        double targetPos,
        double mass,
        double stiffness,
        double damping,
        FrameEmitter emitter) {
      this.keyViewId = keyViewId;
      this.pos = startPos;
      this.target = targetPos;
      this.mass = mass;
      this.stiffness = stiffness;
      this.damping = damping;
      this.emitter = emitter;
      this.vel = 0.0;
      this.lastFrameNanos = 0L;
      this.cancelled = false;
    }
  }

  private static final ConcurrentHashMap<Integer, ActiveSpring> ACTIVE_SPRINGS =
      new ConcurrentHashMap<Integer, ActiveSpring>();
  private static final Object TICK_LOCK = new Object();
  private static volatile Handler mainHandler = null;
  private static boolean tickScheduled = false;

  public static void animateToggleRow(final ManaUi.ToggleRowHandle handle, final boolean checked) {
    animateToggleRow(handle, checked, null);
  }

  public static void animateToggleRow(
      final ManaUi.ToggleRowHandle handle, final boolean checked, final ManaUi.Theme theme) {
    if (handle == null) return;
    if (!isAndroidMainLooperAvailable()) {
      ManaUi.Theme t = theme != null ? theme : ThemeEngine.currentTheme();
      UiOps batch = new UiOps();
      ManaUi.updateToggleRow(batch, handle, checked, t);
      Loader.applySyntheticOps(batch.toBytes());
      return;
    }

    UiOps a11y = new UiOps();
    a11y.updateView(
        handle.rowId,
        ManaUi.props(ManaUi.prop("accessibilityChecked", UiOps.Value.ofBool(checked))));
    Loader.applySyntheticOps(a11y.toBytes());

    animateSwitch(handle.toggle, checked, theme);
  }

  public static void animateSwitch(final ManaUi.SwitchHandle handle, final boolean checked) {
    animateSwitch(handle, checked, null);
  }

  public static void animateSwitch(
      final ManaUi.SwitchHandle handle, final boolean checked, final ManaUi.Theme theme) {
    if (handle == null) return;
    if (!isAndroidMainLooperAvailable()) {
      ManaUi.Theme t = theme != null ? theme : ThemeEngine.currentTheme();
      UiOps batch = new UiOps();
      ManaUi.updateSwitch(batch, handle, checked, t);
      Loader.applySyntheticOps(batch.toBytes());
      return;
    }

    double target = checked ? 1.0 : 0.0;
    double start = checked ? 0.0 : 1.0;
    FrameEmitter emitter =
        new FrameEmitter() {
          @Override
          public void emit(UiOps batch, double pos, boolean isFinal) {
            ManaUi.Theme t = theme != null ? theme : ThemeEngine.currentTheme();
            double p = isFinal ? (checked ? 1.0 : 0.0) : pos;
            double clamped = clamp01(p);
            long bg = lerpArgb(t.switchOffBg, t.switchOnBg, clamped);
            long border = lerpArgb(t.switchOffBorder, t.switchOnBorder, clamped);
            double tx = isFinal ? (checked ? 28.0 : 0.0) : (p * 28.0);
            batch.updateView(
                handle.trackId,
                ManaUi.props(
                    ManaUi.prop("background", ManaUi.colorVal(bg)),
                    ManaUi.prop("borderColor", ManaUi.colorVal(border))));
            batch.updateView(
                handle.thumbId,
                ManaUi.props(
                    ManaUi.prop(
                        "translateX",
                        isFinal ? UiOps.Value.ofInt(checked ? 28 : 0) : UiOps.Value.ofDouble(tx))));
          }
        };

    startOrRetarget(
        handle.trackId, start, target, DEFAULT_MASS, DEFAULT_STIFFNESS, DEFAULT_DAMPING, emitter);
  }

  public static void animateRadioRow(final ManaUi.RadioRowHandle handle, final boolean selected) {
    animateRadioRow(handle, selected, null);
  }

  public static void animateRadioRow(
      final ManaUi.RadioRowHandle handle, final boolean selected, final ManaUi.Theme theme) {
    if (handle == null) return;
    if (!isAndroidMainLooperAvailable()) {
      ManaUi.Theme t = theme != null ? theme : ThemeEngine.currentTheme();
      UiOps batch = new UiOps();
      ManaUi.updateRadioRow(batch, handle, selected, t);
      Loader.applySyntheticOps(batch.toBytes());
      return;
    }

    UiOps a11y = new UiOps();
    a11y.updateView(
        handle.rowId,
        ManaUi.props(
            ManaUi.prop("accessibilitySelected", UiOps.Value.ofBool(selected)),
            ManaUi.prop("accessibilityChecked", UiOps.Value.ofBool(selected))));
    Loader.applySyntheticOps(a11y.toBytes());

    double target = selected ? 1.0 : 0.0;
    double start = selected ? 0.0 : 1.0;
    FrameEmitter emitter =
        new FrameEmitter() {
          @Override
          public void emit(UiOps batch, double pos, boolean isFinal) {
            ManaUi.Theme t = theme != null ? theme : ThemeEngine.currentTheme();
            double p = isFinal ? (selected ? 1.0 : 0.0) : pos;
            double c = clamp01(p);
            long bg = lerpArgb(0L, t.brandAccent, c);
            long border = lerpArgb(t.radioUncheckedBorder, t.brandAccent, c);
            double scale = 0.5 + 0.5 * p;
            batch.updateView(
                handle.outerCircleId,
                ManaUi.props(
                    ManaUi.prop("background", ManaUi.colorVal(bg)),
                    ManaUi.prop("borderColor", ManaUi.colorVal(border))));
            batch.updateView(
                handle.innerDotWrapId,
                ManaUi.props(ManaUi.prop("opacity", UiOps.Value.ofDouble(c))));
            batch.updateView(
                handle.innerDotId,
                ManaUi.props(
                    ManaUi.prop("scaleFactor", UiOps.Value.ofDouble(Math.max(0.1, scale)))));
          }
        };

    startOrRetarget(
        handle.outerCircleId,
        start,
        target,
        DEFAULT_MASS,
        DEFAULT_STIFFNESS,
        DEFAULT_DAMPING,
        emitter);
  }

  public static void cancelForViews(int[] viewIds) {
    if (viewIds == null || viewIds.length == 0 || ACTIVE_SPRINGS.isEmpty()) return;
    for (int i = 0; i < viewIds.length; i++) {
      ActiveSpring s = ACTIVE_SPRINGS.remove(Integer.valueOf(viewIds[i]));
      if (s != null) {
        s.cancelled = true;
      }
    }
  }

  public static void cancelAll() {
    for (ActiveSpring s : ACTIVE_SPRINGS.values()) {
      s.cancelled = true;
    }
    ACTIVE_SPRINGS.clear();
  }

  public static long lerpArgb(long c0, long c1, double t) {
    double k = clamp01(t);
    int a0 = (int) ((c0 >>> 24) & 0xFFL);
    int r0 = (int) ((c0 >>> 16) & 0xFFL);
    int g0 = (int) ((c0 >>> 8) & 0xFFL);
    int b0 = (int) (c0 & 0xFFL);

    int a1 = (int) ((c1 >>> 24) & 0xFFL);
    int r1 = (int) ((c1 >>> 16) & 0xFFL);
    int g1 = (int) ((c1 >>> 8) & 0xFFL);
    int b1 = (int) (c1 & 0xFFL);

    long a = Math.round(a0 + (a1 - a0) * k) & 0xFFL;
    long r = Math.round(r0 + (r1 - r0) * k) & 0xFFL;
    long g = Math.round(g0 + (g1 - g0) * k) & 0xFFL;
    long b = Math.round(b0 + (b1 - b0) * k) & 0xFFL;
    return (a << 24) | (r << 16) | (g << 8) | b;
  }

  static boolean stepSpring(
      double[] posVel, double target, double mass, double stiffness, double damping, double dt) {
    double clampedDt = Math.min(Math.max(dt, 0.001), MAX_FRAME_DT);
    int steps = Math.max(1, (int) Math.ceil(clampedDt / SUBSTEP_DT));
    double h = clampedDt / steps;
    double pos = posVel[0];
    double vel = posVel[1];
    for (int i = 0; i < steps; i++) {
      double accel = (-stiffness * (pos - target) - damping * vel) / mass;
      vel += accel * h;
      pos += vel * h;
    }
    if (Math.abs(target - pos) < REST_POS_EPS && Math.abs(vel) < REST_VEL_EPS) {
      posVel[0] = target;
      posVel[1] = 0.0;
      return true;
    }
    posVel[0] = pos;
    posVel[1] = vel;
    return false;
  }

  private static void startOrRetarget(
      int keyViewId,
      double startPos,
      double targetPos,
      double mass,
      double stiffness,
      double damping,
      FrameEmitter emitter) {
    Integer key = Integer.valueOf(keyViewId);
    ActiveSpring existing = ACTIVE_SPRINGS.get(key);
    if (existing != null && !existing.cancelled) {
      existing.target = targetPos;
      existing.emitter = emitter;
    } else {
      ACTIVE_SPRINGS.put(
          key, new ActiveSpring(keyViewId, startPos, targetPos, mass, stiffness, damping, emitter));
    }
    ensureTickScheduled();
  }

  private static void ensureTickScheduled() {
    synchronized (TICK_LOCK) {
      if (tickScheduled) return;
      tickScheduled = true;
    }
    Handler h = getMainHandler();
    if (h == null) {
      synchronized (TICK_LOCK) {
        tickScheduled = false;
      }
      return;
    }
    h.post(
        new Runnable() {
          @Override
          public void run() {
            try {
              Choreographer.getInstance().postFrameCallback(FRAME_CALLBACK);
            } catch (Throwable ignored) {
              synchronized (TICK_LOCK) {
                tickScheduled = false;
              }
            }
          }
        });
  }

  private static final Choreographer.FrameCallback FRAME_CALLBACK =
      new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
          UiOps batch = new UiOps();
          List<Integer> finishedKeys = null;
          double[] pv = new double[2];

          Iterator<Map.Entry<Integer, ActiveSpring>> it = ACTIVE_SPRINGS.entrySet().iterator();
          while (it.hasNext()) {
            Map.Entry<Integer, ActiveSpring> entry = it.next();
            ActiveSpring s = entry.getValue();
            if (s.cancelled) {
              it.remove();
              continue;
            }
            double dt;
            if (s.lastFrameNanos == 0L) {
              dt = 0.016;
            } else {
              dt = (frameTimeNanos - s.lastFrameNanos) / 1_000_000_000.0;
            }
            s.lastFrameNanos = frameTimeNanos;

            pv[0] = s.pos;
            pv[1] = s.vel;
            boolean settled = stepSpring(pv, s.target, s.mass, s.stiffness, s.damping, dt);
            s.pos = pv[0];
            s.vel = pv[1];

            FrameEmitter em = s.emitter;
            if (em != null) {
              em.emit(batch, s.pos, settled);
            }
            if (settled) {
              if (finishedKeys == null) finishedKeys = new ArrayList<Integer>();
              finishedKeys.add(entry.getKey());
            }
          }

          if (finishedKeys != null) {
            for (int i = 0; i < finishedKeys.size(); i++) {
              ACTIVE_SPRINGS.remove(finishedKeys.get(i));
            }
          }

          if (!batch.isEmpty()) {
            Loader.applySyntheticOps(batch.toBytes());
          }

          boolean keepGoing = !ACTIVE_SPRINGS.isEmpty();
          if (keepGoing) {
            try {
              Choreographer.getInstance().postFrameCallback(this);
            } catch (Throwable ignored) {
              synchronized (TICK_LOCK) {
                tickScheduled = false;
              }
            }
          } else {
            synchronized (TICK_LOCK) {
              tickScheduled = false;
            }

            if (!ACTIVE_SPRINGS.isEmpty()) {
              ensureTickScheduled();
            }
          }
        }
      };

  private static Handler getMainHandler() {
    Handler h = mainHandler;
    if (h != null) return h;
    try {
      Looper main = Looper.getMainLooper();
      if (main == null) return null;
      h = new Handler(main);
      mainHandler = h;
      return h;
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static boolean isAndroidMainLooperAvailable() {
    return getMainHandler() != null;
  }

  private static double clamp01(double v) {
    if (v <= 0.0) return 0.0;
    if (v >= 1.0) return 1.0;
    return v;
  }
}
