package com.discord.loader.plugins.mentionavatars

import com.discord.loader.UiOps
import com.discord.loader.plugins.messagelogger.GatewayCodec
import com.discord.loader.plugins.messagelogger.GwValue
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.Locale

class MentionUser(val id: String, var avatarUrl: String?, val names: MutableSet<String>)

class MentionAvatarsEngine {
    private val lock = Any()
    private val byMessage = LinkedHashMap<String, List<MentionUser>>()
    private val users = LinkedHashMap<String, MentionUser>()
    private val contentRoots = HashMap<Int, String>()
    private val parents = HashMap<Int, Int>()

    fun onGatewayPayload(bytes: ByteArray?): Boolean {
        val type = GatewayCodec.peekGatewayEventType(bytes)
        if (type == GatewayCodec.PEEK_NON_DISPATCH) return false
        if (type != null && type != "MESSAGE_CREATE" && type != "MESSAGE_UPDATE") return false
        val root = GatewayCodec.parse(bytes)?.value as? GwValue.GwMap ?: return false
        val t = root.str("t")
        if (t != "MESSAGE_CREATE" && t != "MESSAGE_UPDATE") return false
        val d = root.map("d") ?: return false
        ingestMessage(d)
        return true
    }

    fun onHttpBody(body: ByteArray?) {
        when (val v = GatewayCodec.parseJson(body)) {
            is GwValue.GwList -> v.values.forEach { (it as? GwValue.GwMap)?.let(::ingestMessage) }
            is GwValue.GwMap -> ingestMessage(v)
            else -> {}
        }
    }

    private fun ingestMessage(m: GwValue.GwMap) {
        val id = m.snowflake("id") ?: return
        val guildId = m.snowflake("guild_id")
        synchronized(lock) {
            m.map("author")?.let { userFrom(it, guildId, m.map("member")) }
            val mentions = m.list("mentions") ?: return
            val list = ArrayList<MentionUser>(mentions.values.size)
            for (u in mentions.values) {
                val map = u as? GwValue.GwMap ?: continue
                userFrom(map, guildId, map.map("member"))?.let { list.add(it) }
            }
            byMessage.remove(id)
            byMessage[id] = list
            trim(byMessage, MAX_MESSAGES)
        }
    }

    private fun userFrom(u: GwValue.GwMap, guildId: String?, member: GwValue.GwMap?): MentionUser? {
        val id = u.snowflake("id") ?: return null
        val avatarUrl = avatarUrl(id, u.str("avatar"), u.str("discriminator"), guildId, member?.str("avatar"))
        val user = learnUser(id)
        user.avatarUrl = avatarUrl
        for (n in arrayOf(u.str("username"), u.str("global_name"), member?.str("nick"))) {
            if (!n.isNullOrEmpty()) user.names.add(n.lowercase(Locale.ROOT))
        }
        return user
    }

    fun rewrite(batch: UiOps): Boolean {
        val ids = IntArray(PROP_NAMES.size) { UiOps.propId(PROP_NAMES[it]) }
        if (ids.any { it < 0 }) return false
        val ops = batch.ops()
        synchronized(lock) {
            trackStructure(ops)
            var changed = false
            for (op in ops) {
                if (op.opcode() != UiOps.OP_CREATE && op.opcode() != UiOps.OP_UPDATE) continue
                val props = op.props() ?: continue
                val at = props.indexOfFirst { it.propId == ids[SPANS] }
                if (at < 0) continue
                val spans = props[at].value.asArray() ?: continue
                val message = contentRootOf(op.viewId()) ?: continue
                val out = withAvatars(spans, byMessage[message] ?: emptyList(), ids) ?: continue
                props[at] = UiOps.Prop(ids[SPANS], UiOps.Value.ofArray(out))
                changed = true
            }
            return changed
        }
    }

    private fun trackStructure(ops: List<UiOps.Op>) {
        val accessibilityId = UiOps.propId("accessibilityId")
        val text = UiOps.propId("text")
        val uri = UiOps.propId("uri")
        for (op in ops) {
            when (op.opcode()) {
                UiOps.OP_CREATE, UiOps.OP_UPDATE -> {
                    val props = op.props() ?: continue
                    val a = props.firstOrNull { it.propId == accessibilityId }?.value?.asString()
                    if (a != null && a.startsWith(CONTENT_PREFIX)) {
                        contentRoots[op.viewId()] = a.substring(CONTENT_PREFIX.length)
                    } else if (a != null && a.startsWith(AUTHOR_PREFIX)) {
                        val name = props.firstOrNull { it.propId == text }?.value?.asString()
                        if (!name.isNullOrEmpty()) learnUser(a.substring(AUTHOR_PREFIX.length)).names.add(name.lowercase(Locale.ROOT))
                    } else if (op.viewType() == "image") {
                        val u = props.firstOrNull { it.propId == uri }?.value?.asString()
                        if (u != null) learnAvatar(u)
                    }
                }
                UiOps.OP_CHILDREN -> op.childIds()?.forEach { parents[it] = op.viewId() }
                UiOps.OP_CHILD_EDITS -> op.childEdits()?.forEach { e ->
                    e.insertIds?.forEach { parents[it] = op.viewId() }
                }
                UiOps.OP_DELETE -> op.deleteIds()?.forEach {
                    contentRoots.remove(it)
                    parents.remove(it)
                }
            }
        }
        if (parents.size > MAX_VIEWS) parents.clear()
        if (contentRoots.size > MAX_VIEWS) contentRoots.clear()
    }

    private fun contentRootOf(viewId: Int): String? {
        var v = viewId
        for (hop in 0 until MAX_DEPTH) {
            contentRoots[v]?.let { return it }
            v = parents[v] ?: return null
        }
        return null
    }

    private fun learnAvatar(uri: String) {
        GUILD_AVATAR.find(uri)?.destructured?.let { (guild, user, hash) ->
            learnUser(user).avatarUrl = avatarUrl(user, null, null, guild, hash)
            return
        }
        USER_AVATAR.find(uri)?.destructured?.let { (user, hash) ->
            learnUser(user).avatarUrl = avatarUrl(user, hash, null, null, null)
        }
    }

    private fun learnUser(id: String): MentionUser {
        val user = users.remove(id) ?: MentionUser(id, null, HashSet())
        users[id] = user
        trim(users, MAX_USERS)
        return user
    }

    private fun withAvatars(spans: List<UiOps.Value>, users: List<MentionUser>, ids: IntArray): List<UiOps.Value>? {
        var out: ArrayList<UiOps.Value>? = null
        for ((i, span) in spans.withIndex()) {
            val props = span.asPropMap()
            val user = if (props == null) null else mentionedUser(props, users, ids)
            val avatar = user?.avatarUrl
            val alreadyDone = i > 0 && spans[i - 1].asPropMap()?.any { it.propId == ids[IMAGE] } == true
            if (avatar == null || alreadyDone) {
                out?.add(span)
                continue
            }
            if (out == null) out = ArrayList<UiOps.Value>(spans.size + 3).also { it.addAll(spans.subList(0, i)) }
            out.add(carrier(props!!, ids, advance = 3.5))
            out.add(carrier(props, ids, image = avatar))
            out.add(carrier(props, ids, advance = 2.0))
            out.add(span)
        }
        return out
    }

    private fun mentionedUser(span: List<UiOps.Prop>, users: List<MentionUser>, ids: IntArray): MentionUser? {
        if (span.none { it.propId == ids[LINK] } || span.none { it.propId == ids[BG] }) return null
        val raw = span.firstOrNull { it.propId == ids[TEXT] }?.value?.asString() ?: return null
        if (!raw.startsWith(FSI_AT)) return null
        val name = raw.trim(FSI, PDI).removePrefix("@").lowercase(Locale.ROOT)
        return users.firstOrNull { name in it.names } ?: uniqueKnownUser(name)
    }

    private fun uniqueKnownUser(name: String): MentionUser? {
        var found: MentionUser? = null
        for (u in users.values) {
            if (name !in u.names) continue
            if (found != null) return null
            found = u
        }
        return found
    }

    private fun carrier(mention: List<UiOps.Prop>, ids: IntArray, advance: Double? = null, image: String? = null): UiOps.Value {
        val props = ArrayList<UiOps.Prop>(8)
        props.add(UiOps.Prop(ids[TEXT], UiOps.Value.ofString(WORD_JOINER)))
        for (p in mention) if (p.propId in inherited(ids)) props.add(p)
        if (advance != null) props.add(UiOps.Prop(ids[ADVANCE], UiOps.Value.ofDouble(advance)))
        if (image != null) {
            props.add(
                UiOps.Prop(
                    ids[IMAGE],
                    UiOps.Value.ofPropMap(
                        listOf(
                            UiOps.Prop(ids[URI], UiOps.Value.ofString(image)),
                            UiOps.Prop(ids[SIZE], UiOps.Value.ofDouble(AVATAR_SIZE)),
                            UiOps.Prop(ids[RADIUS], UiOps.Value.ofDouble(AVATAR_SIZE / 2)),
                            UiOps.Prop(ids[POOL], UiOps.Value.ofString("small")),
                        ),
                    ),
                ),
            )
        }
        return UiOps.Value.ofPropMap(props)
    }

    private fun inherited(ids: IntArray) = intArrayOf(ids[WEIGHT], ids[COLOR], ids[BG], ids[BG_RADIUS], ids[LINK])

    private fun <V> trim(map: LinkedHashMap<String, V>, cap: Int) {
        if (map.size <= cap) return
        val it = map.entries.iterator()
        while (map.size > cap && it.hasNext()) {
            it.next()
            it.remove()
        }
    }

    private fun GwValue.GwMap.str(key: String): String? = (values[key] as? GwValue.GwStr)?.value
    private fun GwValue.GwMap.map(key: String): GwValue.GwMap? = values[key] as? GwValue.GwMap
    private fun GwValue.GwMap.list(key: String): GwValue.GwList? = values[key] as? GwValue.GwList
    private fun GwValue.GwMap.snowflake(key: String): String? = when (val v = values[key]) {
        is GwValue.GwStr -> v.value
        is GwValue.GwInt -> v.value.toString()
        else -> null
    }

    companion object {
        private const val MAX_MESSAGES = 2000
        private const val MAX_USERS = 4000
        private const val MAX_VIEWS = 30_000
        private const val MAX_DEPTH = 10
        private const val AVATAR_SIZE = 16.0
        private const val CONTENT_PREFIX = "message.content:"
        private const val AUTHOR_PREFIX = "message.author:"
        private val GUILD_AVATAR = Regex("/guilds/(\\d+)/users/(\\d+)/avatars/(\\w+)")
        private val USER_AVATAR = Regex("/avatars/(\\d+)/(\\w+)")
        private const val WORD_JOINER = "⁠"
        private const val FSI = '⁨'
        private const val PDI = '⁩'
        private const val FSI_AT = "⁨@"

        private val PROP_NAMES = arrayOf(
            "spans", "text", "weight", "color", "bg", "bgRadius", "link", "advance",
            "image", "uri", "size", "radius", "pool",
        )
        private const val SPANS = 0
        private const val TEXT = 1
        private const val WEIGHT = 2
        private const val COLOR = 3
        private const val BG = 4
        private const val BG_RADIUS = 5
        private const val LINK = 6
        private const val ADVANCE = 7
        private const val IMAGE = 8
        private const val URI = 9
        private const val SIZE = 10
        private const val RADIUS = 11
        private const val POOL = 12

        const val CDN = "https://cdn.discordapp.com"

        fun avatarUrl(id: String, hash: String?, discriminator: String?, guildId: String?, memberHash: String?): String {
            if (!memberHash.isNullOrEmpty() && guildId != null) {
                return "$CDN/guilds/$guildId/users/$id/avatars/$memberHash.png?size=64"
            }
            if (!hash.isNullOrEmpty()) return "$CDN/avatars/$id/$hash.png?size=64"
            val disc = discriminator?.toIntOrNull() ?: 0
            val index = if (disc != 0) disc % 5 else ((id.toLongOrNull() ?: 0L) shr 22).rem(6).toInt()
            return "$CDN/embed/avatars/$index.png"
        }
    }
}
