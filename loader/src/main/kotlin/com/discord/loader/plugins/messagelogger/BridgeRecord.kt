package com.discord.loader.plugins.messagelogger

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

sealed class RValue {
    object RNull : RValue()
    data class RBool(val value: Boolean) : RValue()
    data class RInt32(val value: Int) : RValue()
    data class RInt64(val value: Long) : RValue()
    data class RDouble(val value: Double) : RValue()
    data class RString(val value: String) : RValue()
    data class RArray(val values: List<RValue>) : RValue()
    data class RRecord(val fields: LinkedHashMap<Int, RValue>) : RValue()
    data class RStringMap(val entries: LinkedHashMap<String, RValue>) : RValue()
}

object BridgeRecord {
    const val TAG_NULL: Int = 0x00
    const val TAG_FALSE: Int = 0x01
    const val TAG_TRUE: Int = 0x02
    const val TAG_INT32: Int = 0x03
    const val TAG_INT64: Int = 0x04
    const val TAG_FLOAT64: Int = 0x05
    const val TAG_STRING: Int = 0x06
    const val TAG_ARRAY: Int = 0x07
    const val TAG_RECORD: Int = 0x08
    const val TAG_STRING_MAP: Int = 0x0A

    private const val MAX_DEPTH = 16
    private const val MAX_COUNT = 100_000
    private const val MAX_STRING_BYTES = 8 * 1024 * 1024

    fun parseRecord(payload: ByteArray?): RValue.RRecord? {
        if (payload == null || payload.isEmpty()) return null
        val value = parseValue(ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN), 0) ?: return null
        return value as? RValue.RRecord
    }

    fun parseValue(payload: ByteArray?): RValue? {
        if (payload == null || payload.isEmpty()) return null
        return parseValue(ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN), 0)
    }

    private fun parseValue(buf: ByteBuffer, depth: Int): RValue? {
        if (depth > MAX_DEPTH || !buf.hasRemaining()) return null
        return when (val tag = buf.get().toInt() and 0xFF) {
            TAG_NULL -> RValue.RNull
            TAG_FALSE -> RValue.RBool(false)
            TAG_TRUE -> RValue.RBool(true)
            TAG_INT32 -> if (buf.remaining() < 4) null else RValue.RInt32(buf.int)
            TAG_INT64 -> if (buf.remaining() < 8) null else RValue.RInt64(buf.long)
            TAG_FLOAT64 -> if (buf.remaining() < 8) null else RValue.RDouble(java.lang.Double.longBitsToDouble(buf.long))
            TAG_STRING -> {
                if (buf.remaining() < 4) return null
                val len = buf.int
                if (len < 0 || len > MAX_STRING_BYTES || len > buf.remaining()) return null
                val bytes = ByteArray(len)
                buf.get(bytes)
                RValue.RString(String(bytes, StandardCharsets.UTF_8))
            }
            TAG_ARRAY -> {
                if (buf.remaining() < 4) return null
                val count = buf.int
                if (count < 0 || count > MAX_COUNT) return null
                val out = ArrayList<RValue>(count.coerceAtMost(1024))
                var i = 0
                while (i < count) {
                    out.add(parseValue(buf, depth + 1) ?: return null)
                    i++
                }
                RValue.RArray(out)
            }
            TAG_RECORD -> {
                if (buf.remaining() < 4) return null
                val count = buf.int
                if (count < 0 || count > MAX_COUNT) return null
                val out = LinkedHashMap<Int, RValue>(count.coerceAtMost(1024) * 2)
                var i = 0
                while (i < count) {
                    if (buf.remaining() < 2) return null
                    val fieldId = buf.short.toInt() and 0xFFFF
                    out[fieldId] = parseValue(buf, depth + 1) ?: return null
                    i++
                }
                RValue.RRecord(out)
            }
            TAG_STRING_MAP -> {
                if (buf.remaining() < 4) return null
                val count = buf.int
                if (count < 0 || count > MAX_COUNT) return null
                val out = LinkedHashMap<String, RValue>(count.coerceAtMost(1024) * 2)
                var i = 0
                while (i < count) {
                    if (buf.remaining() < 4) return null
                    val len = buf.int
                    if (len < 0 || len > MAX_STRING_BYTES || len > buf.remaining()) return null
                    val bytes = ByteArray(len)
                    buf.get(bytes)
                    out[String(bytes, StandardCharsets.UTF_8)] = parseValue(buf, depth + 1) ?: return null
                    i++
                }
                RValue.RStringMap(out)
            }
            else -> null
        }
    }

    fun writeRecord(fields: Map<Int, RValue>): ByteArray {
        val sink = ByteSink()
        sink.writeByte(TAG_RECORD)
        sink.writeInt(fields.size)
        for ((fieldId, value) in fields) {
            sink.writeShort(fieldId)
            writeValue(sink, value)
        }
        return sink.toByteArray()
    }

    fun writeValue(value: RValue): ByteArray {
        val sink = ByteSink()
        writeValue(sink, value)
        return sink.toByteArray()
    }

    private fun writeValue(sink: ByteSink, value: RValue) {
        when (value) {
            RValue.RNull -> sink.writeByte(TAG_NULL)
            is RValue.RBool -> sink.writeByte(if (value.value) TAG_TRUE else TAG_FALSE)
            is RValue.RInt32 -> {
                sink.writeByte(TAG_INT32)
                sink.writeInt(value.value)
            }
            is RValue.RInt64 -> {
                sink.writeByte(TAG_INT64)
                sink.writeLong(value.value)
            }
            is RValue.RDouble -> {
                sink.writeByte(TAG_FLOAT64)
                sink.writeLong(java.lang.Double.doubleToRawLongBits(value.value))
            }
            is RValue.RString -> {
                val bytes = value.value.toByteArray(StandardCharsets.UTF_8)
                sink.writeByte(TAG_STRING)
                sink.writeInt(bytes.size)
                sink.write(bytes)
            }
            is RValue.RArray -> {
                sink.writeByte(TAG_ARRAY)
                sink.writeInt(value.values.size)
                for (item in value.values) writeValue(sink, item)
            }
            is RValue.RRecord -> {
                sink.writeByte(TAG_RECORD)
                sink.writeInt(value.fields.size)
                for ((fieldId, item) in value.fields) {
                    sink.writeShort(fieldId)
                    writeValue(sink, item)
                }
            }
            is RValue.RStringMap -> {
                sink.writeByte(TAG_STRING_MAP)
                sink.writeInt(value.entries.size)
                for ((key, item) in value.entries) {
                    val bytes = key.toByteArray(StandardCharsets.UTF_8)
                    sink.writeInt(bytes.size)
                    sink.write(bytes)
                    writeValue(sink, item)
                }
            }
        }
    }

    private class ByteSink {
        private val out = ByteArrayOutputStream(128)

        fun writeByte(v: Int) = out.write(v and 0xFF)
        fun write(bytes: ByteArray) = out.write(bytes, 0, bytes.size)

        fun writeShort(v: Int) {
            writeByte(v)
            writeByte(v shr 8)
        }

        fun writeInt(v: Int) {
            writeByte(v)
            writeByte(v shr 8)
            writeByte(v shr 16)
            writeByte(v shr 24)
        }

        fun writeLong(v: Long) {
            var x = v
            repeat(8) {
                writeByte(x.toInt())
                x = x ushr 8
            }
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}

fun RValue.RRecord.int(fieldId: Int): Int? = fields[fieldId]?.let { v ->
    when (v) {
        is RValue.RInt32 -> v.value
        is RValue.RInt64 -> v.value.toInt()
        is RValue.RDouble -> {
            val d = v.value
            if (d == Math.rint(d) && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) d.toInt() else null
        }
        else -> null
    }
}

fun RValue.RRecord.long(fieldId: Int): Long? = fields[fieldId]?.let { v ->
    when (v) {
        is RValue.RInt32 -> v.value.toLong()
        is RValue.RInt64 -> v.value
        is RValue.RDouble -> {
            val d = v.value
            if (d == Math.rint(d) && Math.abs(d) < 9.2e18) d.toLong() else null
        }
        else -> null
    }
}

fun RValue.RRecord.str(fieldId: Int): String? = (fields[fieldId] as? RValue.RString)?.value

fun RValue.RRecord.bool(fieldId: Int): Boolean? = (fields[fieldId] as? RValue.RBool)?.value

fun RValue.RRecord.array(fieldId: Int): List<RValue>? = (fields[fieldId] as? RValue.RArray)?.values

fun RValue.RRecord.record(fieldId: Int): RValue.RRecord? = fields[fieldId] as? RValue.RRecord
