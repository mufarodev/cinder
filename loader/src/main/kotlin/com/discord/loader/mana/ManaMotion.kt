package com.discord.loader.mana

import com.discord.loader.Loader
import com.discord.loader.ManaUi
import com.discord.loader.UiOps
import java.util.ArrayDeque
import java.util.Arrays
import java.util.LinkedHashMap
import java.util.LinkedHashSet

object ManaMotion {
    const val OP_SET_VALUE: Int = 1
    const val OP_ANIMATE: Int = 2
    const val OP_BIND_WORKLET: Int = 3
    const val OP_UNBIND_WORKLET: Int = 4
    const val OP_INIT_VALUE: Int = 8
    const val OP_FREE_SLOT: Int = 9

    const val WORKLET_KIND_STYLE: Int = 0
    const val WORKLET_KIND_PAN: Int = 1
    const val WORKLET_KIND_PINCH: Int = 2

    const val ANIM_TIMING: Int = 1
    const val ANIM_SPRING: Int = 2
    const val ANIM_DECAY: Int = 3
    const val ANIM_DELAY: Int = 4
    const val ANIM_SEQUENCE: Int = 5
    const val ANIM_REPEAT: Int = 6
    const val ANIM_REDUCED_MOTION: Int = 7

    const val EASING_LINEAR: Int = 0
    const val EASING_BEZIER: Int = 1
    const val EASING_QUAD: Int = 2
    const val EASING_CUBIC: Int = 3
    const val EASING_POLY: Int = 4
    const val EASING_SIN: Int = 5

    const val SPRING_FLAG_NONE: Int = 0
    const val SPRING_FLAG_CLAMP: Int = 1
    const val SPRING_FLAG_DURATION_BASED: Int = 2
    const val DECAY_FLAG_NONE: Int = 0
    const val DECAY_FLAG_CLAMP: Int = 1

    const val KEYFRAME_MODE_ONCE: Int = 0
    const val KEYFRAME_MODE_LOOP: Int = 1
    const val KEYFRAME_MODE_PING_PONG: Int = 2

    const val AXIS_X: Int = 0
    const val AXIS_Y: Int = 1

    const val WORKLET_SAMPLED: Int = -1890075735

    const val WORKLET_KEYFRAMES: Int = -311856514

    const val WORKLET_DRAG_SNAP: Int = 299774864

    const val WORKLET_FADE: Int = -1425870398

    const val WORKLET_TRANSLATE: Int = 704891930

    const val WORKLET_TRANSLATE_SCALE: Int = -1008839137

    const val PROP_TRANSLATE_X: Int = 1
    const val PROP_TRANSLATE_Y: Int = 2
    const val PROP_SCALE: Int = 3
    const val PROP_SCALE_X: Int = 4
    const val PROP_SCALE_Y: Int = 5
    const val PROP_ROTATION: Int = 6
    const val PROP_OPACITY: Int = 7
    const val PROP_BACKGROUND: Int = 8
    const val PROP_RADIUS: Int = 9
    const val PROP_BORDER_COLOR: Int = 10
    const val PROP_TINT: Int = 11
    const val PROP_COLOR: Int = 12
    const val PROP_SHADOW_OPACITY: Int = 13
    const val PROP_SHADOW_OFFSET_X: Int = 14
    const val PROP_SHADOW_OFFSET_Y: Int = 15
    const val PROP_BORDER_WIDTH: Int = 16
    const val PROP_PADDING_BOTTOM: Int = 17
    const val PROP_HEIGHT: Int = 18
    const val PROP_WIDTH: Int = 19
    const val PROP_PROGRESS: Int = 20

    const val COLOR_PROP_MASK: Int = 0x1D00

    const val SLOT_MIN: Int = 49_152

    const val SLOT_MAX: Int = 65_535

    const val ENGINE_HARD_SLOT_LIMIT: Int = 65_536

    const val SWITCH_THUMB_TRAVEL_DP: Float = 28f

    @JvmStatic
    fun isColorProp(propId: Int): Boolean = propId in 0..30 && ((COLOR_PROP_MASK ushr propId) and 1) != 0

    @JvmStatic
    fun f32(value: Float): Int = java.lang.Float.floatToRawIntBits(value)

    @JvmStatic
    fun fromF32(bits: Int): Float = java.lang.Float.intBitsToFloat(bits)

    @JvmStatic
    fun isWorkletSupported(): Boolean = UiOps.propId("worklet") >= 0

    data class SpringConfig
        @JvmOverloads
        constructor(
            val mass: Float,
            val stiffness: Float,
            val damping: Float,
            val flags: Int = SPRING_FLAG_NONE,
            val restDisplacement: Float = 0.01f,
            val restSpeed: Float = 2f,
            val initialVelocity: Float = 0f,
            val reducedMotion: Boolean = true,
        ) {
            companion object {
                @JvmField
                val MANA_SWITCH: SpringConfig =
                    SpringConfig(
                        mass = 1f,
                        stiffness = 300f,
                        damping = 27f,
                        flags = SPRING_FLAG_NONE,
                        restDisplacement = 0.01f,
                        restSpeed = 2f,
                        initialVelocity = 0f,
                        reducedMotion = true,
                    )

                @JvmField
                val MANA_RADIO: SpringConfig = MANA_SWITCH

                @JvmField
                val CLAMPED_COLOR: SpringConfig =
                    SpringConfig(
                        mass = 0.3f,
                        stiffness = 250f,
                        damping = 13f,
                        flags = SPRING_FLAG_CLAMP,
                        restDisplacement = 0.01f,
                        restSpeed = 2f,
                        initialVelocity = 0f,
                        reducedMotion = true,
                    )

                @JvmField
                val SEGMENTED_INDICATOR: SpringConfig =
                    SpringConfig(
                        mass = 0.35f,
                        stiffness = 250f,
                        damping = 13f,
                        flags = SPRING_FLAG_NONE,
                        restDisplacement = 0.01f,
                        restSpeed = 2f,
                        initialVelocity = 0f,
                        reducedMotion = false,
                    )

                @JvmField
                val PRESS_SCALE: SpringConfig =
                    SpringConfig(
                        mass = 0.5f,
                        stiffness = 400f,
                        damping = 28f,
                        flags = SPRING_FLAG_NONE,
                        restDisplacement = 0.005f,
                        restSpeed = 1f,
                        initialVelocity = 0f,
                        reducedMotion = true,
                    )

                @JvmField
                val SHEET_SNAP: SpringConfig =
                    SpringConfig(
                        mass = 1f,
                        stiffness = 320f,
                        damping = 32f,
                        flags = SPRING_FLAG_CLAMP,
                        restDisplacement = 0.5f,
                        restSpeed = 10f,
                        initialVelocity = 0f,
                        reducedMotion = true,
                    )
            }
        }

    internal class IntWordBuffer(
        initialCapacity: Int = 32,
    ) {
        private var buf = IntArray(if (initialCapacity < 8) 8 else initialCapacity)
        var size: Int = 0
            private set

        fun add(word: Int) {
            ensureCapacity(size + 1)
            buf[size++] = word
        }

        fun addFloat(value: Float) {
            add(f32(value))
        }

        fun addAll(
            words: IntArray,
            offset: Int = 0,
            length: Int = words.size - offset,
        ) {
            if (length <= 0) return
            ensureCapacity(size + length)
            System.arraycopy(words, offset, buf, size, length)
            size += length
        }

        fun toIntArray(): IntArray = Arrays.copyOf(buf, size)

        private fun ensureCapacity(needed: Int) {
            if (needed <= buf.size) return
            var next = buf.size * 2
            while (next < needed) {
                next *= 2
            }
            buf = Arrays.copyOf(buf, next)
        }
    }

    class MotionSlotAllocator
        @JvmOverloads
        constructor(
            val minSlot: Int = SLOT_MIN,
            val maxSlot: Int = SLOT_MAX,
        ) {
            init {
                require(minSlot in 1..ENGINE_HARD_SLOT_LIMIT) { "minSlot out of range: $minSlot" }
                require(maxSlot in minSlot..ENGINE_HARD_SLOT_LIMIT) { "maxSlot out of range: $maxSlot" }
            }

            private val lock = Any()
            private var nextCursor: Int = minSlot
            private var highestRustSlot: Int = 0
            private val freeQueue = ArrayDeque<Int>()
            private val freeSet = LinkedHashSet<Int>()
            private val slotsByScope = LinkedHashMap<String, LinkedHashSet<Int>>()

            fun observeRustMotionWords(words: IntArray?) {
                if (words == null || words.isEmpty()) return
                var maxSeen = 0
                walkMotionStream(words) { _, slotId ->
                    if (slotId in 1..ENGINE_HARD_SLOT_LIMIT && slotId > maxSeen) {
                        maxSeen = slotId
                    }
                }
                if (maxSeen <= 0) return
                synchronized(lock) {
                    if (maxSeen > highestRustSlot) {
                        highestRustSlot = maxSeen
                    }
                    if (highestRustSlot >= nextCursor) {
                        nextCursor = highestRustSlot + 1
                    }
                    if (freeSet.isNotEmpty() && highestRustSlot >= minSlot) {
                        val iter = freeQueue.iterator()
                        while (iter.hasNext()) {
                            val s = iter.next()
                            if (s <= highestRustSlot) {
                                iter.remove()
                                freeSet.remove(s)
                            }
                        }
                    }
                }
            }

            fun allocSlot(scopeId: String): Int {
                require(scopeId.isNotEmpty()) { "scopeId must not be empty" }
                synchronized(lock) {
                    var slot = -1
                    while (!freeQueue.isEmpty()) {
                        val candidate = freeQueue.removeFirst()
                        freeSet.remove(candidate)
                        if (candidate in minSlot..maxSlot && candidate > highestRustSlot) {
                            slot = candidate
                            break
                        }
                    }
                    if (slot < 0) {
                        if (nextCursor > maxSlot) {
                            throw IllegalStateException(
                                "MotionSlotAllocator exhausted [$minSlot..$maxSlot] (rustMax=$highestRustSlot)",
                            )
                        }
                        slot = nextCursor++
                    }
                    var scopeSet = slotsByScope[scopeId]
                    if (scopeSet == null) {
                        scopeSet = LinkedHashSet()
                        slotsByScope[scopeId] = scopeSet
                    }
                    scopeSet.add(slot)
                    return slot
                }
            }

            fun releaseScope(scopeId: String): IntArray {
                if (scopeId.isEmpty()) return IntArray(0)
                synchronized(lock) {
                    val scopeSet = slotsByScope.remove(scopeId) ?: return IntArray(0)
                    val out = IntArray(scopeSet.size)
                    var i = 0
                    for (slot in scopeSet) {
                        out[i++] = slot
                        if (slot in minSlot..maxSlot && slot > highestRustSlot && freeSet.add(slot)) {
                            freeQueue.addLast(slot)
                        }
                    }
                    return out
                }
            }

            @JvmOverloads
            fun activeSlotCount(scopeId: String? = null): Int =
                synchronized(lock) {
                    if (scopeId != null) {
                        slotsByScope[scopeId]?.size ?: 0
                    } else {
                        var total = 0
                        for (set in slotsByScope.values) {
                            total += set.size
                        }
                        total
                    }
                }

            fun maxObservedRustSlot(): Int = synchronized(lock) { highestRustSlot }

            fun reset() {
                synchronized(lock) {
                    nextCursor = minSlot
                    highestRustSlot = 0
                    freeQueue.clear()
                    freeSet.clear()
                    slotsByScope.clear()
                }
            }
        }

    @JvmField
    val slots: MotionSlotAllocator = MotionSlotAllocator()

    @JvmStatic
    fun allocSlot(scopeId: String): Int = slots.allocSlot(scopeId)

    @JvmStatic
    fun observeRustMotionWords(words: IntArray?) {
        slots.observeRustMotionWords(words)
    }

    @JvmStatic
    fun observeRustBatch(opList: List<UiOps.Op>?) {
        if (opList == null || opList.isEmpty()) return
        for (i in 0 until opList.size) {
            val op = opList[i]
            if (op.opcode() == UiOps.OP_MOTION) {
                slots.observeRustMotionWords(op.motionWords())
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun activeSlotCount(scopeId: String? = null): Int = slots.activeSlotCount(scopeId)

    @JvmStatic
    fun releaseScopeInto(
        scopeId: String,
        batch: UiOps,
    ): IntArray {
        unbindThemeWorklets(scopeId)
        val released = slots.releaseScope(scopeId)
        if (released.isNotEmpty()) {
            val mb = MotionBatchBuilder()
            mb.freeSlots(released)
            mb.appendTo(batch)
        }
        return released
    }

    fun walkMotionStream(
        words: IntArray,
        onSlotRef: (opcode: Int, slotId: Int) -> Unit,
    ) {
        var pos = 0
        val len = words.size
        while (pos < len) {
            val op = words[pos++]
            when (op) {
                OP_SET_VALUE, OP_INIT_VALUE -> {
                    if (pos + 2 > len) return
                    val slotId = words[pos]
                    onSlotRef(op, slotId)
                    pos += 2
                }

                OP_ANIMATE -> {
                    if (pos + 2 > len) return
                    val slotId = words[pos]
                    onSlotRef(op, slotId)
                    pos += 2
                    val next = skipAnimSpec(words, pos, 0)
                    if (next < 0) return
                    pos = next
                }

                OP_BIND_WORKLET -> {
                    if (pos + 4 > len) return
                    val captureLen = words[pos + 3]
                    if (captureLen < 0 || pos + 4 + captureLen > len) return
                    pos += 4 + captureLen
                }

                OP_UNBIND_WORKLET -> {
                    if (pos + 2 > len) return
                    pos += 2
                }

                OP_FREE_SLOT -> {
                    if (pos + 1 > len) return
                    val slotId = words[pos]
                    onSlotRef(op, slotId)
                    pos += 1
                }

                else -> {
                    return
                }
            }
        }
    }

    private fun skipAnimSpec(
        words: IntArray,
        startPos: Int,
        depth: Int,
    ): Int {
        if (depth > 16 || startPos >= words.size) return -1
        val tag = words[startPos]
        var pos = startPos + 1
        return when (tag) {
            ANIM_TIMING -> {
                if (pos + 7 <= words.size) pos + 7 else -1
            }

            ANIM_SPRING -> {
                if (pos + 8 <= words.size) pos + 8 else -1
            }

            ANIM_DECAY -> {
                if (pos + 5 <= words.size) pos + 5 else -1
            }

            ANIM_DELAY -> {
                if (pos + 1 > words.size) return -1
                skipAnimSpec(words, pos + 1, depth + 1)
            }

            ANIM_SEQUENCE -> {
                if (pos + 1 > words.size) return -1
                val count = words[pos++]
                if (count < 0 || count > 64) return -1
                for (i in 0 until count) {
                    pos = skipAnimSpec(words, pos, depth + 1)
                    if (pos < 0) return -1
                }
                pos
            }

            ANIM_REPEAT -> {
                if (pos + 2 > words.size) return -1
                skipAnimSpec(words, pos + 2, depth + 1)
            }

            ANIM_REDUCED_MOTION -> {
                skipAnimSpec(words, pos, depth + 1)
            }

            else -> {
                -1
            }
        }
    }

    class MotionBatchBuilder {
        private val words = IntWordBuffer(32)

        private fun requireValidSlot(slotId: Int) {
            require(slotId in 1..ENGINE_HARD_SLOT_LIMIT) {
                "Invalid motion slotId=$slotId (must be in 1..$ENGINE_HARD_SLOT_LIMIT)"
            }
        }

        fun initValue(
            slotId: Int,
            initialValue: Float,
        ): MotionBatchBuilder {
            requireValidSlot(slotId)
            words.add(OP_INIT_VALUE)
            words.add(slotId)
            words.addFloat(initialValue)
            return this
        }

        fun setValue(
            slotId: Int,
            value: Float,
        ): MotionBatchBuilder {
            requireValidSlot(slotId)
            words.add(OP_SET_VALUE)
            words.add(slotId)
            words.addFloat(value)
            return this
        }

        @JvmOverloads
        fun animateSpring(
            slotId: Int,
            toValue: Float,
            spring: SpringConfig = SpringConfig.MANA_SWITCH,
            callbackId: Int = 0,
        ): MotionBatchBuilder {
            requireValidSlot(slotId)
            words.add(OP_ANIMATE)
            words.add(slotId)
            words.add(callbackId)
            writeSpringSpec(toValue, spring)
            return this
        }

        @JvmOverloads
        fun animateArgbSlots(
            aSlot: Int,
            rSlot: Int,
            gSlot: Int,
            bSlot: Int,
            argb: Long,
            spring: SpringConfig = SpringConfig.MANA_SWITCH,
            callbackId: Int = 0,
        ): MotionBatchBuilder {
            val a = ((argb ushr 24) and 0xFFL).toFloat()
            val r = ((argb ushr 16) and 0xFFL).toFloat()
            val g = ((argb ushr 8) and 0xFFL).toFloat()
            val b = (argb and 0xFFL).toFloat()
            animateSpring(aSlot, a, spring, callbackId)
            animateSpring(rSlot, r, spring, callbackId)
            animateSpring(gSlot, g, spring, callbackId)
            animateSpring(bSlot, b, spring, callbackId)
            return this
        }

        @JvmOverloads
        fun animateTiming(
            slotId: Int,
            toValue: Float,
            durationMs: Float,
            easingKind: Int = EASING_LINEAR,
            p0: Float = 0f,
            p1: Float = 0f,
            p2: Float = 0f,
            p3: Float = 0f,
            reducedMotion: Boolean = false,
            callbackId: Int = 0,
        ): MotionBatchBuilder {
            requireValidSlot(slotId)
            words.add(OP_ANIMATE)
            words.add(slotId)
            words.add(callbackId)
            if (reducedMotion) {
                words.add(ANIM_REDUCED_MOTION)
            }
            writeTimingSpec(toValue, durationMs, easingKind, p0, p1, p2, p3)
            return this
        }

        @JvmOverloads
        fun animateDecay(
            slotId: Int,
            velocity: Float,
            deceleration: Float = 0.998f,
            clampMin: Float = 0f,
            clampMax: Float = 0f,
            clamp: Boolean = false,
            callbackId: Int = 0,
        ): MotionBatchBuilder {
            requireValidSlot(slotId)
            words.add(OP_ANIMATE)
            words.add(slotId)
            words.add(callbackId)
            words.add(ANIM_DECAY)
            words.add(if (clamp) DECAY_FLAG_CLAMP else DECAY_FLAG_NONE)
            words.addFloat(velocity)
            words.addFloat(deceleration)
            words.addFloat(clampMin)
            words.addFloat(clampMax)
            return this
        }

        @JvmOverloads
        fun animateDelayedSpring(
            slotId: Int,
            delayMs: Float,
            toValue: Float,
            spring: SpringConfig = SpringConfig.MANA_SWITCH,
            callbackId: Int = 0,
        ): MotionBatchBuilder {
            requireValidSlot(slotId)
            words.add(OP_ANIMATE)
            words.add(slotId)
            words.add(callbackId)
            words.add(ANIM_DELAY)
            words.addFloat(delayMs)
            writeSpringSpec(toValue, spring)
            return this
        }

        @JvmOverloads
        fun animateSpringSequence(
            slotId: Int,
            targets: FloatArray,
            spring: SpringConfig = SpringConfig.MANA_SWITCH,
            reducedMotion: Boolean = false,
            callbackId: Int = 0,
        ): MotionBatchBuilder {
            requireValidSlot(slotId)
            require(targets.size in 1..64) { "Sequence count must be 1..64, got ${targets.size}" }
            words.add(OP_ANIMATE)
            words.add(slotId)
            words.add(callbackId)
            if (reducedMotion) {
                words.add(ANIM_REDUCED_MOTION)
            }
            words.add(ANIM_SEQUENCE)
            words.add(targets.size)
            val innerSpring = if (spring.reducedMotion) spring.copy(reducedMotion = false) else spring
            for (i in targets.indices) {
                writeSpringSpec(targets[i], innerSpring)
            }
            return this
        }

        @JvmOverloads
        fun animateRepeatingTiming(
            slotId: Int,
            count: Int,
            reverse: Boolean,
            toValue: Float,
            durationMs: Float,
            easingKind: Int = EASING_LINEAR,
            p0: Float = 0f,
            p1: Float = 0f,
            p2: Float = 0f,
            p3: Float = 0f,
            callbackId: Int = 0,
        ): MotionBatchBuilder {
            requireValidSlot(slotId)
            words.add(OP_ANIMATE)
            words.add(slotId)
            words.add(callbackId)
            words.add(ANIM_REPEAT)
            words.add(count)
            words.add(if (reverse) 1 else 0)
            writeTimingSpec(toValue, durationMs, easingKind, p0, p1, p2, p3)
            return this
        }

        @JvmOverloads
        fun bindWorklet(
            viewId: Int,
            workletWords: IntArray,
            kind: Int = WORKLET_KIND_STYLE,
        ): MotionBatchBuilder {
            require(workletWords.isNotEmpty()) { "workletWords must contain programId at index 0" }
            words.add(OP_BIND_WORKLET)
            words.add(viewId)
            words.add(kind)
            words.add(workletWords[0])
            words.add(workletWords.size - 1)
            words.addAll(workletWords, 1, workletWords.size - 1)
            return this
        }

        @JvmOverloads
        fun unbindWorklet(
            viewId: Int,
            kind: Int = WORKLET_KIND_STYLE,
        ): MotionBatchBuilder {
            words.add(OP_UNBIND_WORKLET)
            words.add(viewId)
            words.add(kind)
            return this
        }

        fun freeSlot(slotId: Int): MotionBatchBuilder {
            requireValidSlot(slotId)
            words.add(OP_FREE_SLOT)
            words.add(slotId)
            return this
        }

        fun freeSlots(slotIds: IntArray): MotionBatchBuilder {
            for (i in slotIds.indices) {
                freeSlot(slotIds[i])
            }
            return this
        }

        fun isEmpty(): Boolean = words.size == 0

        fun wordCount(): Int = words.size

        fun toIntArray(): IntArray = words.toIntArray()

        fun appendTo(batch: UiOps): UiOps {
            if (words.size > 0) {
                batch.motion(*words.toIntArray())
            }
            return batch
        }

        private fun writeSpringSpec(
            toValue: Float,
            spring: SpringConfig,
        ) {
            if (spring.reducedMotion) {
                words.add(ANIM_REDUCED_MOTION)
            }
            words.add(ANIM_SPRING)
            words.addFloat(toValue)
            words.addFloat(spring.mass)
            words.addFloat(spring.stiffness)
            words.addFloat(spring.damping)
            words.add(spring.flags)
            words.addFloat(spring.restDisplacement)
            words.addFloat(spring.restSpeed)
            words.addFloat(spring.initialVelocity)
        }

        private fun writeTimingSpec(
            toValue: Float,
            durationMs: Float,
            easingKind: Int,
            p0: Float,
            p1: Float,
            p2: Float,
            p3: Float,
        ) {
            words.add(ANIM_TIMING)
            words.addFloat(toValue)
            words.addFloat(durationMs)
            words.add(easingKind)
            words.addFloat(p0)
            words.addFloat(p1)
            words.addFloat(p2)
            words.addFloat(p3)
        }
    }

    @JvmStatic
    fun batch(): MotionBatchBuilder = MotionBatchBuilder()

    @JvmStatic
    fun initValueWords(
        slotId: Int,
        initialValue: Float,
    ): IntArray = intArrayOf(OP_INIT_VALUE, slotId, f32(initialValue))

    @JvmStatic
    fun setValueWords(
        slotId: Int,
        value: Float,
    ): IntArray = intArrayOf(OP_SET_VALUE, slotId, f32(value))

    class SampledTrack private constructor(
        val propId: Int,
        internal val values: IntArray,
    ) {
        companion object {
            @JvmStatic
            fun floats(
                propId: Int,
                vararg values: Float,
            ): SampledTrack {
                require(!isColorProp(propId)) {
                    "Prop $propId is an ARGB color property; use SampledTrack.colors()"
                }
                val bits = IntArray(values.size)
                for (i in values.indices) {
                    bits[i] = f32(values[i])
                }
                return SampledTrack(propId, bits)
            }

            @JvmStatic
            fun colors(
                propId: Int,
                vararg argbColors: Long,
            ): SampledTrack {
                require(isColorProp(propId)) {
                    "Prop $propId is a float property; use SampledTrack.floats()"
                }
                val raw = IntArray(argbColors.size)
                for (i in argbColors.indices) {
                    raw[i] = (argbColors[i] and 0xFFFFFFFFL).toInt()
                }
                return SampledTrack(propId, raw)
            }

            @JvmStatic
            fun colorsInt(
                propId: Int,
                vararg argbColors: Int,
            ): SampledTrack {
                require(isColorProp(propId)) {
                    "Prop $propId is a float property; use SampledTrack.floats()"
                }
                return SampledTrack(propId, Arrays.copyOf(argbColors, argbColors.size))
            }
        }
    }

    @JvmStatic
    fun sampledWorklet(
        slotId: Int,
        stops: FloatArray,
        vararg tracks: SampledTrack,
    ): IntArray {
        require(slotId in 1..ENGINE_HARD_SLOT_LIMIT) { "Invalid slotId=$slotId" }
        require(stops.size >= 2) { "sampledWorklet requires at least 2 input stops" }
        require(tracks.isNotEmpty()) { "sampledWorklet requires at least 1 track" }
        val buf = IntWordBuffer(4 + stops.size + tracks.size * (2 + stops.size))
        buf.add(WORKLET_SAMPLED)
        buf.add(slotId)
        buf.add(stops.size)
        for (i in stops.indices) {
            buf.addFloat(stops[i])
        }
        buf.add(tracks.size)
        for (t in tracks) {
            require(t.values.size == stops.size) {
                "Track prop=${t.propId} value count ${t.values.size} != stop count ${stops.size}"
            }
            buf.add(t.propId)
            buf.add(t.values.size)
            buf.addAll(t.values)
        }
        return buf.toIntArray()
    }

    @JvmStatic
    @JvmOverloads
    fun keyframesWorklet(
        durationSec: Float,
        mode: Int = KEYFRAME_MODE_LOOP,
        flags: Int = 0,
        vararg tracks: SampledTrack,
    ): IntArray {
        require(durationSec > 0f) { "durationSec must be > 0" }
        require(tracks.isNotEmpty()) { "keyframesWorklet requires at least 1 track" }
        val buf = IntWordBuffer(5 + tracks.size * 4)
        buf.add(WORKLET_KEYFRAMES)
        buf.addFloat(durationSec)
        buf.add(mode)
        buf.add(flags)
        buf.add(tracks.size)
        for (t in tracks) {
            buf.add(t.propId)
            buf.add(t.values.size)
            buf.addAll(t.values)
        }
        return buf.toIntArray()
    }

    @JvmStatic
    @JvmOverloads
    fun dragSnapWorklet(
        slotId: Int,
        axis: Int,
        snapPoints: FloatArray,
        min: Float,
        max: Float,
        rubberbandMin: Float = 0f,
        rubberbandMax: Float = 0f,
        spring: SpringConfig = SpringConfig.SHEET_SNAP,
        velocityScale: Float = 0.25f,
        dismissSnapIndex: Int = -1,
    ): IntArray {
        require(slotId in 1..ENGINE_HARD_SLOT_LIMIT) { "Invalid slotId=$slotId" }
        require(snapPoints.isNotEmpty()) { "dragSnapWorklet requires at least 1 snap point" }
        val buf = IntWordBuffer(18 + snapPoints.size)
        buf.add(WORKLET_DRAG_SNAP)
        buf.add(slotId)
        buf.add(axis)
        buf.add(snapPoints.size)
        for (i in snapPoints.indices) {
            buf.addFloat(snapPoints[i])
        }
        buf.addFloat(min)
        buf.addFloat(max)
        buf.addFloat(rubberbandMin)
        buf.addFloat(rubberbandMax)
        buf.addFloat(spring.mass)
        buf.addFloat(spring.stiffness)
        buf.addFloat(spring.damping)
        buf.add(spring.flags)
        buf.addFloat(spring.restDisplacement)
        buf.addFloat(spring.restSpeed)
        buf.addFloat(velocityScale)
        buf.add(dismissSnapIndex)
        return buf.toIntArray()
    }

    @JvmStatic
    fun fadeWorklet(opacitySlotId: Int): IntArray {
        require(opacitySlotId in 1..ENGINE_HARD_SLOT_LIMIT) { "Invalid slotId=$opacitySlotId" }
        return intArrayOf(WORKLET_FADE, opacitySlotId)
    }

    @JvmStatic
    fun translateWorklet(
        txSlotId: Int,
        tySlotId: Int,
    ): IntArray {
        require(txSlotId in 1..ENGINE_HARD_SLOT_LIMIT) { "Invalid txSlotId=$txSlotId" }
        require(tySlotId in 1..ENGINE_HARD_SLOT_LIMIT) { "Invalid tySlotId=$tySlotId" }
        return intArrayOf(WORKLET_TRANSLATE, txSlotId, tySlotId)
    }

    @JvmStatic
    fun translateScaleWorklet(
        txSlotId: Int,
        tySlotId: Int,
        scaleSlotId: Int,
    ): IntArray {
        require(txSlotId in 1..ENGINE_HARD_SLOT_LIMIT) { "Invalid txSlotId=$txSlotId" }
        require(tySlotId in 1..ENGINE_HARD_SLOT_LIMIT) { "Invalid tySlotId=$tySlotId" }
        require(scaleSlotId in 1..ENGINE_HARD_SLOT_LIMIT) { "Invalid scaleSlotId=$scaleSlotId" }
        return intArrayOf(WORKLET_TRANSLATE_SCALE, txSlotId, tySlotId, scaleSlotId)
    }

    @JvmStatic
    fun workletValue(words: IntArray): UiOps.Value = UiOps.Value.ofWordList(*words)

    @JvmStatic
    fun workletProp(words: IntArray): UiOps.Prop? = ManaUi.prop("worklet", workletValue(words))

    private class ThemeTrackSpec(
        val propId: Int,
        val tokens: Array<ColorToken?>,
        val staticColors: LongArray,
        val floatValues: FloatArray? = null,
    )

    private class ThemeWorkletBinding(
        val scopeId: String,
        val viewId: Int,
        val slotId: Int,
        val stops: FloatArray,
        val tracks: Array<ThemeTrackSpec>,
    ) {
        fun hasChanged(
            previous: ManaPalette,
            current: ManaPalette,
        ): Boolean {
            for (t in tracks) {
                if (t.floatValues != null) continue
                for (i in t.tokens.indices) {
                    val tok = t.tokens[i] ?: continue
                    if (previous[tok] != current[tok]) return true
                }
            }
            return false
        }

        fun buildWorkletWords(palette: ManaPalette): IntArray {
            val builtTracks =
                Array(tracks.size) { idx ->
                    val spec = tracks[idx]
                    val floats = spec.floatValues
                    if (floats != null) {
                        SampledTrack.floats(spec.propId, *floats)
                    } else {
                        val colors =
                            LongArray(spec.tokens.size) { j ->
                                val tok = spec.tokens[j]
                                if (tok != null) palette[tok] else spec.staticColors[j]
                            }
                        SampledTrack.colors(spec.propId, *colors)
                    }
                }
            return sampledWorklet(slotId, stops, *builtTracks)
        }
    }

    private val workletLock = Any()
    private val themeWorkletsByScope =
        LinkedHashMap<String, LinkedHashMap<Int, ThemeWorkletBinding>>()

    @JvmStatic
    fun bindSwitchThemeWorklet(
        scopeId: String,
        trackViewId: Int,
        slotId: Int,
    ) {
        if (scopeId.isEmpty()) return
        val binding =
            ThemeWorkletBinding(
                scopeId = scopeId,
                viewId = trackViewId,
                slotId = slotId,
                stops = floatArrayOf(0f, 1f),
                tracks =
                    arrayOf(
                        ThemeTrackSpec(
                            propId = PROP_BACKGROUND,
                            tokens = arrayOf(ColorToken.SWITCH_OFF_BG, ColorToken.SWITCH_ON_BG),
                            staticColors = longArrayOf(0L, 0L),
                        ),
                        ThemeTrackSpec(
                            propId = PROP_BORDER_COLOR,
                            tokens = arrayOf(ColorToken.SWITCH_OFF_BORDER, ColorToken.SWITCH_ON_BORDER),
                            staticColors = longArrayOf(0L, 0L),
                        ),
                    ),
            )
        registerThemeWorklet(binding)
    }

    @JvmStatic
    fun bindCheckboxThemeWorklet(
        scopeId: String,
        boxViewId: Int,
        slotId: Int,
    ) {
        bindRadioThemeWorklet(scopeId, boxViewId, slotId)
    }

    @JvmStatic
    @JvmOverloads
    fun bindInputFocusThemeWorklet(
        scopeId: String,
        boxViewId: Int,
        slotId: Int,
        isError: Boolean = false,
        idleWidthDp: Float = 1f,
        focusedWidthDp: Float = 1.5f,
    ) {
        if (scopeId.isEmpty()) return
        val idleTok = if (isError) ColorToken.DANGER else ColorToken.INPUT_BORDER
        val focusTok = if (isError) ColorToken.DANGER else ColorToken.BRAND_500
        val binding =
            ThemeWorkletBinding(
                scopeId = scopeId,
                viewId = boxViewId,
                slotId = slotId,
                stops = floatArrayOf(0f, 1f),
                tracks =
                    arrayOf(
                        ThemeTrackSpec(
                            propId = PROP_BORDER_COLOR,
                            tokens = arrayOf(idleTok, focusTok),
                            staticColors = longArrayOf(0L, 0L),
                        ),
                        ThemeTrackSpec(
                            propId = PROP_BORDER_WIDTH,
                            tokens = emptyArray(),
                            staticColors = LongArray(0),
                            floatValues = floatArrayOf(idleWidthDp, focusedWidthDp),
                        ),
                    ),
            )
        registerThemeWorklet(binding)
    }

    @JvmStatic
    fun bindRadioThemeWorklet(
        scopeId: String,
        outerCircleViewId: Int,
        slotId: Int,
    ) {
        if (scopeId.isEmpty()) return
        val binding =
            ThemeWorkletBinding(
                scopeId = scopeId,
                viewId = outerCircleViewId,
                slotId = slotId,
                stops = floatArrayOf(0f, 1f),
                tracks =
                    arrayOf(
                        ThemeTrackSpec(
                            propId = PROP_BACKGROUND,
                            tokens = arrayOf(null, ColorToken.BRAND_500),
                            staticColors = longArrayOf(0L, 0L),
                        ),
                        ThemeTrackSpec(
                            propId = PROP_BORDER_COLOR,
                            tokens = arrayOf(ColorToken.RADIO_UNCHECKED_BORDER, ColorToken.BRAND_500),
                            staticColors = longArrayOf(0L, 0L),
                        ),
                    ),
            )
        registerThemeWorklet(binding)
    }

    private fun registerThemeWorklet(binding: ThemeWorkletBinding) {
        synchronized(workletLock) {
            var map = themeWorkletsByScope[binding.scopeId]
            if (map == null) {
                map = LinkedHashMap()
                themeWorkletsByScope[binding.scopeId] = map
            }
            map[binding.viewId] = binding
        }
    }

    @JvmStatic
    fun unbindThemeWorklets(scopeId: String) {
        if (scopeId.isEmpty()) return
        synchronized(workletLock) {
            themeWorkletsByScope.remove(scopeId)
        }
    }

    @JvmStatic
    fun unbindThemeWorkletView(viewId: Int) {
        synchronized(workletLock) {
            val iter = themeWorkletsByScope.entries.iterator()
            while (iter.hasNext()) {
                val map = iter.next().value
                map.remove(viewId)
                if (map.isEmpty()) {
                    iter.remove()
                }
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun themeWorkletBindingCount(scopeId: String? = null): Int =
        synchronized(workletLock) {
            if (scopeId != null) {
                themeWorkletsByScope[scopeId]?.size ?: 0
            } else {
                var total = 0
                for (map in themeWorkletsByScope.values) {
                    total += map.size
                }
                total
            }
        }

    @JvmStatic
    fun computeThemeReskinWorklets(
        previous: ManaPalette,
        current: ManaPalette,
    ): LinkedHashMap<Int, IntArray> {
        val out = LinkedHashMap<Int, IntArray>()
        if (previous == current || !isWorkletSupported()) return out
        val snapshot: List<ThemeWorkletBinding> =
            synchronized(workletLock) {
                if (themeWorkletsByScope.isEmpty()) return out
                val list = ArrayList<ThemeWorkletBinding>()
                for (scopeMap in themeWorkletsByScope.values) {
                    for (binding in scopeMap.values) {
                        list.add(binding)
                    }
                }
                list
            }
        for (binding in snapshot) {
            if (binding.hasChanged(previous, current)) {
                out[binding.viewId] = binding.buildWorkletWords(current)
            }
        }
        return out
    }

    @JvmStatic
    fun switchTrackWorklet(
        slotId: Int,
        offBg: Long,
        onBg: Long,
        offBorder: Long,
        onBorder: Long,
    ): IntArray =
        sampledWorklet(
            slotId = slotId,
            stops = floatArrayOf(0f, 1f),
            SampledTrack.colors(PROP_BACKGROUND, offBg, onBg),
            SampledTrack.colors(PROP_BORDER_COLOR, offBorder, onBorder),
        )

    @JvmStatic
    @JvmOverloads
    fun switchThumbWorklet(
        slotId: Int,
        travelDp: Float = SWITCH_THUMB_TRAVEL_DP,
    ): IntArray =
        sampledWorklet(
            slotId = slotId,
            stops = floatArrayOf(0f, 1f),
            SampledTrack.floats(PROP_TRANSLATE_X, 0f, travelDp),
        )

    @JvmStatic
    @JvmOverloads
    fun triggerSwitch(
        batch: UiOps,
        slotId: Int,
        checked: Boolean,
        spring: SpringConfig = SpringConfig.MANA_SWITCH,
    ): UiOps =
        MotionBatchBuilder()
            .animateSpring(slotId, if (checked) 1f else 0f, spring)
            .appendTo(batch)

    @JvmStatic
    fun triggerToggleRow(
        batch: UiOps,
        handle: ManaUi.ToggleRowHandle,
        checked: Boolean,
    ): Boolean {
        val slot = handle.toggle.motionSlot
        if (slot < 0) return false
        batch.updateView(
            handle.rowId,
            ManaUi.props(ManaUi.prop("accessibilityChecked", UiOps.Value.ofBool(checked))),
        )
        triggerSwitch(batch, slot, checked, SpringConfig.MANA_SWITCH)
        return true
    }

    @JvmStatic
    fun applyToggleRow(
        handle: ManaUi.ToggleRowHandle,
        checked: Boolean,
    ): Boolean {
        if (handle.toggle.motionSlot < 0) return false
        val batch = UiOps.create()
        triggerToggleRow(batch, handle, checked)
        Loader.applySyntheticOps(batch.toBytes())
        return true
    }

    @JvmStatic
    fun checkboxBoxWorklet(
        slotId: Int,
        uncheckedBorder: Long,
        checkedAccent: Long,
    ): IntArray = radioOuterWorklet(slotId, uncheckedBorder, checkedAccent)

    @JvmStatic
    fun checkboxCheckWorklet(slotId: Int): IntArray =
        sampledWorklet(
            slotId = slotId,
            stops = floatArrayOf(0f, 1f),
            SampledTrack.floats(PROP_OPACITY, 0f, 1f),
            SampledTrack.floats(PROP_SCALE, 0.5f, 1f),
        )

    @JvmStatic
    fun triggerCheckbox(
        batch: UiOps,
        rowId: Int,
        motionSlot: Int,
        checked: Boolean,
    ): Boolean {
        if (motionSlot < 0) return false
        batch.updateView(
            rowId,
            ManaUi.props(ManaUi.prop("accessibilityChecked", UiOps.Value.ofBool(checked))),
        )
        triggerSwitch(batch, motionSlot, checked, SpringConfig.MANA_SWITCH)
        return true
    }

    @JvmStatic
    fun radioOuterWorklet(
        slotId: Int,
        uncheckedBorder: Long,
        checkedAccent: Long,
    ): IntArray =
        sampledWorklet(
            slotId = slotId,
            stops = floatArrayOf(0f, 1f),
            SampledTrack.colors(PROP_BACKGROUND, 0L, checkedAccent),
            SampledTrack.colors(PROP_BORDER_COLOR, uncheckedBorder, checkedAccent),
        )

    @JvmStatic
    fun radioInnerWrapWorklet(slotId: Int): IntArray =
        sampledWorklet(
            slotId = slotId,
            stops = floatArrayOf(0f, 1f),
            SampledTrack.floats(PROP_OPACITY, 0f, 1f),
        )

    @JvmStatic
    fun radioInnerDotWorklet(slotId: Int): IntArray =
        sampledWorklet(
            slotId = slotId,
            stops = floatArrayOf(0f, 1f),
            SampledTrack.floats(PROP_SCALE, 0.5f, 1f),
        )

    @JvmStatic
    fun triggerRadioRow(
        batch: UiOps,
        handle: ManaUi.RadioRowHandle,
        selected: Boolean,
    ): Boolean {
        val slot = handle.motionSlot
        if (slot < 0) return false
        batch.updateView(
            handle.rowId,
            ManaUi.props(
                ManaUi.prop("accessibilitySelected", UiOps.Value.ofBool(selected)),
                ManaUi.prop("accessibilityChecked", UiOps.Value.ofBool(selected)),
            ),
        )
        triggerSwitch(batch, slot, selected, SpringConfig.MANA_RADIO)
        return true
    }

    @JvmStatic
    @JvmOverloads
    fun pressScaleWorklet(
        slotId: Int,
        pressedScale: Float = 0.96f,
    ): IntArray =
        sampledWorklet(
            slotId = slotId,
            stops = floatArrayOf(0f, 1f),
            SampledTrack.floats(PROP_SCALE, 1f, pressedScale),
        )

    @JvmStatic
    fun triggerPressScale(
        batch: UiOps,
        slotId: Int,
        pressed: Boolean,
    ): UiOps =
        MotionBatchBuilder()
            .animateSpring(slotId, if (pressed) 1f else 0f, SpringConfig.PRESS_SCALE)
            .appendTo(batch)

    @JvmStatic
    fun segmentedIndicatorWorklet(
        slotId: Int,
        tabOffsetsDp: FloatArray,
    ): IntArray {
        require(tabOffsetsDp.size >= 2) { "Segmented indicator requires at least 2 tab offsets" }
        val stops = FloatArray(tabOffsetsDp.size) { i -> i.toFloat() }
        return sampledWorklet(
            slotId = slotId,
            stops = stops,
            SampledTrack.floats(PROP_TRANSLATE_X, *tabOffsetsDp),
        )
    }

    @JvmStatic
    fun triggerSegmentedIndicator(
        batch: UiOps,
        slotId: Int,
        selectedIndex: Int,
    ): UiOps =
        MotionBatchBuilder()
            .animateSpring(slotId, selectedIndex.toFloat(), SpringConfig.SEGMENTED_INDICATOR)
            .appendTo(batch)

    @JvmStatic
    fun segmentedPillTranslateWorklet(slotId: Int): IntArray =
        sampledWorklet(
            slotId = slotId,
            stops = floatArrayOf(-512f, 4096f),
            SampledTrack.floats(PROP_TRANSLATE_X, -512f, 4096f),
        )

    @JvmStatic
    fun triggerSegmentedPillX(
        batch: UiOps,
        slotId: Int,
        targetXDp: Float,
    ): UiOps =
        MotionBatchBuilder()
            .animateSpring(slotId, targetXDp, SpringConfig.SEGMENTED_INDICATOR)
            .appendTo(batch)

    @JvmStatic
    @JvmOverloads
    fun inputFocusWorklet(
        slotId: Int,
        idleBorder: Long,
        focusedBorder: Long,
        idleWidthDp: Float = 1f,
        focusedWidthDp: Float = 1.5f,
    ): IntArray =
        sampledWorklet(
            slotId = slotId,
            stops = floatArrayOf(0f, 1f),
            SampledTrack.colors(PROP_BORDER_COLOR, idleBorder, focusedBorder),
            SampledTrack.floats(PROP_BORDER_WIDTH, idleWidthDp, focusedWidthDp),
        )

    @JvmStatic
    fun triggerInputFocus(
        batch: UiOps,
        slotId: Int,
        focused: Boolean,
    ): UiOps =
        MotionBatchBuilder()
            .animateSpring(slotId, if (focused) 1f else 0f, SpringConfig.CLAMPED_COLOR)
            .appendTo(batch)

    @JvmStatic
    fun sheetTranslateWorklet(
        slotId: Int,
        closedOffsetDp: Float,
    ): IntArray =
        sampledWorklet(
            slotId = slotId,
            stops = floatArrayOf(0f, closedOffsetDp),
            SampledTrack.floats(PROP_TRANSLATE_Y, 0f, closedOffsetDp),
        )

    @JvmStatic
    @JvmOverloads
    fun sheetScrimOpacityWorklet(
        slotId: Int,
        closedOffsetDp: Float,
        maxOpacity: Float = 0.6f,
    ): IntArray =
        sampledWorklet(
            slotId = slotId,
            stops = floatArrayOf(0f, closedOffsetDp),
            SampledTrack.floats(PROP_OPACITY, maxOpacity, 0f),
        )

    @JvmStatic
    @JvmOverloads
    fun sheetDragPanWorklet(
        slotId: Int,
        snapPointsDp: FloatArray,
        dismissSnapIndex: Int = -1,
    ): IntArray {
        var minSnap = snapPointsDp[0]
        var maxSnap = snapPointsDp[0]
        for (i in 1 until snapPointsDp.size) {
            if (snapPointsDp[i] < minSnap) minSnap = snapPointsDp[i]
            if (snapPointsDp[i] > maxSnap) maxSnap = snapPointsDp[i]
        }
        return dragSnapWorklet(
            slotId = slotId,
            axis = AXIS_Y,
            snapPoints = snapPointsDp,
            min = minSnap,
            max = maxSnap,
            rubberbandMin = 0.15f,
            rubberbandMax = 0f,
            spring = SpringConfig.SHEET_SNAP,
            velocityScale = 0.25f,
            dismissSnapIndex = dismissSnapIndex,
        )
    }

    @JvmStatic
    fun triggerSheetSnap(
        batch: UiOps,
        slotId: Int,
        targetOffsetDp: Float,
    ): UiOps =
        MotionBatchBuilder()
            .animateSpring(slotId, targetOffsetDp, SpringConfig.SHEET_SNAP)
            .appendTo(batch)

    @JvmStatic
    fun resetForTests() {
        slots.reset()
        synchronized(workletLock) {
            themeWorkletsByScope.clear()
        }
    }
}
