package com.discord.loader.plugins.messagelogger

import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class LoggedAttachment(
    val id: String,
    val filename: String?,
    val url: String?,
    var deleted: Boolean = false,

    val raw: GwValue.GwMap? = null,
)

data class EditEntry(
    val timestamp: String?,
    val content: String,
)

class LoggedMessage(
    val id: String,
    val channelId: String,
    var guildId: String?,
    val authorId: String,
    val authorName: String?,
    val authorBot: Boolean,
    var content: String?,
    val timestamp: String?,
    var editedTimestamp: String?,
    val attachments: MutableList<LoggedAttachment>,
    val nonce: String?,
    val flags: Int,

    var rawMessage: GwValue.GwMap? = null,
) {

    var displayContent: String? = content
    val editHistory: MutableList<EditEntry> = ArrayList()
    var deleted: Boolean = false
    var deletedAtMs: Long = 0L

    var sequence: Long = 0L

    val preview: String
        get() = (displayContent ?: content ?: "").replace('\n', ' ').take(120)
}

data class HttpRequestInfo(val method: String, val url: String)

class MessageLogStore {
    private val lock = Any()
    private val messages = LinkedHashMap<String, LoggedMessage>(64, 0.75f, true)

    @Volatile
    private var maxCached: Int = MessageLogConfig.DEFAULT_MAX_CACHED
    @Volatile
    var currentUserId: String? = null

    private val channelGuild = ConcurrentHashMap<String, String>()
    private val channelParent = ConcurrentHashMap<String, String>()
    private val gatewaySockets = ConcurrentHashMap.newKeySet<Int>()
    private val httpRequests = ConcurrentHashMap<Int, HttpRequestInfo>()

    private var nextSequence: Long = 0L
    val loggedDeletes = AtomicInteger()
    val loggedEdits = AtomicInteger()
    val loggedCreates = AtomicInteger()
    val seededFromHistory = AtomicInteger()
    val mergedIntoHistory = AtomicInteger()

    private fun evictOverflowLocked() {
        val cap = maxCached
        if (messages.size <= cap) return
        val it = messages.entries.iterator()
        while (messages.size > cap && it.hasNext()) {
            it.next()
            it.remove()
        }
    }

    fun setMaxCached(value: Int) {
        val safe = value.coerceIn(MessageLogConfig.MIN_MAX_CACHED, MessageLogConfig.MAX_MAX_CACHED)
        synchronized(lock) {
            maxCached = safe
            evictOverflowLocked()
        }
    }

    fun maxCached(): Int = maxCached

    fun put(message: LoggedMessage) {
        synchronized(lock) {
            message.sequence = nextSequence++
            messages[key(message.channelId, message.id)] = message
            evictOverflowLocked()
        }
    }

    fun seedFromHistory(message: LoggedMessage): Boolean {
        synchronized(lock) {
            val k = key(message.channelId, message.id)
            val existing = messages[k]
            if (existing == null) {
                message.sequence = nextSequence++
                messages[k] = message
                evictOverflowLocked()
                return true
            }
            if (existing.rawMessage == null && message.rawMessage != null) {
                existing.rawMessage = message.rawMessage
            }
            if (existing.guildId == null && message.guildId != null) {
                existing.guildId = message.guildId
            }
            return false
        }
    }

    fun get(channelId: String, id: String): LoggedMessage? = synchronized(lock) {
        messages[key(channelId, id)]
    }

    fun remove(channelId: String, id: String): LoggedMessage? = synchronized(lock) {
        messages.remove(key(channelId, id))
    }

    fun contains(channelId: String, id: String): Boolean = synchronized(lock) {
        messages.containsKey(key(channelId, id))
    }

    fun findByNonce(channelId: String, nonce: String, exceptId: String): LoggedMessage? = synchronized(lock) {
        if (nonce.isEmpty()) return@synchronized null
        for (msg in messages.values) {
            if (msg.channelId == channelId && msg.nonce == nonce && msg.id != exceptId) {
                return@synchronized msg
            }
        }
        null
    }

    fun markDeleted(channelId: String, id: String, atMs: Long, displayContent: String?): Boolean {
        synchronized(lock) {
            val msg = messages[key(channelId, id)] ?: return false
            if (msg.deleted) {
                return false
            }
            msg.deleted = true
            msg.deletedAtMs = atMs
            if (displayContent != null) {
                msg.displayContent = displayContent
            }
            for (attachment in msg.attachments) attachment.deleted = true
            loggedDeletes.incrementAndGet()
            return true
        }
    }

    fun markAttachmentDeleted(channelId: String, id: String, attachmentId: String): Boolean {
        synchronized(lock) {
            val msg = messages[key(channelId, id)] ?: return false
            var changed = false
            for (attachment in msg.attachments) {
                if (attachment.id == attachmentId && !attachment.deleted) {
                    attachment.deleted = true
                    changed = true
                }
            }
            return changed
        }
    }

    fun recordEdit(
        channelId: String,
        id: String,
        timestamp: String?,
        previousContent: String,
        newContent: String,
        displayContent: String? = null,
    ) {
        synchronized(lock) {
            val msg = messages[key(channelId, id)] ?: return
            msg.editHistory.add(EditEntry(timestamp, previousContent))
            msg.content = newContent
            msg.editedTimestamp = timestamp ?: msg.editedTimestamp
            msg.displayContent = displayContent ?: newContent
            msg.rawMessage?.let { raw ->
                raw.values["content"] = GwValue.GwStr(newContent)
                if (timestamp != null) {
                    raw.values["edited_timestamp"] = GwValue.GwStr(timestamp)
                }
            }
            loggedEdits.incrementAndGet()
        }
    }

    fun deletedMessagesInChannel(channelId: String): List<LoggedMessage> = synchronized(lock) {
        val out = ArrayList<LoggedMessage>()
        for (msg in messages.values) {
            if (msg.channelId == channelId && msg.deleted) {
                out.add(msg)
            }
        }
        out
    }

    fun snapshot(): List<LoggedMessage> = synchronized(lock) {
        ArrayList(messages.values).also { list ->
            list.sortWith(compareByDescending<LoggedMessage> { it.id.toLongOrNull() ?: it.sequence }
                .thenByDescending { it.sequence })
        }
    }

    fun size(): Int = synchronized(lock) { messages.size }

    fun clear() {
        synchronized(lock) {
            messages.clear()
            loggedDeletes.set(0)
            loggedEdits.set(0)
            loggedCreates.set(0)
            seededFromHistory.set(0)
            mergedIntoHistory.set(0)
            nextSequence = 0L
        }
        channelGuild.clear()
        channelParent.clear()
    }

    fun putChannelGuild(channelId: String, guildId: String?) {
        if (guildId.isNullOrEmpty()) return
        channelGuild[channelId] = guildId
    }

    fun guildOf(channelId: String): String? = channelGuild[channelId]

    fun putChannelParent(channelId: String, parentId: String?) {
        if (parentId.isNullOrEmpty()) return
        channelParent[channelId] = parentId
    }

    fun parentOf(channelId: String): String? = channelParent[channelId]

    fun trackGatewaySocket(id: Int, url: String): Boolean {
        val isGateway = isDiscordGatewayUrl(url)
        if (isGateway) gatewaySockets.add(id) else gatewaySockets.remove(id)
        return isGateway
    }

    fun isGatewaySocket(id: Int): Boolean = gatewaySockets.contains(id)

    fun forgetSocket(id: Int) {
        gatewaySockets.remove(id)
    }

    fun gatewaySocketCount(): Int = gatewaySockets.size

    private val selfDeletes: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val deleteRequests = ConcurrentHashMap<Int, String>()

    fun trackSelfDelete(requestId: Int, channelId: String, messageId: String) {
        val k = key(channelId, messageId)
        selfDeletes.add(k)
        deleteRequests[requestId] = k
    }

    fun deleteRequestKey(requestId: Int): String? = deleteRequests.remove(requestId)

    fun consumeSelfDelete(channelId: String, messageId: String): Boolean =
        selfDeletes.remove(key(channelId, messageId))

    fun trackHttpRequest(id: Int, method: String, url: String) {
        httpRequests[id] = HttpRequestInfo(method, url)
    }

    fun httpRequest(id: Int): HttpRequestInfo? = httpRequests[id]

    fun forgetHttpRequest(id: Int) {
        httpRequests.remove(id)
    }

    companion object {
        fun key(channelId: String, id: String): String = "$channelId:$id"

        fun isDiscordGatewayUrl(url: String): Boolean {
            val schemeEnd = url.indexOf("://")
            val hostStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
            var hostEnd = url.length
            for (i in hostStart until url.length) {
                val c = url[i]
                if (c == '/' || c == '?' || c == ':' || c == '#') {
                    hostEnd = i
                    break
                }
            }
            val host = url.substring(hostStart, hostEnd).lowercase(Locale.ROOT)
            return host.contains("gateway") && !host.contains("remote-auth")
        }
    }
}
