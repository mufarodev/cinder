package com.discord.loader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class UiOpsTest {
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

  private static void checkBytes(byte[] actual, byte[] expected, String msg) {
    if (!Arrays.equals(actual, expected)) {
      throw new AssertionError(
          "FAIL: "
              + msg
              + " (expected len="
              + (expected == null ? -1 : expected.length)
              + ", actual len="
              + (actual == null ? -1 : actual.length)
              + ")");
    }
  }

  private static void runTest(String name, Runnable r) {
    Loader.resetForTests();
    BridgeRegistry.reset();
    r.run();
    passed++;
    System.out.println("PASS " + name);
  }

  private static void registerSampleProps() {
    String[] table = new String[650];
    Arrays.fill(table, "");
    table[9] = "accessibilityId";
    table[11] = "accessibilityLabel";
    table[129] = "color";
    table[149] = "dismiss";
    table[275] = "label";
    table[489] = "style";
    table[499] = "text";
    table[523] = "title";
    table[543] = "value";
    table[575] = "change";
    table[614] = "press";
    table[620] = "rowPress";
    table[642] = "longPress";
    UiOps.registerPropNames(table);
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
    checkEq(buf.get(0) & 0xff, Frame.KIND_BATCH, "batch kind=4");
    checkEq(buf.getInt(12), 0, "batch payloadLen=0");
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

  private static void testAll10OpcodesAnd13ValueTagsExactRoundTrip() {
    registerSampleProps();

    List<UiOps.Prop> styleProps =
        Arrays.asList(
            UiOps.Prop.named("color", UiOps.Value.ofInt(0xFF5865F2)),
            UiOps.Prop.named("label", UiOps.Value.ofString("Cinder ⚡")));
    List<UiOps.NamedValue> dynMap =
        Arrays.asList(
            new UiOps.NamedValue("customKey", UiOps.Value.ofDouble(3.14159)),
            new UiOps.NamedValue("flag", UiOps.Value.ofBool(true)));
    List<UiOps.Value> arrItems =
        Arrays.asList(
            UiOps.Value.ofNull(),
            UiOps.Value.ofBool(false),
            UiOps.Value.ofBool(true),
            UiOps.Value.ofInt(-42),
            UiOps.Value.ofLong(0x0123456789ABCDEFL),
            UiOps.Value.ofDouble(-12.5));
    List<UiOps.Prop> createProps =
        Arrays.asList(
            new UiOps.Prop(0, UiOps.Value.ofNull()),
            new UiOps.Prop(1, UiOps.Value.ofBool(false)),
            new UiOps.Prop(2, UiOps.Value.ofBool(true)),
            new UiOps.Prop(3, UiOps.Value.ofInt(123456)),
            new UiOps.Prop(4, UiOps.Value.ofLong(9876543210123L)),
            new UiOps.Prop(5, UiOps.Value.ofDouble(42.25)),
            UiOps.Prop.named("text", UiOps.Value.ofString("Staff Settings — UTF8 ✓")),
            new UiOps.Prop(7, UiOps.Value.ofArray(arrItems)),
            UiOps.Prop.named("style", UiOps.Value.ofPropMap(styleProps)),
            new UiOps.Prop(9, UiOps.Value.ofFloat4(1.0f, 2.5f, 3.75f, 4.0f)),
            new UiOps.Prop(10, UiOps.Value.ofStringMap(dynMap)),
            new UiOps.Prop(11, UiOps.Value.ofWordList(10, 20, 30, 0x80000001)),
            new UiOps.Prop(
                12, UiOps.Value.ofWordListDelta(0, new int[] {1, 3}, new int[] {99, 777})));

    UiOps batch =
        UiOps.create()
            .createView(100, "stack", createProps)
            .updateView(
                100,
                Collections.singletonList(
                    UiOps.Prop.named("accessibilityId", UiOps.Value.ofString("settings-search"))))
            .setChildren(100, 101, 102, 103)
            .deleteViews(998, 999)
            .setRoot(100)
            .focus(101)
            .clearFocus()
            .scrollTo(
                100,
                UiOps.Value.ofPropMap(
                    Collections.singletonList(UiOps.Prop.named("value", UiOps.Value.ofInt(250)))))
            .requestFrame()
            .motion(1, 2, 3, 4)
            .childEdits(
                100,
                Arrays.asList(
                    new UiOps.ChildEdit(1, 1, 200, 201),
                    new UiOps.ChildEdit(0, 0, UiOps.SYNTHETIC_ID_BASE + 1)));

    byte[] encoded = batch.toBytes();
    checkEq(encoded[0] & 0xff, UiOps.WIRE_VERSION, "wire version header = 1");

    UiOps decoded = UiOps.parse(encoded);
    check(decoded != null, "parsed valid batch");
    checkEq(decoded.size(), 11, "11 ops parsed");
    checkBytes(
        decoded.toBytes(), encoded, "exact byte-for-byte round-trip across all 10 ops & 13 tags");

    UiOps.Op op0 = decoded.get(0);
    checkEq(op0.opcode(), UiOps.OP_CREATE, "op0 create");
    checkEq(op0.viewId(), 100, "op0 viewId");
    checkEq(op0.viewType(), "stack", "op0 viewType");
    checkEq(op0.propString("text"), "Staff Settings — UTF8 ✓", "op0 text prop");
    check(op0.containsString("Cinder ⚡"), "recursive containsString into PROP_MAP");
    check(op0.containsString("customKey"), "recursive containsString into STRING_MAP");
    checkEq(op0.findProp(9).value.asFloat4()[2], 3.75f, "FLOAT4 value[2]");
    checkEq(op0.findProp(11).value.asWordList()[3], 0x80000001, "WORD_LIST[3]");
    checkEq(op0.findProp(12).value.asWordListDelta().baseIndex, 0, "WORD_LIST_DELTA base");
    checkEq(op0.findProp(12).value.asWordListDelta().values[1], 777, "WORD_LIST_DELTA val[1]");

    checkEq(decoded.get(1).opcode(), UiOps.OP_UPDATE, "op1 update");
    checkEq(decoded.get(1).propString("accessibilityId"), "settings-search", "op1 accessibilityId");

    checkEq(decoded.get(2).opcode(), UiOps.OP_CHILDREN, "op2 children");
    check(Arrays.equals(decoded.get(2).childIds(), new int[] {101, 102, 103}), "op2 childIds");

    checkEq(decoded.get(3).opcode(), UiOps.OP_DELETE, "op3 delete");
    check(Arrays.equals(decoded.get(3).deleteIds(), new int[] {998, 999}), "op3 deleteIds");

    checkEq(decoded.get(4).opcode(), UiOps.OP_ROOT, "op4 root");
    checkEq(decoded.get(4).viewId(), 100, "op4 root viewId");

    checkEq(decoded.get(5).opcode(), UiOps.OP_FOCUS, "op5 focus target");
    check(decoded.get(5).hasFocusTarget(), "op5 hasFocusTarget");
    checkEq(decoded.get(5).viewId(), 101, "op5 focus viewId");

    checkEq(decoded.get(6).opcode(), UiOps.OP_FOCUS, "op6 clearFocus");
    check(!decoded.get(6).hasFocusTarget(), "op6 !hasFocusTarget");

    checkEq(decoded.get(7).opcode(), UiOps.OP_SCROLL_TO, "op7 scrollTo");
    checkEq(
        decoded.get(7).scrollOptions().asPropMap().get(0).value.asInt(), 250, "op7 scrollTo value");

    checkEq(decoded.get(8).opcode(), UiOps.OP_REQUEST_FRAME, "op8 requestFrame");
    checkEq(decoded.get(9).opcode(), UiOps.OP_MOTION, "op9 motion");
    check(Arrays.equals(decoded.get(9).motionWords(), new int[] {1, 2, 3, 4}), "op9 words");

    checkEq(decoded.get(10).opcode(), UiOps.OP_CHILD_EDITS, "op10 childEdits");
    checkEq(decoded.get(10).childEdits().size(), 2, "op10 2 edits");
    checkEq(decoded.get(10).childEdits().get(0).keepCount, 1, "edit0 keep");
    checkEq(decoded.get(10).childEdits().get(0).deleteCount, 1, "edit0 del");
    check(
        Arrays.equals(decoded.get(10).childEdits().get(0).insertIds, new int[] {200, 201}),
        "edit0 ins");
    check(
        UiOps.isSyntheticId(decoded.get(10).childEdits().get(1).insertIds[0]),
        "synthetic ID check");
  }

  private static void testAppendOpsViaOnUiOpsAndMalformedReject() {
    registerSampleProps();

    check(UiOps.parse(null) == null, "null buffer");
    check(UiOps.parse(new byte[0]) == null, "empty buffer");
    check(UiOps.parse(new byte[] {2}) == null, "wrong version 2");
    check(UiOps.parse(new byte[] {1, 99}) == null, "unknown opcode 99");
    check(UiOps.parse(new byte[] {1, UiOps.OP_CREATE, 1, 2}) == null, "truncated create");
    check(UiOps.parse(new byte[] {1, UiOps.OP_FOCUS, 1, 0, 0}) == null, "truncated focus");

    ModPlugin injector =
        new ModPlugin() {
          @Override
          public byte[] onUiOps(byte[] ops, int length) {
            UiOps batch = UiOps.parse(ops, length);
            if (batch == null) return null;
            int synId = UiOps.SYNTHETIC_ID_BASE + 1;
            batch.createView(
                synId,
                "text",
                Collections.singletonList(
                    UiOps.Prop.named("text", UiOps.Value.ofString("Cinder"))));
            batch.childEdits(10, Collections.singletonList(new UiOps.ChildEdit(1, 0, synId)));
            return batch.toBytes();
          }
        };
    Loader.setPluginsForTests(injector);

    byte[] orig =
        UiOps.create().createView(10, "stack", Collections.<UiOps.Prop>emptyList()).toBytes();

    byte[] padded = Arrays.copyOf(orig, orig.length + 32);
    byte[] result = Loader.onOps(padded, orig.length);
    check(result != padded, "injector returned resized array");
    UiOps out = UiOps.parse(result);
    check(out != null && out.size() == 3, "appended 2 synthetic ops -> 3 total");
    checkEq(out.get(1).viewId(), UiOps.SYNTHETIC_ID_BASE + 1, "synthetic view ID");
    checkEq(out.get(1).propString("text"), "Cinder", "synthetic text prop");
  }

  private static void testViewEventCodec() {
    registerSampleProps();

    byte[] pressBytes =
        UiOps.ViewEvent.buildViewEvent(
            UiOps.SYNTHETIC_ID_BASE + 7,
            UiOps.propId("press"),
            UiOps.Value.ofPropMap(
                Collections.singletonList(UiOps.Prop.named("value", UiOps.Value.ofBool(true)))));
    check(UiOps.ViewEvent.isViewEventFrame(pressBytes), "isViewEventFrame");
    check(Frame.parse(pressBytes) == null, "ViewEvent must not parse as host Frame");

    UiOps.ViewEvent ev = UiOps.ViewEvent.parse(pressBytes);
    check(ev != null, "parsed ViewEvent");
    check(ev.isViewEvent(), "isViewEvent");
    check(ev.isSyntheticView(), "isSyntheticView");
    checkEq(ev.viewId(), UiOps.SYNTHETIC_ID_BASE + 7, "viewId");
    checkEq(ev.propId(), 614, "propId for press");
    checkEq(ev.eventName(), "press", "eventName");
    check(ev.payload().asPropMap().get(0).value.asBoolean(), "payload.value == true");

    UiOps.ViewEvent back = UiOps.ViewEvent.parse(UiOps.ViewEvent.buildBackEvent());
    check(back != null && back.isBackEvent() && !back.isSyntheticView(), "back event");

    UiOps.ViewEvent key = UiOps.ViewEvent.parse(UiOps.ViewEvent.buildKeyCommandEvent("devtools"));
    check(key != null && key.eventKind() == UiOps.ViewEvent.EVENT_KEY_COMMAND, "key command kind");
    checkEq(key.keyCommand(), "devtools", "key command text");

    UiOps.ViewEvent frameTick = UiOps.ViewEvent.parse(UiOps.ViewEvent.buildFrameEvent());
    check(frameTick != null && frameTick.eventKind() == UiOps.ViewEvent.EVENT_FRAME, "frame tick");

    ByteBuffer ins = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
    ins.putInt(0);
    ins.put((byte) 1);
    ins.put((byte) 4);
    ins.putFloat(10f);
    ins.putFloat(20f);
    ins.putFloat(30f);
    ins.putFloat(40f);
    ins.putFloat(3.0f);
    UiOps.ViewEvent insEv = UiOps.ViewEvent.parse(ins.array());
    check(insEv != null && insEv.insets() != null && insEv.insets()[4] == 3.0f, "window insets");
  }

  private static void testOnDispatchSingleAndBatchFiltering() {
    registerSampleProps();
    final int synRowId = UiOps.SYNTHETIC_ID_BASE + 42;
    final AtomicInteger synPresses = new AtomicInteger();
    final AtomicInteger realEventsSeen = new AtomicInteger();
    final AtomicInteger hostEventsSeen = new AtomicInteger();

    ModPlugin mod =
        new ModPlugin() {
          @Override
          public byte[] onViewEvent(UiOps.ViewEvent event) {
            if (event.isSyntheticView()) {
              synPresses.incrementAndGet();
              return Frame.DROP;
            }
            realEventsSeen.incrementAndGet();
            return null;
          }

          @Override
          public byte[] onDispatch(Frame frame) {
            hostEventsSeen.incrementAndGet();
            if (frame.moduleId() == 77) {
              return frame.withPayload("rewritten".getBytes(StandardCharsets.UTF_8));
            }
            if (frame.moduleId() == 88) {
              return Frame.DROP;
            }
            return null;
          }
        };
    Loader.setPluginsForTests(mod);

    byte[] synPress =
        UiOps.ViewEvent.buildViewEvent(synRowId, UiOps.propId("press"), UiOps.Value.ofNull());
    byte[] realPress =
        UiOps.ViewEvent.buildViewEvent(55, UiOps.propId("press"), UiOps.Value.ofNull());
    byte[] hostEvtKeep =
        Frame.build(Frame.KIND_EVENT, 12, 34, 0, "orig".getBytes(StandardCharsets.UTF_8));
    byte[] hostEvtRewrite =
        Frame.build(Frame.KIND_EVENT, 77, 1, 0, "before".getBytes(StandardCharsets.UTF_8));
    byte[] hostEvtDrop =
        Frame.build(Frame.KIND_EVENT, 88, 1, 0, "dropme".getBytes(StandardCharsets.UTF_8));

    check(Loader.onDispatch(synPress) == null, "single synthetic event dropped -> null");
    checkEq(synPresses.get(), 1, "plugin handled synthetic press");

    check(Loader.onDispatch(realPress) == realPress, "single real event forwarded unchanged");

    byte[] rw = Loader.onDispatch(hostEvtRewrite);
    check(rw != null && rw != hostEvtRewrite, "frame rewritten");
    checkEq(
        new String(Frame.parse(rw).payload(), StandardCharsets.UTF_8),
        "rewritten",
        "rewritten payload");
    check(Loader.onDispatch(hostEvtDrop) == null, "dropped frame -> null");

    byte[] mixedBatch = packBatch(realPress, synPress, hostEvtRewrite, hostEvtDrop);
    byte[] filteredBatch = Loader.onDispatch(mixedBatch);
    check(filteredBatch != null && filteredBatch != mixedBatch, "mixed batch repacked");
    List<byte[]> remaining = unpackBatch(filteredBatch);
    checkEq(remaining.size(), 2, "2 of 4 items survive batch filter");
    checkBytes(remaining.get(0), realPress, "batch item 0 is realPress");
    checkEq(
        new String(Frame.parse(remaining.get(1)).payload(), StandardCharsets.UTF_8),
        "rewritten",
        "batch item 1 is rewritten frame");
    checkEq(synPresses.get(), 2, "synthetic press inside batch was intercepted");

    byte[] allDroppedBatch = packBatch(synPress, hostEvtDrop);
    check(Loader.onDispatch(allDroppedBatch) == null, "all-dropped batch -> null");

    byte[] untouchedBatch = packBatch(realPress, hostEvtKeep);
    check(
        Loader.onDispatch(untouchedBatch) == untouchedBatch,
        "untouched batch returns same reference");
  }

  private static void testApplySyntheticOpsAndReentrancyGuard() {
    registerSampleProps();
    final AtomicInteger hostApplyCount = new AtomicInteger();
    final AtomicInteger pluginOnUiOpsCount = new AtomicInteger();
    final AtomicReference<byte[]> lastApplied = new AtomicReference<byte[]>();

    final HostInvoker mockHost =
        new HostInvoker() {
          @Override
          public byte[] invokeOrig(byte[] frame) {
            return null;
          }

          @Override
          public void applyOps(byte[] ops, int length) {
            byte[] hooked = Loader.onOps(this, ops, length);
            hostApplyCount.incrementAndGet();
            lastApplied.set(Arrays.copyOf(hooked, hooked == ops ? length : hooked.length));
          }
        };

    ModPlugin reentrantPlugin =
        new ModPlugin() {
          @Override
          public byte[] onUiOps(byte[] ops, int length) {
            pluginOnUiOpsCount.incrementAndGet();
            return null;
          }
        };
    Loader.setPluginsForTests(reentrantPlugin);

    byte[] synBatch =
        UiOps.create()
            .createView(UiOps.SYNTHETIC_ID_BASE + 1, "view", Collections.<UiOps.Prop>emptyList())
            .toBytes();
    check(!Loader.applySyntheticOps(synBatch), "fails cleanly before host captured");

    byte[] realBatch = UiOps.create().requestFrame().toBytes();
    Loader.onOps(mockHost, realBatch, realBatch.length);
    check(Loader.activeHost() == mockHost, "activeHost captured from onOps");
    checkEq(pluginOnUiOpsCount.get(), 1, "real onOps invoked plugin");

    final AtomicInteger scheduledRuns = new AtomicInteger();
    Loader.setSyntheticExecutorForTests(
        new Executor() {
          @Override
          public void execute(Runnable command) {
            scheduledRuns.incrementAndGet();
            command.run();
          }
        });

    check(Loader.applySyntheticOps(synBatch), "applySyntheticOps returned true");
    checkEq(scheduledRuns.get(), 1, "dispatched via executor");
    checkEq(hostApplyCount.get(), 1, "host.applyOps executed");
    checkBytes(lastApplied.get(), synBatch, "host received exact synthetic bytes");
    checkEq(pluginOnUiOpsCount.get(), 1, "re-entrancy guard prevented plugin onUiOps callback!");

    Loader.onOps(mockHost, realBatch, realBatch.length);
    checkEq(pluginOnUiOpsCount.get(), 2, "normal onOps works after synthetic guard clears");
  }

  private static void testCinderDiagnosticsSettingsInspectorSmoke() {
    registerSampleProps();
    com.discord.loader.plugins.CinderDiagnosticsPlugin diag =
        new com.discord.loader.plugins.CinderDiagnosticsPlugin();
    Loader.setPluginsForTests(diag);
    byte[] settingsBatch =
        UiOps.create()
            .createView(200, "stack", Collections.<UiOps.Prop>emptyList())
            .createView(
                201,
                "input",
                Collections.singletonList(
                    UiOps.Prop.named("accessibilityId", UiOps.Value.ofString("settings-search"))))
            .setChildren(200, 201)
            .toBytes();

    for (int i = 0; i < 6; i++) {
      byte[] out = Loader.onOps(settingsBatch, settingsBatch.length);
      check(out == settingsBatch, "CinderDiagnosticsPlugin inspector must not mutate batch");
    }
  }

  public static void main(String[] args) {
    runTest(
        "testAll10OpcodesAnd13ValueTagsExactRoundTrip",
        new Runnable() {
          @Override
          public void run() {
            testAll10OpcodesAnd13ValueTagsExactRoundTrip();
          }
        });
    runTest(
        "testAppendOpsViaOnUiOpsAndMalformedReject",
        new Runnable() {
          @Override
          public void run() {
            testAppendOpsViaOnUiOpsAndMalformedReject();
          }
        });
    runTest(
        "testViewEventCodec",
        new Runnable() {
          @Override
          public void run() {
            testViewEventCodec();
          }
        });
    runTest(
        "testOnDispatchSingleAndBatchFiltering",
        new Runnable() {
          @Override
          public void run() {
            testOnDispatchSingleAndBatchFiltering();
          }
        });
    runTest(
        "testApplySyntheticOpsAndReentrancyGuard",
        new Runnable() {
          @Override
          public void run() {
            testApplySyntheticOpsAndReentrancyGuard();
          }
        });
    runTest(
        "testCinderDiagnosticsSettingsInspectorSmoke",
        new Runnable() {
          @Override
          public void run() {
            testCinderDiagnosticsSettingsInspectorSmoke();
          }
        });
    System.out.println("ALL " + passed + " UIOPS TESTS PASSED");
  }
}
