package com.discord.loader.plugins

import com.discord.loader.BridgeRegistry
import com.discord.loader.CinderNative
import com.discord.loader.CinderSettings
import com.discord.loader.Frame
import com.discord.loader.HostInvoker
import com.discord.loader.Loader
import com.discord.loader.ManaUi
import com.discord.loader.ModPlugin
import com.discord.loader.UiOps
import com.discord.loader.mana.ManaIds
import com.discord.loader.mana.ManaMotion
import com.discord.loader.mana.ThemeEngine
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object BuiltinPluginsTest {
    private var passed = 0

    private fun checkCond(
        cond: Boolean,
        msg: String,
    ) {
        if (!cond) throw AssertionError("FAIL: $msg")
    }

    private fun checkEq(
        actual: Any?,
        expected: Any?,
        msg: String,
    ) {
        if (actual != expected) {
            throw AssertionError("FAIL: $msg (expected=$expected, actual=$actual)")
        }
    }

    private fun registerSampleProps() {
        UiOps.registerPropNames(
            arrayOf(
                "",
                "direction",
                "padding",
                "margin",
                "width",
                "height",
                "minHeight",
                "minWidth",
                "flex",
                "gap",
                "align",
                "justify",
                "background",
                "activeBackground",
                "color",
                "fontSize",
                "fontWeight",
                "lineHeight",
                "lineHeightExact",
                "text",
                "textAlign",
                "radius",
                "radiusTopLeft",
                "radiusTopRight",
                "radiusBottomLeft",
                "radiusBottomRight",
                "borderColor",
                "borderWidth",
                "clip",
                "pressFeedback",
                "role",
                "accessibilityLabel",
                "accessibilityId",
                "accessibilityChecked",
                "accessibilityHidden",
                "accessible",
                "on",
                "press",
                "longPress",
                "navigate",
                "dismiss",
                "change",
                "changeText",
                "focus",
                "blur",
                "submit",
                "presentation",
                "shrink",
                "mana",
                "appeared",
                "exitEnd",
                "animate",
                "fullScreenBack",
                "stackHeader",
                "absolute",
                "paddingBox",
                "gravity",
                "shadow",
                "hitSlop",
                "index",
                "translateX",
                "translateY",
                "opacity",
                "scaleFactor",
                "uri",
                "tint",
                "scale",
                "enabled",
                "maxLines",
                "worklet",
                "value",
                "placeholder",
                "placeholderColor",
                "keyboardShouldPersistTaps",
                "fullscreen",
                "dismissible",
                "includeFontPadding",
            ),
        )
    }

    private fun runTest(
        name: String,
        fn: () -> Unit,
    ) {
        Loader.resetForTests()
        registerSampleProps()
        fn()
        passed++
        println("  PASS $name")
    }

    private class RecordingHost : HostInvoker {
        val invokeCalls = AtomicInteger()
        var lastInvokeBytes: ByteArray? = null
        val appliedBatches = ArrayList<UiOps>()

        override fun invokeOrig(frame: ByteArray?): ByteArray? {
            invokeCalls.incrementAndGet()
            lastInvokeBytes = frame
            if (frame == null) return null
            val req = Frame.parse(frame) ?: return null
            return Frame.build(Frame.KIND_RESPONSE, req.moduleId(), req.methodId(), req.callId(), byteArrayOf(0x01))
        }

        override fun applyOps(
            ops: ByteArray,
            length: Int,
        ) {
            val parsed = UiOps.parse(ops, length)
            if (parsed != null) appliedBatches.add(parsed)
        }
    }

    private fun installSyncHost(): RecordingHost {
        val host = RecordingHost()
        Loader.registerHost(host)
        Loader.setSyntheticExecutorForTests(Executor { r -> r.run() })
        return host
    }

    private fun pressEvent(viewId: Int): ByteArray = UiOps.ViewEvent.buildViewEvent(viewId, UiOps.propId("press"), null)

    private fun buildSettingsOverviewBatch(): ByteArray {
        val b = UiOps.create()
        val p = { k: String, v: UiOps.Value -> Collections.singletonList(UiOps.Prop.named(k, v)) }
        b.createView(100, "view", p("accessibilityId", UiOps.Value.ofString("settings-phone")))
        b.createView(101, "scroll", Collections.emptyList())
        b.createView(102, "view", p("direction", UiOps.Value.ofString("column")))
        b.createView(103, "input", p("accessibilityId", UiOps.Value.ofString("user_settings_search_input")))
        b.createView(104, "view", p("accessibilityId", UiOps.Value.ofString("settings.row.account")))
        b.setChildren(102, 103, 104)
        b.setChildren(101, 102)
        b.setChildren(100, 101)
        b.updateView(5, p("index", UiOps.Value.ofInt(2)))
        b.setChildren(5, 6, 997, 100)
        return b.toBytes()
    }

    private class FakeNativeBackend(
        @Volatile var baseAddr: Long = 0L,
        @Volatile var buildId: String? = "0123456789abcdef0123456789abcdef01234567",
        @Volatile var flagValue: Int = 1,
    ) : CinderNative.Backend {
        val writeCount = AtomicInteger()
        var lastWrittenVaddr: Long = -1L

        override fun getHostBase(): Long = baseAddr

        override fun getHostBuildId(): String? = buildId

        override fun writeHostFlag(
            vaddr: Long,
            value: Int,
        ): Boolean {
            if (baseAddr == 0L) return false
            writeCount.incrementAndGet()
            lastWrittenVaddr = vaddr
            flagValue = value and 0xFF
            return true
        }

        override fun readHostFlag(vaddr: Long): Int = if (baseAddr == 0L) -1 else flagValue
    }

    private fun testCinderNativeManifestDeferredFlushAndBuildDrift() {
        val sampleJson =
            """
            {
              "version": 1,
              "build_id": "AABBCCDD00112233445566778899AABBCCDDEEFF",
              "patches": [
                {"tag": "release.access", "offset": "0x2719d00", "orig": "1f2003d5", "patched": "20008052"},
                {"tag": "staff.devtools", "offset": "0x1d57784", "orig": "ff0301d1fd7b02a9f31b00f9", "patched": "681102f000414c39c0035fd6"}
              ],
              "runtime_flags": {
                "staff.devtools": {"vaddr": "0x5f86310", "default": 1}
              }
            }
            """.trimIndent()

        val manifest = CinderNative.parseManifestJson(sampleJson)
        checkCond(manifest.loaded, "manifest parsed")
        checkEq(manifest.buildId, "aabbccdd00112233445566778899aabbccddeeff", "build_id lowercased")
        checkEq(manifest.patchTags.size, 2, "2 patch tags parsed")
        checkEq(manifest.patchTags[0], "release.access", "first patch tag")
        checkEq(manifest.patchTags[1], "staff.devtools", "second patch tag")
        checkEq(manifest.flagVaddr("staff.devtools"), 0x5f86310L, "runtime_flags hex vaddr parsed")
        checkEq(manifest.runtimeFlagDefaults["staff.devtools"], 1, "runtime_flags default parsed")

        val backend =
            FakeNativeBackend(
                baseAddr = 0L,
                buildId = "aabbccdd00112233445566778899aabbccddeeff",
                flagValue = 1,
            )
        CinderNative.setManifestForTests(manifest)
        CinderNative.setBackendForTests(backend)

        val staff = StaffDevToolsPlugin()
        Loader.setPluginsForTests(staff)

        Loader.setPluginEnabled(staff, false)
        checkCond(CinderNative.hasPendingStaffDevTools(), "write deferred while baseAddr==0")
        checkEq(backend.writeCount.get(), 0, "no native write while host unmapped")
        checkEq(StaffDevToolsPlugin.formatNativeFlagStatus(), "Pending Host Load", "status reflects unmapped host")

        backend.baseAddr = 0x7000_0000_00L
        val host = installSyncHost()
        val dummyReq = Frame.build(Frame.KIND_REQUEST, 1, 1, 42, byteArrayOf(0x00))
        Loader.dispatchInvoke(host, dummyReq)

        checkCond(!CinderNative.hasPendingStaffDevTools(), "pending flag flushed on first dispatchInvoke")
        checkEq(backend.writeCount.get(), 1, "native write executed once")
        checkEq(backend.lastWrittenVaddr, 0x5f86310L, "wrote exact manifest vaddr")
        checkEq(backend.flagValue, 0, "deferred false wrote 0x0 to .bss flag")
        checkEq(CinderNative.readStaffDevToolsFlag(), 0, "readStaffDevToolsFlag returns 0")
        checkEq(StaffDevToolsPlugin.formatNativeFlagStatus(), "OFF (0x0)", "status reports OFF (0x0)")

        Loader.setPluginEnabled(staff, true)
        checkEq(backend.writeCount.get(), 2, "live toggle wrote immediately")
        checkEq(backend.flagValue, 1, "live toggle wrote 0x1")
        checkEq(StaffDevToolsPlugin.formatNativeFlagStatus(), "ON (0x1)", "status reports ON (0x1)")

        backend.buildId = "deadbeef00000000000000000000000000000000"
        checkCond(!CinderNative.verifyHostBuildId(), "Build ID drift detected")
        val okAfterDrift = CinderNative.setStaffDevTools(false)
        checkCond(!okAfterDrift, "setStaffDevTools refuses write on Build ID drift")
        checkEq(backend.writeCount.get(), 2, "no write performed after Build ID drift")
        checkEq(backend.flagValue, 1, ".bss byte untouched after Build ID drift")
        checkEq(CinderNative.readStaffDevToolsFlag(), -1, "readStaffDevToolsFlag returns -1 on drift")
        checkEq(StaffDevToolsPlugin.formatNativeFlagStatus(), "Blocked (Build Drift)", "status reports Build Drift")
    }

    private fun testCoreLockedOnAndPluginDispatchGating() {
        val host = installSyncHost()
        val cs = CinderSettings()
        val release = PhoenixAccessPlugin()
        val diag = CinderDiagnosticsPlugin()
        val staff = StaffDevToolsPlugin()

        val gatedCalls = AtomicInteger()
        val gatedOps = AtomicInteger()
        val gatedDispatches = AtomicInteger()
        val gatedLogs = AtomicInteger()
        val gatedPlugin =
            object : ModPlugin {
                override fun id(): String = "gated-test-plugin"

                override fun version(): String = "1.0.0"

                override fun isRequired(): Boolean = false

                override fun defaultEnabled(): Boolean = true

                override fun onHostCall(frame: Frame): ByteArray? {
                    gatedCalls.incrementAndGet()
                    return null
                }

                override fun onUiOps(
                    ops: ByteArray?,
                    length: Int,
                ): ByteArray? {
                    gatedOps.incrementAndGet()
                    return null
                }

                override fun onDispatch(frame: Frame): ByteArray? {
                    gatedDispatches.incrementAndGet()
                    return null
                }

                override fun onLog(
                    level: Int,
                    message: String?,
                ) {
                    gatedLogs.incrementAndGet()
                }
            }

        Loader.setPluginsForTests(cs, release, diag, staff, gatedPlugin)

        checkCond(cs.isRequired(), "CinderSettings is Core")
        checkCond(release.isRequired(), "PhoenixAccessPlugin is Core")
        checkCond(diag.isRequired(), "CinderDiagnosticsPlugin is Core")
        checkCond(!Loader.setPluginEnabled(cs, false), "refused to disable CinderSettings")
        checkCond(!Loader.setPluginEnabled(release, false), "refused to disable PhoenixAccessPlugin")
        checkCond(!Loader.setPluginEnabled(diag, false), "refused to disable CinderDiagnosticsPlugin")
        checkCond(Loader.isPluginEnabled(release), "PhoenixAccessPlugin remains enabled")
        checkCond(Loader.isPluginEnabled(diag), "CinderDiagnosticsPlugin remains enabled")

        val req = Frame.build(Frame.KIND_REQUEST, 10, 2, 1, byteArrayOf(0x01))
        val ops = UiOps.create().deleteViews(999).toBytes()
        val ev = Frame.build(Frame.KIND_EVENT, 10, 2, 1, byteArrayOf(0x02))
        Loader.dispatchInvoke(host, req)
        Loader.onOps(ops, ops.size)
        Loader.onDispatch(ev)
        Loader.onLog(1, "hello")
        checkEq(gatedCalls.get(), 1, "enabled plugin got onHostCall")
        checkEq(gatedOps.get(), 1, "enabled plugin got onUiOps")
        checkEq(gatedDispatches.get(), 1, "enabled plugin got onDispatch")
        checkEq(gatedLogs.get(), 1, "enabled plugin got onLog")
        checkEq(diag.hostCallCount(), 1, "CinderDiagnosticsPlugin tracked host call")
        checkEq(diag.uiOpBatchCount(), 1, "CinderDiagnosticsPlugin tracked ui op batch")
        checkEq(diag.dispatchCount(), 1, "CinderDiagnosticsPlugin tracked dispatch")

        checkCond(Loader.setPluginEnabled(gatedPlugin, false), "disabled togglable plugin")
        checkCond(!Loader.isPluginEnabled(gatedPlugin), "isPluginEnabled is false")
        Loader.dispatchInvoke(host, req)
        Loader.onOps(ops, ops.size)
        Loader.onDispatch(ev)
        Loader.onLog(1, "world")
        checkEq(gatedCalls.get(), 1, "disabled plugin skipped in dispatchInvoke")
        checkEq(gatedOps.get(), 1, "disabled plugin skipped in onOps")
        checkEq(gatedDispatches.get(), 1, "disabled plugin skipped in onDispatch")
        checkEq(gatedLogs.get(), 1, "disabled plugin skipped in onLog")
        checkEq(diag.hostCallCount(), 2, "Core CinderDiagnosticsPlugin still receives calls")
    }

    // ---- 3b. TextReplacePlugin: outgoing message rewrite ----

    private fun testTextReplacePlugin() {
        BridgeRegistry.register(arrayOf("", "Networking", "Networking.request"))
        val modNet = BridgeRegistry.moduleId("Networking")
        val methReq = BridgeRegistry.methodId("Networking", "request")
        val host = installSyncHost()
        val plugin = TextReplacePlugin()
        Loader.setPluginsForTests(plugin)
        plugin.onLoad(null)

        // Real Record layout: tag 0x08, i32 fieldCount, then (u16 id, tag 0x06, i32 len, utf8) strings.
        fun record(method: String, url: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            fun le(n: Int, w: Int) { for (k in 0 until w) out.write((n shr (8 * k)) and 0xff) }
            out.write(0x08); le(2, 4)
            for ((id, v) in listOf(1 to method, 2 to url)) {
                val b = v.toByteArray(StandardCharsets.UTF_8)
                le(id, 2); out.write(0x06); le(b.size, 4); out.write(b)
            }
            return out.toByteArray()
        }
        fun send(url: String, body: String): Frame? {
            val base = Frame.build(Frame.KIND_REQUEST, modNet, methReq, 7, record("POST", url))
            val raw = Frame.parse(base)!!.withTrailing(body.toByteArray(StandardCharsets.UTF_8))
            Loader.dispatchInvoke(host, raw)
            return Frame.parse(host.lastInvokeBytes)
        }
        fun bodyOf(f: Frame?) = String(f!!.trailing(), StandardCharsets.UTF_8)
        val url = "https://discord.com/api/v9/channels/123456/messages"

        // parsing: skips junk/empty-find lines, trims, allows empty replacement
        val parsed = TextReplacePlugin.parseRules("teh => the\nnonsense\n => x\nrm =>\n")
        checkEq(parsed.map { it.find + "|" + it.replace }, listOf("teh|the", "rm|"), "rule parsing")

        // no rules -> untouched
        val orig = "{\"content\":\"teh cat\"}"
        checkEq(bodyOf(send(url, orig)), orig, "no rules: pass-through")

        plugin.setRulesText("teh => the\nthe cat => the dog")
        checkEq(bodyOf(send(url, orig)), "{\"content\":\"the dog\"}", "rules applied sequentially")

        // escapes: quote, newline, emoji (surrogate pair escape + raw), other fields untouched
        val body = "{\"mobile_network_type\":\"teh\",\"content\":\"teh \\\"q\\\"\\n\\ud83d\\ude00 \ud83d\ude00\",\"nonce\":\"1\",\"flags\":0}"
        val out = bodyOf(send(url, body))
        checkEq(
            out,
            "{\"mobile_network_type\":\"teh\",\"content\":\"the \\\"q\\\"\\n\ud83d\ude00 \ud83d\ude00\",\"nonce\":\"1\",\"flags\":0}",
            "escapes round-trip; nested/other fields untouched ($out)",
        )

        // content nested deeper is not top-level -> ignored
        val nested = "{\"embed\":{\"content\":\"teh\"}}"
        checkEq(bodyOf(send(url, nested)), nested, "nested content ignored")

        // edits (PATCH path) are rewritten; ack / reactions / other hosts are not
        checkEq(bodyOf(send("$url/987654", orig)), "{\"content\":\"the dog\"}", "edit path rewritten")
        checkEq(bodyOf(send("$url/987654/ack", orig)), orig, "ack path untouched")
        checkEq(bodyOf(send("https://discord.com/api/v9/channels/1/threads", orig)), orig, "non-message path untouched")

        // malformed body -> pass-through, no throw
        checkEq(bodyOf(send(url, "{\"content\":\"teh")), "{\"content\":\"teh", "unterminated string pass-through")
        checkEq(bodyOf(send(url, "{\"content\":null}")), "{\"content\":null}", "null content pass-through")
    }

    private fun testNoTelemetryPluginInterceptionAndSafety() {
        BridgeRegistry.register(
            arrayOf(
                "",
                "Networking",
                "Networking.request",
                "Networking.upload",
                "CrashReporter",
                "CrashReporter.report",
                "CrashReports",
                "CrashReports.submit",
                "TelemetryRing",
                "TelemetryRing.setTouchLogging",
                "Tracking",
                "Tracking.request",
            ),
        )
        val modNet = BridgeRegistry.moduleId("Networking")
        val methReq = BridgeRegistry.methodId("Networking", "request")
        val methUp = BridgeRegistry.methodId("Networking", "upload")
        val modCr1 = BridgeRegistry.moduleId("CrashReporter")
        val methCr1 = BridgeRegistry.methodId("CrashReporter", "report")
        val modCr2 = BridgeRegistry.moduleId("CrashReports")
        val methCr2 = BridgeRegistry.methodId("CrashReports", "submit")
        val modTr = BridgeRegistry.moduleId("TelemetryRing")
        val methTr = BridgeRegistry.methodId("TelemetryRing", "setTouchLogging")
        val modTk = BridgeRegistry.moduleId("Tracking")
        val methTk = BridgeRegistry.methodId("Tracking", "request")

        val host = installSyncHost()
        val noTel = NoTelemetryPlugin()
        Loader.setPluginsForTests(noTel)
        noTel.onLoad(null)

        fun frameWithTrailing(
            mod: Int,
            meth: Int,
            callId: Int,
            payload: ByteArray,
            trailing: ByteArray,
        ): ByteArray {
            val base = Frame.build(Frame.KIND_REQUEST, mod, meth, callId, payload)
            val combined = ByteArray(base.size + trailing.size)
            System.arraycopy(base, 0, combined, 0, base.size)
            System.arraycopy(trailing, 0, combined, base.size, trailing.size)
            return combined
        }

        val sciencePayload = "\u0001POST\u0000https://discord.com/api/v9/science?foo=1\u0000".toByteArray(StandardCharsets.UTF_8)
        val scienceTrailing = "{\"events\":[]}".toByteArray(StandardCharsets.UTF_8)
        val scienceFrame = frameWithTrailing(modNet, methReq, 10, sciencePayload, scienceTrailing)
        Loader.dispatchInvoke(host, scienceFrame)
        checkEq(noTel.blockedScienceCount(), 1, "blocked /science counted")
        checkEq(host.invokeCalls.get(), 1, "Networking.request forwarded rewritten frame to OkHttp for clean async rejection")
        val forwardedScience = Frame.parse(host.lastInvokeBytes)!!
        checkEq(forwardedScience.payloadLength(), sciencePayload.size, "payload byte length preserved identically")
        checkEq(forwardedScience.trailingLength(), scienceTrailing.size, "trailing POST body length preserved")
        val rewrittenScienceStr = String(forwardedScience.payload(), StandardCharsets.UTF_8)
        checkCond(
            rewrittenScienceStr.contains("cinder::discord.com/api/v9/science?foo=1"),
            "https:// rewritten in-place to cinder:: ($rewrittenScienceStr)",
        )
        checkCond(!rewrittenScienceStr.contains("https://"), "original https:// removed")

        val errmonPayload = "url=http://release-errmon.disabled.tools/api/v1/envelope".toByteArray(StandardCharsets.UTF_8)
        val errmonFrame = Frame.build(Frame.KIND_REQUEST, modNet, methUp, 11, errmonPayload)
        Loader.dispatchInvoke(host, errmonFrame)
        checkEq(noTel.blockedErrmonCount(), 1, "blocked errmon counted")
        val forwardedErrmon = Frame.parse(host.lastInvokeBytes)!!
        checkEq(forwardedErrmon.payloadLength(), errmonPayload.size, "http:// rewrite preserves exact payload length")
        val rewrittenErrmonStr = String(forwardedErrmon.payload(), StandardCharsets.UTF_8)
        checkCond(
            rewrittenErrmonStr.contains("cndr::/release-errmon.disabled.tools/api/v1/envelope"),
            "http:// rewritten in-place to cndr::/ ($rewrittenErrmonStr)",
        )

        val chatPayload = "POST https://discord.com/api/v9/channels/123456/messages".toByteArray(StandardCharsets.UTF_8)
        val chatTrailing =
            "{\"content\":\"check https://discord.com/api/v9/science and https://release-errmon.disabled.tools\"}"
                .toByteArray(
                    StandardCharsets.UTF_8,
                )
        val chatFrame = frameWithTrailing(modNet, methReq, 12, chatPayload, chatTrailing)
        Loader.dispatchInvoke(host, chatFrame)
        checkCond(Arrays.equals(host.lastInvokeBytes, chatFrame), "chat message with telemetry text in POST body passed through verbatim")
        checkEq(noTel.blockedScienceCount(), 1, "no false positive on science in chat body")
        checkEq(noTel.blockedErrmonCount(), 1, "no false positive on errmon in chat body")

        val clubPayload = "GET https://discord.com/api/v9/guilds/science-club".toByteArray(StandardCharsets.UTF_8)
        val clubFrame = Frame.build(Frame.KIND_REQUEST, modNet, methReq, 13, clubPayload)
        Loader.dispatchInvoke(host, clubFrame)
        checkCond(Arrays.equals(host.lastInvokeBytes, clubFrame), "/science-club passed through untouched")

        val invokesBeforeDrops = host.invokeCalls.get()
        val cr1 = Loader.dispatchInvoke(host, Frame.build(Frame.KIND_REQUEST, modCr1, methCr1, 20, byteArrayOf(1)))
        val cr2 = Loader.dispatchInvoke(host, Frame.build(Frame.KIND_REQUEST, modCr2, methCr2, 21, byteArrayOf(2)))
        val tr1 = Loader.dispatchInvoke(host, Frame.build(Frame.KIND_REQUEST, modTr, methTr, 22, byteArrayOf(3)))
        val tk1 = Loader.dispatchInvoke(host, Frame.build(Frame.KIND_REQUEST, modTk, methTk, 23, byteArrayOf(4)))
        checkCond(cr1 == null && cr2 == null && tr1 == null && tk1 == null, "all 4 telemetry modules returned null synchronously")
        checkEq(host.invokeCalls.get(), invokesBeforeDrops, "dropped telemetry modules never invoked host")
        checkEq(noTel.blockedCrashReportsCount(), 2, "CrashReporter + CrashReports counted")
        checkEq(noTel.blockedTrackingCount(), 2, "TelemetryRing + Tracking counted")
        checkEq(noTel.totalBlockedCount(), 6, "total blocked count == 6")

        Loader.setPluginEnabled(noTel, false)
        val crRespAfterDisable = Loader.dispatchInvoke(host, Frame.build(Frame.KIND_REQUEST, modCr1, methCr1, 24, byteArrayOf(1)))
        checkCond(crRespAfterDisable != null, "when NoTelemetryPlugin disabled, CrashReporter reaches host normally")
        Loader.dispatchInvoke(host, scienceFrame)
        checkCond(Arrays.equals(host.lastInvokeBytes, scienceFrame), "when disabled, /science passes through untouched")
        checkEq(noTel.totalBlockedCount(), 6, "counters unchanged while disabled")
    }

    private fun testVencordPluginsUiAndReactiveRestartBanner() {
        val host = installSyncHost()
        val manifest =
            CinderNative.parseManifestJson(
                """{"version":1,"build_id":"11223344","patches":[{"tag":"release.access"},{"tag":"staff.devtools"}],"runtime_flags":{"staff.devtools":{"vaddr":"0x5f86310","default":1}}}""",
            )
        val backend = FakeNativeBackend(baseAddr = 0x7000_0000_00L, buildId = "11223344", flagValue = 1)
        CinderNative.setManifestForTests(manifest)
        CinderNative.setBackendForTests(backend)

        val cs = CinderSettings()
        val release = PhoenixAccessPlugin()
        val diag = CinderDiagnosticsPlugin()
        val staff = StaffDevToolsPlugin()
        val noTel = NoTelemetryPlugin()

        Loader.setPluginsForTests(cs, release, diag, staff, noTel)
        cs.onLoad(null)
        release.onLoad(null)
        diag.onLoad(null)
        staff.onLoad(null)
        noTel.onLoad(null)

        val overview = buildSettingsOverviewBatch()
        val rewrittenOverview = Loader.onOps(overview, overview.size)
        val cinderRowId =
            UiOps
                .parse(rewrittenOverview)!!
                .ops()
                .first { it.opcode() == UiOps.OP_CREATE && it.propString("accessibilityId") == "settings.row.cinder" }
                .viewId()
        Loader.onDispatch(pressEvent(cinderRowId))
        checkEq(cs.getRouter().depth, 1, "Cinder root page open")

        val root = cs.getRouter().topPage!!
        val staffHandle = root.ref<ManaUi.ToggleRowHandle>("plugin:staff-devtools")
        val noTelHandle = root.ref<ManaUi.ToggleRowHandle>("plugin:no-telemetry")
        val releaseHandle = root.ref<ManaUi.NavRowHandle>("plugin:release-access")
        val diagHandle = root.ref<ManaUi.NavRowHandle>("plugin:cinder-diagnostics")
        checkCond(staffHandle != null, "staff-devtools rendered as navToggleRow (ToggleRowHandle)")
        checkCond(noTelHandle != null, "no-telemetry rendered as navToggleRow (ToggleRowHandle)")
        checkCond(releaseHandle != null, "release-access rendered as Core navRow (NavRowHandle)")
        checkCond(diagHandle != null, "cinder-diagnostics rendered as Core navRow (NavRowHandle)")
        checkCond(root.ref<Int>("cinder.btn.restart") == null, "Restart Required banner hidden initially")
        checkCond(!Loader.hasPendingRestart(), "no pending restart initially")

        val rootTexts =
            host.appliedBatches
                .last()
                .ops()
                .filter { it.opcode() == UiOps.OP_CREATE && it.viewType() == "text" }
                .mapNotNull { it.propString("text") }
        checkCond(rootTexts.contains("No Telemetry"), "no-telemetry displayed as No Telemetry")
        checkCond(rootTexts.contains("Staff DevTools"), "staff-devtools displayed as Staff DevTools")
        checkCond(rootTexts.contains("Release Access"), "release-access displayed as Release Access")
        checkCond(rootTexts.contains("Cinder Diagnostics"), "cinder-diagnostics displayed as Cinder Diagnostics")
        checkCond(
            !rootTexts.contains("Blocks Discord science, errmon, crash reports & analytics SDKs"),
            "plugin description subtitles removed from Cinder root",
        )
        checkCond(
            !rootTexts.contains("Tap a plugin to open its settings, or flip its switch to toggle it inline."),
            "plugin section footer removed from Cinder root",
        )
        checkCond(
            !rootTexts.contains("Core plugins power essential Cinder functionality and stay enabled."),
            "core plugins section footer removed from Cinder root",
        )

        Loader.onDispatch(pressEvent(staffHandle!!.toggle.trackId))
        checkEq(cs.getRouter().depth, 1, "tapping switch track does NOT navigate away from Cinder root")
        checkCond(!Loader.isPluginEnabled(staff), "staff-devtools toggled OFF via inline switch")
        checkEq(backend.flagValue, 0, "native .bss staff.devtools flag flipped to 0 live")
        checkCond(!Loader.hasPendingRestart(), "staff-devtools does NOT trigger restart required")
        checkCond(root.ref<Int>("cinder.btn.restart") == null, "Restart Required banner stays hidden")

        Loader.onDispatch(pressEvent(staffHandle.rowId))
        checkEq(cs.getRouter().depth, 2, "tapping row body opens Staff DevTools settings page")
        val staffPage = cs.getRouter().topPage!!
        checkEq(staffPage.page.title, "Staff DevTools", "Staff DevTools page title")
        val staffPageToggle = staffPage.ref<ManaUi.ToggleRowHandle>(StaffDevToolsPlugin.REF_ENABLE_TOGGLE)!!
        Loader.onDispatch(pressEvent(staffPageToggle.rowId))
        checkCond(Loader.isPluginEnabled(staff), "re-enabled staff-devtools from inside its settings page")
        checkEq(backend.flagValue, 1, "native .bss flag flipped back to 1")
        checkCond(cs.onBackPress(), "popped Staff DevTools page")
        checkEq(cs.getRouter().depth, 1, "back on Cinder root")

        Loader.onDispatch(pressEvent(noTelHandle!!.toggle.trackId))
        checkEq(cs.getRouter().depth, 1, "no-telemetry switch track does NOT navigate")
        checkCond(!Loader.isPluginEnabled(noTel), "no-telemetry toggled OFF")
        checkCond(Loader.hasPendingRestart(), "no-telemetry triggers pending restart")
        val restartBtnId1 = root.ref<Int>("cinder.btn.restart")
        checkCond(restartBtnId1 != null && restartBtnId1 >= ManaIds.SYNTHETIC_START, "Restart Required banner dynamically mounted")

        Loader.onDispatch(pressEvent(noTelHandle.toggle.trackId))
        checkCond(Loader.isPluginEnabled(noTel), "no-telemetry restored to boot state ON")
        checkCond(!Loader.hasPendingRestart(), "pending restart cleared when restored to boot state")
        checkCond(root.ref<Int>("cinder.btn.restart") == null, "Restart Required banner dynamically unmounted")
        val unmountBannerBatch = host.appliedBatches.last()
        checkCond(unmountBannerBatch.ops().any { it.opcode() == UiOps.OP_DELETE }, "unmounting banner emits OP_DELETE")

        val restartCalled = AtomicBoolean(false)
        Loader.setRestartHookForTests(Runnable { restartCalled.set(true) })
        Loader.onDispatch(pressEvent(noTelHandle.toggle.trackId))
        val restartBtnId2 = root.ref<Int>("cinder.btn.restart")!!
        Loader.onDispatch(pressEvent(restartBtnId2))
        checkCond(restartCalled.get(), "Restart Discord button triggered Loader.restartApp()")

        Loader.onDispatch(pressEvent(releaseHandle!!.rowId))
        checkEq(
            cs
                .getRouter()
                .topPage!!
                .page.title,
            "Release Access",
            "opened Release Access Core page",
        )
        checkCond(cs.onBackPress(), "popped Release Access page")

        Loader.onDispatch(pressEvent(diagHandle!!.rowId))
        checkEq(
            cs
                .getRouter()
                .topPage!!
                .page.title,
            "Cinder Diagnostics",
            "opened Cinder Diagnostics Core page",
        )
        val resetBtnId = cs.getRouter().topPage!!.ref<Int>(CinderDiagnosticsPlugin.REF_RESET_BUTTON)!!
        Loader.onDispatch(pressEvent(resetBtnId))
        checkCond(cs.getRouter().currentToast != null, "CinderDiagnosticsPlugin reset toast shown")
        cs.getRouter().dismissToast()
        checkCond(cs.onBackPress(), "popped Cinder Diagnostics page")

        checkCond(cs.onBackPress(), "popped Cinder root page")
        checkEq(cs.getRouter().depth, 0, "router stack empty")
        checkEq(ManaMotion.activeSlotCount(CinderSettings.THEME_SCOPE_PAGE), 0, "0 leaked page motion slots")
        checkEq(ThemeEngine.bindingCount(CinderSettings.THEME_SCOPE_PAGE), 0, "0 leaked page theme bindings")
    }

    private fun testPluginDisplayNames() {
        checkEq(NoTelemetryPlugin().name(), "No Telemetry", "no-telemetry display name")
        checkEq(StaffDevToolsPlugin().name(), "Staff DevTools", "staff-devtools display name")
        checkEq(PhoenixAccessPlugin().name(), "Release Access", "release-access display name")
        checkEq(CinderDiagnosticsPlugin().name(), "Cinder Diagnostics", "cinder-diagnostics display name")

        val custom =
            object : ModPlugin {
                override fun id(): String = "no-tracking"
            }
        checkEq(custom.name(), "No Tracking", "default plugin name title-cases separators")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        runTest("cinderNativeManifestDeferredFlushAndBuildDrift") { testCinderNativeManifestDeferredFlushAndBuildDrift() }
        runTest("coreLockedOnAndPluginDispatchGating") { testCoreLockedOnAndPluginDispatchGating() }
        runTest("noTelemetryPluginInterceptionAndSafety") { testNoTelemetryPluginInterceptionAndSafety() }
        runTest("textReplacePlugin") { testTextReplacePlugin() }
        runTest("vencordPluginsUiAndReactiveRestartBanner") { testVencordPluginsUiAndReactiveRestartBanner() }
        runTest("pluginDisplayNames") { testPluginDisplayNames() }
        println("ALL $passed BuiltinPluginsTest TESTS PASSED")
    }
}
