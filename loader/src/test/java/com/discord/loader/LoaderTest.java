package com.discord.loader;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class LoaderTest {
  private static int passed = 0;

  @SuppressWarnings("unused")
  private static final class MockGuestHost {
    private static final String[] p = new String[64];

    static {
      for (int i = 0; i < p.length; i++) p[i] = "guest.enqueue:";
      p[10] = "guest.enqueue:Updates";
      p[20] = "guest.enqueue:Updates.developerMode";
      p[30] = "guest.enqueue:Database";
    }
  }

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
              + " (expected="
              + Arrays.toString(expected)
              + ", actual="
              + Arrays.toString(actual)
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

  private static void testFrameBuildersAndParsing() {
    byte[] reqBytes =
        Frame.build(Frame.KIND_REQUEST, 60, 241, 777, "hello".getBytes(StandardCharsets.UTF_8));
    Frame f = Frame.parse(reqBytes);
    check(f != null, "parsed valid request frame");
    check(f.isRequest(), "isRequest");
    check(!f.isResponse(), "!isResponse");
    checkEq(f.version(), 1, "version");
    checkEq(f.moduleId(), 60, "moduleId");
    checkEq(f.methodId(), 241, "methodId");
    checkEq(f.callId(), 777, "callId");
    checkEq(f.payloadLength(), 5, "payloadLength");
    checkEq(f.trailingLength(), 0, "trailingLength");
    checkBytes(f.payload(), "hello".getBytes(StandardCharsets.UTF_8), "payload");

    byte[] withTrailing = Arrays.copyOf(reqBytes, reqBytes.length + 3);
    withTrailing[1] = 0x5A;
    withTrailing[reqBytes.length] = (byte) 0xDE;
    withTrailing[reqBytes.length + 1] = (byte) 0xAD;
    withTrailing[reqBytes.length + 2] = (byte) 0xBE;
    Frame ft = Frame.parse(withTrailing);
    check(ft != null, "parsed frame with trailing bytes");
    checkEq(ft.trailingLength(), 3, "trailingLength=3");
    checkBytes(ft.trailing(), new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE}, "trailing()");
    checkBytes(f.trailing(), new byte[0], "trailing() empty when absent");

    byte[] replacedTrailing = ft.withTrailing(new byte[]{1, 2, 3, 4});
    Frame rt = Frame.parse(replacedTrailing);
    check(rt != null, "withTrailing frame parses");
    checkEq(rt.payloadLength(), 5, "withTrailing preserves payload length");
    checkEq(rt.trailingLength(), 4, "withTrailing replaces trailing length");
    checkBytes(rt.trailing(), new byte[]{1, 2, 3, 4}, "withTrailing bytes");
    checkEq(rt.raw()[1], (byte) 0x5A, "withTrailing preserves flags byte");

    byte[] both = f.withPayloadAndTrailing("abc".getBytes(StandardCharsets.UTF_8), new byte[]{9, 8});
    Frame bt = Frame.parse(both);
    check(bt != null, "withPayloadAndTrailing frame parses");
    checkBytes(bt.payload(), "abc".getBytes(StandardCharsets.UTF_8), "withPayloadAndTrailing payload");
    checkBytes(bt.trailing(), new byte[]{9, 8}, "withPayloadAndTrailing trailing");

    byte[] mutated = ft.withPayload("longer-payload!".getBytes(StandardCharsets.UTF_8));
    Frame fm = Frame.parse(mutated);
    check(fm != null, "parsed mutated frame");
    checkEq(fm.kind(), Frame.KIND_REQUEST, "withPayload preserves kind");
    checkEq(fm.raw()[1], (byte) 0x5A, "withPayload preserves flags byte");
    checkEq(fm.moduleId(), 60, "withPayload preserves moduleId");
    checkEq(fm.methodId(), 241, "withPayload preserves methodId");
    checkEq(fm.callId(), 777, "withPayload preserves callId");
    checkEq(fm.payloadLength(), 15, "withPayload updates payloadLength");
    checkEq(fm.trailingLength(), 3, "withPayload preserves trailing length");
    checkBytes(fm.payload(), "longer-payload!".getBytes(StandardCharsets.UTF_8), "mutated payload");
    checkEq(mutated[mutated.length - 3], (byte) 0xDE, "trailing byte 0");
    checkEq(mutated[mutated.length - 1], (byte) 0xBE, "trailing byte 2");

    byte[] replyBytes = f.reply("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
    Frame rep = Frame.parse(replyBytes);
    check(rep != null, "parsed reply frame");
    check(rep.isResponse(), "reply isResponse");
    checkEq(rep.kind(), Frame.KIND_RESPONSE, "reply kind=2");
    checkEq(rep.moduleId(), 60, "reply moduleId");
    checkEq(rep.methodId(), 241, "reply methodId");
    checkEq(rep.callId(), 777, "reply callId");
    checkBytes(rep.payload(), "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), "reply payload");

    byte[] uiOps = new byte[24];
    uiOps[4] = 1;
    check(Frame.parse(uiOps) == null, "UI-ops frame must not parse as host call");
    check(Frame.parse(new byte[15]) == null, "short frame must return null");
  }

  private static void testBridgeRegistry() {
    check(!BridgeRegistry.isReady(), "empty initially");
    checkEq(BridgeRegistry.size(), 0, "size=0");
    checkEq(BridgeRegistry.moduleId("Updates"), -1, "unresolved module");

    String[] table = new String[300];
    Arrays.fill(table, "");
    table[16] = "ClientSwitch";
    table[22] = "Database";
    table[60] = "Updates";
    table[241] = "Updates.developerMode";
    table[294] = "@access";
    table[295] = "@ws";

    BridgeRegistry.register(table);
    check(BridgeRegistry.isReady(), "ready after register");
    checkEq(BridgeRegistry.size(), 6, "6 non-empty symbols");
    checkEq(BridgeRegistry.moduleId("Updates"), 60, "Updates=60");
    checkEq(BridgeRegistry.methodId("Updates", "developerMode"), 241, "Updates.developerMode=241");
    checkEq(BridgeRegistry.nameOf(241), "Updates.developerMode", "nameOf(241)");
    checkEq(BridgeRegistry.methodName(241), "developerMode", "methodName(241)");
    checkEq(BridgeRegistry.nameOf(294), "@access", "preserves @ prefix");
    check(BridgeRegistry.nameOf(1) == null, "empty slot returns null");

    Frame f = Frame.parse(Frame.build(Frame.KIND_REQUEST, 60, 241, 1, null));
    check(BridgeRegistry.matchesModule(f, "Updates"), "matchesModule");
    check(BridgeRegistry.matches(f, "Updates", "developerMode"), "matches");
    check(!BridgeRegistry.matches(f, "Database", "developerMode"), "!matches wrong module");
    checkEq(BridgeRegistry.describe(f), "Updates.developerMode", "describe resolved");

    Frame event = Frame.parse(Frame.build(Frame.KIND_EVENT, 0, 295, 0, null));
    check(event.isEvent(), "event frame isEvent");
    check(BridgeRegistry.matchesEvent(event, "@ws"), "matchesEvent @ws");
    check(!BridgeRegistry.matchesEvent(event, "@http"), "matchesEvent rejects other topic");
    check(!BridgeRegistry.matchesEvent(f, "@ws"), "matchesEvent rejects non-event frame");

    Frame unknown = Frame.parse(Frame.build(Frame.KIND_REQUEST, 999, 888, 1, null));
    checkEq(BridgeRegistry.describe(unknown), "m999.888", "describe unresolved");

    BridgeRegistry.reset();
    check(
        BridgeRegistry.discoverFromGuestHost(MockGuestHost.class), "reflection fallback succeeded");
    checkEq(BridgeRegistry.moduleId("Updates"), 10, "fallback Updates=10");
    checkEq(
        BridgeRegistry.methodId("Updates", "developerMode"),
        20,
        "fallback Updates.developerMode=20");
    checkEq(BridgeRegistry.moduleId("Database"), 30, "fallback Database=30");
  }

  private static void testMultiPluginChainingAndReParse() {
    final AtomicReference<String> seenByPlugin2Req = new AtomicReference<String>();
    final AtomicReference<String> seenByHost = new AtomicReference<String>();
    final AtomicReference<String> seenByPlugin2Resp = new AtomicReference<String>();
    final AtomicReference<Integer> correlatedCallId = new AtomicReference<Integer>();

    ModPlugin p1 =
        new ModPlugin() {
          @Override
          public String id() {
            return "p1";
          }

          @Override
          public byte[] onHostCall(Frame frame) {
            return frame.withPayload("step1".getBytes(StandardCharsets.UTF_8));
          }

          @Override
          public byte[] onHostResponse(Frame request, Frame response) {
            return response.withPayload("resp1".getBytes(StandardCharsets.UTF_8));
          }
        };

    ModPlugin p2 =
        new ModPlugin() {
          @Override
          public String id() {
            return "p2";
          }

          @Override
          public byte[] onHostCall(Frame frame) {
            seenByPlugin2Req.set(
                new String(frame.payload(), StandardCharsets.UTF_8) + ":" + frame.payloadLength());
            return frame.withPayload("step1+step2".getBytes(StandardCharsets.UTF_8));
          }

          @Override
          public byte[] onHostResponse(Frame request, Frame response) {
            correlatedCallId.set(Integer.valueOf(request.callId()));
            seenByPlugin2Resp.set(
                new String(response.payload(), StandardCharsets.UTF_8)
                    + ":"
                    + response.payloadLength());
            return response.withPayload("resp1+resp2".getBytes(StandardCharsets.UTF_8));
          }
        };

    Loader.setPluginsForTests(p1, p2);

    byte[] req =
        Frame.build(Frame.KIND_REQUEST, 60, 241, 42, "orig".getBytes(StandardCharsets.UTF_8));
    byte[] out =
        Loader.dispatchInvoke(
            new HostInvoker() {
              @Override
              public byte[] invokeOrig(byte[] frame) {
                Frame f = Frame.parse(frame);
                seenByHost.set(new String(f.payload(), StandardCharsets.UTF_8));
                return f.reply("host-resp".getBytes(StandardCharsets.UTF_8));
              }
            },
            req);

    checkEq(seenByPlugin2Req.get(), "step1:5", "plugin 2 sees re-parsed Frame from plugin 1");
    checkEq(seenByHost.get(), "step1+step2", "host receives final chained request");
    checkEq(correlatedCallId.get(), Integer.valueOf(42), "correlated request callId");
    checkEq(
        seenByPlugin2Resp.get(), "resp1:5", "plugin 2 sees re-parsed response Frame from plugin 1");

    Frame finalResp = Frame.parse(out);
    check(finalResp != null && finalResp.isResponse(), "final output is valid response");
    checkEq(
        new String(finalResp.payload(), StandardCharsets.UTF_8),
        "resp1+resp2",
        "final response payload");
  }

  private static void testShortCircuitVirtualModuleAndDrop() {
    final AtomicInteger hostCalls = new AtomicInteger();
    final AtomicInteger downstreamReqCalls = new AtomicInteger();
    final AtomicReference<String> observerResp = new AtomicReference<String>();

    ModPlugin virtualMod =
        new ModPlugin() {
          @Override
          public String id() {
            return "virtual";
          }

          @Override
          public byte[] onHostCall(Frame frame) {
            if (frame.moduleId() == 9999) {
              return frame.reply("{\"virtual\":true}".getBytes(StandardCharsets.UTF_8));
            }
            if (frame.moduleId() == 9998) {
              return Frame.DROP;
            }
            return null;
          }
        };

    ModPlugin observer =
        new ModPlugin() {
          @Override
          public String id() {
            return "observer";
          }

          @Override
          public byte[] onHostCall(Frame frame) {
            downstreamReqCalls.incrementAndGet();
            return null;
          }

          @Override
          public byte[] onHostResponse(Frame request, Frame response) {
            observerResp.set(
                request.callId() + ":" + new String(response.payload(), StandardCharsets.UTF_8));
            return null;
          }
        };

    Loader.setPluginsForTests(virtualMod, observer);
    HostInvoker host =
        new HostInvoker() {
          @Override
          public byte[] invokeOrig(byte[] frame) {
            hostCalls.incrementAndGet();
            return Frame.parse(frame).reply("real".getBytes(StandardCharsets.UTF_8));
          }
        };

    byte[] vReq = Frame.build(Frame.KIND_REQUEST, 9999, 1, 101, null);
    byte[] vOut = Loader.dispatchInvoke(host, vReq);
    checkEq(hostCalls.get(), 0, "short-circuit skipped host.invokeOrig");
    checkEq(downstreamReqCalls.get(), 0, "short-circuit skipped downstream onHostCall");
    checkEq(
        observerResp.get(), "101:{\"virtual\":true}", "observer still sees correlated response");
    checkEq(
        new String(Frame.parse(vOut).payload(), StandardCharsets.UTF_8),
        "{\"virtual\":true}",
        "synthetic reply");

    byte[] dReq = Frame.build(Frame.KIND_REQUEST, 9998, 1, 102, null);
    byte[] dOut = Loader.dispatchInvoke(host, dReq);
    checkEq(hostCalls.get(), 0, "DROP skipped host.invokeOrig");
    check(dOut == null, "DROP returned null");

    byte[] uiOps = new byte[] {0, 0, 0, 0, 1, 2, 3, 4};
    final AtomicInteger uiOpsForwarded = new AtomicInteger();
    Loader.dispatchInvoke(
        new HostInvoker() {
          @Override
          public byte[] invokeOrig(byte[] frame) {
            uiOpsForwarded.incrementAndGet();
            return null;
          }
        },
        uiOps);
    checkEq(uiOpsForwarded.get(), 1, "UI-ops frame bypassed host-call hooks");
    checkEq(downstreamReqCalls.get(), 0, "UI-ops frame did not fire onHostCall");
  }

  private static void testUiOpsResizingAndDefaultMethods() {

    ModPlugin minimal = new ModPlugin() {};
    checkEq(minimal.version(), "0.1.0", "default version");

    final AtomicInteger p2SawLen = new AtomicInteger();
    ModPlugin resizer =
        new ModPlugin() {
          @Override
          public byte[] onUiOps(byte[] ops, int length) {
            byte[] grown = new byte[length + 4];
            System.arraycopy(ops, 0, grown, 0, length);
            grown[length] = 9;
            return grown;
          }
        };
    ModPlugin inspector =
        new ModPlugin() {
          @Override
          public byte[] onUiOps(byte[] ops, int length) {
            p2SawLen.set(length);
            return null;
          }
        };

    Loader.setPluginsForTests(minimal);
    byte[] orig = new byte[16];
    byte[] res1 = Loader.onOps(orig, 6);
    check(res1 == orig, "unchanged onUiOps must return exact same reference");

    Loader.setPluginsForTests(resizer, inspector);
    byte[] res2 = Loader.onOps(orig, 6);
    check(res2 != orig, "resized onUiOps returns new array");
    checkEq(res2.length, 10, "new array length = 6 + 4");
    checkEq(p2SawLen.get(), 10, "downstream plugin saw new length 10");
    checkEq(res2[6], (byte) 9, "appended op byte present");
  }

  private static void testExceptionIsolationAndConcurrency() throws Exception {
    ModPlugin buggy =
        new ModPlugin() {
          @Override
          public byte[] onHostCall(Frame frame) {
            throw new RuntimeException("boom req");
          }

          @Override
          public byte[] onHostResponse(Frame req, Frame resp) {
            throw new RuntimeException("boom resp");
          }

          @Override
          public byte[] onUiOps(byte[] ops, int len) {
            throw new RuntimeException("boom ops");
          }

          @Override
          public void onLog(int level, String msg) {
            throw new RuntimeException("boom log");
          }
        };
    ModPlugin checker =
        new ModPlugin() {
          @Override
          public byte[] onHostResponse(Frame req, Frame resp) {
            if (req.callId() != resp.callId() || req.moduleId() != resp.moduleId()) {
              throw new AssertionError("correlation mismatch!");
            }
            return resp.withPayload(("ok-" + req.callId()).getBytes(StandardCharsets.UTF_8));
          }
        };

    Loader.setPluginsForTests(buggy, checker);
    Loader.onLog(1, "hello");
    byte[] opsIn = new byte[8];
    check(Loader.onOps(opsIn, 4) == opsIn, "exception in onUiOps isolated");

    int threads = 8;
    int iters = 200;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    final CountDownLatch start = new CountDownLatch(1);
    Future<?>[] futures = new Future<?>[threads];
    for (int t = 0; t < threads; t++) {
      final int tid = t;
      futures[t] =
          pool.submit(
              new Runnable() {
                @Override
                public void run() {
                  try {
                    start.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  for (int i = 0; i < iters; i++) {
                    final int callId = tid * 10000 + i;
                    final int modId = 10 + tid;
                    byte[] req = Frame.build(Frame.KIND_REQUEST, modId, 100 + tid, callId, null);
                    byte[] resp =
                        Loader.dispatchInvoke(
                            new HostInvoker() {
                              @Override
                              public byte[] invokeOrig(byte[] frame) {
                                return Frame.parse(frame)
                                    .reply("raw".getBytes(StandardCharsets.UTF_8));
                              }
                            },
                            req);
                    Frame rf = Frame.parse(resp);
                    checkEq(rf.callId(), callId, "concurrent callId");
                    checkEq(
                        new String(rf.payload(), StandardCharsets.UTF_8),
                        "ok-" + callId,
                        "concurrent payload");
                  }
                }
              });
    }
    start.countDown();
    for (Future<?> f : futures) {
      f.get();
    }
    pool.shutdown();
  }

  public static void main(String[] args) throws Exception {
    runTest(
        "testFrameBuildersAndParsing",
        new Runnable() {
          @Override
          public void run() {
            testFrameBuildersAndParsing();
          }
        });
    runTest(
        "testBridgeRegistry",
        new Runnable() {
          @Override
          public void run() {
            testBridgeRegistry();
          }
        });
    runTest(
        "testMultiPluginChainingAndReParse",
        new Runnable() {
          @Override
          public void run() {
            testMultiPluginChainingAndReParse();
          }
        });
    runTest(
        "testShortCircuitVirtualModuleAndDrop",
        new Runnable() {
          @Override
          public void run() {
            testShortCircuitVirtualModuleAndDrop();
          }
        });
    runTest(
        "testUiOpsResizingAndDefaultMethods",
        new Runnable() {
          @Override
          public void run() {
            testUiOpsResizingAndDefaultMethods();
          }
        });
    runTest(
        "testExceptionIsolationAndConcurrency",
        new Runnable() {
          @Override
          public void run() {
            try {
              testExceptionIsolationAndConcurrency();
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }
        });
    System.out.println("ALL " + passed + " JAVA TESTS PASSED");
  }
}
