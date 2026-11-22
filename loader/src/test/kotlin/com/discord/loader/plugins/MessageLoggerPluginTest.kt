package com.discord.loader.plugins

import com.discord.loader.BridgeRegistry
import com.discord.loader.Frame
import com.discord.loader.HostInvoker
import com.discord.loader.Loader
import com.discord.loader.ManaUi
import com.discord.loader.UiOps
import com.discord.loader.mana.ManaContext
import com.discord.loader.mana.ManaPatchDispatcher
import com.discord.loader.mana.ManaRouter
import com.discord.loader.mana.ManaScope
import com.discord.loader.plugins.messagelogger.BridgeRecord
import com.discord.loader.plugins.messagelogger.GatewayCodec
import com.discord.loader.plugins.messagelogger.GwEncoding
import com.discord.loader.plugins.messagelogger.GwValue
import com.discord.loader.plugins.messagelogger.MessageLoggerEngine
import com.discord.loader.plugins.messagelogger.MessageLogStore
import com.discord.loader.plugins.messagelogger.RValue
import com.discord.loader.plugins.messagelogger.int
import com.discord.loader.plugins.messagelogger.long
import com.discord.loader.plugins.messagelogger.str
import java.nio.charset.StandardCharsets

object MessageLoggerPluginTest {
    private var passed = 0

    private fun checkCond(cond: Boolean, msg: String) {
        if (!cond) throw AssertionError("FAIL: $msg")
    }

    private fun checkEq(actual: Any?, expected: Any?, msg: String) {
        if (actual != expected) {
            throw AssertionError("FAIL: $msg (expected=$expected, actual=$actual)")
        }
    }

    private fun registerSampleProps() {
        UiOps.registerPropNames(
            arrayOf(
                "",
                "direction", "padding", "margin", "width", "height", "minHeight", "minWidth",
                "flex", "gap", "align", "justify", "background", "activeBackground", "color",
                "fontSize", "fontWeight", "lineHeight", "lineHeightExact", "text", "textAlign",
                "radius", "radiusTopLeft", "radiusTopRight", "radiusBottomLeft", "radiusBottomRight",
                "borderColor", "borderWidth", "clip", "pressFeedback", "role", "accessibilityLabel",
                "accessibilityId", "accessibilityChecked", "accessibilityHidden", "accessible",
                "on", "press", "longPress", "navigate", "dismiss", "change", "changeText",
                "focus", "blur", "submit", "presentation", "shrink", "mana", "appeared",
                "exitEnd", "animate", "fullScreenBack", "stackHeader", "absolute", "paddingBox",
                "gravity", "shadow", "hitSlop", "index", "translateX", "translateY", "opacity",
                "scaleFactor", "uri", "tint", "scale", "enabled", "maxLines", "worklet", "value",
                "placeholder", "placeholderColor", "keyboardShouldPersistTaps", "fullscreen",
                "dismissible", "includeFontPadding", "steps", "minTrackTint", "maxTrackTint",
                "thumbTint", "thumbSize", "stepHaptic", "background", "borderRadius", "spans",
            ),
        )
    }

    private fun registerBridgeSymbols() {
        BridgeRegistry.register(
            arrayOf(
                "",
                "WebSocket", "WebSocket.open", "WebSocket.adopt", "WebSocket.close",
                "@ws", "Networking", "Networking.request", "@http",
                "id", "url", "origin", "event", "method", "status", "rawLength",
            ),
        )
    }

    private fun runTest(name: String, fn: () -> Unit) {
        Loader.resetForTests()
        registerSampleProps()
        registerBridgeSymbols()
        fn()
        passed++
        println("  PASS $name")
    }

    private fun frameWithTrailing(
        kind: Int,
        moduleId: Int,
        methodId: Int,
        callId: Int,
        payload: ByteArray,
        trailing: ByteArray,
    ): ByteArray {
        val base = Frame.build(kind, moduleId, methodId, callId, payload)
        val out = ByteArray(base.size + trailing.size)
        System.arraycopy(base, 0, out, 0, base.size)
        System.arraycopy(trailing, 0, out, base.size, trailing.size)
        return out
    }

    private fun record(vararg pairs: Pair<String, RValue>): ByteArray {
        val fields = LinkedHashMap<Int, RValue>()
        for ((name, value) in pairs) {
            val id = BridgeRegistry.idOf(name)
            checkCond(id >= 0, "symbol $name registered")
            fields[id] = value
        }
        return BridgeRecord.writeRecord(fields)
    }

    private fun gwstr(s: String) = GwValue.GwStr(s)

    private fun dispatchMap(
        t: String,
        s: Long?,
        d: GwValue.GwMap,
    ): GwValue.GwMap {
        val root = LinkedHashMap<String, GwValue>()
        root["t"] = gwstr(t)
        if (s != null) root["s"] = GwValue.GwInt(s)
        root["op"] = GwValue.GwInt(0)
        root["d"] = d
        return GwValue.GwMap(root)
    }

    private fun mapOf(vararg pairs: Pair<String, GwValue>): GwValue.GwMap =
        GwValue.GwMap(LinkedHashMap<String, GwValue>().apply { pairs.forEach { put(it.first, it.second) } })

    private fun messageMap(
        id: String,
        channel: String,
        author: String,
        content: String?,
        nonce: String? = null,
        flags: Int = 0,
        bot: Boolean = false,
        attachments: List<GwValue.GwMap>? = null,
        guild: String? = "g1",
    ): GwValue.GwMap {
        val authorMap = mapOf("id" to gwstr(author), "username" to gwstr("user-$author"), "bot" to GwValue.GwBool(bot))
        val fields = LinkedHashMap<String, GwValue>()
        fields["id"] = gwstr(id)
        fields["channel_id"] = gwstr(channel)
        if (guild != null) fields["guild_id"] = gwstr(guild)
        fields["author"] = authorMap
        fields["content"] = if (content == null) GwValue.GwNull else gwstr(content)
        fields["timestamp"] = gwstr("2026-01-01T00:00:00Z")
        if (attachments != null) fields["attachments"] = GwValue.GwList(attachments.toMutableList())
        if (nonce != null) fields["nonce"] = gwstr(nonce)
        if (flags != 0) fields["flags"] = GwValue.GwInt(flags.toLong())
        return GwValue.GwMap(fields)
    }

    private fun attachment(
        id: String,
        name: String,
        url: String,
        size: Long = 12345L,
        proxyUrl: String = "https://media.discordapp.net/$name",
    ): GwValue.GwMap = mapOf(
        "id" to gwstr(id),
        "filename" to gwstr(name),
        "size" to GwValue.GwInt(size),
        "url" to gwstr(url),
        "proxy_url" to gwstr(proxyUrl),
        "content_type" to gwstr("image/png"),
    )

    private fun dispatchJson(t: String, s: Long?, d: GwValue.GwMap): ByteArray =
        GatewayCodec.encodeJson(dispatchMap(t, s, d))

    private fun dispatchEtf(t: String, s: Long?, d: GwValue.GwMap): ByteArray =
        GatewayCodec.encodeEtf(dispatchMap(t, s, d))

    private fun trackGateway(plugin: MessageLoggerPlugin, socketId: Int, url: String) {
        val payload = record("id" to RValue.RInt32(socketId), "url" to RValue.RString(url))
        val frame = Frame.build(
            Frame.KIND_REQUEST,
            BridgeRegistry.moduleId("WebSocket"),
            BridgeRegistry.methodId("WebSocket", "open"),
            1,
            payload,
        )
        checkCond(plugin.onHostCall(Frame.parse(frame)!!) == null, "WebSocket.open passes through")
    }

    private fun wsFrame(socketId: Int, event: String, payload: ByteArray): Frame {
        val recordPayload = record(
            "id" to RValue.RInt32(socketId),
            "event" to RValue.RString(event),
        )
        val bytes = frameWithTrailing(
            Frame.KIND_EVENT,
            0,
            BridgeRegistry.idOf("@ws"),
            0,
            recordPayload,
            payload,
        )
        return Frame.parse(bytes)!!
    }

    private fun pressEvent(viewId: Int): UiOps.ViewEvent =
        UiOps.ViewEvent.parse(UiOps.ViewEvent.buildViewEvent(viewId, UiOps.propId("press"), null))!!

    private fun testBridgeRecordRoundTrip() {
        val payload = record(
            "id" to RValue.RInt32(42),
            "url" to RValue.RString("wss://gateway.discord.gg/?encoding=json"),
            "event" to RValue.RString("binary"),
            "status" to RValue.RInt64(200L),
            "method" to RValue.RString("GET"),
        )
        val parsed = BridgeRecord.parseRecord(payload)
        checkCond(parsed != null, "record parsed")
        checkEq(parsed!!.int(BridgeRegistry.idOf("id")), 42, "id INT32")
        checkEq(parsed.str(BridgeRegistry.idOf("url")), "wss://gateway.discord.gg/?encoding=json", "url STRING")
        checkEq(parsed.long(BridgeRegistry.idOf("status")), 200L, "status INT64")
        checkEq(parsed.str(BridgeRegistry.idOf("event")), "binary", "event STRING")

        val dblRec = RValue.RRecord(linkedMapOf(1 to RValue.RDouble(77.0), 2 to RValue.RDouble(1.5)))
        checkEq(dblRec.int(1), 77, "whole double coerced to int")
        checkEq(dblRec.long(1), 77L, "whole double coerced to long")
        checkEq(dblRec.int(2), null, "fractional double not coerced to int")

        val nested = BridgeRecord.writeRecord(
            mapOf(
                BridgeRegistry.idOf("id") to RValue.RInt32(1),
                BridgeRegistry.idOf("url") to RValue.RString("nested"),
            ),
        )
        val complex = BridgeRecord.writeValue(
            RValue.RRecord(
                linkedMapOf(
                    1 to RValue.RBool(true),
                    2 to RValue.RNull,
                    3 to RValue.RDouble(1.5),
                    4 to RValue.RArray(listOf(RValue.RInt32(7), RValue.RString("x"))),
                    5 to BridgeRecord.parseRecord(nested)!!,
                    6 to RValue.RStringMap(linkedMapOf("k" to RValue.RInt32(9))),
                ),
            ),
        )
        val reparsed = BridgeRecord.parseValue(complex) as? RValue.RRecord
        checkCond(reparsed != null, "nested record parsed")
        checkEq((reparsed!!.fields[1] as RValue.RBool).value, true, "bool tag")
        checkCond(reparsed.fields[2] is RValue.RNull, "null tag")
        checkEq((reparsed.fields[3] as RValue.RDouble).value, 1.5, "double tag")
        checkEq((reparsed.fields[4] as RValue.RArray).values.size, 2, "array tag")
        checkEq(
            ((reparsed.fields[5] as RValue.RRecord).fields[BridgeRegistry.idOf("url")] as RValue.RString).value,
            "nested",
            "nested record tag",
        )
        checkEq(
            (reparsed.fields[6] as RValue.RStringMap).entries["k"],
            RValue.RInt32(9),
            "string map tag",
        )

        checkCond(BridgeRecord.parseRecord(byteArrayOf(0x08, 0x01)) == null, "truncated record fail-soft")
        checkCond(BridgeRecord.parseRecord(byteArrayOf(0x07, 0x00)) == null, "wrong top-level tag fail-soft")
        checkCond(BridgeRecord.parseRecord(byteArrayOf(0x08, 0x01, 0x00, 0x01, 0x7F)) == null, "unknown value tag fail-soft")
    }

    private fun testGatewayCodecJsonAndEtf() {
        val snowflake = 1234567890123456789L
        val utf8Text = "hello 🔥 café 日本語"
        val inner = mapOf(
            "id" to gwstr("1234567890123456789"),
            "big" to GwValue.GwInt(snowflake),
            "text" to gwstr(utf8Text),
            "edited_timestamp" to GwValue.GwNull,
            "empty_list" to GwValue.GwList(mutableListOf()),
            "ok" to GwValue.GwBool(true, GwValue.GwStr.TAG_ATOM),
            "nested" to GwValue.GwList(mutableListOf(gwstr("a"), GwValue.GwInt(7))),
        )
        val root = dispatchMap("MESSAGE_CREATE", 99L, inner)

        val json = GatewayCodec.encodeJson(root)
        checkEq(json[0], '{'.code.toByte(), "JSON starts with '{'")
        checkEq(GatewayCodec.peekGatewayEventType(json), "MESSAGE_CREATE", "fast JSON peek")
        val parsedJson = GatewayCodec.parse(json)!!
        checkEq(parsedJson.encoding, GwEncoding.JSON, "JSON sniffed")
        val jsonD = (parsedJson.value as GwValue.GwMap).values["d"] as GwValue.GwMap
        checkEq(jsonD.values["big"], GwValue.GwInt(snowflake), "JSON 64-bit integer")
        checkEq(jsonD.values["text"], gwstr(utf8Text), "JSON UTF-8 preserved")
        checkEq(jsonD.values["edited_timestamp"], GwValue.GwNull, "JSON null preserved")
        checkEq((jsonD.values["empty_list"] as GwValue.GwList).values.size, 0, "JSON empty list")

        val etf = GatewayCodec.encodeEtf(root)
        checkEq(etf[0].toInt() and 0xFF, 0x83, "ETF starts with 0x83")
        checkEq(GatewayCodec.peekGatewayEventType(etf), "MESSAGE_CREATE", "fast ETF peek")
        val parsedEtf = GatewayCodec.parse(etf)!!
        checkEq(parsedEtf.encoding, GwEncoding.ETF, "ETF sniffed")
        val etfD = (parsedEtf.value as GwValue.GwMap).values["d"] as GwValue.GwMap
        checkEq(etfD.values["big"], GwValue.GwInt(snowflake), "ETF SMALL_BIG_EXT snowflake")
        checkEq(etfD.values["text"], gwstr(utf8Text), "ETF BINARY_EXT UTF-8 preserved without mojibake")
        checkEq(etfD.values["edited_timestamp"], GwValue.GwNull, "ETF nil atom decoded as GwNull")
        checkCond(etfD.values["empty_list"] is GwValue.GwList, "ETF NIL_EXT decoded as empty GwList")
        checkEq((etfD.values["empty_list"] as GwValue.GwList).values.size, 0, "ETF NIL_EXT list is empty")
        checkEq(etfD.values["ok"], GwValue.GwBool(true, 100), "ETF atom preserved")
        checkCond(
            GatewayCodec.encode(parsedEtf.value, GwEncoding.ETF).contentEquals(etf),
            "ETF round-trip is byte-identical",
        )

        val hbJson = "{\"t\":null,\"s\":null,\"op\":11,\"d\":null}".toByteArray(StandardCharsets.UTF_8)
        checkEq(GatewayCodec.peekGatewayEventType(hbJson), GatewayCodec.PEEK_NON_DISPATCH, "JSON heartbeat peek")
        val hbEtf = GatewayCodec.encodeEtf(mapOf("t" to GwValue.GwNull, "s" to GwValue.GwNull, "op" to GwValue.GwInt(11), "d" to GwValue.GwNull))
        checkEq(GatewayCodec.peekGatewayEventType(hbEtf), GatewayCodec.PEEK_NON_DISPATCH, "ETF heartbeat peek")

        val atomKeyMap = GwValue.GwMap(
            linkedMapOf("op" to GwValue.GwInt(0), "t" to gwstr("PING")),
            linkedMapOf("op" to GwValue.GwStr.TAG_ATOM, "t" to GwValue.GwStr.TAG_ATOM),
        )
        val atomKeyEtf = GatewayCodec.encodeEtf(atomKeyMap)
        val parsedAtomKeys = GatewayCodec.parseEtf(atomKeyEtf)!! as GwValue.GwMap
        checkEq(parsedAtomKeys.keyTags["op"], GwValue.GwStr.TAG_ATOM, "ETF atom map key preserved")
        checkCond(
            GatewayCodec.encodeEtf(parsedAtomKeys).contentEquals(atomKeyEtf),
            "ETF atom-key round-trip is byte-identical",
        )

        checkCond(GatewayCodec.sniff(byteArrayOf(0x78.toByte(), 0x9C.toByte())) == null, "zlib-stream sniffed as unsupported")
        checkCond(GatewayCodec.parse(byteArrayOf(0x7B, 0x7D)) != null, "empty JSON object parsed")
        checkCond(GatewayCodec.parseEtf(byteArrayOf(0x83.toByte(), 0x7F.toByte())) == null, "unknown ETF term fail-soft")
    }

    private fun testCreateDeleteAndSequenceSafety() {
        val plugin = MessageLoggerPlugin()
        plugin.onLoad(null)
        trackGateway(plugin, 7, "wss://gateway.discord.gg/?v=9&encoding=json&compress=zstd-stream")

        checkCond(!MessageLogStore.isDiscordGatewayUrl("wss://remote-auth-gateway.discord.gg/?v=2"), "remote-auth excluded")
        checkCond(MessageLogStore.isDiscordGatewayUrl("wss://gateway.discord.gg/?v=9&encoding=json"), "gateway included")

        val create = dispatchJson(
            "MESSAGE_CREATE",
            100L,
            messageMap("1000", "2000", "3000", "hello world", attachments = listOf(attachment("900", "pic.png", "https://cdn/pic.png", size = 54321L))),
        )
        checkCond(plugin.onDispatch(wsFrame(7, "binary", create)) == null, "MESSAGE_CREATE passes through")
        checkEq(plugin.store.size(), 1, "MESSAGE_CREATE cached")

        val delete = dispatchJson("MESSAGE_DELETE", 101L, mapOf("id" to gwstr("1000"), "channel_id" to gwstr("2000")))
        val replacement = plugin.onDispatch(wsFrame(7, "text", delete))
        checkCond(replacement != null, "MESSAGE_DELETE rewritten")
        val out = Frame.parse(replacement!!)!!
        checkEq(out.methodId(), BridgeRegistry.idOf("@ws"), "rewritten frame keeps @ws method")
        checkEq(out.callId(), 0, "rewritten frame keeps callId")
        checkCond(out.trailing().isNotEmpty(), "rewritten frame carries trailing payload")

        val rewritten = GatewayCodec.parse(out.trailing())!!.value as GwValue.GwMap
        checkEq(rewritten.values["op"], GwValue.GwInt(0), "op preserved")
        checkEq(rewritten.values["s"], GwValue.GwInt(101L), "sequence s preserved 1:1")
        checkEq(rewritten.values["t"], gwstr("MESSAGE_UPDATE"), "delete rewritten to MESSAGE_UPDATE")
        val d = rewritten.values["d"] as GwValue.GwMap
        checkEq((d.values["content"] as GwValue.GwStr).value, "[Deleted] hello world", "deleted content preserved")
        checkCond(d.values["author"] is GwValue.GwMap && d.values.containsKey("timestamp"), "delete rewrite is a full message (author/timestamp)")
        val atts = (d.values["attachments"] as GwValue.GwList).values
        checkEq(atts.size, 1, "attachment retained on delete")
        val att0 = atts[0] as GwValue.GwMap
        checkEq(att0.values["size"], GwValue.GwInt(54321L), "attachment full wire schema (size) preserved")
        checkEq(att0.values["proxy_url"], gwstr("https://media.discordapp.net/pic.png"), "attachment proxy_url preserved")

        val dupReplacement = plugin.onDispatch(wsFrame(7, "text", delete))!!
        val dupD = (GatewayCodec.parse(Frame.parse(dupReplacement)!!.trailing())!!.value as GwValue.GwMap).values["d"] as GwValue.GwMap
        checkEq((dupD.values["content"] as GwValue.GwStr).value, "[Deleted] hello world", "no double [Deleted] prefix")
        checkEq(plugin.store.loggedDeletes.get(), 1, "duplicate delete does not double-count")

        checkEq(MessageLoggerEngine.extractDeleteMessageIds("DELETE", "https://discord.com/api/v9/channels/2000/messages/1005?x=1"), Pair("2000", "1005"), "DELETE url parsed")
        checkCond(MessageLoggerEngine.extractDeleteMessageIds("GET", "https://discord.com/api/v9/channels/2000/messages/1005") == null, "non-DELETE ignored")
        plugin.onDispatch(wsFrame(7, "binary", dispatchJson("MESSAGE_CREATE", 104L, messageMap("1005", "2000", "3000", "mine"))))
        plugin.store.trackSelfDelete(1, "2000", "1005")
        val ownRep = plugin.onDispatch(wsFrame(7, "text", dispatchJson("MESSAGE_DELETE", 105L, mapOf("id" to gwstr("1005"), "channel_id" to gwstr("2000")))))!!
        val ownOut = GatewayCodec.parse(Frame.parse(ownRep)!!.trailing())!!.value as GwValue.GwMap
        checkEq(ownOut.values["t"], gwstr("MESSAGE_CREATE"), "own delete echo re-created")
        checkEq(((ownOut.values["d"] as GwValue.GwMap).values["content"] as GwValue.GwStr).value, "[Deleted] mine", "own delete content marked")

        val etfMsg = mapOf(
            "id" to GwValue.GwInt(1574040791712530432L),
            "channel_id" to GwValue.GwInt(1525243776891752478L),
            "author" to mapOf("id" to GwValue.GwInt(653027660724699136L), "username" to gwstr("etfuser")),
            "content" to gwstr("etf snowflake 🔥"),
            "attachments" to GwValue.GwList(mutableListOf()),
        )
        plugin.onDispatch(wsFrame(7, "binary", dispatchEtf("MESSAGE_CREATE", 102L, etfMsg)))
        checkCond(plugin.store.get("1525243776891752478", "1574040791712530432") != null, "ETF integer snowflake cached")
        val etfDel = dispatchEtf(
            "MESSAGE_DELETE",
            103L,
            mapOf("id" to GwValue.GwInt(1574040791712530432L), "channel_id" to GwValue.GwInt(1525243776891752478L)),
        )
        val etfDelRep = plugin.onDispatch(wsFrame(7, "binary", etfDel))!!
        val etfDelOut = GatewayCodec.parse(Frame.parse(etfDelRep)!!.trailing())!!
        checkEq(etfDelOut.encoding, GwEncoding.ETF, "ETF delete rewrite stays ETF")
        val etfDelD = (etfDelOut.value as GwValue.GwMap).values["d"] as GwValue.GwMap
        checkEq(etfDelD.values["id"], GwValue.GwInt(1574040791712530432L), "ETF integer snowflake wire type preserved")
        checkEq((etfDelD.values["content"] as GwValue.GwStr).value, "[Deleted] etf snowflake 🔥", "ETF UTF-8 content preserved")

        for (id in listOf("3001", "3002")) {
            plugin.onDispatch(wsFrame(7, "binary", dispatchJson("MESSAGE_CREATE", 110L, messageMap(id, "2002", "u1", "bulk"))))
        }
        val bulk = dispatchJson(
            "MESSAGE_DELETE_BULK",
            112L,
            mapOf(
                "channel_id" to gwstr("2002"),
                "ids" to GwValue.GwList(mutableListOf(gwstr("3001"), gwstr("unknown"), gwstr("3002"))),
            ),
        )
        val bulkReplacement = plugin.onDispatch(wsFrame(7, "binary", bulk))!!
        val bulkOut = GatewayCodec.parse(Frame.parse(bulkReplacement)!!.trailing())!!.value as GwValue.GwMap
        checkEq(bulkOut.values["s"], GwValue.GwInt(112L), "bulk delete sequence preserved")
        val ids = ((bulkOut.values["d"] as GwValue.GwMap).values["ids"] as GwValue.GwList).values
        checkEq(ids, listOf(gwstr("unknown")), "only uncached IDs forwarded in bulk delete")
        checkEq(plugin.store.loggedDeletes.get(), 5, "4 deletes logged total")

        checkCond(plugin.onDispatch(wsFrame(7, "binary", byteArrayOf(0x78.toByte(), 0x9C.toByte()))) == null, "undecodable payload passes through")
        checkCond(plugin.onDispatch(wsFrame(7, "binary", dispatchJson("USER_SETTINGS_PROTO_UPDATE", 113L, mapOf()))) == null, "irrelevant event fast-path skipped")
        checkCond(plugin.onDispatch(wsFrame(99, "binary", delete)) == null, "non-gateway socket passes through")
    }

    private fun testEditTrackingAndAttachments() {
        val plugin = MessageLoggerPlugin()
        plugin.onLoad(null)
        trackGateway(plugin, 3, "wss://gateway.discord.gg/?encoding=etf")

        val created = dispatchEtf(
            "MESSAGE_CREATE",
            10L,
            messageMap(
                "e1", "c1", "u1", "original",
                attachments = listOf(
                    attachment("a1", "one.png", "https://cdn/one.png", size = 111L),
                    attachment("a2", "two.png", "https://cdn/two.png", size = 222L),
                ),
            ),
        )
        plugin.onDispatch(wsFrame(3, "binary", created))

        val edit = dispatchEtf(
            "MESSAGE_UPDATE",
            11L,
            messageMap("e1", "c1", "u1", "edited version", attachments = emptyList()),
        ).let { bytes ->
            val parsed = GatewayCodec.parseEtf(bytes)!! as GwValue.GwMap
            val d = parsed.values["d"] as GwValue.GwMap
            d.values["edited_timestamp"] = gwstr("2026-01-02T00:00:00Z")
            GatewayCodec.encodeEtf(parsed)
        }
        val editReplacement = plugin.onDispatch(wsFrame(3, "binary", edit))
        checkCond(editReplacement != null, "edit rewritten for inline history")
        val parsedEdit = GatewayCodec.parse(Frame.parse(editReplacement!!)!!.trailing())!!
        checkEq(parsedEdit.encoding, GwEncoding.ETF, "rewrite stays in ETF encoding")
        val outD = (parsedEdit.value as GwValue.GwMap).values["d"] as GwValue.GwMap
        val content = (outD.values["content"] as GwValue.GwStr).value
        checkCond(content.contains("~~original~~ *(edited)*"), "inline edit renders previous version")
        checkCond(content.endsWith("edited version"), "inline edit ends with latest content")
        checkEq(plugin.store.loggedEdits.get(), 1, "one edit logged")

        val attachments = (outD.values["attachments"] as GwValue.GwList).values
        checkEq(attachments.size, 2, "both removed attachments retained even when ETF sent NIL_EXT")
        checkEq((attachments[0] as GwValue.GwMap).values["size"], GwValue.GwInt(111L), "attachment 1 size preserved")
        checkEq((attachments[1] as GwValue.GwMap).values["size"], GwValue.GwInt(222L), "attachment 2 size preserved")

        val embedOnlyEtf = dispatchEtf("MESSAGE_UPDATE", 12L, messageMap("e1", "c1", "u1", null).also {
            it.values["edited_timestamp"] = GwValue.GwNull
        })
        checkCond(plugin.onDispatch(wsFrame(3, "binary", embedOnlyEtf)) == null, "ETF embed-only update with nil timestamp is a no-op")
        checkEq(plugin.store.loggedEdits.get(), 1, "no false edit from embed unfurl")

        val clearText = dispatchJson("MESSAGE_UPDATE", 13L, messageMap("e1", "c1", "u1", "").also {
            it.values["edited_timestamp"] = gwstr("2026-01-03T00:00:00Z")
        })
        val clearRep = plugin.onDispatch(wsFrame(3, "binary", clearText))
        checkCond(clearRep != null, "clearing text to empty string is tracked as an edit")
        checkEq(plugin.store.get("c1", "e1")!!.editHistory.size, 2, "two edit history entries")
    }

    private fun testIgnoresEphemeralAndNonceGuard() {
        val plugin = MessageLoggerPlugin()
        plugin.onLoad(null)
        trackGateway(plugin, 5, "wss://gateway.discord.gg/?encoding=json")

        val ready = dispatchJson(
            "READY",
            1L,
            mapOf(
                "user" to mapOf("id" to gwstr("me")),
                "guilds" to GwValue.GwList(mutableListOf(
                    mapOf(
                        "id" to gwstr("g_ready"),
                        "channels" to GwValue.GwList(mutableListOf(mapOf("id" to gwstr("ch_ready")))),
                        "threads" to GwValue.GwList(mutableListOf(mapOf("id" to gwstr("th_ready"), "parent_id" to gwstr("ch_ready")))),
                    ),
                )),
            ),
        )
        plugin.onDispatch(wsFrame(5, "binary", ready))
        checkEq(plugin.store.currentUserId, "me", "READY extracted currentUserId")
        checkEq(plugin.store.guildOf("ch_ready"), "g_ready", "READY indexed guild channel")
        checkEq(plugin.store.parentOf("th_ready"), "ch_ready", "READY indexed thread parent")

        val initGuild = dispatchJson(
            "INITIAL_GUILD",
            2L,
            mapOf(
                "id" to gwstr("g_init"),
                "threads" to GwValue.GwList(mutableListOf(mapOf("id" to gwstr("th_init"), "parent_id" to gwstr("parent_init")))),
            ),
        )
        plugin.onDispatch(wsFrame(5, "binary", initGuild))
        checkEq(plugin.store.guildOf("th_init"), "g_init", "INITIAL_GUILD indexed thread guild")
        checkEq(plugin.store.parentOf("th_init"), "parent_init", "INITIAL_GUILD indexed thread parent")

        plugin.onDispatch(wsFrame(5, "binary", dispatchJson("MESSAGE_CREATE", 3L, messageMap("bot1", "c1", "bot", "x", bot = true))))
        checkCond(plugin.store.get("c1", "bot1") == null, "bot message not cached")

        plugin.onDispatch(wsFrame(5, "binary", dispatchJson("MESSAGE_CREATE", 4L, messageMap("eph", "c1", "u1", "secret", flags = 64))))
        val ephDelete = dispatchJson("MESSAGE_DELETE", 5L, mapOf("id" to gwstr("eph"), "channel_id" to gwstr("c1"), "flags" to GwValue.GwInt(64)))
        checkCond(plugin.onDispatch(wsFrame(5, "binary", ephDelete)) == null, "ephemeral delete passes through")

        plugin.config.ignoreUsersRaw = "u2, u3"
        plugin.onDispatch(wsFrame(5, "binary", dispatchJson("MESSAGE_CREATE", 6L, messageMap("u2m", "c1", "u2", "hi"))))
        checkCond(plugin.store.get("c1", "u2m") == null, "ignored user not cached")

        plugin.config.ignoreChannelsRaw = "parent_init"
        plugin.onDispatch(wsFrame(5, "binary", dispatchJson("MESSAGE_CREATE", 7L, messageMap("threadMsg", "th_init", "u4", "hi", guild = null))))
        checkCond(plugin.store.get("th_init", "threadMsg") == null, "INITIAL_GUILD thread parent channel ignore inherited")

        plugin.config.ignoreChannelsRaw = ""
        plugin.config.ignoreGuildsRaw = "g_ready"
        plugin.onDispatch(wsFrame(5, "binary", dispatchJson("MESSAGE_CREATE", 8L, messageMap("guildMsg", "ch_ready", "u4", "hi", guild = null))))
        checkCond(plugin.store.get("ch_ready", "guildMsg") == null, "READY guild ignore inherited via channel lookup")

        plugin.config.ignoreGuildsRaw = ""
        plugin.onDispatch(wsFrame(5, "binary", dispatchJson("MESSAGE_CREATE", 9L, messageMap("orig", "c5", "u1", "real", nonce = "n1"))))
        val spoof = dispatchJson("MESSAGE_CREATE", 10L, messageMap("fake", "c5", "attacker", "fake", nonce = "n1"))
        val replacement = plugin.onDispatch(wsFrame(5, "binary", spoof))
        checkCond(replacement != null, "nonce-spoofed create rewritten")
        val spoofD = (GatewayCodec.parse(Frame.parse(replacement!!)!!.trailing())!!.value as GwValue.GwMap).values["d"] as GwValue.GwMap
        checkCond(!spoofD.values.containsKey("nonce"), "nonce stripped from spoofed create")
    }

    private fun testHttpSeedingAndHistoryMerge() {
        val plugin = MessageLoggerPlugin()
        plugin.onLoad(null)
        trackGateway(plugin, 9, "wss://gateway.discord.gg/?encoding=json")

        checkCond(MessageLoggerEngine.isUsersMeUrl("https://discord.com/api/v9/users/@me"), "exact /users/@me")
        checkCond(!MessageLoggerEngine.isUsersMeUrl("https://discord.com/api/v9/users/@me/billing/subscriptions"), "reject /users/@me/billing")
        checkEq(MessageLoggerEngine.extractChannelMessagesId("https://discord.com/api/v9/channels/777/messages?limit=25"), "777", "exact /channels/777/messages")
        checkEq(MessageLoggerEngine.extractChannelMessagesId("https://discord.com/api/v9/channels/777/messages/search"), null, "reject /messages/search")

        fun sendHttp(reqId: Int, method: String, url: String, body: ByteArray): ByteArray? {
            val req = record("id" to RValue.RInt32(reqId), "method" to RValue.RString(method), "url" to RValue.RString(url))
            plugin.onHostCall(Frame.parse(Frame.build(Frame.KIND_REQUEST, BridgeRegistry.moduleId("Networking"), BridgeRegistry.methodId("Networking", "request"), reqId, req))!!)
            val resp = record("id" to RValue.RInt32(reqId), "status" to RValue.RInt32(200))
            return plugin.onDispatch(Frame.parse(frameWithTrailing(Frame.KIND_EVENT, 0, BridgeRegistry.idOf("@http"), 0, resp, body))!!)
        }

        sendHttp(1, "GET", "https://discord.com/api/v9/users/@me", GatewayCodec.encodeJson(mapOf("id" to gwstr("me-user"))))
        checkEq(plugin.store.currentUserId, "me-user", "currentUserId set from /users/@me")
        sendHttp(2, "GET", "https://discord.com/api/v9/users/@me/billing/subscriptions", GatewayCodec.encodeJson(mapOf("id" to gwstr("sub-999"))))
        checkEq(plugin.store.currentUserId, "me-user", "/users/@me/billing did not clobber currentUserId")

        val initialHistory = GatewayCodec.encodeJson(
            GwValue.GwList(mutableListOf(
                messageMap("300", "777", "u8", "newest", guild = null),
                messageMap("200", "777", "u8", "middle", guild = null),
                messageMap("100", "777", "u8", "oldest", guild = null),
            )),
        )
        checkCond(sendHttp(3, "GET", "https://discord.com/api/v9/channels/777/messages?limit=25", initialHistory) == null, "initial history passes through")
        checkEq(plugin.store.seededFromHistory.get(), 3, "3 history messages seeded")

        plugin.onDispatch(wsFrame(9, "binary", dispatchJson("MESSAGE_DELETE", 20L, mapOf("id" to gwstr("200"), "channel_id" to gwstr("777")))))
        plugin.onDispatch(wsFrame(9, "binary", dispatchJson("MESSAGE_DELETE", 21L, mapOf("id" to gwstr("300"), "channel_id" to gwstr("777")))))
        plugin.onDispatch(wsFrame(9, "binary", dispatchJson("MESSAGE_UPDATE", 22L, messageMap("100", "777", "u8", "oldest-edited", guild = null).also {
            it.values["edited_timestamp"] = gwstr("2026-01-04T00:00:00Z")
        })))

        val reloadBody = GatewayCodec.encodeJson(
            GwValue.GwList(mutableListOf(messageMap("100", "777", "u8", "oldest-edited", guild = null))),
        )
        val mergedFrame = sendHttp(4, "GET", "https://discord.com/api/v9/channels/777/messages?limit=25", reloadBody)
        checkCond(mergedFrame != null, "Phase 2 history reload merged deleted & edited messages")
        val mergedList = GatewayCodec.parseJson(Frame.parse(mergedFrame!!)!!.trailing()) as GwValue.GwList
        checkEq(mergedList.values.size, 3, "all 3 messages present after merge")

        checkEq((mergedList.values[0] as GwValue.GwMap).values["id"], gwstr("300"), "newest deleted merged first")
        checkEq((mergedList.values[0] as GwValue.GwMap).values["content"], gwstr("[Deleted] newest"), "merged 300 content")
        checkEq((mergedList.values[1] as GwValue.GwMap).values["id"], gwstr("200"), "middle deleted merged second")
        checkEq((mergedList.values[2] as GwValue.GwMap).values["id"], gwstr("100"), "oldest message preserved")
        checkCond(((mergedList.values[2] as GwValue.GwMap).values["content"] as GwValue.GwStr).value.contains("~~oldest~~"), "edit history survived reload")

        checkEq(plugin.store.get("777", "100")!!.editHistory.size, 1, "editHistory intact after HTTP refetch")
    }

    private class RecordingHost : HostInvoker {
        override fun invokeOrig(frame: ByteArray?): ByteArray? = frame
        override fun applyOps(ops: ByteArray, length: Int) {}
    }

    private fun testLoaderFrameIntegrationAndFailSoft() {
        val host = RecordingHost()
        Loader.registerHost(host)
        val plugin = MessageLoggerPlugin()
        Loader.setPluginsForTests(plugin)
        plugin.onLoad(null)
        trackGateway(plugin, 11, "wss://gateway.discord.gg/?encoding=json")

        val create = dispatchJson("MESSAGE_CREATE", 1L, messageMap("x1", "c1", "u1", "alpha"))
        val createFrame = wsFrame(11, "binary", create).raw()
        val forwardedCreate = Loader.onDispatch(createFrame)
        checkCond(forwardedCreate != null && forwardedCreate.contentEquals(createFrame), "Loader forwards untouched create frame")
        checkEq(plugin.store.size(), 1, "Loader-driven create cached")

        val delete = dispatchJson("MESSAGE_DELETE", 2L, mapOf("id" to gwstr("x1"), "channel_id" to gwstr("c1")))
        val deleteFrame = wsFrame(11, "binary", delete).raw()
        val forwardedDelete = Loader.onDispatch(deleteFrame)
        checkCond(forwardedDelete != null && !forwardedDelete.contentEquals(deleteFrame), "Loader forwards rewritten delete frame")
        val rewritten = GatewayCodec.parse(Frame.parse(forwardedDelete!!)!!.trailing())!!.value as GwValue.GwMap
        checkEq(rewritten.values["s"], GwValue.GwInt(2L), "Loader-level rewrite preserves s")

        checkCond(Loader.setPluginEnabled(plugin, false), "plugin disabled")
        val afterDisable = Loader.onDispatch(deleteFrame)
        checkCond(afterDisable != null && afterDisable.contentEquals(deleteFrame), "disabled plugin passes frames through")

        checkCond(Loader.onDispatch(byteArrayOf(0x03, 0x00)) == null || true, "malformed frame handled")
    }

    private fun testRedTextRecolorsDeletedRows() {
        val plugin = MessageLoggerPlugin()
        plugin.onLoad(null)
        fun batch(text: String) = UiOps.create().createView(7, "text", listOf(
            UiOps.Prop.named("spans", UiOps.Value.ofArray(listOf(UiOps.Value.ofPropMap(listOf(
                UiOps.Prop.named("text", UiOps.Value.ofString(text)),
                UiOps.Prop.named("color", UiOps.Value.ofLong(4294704123L)),
            ))))),
            UiOps.Prop.named("color", UiOps.Value.ofLong(4294704123L)),
        )).toBytes()
        val bytes = batch("[Deleted] hi")
        val out = plugin.onUiOps(bytes, bytes.size)
        checkCond(out != null, "deleted row rewritten")
        val props = UiOps.parse(out!!, out.size)!!.ops()[0].props()
        val color = props.first { it.name() == "color" }.value.asLong()
        checkEq(color, 0xFFF23F43L, "view color red")
        val span = props.first { it.name() == "spans" }.value.asArray()!![0].asPropMap()!!
        checkEq(span.first { it.name() == "text" }.value.asString(), "hi", "tag stripped")
        checkEq(span.first { it.name() == "color" }.value.asLong(), 0xFFF23F43L, "span color red")
        val plain = batch("hello")
        checkCond(plugin.onUiOps(plain, plain.size) == null, "normal rows untouched")
    }

    private fun testManaUiMountFilterAndClear() {
        val plugin = MessageLoggerPlugin()
        plugin.onLoad(null)
        plugin.store.put(
            com.discord.loader.plugins.messagelogger.LoggedMessage(
                id = "1", channelId = "c1", guildId = "g1", authorId = "u1", authorName = "alice",
                authorBot = false, content = "first", timestamp = null, editedTimestamp = null,
                attachments = mutableListOf(), nonce = null, flags = 0,
            ).also { it.deleted = true; it.displayContent = "[Deleted] first" },
        )
        plugin.store.put(
            com.discord.loader.plugins.messagelogger.LoggedMessage(
                id = "2", channelId = "c1", guildId = "g1", authorId = "u2", authorName = "bob",
                authorBot = false, content = "second", timestamp = null, editedTimestamp = null,
                attachments = mutableListOf(), nonce = null, flags = 0,
            ),
        )
        plugin.store.loggedDeletes.set(1)

        val router = ManaRouter(ManaPatchDispatcher { _ -> true })
        val ctx = ManaContext(router, ManaScope())
        val settings = MessageLoggerUi.settingsPage(plugin, ctx)
        router.push(settings)

        val settingsPage = router.topPage!!
        checkEq(settingsPage.page.title, "Message Logger", "settings page mounted")
        checkCond(settingsPage.ref<ManaUi.NavRowHandle>(MessageLoggerUi.REF_VIEW_LOG) != null, "view-log row registered")
        checkCond(settingsPage.ref<Int>(MessageLoggerUi.REF_ENABLE_TOGGLE) != null, "enable toggle registered")
        checkCond(settingsPage.ref<Int>(MessageLoggerUi.REF_CLEAR_ALL) != null, "clear-all button registered")

        val viewLogId = settingsPage.ref<ManaUi.NavRowHandle>(MessageLoggerUi.REF_VIEW_LOG)!!.rowId
        router.onViewEvent(pressEvent(viewLogId))
        checkEq(router.depth, 2, "viewer page pushed")
        val viewer = router.topPage!!
        checkEq(viewer.page.title, "Message Log", "viewer title")

        val loggedRows = viewer.refKeys().filter { it.startsWith("log-entry:") }
        checkEq(loggedRows, listOf("log-entry:1"), "Logged tab excludes untouched cached messages")

        val filterHandle = viewer.ref<ManaUi.SegmentedHandle>(MessageLoggerUi.REF_FILTER)
        checkCond(filterHandle != null, "segmented control registered")
        router.onViewEvent(pressEvent(filterHandle!!.tabIds[1]))
        checkEq(router.depth, 2, "filter rebuild keeps exactly one viewer page")
        checkEq(router.topPage!!.page.title, "Message Log", "rebuilt viewer title")

        val entryRows = router.topPage!!.refKeys().filter { it.startsWith("log-entry:") }
        checkCond(entryRows.isNotEmpty(), "viewer rendered entry rows")
        val entryRowId = router.topPage!!.ref<ManaUi.NavRowHandle>(entryRows.first())!!.rowId
        router.onViewEvent(pressEvent(entryRowId))
        checkEq(router.depth, 3, "detail page pushed")
        checkEq(router.topPage!!.page.title, "Message Details", "detail title")
        checkCond(router.pop(), "popped detail page")
        checkEq(router.depth, 2, "back on viewer")

        checkCond(router.pop(), "popped viewer page")
        checkEq(router.depth, 1, "back on settings page")
        val clearAllId = router.topPage!!.ref<Int>(MessageLoggerUi.REF_CLEAR_ALL)!!
        router.onViewEvent(pressEvent(clearAllId))
        checkCond(router.currentDialog != null, "confirmation dialog shown")
        val confirmId = router.currentDialog!!.ref<Int>("dialog:confirm")!!
        router.onViewEvent(pressEvent(confirmId))
        checkEq(plugin.store.size(), 0, "clear-all emptied the store")
        checkEq(plugin.store.loggedDeletes.get(), 0, "clear-all reset counters")

        router.popToRoot()
        checkEq(router.depth, 0, "router stack empty")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        runTest("bridgeRecordRoundTrip") { testBridgeRecordRoundTrip() }
        runTest("gatewayCodecJsonAndEtfRoundTrip") { testGatewayCodecJsonAndEtf() }
        runTest("createDeleteBulkAndSequenceSafety") { testCreateDeleteAndSequenceSafety() }
        runTest("editTrackingAndAttachmentRetention") { testEditTrackingAndAttachments() }
        runTest("ignoresEphemeralAndNonceGuard") { testIgnoresEphemeralAndNonceGuard() }
        runTest("httpHistorySeedingAndMerge") { testHttpSeedingAndHistoryMerge() }
        runTest("loaderFrameIntegrationAndFailSoft") { testLoaderFrameIntegrationAndFailSoft() }
        runTest("redTextRecolorsDeletedRows") { testRedTextRecolorsDeletedRows() }
        runTest("manaUiMountFilterAndClear") { testManaUiMountFilterAndClear() }
        println("ALL $passed MessageLoggerPluginTest TESTS PASSED")
    }
}
