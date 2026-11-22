package com.discord.loader.plugins

import android.content.Context
import android.util.Log
import com.discord.loader.BridgeRegistry
import com.discord.loader.CinderSettingsPlugin
import com.discord.loader.Frame
import com.discord.loader.Loader
import com.discord.loader.ModPlugin
import com.discord.loader.ManaUi
import com.discord.loader.UiOps
import com.discord.loader.mana.ButtonStyle
import com.discord.loader.mana.CalloutTone
import com.discord.loader.mana.ColorToken
import com.discord.loader.mana.ManaContext
import com.discord.loader.mana.ManaIcons
import com.discord.loader.mana.ManaPage
import com.discord.loader.mana.ManaPalette
import com.discord.loader.mana.ManaScope
import com.discord.loader.mana.ManaWidgets
import com.discord.loader.mana.listState
import com.discord.loader.mana.manaPage
import com.discord.loader.mana.stringState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Applies find/replace rules to outgoing messages. The request body is the host call's
 * trailing bytes, so only the top-level JSON `"content"` string is rewritten.
 */
class TextReplacePlugin : ModPlugin {
    data class Rule(val find: String = "", val replace: String = "")

    companion object {
        const val PLUGIN_ID = "text-replace"
        private const val PREF_FILE = "cinder.text_replace"
        private const val PREF_RULES = "rule_list"
        private const val RULE_SEP = '\u001E'
        private const val FIELD_SEP = '\u001F'

        private val SEND_URL = Regex("""https?://[^/]+(/api(/v\d+)?)?/channels/\d+/messages(/\d+)?(\?.*)?""")

        fun encodeRules(rules: List<Rule>): String =
            rules.filter { it.find.isNotEmpty() }.joinToString(RULE_SEP.toString()) { it.find + FIELD_SEP + it.replace }

        fun decodeRules(text: String): List<Rule> {
            val out = ArrayList<Rule>()
            for (entry in text.split(RULE_SEP)) {
                val i = entry.indexOf(FIELD_SEP)
                if (i > 0) out.add(Rule(entry.substring(0, i), entry.substring(i + 1)))
            }
            return out
        }

        internal fun applyRules(text: String, rules: List<Rule>): String {
            var s = text
            for (r in rules) s = s.replace(r.find, r.replace)
            return s
        }

        internal fun rewriteJsonContent(body: String, rules: List<Rule>): String? {
            var depth = 0
            var i = 0
            while (i < body.length) {
                when (body[i]) {
                    '{', '[' -> i++.also { depth++ }
                    '}', ']' -> i++.also { depth-- }
                    '"' -> {
                        val end = stringEnd(body, i)
                        if (end < 0) return null
                        if (depth == 1 && end - i == 9 && body.startsWith("\"content\"", i)) {
                            var j = skipWs(body, end)
                            if (j >= body.length || body[j] != ':') { i = end; continue }
                            j = skipWs(body, j + 1)
                            if (j >= body.length || body[j] != '"') return null
                            val valEnd = stringEnd(body, j)
                            if (valEnd < 0) return null
                            val old = unescape(body.substring(j + 1, valEnd - 1)) ?: return null
                            val new = applyRules(old, rules)
                            if (new == old) return null
                            return body.substring(0, j) + quote(new) + body.substring(valEnd)
                        }
                        i = end
                    }
                    else -> i++
                }
            }
            return null
        }

        private fun stringEnd(s: String, start: Int): Int {
            var i = start + 1
            while (i < s.length) {
                when (s[i]) {
                    '\\' -> i += 2
                    '"' -> return i + 1
                    else -> i++
                }
            }
            return -1
        }

        private fun skipWs(s: String, from: Int): Int {
            var i = from
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
            return i
        }

        private fun unescape(s: String): String? {
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i++]
                if (c != '\\') { sb.append(c); continue }
                if (i >= s.length) return null
                when (val e = s[i++]) {
                    '"', '\\', '/' -> sb.append(e)
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000c')
                    'u' -> {
                        if (i + 4 > s.length) return null
                        sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: return null)
                        i += 4
                    }
                    else -> return null
                }
            }
            return sb.toString()
        }

        private fun quote(s: String): String {
            val sb = StringBuilder(s.length + 2).append('"')
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }

        /** Record strings carry an i32 LE byte length, which delimits the URL. */
        internal fun requestUrl(payload: ByteArray): String? {
            val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 4..payload.size - 7) {
                if (payload[i] != 'h'.code.toByte() || payload[i + 1] != 't'.code.toByte() ||
                    payload[i + 2] != 't'.code.toByte() || payload[i + 3] != 'p'.code.toByte()
                ) continue
                val len = bb.getInt(i - 4)
                if (len < 7 || len > payload.size - i) continue
                val url = String(payload, i, len, StandardCharsets.UTF_8)
                if (url.startsWith("http://") || url.startsWith("https://")) return url
            }
            return null
        }
    }

    // Every row shown in the settings page, including blank ones.
    @Volatile
    private var rules: List<Rule> = emptyList()

    // Rows with a non-empty find string; the only list the host-call hook reads.
    @Volatile
    private var active: List<Rule> = emptyList()

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    override fun id(): String = PLUGIN_ID

    override fun name(): String = "Text Replace"

    override fun version(): String = "1.0.0"

    override fun description(): String = "Automatically replace words in messages before you send them"

    override fun icon(): String = ManaIcons.PENCIL

    override fun defaultEnabled(): Boolean = true

    override fun requiresRestart(): Boolean = false

    override fun onLoad(context: Context?) {
        prefs = context?.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        setRules(decodeRules(prefs?.getString(PREF_RULES, "") ?: ""), persist = false)
    }

    // Written from the UI thread, read on wasm-guest: swap the whole list.
    fun setRules(list: List<Rule>, persist: Boolean = true) {
        rules = list
        active = list.filter { it.find.isNotEmpty() }
        if (persist) prefs?.edit()?.putString(PREF_RULES, encodeRules(list))?.apply()
    }

    val currentRules: List<Rule> get() = rules

    private fun updateRule(index: Int, change: (Rule) -> Rule) {
        val list = rules
        if (index in list.indices) setRules(list.toMutableList().also { it[index] = change(it[index]) })
    }

    override fun onHostCall(frame: Frame): ByteArray? {
        try {
            val current = active
            if (current.isEmpty() || frame.trailingLength() == 0) return null
            if (!BridgeRegistry.matches(frame, "Networking", "request")) return null
            val url = requestUrl(frame.payload()) ?: return null
            if (!SEND_URL.matches(url)) return null
            val body = String(frame.trailing(), StandardCharsets.UTF_8)
            if (!body.trimStart().startsWith("{")) return null
            val rewritten = rewriteJsonContent(body, current) ?: return null
            return frame.withTrailing(rewritten.toByteArray(StandardCharsets.UTF_8))
        } catch (t: Throwable) {
            Log.w(Loader.TAG, "TextReplacePlugin: pass-through after error: $t")
            return null
        }
    }

    private fun emitRuleRow(
        scope: ManaScope,
        batch: UiOps,
        palette: ManaPalette,
        index: Int,
        rule: Rule,
        onRemove: () -> Unit,
    ): Int {
        val cardId = scope.nextId()
        val headerId = scope.nextId()
        val titleId = scope.nextId()

        batch.createView(
            cardId,
            "view",
            ManaUi.props(
                ManaUi.prop("direction", UiOps.Value.ofString("column")),
                ManaUi.prop("width", UiOps.Value.ofString("fill")),
                ManaUi.prop("align", UiOps.Value.ofString("stretch")),
                ManaUi.prop("gap", UiOps.Value.ofInt(12)),
                ManaUi.prop("padding", UiOps.Value.ofFloat4(16f, 16f, 16f, 16f)),
                ManaUi.prop("radius", UiOps.Value.ofInt(12)),
                ManaUi.prop("background", ManaUi.colorVal(palette[ColorToken.BG_BASE_SECONDARY])),
            ),
        )
        scope.bindTheme(cardId, "background", ColorToken.BG_BASE_SECONDARY)

        batch.createView(
            headerId,
            "view",
            ManaUi.props(
                ManaUi.prop("direction", UiOps.Value.ofString("row")),
                ManaUi.prop("width", UiOps.Value.ofString("fill")),
                ManaUi.prop("align", UiOps.Value.ofString("center")),
            ),
        )
        batch.createView(
            titleId,
            "text",
            ManaUi.props(
                ManaUi.prop("text", UiOps.Value.ofString("Rule ${index + 1}")),
                ManaUi.prop("color", ManaUi.colorVal(palette[ColorToken.TEXT_PRIMARY])),
                ManaUi.prop("fontSize", UiOps.Value.ofInt(16)),
                ManaUi.prop("fontWeight", UiOps.Value.ofInt(600)),
                ManaUi.prop("flex", UiOps.Value.ofDouble(1.0)),
            ),
        )
        scope.bindTheme(titleId, "color", ColorToken.TEXT_PRIMARY)
        val removeId = scope.nextId()
        batch.createView(
            removeId,
            "view",
            ManaUi.props(
                ManaUi.prop("direction", UiOps.Value.ofString("row")),
                ManaUi.prop("width", UiOps.Value.ofInt(36)),
                ManaUi.prop("height", UiOps.Value.ofInt(36)),
                ManaUi.prop("align", UiOps.Value.ofString("center")),
                ManaUi.prop("justify", UiOps.Value.ofString("center")),
                ManaUi.prop("radius", UiOps.Value.ofInt(18)),
                ManaUi.prop("role", UiOps.Value.ofString("button")),
                ManaUi.prop("accessibilityLabel", UiOps.Value.ofString("Remove rule ${index + 1}")),
                ManaUi.prop("accessibilityId", UiOps.Value.ofString("text-replace.remove.$index")),
                ManaUi.prop("on", ManaUi.stringArray("press")),
            ),
        )
        val trashId = ManaUi.icon(batch, scope.allocator, ManaIcons.TRASH, 20, ColorToken.DANGER_TEXT, palette)
        batch.setChildren(removeId, trashId)
        scope.onPress(removeId, onRemove)
        batch.setChildren(headerId, titleId, removeId)

        val find = ManaWidgets.emitTextInput(
            scope, batch, palette,
            label = "Replace",
            placeholder = "Text to find",
            state = stringState(rule.find),
            helperText = null,
            errorState = null,
            multiline = false,
            clearable = true,
            accessibilityId = "text-replace.find.$index",
            onChange = { text -> updateRule(index) { it.copy(find = text) } },
            onSubmit = null,
        )
        val with = ManaWidgets.emitTextInput(
            scope, batch, palette,
            label = "With",
            placeholder = "Replacement",
            state = stringState(rule.replace),
            helperText = null,
            errorState = null,
            multiline = false,
            clearable = true,
            accessibilityId = "text-replace.with.$index",
            onChange = { text -> updateRule(index) { it.copy(replace = text) } },
            onSubmit = null,
        )
        batch.setChildren(cardId, headerId, find.rootId, with.rootId)
        return cardId
    }

    override fun createSettingsPage(ctx: ManaContext): ManaPage = manaPage(
        title = "Text Replace",
        titleAccessibilityId = "cinder.plugin.text-replace.title",
    ) {
        val enabledState = CinderSettingsPlugin.pluginEnabledState(this@TextReplacePlugin)
        if (rules.isEmpty()) setRules(listOf(Rule()), persist = false)
        val rows = listState(rules)

        fun replaceAll(next: List<Rule>) {
            setRules(next)
            rows.value = next
        }

        section(title = "Controls") {
            toggleRow(
                title = "Enable Text Replace",
                subtitle = "Applies to messages you send or edit",
                icon = ManaIcons.PENCIL,
                state = enabledState,
                accessibilityId = "text-replace.toggle.enabled",
            ) { next ->
                Loader.setPluginEnabled(this@TextReplacePlugin, next)
            }
        }

        section(
            title = "Rules",
            footer = "Rules are case-sensitive and run top to bottom.",
        ) {
            dynamicRows(rows) { batch, _, palette, index, rule ->
                emitRuleRow(scope, batch, palette, index, rule) {
                    replaceAll(rules.filterIndexed { i, _ -> i != index })
                }
            }
            button(
                label = "Add rule",
                icon = ManaIcons.PLUS_SMALL,
                style = ButtonStyle.SECONDARY,
                accessibilityId = "text-replace.add",
                refKey = "text-replace.add",
            ) {
                replaceAll(rules + Rule())
            }
        }

        callout(
            text = "Replacements are applied to the whole message, including text inside code blocks and links.",
            tone = CalloutTone.INFO,
        )
    }
}
