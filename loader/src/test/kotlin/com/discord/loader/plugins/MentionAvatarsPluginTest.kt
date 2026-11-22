package com.discord.loader.plugins

import com.discord.loader.Loader
import com.discord.loader.UiOps
import com.discord.loader.plugins.mentionavatars.MentionAvatarsEngine

object MentionAvatarsPluginTest {
    private fun check(cond: Boolean, msg: String) {
        if (!cond) throw AssertionError("FAIL: $msg")
    }

    private fun registerProps() {
        UiOps.registerPropNames(
            arrayOf(
                "", "spans", "text", "weight", "color", "bg", "bgRadius", "link", "advance", "image",
                "uri", "size", "radius", "pool", "accessibilityId",
            ),
        )
    }

    private fun str(s: String) = UiOps.Value.ofString(s)

    private fun mentionSpan(name: String) = UiOps.Value.ofPropMap(
        listOf(
            UiOps.Prop.named("text", str("⁨@$name⁩")),
            UiOps.Prop.named("weight", UiOps.Value.ofInt(600)),
            UiOps.Prop.named("color", UiOps.Value.ofLong(4291415547L)),
            UiOps.Prop.named("bg", UiOps.Value.ofLong(1297638898L)),
            UiOps.Prop.named("bgRadius", UiOps.Value.ofInt(3)),
            UiOps.Prop.named("link", str("0")),
        ),
    )

    private fun plainSpan(text: String) = UiOps.Value.ofPropMap(listOf(UiOps.Prop.named("text", str(text))))

    private fun row(messageId: String, viewId: Int, spans: List<UiOps.Value>) = UiOps.create()
        .createView(viewId - 1, "view", listOf(UiOps.Prop.named("accessibilityId", str("message.content:$messageId"))))
        .createView(viewId, "text", listOf(UiOps.Prop.named("spans", UiOps.Value.ofArray(spans))))
        .setChildren(viewId - 1, viewId)
        .toBytes()

    private fun message(type: String, id: String, mentions: String) =
        """{"op":0,"s":5,"t":"$type","d":{"id":"$id","channel_id":"9","guild_id":"7",
            "author":{"id":"1","username":"alice"},"content":"hi","mentions":$mentions}}"""
            .toByteArray(Charsets.UTF_8)

    private fun spansOf(out: ByteArray): List<List<UiOps.Prop>> =
        UiOps.parse(out, out.size)!!.ops()[1].props().first { it.name() == "spans" }.value.asArray()!!.map { it.asPropMap()!! }

    private fun prop(span: List<UiOps.Prop>, name: String) = span.firstOrNull { it.name() == name }?.value

    private fun testGatewayMentionGetsAvatar() {
        val plugin = MentionAvatarsPlugin()
        plugin.engine.onGatewayPayload(
            message(
                "MESSAGE_CREATE", "100",
                """[{"id":"2","username":"bob","global_name":"Bobby","avatar":"abc","member":{"nick":"B-Man"}}]""",
            ),
        )
        for (shown in listOf("B-Man", "bobby", "bob")) {
            val bytes = row("100", 11, listOf(plainSpan("hi "), mentionSpan(shown), plainSpan(" there")))
            val out = plugin.onUiOps(bytes, bytes.size)
            check(out != null, "$shown rewritten")
            val spans = spansOf(out!!)
            check(spans.size == 6, "3 carriers inserted for $shown (got ${spans.size})")
            val image = prop(spans[2], "image")!!.asPropMap()!!
            check(image.first { it.name() == "uri" }.value.asString() == "https://cdn.discordapp.com/avatars/2/abc.png?size=64", "avatar uri")
            check(prop(spans[2], "bg")!!.asLong() == 1297638898L, "carrier inherits pill bg")
            check(prop(spans[2], "link") != null, "carrier inherits link")
            check(prop(spans[1], "advance") != null && prop(spans[3], "advance") != null, "padding carriers")
            check(prop(spans[4], "text")!!.asString() == "⁨@$shown⁩", "mention span kept")
        }
    }

    private fun testUnknownAndNonUserMentionsUntouched() {
        val plugin = MentionAvatarsPlugin()
        plugin.engine.onGatewayPayload(message("MESSAGE_CREATE", "100", """[{"id":"2","username":"bob"}]"""))
        val roleMention = row("100", 11, listOf(mentionSpan("Moderators")))
        check(plugin.onUiOps(roleMention, roleMention.size) == null, "role pill untouched")
        val otherMessage = row("101", 11, listOf(mentionSpan("dave")))
        check(plugin.onUiOps(otherMessage, otherMessage.size) == null, "unknown name untouched")
        val noBg = UiOps.Value.ofPropMap(listOf(UiOps.Prop.named("text", str("⁨@bob⁩"))))
        val plainText = row("100", 11, listOf(noBg))
        check(plugin.onUiOps(plainText, plainText.size) == null, "non-pill text untouched")
    }

    private fun testHttpHistoryEditAndDefaultAvatar() {
        val plugin = MentionAvatarsPlugin()
        plugin.engine.onHttpBody(
            """[{"id":"200","channel_id":"9","author":{"id":"1","username":"alice"},
                "mentions":[{"id":"4194304","username":"carol","avatar":null}]}]""".toByteArray(Charsets.UTF_8),
        )
        val first = row("200", 21, listOf(mentionSpan("carol")))
        val out = plugin.onUiOps(first, first.size)!!
        val uri = prop(spansOf(out)[1], "image")!!.asPropMap()!!.first { it.name() == "uri" }.value.asString()
        check(uri == "https://cdn.discordapp.com/embed/avatars/1.png", "default avatar by snowflake ($uri)")

        val update = UiOps.create()
            .createView(23, "text", listOf(UiOps.Prop.named("spans", UiOps.Value.ofArray(listOf(mentionSpan("carol"))))))
            .setChildren(21, 23)
        val bytes = update.toBytes()
        check(plugin.onUiOps(bytes, bytes.size) != null, "later text view under a tracked row is rewritten")
    }

    private fun authorRow(userId: String, name: String, avatarHash: String, messageId: String) = UiOps.create()
        .createView(31, "image", listOf(UiOps.Prop.named("uri", str("https://cdn.discordapp.com/avatars/$userId/$avatarHash.webp?size=128"))))
        .createView(32, "text", listOf(UiOps.Prop.named("text", str(name)), UiOps.Prop.named("accessibilityId", str("message.author:$userId"))))
        .createView(33, "view", listOf(UiOps.Prop.named("accessibilityId", str("message.content:$messageId"))))
        .createView(34, "text", listOf(UiOps.Prop.named("spans", UiOps.Value.ofArray(listOf(plainSpan("hello"))))))
        .setChildren(33, 34)
        .toBytes()

    private fun testCachedMessagesFallBackToSeenAuthors() {
        val plugin = MentionAvatarsPlugin()
        val seen = authorRow("2", "Bob", "abc", "300")
        check(plugin.onUiOps(seen, seen.size) == null, "plain author row untouched")
        val cached = row("301", 41, listOf(mentionSpan("bob")))
        val out = plugin.onUiOps(cached, cached.size)
        check(out != null, "cached mention resolved from author row")
        val uri = prop(spansOf(out!!)[1], "image")!!.asPropMap()!!.first { it.name() == "uri" }.value.asString()
        check(uri == "https://cdn.discordapp.com/avatars/2/abc.png?size=64", "uri from seen avatar ($uri)")

        val twin = authorRow("3", "Bob", "def", "302")
        plugin.onUiOps(twin, twin.size)
        val ambiguous = row("303", 51, listOf(mentionSpan("Bob")))
        check(plugin.onUiOps(ambiguous, ambiguous.size) == null, "ambiguous display name skipped")
    }

    private fun testFailSoft() {
        val plugin = MentionAvatarsPlugin()
        check(plugin.onUiOps(null, 0) == null, "null ops")
        val junk = byteArrayOf(1, 0x7f, 0x7f, 0x7f)
        check(plugin.onUiOps(junk, junk.size) == null, "junk ops")
        check(!MentionAvatarsEngine().onGatewayPayload("not json".toByteArray()), "junk gateway")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        var passed = 0
        for ((name, fn) in listOf<Pair<String, () -> Unit>>(
            "gatewayMentionGetsAvatar" to ::testGatewayMentionGetsAvatar,
            "unknownAndNonUserMentionsUntouched" to ::testUnknownAndNonUserMentionsUntouched,
            "httpHistoryEditAndDefaultAvatar" to ::testHttpHistoryEditAndDefaultAvatar,
            "cachedMessagesFallBackToSeenAuthors" to ::testCachedMessagesFallBackToSeenAuthors,
            "failSoft" to ::testFailSoft,
        )) {
            Loader.resetForTests()
            registerProps()
            fn()
            passed++
            println("  PASS $name")
        }
        println("ALL $passed MentionAvatarsPluginTest TESTS PASSED")
    }
}
