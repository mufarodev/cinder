package com.discord.loader.plugins.messagelogger

import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashMap

class EngineDecision(
    val replacement: ByteArray?,
    val mutated: Boolean,

    val note: String? = null,
) {
    companion object {
        val PASS: EngineDecision get() = EngineDecision(null, false, null)
    }
}

class MessageLoggerEngine(
    val config: MessageLogConfig,
    val store: MessageLogStore,
) {

    fun onWsEvent(socketId: Int, event: String, trailing: ByteArray?): EngineDecision {
        if (event == "close" || event == "error") {
            store.forgetSocket(socketId)
            return EngineDecision.PASS
        }
        if (!store.isGatewaySocket(socketId)) return EngineDecision.PASS
        if (event != "binary" && event != "text") return EngineDecision.PASS
        return processGatewayPayload(trailing)
    }

    fun processGatewayPayload(trailing: ByteArray?): EngineDecision {

        val peeked = GatewayCodec.peekGatewayEventType(trailing)
        if (peeked == GatewayCodec.PEEK_NON_DISPATCH) return EngineDecision.PASS
        if (peeked != null && peeked !in INTERESTED_EVENTS) return EngineDecision.PASS

        val parsed = GatewayCodec.parse(trailing) ?: return EngineDecision.PASS
        val root = parsed.value as? GwValue.GwMap ?: return EngineDecision.PASS
        val op = root.num("op") ?: return EngineDecision.PASS
        if (op != 0L) return EngineDecision.PASS
        val t = root.str("t") ?: return EngineDecision.PASS
        if (t !in INTERESTED_EVENTS) return EngineDecision.PASS
        val d = root.map("d") ?: return EngineDecision.PASS

        var note: String? = null
        val mutated = when (t) {
            "READY" -> {
                d.map("user")?.snowflake("id")?.let { store.currentUserId = it }
                d.list("guilds")?.values?.forEach { g ->
                    (g as? GwValue.GwMap)?.let { indexGuildChannels(it) }
                }
                false
            }
            "INITIAL_GUILD", "GUILD_CREATE" -> {
                indexGuildChannels(d)
                false
            }
            "CHANNEL_INFO" -> {
                val gid = d.snowflake("guild_id")
                if (gid != null) {
                    d.list("channels")?.values?.forEach { ch ->
                        (ch as? GwValue.GwMap)?.snowflake("id")?.let { store.putChannelGuild(it, gid) }
                    }
                }
                false
            }
            "CHANNEL_CREATE", "CHANNEL_UPDATE", "THREAD_CREATE" -> {
                indexChannel(d, d.snowflake("guild_id"))
                false
            }
            "THREAD_LIST_SYNC" -> {
                val gid = d.snowflake("guild_id")
                d.list("threads")?.values?.forEach { th ->
                    (th as? GwValue.GwMap)?.let { indexChannel(it, gid) }
                }
                false
            }
            "MESSAGE_CREATE" -> {
                val m = processCreate(d)
                if (m) note = "nonce-stripped m=${d.snowflake("id")}"
                m
            }
            "MESSAGE_UPDATE" -> {
                val m = processUpdate(d)
                if (m) note = "edit-rewrite c=${d.snowflake("channel_id")} m=${d.snowflake("id")}"
                m
            }
            "MESSAGE_DELETE" -> {
                val m = processDelete(d, root)
                if (m) note = "delete-rewrite c=${d.snowflake("channel_id")} m=${d.snowflake("id")} s=${root.num("s")} as=${(root.values["t"] as? GwValue.GwStr)?.value}"
                m
            }
            "MESSAGE_DELETE_BULK" -> {
                val m = processBulkDelete(d)
                if (m) note = "bulk-delete-rewrite c=${d.snowflake("channel_id")} s=${root.num("s")}"
                m
            }
            else -> false
        }

        if (!mutated) return EngineDecision.PASS
        return EngineDecision(GatewayCodec.encode(root, parsed.encoding), true, note)
    }

    fun processHttpPayload(method: String, url: String, status: Int, body: ByteArray?): EngineDecision {
        if (status != 200 || body == null || body.isEmpty()) return EngineDecision.PASS

        if (isUsersMeUrl(url)) {
            val value = GatewayCodec.parseJson(body) ?: return EngineDecision.PASS
            (value as? GwValue.GwMap)?.snowflake("id")?.let { store.currentUserId = it }
            return EngineDecision.PASS
        }

        if (!method.equals("GET", ignoreCase = true)) return EngineDecision.PASS
        val channelId = extractChannelMessagesId(url) ?: return EngineDecision.PASS

        val value = GatewayCodec.parseJson(body) ?: return EngineDecision.PASS
        val array = value as? GwValue.GwList ?: return EngineDecision.PASS

        val returnedIds = HashSet<String>(array.values.size * 2)
        var minSnowflake = Long.MAX_VALUE
        var maxSnowflake = Long.MIN_VALUE
        var seeded = 0
        var mutated = false

        for (item in array.values) {
            val map = item as? GwValue.GwMap ?: continue
            val msg = messageFrom(map) ?: continue
            returnedIds.add(msg.id)
            msg.id.toLongOrNull()?.let { sf ->
                if (sf < minSnowflake) minSnowflake = sf
                if (sf > maxSnowflake) maxSnowflake = sf
            }
            if (shouldIgnore(msg, isEdit = false)) continue
            if (store.seedFromHistory(msg)) {
                seeded++
            } else if (config.mergeIntoHistory && config.inlineEdits) {

                val existing = store.get(channelId, msg.id)
                if (existing != null && !existing.deleted && existing.editHistory.isNotEmpty()) {
                    val inline = buildInlineEditedContent(existing)
                    map.values["content"] = GwValue.GwStr(inline)
                    if (config.logDeletedAttachments && existing.attachments.any { it.deleted }) {
                        reappendDeletedAttachments(map, existing)
                    }
                    mutated = true
                }
            }
        }
        if (seeded > 0) {
            store.seededFromHistory.addAndGet(seeded)
        }

        var mergedCount = 0
        if (config.mergeIntoHistory && config.logDeletes) {
            val query = queryParams(url)
            val hasPaginationCursor = query.containsKey("before") ||
                query.containsKey("after") ||
                query.containsKey("around")
            val beforeCursor = query["before"]?.toLongOrNull()
            val afterCursor = query["after"]?.toLongOrNull()

            val deleted = store.deletedMessagesInChannel(channelId)
            val toInsert = ArrayList<Pair<Long, GwValue.GwMap>>()
            for (msg in deleted) {
                if (msg.id in returnedIds) continue
                if (isEphemeral(msg.flags) || shouldIgnore(msg, isEdit = false)) continue
                val sf = msg.id.toLongOrNull() ?: continue
                val raw = msg.rawMessage ?: continue

                val inWindow = when {
                    minSnowflake <= maxSnowflake -> {
                        if (!hasPaginationCursor) {

                            sf >= minSnowflake
                        } else if (beforeCursor != null) {
                            sf in minSnowflake until beforeCursor
                        } else if (afterCursor != null) {
                            sf in (afterCursor + 1)..maxSnowflake
                        } else {
                            sf in minSnowflake..maxSnowflake
                        }
                    }
                    else -> !hasPaginationCursor
                }
                if (!inWindow) continue

                val clone = raw.deepCopy() as GwValue.GwMap
                clone.values["content"] = GwValue.GwStr(msg.displayContent ?: formatDeletedContent(msg))
                if (config.logDeletedAttachments && msg.attachments.isNotEmpty()) {
                    clone.values["attachments"] = GwValue.GwList(
                        ArrayList(msg.attachments.map { attachmentToGw(it) }),
                    )
                }
                toInsert.add(Pair(sf, clone))
            }

            if (toInsert.isNotEmpty()) {
                val combined = ArrayList<Pair<Long, GwValue>>(array.values.size + toInsert.size)
                for (item in array.values) {
                    val sf = (item as? GwValue.GwMap)?.snowflake("id")?.toLongOrNull() ?: 0L
                    combined.add(Pair(sf, item))
                }
                for (entry in toInsert) {
                    combined.add(Pair(entry.first, entry.second))
                }

                combined.sortWith(compareByDescending<Pair<Long, GwValue>> { it.first })
                array.values.clear()
                for (entry in combined) {
                    array.values.add(entry.second)
                }
                mergedCount = toInsert.size
                store.mergedIntoHistory.addAndGet(mergedCount)
                mutated = true
            }
        }

        if (!mutated) return EngineDecision.PASS
        return EngineDecision(
            GatewayCodec.encodeJson(array),
            true,
            "http-history-merge c=$channelId merged=$mergedCount",
        )
    }

    fun onHttpResponse(requestId: Int, status: Int, body: ByteArray?): EngineDecision {
        val request = store.httpRequest(requestId) ?: return EngineDecision.PASS
        store.forgetHttpRequest(requestId)
        return processHttpPayload(request.method, request.url, status, body)
    }

    private fun indexGuildChannels(guild: GwValue.GwMap) {
        val gid = guild.snowflake("id") ?: return
        guild.list("channels")?.values?.forEach { ch ->
            (ch as? GwValue.GwMap)?.let { indexChannel(it, gid) }
        }
        guild.list("threads")?.values?.forEach { th ->
            (th as? GwValue.GwMap)?.let { indexChannel(it, gid) }
        }
    }

    private fun indexChannel(channel: GwValue.GwMap, fallbackGuildId: String?) {
        val id = channel.snowflake("id") ?: return
        val gid = channel.snowflake("guild_id") ?: fallbackGuildId
        if (gid != null) store.putChannelGuild(id, gid)
        channel.snowflake("parent_id")?.let { store.putChannelParent(id, it) }
    }

    private fun processCreate(d: GwValue.GwMap): Boolean {
        val msg = messageFrom(d) ?: return false
        var mutated = false

        val nonce = msg.nonce
        if (!nonce.isNullOrEmpty() && msg.authorId != store.currentUserId) {
            val victim = store.findByNonce(msg.channelId, nonce, msg.id)
            if (victim != null) {
                d.values.remove("nonce")
                d.keyTags.remove("nonce")
                mutated = true
            }
        }

        if (!shouldIgnore(msg, isEdit = false)) {
            store.put(msg)
            store.loggedCreates.incrementAndGet()
        }
        return mutated
    }

    private fun processUpdate(d: GwValue.GwMap): Boolean {
        val id = d.snowflake("id") ?: return false
        val channelId = d.snowflake("channel_id") ?: return false
        val cached = store.get(channelId, id) ?: return false
        if (isEphemeral(d.num("flags")?.toInt() ?: cached.flags)) return false
        if (shouldIgnore(cached, isEdit = true)) return false

        var mutated = false
        val newContent = d.str("content")
        val newEdited = d.str("edited_timestamp")

        var recordedEdit = false
        if (config.logEdits && newContent != null && !newEdited.isNullOrEmpty() &&
            newContent != cached.content
        ) {
            store.recordEdit(channelId, id, newEdited, cached.content ?: "", newContent)
            recordedEdit = true
        }

        if (config.inlineEdits && recordedEdit && cached.editHistory.isNotEmpty()) {
            val inline = buildInlineEditedContent(cached)
            cached.displayContent = inline
            val original = d.values["content"] as? GwValue.GwStr
            d.values["content"] = GwValue.GwStr(inline, original?.etfTag ?: GwValue.GwStr.TAG_BINARY)
            mutated = true
        }

        if (config.logDeletedAttachments && d.values["attachments"] is GwValue.GwList) {
            val presentIds = HashSet<String>()
            d.list("attachments")?.values?.forEach { item ->
                (item as? GwValue.GwMap)?.snowflake("id")?.let { presentIds.add(it) }
            }
            val removed = cached.attachments.filter { !it.deleted && it.id !in presentIds }
            if (removed.isNotEmpty()) {
                val array = d.values["attachments"] as GwValue.GwList
                for (attachment in removed) {
                    store.markAttachmentDeleted(channelId, id, attachment.id)
                    array.values.add(attachmentToGw(attachment))
                }
                mutated = true
            }
        }
        return mutated
    }

    private fun processDelete(d: GwValue.GwMap, root: GwValue.GwMap): Boolean {
        val id = d.snowflake("id") ?: return false
        val channelId = d.snowflake("channel_id") ?: return false
        val cached = store.get(channelId, id) ?: return false
        if (!config.logDeletes) return false
        if (isEphemeral(d.num("flags")?.toInt() ?: cached.flags)) return false
        if (shouldIgnore(cached, isEdit = false)) return false

        val formatted = if (cached.deleted && !cached.displayContent.isNullOrEmpty()) {
            cached.displayContent!!
        } else {
            formatDeletedContent(cached)
        }
        store.markDeleted(channelId, id, System.currentTimeMillis(), formatted)

        val tTag = (root.values["t"] as? GwValue.GwStr)?.etfTag ?: GwValue.GwStr.TAG_BINARY

        val own = store.consumeSelfDelete(channelId, id) && cached.rawMessage != null
        root.values["t"] = GwValue.GwStr(if (own) "MESSAGE_CREATE" else "MESSAGE_UPDATE", tTag)

        val keyTag = dominantKeyTag(d)

        cached.rawMessage?.let { raw ->
            for ((k, v) in raw.values) d.values[k] = v.deepCopy()
            for ((k, t) in raw.keyTags) d.keyTags[k] = t
        }
        if (!d.values.containsKey("id")) {
            d.values["id"] = GwValue.GwStr(id)
            if (keyTag != 0) d.keyTags["id"] = keyTag
        }
        if (!d.values.containsKey("channel_id")) {
            d.values["channel_id"] = GwValue.GwStr(channelId)
            if (keyTag != 0) d.keyTags["channel_id"] = keyTag
        }
        cached.guildId?.let { gid ->
            if (!d.values.containsKey("guild_id")) {
                d.values["guild_id"] = GwValue.GwStr(gid)
                if (keyTag != 0) d.keyTags["guild_id"] = keyTag
            }
        }

        val contentTag = (d.values["content"] as? GwValue.GwStr)?.etfTag ?: GwValue.GwStr.TAG_BINARY
        d.values["content"] = GwValue.GwStr(formatted, contentTag)
        if (keyTag != 0 && !d.keyTags.containsKey("content")) {
            d.keyTags["content"] = keyTag
        }

        if (config.logDeletedAttachments && cached.attachments.isNotEmpty()) {
            d.values["attachments"] = GwValue.GwList(
                ArrayList(cached.attachments.map { attachmentToGw(it) }),
            )
            if (keyTag != 0 && !d.keyTags.containsKey("attachments")) {
                d.keyTags["attachments"] = keyTag
            }
        }
        return true
    }

    private fun processBulkDelete(d: GwValue.GwMap): Boolean {
        val ids = d.values["ids"] as? GwValue.GwList ?: return false
        val channelId = d.snowflake("channel_id") ?: return false
        if (!config.logDeletes) return false

        val toForward = ArrayList<GwValue>(ids.values.size)
        var loggedAny = false
        for (value in ids.values) {
            val id = valueAsString(value) ?: run { toForward.add(value); continue }
            val cached = store.get(channelId, id)
            val passThrough = cached == null || isEphemeral(cached.flags) || shouldIgnore(cached, isEdit = false)
            if (passThrough) {
                toForward.add(value)
            } else {
                val formatted = if (cached.deleted && !cached.displayContent.isNullOrEmpty()) {
                    cached.displayContent!!
                } else {
                    formatDeletedContent(cached)
                }
                store.markDeleted(channelId, id, System.currentTimeMillis(), formatted)
                loggedAny = true
            }
        }
        if (!loggedAny || toForward.size == ids.values.size) return false
        ids.values.clear()
        ids.values.addAll(toForward)
        return true
    }

    private fun messageFrom(d: GwValue.GwMap): LoggedMessage? {
        val id = d.snowflake("id") ?: return null
        val channelId = d.snowflake("channel_id") ?: return null
        val author = d.map("author") ?: return null
        val authorId = author.snowflake("id") ?: return null
        val authorName = author.str("username") ?: author.str("global_name")
        val authorBot = author.bool("bot") ?: false
        val attachments = ArrayList<LoggedAttachment>()
        d.list("attachments")?.values?.forEach { item ->
            val a = item as? GwValue.GwMap ?: return@forEach
            val aid = a.snowflake("id") ?: return@forEach
            attachments.add(
                LoggedAttachment(
                    id = aid,
                    filename = a.str("filename"),
                    url = a.str("url"),
                    deleted = false,
                    raw = a.deepCopy() as GwValue.GwMap,
                ),
            )
        }
        val nonce = d.values["nonce"]?.let { valueAsString(it) }
        val flags = (d.num("flags") ?: 0L).toInt()
        return LoggedMessage(
            id = id,
            channelId = channelId,
            guildId = d.snowflake("guild_id"),
            authorId = authorId,
            authorName = authorName,
            authorBot = authorBot,
            content = d.str("content"),
            timestamp = d.str("timestamp"),
            editedTimestamp = d.str("edited_timestamp"),
            attachments = attachments,
            nonce = nonce,
            flags = flags,
            rawMessage = d.deepCopy() as GwValue.GwMap,
        )
    }

    private fun attachmentToGw(a: LoggedAttachment): GwValue.GwMap {
        if (a.raw != null) {
            return a.raw.deepCopy() as GwValue.GwMap
        }
        val fields = LinkedHashMap<String, GwValue>()
        fields["id"] = GwValue.GwStr(a.id)
        fields["filename"] = if (a.filename != null) GwValue.GwStr(a.filename) else GwValue.GwNull
        fields["size"] = GwValue.GwInt(0)
        fields["url"] = if (a.url != null) GwValue.GwStr(a.url) else GwValue.GwNull
        fields["proxy_url"] = if (a.url != null) GwValue.GwStr(a.url) else GwValue.GwNull
        return GwValue.GwMap(fields)
    }

    private fun reappendDeletedAttachments(map: GwValue.GwMap, cached: LoggedMessage) {
        val list = (map.values["attachments"] as? GwValue.GwList)
            ?: GwValue.GwList(ArrayList()).also { map.values["attachments"] = it }
        val present = HashSet<String>()
        for (item in list.values) {
            (item as? GwValue.GwMap)?.snowflake("id")?.let { present.add(it) }
        }
        for (att in cached.attachments) {
            if (att.deleted && att.id !in present) {
                list.values.add(attachmentToGw(att))
            }
        }
    }

    private fun buildInlineEditedContent(msg: LoggedMessage): String {
        val sb = StringBuilder()
        for (entry in msg.editHistory.takeLast(INLINE_EDIT_LIMIT)) {
            sb.append("~~").append(entry.content).append("~~ *(edited)*\n")
        }
        sb.append(msg.content ?: "")
        return sb.toString()
    }

    private fun formatDeletedContent(msg: LoggedMessage): String {
        val original = if (config.inlineEdits && msg.editHistory.isNotEmpty()) {
            buildInlineEditedContent(msg)
        } else {
            msg.content ?: ""
        }
        if (original.isEmpty()) return "[Deleted]"
        return when (config.deleteStyle) {
            DeleteStyle.INLINE_TAG -> "[Deleted] $original"
            DeleteStyle.STRIKETHROUGH -> "~~$original~~ *(deleted)*"

            DeleteStyle.RED_TEXT -> "[Deleted] $original"
        }
    }

    private fun dominantKeyTag(map: GwValue.GwMap): Int {
        for (tag in map.keyTags.values) {
            if (tag != 0) return tag
        }
        return 0
    }

    private fun isEphemeral(flags: Int): Boolean = flags and 64 != 0

    private fun shouldIgnore(msg: LoggedMessage, isEdit: Boolean): Boolean {
        if (config.ignoreBots && msg.authorBot) return true
        if (config.ignoreSelf && !store.currentUserId.isNullOrEmpty() && msg.authorId == store.currentUserId) {
            return true
        }
        if (config.ignoreUsers().contains(msg.authorId)) return true
        val guildId = msg.guildId ?: store.guildOf(msg.channelId)
        if (guildId != null && config.ignoreGuilds().contains(guildId)) return true
        if (config.ignoreChannels().contains(msg.channelId)) return true
        val parentId = store.parentOf(msg.channelId)
        if (parentId != null && config.ignoreChannels().contains(parentId)) return true
        if (isEdit && msg.deleted) return true
        return false
    }

    private fun valueAsString(value: GwValue): String? = when (value) {
        is GwValue.GwStr -> value.value
        is GwValue.GwInt -> value.value.toString()
        is GwValue.GwDouble -> value.value.toString()
        is GwValue.GwBool -> if (value.value) "true" else "false"
        else -> null
    }

    private fun GwValue.GwMap.num(key: String): Long? = values[key]?.let { v ->
        when (v) {
            is GwValue.GwInt -> v.value
            is GwValue.GwDouble -> v.value.toLong()
            else -> null
        }
    }

    private fun GwValue.GwMap.str(key: String): String? = (values[key] as? GwValue.GwStr)?.value

    private fun GwValue.GwMap.snowflake(key: String): String? = when (val v = values[key]) {
        is GwValue.GwStr -> v.value
        is GwValue.GwInt -> v.value.toString()
        else -> null
    }

    private fun GwValue.GwMap.bool(key: String): Boolean? = (values[key] as? GwValue.GwBool)?.value

    private fun GwValue.GwMap.map(key: String): GwValue.GwMap? = values[key] as? GwValue.GwMap

    private fun GwValue.GwMap.list(key: String): GwValue.GwList? = values[key] as? GwValue.GwList

    companion object {
        private const val INLINE_EDIT_LIMIT = 3

        private val INTERESTED_EVENTS = setOf(
            "READY",
            "INITIAL_GUILD",
            "GUILD_CREATE",
            "CHANNEL_INFO",
            "CHANNEL_CREATE",
            "CHANNEL_UPDATE",
            "THREAD_CREATE",
            "THREAD_LIST_SYNC",
            "MESSAGE_CREATE",
            "MESSAGE_UPDATE",
            "MESSAGE_DELETE",
            "MESSAGE_DELETE_BULK",
        )

        fun stripQuery(url: String): String {
            val q = url.indexOf('?')
            val path = if (q >= 0) url.substring(0, q) else url
            return if (path.endsWith("/")) path.dropLast(1) else path
        }

        fun isUsersMeUrl(url: String): Boolean {
            val path = stripQuery(url)
            return path.endsWith("/users/@me")
        }

        fun extractChannelMessagesId(url: String): String? {
            val path = stripQuery(url)
            if (!path.endsWith("/messages")) return null
            val beforeMessages = path.substring(0, path.length - "/messages".length)
            val marker = "/channels/"
            val idx = beforeMessages.lastIndexOf(marker)
            if (idx < 0) return null
            val channelId = beforeMessages.substring(idx + marker.length)
            if (channelId.isEmpty() || !channelId.all { it.isDigit() }) return null
            return channelId
        }

        fun extractDeleteMessageIds(method: String, url: String): Pair<String, String>? {
            if (!method.equals("DELETE", ignoreCase = true)) return null
            val parts = stripQuery(url).split('/')
            val n = parts.size
            if (n < 4 || parts[n - 2] != "messages" || parts[n - 4] != "channels") return null
            val mid = parts[n - 1]
            val cid = parts[n - 3]
            if (mid.isEmpty() || cid.isEmpty() || !mid.all { it.isDigit() } || !cid.all { it.isDigit() }) return null
            return Pair(cid, mid)
        }

        fun queryParams(url: String): Map<String, String> {
            val q = url.indexOf('?')
            if (q < 0 || q + 1 >= url.length) return emptyMap()
            val out = HashMap<String, String>()
            for (pair in url.substring(q + 1).split('&')) {
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    out[pair.substring(0, eq)] = pair.substring(eq + 1)
                }
            }
            return out
        }
    }
}
