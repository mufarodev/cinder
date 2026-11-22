package com.discord.loader.mana

import com.discord.loader.CinderSettings
import com.discord.loader.Frame
import com.discord.loader.HostInvoker
import com.discord.loader.Loader
import com.discord.loader.ManaUi
import com.discord.loader.UiOps
import java.util.Arrays
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object ManaSdkCoreTest {
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

    private fun registerAllProps() {
        UiOps.registerPropNames(
            arrayOf(
                "",
                "absolute",
                "accessibilityChecked",
                "accessibilityHidden",
                "accessibilityId",
                "accessibilityLabel",
                "accessibilitySelected",
                "accessible",
                "activeBackground",
                "align",
                "animate",
                "appeared",
                "autoCapitalize",
                "autoCorrect",
                "background",
                "blur",
                "borderColor",
                "borderWidth",
                "changeText",
                "clip",
                "color",
                "direction",
                "dismiss",
                "dismissLabel",
                "dismissible",
                "exitEnd",
                "expanded",
                "flex",
                "focus",
                "fontSize",
                "fontWeight",
                "fullScreenBack",
                "fullscreen",
                "gap",
                "gravity",
                "handleColor",
                "height",
                "hitSlop",
                "includeFontPadding",
                "index",
                "justify",
                "keyboardDismiss",
                "keyboardShouldPersistTaps",
                "layout",
                "lineHeight",
                "lineHeightExact",
                "longPress",
                "mana",
                "margin",
                "maxLines",
                "minHeight",
                "minWidth",
                "navigate",
                "on",
                "opacity",
                "padding",
                "paddingBox",
                "panWorklet",
                "panWorkletAxis",
                "panWorkletConfig",
                "placeholder",
                "placeholderColor",
                "presentation",
                "press",
                "pressFeedback",
                "radius",
                "radiusBottomLeft",
                "radiusBottomRight",
                "radiusTopLeft",
                "radiusTopRight",
                "returnKey",
                "role",
                "scale",
                "scaleFactor",
                "shadow",
                "shrink",
                "stackHeader",
                "submit",
                "text",
                "textAlign",
                "tint",
                "translateX",
                "translateY",
                "uri",
                "value",
                "width",
                "worklet",
            ),
        )
    }

    private class RecordingDispatcher : ManaPatchDispatcher {
        val batches = ArrayList<UiOps>()

        override fun dispatch(ops: UiOps): Boolean {
            val bytes = ops.toBytes()
            val parsed = UiOps.parse(bytes, bytes.size)
            checkCond(parsed != null, "dispatched UiOps must round-trip cleanly")
            batches.add(parsed!!)
            return true
        }

        fun last(): UiOps = batches[batches.size - 1]
    }

    private class RecordingHost : HostInvoker {
        val appliedSynthetic = ArrayList<UiOps>()

        override fun invokeOrig(frame: ByteArray): ByteArray? = null

        override fun applyOps(
            ops: ByteArray,
            length: Int,
        ) {
            val hooked = Loader.onOps(this, ops, length)
            val parsed = UiOps.parse(hooked, if (hooked === ops) length else hooked.size)
            checkCond(parsed != null, "every synthetic op batch must parse cleanly")
            appliedSynthetic.add(parsed!!)
        }
    }

    private fun installSyncHost(): RecordingHost {
        val host = RecordingHost()
        Loader.registerHost(host)
        Loader.setSyntheticExecutorForTests(Executor { cmd -> cmd.run() })
        return host
    }

    private fun p(vararg kv: Pair<String, UiOps.Value>): List<UiOps.Prop> = kv.map { (k, v) -> UiOps.Prop.named(k, v) }

    private fun colorVal(argb: Long): UiOps.Value {
        val u = argb and 0xFFFFFFFFL
        return if (u > Int.MAX_VALUE) UiOps.Value.ofLong(u) else UiOps.Value.ofInt(u.toInt())
    }

    private fun viewEvent(
        viewId: Int,
        eventName: String,
        payload: UiOps.Value? = null,
    ): UiOps.ViewEvent {
        val bytes = UiOps.ViewEvent.buildViewEvent(viewId, UiOps.propId(eventName), payload)
        return UiOps.ViewEvent.parse(bytes)!!
    }

    private fun runTest(
        name: String,
        block: () -> Unit,
    ) {
        Loader.resetForTests()
        ThemeEngine.resetForTests()
        ManaMotion.resetForTests()
        ManaRuntime.resetForTests()
        registerAllProps()
        block()
        passed++
        println("PASS $name")
    }

    private fun testManaStateReactivityMapCombineAndAutoDispose() {
        val scope = ManaScope("state-test")
        val count = intState(0)
        val label = stringState("alpha")

        val observedCounts = ArrayList<Int>()
        count.observe(scope, emitInitial = true) { observedCounts.add(it) }
        checkEq(observedCounts, listOf(0), "emitInitial=true delivers initial value")

        count.value = 0
        checkEq(observedCounts, listOf(0), "same value deduplicated")

        count.update { it + 1 }
        count.update { it + 2 }
        checkEq(observedCounts, listOf(0, 1, 3), "distinct updates delivered in order")

        val parity = count.map(scope) { if (it % 2 == 0) "even" else "odd" }
        val parityLog = ArrayList<String>()
        parity.observe(scope) { parityLog.add(it) }
        checkEq(parity.value, "odd", "initial mapped parity")

        count.value = 5
        checkEq(parityLog, emptyList<String>(), "unchanged mapped output deduplicated")
        count.value = 6
        checkEq(parityLog, listOf("even"), "changed mapped output delivered")

        val combined = combineState(scope, count, label) { c, l -> "$l:$c" }
        val combinedLog = ArrayList<String>()
        combined.observe(scope) { combinedLog.add(it) }
        checkEq(combined.value, "alpha:6", "initial combined value")

        label.value = "beta"
        count.value = 7
        checkEq(combinedLog, listOf("beta:6", "beta:7"), "combined reacts to either source")

        checkCond(count.listenerCount() >= 2, "source states hold scoped listeners before unmount")
        checkCond(label.listenerCount() >= 1, "label holds scoped listener before unmount")

        scope.unmountWithoutOps()
        checkEq(count.listenerCount(), 0, "count listeners cleared on unmount")
        checkEq(label.listenerCount(), 0, "label listeners cleared on unmount")
        checkEq(parity.listenerCount(), 0, "mapped listeners cleared on unmount")
        checkEq(combined.listenerCount(), 0, "combined listeners cleared on unmount")

        count.value = 99
        label.value = "gamma"
        checkEq(observedCounts, listOf(0, 1, 3, 5, 6, 7), "no notifications after unmount")
        checkEq(combinedLog, listOf("beta:6", "beta:7"), "no combined notifications after unmount")
    }

    private fun testManaScopeIdsTypedEventsLivePatchesAndTeardown() {
        val dispatcher = RecordingDispatcher()
        val s1 = ManaScope("scope-1", dispatcher)
        val s2 = ManaScope("scope-2", dispatcher)

        val a1 = s1.nextId()
        val a2 = s2.nextId()
        val a3 = s1.nextId()
        checkCond(a1 >= ManaIds.SYNTHETIC_START, "IDs start at SYNTHETIC_START")
        checkCond(a2 > a1 && a3 > a2, "global monotonic IDs across concurrent scopes")
        checkCond(s1.ownsView(a1) && s1.ownsView(a3) && !s1.ownsView(a2), "view ownership isolated per scope")
        checkCond(s2.ownsView(a2) && !s2.ownsView(a1), "scope 2 owns only its own IDs")

        val events = ArrayList<String>()
        s1.onPress(a1) { events.add("press") }
        s1.onLongPress(a1) { events.add("longPress") }
        s1.onChangeText(a1) { events.add("change:$it") }
        s1.onSubmit(a1) { events.add("submit:$it") }
        s1.onFocus(a1) { events.add("focus") }
        s1.onBlur(a1) { events.add("blur") }
        s1.onDismiss(a1) { events.add("dismiss") }

        checkCond(s1.dispatchEvent(viewEvent(a1, "press")), "press dispatched")
        checkCond(s1.dispatchEvent(viewEvent(a1, "longPress")), "longPress dispatched")
        checkCond(s1.dispatchEvent(viewEvent(a1, "changeText", UiOps.Value.ofString("hello"))), "string changeText")
        val mapPayload = UiOps.Value.ofStringMap(listOf(UiOps.NamedValue("text", UiOps.Value.ofString("world"))))
        checkCond(s1.dispatchEvent(viewEvent(a1, "submit", mapPayload)), "string-map submit")
        val propPayload = UiOps.Value.ofPropMap(listOf(UiOps.Prop.named("value", UiOps.Value.ofString("propVal"))))
        checkCond(s1.dispatchEvent(viewEvent(a1, "changeText", propPayload)), "prop-map changeText")
        checkCond(s1.dispatchEvent(viewEvent(a1, "focus")), "focus dispatched")
        checkCond(s1.dispatchEvent(viewEvent(a1, "blur")), "blur dispatched")
        checkCond(s1.dispatchEvent(viewEvent(a1, "dismiss")), "dismiss dispatched")
        checkEq(
            events,
            listOf("press", "longPress", "change:hello", "submit:world", "change:propVal", "focus", "blur", "dismiss"),
            "all 7 typed event kinds + 3 payload shapes decoded",
        )

        s1.bindTheme(a1, "background", ColorToken.BG_BASE_PRIMARY)
        val slot = s1.allocMotionSlot()
        checkCond(slot in ManaMotion.SLOT_MIN..ManaMotion.SLOT_MAX, "allocated scoped motion slot")
        val disposed = AtomicBoolean(false)
        s1.addDisposer { disposed.set(true) }

        checkCond(
            s1.emitPatch { ops -> ops.updateView(a1, p("text" to UiOps.Value.ofString("live"))) },
            "emitPatch dispatches while mounted",
        )
        checkEq(dispatcher.batches.size, 1, "1 patch dispatched")

        checkCond(s1.unmount(), "first unmount succeeds")
        checkCond(!s1.isMounted, "scope marked unmounted")
        checkCond(disposed.get(), "disposer ran on unmount")
        checkEq(s1.viewCount(), 0, "0 views after unmount")
        checkEq(s1.handlerCount(), 0, "0 handlers after unmount")
        checkEq(s1.disposerCount(), 0, "0 disposers after unmount")
        checkEq(ThemeEngine.bindingCount("scope-1"), 0, "0 theme bindings after unmount")
        checkEq(ManaMotion.activeSlotCount("scope-1"), 0, "0 motion slots after unmount")

        val teardown = dispatcher.last()
        checkEq(teardown.size(), 2, "teardown has OP_DELETE + OP_MOTION(OP_FREE_SLOT)")
        checkEq(teardown.get(0).opcode(), UiOps.OP_DELETE, "op 0 is OP_DELETE")
        checkEq(teardown.get(1).opcode(), UiOps.OP_MOTION, "op 1 is OP_MOTION")
        val freeWords = teardown.get(1).motionWords()
        checkCond(
            freeWords != null && freeWords.size == 2 && freeWords[0] == ManaMotion.OP_FREE_SLOT && freeWords[1] == slot,
            "OP_FREE_SLOT freed exact slot",
        )

        checkCond(!s1.unmount(), "second unmount is idempotent no-op")
        checkCond(
            !s1.emitPatch { ops -> ops.updateView(a1, p("text" to UiOps.Value.ofString("noop"))) },
            "emitPatch after unmount returns false",
        )
        checkCond(!s1.dispatchEvent(viewEvent(a1, "press")), "dispatchEvent after unmount returns false")

        s2.unmountWithoutOps()
    }

    private fun testReactiveManaPageWidgetsAndLiveThemeReskin() {
        val dispatcher = RecordingDispatcher()
        ManaRuntime.patchDispatcher = dispatcher
        val router = ManaRouter(dispatcher)
        router.attachNavigator(5, intArrayOf(6, 997, 4244), 2)

        val toggleState = booleanState(false)
        val radioState = stringState("a")
        val segState = intState(0)
        val queryState = stringState("")
        val headerClicks = AtomicInteger(0)
        val lastSubmit = ArrayList<String>()

        val page =
            manaPage("Widgets", subtitle = "Subtitle", scopeId = "widgets-page") {
                headerAction(ManaIcons.SPARKLES, "Sparkle", refKey = "hdr") {
                    headerClicks.incrementAndGet()
                }
                section("Controls") {
                    toggleRow("Switch", state = toggleState, refKey = "sw")
                    valueRow("Switch Label", toggleState.map(scope) { if (it) "ON" else "OFF" }, refKey = "swVal")
                }
                section("Radios") {
                    radioGroup(
                        options =
                            listOf(
                                RadioOption("a", "Option A"),
                                RadioOption("b", "Option B"),
                                RadioOption("c", "Option C"),
                            ),
                        selected = radioState,
                        refKey = "rad",
                    )
                }
                section("Mixed") {
                    segmentedControl(listOf("One", "Two", "Three"), segState, refKey = "seg")
                    searchInput("Find...", queryState, refKey = "srch", onSubmit = { lastSubmit.add(it) })
                    text(queryState.map(scope) { "Q=$it" }, refKey = "qTxt")
                    badge(segState.map(scope) { "Tab $it" }, refKey = "tabBadge")
                }
            }

        val mounted = router.push(page)
        checkEq(dispatcher.batches.size, 1, "push emitted 1 atomic creation batch")
        checkCond(ThemeEngine.bindingCount("widgets-page") > 10, "page registered live ThemeEngine bindings")

        val hdrId = mounted.ref<Int>("hdr")!!
        checkCond(router.onViewEvent(viewEvent(hdrId, "press")) === Frame.DROP, "header action swallowed")
        checkEq(headerClicks.get(), 1, "header action invoked")

        val swHandle = mounted.ref<ManaUi.ToggleRowHandle>("sw")!!
        val swValHandle = mounted.ref<ManaUi.ValueRowHandle>("swVal")!!
        val beforeToggle = dispatcher.batches.size
        router.onViewEvent(viewEvent(swHandle.toggle.trackId, "press"))
        checkCond(toggleState.value, "toggle flipped true")
        val afterToggle = dispatcher.batches.subList(beforeToggle, dispatcher.batches.size)
        checkCond(
            afterToggle.any { b ->
                b.size() == 2 && b.get(0).opcode() == UiOps.OP_UPDATE && b.get(1).opcode() == UiOps.OP_MOTION
            },
            "toggle emitted exact 2-op native OP_MOTION spring batch",
        )
        checkCond(
            afterToggle.any { b ->
                b.ops().any { op ->
                    op.opcode() == UiOps.OP_UPDATE &&
                        op.viewId() == swValHandle.valueTextId &&
                        op.propString("text") == "ON"
                }
            },
            "derived valueRow patched in-place to ON",
        )

        val radios = mounted.ref<List<ManaUi.RadioRowHandle>>("rad")!!
        checkEq(radios.size, 3, "3 radio handles captured")
        val beforeRadio = dispatcher.batches.size
        router.onViewEvent(viewEvent(radios[2].rowId, "press"))
        checkEq(radioState.value, "c", "radio selected c")
        val radioBatch = dispatcher.batches[beforeRadio]
        val radioMotionOp = radioBatch.ops().firstOrNull { it.opcode() == UiOps.OP_MOTION }
        checkCond(radioMotionOp != null, "radio switch emitted OP_MOTION")
        val updatedRowIds =
            radioBatch
                .ops()
                .filter { it.opcode() == UiOps.OP_UPDATE }
                .map { it.viewId() }
                .toSet()
        checkCond(radios[0].rowId in updatedRowIds && radios[2].rowId in updatedRowIds, "old & new radios updated")
        checkCond(radios[1].rowId !in updatedRowIds, "untouched middle radio NOT updated")

        val seg = mounted.ref<ManaUi.SegmentedHandle>("seg")!!
        checkCond(seg.trackId >= ManaIds.SYNTHETIC_START && seg.pillId >= ManaIds.SYNTHETIC_START, "segmented track & sliding pill created")
        checkCond(seg.motionSlot in ManaMotion.SLOT_MIN..ManaMotion.SLOT_MAX, "segmented pill allocated motionSlot")
        val layoutPayload =
            UiOps.Value.ofPropMap(
                listOf(
                    UiOps.Prop.named("width", UiOps.Value.ofDouble(300.0)),
                    UiOps.Prop.named("height", UiOps.Value.ofDouble(36.0)),
                ),
            )
        val beforeLayout = dispatcher.batches.size
        checkCond(router.onViewEvent(viewEvent(seg.trackId, "layout", layoutPayload)) === Frame.DROP, "layout event consumed")
        val layoutBatch = dispatcher.batches[beforeLayout]
        val pillLayoutOp = layoutBatch.ops().first { it.opcode() == UiOps.OP_UPDATE && it.viewId() == seg.pillId }
        checkEq(pillLayoutOp.findProp("width")?.value?.asDouble(), 100.0, "pill width = 300 / 3 = 100dp")
        checkEq(pillLayoutOp.findProp("height")?.value?.asDouble(), 36.0, "pill height = 36dp")
        checkEq(pillLayoutOp.findProp("opacity")?.value?.asDouble(), 1.0, "pill visible after measurement")

        val beforeSeg = dispatcher.batches.size
        router.onViewEvent(viewEvent(seg.tabIds[2], "press"))
        checkEq(segState.value, 2, "segmented control selected index 2")
        val afterSeg = dispatcher.batches.subList(beforeSeg, dispatcher.batches.size)
        checkCond(
            afterSeg.any { b -> b.ops().any { it.opcode() == UiOps.OP_UPDATE && it.propString("text") == "Tab 2" } },
            "reactive badge patched to Tab 2",
        )
        val segMotion = afterSeg.flatMap { it.ops() }.firstOrNull { it.opcode() == UiOps.OP_MOTION }
        checkCond(segMotion != null, "segmented tab press emitted OP_MOTION spring slide")
        val segWords = segMotion!!.motionWords()
        checkEq(segWords[0], ManaMotion.OP_ANIMATE, "pill slide uses OP_ANIMATE")
        checkEq(segWords[1], seg.motionSlot, "pill slide targets pill motionSlot")
        checkEq(segWords[3], ManaMotion.ANIM_SPRING, "pill slide uses SEGMENTED_INDICATOR spring (no reducedMotion wrapper)")
        checkEq(ManaMotion.fromF32(segWords[4]), 200f, "pill slides to 2 * 100dp = 200dp")

        val srchFocusCheck = mounted.ref<ManaUi.SearchInputHandle>("srch")!!
        checkCond(srchFocusCheck.focusMotionSlot in ManaMotion.SLOT_MIN..ManaMotion.SLOT_MAX, "searchInput allocated focusMotionSlot")
        val beforeSearchFocus = dispatcher.batches.size
        checkCond(router.onViewEvent(viewEvent(srchFocusCheck.inputId, "focus")) === Frame.DROP, "search focus consumed")
        val searchFocusMotion = dispatcher.batches[beforeSearchFocus].ops().firstOrNull { it.opcode() == UiOps.OP_MOTION }
        checkCond(searchFocusMotion != null, "search focus emitted OP_MOTION spring")
        checkEq(searchFocusMotion!!.motionWords()[1], srchFocusCheck.focusMotionSlot, "search focus targets focusMotionSlot")
        checkCond(router.onViewEvent(viewEvent(srchFocusCheck.inputId, "blur")) === Frame.DROP, "search blur consumed")

        val srch = mounted.ref<ManaUi.SearchInputHandle>("srch")!!
        val qTxtId = mounted.ref<Int>("qTxt")!!
        val beforeType = dispatcher.batches.size
        router.onViewEvent(viewEvent(srch.inputId, "changeText", UiOps.Value.ofString("mana")))
        checkEq(queryState.value, "mana", "search state synced from changeText")
        val typeBatches = dispatcher.batches.subList(beforeType, dispatcher.batches.size)
        checkCond(
            typeBatches.none { b -> b.ops().any { it.viewId() == srch.inputId && it.findProp("value") != null } },
            "typing does not echo redundant value patch back to focused input",
        )
        checkCond(
            typeBatches.any { b -> b.ops().any { it.viewId() == qTxtId && it.propString("text") == "Q=mana" } },
            "derived text patched live to Q=mana",
        )

        router.onViewEvent(viewEvent(srch.inputId, "submit", UiOps.Value.ofString("mana")))
        checkEq(lastSubmit, listOf("mana"), "submit callback received query")

        val beforeClear = dispatcher.batches.size
        queryState.value = ""
        val clearBatches = dispatcher.batches.subList(beforeClear, dispatcher.batches.size)
        checkCond(
            clearBatches.any { b ->
                b.ops().any { it.viewId() == srch.inputId && it.propString("value") == "" }
            },
            "programmatic state write syncs input value back to UIManager",
        )

        val reskin = ThemeEngine.applyPreset(ThemePreset.LIGHT, applyReskin = false)
        checkCond(reskin.size() > 10, "live theme switch generates atomic OP_UPDATEs for mounted ManaPage")

        checkCond(router.pop(), "pop widgets page")
        checkEq(ThemeEngine.bindingCount("widgets-page"), 0, "0 theme bindings after pop")
        checkEq(ManaMotion.activeSlotCount("widgets-page"), 0, "0 motion slots after pop")
    }

    private fun testMultiLevelNavigationDepth3SwipeBackAndPopToRoot() {
        val dispatcher = RecordingDispatcher()
        val router = ManaRouter(dispatcher)
        router.attachNavigator(5, intArrayOf(6, 997, 4244), 2)

        val p1 =
            router.push(
                manaPage("Level 1", scopeId = "p1") {
                    section("S1") { toggleRow("T1", checked = false) {} }
                },
            )
        val p2 =
            router.push(
                manaPage("Level 2", scopeId = "p2") {
                    section("S2") { toggleRow("T2", checked = true) {} }
                },
            )
        val p3 =
            router.push(
                manaPage("Level 3", scopeId = "p3") {
                    section("S3") { toggleRow("T3", checked = false) {} }
                },
            )

        checkEq(router.depth, 3, "stack depth is 3")
        val push3 = dispatcher.last()
        val navKids3 = push3.ops().first { it.opcode() == UiOps.OP_CHILDREN && it.viewId() == 5 }.childIds()
        val navIdx3 =
            push3
                .ops()
                .first { it.opcode() == UiOps.OP_UPDATE && it.viewId() == 5 }
                .findProp("index")
                .value
                .asInt()
        checkCond(
            Arrays.equals(navKids3, intArrayOf(6, 997, 4244, p1.rootId, p2.rootId, p3.rootId)),
            "navigator children contain all 3 stacked pages",
        )
        checkEq(navIdx3, 5, "navigator index advanced to 5")

        val swipe = viewEvent(5, "navigate")
        checkCond(router.onViewEvent(swipe) === Frame.DROP, "swipe-back at depth 3 swallowed")
        checkEq(router.depth, 2, "stack depth reduced to 2")
        checkEq(ThemeEngine.bindingCount("p3"), 0, "p3 theme bindings freed")
        checkEq(ManaMotion.activeSlotCount("p3"), 0, "p3 motion slots freed")
        checkCond(ThemeEngine.bindingCount("p2") > 0 && ManaMotion.activeSlotCount("p2") > 0, "p2 still intact")

        val pop3 = dispatcher.last()
        checkEq(
            pop3
                .get(0)
                .findProp("index")
                .value
                .asInt(),
            4,
            "pop 3 -> 2 restores index=4",
        )
        checkCond(
            Arrays.equals(pop3.get(2).childIds(), intArrayOf(6, 997, 4244, p1.rootId, p2.rootId)),
            "pop 3 -> 2 restores [6, 997, 4244, p1, p2]",
        )

        router.push(
            manaPage("Level 3b", scopeId = "p3b") {
                section("S3b") { toggleRow("T3b", checked = true) {} }
            },
        )
        checkEq(router.depth, 3, "depth back to 3")
        val slotsBeforeRoot =
            ManaMotion.activeSlotCount("p1") +
                ManaMotion.activeSlotCount("p2") +
                ManaMotion.activeSlotCount("p3b")
        checkEq(slotsBeforeRoot, 3, "3 motion slots active across 3 pages")

        val poppedCount = router.popToRoot()
        checkEq(poppedCount, 3, "popToRoot popped all 3 pages")
        checkCond(router.isEmpty, "router stack empty after popToRoot")
        checkEq(
            ThemeEngine.bindingCount("p1") + ThemeEngine.bindingCount("p2") + ThemeEngine.bindingCount("p3b"),
            0,
            "all page theme bindings freed",
        )
        checkEq(ManaMotion.slots.activeSlotCount(), 0, "all page motion slots freed")

        val rootBatch = dispatcher.last()
        checkEq(rootBatch.size(), 4, "atomic 4-op popToRoot batch")
        checkEq(
            rootBatch
                .get(0)
                .findProp("index")
                .value
                .asInt(),
            2,
            "restores base index=2",
        )
        checkCond(
            Arrays.equals(rootBatch.get(2).childIds(), intArrayOf(6, 997, 4244)),
            "restores exact base navigator children",
        )
        val freed = rootBatch.get(3).motionWords()
        checkEq(freed.size, 6, "3 OP_FREE_SLOT pairs (6 words) emitted in one batch")
    }

    private fun testOverlaysStackingStrictBackPriorityScrimAndToastAutoDismiss() {
        val dispatcher = RecordingDispatcher()
        val router = ManaRouter(dispatcher)
        router.attachNavigator(5, intArrayOf(6, 997, 4244), 2)

        val pendingTimers = ArrayList<Pair<Long, Runnable>>()
        val cancelledTimers = AtomicInteger(0)
        ManaToastScheduler.scheduler =
            ToastDelayScheduler { delayMs, action ->
                val entry = Pair(delayMs, action)
                pendingTimers.add(entry)
                (
                    {
                        if (pendingTimers.remove(entry)) {
                            cancelledTimers.incrementAndGet()
                        }
                    }
                )
            }

        val page =
            router.push(
                manaPage("Host Page", scopeId = "overlay-host") {
                    section("Host") { valueRow("Status", "Ready") }
                },
            )
        val baseScreenBoxChildren =
            dispatcher.batches[0]
                .ops()
                .last { it.opcode() == UiOps.OP_CHILDREN && it.viewId() == page.screenBoxId }
                .childIds()!!

        val sheetDismissed = AtomicBoolean(false)
        val dialogDismissed = AtomicBoolean(false)
        val sheet =
            router.presentSheet(
                manaSheet("My Sheet", scopeId = "ov-sheet", onDismiss = { sheetDismissed.set(true) }) {
                    toggleRow("Sheet Toggle", checked = false) {}
                },
            )
        val dialog =
            router.showDialog(
                confirmDialog(
                    title = "Confirm?",
                    message = "Stacked above sheet",
                    onCancel = { dialogDismissed.set(true) },
                    onConfirm = {},
                ),
            )
        val toast = router.showToast("Saved!", icon = ManaIcons.CHECKMARK_LARGE, tone = ManaToastTone.POSITIVE)

        checkCond(router.currentSheet === sheet, "sheet active")
        checkCond(router.currentDialog === dialog, "dialog active")
        checkCond(router.currentToast === toast, "toast active")
        checkEq(pendingTimers.size, 1, "1 toast auto-dismiss timer scheduled")

        val toastMountBatch = dispatcher.last()
        val hostKids =
            toastMountBatch
                .ops()
                .last { it.opcode() == UiOps.OP_CHILDREN && it.viewId() == page.screenBoxId }
                .childIds()
        val expectedKids = IntArray(baseScreenBoxChildren.size + 3)
        System.arraycopy(baseScreenBoxChildren, 0, expectedKids, 0, baseScreenBoxChildren.size)
        expectedKids[baseScreenBoxChildren.size] = sheet.rootId
        expectedKids[baseScreenBoxChildren.size + 1] = dialog.rootId
        expectedKids[baseScreenBoxChildren.size + 2] = toast.rootId
        checkCond(Arrays.equals(hostKids, expectedKids), "overlay Z-order is base -> sheet -> dialog -> toast")

        val sheetBatch = dispatcher.batches[1]
        val sheetCreate = sheetBatch.ops().first { it.opcode() == UiOps.OP_CREATE && it.viewId() == sheet.rootId }
        checkEq(sheetCreate.viewType(), "sheet", "ManaSheet emits native type=\"sheet\"")
        checkEq(sheetCreate.findProp("mana")?.value?.asBoolean(), true, "sheet sets mana=true")
        checkEq(sheetCreate.findProp("radiusTopLeft")?.value?.asInt(), 24, "sheet radiusTopLeft=24")
        checkEq(sheetCreate.findProp("radiusTopRight")?.value?.asInt(), 24, "sheet radiusTopRight=24")
        checkEq(sheetCreate.findProp("dismissible")?.value?.asBoolean(), true, "sheet dismissible=true")
        checkCond(sheetCreate.findProp("handleColor") != null, "sheet sets handleColor")

        val dialogBatch = dispatcher.batches[2]
        val modalCreate = dialogBatch.ops().first { it.opcode() == UiOps.OP_CREATE && it.viewId() == dialog.rootId }
        checkEq(modalCreate.viewType(), "modal", "ManaDialog emits native type=\"modal\"")
        checkEq(
            modalCreate.findProp("fullscreen")?.value?.asBoolean(),
            false,
            "modal sets fullscreen=false for centered full-window Dialog",
        )
        checkEq(modalCreate.findProp("dismissible")?.value?.asBoolean(), true, "modal sets dismissible=true")

        val timerAction = pendingTimers.removeAt(0).second
        timerAction.run()
        checkCond(router.currentToast == null, "toast auto-dismissed when timer fired")

        checkCond(router.onBackPress(), "Back #1 consumed by Dialog")
        checkCond(router.currentDialog == null, "Dialog dismissed first")
        checkCond(dialogDismissed.get(), "Dialog onDismiss fired")
        val dlgDeleteOp = dispatcher.last().ops().first { it.opcode() == UiOps.OP_DELETE }
        checkEq(dlgDeleteOp.deleteIds()[0], dialog.rootId, "dialog modalId MUST be ids[0] in OP_DELETE for native j42 close animation")
        checkCond(router.currentSheet != null, "Sheet still open after Dialog dismissed")

        checkCond(router.onBackPress(), "Back #2 consumed by Sheet")
        checkCond(router.currentSheet == null, "Sheet dismissed second")
        checkCond(sheetDismissed.get(), "Sheet onDismiss fired")
        val sheetDeleteOp = dispatcher.last().ops().first { it.opcode() == UiOps.OP_DELETE }
        checkEq(sheetDeleteOp.deleteIds()[0], sheet.rootId, "sheetId MUST be ids[0] in OP_DELETE for native dc3 close animation")
        checkEq(ManaMotion.activeSlotCount("ov-sheet"), 0, "Sheet motion slots freed")
        checkEq(router.depth, 1, "Host page still open after Sheet dismissed")

        val lockedDlg =
            router.showDialog(
                manaDialog("Locked", dismissible = false) {
                    primaryAction("Unlock", refKey = "unlock") {}
                },
            )
        checkCond(router.onBackPress(), "Back consumed by non-dismissible dialog")
        checkCond(router.currentDialog === lockedDlg, "non-dismissible dialog stayed open on Back")
        router.onViewEvent(viewEvent(lockedDlg.rootId, "dismiss"))
        checkCond(router.currentDialog === lockedDlg, "non-dismissible dialog stayed open on dismiss event")

        val unlockBtnId = lockedDlg.ref<Int>("unlock")!!
        router.onViewEvent(viewEvent(unlockBtnId, "press"))
        checkCond(router.currentDialog == null, "primary action dismissed locked dialog")

        val s2 = router.presentSheet(manaSheet("Swipe Down") { text("Hello") })
        router.onViewEvent(viewEvent(s2.rootId, "dismiss"))
        checkCond(router.currentSheet == null, "native dismiss event dismissed sheet")

        val d2 = router.showDialog(manaDialog("ExitEnd Test") { secondaryAction("OK") {} })
        router.onViewEvent(viewEvent(d2.rootId, "exitEnd"))
        checkCond(router.currentDialog == null, "native exitEnd event cleaned up dialog")

        router.presentSheet(
            manaSheet("Active During Teardown", scopeId = "ov-teardown") {
                toggleRow("T", checked = false) {}
            },
        )
        router.showToast("Toast During Teardown")
        checkEq(pendingTimers.size, 1, "toast timer registered before teardown")
        val discordPop = UiOps.create().setChildren(5, 6, 997).ops()
        router.observeDiscordBatch(discordPop)
        checkCond(router.isEmpty, "router cleared on Discord navigator teardown")
        checkCond(router.currentSheet == null && router.currentToast == null, "overlays cleared on teardown")
        checkEq(cancelledTimers.get(), 1, "toast timer cancelled on teardown")
        checkEq(ThemeEngine.bindingCount("overlay-host"), 0, "0 host theme bindings after teardown")
        checkEq(ThemeEngine.bindingCount("ov-teardown"), 0, "0 sheet theme bindings after teardown")
        checkEq(ManaMotion.slots.activeSlotCount(), 0, "0 active motion slots after teardown")
        checkCond(!router.onBackPress(), "Back returns false when router is empty")
    }

    private fun testCinderSettingsToManaShowcaseEndToEnd() {
        val host = installSyncHost()
        ManaToastScheduler.scheduler = ToastDelayScheduler { _, _ -> ({}) }

        val cs = CinderSettings()
        cs.onLoad(null)
        Loader.setPluginsForTests(cs)

        val overview =
            UiOps
                .create()
                .updateView(5, p("index" to UiOps.Value.ofInt(2)))
                .createView(4244, "view", p("direction" to UiOps.Value.ofString("column")))
                .createView(
                    4245,
                    "view",
                    p(
                        "direction" to UiOps.Value.ofString("column"),
                        "background" to colorVal(0xFF242429L),
                        "accessibilityId" to UiOps.Value.ofString("settings-phone"),
                    ),
                ).createView(4250, "scroll", p("width" to UiOps.Value.ofString("fill")))
                .createView(4251, "view", p("direction" to UiOps.Value.ofString("column"), "gap" to UiOps.Value.ofInt(24)))
                .createView(4252, "view", p("accessibilityId" to UiOps.Value.ofString("settings.row.account")))
                .setChildren(4251, 4252)
                .setChildren(4250, 4251)
                .setChildren(4245, 4250)
                .setChildren(4244, 4245)
                .setChildren(5, 6, 997, 4244)
                .toBytes()
        val modifiedOverview = Loader.onOps(overview, overview.size)
        val parsedOverview = UiOps.parse(modifiedOverview)!!
        val cinderRowId =
            parsedOverview
                .ops()
                .first { it.opcode() == UiOps.OP_CREATE && it.propString("accessibilityId") == "settings.row.cinder" }
                .viewId()

        checkCond(
            Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(cinderRowId, UiOps.propId("press"), null)) == null,
            "Cinder row press handled",
        )
        val router = cs.getRouter()
        checkEq(router.depth, 1, "Cinder root page at depth 1")

        val showcaseRowId = router.topPage!!.ref<ManaUi.NavRowHandle>("cinder.row.showcase")!!.rowId
        checkCond(showcaseRowId >= ManaIds.SYNTHETIC_START, "Mana Showcase navRow present on Cinder root page")
        checkCond(
            Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(showcaseRowId, UiOps.propId("press"), null)) == null,
            "Mana Showcase press handled",
        )
        checkEq(router.depth, 2, "Mana Showcase pushed at depth 2")

        val showcase = router.topPage!!
        checkEq(showcase.page.title, "Mana Showcase", "top page is Mana Showcase")

        val sparkleId = showcase.ref<Int>(ManaShowcasePage.REF_HEADER_SPARKLE)!!
        Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(sparkleId, UiOps.propId("press"), null))
        checkCond(router.currentToast != null, "header sparkle opened toast")

        val turboToggle = showcase.ref<ManaUi.ToggleRowHandle>(ManaShowcasePage.REF_TURBO_TOGGLE)!!
        Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(turboToggle.toggle.trackId, UiOps.propId("press"), null))

        val radios = showcase.ref<List<ManaUi.RadioRowHandle>>(ManaShowcasePage.REF_RADIO_PROFILE)!!
        Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(radios[2].rowId, UiOps.propId("press"), null))

        val seg = showcase.ref<ManaUi.SegmentedHandle>(ManaShowcasePage.REF_SEGMENT_DENSITY)!!
        Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(seg.tabIds[2], UiOps.propId("press"), null))

        val search = showcase.ref<ManaUi.SearchInputHandle>(ManaShowcasePage.REF_SEARCH_INPUT)!!
        Loader.onDispatch(
            UiOps.ViewEvent.buildViewEvent(search.inputId, UiOps.propId("changeText"), UiOps.Value.ofString("cinder")),
        )
        val clearBtn = showcase.ref<Int>(ManaShowcasePage.REF_CLEAR_SEARCH_BTN)!!
        Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(clearBtn, UiOps.propId("press"), null))

        val incBtn = showcase.ref<Int>(ManaShowcasePage.REF_INC_BTN)!!
        Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(incBtn, UiOps.propId("press"), null))
        Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(incBtn, UiOps.propId("press"), null))

        val sheetActionId = showcase.ref<Int>(ManaShowcasePage.REF_OPEN_SHEET)!!
        Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(sheetActionId, UiOps.propId("press"), null))
        checkCond(router.currentSheet != null, "Showcase sheet opened")
        val stackDlgBtnId = router.currentSheet!!.ref<Int>(ManaShowcasePage.REF_SHEET_DIALOG_BTN)!!
        Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(stackDlgBtnId, UiOps.propId("press"), null))
        checkCond(router.currentDialog != null, "Dialog stacked on top of Showcase sheet")

        val backEvent = UiOps.ViewEvent.buildBackEvent()
        checkCond(Loader.onDispatch(backEvent) == null, "Back dismisses stacked dialog")
        checkCond(router.currentDialog == null && router.currentSheet != null, "Sheet remains after dialog dismissed")
        checkCond(Loader.onDispatch(backEvent) == null, "Back dismisses Showcase sheet")
        checkCond(router.currentSheet == null, "Sheet dismissed")

        val nestedNav = showcase.ref<ManaUi.NavRowHandle>(ManaShowcasePage.REF_NESTED_NAV)!!
        Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(nestedNav.rowId, UiOps.propId("press"), null))
        checkEq(router.depth, 3, "Nested Detail pushed at depth 3")
        val nested = router.topPage!!
        checkEq(nested.page.title, "Nested Detail", "top page is Nested Detail")

        val nestedSwitch = nested.ref<ManaUi.ToggleRowHandle>(ManaShowcasePage.REF_NESTED_TOGGLE)!!
        Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(nestedSwitch.rowId, UiOps.propId("press"), null))
        val popRootBtn = nested.ref<Int>(ManaShowcasePage.REF_NESTED_POP_ROOT_BTN)!!
        Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(popRootBtn, UiOps.propId("press"), null))

        checkCond(router.isEmpty, "popToRoot returned cleanly to Settings Overview")
        checkCond(
            router.currentSheet == null && router.currentDialog == null && router.currentToast == null,
            "all overlays cleaned up",
        )
        checkEq(ThemeEngine.bindingCount(CinderSettings.THEME_SCOPE_PAGE), 0, "0 Cinder page bindings leaked")
        checkEq(
            ManaMotion.slots.activeSlotCount(),
            0,
            "0 ManaMotion slots leaked across entire 3-level Showcase session",
        )
        checkCond(
            ThemeEngine.bindingCount(CinderSettings.THEME_SCOPE_OVERVIEW) > 0,
            "Settings Overview bindings preserved",
        )
        checkCond(host.appliedSynthetic.size >= 15, "all synthetic batches applied and parsed cleanly")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        runTest("manaStateReactivityMapCombineAndAutoDispose", ::testManaStateReactivityMapCombineAndAutoDispose)
        runTest("manaScopeIdsTypedEventsLivePatchesAndTeardown", ::testManaScopeIdsTypedEventsLivePatchesAndTeardown)
        runTest("reactiveManaPageWidgetsAndLiveThemeReskin", ::testReactiveManaPageWidgetsAndLiveThemeReskin)
        runTest("multiLevelNavigationDepth3SwipeBackAndPopToRoot", ::testMultiLevelNavigationDepth3SwipeBackAndPopToRoot)
        runTest(
            "overlaysStackingStrictBackPriorityScrimAndToastAutoDismiss",
            ::testOverlaysStackingStrictBackPriorityScrimAndToastAutoDismiss,
        )
        runTest("cinderSettingsToManaShowcaseEndToEnd", ::testCinderSettingsToManaShowcaseEndToEnd)
        println("ALL $passed ManaSdkCoreTest TESTS PASSED")
    }
}
