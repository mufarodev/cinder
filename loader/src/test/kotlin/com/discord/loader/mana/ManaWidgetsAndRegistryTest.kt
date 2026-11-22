package com.discord.loader.mana

import com.discord.loader.CinderSettings
import com.discord.loader.Frame
import com.discord.loader.HostInvoker
import com.discord.loader.Loader
import com.discord.loader.ManaUi
import com.discord.loader.ModPlugin
import com.discord.loader.UiOps
import com.discord.loader.plugins.CinderDiagnosticsPlugin
import java.util.Arrays
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object ManaWidgetsAndRegistryTest {
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
                "change",
                "changeText",
                "clip",
                "color",
                "direction",
                "dismiss",
                "dismissLabel",
                "dismissible",
                "enabled",
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
                "maxTrackTint",
                "minHeight",
                "minTrackTint",
                "minWidth",
                "multiline",
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
                "stepHaptic",
                "steps",
                "submit",
                "text",
                "textAlign",
                "thumbSize",
                "thumbTint",
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

    private class RecordingHost : HostInvoker {
        val batches = ArrayList<UiOps>()

        override fun invokeOrig(frame: ByteArray): ByteArray? = null

        override fun applyOps(
            ops: ByteArray,
            length: Int,
        ) {
            val hooked = Loader.onOps(this, ops, length)
            val parsed = UiOps.parse(hooked, if (hooked === ops) length else hooked.size)
            checkCond(parsed != null, "synthetic batch must parse cleanly")
            batches.add(parsed!!)
        }
    }

    private fun installSyncHost(): RecordingHost {
        val host = RecordingHost()
        Loader.registerHost(host)
        Loader.setSyntheticExecutorForTests(Executor { cmd -> cmd.run() })
        return host
    }

    private fun p(vararg kv: Any): List<UiOps.Prop> {
        val out = ArrayList<UiOps.Prop>(kv.size / 2)
        var i = 0
        while (i + 1 < kv.size) {
            out.add(UiOps.Prop.named(kv[i] as String, kv[i + 1] as UiOps.Value))
            i += 2
        }
        return out
    }

    private fun strArr(vararg items: String): UiOps.Value = UiOps.Value.ofArray(items.map { UiOps.Value.ofString(it) })

    private fun colorVal(argb: Long): UiOps.Value {
        val u = argb and 0xFFFFFFFFL
        return if (u > Int.MAX_VALUE) UiOps.Value.ofLong(u) else UiOps.Value.ofInt(u.toInt())
    }

    private fun pressEvent(viewId: Int): UiOps.ViewEvent =
        UiOps.ViewEvent.parse(UiOps.ViewEvent.buildViewEvent(viewId, UiOps.propId("press"), null))!!

    private fun changeTextBytes(
        viewId: Int,
        text: String,
    ): ByteArray {
        val payload =
            UiOps.Value.ofPropMap(
                listOf(UiOps.Prop.named("text", UiOps.Value.ofString(text))),
            )
        return UiOps.ViewEvent.buildViewEvent(viewId, UiOps.propId("changeText"), payload)
    }

    private fun changeTextEvent(
        viewId: Int,
        text: String,
    ): UiOps.ViewEvent = UiOps.ViewEvent.parse(changeTextBytes(viewId, text))!!

    private fun sliderChangeEvent(
        viewId: Int,
        stepIndex: Int,
    ): UiOps.ViewEvent {
        val payload =
            UiOps.Value.ofPropMap(
                listOf(UiOps.Prop.named("value", UiOps.Value.ofInt(stepIndex))),
            )
        return UiOps.ViewEvent.parse(
            UiOps.ViewEvent.buildViewEvent(viewId, UiOps.propId("change"), payload),
        )!!
    }

    private fun buildSettingsOverviewWithSearch(
        navId: Int = 5,
        phoneId: Int = 9231,
        scrollColId: Int = 9238,
        searchWrapId: Int = 9239,
        searchInputId: Int = 9245,
        catWrapId: Int = 9250,
    ): ByteArray {
        val modalWrap = phoneId - 1
        val inner1 = phoneId + 1
        val inner2 = phoneId + 2
        val scrollId = phoneId + 6
        val acctRowId = catWrapId + 1
        return UiOps
            .create()
            .updateView(navId, p("index", UiOps.Value.ofInt(2)))
            .createView(modalWrap, "view", p("presentation", UiOps.Value.ofString("modal")))
            .createView(
                phoneId,
                "view",
                p(
                    "direction",
                    UiOps.Value.ofString("column"),
                    "background",
                    colorVal(0xFF1C1D22L),
                    "accessibilityId",
                    UiOps.Value.ofString("settings-phone"),
                ),
            ).createView(inner1, "view", p("direction", UiOps.Value.ofString("column")))
            .createView(
                inner2,
                "view",
                p(
                    "direction",
                    UiOps.Value.ofString("column"),
                    "background",
                    colorVal(0xFF1C1D22L),
                ),
            ).createView(scrollId, "scroll", p("width", UiOps.Value.ofString("fill")))
            .createView(
                scrollColId,
                "view",
                p(
                    "direction",
                    UiOps.Value.ofString("column"),
                    "gap",
                    UiOps.Value.ofInt(24),
                ),
            ).createView(searchWrapId, "view", p("direction", UiOps.Value.ofString("row")))
            .createView(
                searchInputId,
                "input",
                p(
                    "accessibilityId",
                    UiOps.Value.ofString(ManaSettingsSearch.SEARCH_INPUT_ACCESSIBILITY_ID),
                    "placeholder",
                    UiOps.Value.ofString("Search"),
                    "on",
                    strArr("changeText", "submit", "focus", "blur"),
                ),
            ).createView(catWrapId, "view", p("direction", UiOps.Value.ofString("column")))
            .createView(
                acctRowId,
                "view",
                p(
                    "accessibilityId",
                    UiOps.Value.ofString("settings.row.account"),
                    "background",
                    colorVal(0x1497979FL),
                    "activeBackground",
                    colorVal(0x12FFFFFFL),
                    "on",
                    strArr("press"),
                ),
            ).setChildren(searchWrapId, searchInputId)
            .setChildren(catWrapId, acctRowId)
            .setChildren(scrollColId, searchWrapId, catWrapId)
            .setChildren(scrollId, scrollColId)
            .setChildren(inner2, scrollId)
            .setChildren(inner1, inner2)
            .setChildren(phoneId, inner1)
            .setChildren(modalWrap, phoneId)
            .setChildren(navId, 6, 997, modalWrap)
            .toBytes()
    }

    private fun runTest(
        name: String,
        block: () -> Unit,
    ) {
        Loader.resetForTests()
        ThemeEngine.resetForTests()
        ManaRuntime.resetForTests()
        ManaMotion.resetForTests()
        ManaIds.resetForTests()
        ManaSettingsSearch.clearEntriesForTests()
        registerAllProps()
        block()
        passed++
        println("PASS $name")
    }

    private fun testCheckboxRowNativeMotionAndThemeReskin() {
        val host = installSyncHost()
        val router = ManaRouter()
        router.attachNavigator(5, intArrayOf(6, 997, 4244), 2)

        val callbackVal = AtomicBoolean(false)
        lateinit var checkedState: ManaState<Boolean>

        val page =
            manaPage(title = "Checkbox Test", scopeId = "test-checkbox") {
                checkedState = booleanState(false)
                section(title = "Choices") {
                    checkboxRow(
                        title = "Enable Haptics",
                        subtitle = "Native 120Hz spring checkmark",
                        state = checkedState,
                        icon = ManaIcons.SPARKLES,
                        accessibilityId = "test.cb.haptics",
                        refKey = "cb",
                    ) { next ->
                        callbackVal.set(next)
                    }
                }
            }

        val mounted = router.push(page)
        val handle = mounted.ref<CheckboxRowHandle>("cb")!!
        checkCond(handle.motionSlot in ManaMotion.SLOT_MIN..ManaMotion.SLOT_MAX, "checkbox allocated safe motion slot")

        val mountBatch = host.batches.last()
        val boxCreate = mountBatch.ops().first { it.opcode() == UiOps.OP_CREATE && it.viewId() == handle.boxId }
        checkEq(boxCreate.findProp("width")?.value?.asInt(), 24, "checkbox box width=24")
        checkEq(boxCreate.findProp("height")?.value?.asInt(), 24, "checkbox box height=24")
        checkEq(boxCreate.findProp("radius")?.value?.asInt(), 6, "checkbox box radius=6")
        checkCond(boxCreate.findProp("worklet") != null, "checkbox box has builtin::sampled worklet")

        val checkImgCreate = mountBatch.ops().first { it.opcode() == UiOps.OP_CREATE && it.viewId() == handle.checkIconId }
        checkEq(checkImgCreate.propString("uri"), ManaIcons.CHECK, "checkbox tick uses ManaIcons.CHECK")

        val checkWrapCreate = mountBatch.ops().first { it.opcode() == UiOps.OP_CREATE && it.viewId() == handle.checkWrapId }
        checkCond(checkWrapCreate.findProp("worklet") != null, "check wrapper has opacity+scale worklet")

        val beforeCount = host.batches.size
        checkCond(router.onViewEvent(pressEvent(handle.rowId)) === Frame.DROP, "checkbox press consumed")
        checkEq(checkedState.value, true, "checkbox state toggled to true")
        checkEq(callbackVal.get(), true, "checkbox callback notified")
        checkCond(host.batches.size > beforeCount, "toggle emitted live patch")

        val toggleBatch = host.batches.last()
        val motionOp = toggleBatch.ops().firstOrNull { it.opcode() == UiOps.OP_MOTION }
        checkCond(motionOp != null, "checkbox toggle emitted OP_MOTION spring")
        val words = motionOp!!.motionWords()
        checkCond(
            words.contains(ManaMotion.OP_ANIMATE) && words.contains(ManaMotion.ANIM_SPRING),
            "checkbox uses native OP_ANIMATE + ANIM_SPRING",
        )

        val rowUpdate = toggleBatch.ops().firstOrNull { it.opcode() == UiOps.OP_UPDATE && it.viewId() == handle.rowId }
        checkEq(rowUpdate?.findProp("accessibilityChecked")?.value?.asBoolean(), true, "accessibilityChecked updated")

        val beforeReskin = host.batches.size
        ThemeEngine.applyPreset(ThemePreset.LIGHT)
        checkCond(host.batches.size > beforeReskin, "theme change emitted reskin batch")

        checkCond(router.pop(), "page popped")
        checkEq(ManaMotion.activeSlotCount("test-checkbox"), 0, "checkbox motion slot freed on pop")
        checkEq(ThemeEngine.bindingCount("test-checkbox"), 0, "checkbox theme bindings cleared on pop")
    }

    private fun testSliderRowNativeViewWireSeekBarAndReactivity() {
        val host = installSyncHost()
        val router = ManaRouter()
        router.attachNavigator(5, intArrayOf(6, 997, 4244), 2)

        val callbackVal = AtomicInteger(-1)
        lateinit var sliderState: ManaState<Int>

        val page =
            manaPage(title = "Slider Test", scopeId = "test-slider") {
                sliderState = intState(250)
                section(title = "Timing") {
                    sliderRow(
                        title = "Throttle Delay",
                        range = 0..1000,
                        step = 50,
                        state = sliderState,
                        valueFormatter = { v -> "$v ms" },
                        subtitle = "Native SeekBar",
                        accessibilityId = "test.slider.delay",
                        refKey = "slider",
                    ) { next ->
                        callbackVal.set(next)
                    }
                }
            }

        val mounted = router.push(page)
        val handle = mounted.ref<SliderRowHandle>("slider")!!
        val mountBatch = host.batches.last()

        val sliderOp = mountBatch.ops().first { it.opcode() == UiOps.OP_CREATE && it.viewId() == handle.sliderId }
        checkEq(sliderOp.viewType(), "slider", "sliderRow emits verified native type=\"slider\"")
        checkEq(sliderOp.findProp("steps")?.value?.asInt(), 20, "steps = (1000 - 0) / 50 = 20")
        checkEq(sliderOp.findProp("value")?.value?.asInt(), 5, "initial stepIndex = 250 / 50 = 5")
        checkCond(sliderOp.findProp("minTrackTint") != null, "minTrackTint set")
        checkCond(sliderOp.findProp("maxTrackTint") != null, "maxTrackTint set")
        checkCond(sliderOp.findProp("thumbTint") != null, "thumbTint set")
        checkEq(sliderOp.propString("stepHaptic"), "selection", "stepHaptic set to selection")

        val valueTextOp = mountBatch.ops().first { it.opcode() == UiOps.OP_CREATE && it.viewId() == handle.valueTextId }
        checkEq(valueTextOp.propString("text"), "250 ms", "initial formatted slider readout")

        checkCond(router.onViewEvent(sliderChangeEvent(handle.sliderId, 12)) === Frame.DROP, "slider change consumed")
        checkEq(sliderState.value, 600, "slider state updated from native change event")
        checkEq(callbackVal.get(), 600, "slider onChange invoked")

        val patchAfterDrag = host.batches.last()
        val textPatch = patchAfterDrag.ops().firstOrNull { it.opcode() == UiOps.OP_UPDATE && it.viewId() == handle.valueTextId }
        checkEq(textPatch?.propString("text"), "600 ms", "readout updated to 600 ms")

        sliderState.value = 9999
        val clampedPatch = host.batches.last()
        val sliderPatch = clampedPatch.ops().first { it.opcode() == UiOps.OP_UPDATE && it.viewId() == handle.sliderId }
        checkEq(sliderPatch.findProp("value")?.value?.asInt(), 20, "clamped to max step 20")
        val clampedText = clampedPatch.ops().first { it.opcode() == UiOps.OP_UPDATE && it.viewId() == handle.valueTextId }
        checkEq(clampedText.propString("text"), "1000 ms", "clamped readout 1000 ms")

        val beforeTheme = host.batches.size
        ThemeEngine.applyPreset(ThemePreset.LIGHT)
        checkCond(host.batches.size > beforeTheme, "slider reskinned on LIGHT theme change")
        val reskin = host.batches.last()
        checkCond(
            reskin.ops().any { it.opcode() == UiOps.OP_UPDATE && it.viewId() == handle.sliderId },
            "slider view updated during live ThemeEngine reskin",
        )

        router.pop()
        checkEq(ThemeEngine.bindingCount("test-slider"), 0, "slider theme bindings cleared")
    }

    private fun testTextInputTwoWayBindingValidationErrorClearAndMultiline() {
        val host = installSyncHost()
        val router = ManaRouter()
        router.attachNavigator(5, intArrayOf(6, 997, 4244), 2)

        lateinit var textState: ManaState<String>
        lateinit var errorState: ManaState<String?>
        val lastChanged = AtomicReference<String>("")

        val page =
            manaPage(title = "Input Test", scopeId = "test-input") {
                textState = stringState("Hello")
                errorState = manaState<String?>(null)
                section(title = "Form") {
                    textInput(
                        label = "Bio",
                        state = textState,
                        placeholder = "Tell us about yourself",
                        helperText = "At least 3 characters",
                        errorState = errorState,
                        clearable = true,
                        multiline = true,
                        accessibilityId = "test.input.bio",
                        refKey = "bio",
                    ) { next ->
                        lastChanged.set(next)
                        errorState.value = if (next.length < 3) "Too short" else null
                    }
                }
            }

        val mounted = router.push(page)
        val handle = mounted.ref<TextInputHandle>("bio")!!
        val mountBatch = host.batches.last()

        val inputOp = mountBatch.ops().first { it.opcode() == UiOps.OP_CREATE && it.viewId() == handle.inputId }
        checkEq(inputOp.viewType(), "input", "textInput creates native type=\"input\"")
        checkEq(inputOp.propString("value"), "Hello", "initial textInput value")
        checkEq(inputOp.findProp("multiline")?.value?.asBoolean(), true, "multiline prop set")
        checkCond(handle.clearBtnId >= 0, "clear button emitted when clearable=true")

        checkCond(router.onViewEvent(changeTextEvent(handle.inputId, "Hi")) === Frame.DROP, "changeText consumed")
        checkEq(textState.value, "Hi", "textState updated two-way")
        checkEq(lastChanged.get(), "Hi", "onChange received updated text")
        checkEq(errorState.value, "Too short", "errorState populated")

        val errBatch = host.batches.last()
        val boxErrPatch = errBatch.ops().firstOrNull { it.opcode() == UiOps.OP_UPDATE && it.viewId() == handle.boxId }
        val msgErrPatch = errBatch.ops().firstOrNull { it.opcode() == UiOps.OP_UPDATE && it.viewId() == handle.messageTextId }
        checkEq(
            boxErrPatch?.findProp("borderColor")?.value?.asLong(),
            ThemeEngine.currentPalette()[ColorToken.DANGER],
            "border turned DANGER on error",
        )
        checkEq(msgErrPatch?.propString("text"), "Too short", "error text shown")

        checkCond(router.onViewEvent(pressEvent(handle.clearBtnId)) === Frame.DROP, "clear button press consumed")
        checkEq(textState.value, "", "textState cleared to empty string")
        checkCond(
            host.batches.any { b ->
                b.ops().any { it.opcode() == UiOps.OP_UPDATE && it.viewId() == handle.inputId && it.propString("value") == "" }
            },
            "input value patched to empty string",
        )

        router.onViewEvent(changeTextEvent(handle.inputId, "Cinder Mobile"))
        checkEq(errorState.value, null, "error cleared for valid text")
        val okBatch = host.batches.last()
        val boxOkPatch = okBatch.ops().firstOrNull { it.opcode() == UiOps.OP_UPDATE && it.viewId() == handle.boxId }
        val msgOkPatch = okBatch.ops().firstOrNull { it.opcode() == UiOps.OP_UPDATE && it.viewId() == handle.messageTextId }
        checkEq(
            boxOkPatch?.findProp("borderColor")?.value?.asLong(),
            ThemeEngine.currentPalette()[ColorToken.INPUT_BORDER],
            "border restored to INPUT_BORDER",
        )
        checkEq(msgOkPatch?.propString("text"), "At least 3 characters", "helperText restored")

        checkCond(handle.focusMotionSlot in ManaMotion.SLOT_MIN..ManaMotion.SLOT_MAX, "textInput allocated focusMotionSlot")
        val focusEv = UiOps.ViewEvent.parse(UiOps.ViewEvent.buildViewEvent(handle.inputId, UiOps.propId("focus"), null))!!
        val blurEv = UiOps.ViewEvent.parse(UiOps.ViewEvent.buildViewEvent(handle.inputId, UiOps.propId("blur"), null))!!
        val beforeFocus = host.batches.size
        checkCond(router.onViewEvent(focusEv) === Frame.DROP, "focus event consumed")
        checkCond(host.batches.size > beforeFocus, "focus emitted OP_MOTION batch")
        val focusMotion =
            host.batches
                .last()
                .ops()
                .firstOrNull { it.opcode() == UiOps.OP_MOTION }
        checkCond(focusMotion != null, "textInput focus emitted OP_MOTION spring")
        checkEq(focusMotion!!.motionWords()[1], handle.focusMotionSlot, "focus targets focusMotionSlot")

        router.onViewEvent(changeTextEvent(handle.inputId, "A"))
        val focusedErrPatch =
            host.batches
                .last()
                .ops()
                .first { it.opcode() == UiOps.OP_UPDATE && it.viewId() == handle.boxId }
        checkEq(
            focusedErrPatch.findProp("borderColor")?.value?.asLong(),
            ThemeEngine.currentPalette()[ColorToken.DANGER],
            "error overrides focus border",
        )
        router.onViewEvent(changeTextEvent(handle.inputId, "Valid Name"))
        val focusedOkPatch =
            host.batches
                .last()
                .ops()
                .first { it.opcode() == UiOps.OP_UPDATE && it.viewId() == handle.boxId }
        checkEq(
            focusedOkPatch.findProp("borderColor")?.value?.asLong(),
            ThemeEngine.currentPalette()[ColorToken.BRAND_500],
            "clearing error while focused restores BRAND_500",
        )

        val beforeReskin = host.batches.size
        ThemeEngine.applyPreset(ThemePreset.LIGHT)
        checkCond(host.batches.size > beforeReskin, "LIGHT theme reskinned input focus worklet")

        checkCond(router.onViewEvent(blurEv) === Frame.DROP, "blur event consumed")
        val blurMotion =
            host.batches
                .last()
                .ops()
                .firstOrNull { it.opcode() == UiOps.OP_MOTION }
        checkCond(blurMotion != null, "textInput blur emitted OP_MOTION spring")

        router.pop()
        checkEq(ThemeEngine.bindingCount("test-input"), 0, "input theme bindings cleaned up")
        checkEq(ManaMotion.activeSlotCount("test-input"), 0, "input focus motion slot freed on pop")
    }

    private fun testCalloutTonesAvatarVariantsActionSheetAndButtonStyles() {
        val host = installSyncHost()
        val router = ManaRouter()
        router.attachNavigator(5, intArrayOf(6, 997, 4244), 2)

        lateinit var ghostEnabled: ManaState<Boolean>
        val ghostClicks = AtomicInteger(0)
        val linkClicks = AtomicInteger(0)
        val pickedOption = AtomicReference<String>("")

        val page =
            manaPage(title = "Catalog Test", scopeId = "test-catalog") {
                ghostEnabled = booleanState(false)

                for (tone in CalloutTone.values()) {
                    callout(
                        tone = tone,
                        title = "Tone ${tone.name}",
                        text = "Semantic callout for ${tone.name}",
                        refKey = "callout:${tone.name}",
                    )
                }
                banner(
                    tone = CalloutTone.WARNING,
                    text = "Banner alias test",
                    refKey = "banner:warn",
                )

                section(title = "Avatars & Buttons") {
                    avatar(
                        uri = "res://ic_mana_fire",
                        size = 44,
                        badgeTone = AvatarBadgeTone.ONLINE,
                        refKey = "avatar:uri",
                    )
                    avatar(
                        initials = "CM",
                        size = 36,
                        badgeTone = AvatarBadgeTone.DND,
                        refKey = "avatar:initials",
                    )
                    button(
                        label = "Small Primary",
                        style = ButtonStyle.PRIMARY,
                        size = ButtonSize.SM,
                        refKey = "btn:sm",
                    ) {}
                    button(
                        label = "Medium Secondary",
                        style = ButtonStyle.SECONDARY,
                        size = ButtonSize.MD,
                        refKey = "btn:md",
                    ) {}
                    button(
                        label = "Ghost Action",
                        style = ButtonStyle.GHOST,
                        enabledState = ghostEnabled,
                        refKey = "btn:ghost",
                    ) {
                        ghostClicks.incrementAndGet()
                    }
                    button(
                        label = "Learn More",
                        style = ButtonStyle.LINK,
                        refKey = "btn:link",
                    ) {
                        linkClicks.incrementAndGet()
                    }
                    actionRow(
                        title = "Open Action Sheet",
                        refKey = "open:actionsheet",
                    ) {
                        context.presentActionSheet(
                            title = "Choose Action",
                            subtitle = "Built on ManaSheet",
                        ) {
                            option(
                                label = "Copy ID",
                                icon = ManaIcons.COPY,
                                refKey = "opt:copy",
                            ) {
                                pickedOption.set("copy")
                            }
                            option(
                                label = "Delete Cache",
                                icon = ManaIcons.TRASH,
                                danger = true,
                                refKey = "opt:delete",
                            ) {
                                pickedOption.set("delete")
                            }
                            cancelButton("Dismiss", refKey = "opt:cancel")
                        }
                    }
                }
            }

        val mounted = router.push(page)
        val mountBatch = host.batches.last()

        val palette = ThemeEngine.currentPalette()
        for (tone in CalloutTone.values()) {
            val ch = mounted.ref<CalloutHandle>("callout:${tone.name}")!!
            val op = mountBatch.ops().first { it.opcode() == UiOps.OP_CREATE && it.viewId() == ch.rootId }
            checkEq(op.findProp("background")?.value?.asLong(), palette[tone.bgToken()], "${tone.name} bg token")
            checkEq(op.findProp("borderColor")?.value?.asLong(), palette[tone.accentToken()], "${tone.name} border accent")
        }
        checkCond(mounted.ref<CalloutHandle>("banner:warn") != null, "banner() alias emits CalloutHandle")

        val uriAvatar = mounted.ref<AvatarHandle>("avatar:uri")!!
        checkCond(uriAvatar.imageId >= 0 && uriAvatar.initialsTextId < 0 && uriAvatar.badgeId >= 0, "URI avatar + online badge")
        val initAvatar = mounted.ref<AvatarHandle>("avatar:initials")!!
        checkCond(initAvatar.initialsTextId >= 0 && initAvatar.imageId < 0 && initAvatar.badgeId >= 0, "initials avatar + DND badge")

        val smBtnId = mounted.ref<Int>("btn:sm")!!
        val mdBtnId = mounted.ref<Int>("btn:md")!!
        val smBtnOp = mountBatch.ops().first { it.opcode() == UiOps.OP_CREATE && it.viewId() == smBtnId }
        val mdBtnOp = mountBatch.ops().first { it.opcode() == UiOps.OP_CREATE && it.viewId() == mdBtnId }
        checkEq(smBtnOp.findProp("minHeight")?.value?.asInt(), 32, "SM button minHeight=32")
        checkEq(smBtnOp.findProp("radius")?.value?.asInt(), 8, "SM button radius=8")
        checkCond(smBtnOp.findProp("activeBackground") != null, "PRIMARY button sets tactile activeBackground")
        checkEq(mdBtnOp.findProp("minHeight")?.value?.asInt(), 40, "MD button minHeight=40")
        checkEq(mdBtnOp.findProp("radius")?.value?.asInt(), 10, "MD button radius=10")
        checkCond(mdBtnOp.findProp("activeBackground") != null, "SECONDARY button sets activeBackground")

        val ghostBtnId = mounted.ref<Int>("btn:ghost")!!
        val linkBtnId = mounted.ref<Int>("btn:link")!!
        checkCond(router.onViewEvent(pressEvent(ghostBtnId)) === Frame.DROP, "disabled ghost button still drops event")
        checkEq(ghostClicks.get(), 0, "disabled ghost button does NOT fire onPress")

        ghostEnabled.value = true
        val enablePatch = host.batches.last()
        val ghostEnableOp = enablePatch.ops().first { it.opcode() == UiOps.OP_UPDATE && it.viewId() == ghostBtnId }
        checkEq(ghostEnableOp.findProp("opacity")?.value?.asDouble(), 1.0, "re-enabled ghost button opacity=1.0")
        router.onViewEvent(pressEvent(ghostBtnId))
        checkEq(ghostClicks.get(), 1, "enabled ghost button fires onPress")

        router.onViewEvent(pressEvent(linkBtnId))
        checkEq(linkClicks.get(), 1, "ButtonStyle.LINK fires onPress")

        val beforeReskin = host.batches.size
        ThemeEngine.applyPreset(ThemePreset.LIGHT)
        checkCond(host.batches.size > beforeReskin, "LIGHT theme reskinned callouts & avatars")

        val openAsId = mounted.ref<Int>("open:actionsheet")!!
        router.onViewEvent(pressEvent(openAsId))
        val sheet = router.currentSheet
        checkCond(sheet != null, "actionSheet presented as ManaSheet overlay")
        val sheetScopeId = sheet!!.scope.scopeId
        val copyRowId = sheet.ref<Int>("opt:copy")!!
        router.onViewEvent(pressEvent(copyRowId))
        checkEq(pickedOption.get(), "copy", "actionSheet option invoked")
        checkCond(router.currentSheet == null, "actionSheet auto-dismissed after selection")
        checkEq(ThemeEngine.bindingCount(sheetScopeId), 0, "actionSheet theme bindings cleaned on dismiss")

        router.onViewEvent(pressEvent(openAsId))
        val sheet2 = router.currentSheet!!
        val cancelBtnId = sheet2.ref<Int>("opt:cancel")!!
        router.onViewEvent(pressEvent(cancelBtnId))
        checkCond(router.currentSheet == null, "actionSheet dismissed via cancelButton")

        router.pop()
        checkEq(ThemeEngine.bindingCount("test-catalog"), 0, "catalog theme bindings cleaned up")
    }

    private fun testPluginSettingsRegistryAutoDiscoveryAndDiagnosticsKotlinDsl() {
        installSyncHost()

        val cs = CinderSettings()
        val diag = CinderDiagnosticsPlugin()
        val minimalPlugin =
            object : ModPlugin {
                override fun id(): String = "minimal-mod"

                override fun version(): String = "1.2.3"

                override fun description(): String = "Lightweight plugin without custom settings UI"
            }

        cs.onLoad(null)
        diag.onLoad(null)
        Loader.setPluginsForTests(cs, diag, minimalPlugin)

        val overview = buildSettingsOverviewWithSearch()
        Loader.onOps(overview, overview.size)
        val cinderRowId =
            cs.getRouter().let {
                val parsed = UiOps.parse(Loader.onOps(overview, overview.size))!!
                parsed.ops().first { it.opcode() == UiOps.OP_CREATE && it.propString("accessibilityId") == "settings.row.cinder" }.viewId()
            }
        checkCond(
            Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(cinderRowId, UiOps.propId("press"), null)) == null,
            "Cinder row press consumed",
        )
        checkEq(cs.getRouter().depth, 1, "Cinder root page open")

        val rootPage = cs.getRouter().topPage!!
        val diagRow = rootPage.ref<ManaUi.NavRowHandle>("plugin:cinder-diagnostics")
        val minimalRow = rootPage.ref<ManaUi.ToggleRowHandle>("plugin:minimal-mod")
        val selfRow = rootPage.ref<ManaUi.NavRowHandle>("plugin:cinder-settings")
        checkCond(diagRow != null, "CinderDiagnosticsPlugin auto-discovered in Cinder -> Core Plugins")
        checkCond(minimalRow != null, "minimal-mod auto-discovered in Cinder -> Plugins (navToggleRow)")
        checkCond(selfRow == null, "CinderSettings itself excluded from Plugins list")

        Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(diagRow!!.rowId, UiOps.propId("press"), null))
        checkEq(cs.getRouter().depth, 2, "CinderDiagnosticsPlugin settings page pushed")
        val diagPage = cs.getRouter().topPage!!
        checkEq(diagPage.page.title, "Cinder Diagnostics", "CinderDiagnosticsPlugin custom title rendered")

        val dumpHandle = diagPage.ref<ManaUi.ToggleRowHandle>(CinderDiagnosticsPlugin.REF_DUMP_TOGGLE)!!
        checkEq(CinderSettings.isDumpSettingsOpsEnabled(), false, "dump toggle starts false")
        Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(dumpHandle.rowId, UiOps.propId("press"), null))
        checkEq(CinderSettings.isDumpSettingsOpsEnabled(), true, "toggling inside CinderDiagnosticsPlugin updates CinderSettings")

        val resetBtnId = diagPage.ref<Int>(CinderDiagnosticsPlugin.REF_RESET_BUTTON)!!
        Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(resetBtnId, UiOps.propId("press"), null))
        checkCond(cs.getRouter().currentToast != null, "CinderDiagnosticsPlugin Reset button displays confirmation toast")
        cs.getRouter().dismissToast()

        checkCond(cs.onBackPress(), "popped CinderDiagnosticsPlugin page")
        checkEq(cs.getRouter().depth, 1, "back on Cinder root")

        Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(minimalRow!!.rowId, UiOps.propId("press"), null))
        checkEq(cs.getRouter().depth, 2, "auto-generated detail page pushed for minimal-mod")
        checkEq(
            cs
                .getRouter()
                .topPage!!
                .page.title,
            "Minimal Mod",
            "default detail page uses title-cased plugin name",
        )

        checkCond(cs.onBackPress(), "popped minimal-mod detail page")
        checkCond(cs.onBackPress(), "popped Cinder root page")
        checkEq(cs.getRouter().depth, 0, "router empty")
        checkEq(ManaMotion.activeSlotCount(CinderSettings.THEME_SCOPE_PAGE), 0, "0 leaked page motion slots")
        checkEq(ThemeEngine.bindingCount(CinderSettings.THEME_SCOPE_PAGE), 0, "0 leaked page theme bindings")
    }

    private fun testSettingsSearchCoexistenceRePinningAndCleanTeardown() {
        val host = installSyncHost()

        val cs = CinderSettings()
        val diag = CinderDiagnosticsPlugin()
        cs.onLoad(null)
        diag.onLoad(null)
        Loader.setPluginsForTests(cs, diag)

        val search = cs.getSettingsSearch()

        CinderSettings.registerToggle(
            "custom.cinder.toggle",
            "Custom Cinder Toggle",
            "Registered explicitly by an external plugin",
            false,
            null,
        )

        val customSelected = AtomicBoolean(false)
        ManaSettingsSearch.registerEntry(
            ManaSearchEntry(
                id = "custom.shortcut",
                title = "Custom Plugin Shortcut",
                subtitle = "Jump straight into custom feature",
                keywords = "shortcut magic",
                onSelect = { customSelected.set(true) },
            ),
        )

        val overview =
            buildSettingsOverviewWithSearch(
                navId = 5,
                phoneId = 9231,
                scrollColId = 9238,
                searchWrapId = 9239,
                searchInputId = 9245,
                catWrapId = 9250,
            )
        Loader.onOps(overview, overview.size)
        checkEq(search.trackedSearchInputId, 9245, "tracked user_settings_search_input=9245")
        checkEq(search.trackedScrollColId, 9238, "tracked scrollColId=9238")

        val typeCinder = changeTextBytes(9245, "cinder")
        val dispatched = Loader.onDispatch(typeCinder)
        checkCond(dispatched != null, "user_settings_search_input changeText MUST pass through to Discord!")
        checkEq(search.currentQuery, "cinder", "search tracked query 'cinder'")
        val cinderSectionId = search.activeSectionId
        checkCond(cinderSectionId >= ManaIds.SYNTHETIC_START, "injected Cinder Results section")
        val cinderRows = search.activeRowIdsSnapshot()
        checkCond(cinderRows.containsKey("cinder"), "search results include Cinder root")
        checkCond(cinderRows.containsKey("toggle:custom.cinder.toggle"), "search results include registered external Cinder toggles")
        checkCond(
            !cinderRows.containsKey("toggle:${CinderSettings.KEY_DUMP_SETTINGS_OPS}"),
            "CinderDiagnosticsPlugin toggles are not duplicated in global toggles",
        )

        val injectBatch = host.batches.last()
        val setKidsOp = injectBatch.ops().first { it.opcode() == UiOps.OP_CHILDREN && it.viewId() == 9238 }
        checkEq(setKidsOp.childIds()[0], 9239, "search bar remains index 0")
        checkEq(setKidsOp.childIds()[1], cinderSectionId, "Cinder Results pinned right below search bar at index 1")

        val discordSearchBatch =
            UiOps
                .create()
                .createView(9600, "view", p("direction", UiOps.Value.ofString("column")))
                .createView(9601, "view", p("direction", UiOps.Value.ofString("column")))
                .setChildren(9238, 9239, 9600, 9601)
                .toBytes()
        val pinnedOut = Loader.onOps(discordSearchBatch, discordSearchBatch.size)
        checkCond(pinnedOut !== discordSearchBatch, "Discord async search update rewritten to preserve Cinder Results")
        val pinnedParsed = UiOps.parse(pinnedOut)!!
        val pinnedKids = pinnedParsed.ops().first { it.opcode() == UiOps.OP_CHILDREN && it.viewId() == 9238 }.childIds()
        checkCond(
            Arrays.equals(pinnedKids, intArrayOf(9239, cinderSectionId, 9600, 9601)),
            "Cinder Results cleanly preserved at index 1 alongside Discord's native results [9600, 9601]",
        )

        val oldScopeId = search.activeScopeId!!
        checkCond(Loader.onDispatch(changeTextBytes(9245, "diagnostics")) != null, "query update passes through to Discord")
        checkEq(ThemeEngine.bindingCount(oldScopeId), 0, "previous search scope theme bindings cleaned up")
        val diagSearchBatch = host.batches.last()
        checkCond(diagSearchBatch.ops().any { it.opcode() == UiOps.OP_DELETE }, "old search views deleted atomically")

        val diagRowId = search.activeRowIdsSnapshot()["plugin:cinder-diagnostics"]!!
        checkCond(
            Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(diagRowId, UiOps.propId("press"), null)) == null,
            "clicking injected search result row drops event from Rust",
        )
        checkEq(cs.getRouter().depth, 1, "CinderDiagnosticsPlugin settings page pushed straight from Settings Search")
        checkEq(
            cs
                .getRouter()
                .topPage!!
                .page.title,
            "Cinder Diagnostics",
            "opened CinderDiagnosticsPlugin page",
        )
        checkCond(cs.onBackPress(), "back returns to Settings Overview")

        Loader.onDispatch(changeTextBytes(9245, "magic"))
        val customRowId = search.activeRowIdsSnapshot()["custom.shortcut"]!!
        Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(customRowId, UiOps.propId("press"), null))
        checkCond(customSelected.get(), "custom ManaSearchEntry onSelect invoked")

        Loader.onDispatch(changeTextBytes(9245, "showcase"))
        val showcaseRowId = search.activeRowIdsSnapshot()["mana-showcase"]!!
        Loader.onDispatch(UiOps.ViewEvent.buildViewEvent(showcaseRowId, UiOps.propId("press"), null))
        checkEq(
            cs
                .getRouter()
                .topPage!!
                .page.title,
            "Mana Showcase",
            "opened Mana Showcase directly from search",
        )
        cs.onBackPress()

        val scopeBeforeClear = search.activeScopeId!!
        checkCond(Loader.onDispatch(changeTextBytes(9245, "")) != null, "clearing search passes through to Discord")
        checkEq(search.activeSectionId, -1, "no active search section after clear")
        checkEq(search.activeViewCount, 0, "0 active search views after clear")
        checkEq(ThemeEngine.bindingCount(scopeBeforeClear), 0, "0 leaked search theme bindings after clear")
        val clearBatch = host.batches.last()
        val restoredKids = clearBatch.ops().first { it.opcode() == UiOps.OP_CHILDREN && it.viewId() == 9238 }.childIds()
        checkCond(
            Arrays.equals(restoredKids, intArrayOf(9239, 9600, 9601)),
            "clean Discord scrollCol children restored after clearing query",
        )

        Loader.onDispatch(changeTextBytes(9245, "cinder"))
        val scopeBeforeTeardown = search.activeScopeId!!
        val beforeTeardownCount = host.batches.size
        val deleteOverview = UiOps.create().deleteViews(9231, 9238, 9245).toBytes()
        Loader.onOps(deleteOverview, deleteOverview.size)
        checkEq(search.activeSectionId, -1, "search detached on Overview OP_DELETE")
        checkEq(search.trackedSearchInputId, -1, "trackedSearchInputId reset on Overview OP_DELETE")
        checkEq(ThemeEngine.bindingCount(scopeBeforeTeardown), 0, "search theme bindings cleaned on Overview teardown")
        checkEq(host.batches.size, beforeTeardownCount, "no dangling synthetic ops emitted on Overview teardown")
    }

    private fun testManaShowcasePageFullPhase5MountInteractionAndCleanup() {
        installSyncHost()
        val router = ManaRouter()
        router.attachNavigator(5, intArrayOf(6, 997, 4244), 2)

        val mounted = router.push(ManaShowcasePage.create())
        val scopeId = mounted.scope.scopeId

        val cbHandle = mounted.ref<CheckboxRowHandle>(ManaShowcasePage.REF_CHECKBOX)!!
        val sliderHandle = mounted.ref<SliderRowHandle>(ManaShowcasePage.REF_SLIDER)!!
        val inputHandle = mounted.ref<TextInputHandle>(ManaShowcasePage.REF_TEXT_INPUT)!!
        val calloutHandle = mounted.ref<CalloutHandle>(ManaShowcasePage.REF_CALLOUT_INFO)!!
        val avatarHandle = mounted.ref<AvatarHandle>(ManaShowcasePage.REF_AVATAR)!!
        val ghostBtnId = mounted.ref<Int>(ManaShowcasePage.REF_GHOST_BTN)!!
        val linkBtnId = mounted.ref<Int>(ManaShowcasePage.REF_LINK_BTN)!!
        val openActionSheetId = mounted.ref<Int>(ManaShowcasePage.REF_OPEN_ACTION_SHEET)!!

        checkCond(calloutHandle.rootId >= ManaIds.SYNTHETIC_START, "showcase callout mounted")
        checkCond(avatarHandle.rootId >= ManaIds.SYNTHETIC_START, "showcase avatar mounted")

        checkCond(router.onViewEvent(pressEvent(cbHandle.rowId)) === Frame.DROP, "showcase checkbox toggled")
        checkCond(router.onViewEvent(sliderChangeEvent(sliderHandle.sliderId, 10)) === Frame.DROP, "showcase slider dragged")
        checkCond(router.onViewEvent(changeTextEvent(inputHandle.inputId, "X")) === Frame.DROP, "showcase input short validation")
        checkCond(router.onViewEvent(changeTextEvent(inputHandle.inputId, "Phoenix")) === Frame.DROP, "showcase input valid")
        checkCond(router.onViewEvent(pressEvent(ghostBtnId)) === Frame.DROP, "showcase ghost button pressed")
        checkCond(router.onViewEvent(pressEvent(linkBtnId)) === Frame.DROP, "showcase link button pressed")

        checkCond(router.onViewEvent(pressEvent(openActionSheetId)) === Frame.DROP, "showcase actionSheet opened")
        checkCond(router.currentSheet != null, "showcase actionSheet active")
        checkCond(router.onBackPress(), "Back dismissed showcase actionSheet")
        checkCond(router.currentSheet == null, "showcase actionSheet closed")

        router.dismissToast()
        checkCond(router.pop(), "showcase popped cleanly")
        checkEq(ManaMotion.activeSlotCount(scopeId), 0, "0 leaked showcase motion slots")
        checkEq(ThemeEngine.bindingCount(scopeId), 0, "0 leaked showcase theme bindings")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        runTest("checkboxRowNativeMotionAndThemeReskin") { testCheckboxRowNativeMotionAndThemeReskin() }
        runTest("sliderRowNativeViewWireSeekBarAndReactivity") { testSliderRowNativeViewWireSeekBarAndReactivity() }
        runTest("textInputTwoWayBindingValidationErrorClearAndMultiline") { testTextInputTwoWayBindingValidationErrorClearAndMultiline() }
        runTest("calloutTonesAvatarVariantsActionSheetAndButtonStyles") { testCalloutTonesAvatarVariantsActionSheetAndButtonStyles() }
        runTest(
            "pluginSettingsRegistryAutoDiscoveryAndDiagnosticsKotlinDsl",
        ) { testPluginSettingsRegistryAutoDiscoveryAndDiagnosticsKotlinDsl() }
        runTest("settingsSearchCoexistenceRePinningAndCleanTeardown") { testSettingsSearchCoexistenceRePinningAndCleanTeardown() }
        runTest("manaShowcasePageFullPhase5MountInteractionAndCleanup") { testManaShowcasePageFullPhase5MountInteractionAndCleanup() }
        println("ALL $passed ManaWidgetsAndRegistryTest TESTS PASSED")
    }
}
