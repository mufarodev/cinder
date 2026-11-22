package com.discord.loader.plugins

import android.content.Context
import android.util.Log
import com.discord.loader.BridgeRegistry
import com.discord.loader.Frame
import com.discord.loader.Loader
import com.discord.loader.UiOps
import com.discord.loader.ModPlugin
import com.discord.loader.mana.ManaContext
import com.discord.loader.mana.ManaIcons
import com.discord.loader.mana.ManaPage
import com.discord.loader.plugins.messagelogger.BridgeRecord
import com.discord.loader.plugins.messagelogger.EngineDecision
import com.discord.loader.plugins.messagelogger.DeleteStyle
import com.discord.loader.plugins.messagelogger.MessageLogConfig
import com.discord.loader.plugins.messagelogger.MessageLoggerEngine
import com.discord.loader.plugins.messagelogger.MessageLogStore
import com.discord.loader.plugins.messagelogger.int
import com.discord.loader.plugins.messagelogger.str
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MessageLoggerPlugin : ModPlugin {
    companion object {
        private const val DELETED_TAG = "[Deleted]"

        private const val DELETED_RED = 0xFFF23F43L
        const val PLUGIN_ID = "message-logger"

        private const val FIELD_ID = "id"
        private const val FIELD_URL = "url"
        private const val FIELD_EVENT = "event"
        private const val FIELD_METHOD = "method"
        private const val FIELD_STATUS = "status"

        private fun isEventTopic(frame: Frame, topic: String): Boolean {
            if (!frame.isEvent()) return false
            val id = BridgeRegistry.idOf(topic)
            return id >= 0 && frame.methodId() == id
        }

        private fun frameTrailing(frame: Frame): ByteArray {
            val len = frame.trailingLength()
            if (len <= 0) return ByteArray(0)
            val raw = frame.raw()
            val out = ByteArray(len)
            System.arraycopy(raw, Frame.HEADER_SIZE + frame.payloadLength(), out, 0, len)
            return out
        }

        private fun frameWithTrailing(frame: Frame, newTrailing: ByteArray): ByteArray {
            val raw = frame.raw()
            val payLen = frame.payloadLength()
            val out = ByteArray(Frame.HEADER_SIZE + payLen + newTrailing.size)
            System.arraycopy(raw, 0, out, 0, Frame.HEADER_SIZE + payLen)
            ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(12, payLen)
            if (newTrailing.isNotEmpty()) {
                System.arraycopy(newTrailing, 0, out, Frame.HEADER_SIZE + payLen, newTrailing.size)
            }
            return out
        }
    }

    val config = MessageLogConfig()
    val store = MessageLogStore()
    val engine = MessageLoggerEngine(config, store)

    override fun id(): String = PLUGIN_ID

    override fun name(): String = "Message Logger"

    override fun version(): String = "1.1.0"

    override fun description(): String =
        "Logs deleted & edited messages and keeps removed attachments visible"

    override fun icon(): String = ManaIcons.CLOCK

    override fun isRequired(): Boolean = false

    override fun defaultEnabled(): Boolean = true

    override fun requiresRestart(): Boolean = false

    override fun onLoad(context: Context?) {
        config.load(context)
        store.setMaxCached(config.maxCachedMessages)
        Log.i(
            Loader.TAG,
            "MessageLoggerPlugin v${version()} loaded (deletes=${config.logDeletes}, " +
                "edits=${config.logEdits}, historyMerge=${config.mergeIntoHistory}, " +
                "maxCached=${config.maxCachedMessages})",
        )
    }

    override fun onEnabledChanged(enabled: Boolean) {
        Log.i(Loader.TAG, "MessageLoggerPlugin toggled -> $enabled (bridge hooks live)")
    }

    override fun onHostCall(frame: Frame): ByteArray? {
        val gatewayOpen = BridgeRegistry.matches(frame, "WebSocket", "open") ||
            BridgeRegistry.matches(frame, "WebSocket", "adopt")
        if (gatewayOpen) {
            val idField = BridgeRegistry.idOf(FIELD_ID)
            val urlField = BridgeRegistry.idOf(FIELD_URL)
            if (idField < 0 || urlField < 0) return null
            val record = BridgeRecord.parseRecord(frame.payload()) ?: return null
            val socketId = record.int(idField) ?: return null
            val url = record.str(urlField) ?: return null
            val isGateway = store.trackGatewaySocket(socketId, url)
            if (isGateway) {
                Log.i(Loader.TAG, "MessageLogger: tracking gateway socket $socketId ($url)")
            }
            return null
        }

        if (BridgeRegistry.matches(frame, "WebSocket", "close")) {
            val idField = BridgeRegistry.idOf(FIELD_ID)
            if (idField < 0) return null
            val record = BridgeRecord.parseRecord(frame.payload()) ?: return null
            val socketId = record.int(idField) ?: return null
            store.forgetSocket(socketId)
            return null
        }

        if (BridgeRegistry.matches(frame, "Networking", "request")) {
            val idField = BridgeRegistry.idOf(FIELD_ID)
            val methodField = BridgeRegistry.idOf(FIELD_METHOD)
            val urlField = BridgeRegistry.idOf(FIELD_URL)
            if (idField < 0 || methodField < 0 || urlField < 0) return null
            val record = BridgeRecord.parseRecord(frame.payload()) ?: return null
            val requestId = record.int(idField) ?: return null
            val method = record.str(methodField) ?: "GET"
            val url = record.str(urlField) ?: return null
            MessageLoggerEngine.extractDeleteMessageIds(method, url)?.let { (c, m) ->
                store.trackSelfDelete(requestId, c, m)
                Log.i(Loader.TAG, "MessageLogger: own DELETE request m=$m")
                return null
            }

            if (MessageLoggerEngine.isUsersMeUrl(url) ||
                (method.equals("GET", ignoreCase = true) && MessageLoggerEngine.extractChannelMessagesId(url) != null)
            ) {
                store.trackHttpRequest(requestId, method, url)
            }
            return null
        }
        return null
    }

    override fun onDispatch(frame: Frame): ByteArray? {
        if (isEventTopic(frame, "@ws")) {
            val idField = BridgeRegistry.idOf(FIELD_ID)
            val eventField = BridgeRegistry.idOf(FIELD_EVENT)
            if (idField < 0 || eventField < 0) return null
            val record = BridgeRecord.parseRecord(frame.payload()) ?: return null
            val socketId = record.int(idField) ?: return null
            val event = record.str(eventField) ?: return null
            val decision = engine.onWsEvent(socketId, event, frameTrailing(frame))
            return applyDecision(frame, decision)
        }

        if (isEventTopic(frame, "@http")) {
            val idField = BridgeRegistry.idOf(FIELD_ID)
            val statusField = BridgeRegistry.idOf(FIELD_STATUS)
            if (idField < 0 || statusField < 0) return null
            val record = BridgeRecord.parseRecord(frame.payload()) ?: return null
            val requestId = record.int(idField) ?: return null
            store.deleteRequestKey(requestId)?.let { k ->
                Log.i(Loader.TAG, "MessageLogger: own DELETE response key=$k status=${record.int(statusField)}")
                return null
            }
            if (store.httpRequest(requestId) == null) return null
            val status = record.int(statusField) ?: return null
            val decision = engine.onHttpResponse(requestId, status, frameTrailing(frame))
            return applyDecision(frame, decision)
        }
        return null
    }

    private val deletedNeedle = DELETED_TAG.toByteArray(Charsets.UTF_8)

    override fun onUiOps(opsBuf: ByteArray?, length: Int): ByteArray? {
        if (opsBuf == null || config.deleteStyle != DeleteStyle.RED_TEXT) return null
        val lim = minOf(length, opsBuf.size) - deletedNeedle.size
        var hit = false
        var i = 0
        while (i <= lim && !hit) {
            if (opsBuf[i] == deletedNeedle[0]) {
                var j = 1
                while (j < deletedNeedle.size && opsBuf[i + j] == deletedNeedle[j]) j++
                hit = j == deletedNeedle.size
            }
            i++
        }
        if (!hit) return null
        return try {
            val batch = UiOps.parse(opsBuf, length) ?: return null
            if (tintDeletedRows(batch)) batch.toBytes() else null
        } catch (t: Throwable) {
            Log.w(Loader.TAG, "MessageLogger: UI recolor failed (fail-soft)", t)
            null
        }
    }

    private fun tintDeletedRows(batch: UiOps): Boolean {
        var changed = false
        for (op in batch.ops()) {
            if (op.opcode() != UiOps.OP_CREATE && op.opcode() != UiOps.OP_UPDATE) continue
            val props = op.props() ?: continue
            var tagged = false
            for (i in props.indices) {
                val p = props[i]
                when (p.rawNameOrNull()) {
                    "text" -> p.value.asString()?.let { t ->
                        if (t.startsWith(DELETED_TAG)) {
                            props[i] = UiOps.Prop(p.propId, UiOps.Value.ofString(stripTag(t)))
                            tagged = true
                        }
                    }
                    "spans" -> {
                        val spans = p.value.asArray() ?: continue
                        val first = spans.firstOrNull()?.asPropMap() ?: continue
                        val ti = first.indexOfFirst { it.rawNameOrNull() == "text" }
                        val t = if (ti >= 0) first[ti].value.asString() else null
                        if (t == null || !t.startsWith(DELETED_TAG)) continue
                        first[ti] = UiOps.Prop(first[ti].propId, UiOps.Value.ofString(stripTag(t)))
                        for (span in spans) setColor(span.asPropMap() ?: continue)
                        tagged = true
                    }
                }
            }
            if (tagged) {
                setColor(props)
                changed = true
            }
        }
        return changed
    }

    private fun stripTag(t: String): String = t.removePrefix(DELETED_TAG).trimStart().ifEmpty { "(deleted)" }

    private fun setColor(props: MutableList<UiOps.Prop>) {
        val id = UiOps.propId("color")
        if (id < 0) return
        val red = UiOps.Prop(id, UiOps.Value.ofLong(DELETED_RED))
        val at = props.indexOfFirst { it.propId == id }
        if (at >= 0) props[at] = red else props.add(red)
    }

    private fun applyDecision(frame: Frame, decision: EngineDecision): ByteArray? {
        val replacement = decision.replacement ?: return null
        decision.note?.let { Log.i(Loader.TAG, "MessageLogger: $it") }
        return try {
            frameWithTrailing(frame, replacement)
        } catch (t: Throwable) {
            Log.w(Loader.TAG, "MessageLogger: frame rewrite failed (fail-soft)", t)
            null
        }
    }

    override fun createSettingsPage(ctx: ManaContext): ManaPage =
        MessageLoggerUi.settingsPage(this, ctx)
}
