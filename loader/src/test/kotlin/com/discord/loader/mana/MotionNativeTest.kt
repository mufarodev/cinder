package com.discord.loader.mana

import com.discord.loader.Loader
import com.discord.loader.ManaUi
import com.discord.loader.UiOps
import java.util.Arrays
import java.util.concurrent.atomic.AtomicInteger

object MotionNativeTest {
    private var passed = 0

    private val CAPTURED_BATCH_067_SWITCH_ON =
        intArrayOf(
            2,
            3510,
            0,
            7,
            2,
            1132396544,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3511,
            0,
            7,
            2,
            1118830592,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3512,
            0,
            7,
            2,
            1120534528,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3513,
            0,
            7,
            2,
            1131544576,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3517,
            0,
            7,
            2,
            1106771968,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3518,
            0,
            7,
            2,
            1125580800,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3519,
            0,
            7,
            2,
            1125580800,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3520,
            0,
            7,
            2,
            1126105088,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3523,
            0,
            7,
            2,
            1105199104,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
        )

    private val CAPTURED_BATCH_068_SWITCH_OFF =
        intArrayOf(
            2,
            3510,
            0,
            7,
            2,
            1106771968,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3511,
            0,
            7,
            2,
            0,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3512,
            0,
            7,
            2,
            0,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3513,
            0,
            7,
            2,
            0,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3517,
            0,
            7,
            2,
            1112276992,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3518,
            0,
            7,
            2,
            1125580800,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3519,
            0,
            7,
            2,
            1125580800,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3520,
            0,
            7,
            2,
            1126105088,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3523,
            0,
            7,
            2,
            0,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
        )

    private val CAPTURED_BATCH_071_RADIO_A_ON =
        intArrayOf(
            2,
            3308,
            0,
            7,
            2,
            1132396544,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3309,
            0,
            7,
            2,
            1118830592,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3310,
            0,
            7,
            2,
            1120534528,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3311,
            0,
            7,
            2,
            1131544576,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3312,
            0,
            7,
            2,
            1132396544,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3313,
            0,
            7,
            2,
            1118830592,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3314,
            0,
            7,
            2,
            1120534528,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3315,
            0,
            7,
            2,
            1131544576,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3316,
            0,
            7,
            2,
            1065353216,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3317,
            0,
            2,
            1065353216,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3318,
            0,
            7,
            2,
            0,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3319,
            0,
            7,
            2,
            0,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3320,
            0,
            7,
            2,
            0,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3321,
            0,
            7,
            2,
            0,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3322,
            0,
            7,
            2,
            1126367232,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3323,
            0,
            7,
            2,
            1125580800,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3324,
            0,
            7,
            2,
            1125580800,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3325,
            0,
            7,
            2,
            1126105088,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3326,
            0,
            7,
            2,
            0,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3327,
            0,
            2,
            1056964608,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
        )

    private val CAPTURED_BATCH_072_RADIO_B_ON =
        intArrayOf(
            2,
            3308,
            0,
            7,
            2,
            0,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3309,
            0,
            7,
            2,
            0,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3310,
            0,
            7,
            2,
            0,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3311,
            0,
            7,
            2,
            0,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3312,
            0,
            7,
            2,
            1126367232,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3313,
            0,
            7,
            2,
            1125580800,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3314,
            0,
            7,
            2,
            1125580800,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3315,
            0,
            7,
            2,
            1126105088,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3316,
            0,
            7,
            2,
            0,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3317,
            0,
            2,
            1056964608,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3318,
            0,
            7,
            2,
            1132396544,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3319,
            0,
            7,
            2,
            1118830592,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3320,
            0,
            7,
            2,
            1120534528,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3321,
            0,
            7,
            2,
            1131544576,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3322,
            0,
            7,
            2,
            1132396544,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3323,
            0,
            7,
            2,
            1118830592,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3324,
            0,
            7,
            2,
            1120534528,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3325,
            0,
            7,
            2,
            1131544576,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3326,
            0,
            7,
            2,
            1065353216,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
            2,
            3327,
            0,
            2,
            1065353216,
            1065353216,
            1133903872,
            1104674816,
            0,
            1008981770,
            1073741824,
            0,
        )

    private val CAPTURED_BATCH_040_CLAMPED_COLOR =
        intArrayOf(
            2,
            2878,
            0,
            7,
            2,
            1132396544,
            1050253722,
            1132068864,
            1095761920,
            1,
            1008981770,
            1073741824,
            0,
            2,
            2879,
            0,
            7,
            2,
            1125515264,
            1050253722,
            1132068864,
            1095761920,
            1,
            1008981770,
            1073741824,
            0,
            2,
            2880,
            0,
            7,
            2,
            1125580800,
            1050253722,
            1132068864,
            1095761920,
            1,
            1008981770,
            1073741824,
            0,
            2,
            2881,
            0,
            7,
            2,
            1126039552,
            1050253722,
            1132068864,
            1095761920,
            1,
            1008981770,
            1073741824,
            0,
            2,
            2888,
            0,
            7,
            2,
            1132396544,
            1050253722,
            1132068864,
            1095761920,
            1,
            1008981770,
            1073741824,
            0,
            2,
            2889,
            0,
            7,
            2,
            1132134400,
            1050253722,
            1132068864,
            1095761920,
            1,
            1008981770,
            1073741824,
            0,
            2,
            2890,
            0,
            7,
            2,
            1132134400,
            1050253722,
            1132068864,
            1095761920,
            1,
            1008981770,
            1073741824,
            0,
            2,
            2891,
            0,
            7,
            2,
            1132134400,
            1050253722,
            1132068864,
            1095761920,
            1,
            1008981770,
            1073741824,
            0,
        )

    private fun checkCond(
        cond: Boolean,
        msg: String,
    ) {
        if (!cond) throw AssertionError("FAIL: $msg")
    }

    private fun checkEq(
        actual: Any?,
        expected: Any?,
        msg: String,
    ) {
        if (actual != expected) {
            throw AssertionError("FAIL: $msg (expected=$expected, actual=$actual)")
        }
    }

    private fun checkWordsEq(
        actual: IntArray,
        expected: IntArray,
        msg: String,
    ) {
        if (!Arrays.equals(actual, expected)) {
            val n = minOf(actual.size, expected.size)
            var firstDiff = -1
            for (i in 0 until n) {
                if (actual[i] != expected[i]) {
                    firstDiff = i
                    break
                }
            }
            throw AssertionError(
                "FAIL: $msg (actualLen=${actual.size}, expectedLen=${expected.size}, " +
                    "firstDiff=$firstDiff, actual=${if (firstDiff >= 0) actual[firstDiff] else null}, " +
                    "expected=${if (firstDiff >= 0) expected[firstDiff] else null})",
            )
        }
    }

    private fun registerMotionProps(includeWorklet: Boolean = true) {
        val base =
            ArrayList<String>(
                listOf(
                    "",
                    "accessibilityChecked",
                    "accessibilityHidden",
                    "accessibilityId",
                    "accessibilityLabel",
                    "accessibilitySelected",
                    "activeBackground",
                    "align",
                    "background",
                    "borderColor",
                    "borderWidth",
                    "clip",
                    "color",
                    "direction",
                    "flex",
                    "fontSize",
                    "fontWeight",
                    "height",
                    "includeFontPadding",
                    "justify",
                    "lineHeight",
                    "lineHeightExact",
                    "margin",
                    "minHeight",
                    "on",
                    "opacity",
                    "padding",
                    "press",
                    "pressFeedback",
                    "radius",
                    "radiusBottomLeft",
                    "radiusBottomRight",
                    "radiusTopLeft",
                    "radiusTopRight",
                    "role",
                    "scale",
                    "scaleFactor",
                    "text",
                    "translateX",
                    "uri",
                    "width",
                ),
            )
        if (includeWorklet) {
            base.add("panWorklet")
            base.add("panWorkletAxis")
            base.add("panWorkletConfig")
            base.add("worklet")
        }
        UiOps.registerPropNames(base.toTypedArray())
    }

    private fun runTest(
        name: String,
        block: () -> Unit,
    ) {
        Loader.resetForTests()
        ManaMotion.resetForTests()
        registerMotionProps(includeWorklet = true)
        block()
        passed++
        println("PASS $name")
    }

    private fun testCapturedSwitchBatch067And068ExactWords() {
        val onWords =
            ManaMotion
                .batch()
                .animateArgbSlots(3510, 3511, 3512, 3513, 0xFF5865F2L, ManaMotion.SpringConfig.MANA_SWITCH)
                .animateArgbSlots(3517, 3518, 3519, 3520, 0x1F97979FL, ManaMotion.SpringConfig.MANA_SWITCH)
                .animateSpring(3523, 28f, ManaMotion.SpringConfig.MANA_SWITCH)
                .toIntArray()
        checkEq(onWords.size, 117, "batch_067 Switch ON word count")
        checkWordsEq(onWords, CAPTURED_BATCH_067_SWITCH_ON, "batch_067 Switch ON exact word match")

        val offWords =
            ManaMotion
                .batch()
                .animateArgbSlots(3510, 3511, 3512, 3513, 0x1F000000L, ManaMotion.SpringConfig.MANA_SWITCH)
                .animateArgbSlots(3517, 3518, 3519, 3520, 0x3397979FL, ManaMotion.SpringConfig.MANA_SWITCH)
                .animateSpring(3523, 0f, ManaMotion.SpringConfig.MANA_SWITCH)
                .toIntArray()
        checkEq(offWords.size, 117, "batch_068 Switch OFF word count")
        checkWordsEq(offWords, CAPTURED_BATCH_068_SWITCH_OFF, "batch_068 Switch OFF exact word match")

        val wire = UiOps.create().motion(*onWords).toBytes()
        val parsed = UiOps.parse(wire)
        checkCond(parsed != null && parsed.size() == 1, "OP_MOTION round-trips cleanly")
        checkWordsEq(parsed!!.get(0).motionWords(), CAPTURED_BATCH_067_SWITCH_ON, "binary round-trip exact")
    }

    private fun testCapturedRadioBatch071And072ExactWords() {
        val rawSpring = ManaMotion.SpringConfig.MANA_RADIO.copy(reducedMotion = false)

        val radioAOn =
            ManaMotion
                .batch()
                .animateArgbSlots(3308, 3309, 3310, 3311, 0xFF5865F2L, ManaMotion.SpringConfig.MANA_RADIO)
                .animateArgbSlots(3312, 3313, 3314, 3315, 0xFF5865F2L, ManaMotion.SpringConfig.MANA_RADIO)
                .animateSpring(3316, 1f, ManaMotion.SpringConfig.MANA_RADIO)
                .animateSpring(3317, 1f, rawSpring)
                .animateArgbSlots(3318, 3319, 3320, 3321, 0x00000000L, ManaMotion.SpringConfig.MANA_RADIO)
                .animateArgbSlots(3322, 3323, 3324, 3325, 0xA397979FL, ManaMotion.SpringConfig.MANA_RADIO)
                .animateSpring(3326, 0f, ManaMotion.SpringConfig.MANA_RADIO)
                .animateSpring(3327, 0.5f, rawSpring)
                .toIntArray()
        checkEq(radioAOn.size, 258, "batch_071 Radio word count")
        checkWordsEq(radioAOn, CAPTURED_BATCH_071_RADIO_A_ON, "batch_071 Radio exact word match")

        val radioBOn =
            ManaMotion
                .batch()
                .animateArgbSlots(3308, 3309, 3310, 3311, 0x00000000L, ManaMotion.SpringConfig.MANA_RADIO)
                .animateArgbSlots(3312, 3313, 3314, 3315, 0xA397979FL, ManaMotion.SpringConfig.MANA_RADIO)
                .animateSpring(3316, 0f, ManaMotion.SpringConfig.MANA_RADIO)
                .animateSpring(3317, 0.5f, rawSpring)
                .animateArgbSlots(3318, 3319, 3320, 3321, 0xFF5865F2L, ManaMotion.SpringConfig.MANA_RADIO)
                .animateArgbSlots(3322, 3323, 3324, 3325, 0xFF5865F2L, ManaMotion.SpringConfig.MANA_RADIO)
                .animateSpring(3326, 1f, ManaMotion.SpringConfig.MANA_RADIO)
                .animateSpring(3327, 1f, rawSpring)
                .toIntArray()
        checkEq(radioBOn.size, 258, "batch_072 Radio word count")
        checkWordsEq(radioBOn, CAPTURED_BATCH_072_RADIO_B_ON, "batch_072 Radio exact word match")
    }

    private fun testCapturedClampedColorBatch040ExactWords() {
        val words =
            ManaMotion
                .batch()
                .animateArgbSlots(2878, 2879, 2880, 2881, 0xFF96979EL, ManaMotion.SpringConfig.CLAMPED_COLOR)
                .animateArgbSlots(2888, 2889, 2890, 2891, 0xFFFBFBFBL, ManaMotion.SpringConfig.CLAMPED_COLOR)
                .toIntArray()
        checkEq(words.size, 104, "batch_040 clamped color word count")
        checkWordsEq(words, CAPTURED_BATCH_040_CLAMPED_COLOR, "batch_040 clamped color exact word match")
    }

    private fun testAllOpMotionCommandsAndStreamWalker() {
        val fadeW = ManaMotion.fadeWorklet(49_160)
        val stream =
            ManaMotion
                .batch()
                .initValue(49_152, 0f)
                .setValue(49_153, 1.5f)
                .animateTiming(49_154, 1f, 200f, ManaMotion.EASING_BEZIER, 0.2f, 0f, 0.2f, 1f, reducedMotion = true)
                .animateDecay(49_155, velocity = 800f, deceleration = 0.998f, clampMin = 0f, clampMax = 400f, clamp = true)
                .animateDelayedSpring(49_156, delayMs = 50f, toValue = 1f, spring = ManaMotion.SpringConfig.MANA_SWITCH)
                .animateSpringSequence(49_157, floatArrayOf(1.1f, 1.0f), ManaMotion.SpringConfig.PRESS_SCALE)
                .animateRepeatingTiming(49_158, count = -1, reverse = true, toValue = 1f, durationMs = 600f)
                .bindWorklet(viewId = 0x7000_0100, workletWords = fadeW, kind = ManaMotion.WORKLET_KIND_STYLE)
                .unbindWorklet(viewId = 0x7000_0100, kind = ManaMotion.WORKLET_KIND_STYLE)
                .freeSlot(49_159)
                .toIntArray()

        checkEq(stream[0], ManaMotion.OP_INIT_VALUE, "cmd 0 is OP_INIT_VALUE (8)")
        checkEq(stream[1], 49_152, "cmd 0 slotId")
        checkEq(stream[2], ManaMotion.f32(0f), "cmd 0 value 0f")
        checkEq(stream[3], ManaMotion.OP_SET_VALUE, "cmd 1 is OP_SET_VALUE (1)")
        checkEq(stream[4], 49_153, "cmd 1 slotId")
        checkEq(stream[5], ManaMotion.f32(1.5f), "cmd 1 value 1.5f")

        val seenSlots = ArrayList<Int>()
        val seenOps = ArrayList<Int>()
        ManaMotion.walkMotionStream(stream) { op, slotId ->
            seenOps.add(op)
            seenSlots.add(slotId)
        }
        checkEq(
            seenSlots,
            listOf(49_152, 49_153, 49_154, 49_155, 49_156, 49_157, 49_158, 49_159),
            "walkMotionStream decoded every opcode & AnimSpec cleanly",
        )
        checkEq(
            seenOps,
            listOf(
                ManaMotion.OP_INIT_VALUE,
                ManaMotion.OP_SET_VALUE,
                ManaMotion.OP_ANIMATE,
                ManaMotion.OP_ANIMATE,
                ManaMotion.OP_ANIMATE,
                ManaMotion.OP_ANIMATE,
                ManaMotion.OP_ANIMATE,
                ManaMotion.OP_FREE_SLOT,
            ),
            "walkMotionStream opcodes match",
        )
    }

    private fun testBuiltinWorkletEncodings() {
        val trackW =
            ManaMotion.switchTrackWorklet(
                slotId = 49_152,
                offBg = 0x1F000000L,
                onBg = 0xFF5865F2L,
                offBorder = 0x3397979FL,
                onBorder = 0x1F97979FL,
            )
        checkWordsEq(
            trackW,
            intArrayOf(
                ManaMotion.WORKLET_SAMPLED,
                49_152,
                2,
                ManaMotion.f32(0f),
                ManaMotion.f32(1f),
                2,
                ManaMotion.PROP_BACKGROUND,
                2,
                0x1F000000,
                0xFF5865F2.toInt(),
                ManaMotion.PROP_BORDER_COLOR,
                2,
                0x3397979F,
                0x1F97979F,
            ),
            "builtin::sampled Switch track exact 14-word encoding",
        )

        val thumbW = ManaMotion.switchThumbWorklet(49_152, 28f)
        checkWordsEq(
            thumbW,
            intArrayOf(
                ManaMotion.WORKLET_SAMPLED,
                49_152,
                2,
                ManaMotion.f32(0f),
                ManaMotion.f32(1f),
                1,
                ManaMotion.PROP_TRANSLATE_X,
                2,
                ManaMotion.f32(0f),
                ManaMotion.f32(28f),
            ),
            "builtin::sampled Switch thumb exact 10-word encoding",
        )

        val kfW =
            ManaMotion.keyframesWorklet(
                durationSec = 1.2f,
                mode = ManaMotion.KEYFRAME_MODE_PING_PONG,
                flags = 0,
                ManaMotion.SampledTrack.floats(ManaMotion.PROP_OPACITY, 0.3f, 1.0f),
            )
        checkWordsEq(
            kfW,
            intArrayOf(
                ManaMotion.WORKLET_KEYFRAMES,
                ManaMotion.f32(1.2f),
                ManaMotion.KEYFRAME_MODE_PING_PONG,
                0,
                1,
                ManaMotion.PROP_OPACITY,
                2,
                ManaMotion.f32(0.3f),
                ManaMotion.f32(1.0f),
            ),
            "builtin::keyframes exact layout",
        )

        checkWordsEq(
            ManaMotion.fadeWorklet(49_152),
            intArrayOf(ManaMotion.WORKLET_FADE, 49_152),
            "app::fade exact layout",
        )
        checkWordsEq(
            ManaMotion.translateWorklet(49_152, 49_153),
            intArrayOf(ManaMotion.WORKLET_TRANSLATE, 49_152, 49_153),
            "app::translate exact layout",
        )
        checkWordsEq(
            ManaMotion.translateScaleWorklet(49_152, 49_153, 49_154),
            intArrayOf(ManaMotion.WORKLET_TRANSLATE_SCALE, 49_152, 49_153, 49_154),
            "app::translate_scale exact layout",
        )

        var rejectedColorAsFloat = false
        try {
            ManaMotion.SampledTrack.floats(ManaMotion.PROP_BACKGROUND, 0f, 1f)
        } catch (_: IllegalArgumentException) {
            rejectedColorAsFloat = true
        }
        checkCond(rejectedColorAsFloat, "PROP_BACKGROUND rejected by SampledTrack.floats()")

        var rejectedHugeSlot = false
        try {
            ManaMotion.batch().animateSpring(0x7000_0000, 1f)
        } catch (_: IllegalArgumentException) {
            rejectedHugeSlot = true
        }
        checkCond(rejectedHugeSlot, "0x7000_0000 viewId rejected as motion slotId (> 65536)")
    }

    private fun testMotionSlotAllocatorBoundsCollisionAndRecycling() {
        val alloc = ManaMotion.MotionSlotAllocator()

        alloc.observeRustMotionWords(CAPTURED_BATCH_067_SWITCH_ON)
        checkEq(alloc.maxObservedRustSlot(), 3523, "observed max Rust slot=3523 from batch_067")

        val s0 = alloc.allocSlot("page.a")
        val s1 = alloc.allocSlot("page.a")
        val s2 = alloc.allocSlot("page.b")
        checkEq(s0, ManaMotion.SLOT_MIN, "first synthetic slot is 49152 (0xC000)")
        checkEq(s1, ManaMotion.SLOT_MIN + 1, "second synthetic slot is 49153")
        checkEq(s2, ManaMotion.SLOT_MIN + 2, "third synthetic slot is 49154")
        checkCond(s0 <= ManaMotion.SLOT_MAX && s2 < ManaMotion.ENGINE_HARD_SLOT_LIMIT, "slots under 65536 cap")
        checkEq(alloc.activeSlotCount("page.a"), 2, "2 active slots in page.a")
        checkEq(alloc.activeSlotCount(), 3, "3 total active slots")

        val freed = alloc.releaseScope("page.a")
        checkWordsEq(freed, intArrayOf(s0, s1), "releaseScope returns exact released slots")
        checkEq(alloc.activeSlotCount("page.a"), 0, "page.a now empty")
        val r0 = alloc.allocSlot("page.c")
        checkEq(r0, s0, "recycled slot 49152 first")

        alloc.observeRustMotionWords(intArrayOf(ManaMotion.OP_INIT_VALUE, 50_000, ManaMotion.f32(0f)))
        checkEq(alloc.maxObservedRustSlot(), 50_000, "observed high Rust slot 50000")
        val afterHighRust = alloc.allocSlot("page.c")
        checkEq(afterHighRust, 50_001, "allocator jumped cleanly past Rust slot 50000 (skipping 49153 in free-list)")

        val tiny = ManaMotion.MotionSlotAllocator(minSlot = 65_534, maxSlot = 65_535)
        checkEq(tiny.allocSlot("s"), 65_534, "tiny slot 0")
        checkEq(tiny.allocSlot("s"), 65_535, "tiny slot 1")
        var exhausted = false
        try {
            tiny.allocSlot("s")
        } catch (_: IllegalStateException) {
            exhausted = true
        }
        checkCond(exhausted, "allocator refuses to exceed maxSlot (65535 < 65536 hard cap)")
        tiny.releaseScope("s")
        checkEq(tiny.allocSlot("s"), 65_534, "tiny slot recycled cleanly after release")
    }

    private fun testNativeSwitchRadioAndLiveThemeReskin() {
        val idSeq = AtomicInteger(UiOps.SYNTHETIC_ID_BASE + 1000)
        val ids = ManaUi.IdAllocator { idSeq.getAndIncrement() }
        val darkTheme = ThemeEngine.currentTheme()
        val buildBatch = UiOps.create()

        var swHandle: ManaUi.ToggleRowHandle? = null
        var radioHandle: ManaUi.RadioRowHandle? = null
        ThemeEngine.withScope("test.motion") {
            swHandle =
                ManaUi.toggleRow(
                    buildBatch,
                    ids,
                    darkTheme,
                    null,
                    "Native Switch",
                    "120Hz",
                    false,
                    "test.sw",
                    ManaUi.RowPosition.FIRST,
                )
            radioHandle =
                ManaUi.radioRow(
                    buildBatch,
                    ids,
                    darkTheme,
                    "Native Radio",
                    "120Hz",
                    true,
                    "test.rd",
                    ManaUi.RowPosition.LAST,
                )
        }
        val sw = swHandle!!
        val rd = radioHandle!!

        checkCond(sw.toggle.motionSlot >= ManaMotion.SLOT_MIN, "Switch got native motionSlot")
        checkCond(rd.motionSlot >= ManaMotion.SLOT_MIN, "Radio got native motionSlot")
        checkCond(sw.toggle.motionSlot != rd.motionSlot, "Switch and Radio got distinct motionSlots")
        checkEq(ManaMotion.activeSlotCount("test.motion"), 2, "2 active motion slots in scope")
        checkEq(ManaMotion.themeWorkletBindingCount("test.motion"), 2, "2 theme-driven worklets bound")

        var sawSwInit = false
        var sawRdInit = false
        for (op in buildBatch.ops()) {
            if (op.opcode() == UiOps.OP_MOTION) {
                val w = op.motionWords()
                if (w != null && w.size == 3 && w[0] == ManaMotion.OP_INIT_VALUE) {
                    if (w[1] == sw.toggle.motionSlot && w[2] == ManaMotion.f32(0f)) sawSwInit = true
                    if (w[1] == rd.motionSlot && w[2] == ManaMotion.f32(1f)) sawRdInit = true
                }
            }
        }
        checkCond(sawSwInit, "Switch slot initialized to 0f via OP_INIT_VALUE (8)")
        checkCond(sawRdInit, "Radio slot initialized to 1f via OP_INIT_VALUE (8)")

        val swToggleBatch = UiOps.create()
        checkCond(ManaMotion.triggerToggleRow(swToggleBatch, sw, true), "triggerToggleRow returned true")
        checkEq(swToggleBatch.size(), 2, "Switch toggle is 2-op batch")
        checkEq(swToggleBatch.get(1).motionWords().size, 13, "Switch toggle emits 13-word spring")
        checkEq(swToggleBatch.get(1).motionWords()[5], ManaMotion.f32(1f), "Switch springs to 1.0f")

        val rdToggleBatch = UiOps.create()
        checkCond(ManaMotion.triggerRadioRow(rdToggleBatch, rd, false), "triggerRadioRow returned true")
        checkEq(rdToggleBatch.size(), 2, "Radio toggle is 2-op batch")
        checkEq(rdToggleBatch.get(1).motionWords().size, 13, "Radio toggle emits 13-word spring")
        checkEq(rdToggleBatch.get(1).motionWords()[5], ManaMotion.f32(0f), "Radio springs to 0.0f")

        val reskin = ThemeEngine.applyPreset(ThemePreset.LIGHT, applyReskin = false)
        var trackUpdates = 0
        var radioOuterUpdates = 0
        var trackNewWorklet: IntArray? = null
        var radioNewWorklet: IntArray? = null
        for (op in reskin.ops()) {
            if (op.opcode() == UiOps.OP_UPDATE && op.viewId() == sw.toggle.trackId) {
                trackUpdates++
                trackNewWorklet = op.findProp("worklet")?.value?.asWordList()
            }
            if (op.opcode() == UiOps.OP_UPDATE && op.viewId() == rd.outerCircleId) {
                radioOuterUpdates++
                radioNewWorklet = op.findProp("worklet")?.value?.asWordList()
            }
        }
        checkEq(trackUpdates, 1, "Switch track gets strictly ONE atomic OP_UPDATE on theme switch")
        checkEq(radioOuterUpdates, 1, "Radio outer gets strictly ONE atomic OP_UPDATE on theme switch")
        checkCond(trackNewWorklet != null, "Switch track reskin includes updated worklet prop")
        checkCond(radioNewWorklet != null, "Radio outer reskin includes updated worklet prop")

        val light = ManaPalette.LIGHT
        val expectedLightTrackW =
            ManaMotion.switchTrackWorklet(
                sw.toggle.motionSlot,
                light[ColorToken.SWITCH_OFF_BG],
                light[ColorToken.SWITCH_ON_BG],
                light[ColorToken.SWITCH_OFF_BORDER],
                light[ColorToken.SWITCH_ON_BORDER],
            )
        checkWordsEq(trackNewWorklet!!, expectedLightTrackW, "Switch track worklet updated to LIGHT palette")

        val expectedLightRadioW =
            ManaMotion.radioOuterWorklet(
                rd.motionSlot,
                light[ColorToken.RADIO_UNCHECKED_BORDER],
                light[ColorToken.BRAND_500],
            )
        checkWordsEq(radioNewWorklet!!, expectedLightRadioW, "Radio outer worklet updated to LIGHT palette")

        val teardown = UiOps.create()
        val released = ManaMotion.releaseScopeInto("test.motion", teardown)
        checkWordsEq(released, intArrayOf(sw.toggle.motionSlot, rd.motionSlot), "released both slots")
        checkEq(ManaMotion.activeSlotCount("test.motion"), 0, "0 active slots after release")
        checkEq(ManaMotion.themeWorkletBindingCount("test.motion"), 0, "0 theme worklets after release")
        checkEq(teardown.size(), 1, "teardown appended 1 OP_MOTION batch")
        checkWordsEq(
            teardown.get(0).motionWords(),
            intArrayOf(
                ManaMotion.OP_FREE_SLOT,
                sw.toggle.motionSlot,
                ManaMotion.OP_FREE_SLOT,
                rd.motionSlot,
            ),
            "teardown emits exact OP_FREE_SLOT (9) commands",
        )
    }

    private fun testPressScaleSegmentedAndBottomSheetRecipes() {
        val slot = ManaMotion.allocSlot("recipes")

        val pressW = ManaMotion.pressScaleWorklet(slot, 0.95f)
        checkEq(pressW[0], ManaMotion.WORKLET_SAMPLED, "pressScale uses builtin::sampled")
        checkEq(pressW[6], ManaMotion.PROP_SCALE, "pressScale targets PROP_SCALE (3)")
        checkEq(pressW[9], ManaMotion.f32(0.95f), "pressScale pressed value = 0.95f")
        val pressBatch = UiOps.create()
        ManaMotion.triggerPressScale(pressBatch, slot, true)
        checkEq(pressBatch.get(0).motionWords().size, 13, "pressScale spring is 13 words")

        val segW = ManaMotion.segmentedIndicatorWorklet(slot, floatArrayOf(0f, 112f, 224f))
        checkEq(segW[0], ManaMotion.WORKLET_SAMPLED, "segmented uses builtin::sampled")
        checkEq(segW[2], 3, "segmented has 3 stops")
        checkEq(segW[7], ManaMotion.PROP_TRANSLATE_X, "segmented targets PROP_TRANSLATE_X (1)")
        val segBatch = UiOps.create()
        ManaMotion.triggerSegmentedIndicator(segBatch, slot, 2)

        checkEq(segBatch.get(0).motionWords().size, 12, "segmented spring is 12 words (no reduced-motion wrapper)")
        checkEq(segBatch.get(0).motionWords()[4], ManaMotion.f32(2f), "segmented springs to index 2.0f")

        val sheetTx = ManaMotion.sheetTranslateWorklet(slot, 420f)
        val sheetScrim = ManaMotion.sheetScrimOpacityWorklet(slot, 420f, 0.6f)
        val sheetPan = ManaMotion.sheetDragPanWorklet(slot, floatArrayOf(0f, 420f), dismissSnapIndex = 1)
        checkEq(sheetTx[0], ManaMotion.WORKLET_SAMPLED, "sheetTranslate uses builtin::sampled")
        checkEq(sheetScrim[6], ManaMotion.PROP_OPACITY, "sheetScrim targets PROP_OPACITY (7)")
        checkEq(sheetPan[0], ManaMotion.WORKLET_DRAG_SNAP, "sheetDragPan uses builtin::drag_snap")
        checkEq(sheetPan[2], ManaMotion.AXIS_Y, "sheetDragPan uses AXIS_Y (1)")
        checkEq(sheetPan[sheetPan.size - 1], 1, "sheetDragPan dismissSnapIndex=1")
    }

    private fun testFallbackWithoutWorkletProp() {
        registerMotionProps(includeWorklet = false)
        checkCond(!ManaMotion.isWorkletSupported(), "isWorkletSupported() false when worklet prop unregistered")

        val idSeq = AtomicInteger(UiOps.SYNTHETIC_ID_BASE + 2000)
        val ids = ManaUi.IdAllocator { idSeq.getAndIncrement() }
        val batch = UiOps.create()
        var sw: ManaUi.ToggleRowHandle? = null
        ThemeEngine.withScope("fallback.scope") {
            sw =
                ManaUi.toggleRow(
                    batch,
                    ids,
                    ThemeEngine.currentTheme(),
                    null,
                    "Fallback",
                    null,
                    false,
                    "fallback.sw",
                    ManaUi.RowPosition.SINGLE,
                )
        }
        checkEq(sw!!.toggle.motionSlot, -1, "fallback switch has motionSlot == -1")
        checkEq(ManaMotion.activeSlotCount("fallback.scope"), 0, "no motion slots allocated in fallback mode")
        checkCond(!ManaMotion.applyToggleRow(sw, true), "applyToggleRow returns false so ManaAnimator takes over")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        runTest("capturedSwitchBatch067And068ExactWords", ::testCapturedSwitchBatch067And068ExactWords)
        runTest("capturedRadioBatch071And072ExactWords", ::testCapturedRadioBatch071And072ExactWords)
        runTest("capturedClampedColorBatch040ExactWords", ::testCapturedClampedColorBatch040ExactWords)
        runTest("allOpMotionCommandsAndStreamWalker", ::testAllOpMotionCommandsAndStreamWalker)
        runTest("builtinWorkletEncodings", ::testBuiltinWorkletEncodings)
        runTest("motionSlotAllocatorBoundsCollisionAndRecycling", ::testMotionSlotAllocatorBoundsCollisionAndRecycling)
        runTest("nativeSwitchRadioAndLiveThemeReskin", ::testNativeSwitchRadioAndLiveThemeReskin)
        runTest("pressScaleSegmentedAndBottomSheetRecipes", ::testPressScaleSegmentedAndBottomSheetRecipes)
        runTest("fallbackWithoutWorkletProp", ::testFallbackWithoutWorkletProp)
        println("ALL $passed MotionNativeTest TESTS PASSED")
    }
}
