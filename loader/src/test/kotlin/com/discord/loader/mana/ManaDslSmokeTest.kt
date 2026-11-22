package com.discord.loader.mana

import com.discord.loader.Loader
import com.discord.loader.ManaUi
import com.discord.loader.UiOps

object ManaDslSmokeTest {
    private var passed = 0

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

    private fun runTest(
        name: String,
        block: () -> Unit,
    ) {
        block()
        passed++
        println("PASS $name")
    }

    private fun testDslBatchAndViewWireRoundTrip() {
        val theme = ManaUi.Theme.defaults()
        val batch =
            manaBatch(theme = theme, allocator = monotonicAllocator(0x7001_0000)) {
                val root =
                    view(type = "view", tag = "root") {
                        prop(1, theme.screenBg)
                        prop(2, true)
                        prop(3, "mana-dsl")
                        prop(4, ManaUi.SWITCH_SPRING)
                    }
                val child =
                    view(type = "text", tag = "title") {
                        prop(5, 16.0)
                        prop(6, null)
                    }
                children(root, child)
            }

        checkEq(batch.rootId, 0x7001_0000, "rootId")
        checkEq(batch.allocatedIds.toList(), listOf(0x7001_0000, 0x7001_0001), "allocatedIds")
        checkEq(batch.opCount, 3, "opCount (2 creates + 1 children)")
        checkEq(batch.nodes.size, 2, "node count")
        checkEq(batch.nodes[0], ManaNodeMeta(0x7001_0000, "view", "root"), "node[0] metadata")
        checkEq(batch.nodes[1], ManaNodeMeta(0x7001_0001, "text", "title"), "node[1] metadata")

        val wire = batch.toBytes()
        val parsed = UiOps.parse(wire)
        checkCond(parsed != null, "UiOps.parse decoded DSL wire bytes")
        checkEq(parsed!!.size(), 3, "round-tripped op count")
        checkEq(parsed.get(0).opcode(), UiOps.OP_CREATE, "op 0 is OP_CREATE")
        checkEq(parsed.get(0).viewType(), "view", "op 0 viewType")
        checkEq(parsed.get(1).viewType(), "text", "op 1 viewType")
        checkEq(parsed.get(2).opcode(), UiOps.OP_CHILDREN, "op 2 is OP_CHILDREN")
        checkEq(parsed.get(2).childIds().toList(), listOf(0x7001_0001), "children match")
    }

    private fun testThemeToneAndNamedPropsInterop() {
        UiOps.registerPropNames(arrayOf("", "text", "color", "fontSize", "accessibilityId"))
        val theme = ManaUi.Theme.defaults()

        checkEq(theme.colorOf(ManaTone.DEFAULT), theme.primaryText, "DEFAULT tone")
        checkEq(theme.colorOf(ManaTone.ACCENT), theme.brandAccent, "ACCENT tone")
        checkEq(theme.colorOf(ManaTone.DANGER), theme.dangerText, "DANGER tone")
        checkEq(theme.colorOf(ManaTone.MUTED), theme.mutedText, "MUTED tone")

        val batch =
            manaBatch(theme = theme) {
                val container =
                    view(type = "view", tag = "card") {
                        prop("accessibilityId", "cinder-kotlin-card")
                        checkCond(!propIfKnown("nonexistent_prop_xyz", 42), "unknown prop skipped")
                    }
                val titleId = label("Cinder Mana", tone = ManaTone.ACCENT, fontSize = 18.0, tag = "hdr")
                children(container, titleId)
            }

        val parsed = UiOps.parse(batch.toBytes())!!
        checkEq(parsed.get(0).propString("accessibilityId"), "cinder-kotlin-card", "named prop")
        checkEq(parsed.get(1).propString("text"), "Cinder Mana", "label text prop")
        checkEq(
            parsed
                .get(1)
                .findProp("color")
                .value
                .asLong(),
            theme.brandAccent,
            "label accent color",
        )
        checkEq(
            parsed
                .get(1)
                .findProp("fontSize")
                .value
                .asDouble(),
            18.0,
            "label fontSize",
        )
    }

    private fun testRepeatViewsRangesAndValuePatterns() {
        val captured = ArrayList<Pair<Int, ManaViewId>>()
        val batch =
            manaBatch(allocator = monotonicAllocator(UiOps.SYNTHETIC_ID_BASE + 100)) {
                val root = view(type = "view", tag = "list")
                val rowIds =
                    repeatViews(0..2, type = "view") { idx, id ->
                        captured.add(idx to ManaViewId(id))
                    }
                children(root, *rowIds)
            }

        checkEq(captured.size, 3, "repeatViews invoked 3 times")
        for ((expectedIdx, pair) in captured.withIndex()) {
            checkEq(pair.first, expectedIdx, "lambda index $expectedIdx")
            checkCond(pair.second.isSynthetic, "ManaViewId($expectedIdx) is synthetic")
        }

        val metaA = ManaNodeMeta(1, "view", "a")
        val metaB = metaA.copy(tag = "b")
        checkCond(metaA != metaB, "data class copy inequality")
        checkEq(metaA.copy(tag = "b"), metaB, "data class equality")
        checkEq(batch, batch.copy(), "ManaBatch equality")
    }

    private fun testJavaKotlinStartupInterop() {
        val summary = ManaDslRuntime.verifyStartup()
        checkCond(summary.contains("Mana Kotlin DSL ready"), "startup summary prefix")
        checkCond(summary.contains("ops=4"), "startup ops=4")
        checkCond(summary.contains("ids=3"), "startup ids=3")
        checkCond(summary.contains("checks=0:true,1:true"), "startup synthetic checks")

        Loader.init(null)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        runTest("testDslBatchAndViewWireRoundTrip", ::testDslBatchAndViewWireRoundTrip)
        runTest("testThemeToneAndNamedPropsInterop", ::testThemeToneAndNamedPropsInterop)
        runTest("testRepeatViewsRangesAndValuePatterns", ::testRepeatViewsRangesAndValuePatterns)
        runTest("testJavaKotlinStartupInterop", ::testJavaKotlinStartupInterop)
        println("ALL $passed KOTLIN TESTS PASSED")
    }
}
