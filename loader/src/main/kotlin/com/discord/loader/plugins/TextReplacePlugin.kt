package com.discord.loader.plugins

import android.content.Context
import android.util.Log
import com.discord.loader.BridgeRegistry
import com.discord.loader.CinderSettingsPlugin
import com.discord.loader.Frame
import com.discord.loader.Loader
import com.discord.loader.ModPlugin
import com.discord.loader.mana.CalloutTone
import com.discord.loader.mana.ManaContext
import com.discord.loader.mana.ManaIcons
import com.discord.loader.mana.ManaPage
import com.discord.loader.mana.manaPage
import com.discord.loader.mana.stringState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Rewrites outgoing chat messages: before a message is sent (or edited), every rule
 * `find => replace` is applied, in order, to its text.
 *
 * Hooks `Networking.request` for `POST /channels/{id}/messages` and
 * `PATCH /channels/{id}/messages/{id}`. The JSON body travels in the frame's trailing bytes, so
 * only the top-level `"content"` string is edited in place and every other byte is preserved.
 * Fail-soft: any surprise returns `null` and the original frame goes through untouched.
 *
 * ponytail: literal, case-sensitive, applied sequentially (a rule sees earlier rules' output).
 * Add regex / ignore-case / whole-word flags if literal matching proves too blunt.
 */
class TextReplacePlugin : ModPlugin {
    class Rule(val find: String, val replace: String)

    companion object {
        const val PLUGIN_ID = "text-replace"
        const val REF_RULES_INPUT = "text-replace.input.rules"
        private const val PREF_FILE = "cinder.text_replace"
        private const val PREF_RULES = "rules"
        private const val SEP = "=>"

        private val SEND_URL = Regex("""https?://[^/]+(/api(/v\d+)?)?/channels/\d+/messages(/\d+)?(\?.*)?""")

        /** One rule per line: `find => replace`. Lines without `=>` or with an empty find are skipped. */
        fun parseRules(text: String): List<Rule> {
            val out = ArrayList<Rule>()
            for (line in text.split('\n')) {
                val i = line.indexOf(SEP)
                if (i < 0) continue
                val find = line.substring(0, i).trim()
                if (find.isEmpty()) continue
                out.add(Rule(find, line.substring(i + SEP.length).trim()))
            }
            return out
        }

        internal fun applyRules(text: String, rules: List<Rule>): String {
            var s = text
            for (r in rules) s = s.replace(r.find, r.replace)
            return s
        }

        /**
         * Returns [body] with its top-level `"content"` string run through [rules], or `null` if
         * there is no such string or no rule changed it.
         */
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
                            if (j >= body.length || body[j] != '"') return null // null / non-string content
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

        /** Index just past the closing quote of the JSON string starting at [start], or -1. */
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

        /** Decodes JSON string escapes (UTF-16 `\uXXXX` pairs combine naturally); null if malformed. */
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

        /** Encodes [s] as a quoted JSON string. */
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

        /**
         * Extracts the `url` string from a `Networking.request` Record payload: finds an
         * `http(s)://` run and trusts the i32 LE byte length that precedes every Record string.
         */
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

    @Volatile
    private var rules: List<Rule> = emptyList()

    @Volatile
    private var rulesText: String = ""

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
        setRulesText(prefs?.getString(PREF_RULES, "") ?: "", persist = false)
    }

    /** Swaps in a new immutable rule list (called from the UI thread; read on `wasm-guest`). */
    fun setRulesText(text: String, persist: Boolean = true) {
        rulesText = text
        rules = parseRules(text)
        if (persist) prefs?.edit()?.putString(PREF_RULES, text)?.apply()
    }

    override fun onHostCall(frame: Frame): ByteArray? {
        try {
            val current = rules
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

    override fun createSettingsPage(ctx: ManaContext): ManaPage = manaPage(
        title = "Text Replace",
        titleAccessibilityId = "cinder.plugin.text-replace.title",
    ) {
        val enabledState = CinderSettingsPlugin.pluginEnabledState(this@TextReplacePlugin)
        val rulesState = stringState(rulesText)

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

        section(title = "Rules") {
            textInput(
                label = "Replacement rules",
                placeholder = "teh => the\nbrb => be right back",
                state = rulesState,
                helperText = "One rule per line: text to find => replacement. Case-sensitive, applied top to bottom.",
                multiline = true,
                clearable = true,
                accessibilityId = REF_RULES_INPUT,
                refKey = REF_RULES_INPUT,
                onChange = { setRulesText(it) },
            )
        }

        callout(
            text = "Replacements are applied to the whole message, including text inside code blocks and links.",
            tone = CalloutTone.INFO,
        )
    }
}
