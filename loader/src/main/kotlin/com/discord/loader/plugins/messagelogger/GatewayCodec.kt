package com.discord.loader.plugins.messagelogger

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

sealed class GwValue {
    object GwNull : GwValue()
    data class GwBool(val value: Boolean, val atomTag: Int = 0) : GwValue()
    data class GwInt(val value: Long) : GwValue()
    data class GwDouble(val value: Double) : GwValue()
    data class GwStr(val value: String, val etfTag: Int = TAG_BINARY) : GwValue() {
        companion object {
            const val TAG_ATOM = 100
            const val TAG_STRING = 107
            const val TAG_BINARY = 109
            const val TAG_SMALL_ATOM = 115
            const val TAG_ATOM_UTF8 = 118
            const val TAG_SMALL_ATOM_UTF8 = 119
        }
    }
    data class GwList(val values: MutableList<GwValue>) : GwValue()
    data class GwMap(
        val values: LinkedHashMap<String, GwValue>,

        val keyTags: LinkedHashMap<String, Int> = LinkedHashMap(),
    ) : GwValue()

    fun deepCopy(): GwValue = when (this) {
        GwNull -> GwNull
        is GwBool -> copy()
        is GwInt -> copy()
        is GwDouble -> copy()
        is GwStr -> copy()
        is GwList -> GwList(ArrayList<GwValue>(values.size).also { out ->
            for (item in values) out.add(item.deepCopy())
        })
        is GwMap -> GwMap(
            LinkedHashMap<String, GwValue>(values.size * 2).also { out ->
                for ((k, v) in values) out[k] = v.deepCopy()
            },
            LinkedHashMap(keyTags),
        )
    }
}

data class ParsedGateway(val encoding: GwEncoding, val value: GwValue)

enum class GwEncoding { JSON, ETF }

object GatewayCodec {

    const val PEEK_NON_DISPATCH = "\u0000"

    private const val NEW_FLOAT_EXT = 70
    private const val SMALL_INTEGER_EXT = 97
    private const val INTEGER_EXT = 98
    private const val ATOM_EXT = 100
    private const val NIL_EXT = 106
    private const val STRING_EXT = 107
    private const val LIST_EXT = 108
    private const val BINARY_EXT = 109
    private const val SMALL_BIG_EXT = 110
    private const val LARGE_BIG_EXT = 111
    private const val SMALL_ATOM_EXT = 115
    private const val MAP_EXT = 116
    private const val ATOM_UTF8_EXT = 118
    private const val SMALL_ATOM_UTF8_EXT = 119

    private const val ETF_VERSION = 0x83
    private const val MAX_DEPTH = 32
    private const val MAX_COUNT = 500_000
    private const val MAX_STRING_BYTES = 16 * 1024 * 1024
    private const val MAX_JSON_CHARS = 16 * 1024 * 1024

    fun sniff(bytes: ByteArray?): GwEncoding? {
        if (bytes == null || bytes.isEmpty()) return null
        return when (bytes[0].toInt() and 0xFF) {
            0x7B -> GwEncoding.JSON
            ETF_VERSION -> GwEncoding.ETF
            else -> null
        }
    }

    fun peekGatewayEventType(bytes: ByteArray?): String? {
        return when (sniff(bytes)) {
            GwEncoding.JSON -> peekJsonEventType(bytes!!)
            GwEncoding.ETF -> peekEtfEventType(bytes!!)
            null -> null
        }
    }

    private fun peekJsonEventType(bytes: ByteArray): String? {

        val limit = bytes.size.coerceAtMost(256)
        var pos = 0
        while (pos < limit && isAsciiWs(bytes[pos])) pos++
        if (pos >= limit || bytes[pos] != '{'.code.toByte()) return null
        pos++

        var fieldsChecked = 0
        while (pos < limit && fieldsChecked < 4) {
            while (pos < limit && isAsciiWs(bytes[pos])) pos++
            if (pos >= limit || bytes[pos] != '"'.code.toByte()) return null
            pos++
            val keyStart = pos
            while (pos < limit && bytes[pos] != '"'.code.toByte()) {
                if (bytes[pos] == '\\'.code.toByte()) return null
                pos++
            }
            if (pos >= limit) return null
            val keyLen = pos - keyStart
            pos++
            while (pos < limit && isAsciiWs(bytes[pos])) pos++
            if (pos >= limit || bytes[pos] != ':'.code.toByte()) return null
            pos++
            while (pos < limit && isAsciiWs(bytes[pos])) pos++
            if (pos >= limit) return null

            val isT = keyLen == 1 && bytes[keyStart] == 't'.code.toByte()
            val first = bytes[pos]
            if (isT) {
                if (first == 'n'.code.toByte() &&
                    pos + 4 <= limit &&
                    bytes[pos + 1] == 'u'.code.toByte() &&
                    bytes[pos + 2] == 'l'.code.toByte() &&
                    bytes[pos + 3] == 'l'.code.toByte()
                ) {
                    return PEEK_NON_DISPATCH
                }
                if (first != '"'.code.toByte()) return null
                pos++
                val valStart = pos
                while (pos < limit && bytes[pos] != '"'.code.toByte()) {
                    if (bytes[pos] == '\\'.code.toByte()) return null
                    pos++
                }
                if (pos >= limit) return null
                return String(bytes, valStart, pos - valStart, StandardCharsets.US_ASCII)
            }

            when (first) {
                '{'.code.toByte(), '['.code.toByte() -> return null
                '"'.code.toByte() -> {
                    pos++
                    while (pos < limit && bytes[pos] != '"'.code.toByte()) {
                        if (bytes[pos] == '\\'.code.toByte()) return null
                        pos++
                    }
                    if (pos >= limit) return null
                    pos++
                }
                else -> {
                    while (pos < limit && bytes[pos] != ','.code.toByte() && bytes[pos] != '}'.code.toByte() && !isAsciiWs(bytes[pos])) {
                        pos++
                    }
                }
            }
            while (pos < limit && isAsciiWs(bytes[pos])) pos++
            if (pos >= limit || bytes[pos] != ','.code.toByte()) return null
            pos++
            fieldsChecked++
        }
        return null
    }

    private fun peekEtfEventType(bytes: ByteArray): String? {
        if (bytes.size < 6 || (bytes[0].toInt() and 0xFF) != ETF_VERSION) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buf.position(1)
        if ((buf.get().toInt() and 0xFF) != MAP_EXT) return null
        if (buf.remaining() < 4) return null
        val count = buf.int
        if (count <= 0 || count > 32) return null
        var i = 0
        while (i < count && i < 6 && buf.hasRemaining()) {
            val k = readEtf(buf, 0) ?: return null
            val key = asKeyAndTag(k)?.first ?: return null
            if (key == "t") {
                val v = readEtf(buf, 0) ?: return null
                return when (v) {
                    GwValue.GwNull -> PEEK_NON_DISPATCH
                    is GwValue.GwStr -> v.value
                    else -> null
                }
            }
            if (key == "d") return null

            if (readEtf(buf, 0) == null) return null
            i++
        }
        return null
    }

    private fun isAsciiWs(b: Byte): Boolean =
        b == ' '.code.toByte() || b == '\n'.code.toByte() || b == '\r'.code.toByte() || b == '\t'.code.toByte()

    fun parse(bytes: ByteArray?): ParsedGateway? {
        val encoding = sniff(bytes) ?: return null
        val value = when (encoding) {
            GwEncoding.JSON -> parseJson(bytes!!)
            GwEncoding.ETF -> parseEtf(bytes!!)
        } ?: return null
        return ParsedGateway(encoding, value)
    }

    fun encode(value: GwValue, encoding: GwEncoding): ByteArray = when (encoding) {
        GwEncoding.JSON -> encodeJson(value)
        GwEncoding.ETF -> encodeEtf(value)
    }

    fun parseJson(bytes: ByteArray?): GwValue? {
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_JSON_CHARS) return null
        val text = String(bytes, StandardCharsets.UTF_8)
        val parser = JsonParser(text)
        val value = parser.parseDocument() ?: return null
        parser.skipWhitespace()
        return if (parser.atEnd()) value else null
    }

    fun encodeJson(value: GwValue): ByteArray {
        val sb = StringBuilder(256)
        writeJson(value, sb, 0)
        return sb.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private class JsonParser(private val s: String) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++ else break
            }
        }

        fun parseDocument(): GwValue? {
            skipWhitespace()
            return parseValue(0)
        }

        private fun parseValue(depth: Int): GwValue? {
            if (depth > MAX_DEPTH || pos >= s.length) return null
            skipWhitespace()
            if (pos >= s.length) return null
            return when (s[pos]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString()?.let { GwValue.GwStr(it) }
                't' -> consumeLiteral("true")?.let { GwValue.GwBool(true) }
                'f' -> consumeLiteral("false")?.let { GwValue.GwBool(false) }
                'n' -> consumeLiteral("null")?.let { GwValue.GwNull }
                '-', in '0'..'9' -> parseNumber()
                else -> null
            }
        }

        private fun consumeLiteral(lit: String): Boolean? {
            if (pos + lit.length > s.length || s.substring(pos, pos + lit.length) != lit) return null
            pos += lit.length
            return true
        }

        private fun parseObject(depth: Int): GwValue? {
            pos++
            val out = LinkedHashMap<String, GwValue>()
            skipWhitespace()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return GwValue.GwMap(out)
            }
            var count = 0
            while (pos < s.length) {
                skipWhitespace()
                if (pos >= s.length || s[pos] != '"') return null
                val key = parseString() ?: return null
                skipWhitespace()
                if (pos >= s.length || s[pos] != ':') return null
                pos++
                val value = parseValue(depth) ?: return null
                out[key] = value
                count++
                if (count > MAX_COUNT) return null
                skipWhitespace()
                if (pos >= s.length) return null
                if (s[pos] == '}') {
                    pos++
                    return GwValue.GwMap(out)
                }
                if (s[pos] != ',') return null
                pos++
            }
            return null
        }

        private fun parseArray(depth: Int): GwValue? {
            pos++
            val out = ArrayList<GwValue>()
            skipWhitespace()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return GwValue.GwList(out)
            }
            while (pos < s.length) {
                val value = parseValue(depth) ?: return null
                out.add(value)
                if (out.size > MAX_COUNT) return null
                skipWhitespace()
                if (pos >= s.length) return null
                if (s[pos] == ']') {
                    pos++
                    return GwValue.GwList(out)
                }
                if (s[pos] != ',') return null
                pos++
            }
            return null
        }

        private fun parseString(): String? {
            if (pos >= s.length || s[pos] != '"') return null
            pos++
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) return null
                        val e = s[pos++]
                        when (e) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) return null
                                val hex = s.substring(pos, pos + 4)
                                pos += 4
                                val code = hex.toIntOrNull(16) ?: return null
                                sb.append(code.toChar())
                            }
                            else -> return null
                        }
                    }
                    c.code < 0x20 -> return null
                    else -> sb.append(c)
                }
            }
            return null
        }

        private fun parseNumber(): GwValue? {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos].isDigit()) pos++
            var isDouble = false
            if (pos < s.length && s[pos] == '.') {
                isDouble = true
                pos++
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isDouble = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            if (pos == start) return null
            val token = s.substring(start, pos)
            return if (!isDouble) {
                token.toLongOrNull()?.let { GwValue.GwInt(it) } ?: token.toDoubleOrNull()?.let { GwValue.GwDouble(it) }
            } else {
                token.toDoubleOrNull()?.let { GwValue.GwDouble(it) }
            }
        }
    }

    private fun writeJson(value: GwValue, out: StringBuilder, depth: Int) {
        if (depth > MAX_DEPTH) {
            out.append("null")
            return
        }
        when (value) {
            GwValue.GwNull -> out.append("null")
            is GwValue.GwBool -> out.append(if (value.value) "true" else "false")
            is GwValue.GwInt -> out.append(value.value.toString())
            is GwValue.GwDouble -> {
                val d = value.value
                if (d.isNaN() || d.isInfinite()) {
                    out.append("null")
                } else {
                    out.append(d.toString())
                }
            }
            is GwValue.GwStr -> writeJsonString(value.value, out)
            is GwValue.GwList -> {
                out.append('[')
                for (i in value.values.indices) {
                    if (i > 0) out.append(',')
                    writeJson(value.values[i], out, depth + 1)
                }
                out.append(']')
            }
            is GwValue.GwMap -> {
                out.append('{')
                var first = true
                for ((key, v) in value.values) {
                    if (!first) out.append(',')
                    first = false
                    writeJsonString(key, out)
                    out.append(':')
                    writeJson(v, out, depth + 1)
                }
                out.append('}')
            }
        }
    }

    private fun writeJsonString(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c.code < 0x20) {
                    out.append("\\u")
                    val hex = Integer.toHexString(c.code)
                    out.append("0000", 0, 4 - hex.length)
                    out.append(hex)
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }

    fun parseEtf(bytes: ByteArray?): GwValue? {
        if (bytes == null || bytes.size < 2 || (bytes[0].toInt() and 0xFF) != ETF_VERSION) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buf.position(1)
        return readEtf(buf, 0)
    }

    private fun readEtf(buf: ByteBuffer, depth: Int): GwValue? {
        if (depth > MAX_DEPTH || !buf.hasRemaining()) return null
        return when (val tag = buf.get().toInt() and 0xFF) {

            NIL_EXT -> GwValue.GwList(ArrayList())
            SMALL_INTEGER_EXT -> {
                if (!buf.hasRemaining()) null else GwValue.GwInt((buf.get().toInt() and 0xFF).toLong())
            }
            INTEGER_EXT -> if (buf.remaining() < 4) null else GwValue.GwInt(buf.int.toLong())
            NEW_FLOAT_EXT -> if (buf.remaining() < 8) null else GwValue.GwDouble(java.lang.Double.longBitsToDouble(buf.long))
            SMALL_BIG_EXT, LARGE_BIG_EXT -> readBigInteger(buf, tag)
            ATOM_EXT, ATOM_UTF8_EXT -> readString(buf, 2, tag)?.let { decodeAtom(it, tag) }
            SMALL_ATOM_EXT, SMALL_ATOM_UTF8_EXT -> readString(buf, 1, tag)?.let { decodeAtom(it, tag) }
            STRING_EXT -> readString(buf, 2, tag)?.let { GwValue.GwStr(it, GwValue.GwStr.TAG_STRING) }
            BINARY_EXT -> readString(buf, 4, tag)?.let { GwValue.GwStr(it, GwValue.GwStr.TAG_BINARY) }
            LIST_EXT -> {
                if (buf.remaining() < 4) return null
                val count = buf.int
                if (count < 0 || count > MAX_COUNT) return null
                val out = ArrayList<GwValue>(count.coerceAtMost(1024))
                var i = 0
                while (i < count) {
                    out.add(readEtf(buf, depth + 1) ?: return null)
                    i++
                }

                if (!buf.hasRemaining()) return null
                val tailTag = buf.get().toInt() and 0xFF
                if (tailTag != NIL_EXT) return null
                GwValue.GwList(out)
            }
            MAP_EXT -> {
                if (buf.remaining() < 4) return null
                val count = buf.int
                if (count < 0 || count > MAX_COUNT) return null
                val out = LinkedHashMap<String, GwValue>(count.coerceAtMost(1024) * 2)
                val tags = LinkedHashMap<String, Int>(count.coerceAtMost(1024) * 2)
                var i = 0
                while (i < count) {
                    val k = readEtf(buf, depth + 1) ?: return null
                    val v = readEtf(buf, depth + 1) ?: return null
                    val key = asKeyAndTag(k) ?: return null
                    out[key.first] = v
                    tags[key.first] = key.second
                    i++
                }
                GwValue.GwMap(out, tags)
            }
            else -> null
        }
    }

    private fun decodeAtom(text: String, tag: Int): GwValue = when (text) {
        "nil" -> GwValue.GwNull
        "true" -> GwValue.GwBool(true, tag)
        "false" -> GwValue.GwBool(false, tag)
        else -> GwValue.GwStr(text, tag)
    }

    private fun asKeyAndTag(value: GwValue): Pair<String, Int>? = when (value) {
        is GwValue.GwStr -> Pair(value.value, value.etfTag)
        is GwValue.GwBool -> Pair(
            if (value.value) "true" else "false",
            value.atomTag.takeIf { it != 0 } ?: GwValue.GwStr.TAG_BINARY,
        )
        is GwValue.GwInt -> Pair(value.value.toString(), GwValue.GwStr.TAG_BINARY)
        else -> null
    }

    private fun readString(buf: ByteBuffer, lengthBytes: Int, tag: Int): String? {
        if (buf.remaining() < lengthBytes) return null
        val len = when (lengthBytes) {
            1 -> buf.get().toInt() and 0xFF
            2 -> buf.short.toInt() and 0xFFFF
            4 -> buf.int
            else -> return null
        }
        if (len < 0 || len > MAX_STRING_BYTES || len > buf.remaining()) return null
        val bytes = ByteArray(len)
        buf.get(bytes)
        val charset = if (tag == BINARY_EXT || tag == ATOM_UTF8_EXT || tag == SMALL_ATOM_UTF8_EXT) {
            StandardCharsets.UTF_8
        } else {
            StandardCharsets.ISO_8859_1
        }
        return String(bytes, charset)
    }

    private fun readBigInteger(buf: ByteBuffer, tag: Int): GwValue? {
        if (!buf.hasRemaining()) return null
        val n = if (tag == SMALL_BIG_EXT) buf.get().toInt() and 0xFF else buf.int
        if (n < 0 || n > 16 || !buf.hasRemaining()) return null
        val sign = buf.get().toInt() and 0xFF
        if (buf.remaining() < n) return null
        val digits = ByteArray(n)
        buf.get(digits)
        var magnitude = BigInteger.ZERO
        for (i in n - 1 downTo 0) {
            magnitude = magnitude.shl(8).or(BigInteger.valueOf((digits[i].toInt() and 0xFF).toLong()))
        }
        val result = if (sign == 0) magnitude else magnitude.negate()
        return if (result.bitLength() <= 63) {
            GwValue.GwInt(result.toLong())
        } else {
            GwValue.GwStr(result.toString(10), GwValue.GwStr.TAG_BINARY)
        }
    }

    fun encodeEtf(value: GwValue): ByteArray {
        val sink = ByteSink()
        sink.writeByte(ETF_VERSION)
        writeEtf(sink, value, 0)
        return sink.toByteArray()
    }

    private fun writeEtf(sink: ByteSink, value: GwValue, depth: Int) {
        if (depth > MAX_DEPTH) {
            writeAtom(sink, "nil", SMALL_ATOM_UTF8_EXT)
            return
        }
        when (value) {
            GwValue.GwNull -> writeAtom(sink, "nil", SMALL_ATOM_UTF8_EXT)
            is GwValue.GwBool -> writeAtom(sink, if (value.value) "true" else "false", value.atomTag)
            is GwValue.GwInt -> {
                val v = value.value
                when (v) {
                    in 0..255 -> {
                        sink.writeByte(SMALL_INTEGER_EXT)
                        sink.writeByte(v.toInt())
                    }
                    in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> {
                        sink.writeByte(INTEGER_EXT)
                        sink.writeIntBE(v.toInt())
                    }
                    else -> writeBigInteger(sink, BigInteger.valueOf(v))
                }
            }
            is GwValue.GwDouble -> {
                sink.writeByte(NEW_FLOAT_EXT)
                sink.writeLongBE(java.lang.Double.doubleToRawLongBits(value.value))
            }
            is GwValue.GwStr -> writeString(sink, value.value, value.etfTag)
            is GwValue.GwList -> {
                if (value.values.isEmpty()) {
                    sink.writeByte(NIL_EXT)
                } else {
                    sink.writeByte(LIST_EXT)
                    sink.writeIntBE(value.values.size)
                    for (item in value.values) writeEtf(sink, item, depth + 1)
                    sink.writeByte(NIL_EXT)
                }
            }
            is GwValue.GwMap -> {
                sink.writeByte(MAP_EXT)
                sink.writeIntBE(value.values.size)
                for ((key, v) in value.values) {
                    writeString(sink, key, value.keyTags[key] ?: GwValue.GwStr.TAG_BINARY)
                    writeEtf(sink, v, depth + 1)
                }
            }
        }
    }

    private fun writeAtom(sink: ByteSink, text: String, tagHint: Int) {
        if (tagHint == ATOM_EXT) {
            val bytes = text.toByteArray(StandardCharsets.ISO_8859_1)
            sink.writeByte(ATOM_EXT)
            sink.writeShortBE(bytes.size)
            sink.write(bytes)
            return
        }
        if (tagHint == SMALL_ATOM_EXT) {
            val bytes = text.toByteArray(StandardCharsets.ISO_8859_1)
            if (bytes.size <= 255) {
                sink.writeByte(SMALL_ATOM_EXT)
                sink.writeByte(bytes.size)
                sink.write(bytes)
                return
            }
        }
        val utf8 = text.toByteArray(StandardCharsets.UTF_8)
        if (tagHint == SMALL_ATOM_UTF8_EXT && utf8.size <= 255) {
            sink.writeByte(SMALL_ATOM_UTF8_EXT)
            sink.writeByte(utf8.size)
            sink.write(utf8)
            return
        }
        sink.writeByte(ATOM_UTF8_EXT)
        sink.writeShortBE(utf8.size)
        sink.write(utf8)
    }

    private fun writeString(sink: ByteSink, text: String, tagHint: Int) {
        when (tagHint) {
            GwValue.GwStr.TAG_ATOM -> writeAtom(sink, text, ATOM_EXT)
            GwValue.GwStr.TAG_SMALL_ATOM -> writeAtom(sink, text, SMALL_ATOM_EXT)
            GwValue.GwStr.TAG_ATOM_UTF8 -> writeAtom(sink, text, ATOM_UTF8_EXT)
            GwValue.GwStr.TAG_SMALL_ATOM_UTF8 -> writeAtom(sink, text, SMALL_ATOM_UTF8_EXT)
            GwValue.GwStr.TAG_STRING -> {
                val bytes = text.toByteArray(StandardCharsets.ISO_8859_1)
                sink.writeByte(STRING_EXT)
                sink.writeShortBE(bytes.size)
                sink.write(bytes)
            }
            else -> {
                val bytes = text.toByteArray(StandardCharsets.UTF_8)
                sink.writeByte(BINARY_EXT)
                sink.writeIntBE(bytes.size)
                sink.write(bytes)
            }
        }
    }

    private fun writeBigInteger(sink: ByteSink, value: BigInteger) {
        val negative = value.signum() < 0
        val digits = value.abs().toByteArray()

        var start = 0
        while (start < digits.size - 1 && digits[start] == 0.toByte()) start++
        val magnitude = digits.copyOfRange(start, digits.size)
        if (magnitude.size <= 255) {
            sink.writeByte(SMALL_BIG_EXT)
            sink.writeByte(magnitude.size)
        } else {
            sink.writeByte(LARGE_BIG_EXT)
            sink.writeIntBE(magnitude.size)
        }
        sink.writeByte(if (negative) 1 else 0)
        for (i in magnitude.indices.reversed()) sink.writeByte(magnitude[i].toInt())
    }

    private class ByteSink {
        private val out = ByteArrayOutputStream(256)

        fun writeByte(v: Int) = out.write(v and 0xFF)
        fun write(src: ByteArray) = out.write(src, 0, src.size)

        fun writeShortBE(v: Int) {
            writeByte(v shr 8)
            writeByte(v)
        }

        fun writeIntBE(v: Int) {
            writeByte(v shr 24)
            writeByte(v shr 16)
            writeByte(v shr 8)
            writeByte(v)
        }

        fun writeLongBE(v: Long) {
            writeByte((v shr 56).toInt())
            writeByte((v shr 48).toInt())
            writeByte((v shr 40).toInt())
            writeByte((v shr 32).toInt())
            writeByte((v shr 24).toInt())
            writeByte((v shr 16).toInt())
            writeByte((v shr 8).toInt())
            writeByte(v.toInt())
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
