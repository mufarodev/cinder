package com.discord.loader.plugins

import android.util.Log
import com.discord.loader.BridgeRegistry
import com.discord.loader.Frame
import com.discord.loader.Loader
import com.discord.loader.ModPlugin
import com.discord.loader.UiOps
import com.discord.loader.mana.ManaIcons
import com.discord.loader.plugins.mentionavatars.MentionAvatarsEngine
import com.discord.loader.plugins.messagelogger.BridgeRecord
import com.discord.loader.plugins.messagelogger.MessageLogStore
import com.discord.loader.plugins.messagelogger.MessageLoggerEngine
import com.discord.loader.plugins.messagelogger.int
import com.discord.loader.plugins.messagelogger.str
import java.util.concurrent.ConcurrentHashMap

class MentionAvatarsPlugin : ModPlugin {
    companion object {
        const val PLUGIN_ID = "mention-avatars"

        private val NEEDLES = arrayOf(
            byteArrayOf(0xE2.toByte(), 0x81.toByte(), 0xA8.toByte(), '@'.code.toByte()),
            "message.content:".toByteArray(Charsets.UTF_8),
        )

        private fun isEventTopic(frame: Frame, topic: String): Boolean {
            if (!frame.isEvent()) return false
            val id = BridgeRegistry.idOf(topic)
            return id >= 0 && frame.methodId() == id
        }
    }

    val engine = MentionAvatarsEngine()
    private val gatewaySockets = ConcurrentHashMap.newKeySet<Int>()
    private val messageRequests = ConcurrentHashMap.newKeySet<Int>()

    override fun id(): String = PLUGIN_ID

    override fun name(): String = "Mention Avatars"

    override fun description(): String = "Shows the user's avatar inside @mentions in chat"

    override fun icon(): String = ManaIcons.USER_CIRCLE

    override fun onHostCall(frame: Frame): ByteArray? {
        try {
            val idField = BridgeRegistry.idOf("id")
            if (idField < 0) return null
            if (BridgeRegistry.matches(frame, "WebSocket", "open") || BridgeRegistry.matches(frame, "WebSocket", "adopt")) {
                val record = BridgeRecord.parseRecord(frame.payload()) ?: return null
                val socket = record.int(idField) ?: return null
                val url = record.str(BridgeRegistry.idOf("url")) ?: return null
                if (MessageLogStore.isDiscordGatewayUrl(url)) gatewaySockets.add(socket) else gatewaySockets.remove(socket)
            } else if (BridgeRegistry.matches(frame, "WebSocket", "close")) {
                BridgeRecord.parseRecord(frame.payload())?.int(idField)?.let { gatewaySockets.remove(it) }
            } else if (BridgeRegistry.matches(frame, "Networking", "request")) {
                val record = BridgeRecord.parseRecord(frame.payload()) ?: return null
                val request = record.int(idField) ?: return null
                val method = record.str(BridgeRegistry.idOf("method")) ?: return null
                val url = record.str(BridgeRegistry.idOf("url")) ?: return null
                val reads = method.equals("GET", true) || method.equals("POST", true)
                if (reads && MessageLoggerEngine.extractChannelMessagesId(url) != null) messageRequests.add(request)
            }
        } catch (t: Throwable) {
            Log.w(Loader.TAG, "MentionAvatars: host-call tracking failed (fail-soft)", t)
        }
        return null
    }

    override fun onDispatch(frame: Frame): ByteArray? {
        try {
            if (isEventTopic(frame, "@ws")) {
                val record = BridgeRecord.parseRecord(frame.payload()) ?: return null
                val socket = record.int(BridgeRegistry.idOf("id")) ?: return null
                val event = record.str(BridgeRegistry.idOf("event")) ?: return null
                if (socket in gatewaySockets && (event == "binary" || event == "text")) {
                    engine.onGatewayPayload(frame.trailing())
                }
            } else if (isEventTopic(frame, "@http")) {
                val record = BridgeRecord.parseRecord(frame.payload()) ?: return null
                val request = record.int(BridgeRegistry.idOf("id")) ?: return null
                if (messageRequests.remove(request) && record.int(BridgeRegistry.idOf("status")) == 200) {
                    engine.onHttpBody(frame.trailing())
                }
            }
        } catch (t: Throwable) {
            Log.w(Loader.TAG, "MentionAvatars: dispatch parse failed (fail-soft)", t)
        }
        return null
    }

    override fun onUiOps(ops: ByteArray?, length: Int): ByteArray? {
        if (ops == null || !contains(ops, minOf(length, ops.size))) return null
        return try {
            val batch = UiOps.parse(ops, length) ?: return null
            if (engine.rewrite(batch)) batch.toBytes() else null
        } catch (t: Throwable) {
            Log.w(Loader.TAG, "MentionAvatars: UI rewrite failed (fail-soft)", t)
            null
        }
    }

    private fun contains(buf: ByteArray, length: Int): Boolean = NEEDLES.any { needle ->
        (0..length - needle.size).any { i -> needle.indices.all { buf[i + it] == needle[it] } }
    }
}
