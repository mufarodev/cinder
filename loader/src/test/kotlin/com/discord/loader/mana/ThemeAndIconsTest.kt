package com.discord.loader.mana

import com.discord.loader.CinderSettings
import com.discord.loader.HostInvoker
import com.discord.loader.Loader
import com.discord.loader.ManaUi
import com.discord.loader.UiOps
import java.util.concurrent.Executor

object ThemeAndIconsTest {
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
        Loader.resetForTests()
        registerTestProps()
        block()
        passed++
        println("PASS $name")
    }

    private fun registerTestProps() {
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
                "borderColor",
                "borderWidth",
                "changeText",
                "clip",
                "color",
                "direction",
                "dismiss",
                "dismissLabel",
                "dismissible",
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
                "lineHeight",
                "lineHeightExact",
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
                "uri",
                "value",
                "width",
            ),
        )
    }

    private fun p(vararg kv: Pair<String, UiOps.Value>): List<UiOps.Prop> = kv.map { (k, v) -> UiOps.Prop.named(k, v) }

    private fun colorVal(argb: Long): UiOps.Value = ManaUi.colorVal(argb)

    private class RecordingHost : HostInvoker {
        val appliedSynthetic = ArrayList<UiOps>()

        override fun invokeOrig(frame: ByteArray?): ByteArray? = null

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

    private fun buildSettingsOverviewBatch(
        phoneId: Int,
        scrollColId: Int,
        screenBg: Long,
        headerColor: Long,
        rowBg: Long,
        activeBg: Long,
        primaryTextColor: Long,
        mutedColor: Long,
        dividerColor: Long,
    ): ByteArray {
        val modalWrapperId = phoneId - 1
        val inner1 = phoneId + 1
        val inner2 = phoneId + 2
        val spacerId = phoneId + 3
        val topDivId = phoneId + 4
        val scrollId = phoneId + 5
        val headerBarId = scrollColId + 20
        val catWrapId = scrollColId + 1
        val catHeaderId = scrollColId + 2
        val cardId = scrollColId + 3
        val acctRowId = scrollColId + 4
        val acctInnerId = scrollColId + 5
        val acctIconId = scrollColId + 6
        val acctLabelId = scrollColId + 7
        val chevBoxId = scrollColId + 8
        val chevImgId = scrollColId + 9
        val divWrapId = scrollColId + 10
        val divLineId = scrollColId + 11

        return UiOps
            .create()
            .updateView(5, p("index" to UiOps.Value.ofInt(2)))
            .createView(
                modalWrapperId,
                "view",
                p(
                    "presentation" to UiOps.Value.ofString("modal"),
                    "direction" to UiOps.Value.ofString("column"),
                    "width" to UiOps.Value.ofString("fill"),
                    "height" to UiOps.Value.ofString("fill"),
                ),
            ).createView(
                phoneId,
                "view",
                p(
                    "direction" to UiOps.Value.ofString("column"),
                    "width" to UiOps.Value.ofString("fill"),
                    "height" to UiOps.Value.ofString("fill"),
                    "background" to colorVal(screenBg),
                    "clip" to UiOps.Value.ofBool(true),
                    "align" to UiOps.Value.ofString("stretch"),
                    "accessibilityId" to UiOps.Value.ofString("settings-phone"),
                ),
            ).createView(
                inner1,
                "view",
                p(
                    "direction" to UiOps.Value.ofString("column"),
                    "width" to UiOps.Value.ofString("fill"),
                    "height" to UiOps.Value.ofString("fill"),
                ),
            ).createView(
                inner2,
                "view",
                p(
                    "direction" to UiOps.Value.ofString("column"),
                    "width" to UiOps.Value.ofString("fill"),
                    "height" to UiOps.Value.ofString("fill"),
                    "background" to colorVal(screenBg),
                ),
            ).createView(
                spacerId,
                "view",
                p("width" to UiOps.Value.ofString("fill"), "height" to UiOps.Value.ofDouble(94.476)),
            ).createView(
                topDivId,
                "view",
                p("width" to UiOps.Value.ofString("fill"), "height" to UiOps.Value.ofInt(1)),
            ).createView(
                scrollId,
                "scroll",
                p("width" to UiOps.Value.ofString("fill"), "flex" to UiOps.Value.ofInt(1)),
            ).createView(
                scrollColId,
                "view",
                p(
                    "direction" to UiOps.Value.ofString("column"),
                    "padding" to UiOps.Value.ofFloat4(16f, 24f, 16f, 30.857f),
                    "width" to UiOps.Value.ofString("fill"),
                    "gap" to UiOps.Value.ofInt(24),
                    "align" to UiOps.Value.ofString("start"),
                ),
            ).createView(catWrapId, "view", p("direction" to UiOps.Value.ofString("column")))
            .createView(
                catHeaderId,
                "text",
                p(
                    "text" to UiOps.Value.ofString("Account Settings"),
                    "color" to colorVal(headerColor),
                    "fontSize" to UiOps.Value.ofInt(16),
                    "role" to UiOps.Value.ofString("header"),
                ),
            ).createView(cardId, "view", p("direction" to UiOps.Value.ofString("column")))
            .createView(
                acctRowId,
                "view",
                p(
                    "direction" to UiOps.Value.ofString("column"),
                    "width" to UiOps.Value.ofString("fill"),
                    "background" to colorVal(rowBg),
                    "activeBackground" to colorVal(activeBg),
                    "role" to UiOps.Value.ofString("button"),
                    "accessibilityId" to UiOps.Value.ofString("settings.row.account"),
                    "on" to ManaUi.stringArray("press"),
                ),
            ).createView(acctInnerId, "view", p("direction" to UiOps.Value.ofString("row")))
            .createView(
                acctIconId,
                "image",
                p(
                    "uri" to UiOps.Value.ofString(ManaIcons.USER_CIRCLE),
                    "width" to UiOps.Value.ofInt(24),
                    "height" to UiOps.Value.ofInt(24),
                    "tint" to colorVal(primaryTextColor),
                    "scale" to UiOps.Value.ofString("fit"),
                ),
            ).createView(
                acctLabelId,
                "text",
                p(
                    "text" to UiOps.Value.ofString("Account"),
                    "color" to colorVal(primaryTextColor),
                    "fontSize" to UiOps.Value.ofInt(16),
                    "includeFontPadding" to UiOps.Value.ofBool(true),
                ),
            ).createView(chevBoxId, "view", p("width" to UiOps.Value.ofInt(12), "height" to UiOps.Value.ofInt(24)))
            .createView(
                chevImgId,
                "image",
                p(
                    "uri" to UiOps.Value.ofString(ManaIcons.CHEVRON_SMALL_RIGHT),
                    "width" to UiOps.Value.ofInt(12),
                    "height" to UiOps.Value.ofInt(24),
                    "tint" to colorVal(mutedColor),
                ),
            ).createView(divWrapId, "view", p("height" to UiOps.Value.ofInt(1)))
            .createView(
                divLineId,
                "view",
                p("width" to UiOps.Value.ofString("fill"), "height" to UiOps.Value.ofInt(1), "background" to colorVal(dividerColor)),
            ).createView(
                headerBarId,
                "view",
                p(
                    "direction" to UiOps.Value.ofString("row"),
                    "padding" to UiOps.Value.ofFloat4(6f, 44f, 6f, 0f),
                    "height" to UiOps.Value.ofDouble(94.476),
                    "background" to colorVal(screenBg),
                    "stackHeader" to UiOps.Value.ofBool(true),
                ),
            ).setChildren(divWrapId, divLineId)
            .setChildren(chevBoxId, chevImgId)
            .setChildren(acctInnerId, acctIconId, acctLabelId, chevBoxId)
            .setChildren(acctRowId, acctInnerId)
            .setChildren(cardId, acctRowId, divWrapId)
            .setChildren(catWrapId, catHeaderId, cardId)
            .setChildren(scrollColId, catWrapId)
            .setChildren(scrollId, scrollColId)
            .setChildren(inner2, spacerId, topDivId, scrollId, headerBarId)
            .setChildren(inner1, inner2)
            .setChildren(phoneId, inner1)
            .setChildren(modalWrapperId, phoneId)
            .setChildren(5, 6, 997, modalWrapperId)
            .toBytes()
    }

    private fun testManaIconsCatalogAndIconHelper() {
        val all = ManaIcons.allVerified()
        checkCond(all.size >= 70, "curated ManaIcons catalog should be substantial (${all.size})")
        checkEq(all.toSet().size, all.size, "no duplicate URIs in ManaIcons.allVerified()")
        for (uri in all) {
            checkCond(ManaIcons.isManaIconUri(uri), "valid Mana icon URI: $uri")
        }

        checkEq(ManaIcons.uri("fire"), ManaIcons.FIRE, "short name normalized")
        checkEq(ManaIcons.uri("ic_mana_wrench"), ManaIcons.WRENCH, "drawable name normalized")
        checkEq(ManaIcons.uri("res://ic_mana_bug"), ManaIcons.BUG, "full URI preserved")
        checkCond(!ManaIcons.isManaIconUri("res://other_icon"), "non-mana prefix rejected")
        checkCond(!ManaIcons.isManaIconUri("res://ic_mana_"), "empty mana name rejected")
        checkCond(!ManaIcons.isManaIconUri(null), "null URI rejected")

        val batch = UiOps.create()
        val ids = monotonicAllocator(0x7002_0000)
        val iconId =
            ThemeEngine.withScope("icon-test") {
                ManaUi.icon(batch, ids, ManaIcons.SHIELD_LOCK, 24, ColorToken.ICON_PRIMARY, ManaPalette.ASH)
            }
        val marginedId =
            ManaUi.icon(
                batch,
                ids,
                ManaIcons.CHEVRON_SMALL_RIGHT,
                12,
                24,
                ManaPalette.ASH[ColorToken.ICON_MUTED],
                ColorToken.ICON_MUTED,
                4f,
                4f,
                16f,
                4f,
            )

        val reparsed = UiOps.parse(batch.toBytes())!!
        checkEq(reparsed.size(), 2, "2 icon OP_CREATE ops emitted")

        val op0 = reparsed.get(0)
        checkEq(op0.opcode(), UiOps.OP_CREATE, "op0 opcode")
        checkEq(op0.viewId(), iconId, "op0 viewId")
        checkEq(op0.viewType(), "image", "op0 viewType=image")
        checkEq(op0.propString("uri"), "res://ic_mana_shield_lock", "op0 uri")
        checkEq(op0.findProp("width").value.asInt(), 24, "op0 width=24")
        checkEq(op0.findProp("height").value.asInt(), 24, "op0 height=24")
        checkEq(op0.findProp("tint").value.asLong() and 0xFFFFFFFFL, 0xFFFBFBFBL, "op0 tint")
        checkEq(op0.propString("scale"), "fit", "op0 scale=fit")
        checkCond(op0.findProp("margin") == null, "zero-margin icon omits margin prop")

        val op1 = reparsed.get(1)
        checkEq(op1.viewId(), marginedId, "op1 viewId")
        checkEq(op1.propString("uri"), "res://ic_mana_chevron_small_right", "op1 uri")
        checkEq(op1.findProp("width").value.asInt(), 12, "op1 width=12")
        checkEq(op1.findProp("height").value.asInt(), 24, "op1 height=24")
        val margin = op1.findProp("margin").value.asFloat4()
        checkCond(
            margin != null && margin[0] == 4f && margin[1] == 4f && margin[2] == 16f && margin[3] == 4f,
            "op1 margin=[4,4,16,4]",
        )
        checkEq(op1.propString("scale"), "fit", "op1 scale=fit")

        checkEq(ThemeEngine.bindingCount("icon-test"), 1, "scoped icon registered tint binding")
        ThemeEngine.unbindScope("icon-test")
        checkEq(ThemeEngine.bindingCount("icon-test"), 0, "icon scope unbound")
    }

    private fun testBuiltinPresetsAndColdStartMatching() {
        for (preset in ThemePreset.values()) {
            val pal = ManaPalette.forPreset(preset)
            checkEq(pal.preset, preset, "preset matches")
            for (token in ColorToken.values()) {
                checkCond(pal[token] != 0L, "$preset defines non-zero token $token")
            }
            val legacy = pal.toLegacyTheme()
            checkEq(legacy.screenBg, pal[ColorToken.BG_BASE_PRIMARY], "$preset legacy screenBg")
            checkEq(legacy.rowBg, pal[ColorToken.BG_BASE_SECONDARY], "$preset legacy rowBg")
            checkEq(legacy.primaryText, pal[ColorToken.TEXT_PRIMARY], "$preset legacy primaryText")
            checkEq(legacy.switchOffBg, pal[ColorToken.SWITCH_OFF_BG], "$preset legacy switchOffBg")
        }

        checkEq(ManaPalette.ASH[ColorToken.BG_BASE_PRIMARY], 0xFF242429L, "Ash screenBg")
        checkEq(ManaPalette.ASH[ColorToken.BG_BASE_SECONDARY], 0x1497979FL, "Ash rowBg")
        checkEq(ManaPalette.ASH[ColorToken.BG_ROW_ACTIVE], 0x12FFFFFFL, "Ash activeBg")
        checkEq(ManaPalette.ASH[ColorToken.TEXT_PRIMARY], 0xFFFBFBFBL, "Ash primaryText")
        checkEq(ManaPalette.ASH[ColorToken.TEXT_SECONDARY], 0xFFABACB2L, "Ash secondaryText")
        checkEq(ManaPalette.ASH[ColorToken.TEXT_MUTED], 0xFF96979EL, "Ash mutedText")
        checkEq(ManaPalette.ASH[ColorToken.DANGER_TEXT], 0xFFF87E7AL, "Ash dangerText")
        checkEq(ManaPalette.ASH[ColorToken.BRAND_500], 0xFF5865F2L, "Ash brand500")

        checkEq(ManaPalette.matchPreset(0xFFF2F3F5L), ThemePreset.LIGHT, "Light standard")
        checkEq(ManaPalette.matchPreset(0xFFFFFFFFL), ThemePreset.LIGHT, "Pure white -> Light")
        checkEq(ManaPalette.matchPreset(0xFFFBFBFBL), ThemePreset.LIGHT, "Visual Refresh light -> Light")
        checkEq(ManaPalette.matchPreset(0xFF242429L), ThemePreset.ASH, "Ash exact")
        checkEq(ManaPalette.matchPreset(0xFF232429L), ThemePreset.ASH, "Ash near")
        checkEq(ManaPalette.matchPreset(0xFF1A1A1EL), ThemePreset.DARK, "Dark exact")
        checkEq(ManaPalette.matchPreset(0xFF1C1D22L), ThemePreset.DARK, "Dark legacy")
        checkEq(ManaPalette.matchPreset(0xFF070709L), ThemePreset.ONYX, "Onyx exact")
        checkEq(ManaPalette.matchPreset(0xFF000000L), ThemePreset.ONYX, "AMOLED black -> Onyx")

        val nonSettingsLight =
            UiOps
                .create()
                .createView(
                    42,
                    "view",
                    p(
                        "direction" to UiOps.Value.ofString("column"),
                        "width" to UiOps.Value.ofString("fill"),
                        "height" to UiOps.Value.ofString("fill"),
                        "background" to colorVal(0xFFFBFBFBL),
                    ),
                )
        ThemeEngine.observeBatch(nonSettingsLight.ops(), applyReskin = false)
        checkEq(ThemeEngine.currentPreset(), ThemePreset.LIGHT, "cold-start snapped to LIGHT")
        checkEq(ThemeEngine.color(ColorToken.BG_BASE_PRIMARY), 0xFFFBFBFBL, "exact root bg preserved")

        checkEq(
            ThemeEngine.color(ColorToken.SWITCH_OFF_BG),
            ManaPalette.LIGHT[ColorToken.SWITCH_OFF_BG],
            "unseen SWITCH_OFF_BG snapped to LIGHT",
        )
        checkEq(
            ThemeEngine.color(ColorToken.RADIO_UNCHECKED_BORDER),
            ManaPalette.LIGHT[ColorToken.RADIO_UNCHECKED_BORDER],
            "unseen RADIO_UNCHECKED_BORDER snapped to LIGHT",
        )
        checkEq(
            ThemeEngine.color(ColorToken.TEXT_PRIMARY),
            ManaPalette.LIGHT[ColorToken.TEXT_PRIMARY],
            "unseen TEXT_PRIMARY snapped to LIGHT",
        )
    }

    private fun testNitroCustomThemeOverrideLayering() {
        val nitroBatch =
            UiOps
                .create()
                .createView(
                    100,
                    "view",
                    p(
                        "width" to UiOps.Value.ofString("fill"),
                        "height" to UiOps.Value.ofString("fill"),
                        "background" to colorVal(0xFF1D1228L),
                        "accessibilityId" to UiOps.Value.ofString("settings-phone"),
                    ),
                ).createView(
                    101,
                    "view",
                    p(
                        "role" to UiOps.Value.ofString("button"),
                        "background" to colorVal(0x22A080FFL),
                        "activeBackground" to colorVal(0x33C0A0FFL),
                    ),
                ).createView(
                    102,
                    "text",
                    p(
                        "text" to UiOps.Value.ofString("Account"),
                        "color" to colorVal(0xFFF5EBFFL),
                        "fontSize" to UiOps.Value.ofInt(16),
                        "includeFontPadding" to UiOps.Value.ofBool(true),
                    ),
                ).createView(
                    103,
                    "view",
                    p(
                        "width" to UiOps.Value.ofInt(56),
                        "height" to UiOps.Value.ofInt(28),
                        "background" to colorVal(0xFF9B59B6L),
                        "borderColor" to colorVal(0x33FFFFFFL),
                        "pressFeedback" to UiOps.Value.ofString("none"),
                    ),
                )

        ThemeEngine.observeBatch(nitroBatch.ops(), applyReskin = false)
        checkCond(ThemeEngine.currentPreset().isDark, "Nitro purple snapped to dark preset family")
        checkEq(ThemeEngine.color(ColorToken.BG_BASE_PRIMARY), 0xFF1D1228L, "Nitro root bg")
        checkEq(ThemeEngine.color(ColorToken.HEADER_BG), 0xFF1D1228L, "Nitro header inherits root bg")
        checkEq(ThemeEngine.color(ColorToken.BG_BASE_SECONDARY), 0x22A080FFL, "Nitro row bg")
        checkEq(ThemeEngine.color(ColorToken.BG_ROW_ACTIVE), 0x33C0A0FFL, "Nitro active row bg")
        checkEq(ThemeEngine.color(ColorToken.TEXT_PRIMARY), 0xFFF5EBFFL, "Nitro primary text")
        checkEq(ThemeEngine.color(ColorToken.ICON_PRIMARY), 0xFFF5EBFFL, "Nitro primary icon mirrors text")
        checkEq(ThemeEngine.color(ColorToken.BRAND_500), 0xFF9B59B6L, "Nitro custom brand accent")
        checkEq(ThemeEngine.color(ColorToken.SWITCH_ON_BG), 0xFF9B59B6L, "Nitro switch ON bg")

        checkEq(ThemeEngine.color(ColorToken.SWITCH_THUMB), 0xFFFFFFFFL, "unseen dark SWITCH_THUMB")

        val lightBatch =
            UiOps.create().createView(
                200,
                "view",
                p(
                    "width" to UiOps.Value.ofString("fill"),
                    "height" to UiOps.Value.ofString("fill"),
                    "background" to colorVal(0xFFF2F3F5L),
                    "accessibilityId" to UiOps.Value.ofString("settings-phone"),
                ),
            )
        ThemeEngine.observeBatch(lightBatch.ops(), applyReskin = false)
        checkEq(ThemeEngine.currentPreset(), ThemePreset.LIGHT, "switched from Nitro dark to Light")
        checkEq(ThemeEngine.color(ColorToken.BRAND_500), ManaPalette.LIGHT[ColorToken.BRAND_500], "stale Nitro accent cleared")
        checkEq(
            ThemeEngine.color(ColorToken.BG_BASE_SECONDARY),
            ManaPalette.LIGHT[ColorToken.BG_BASE_SECONDARY],
            "stale Nitro rowBg cleared",
        )
        checkEq(ThemeEngine.color(ColorToken.TEXT_PRIMARY), ManaPalette.LIGHT[ColorToken.TEXT_PRIMARY], "stale Nitro text cleared")
    }

    private fun testLiveReskinningAtomicDiffAndUnbind() {
        ThemeEngine.applyPreset(ThemePreset.ASH, applyReskin = false)

        val scope = "test-live-scope"
        val canvasId = 0x7005_0001
        val rowId = 0x7005_0002
        val iconId = 0x7005_0003
        val brandBtnId = 0x7005_0004

        ThemeEngine.bind(scope, canvasId, "background", ColorToken.BG_BASE_PRIMARY)
        ThemeEngine.bind(scope, rowId, "background", ColorToken.BG_BASE_SECONDARY)
        ThemeEngine.bind(scope, rowId, "activeBackground", ColorToken.BG_ROW_ACTIVE)
        ThemeEngine.bind(scope, iconId, "tint", ColorToken.ICON_MUTED)
        ThemeEngine.bind(scope, brandBtnId, "background", ColorToken.BRAND_500)

        val pureDiff = ThemeEngine.computeReskinOps(ManaPalette.ASH, ManaPalette.LIGHT)
        checkEq(pureDiff.size(), 3, "exactly 3 views updated (unchanged brandBtnId omitted!)")
        val updatedIds = pureDiff.ops().map { it.viewId() }.toSet()
        checkCond(canvasId in updatedIds, "canvasId in atomic reskin batch")
        checkCond(rowId in updatedIds, "rowId in atomic reskin batch")
        checkCond(iconId in updatedIds, "iconId in atomic reskin batch")
        checkCond(brandBtnId !in updatedIds, "unchanged brandBtnId omitted from reskin batch")

        val rowUpdate = pureDiff.ops().first { it.viewId() == rowId }
        checkEq(rowUpdate.opcode(), UiOps.OP_UPDATE, "reskin emits OP_UPDATE")
        checkEq(
            rowUpdate.findProp("background").value.asLong() and 0xFFFFFFFFL,
            ManaPalette.LIGHT[ColorToken.BG_BASE_SECONDARY],
            "rowId background updated to Light",
        )
        checkEq(
            rowUpdate.findProp("activeBackground").value.asLong() and 0xFFFFFFFFL,
            ManaPalette.LIGHT[ColorToken.BG_ROW_ACTIVE],
            "rowId activeBackground updated to Light",
        )

        val lightBatch =
            UiOps
                .create()
                .createView(
                    300,
                    "view",
                    p(
                        "width" to UiOps.Value.ofString("fill"),
                        "height" to UiOps.Value.ofString("fill"),
                        "background" to colorVal(0xFFF2F3F5L),
                        "accessibilityId" to UiOps.Value.ofString("settings-phone"),
                    ),
                )
        val reskin1 = ThemeEngine.observeBatch(lightBatch.ops(), applyReskin = false)
        checkEq(reskin1.size(), 3, "observeBatch emitted 3 atomic OP_UPDATEs on ASH -> LIGHT")

        val reskin2 = ThemeEngine.observeBatch(lightBatch.ops(), applyReskin = false)
        checkCond(reskin2.isEmpty(), "identical theme batch emits zero reskin ops")

        val inPlaceOnyx =
            UiOps
                .create()
                .updateView(300, p("background" to colorVal(0xFF070709L)))
        val reskinOnyx = ThemeEngine.observeBatch(inPlaceOnyx.ops(), applyReskin = false)
        checkEq(ThemeEngine.currentPreset(), ThemePreset.ONYX, "in-place OP_UPDATE switched to ONYX")
        checkEq(reskinOnyx.size(), 3, "in-place OP_UPDATE repainted all 3 changed bound views")
        val canvasOnyx = reskinOnyx.ops().first { it.viewId() == canvasId }
        checkEq(
            canvasOnyx.findProp("background").value.asLong() and 0xFFFFFFFFL,
            0xFF070709L,
            "canvas repainted to Onyx 0xFF070709",
        )

        ThemeEngine.unbindScope(scope)
        checkEq(ThemeEngine.bindingCount(scope), 0, "scope bindings cleared")
        val afterUnbind = ThemeEngine.applyPreset(ThemePreset.ASH, applyReskin = false)
        checkCond(afterUnbind.isEmpty(), "zero ops emitted after unbindScope")
    }

    private fun testCinderSettingsIconsAndLiveThemeSwitch() {
        val host = installSyncHost()
        val cs = CinderSettings()
        cs.onLoad(null)
        Loader.setPluginsForTests(cs)

        val ashOverview =
            buildSettingsOverviewBatch(
                phoneId = 4245,
                scrollColId = 4251,
                screenBg = 0xFF242429L,
                headerColor = 0xFFABACB2L,
                rowBg = 0x1497979FL,
                activeBg = 0x12FFFFFFL,
                primaryTextColor = 0xFFFBFBFBL,
                mutedColor = 0xFF96979EL,
                dividerColor = 0x1F97979FL,
            )
        val modifiedOverview = Loader.onOps(ashOverview, ashOverview.size)
        checkEq(ThemeEngine.currentPreset(), ThemePreset.ASH, "detected ASH from Settings Overview")

        val parsedOverview = UiOps.parse(modifiedOverview)!!
        var foundLeadingFire = false
        var foundChevron = false
        var overviewRowId = -1
        for (op in parsedOverview.ops()) {
            if (op.opcode() != UiOps.OP_CREATE || !UiOps.isSyntheticId(op.viewId())) continue
            if ("settings.row.cinder" == op.propString("accessibilityId")) {
                overviewRowId = op.viewId()
            }
            if ("image" == op.viewType()) {
                val uri = op.propString("uri")
                if (uri == ManaIcons.FIRE) {
                    val m = op.findProp("margin")?.value?.asFloat4()
                    checkEq(op.findProp("width").value.asInt(), 24, "overview leading icon width=24")
                    checkEq(op.findProp("height").value.asInt(), 24, "overview leading icon height=24")
                    checkCond(m != null && m[0] == 4f && m[2] == 16f, "overview leading icon margin=[4,4,16,4]")
                    checkEq(op.propString("scale"), "fit", "overview leading icon scale=fit")
                    foundLeadingFire = true
                } else if (uri == ManaIcons.CHEVRON_SMALL_RIGHT) {
                    checkEq(op.findProp("width").value.asInt(), 12, "overview chevron width=12")
                    checkEq(op.findProp("height").value.asInt(), 24, "overview chevron height=24")
                    checkEq(op.propString("scale"), "fit", "overview chevron scale=fit")
                    foundChevron = true
                }
            }
        }
        checkCond(foundLeadingFire, "Overview row uses real res://ic_mana_fire leading icon")
        checkCond(foundChevron, "Overview row uses real res://ic_mana_chevron_small_right")
        checkCond(overviewRowId >= UiOps.SYNTHETIC_ID_BASE, "found injected overview row ID")
        checkCond(
            ThemeEngine.bindingCount(CinderSettings.THEME_SCOPE_OVERVIEW) > 0,
            "Overview registered live ThemeEngine bindings",
        )

        val pressCinder = UiOps.ViewEvent.buildViewEvent(overviewRowId, UiOps.propId("press"), null)
        checkCond(Loader.onDispatch(pressCinder) == null, "Cinder row press handled")
        checkEq(host.appliedSynthetic.size, 1, "1 push batch applied on page open")
        checkCond(
            ThemeEngine.bindingCount(CinderSettings.THEME_SCOPE_PAGE) > 10,
            "Cinder page registered semantic ThemeEngine bindings",
        )

        val pushBatch = host.appliedSynthetic[0]
        var foundBackArrow = false
        for (op in pushBatch.ops()) {
            if (op.opcode() == UiOps.OP_CREATE &&
                "image" == op.viewType() &&
                op.propString("uri") == ManaIcons.ARROW_LARGE_LEFT
            ) {
                checkEq(op.findProp("width").value.asInt(), 24, "back arrow width=24")
                checkEq(op.findProp("height").value.asInt(), 24, "back arrow height=24")
                checkEq(op.propString("scale"), "fit", "back arrow scale=fit")
                foundBackArrow = true
            }
        }
        checkCond(foundBackArrow, "Cinder Page uses real res://ic_mana_arrow_large_left back arrow")

        val liveLightSwitch =
            UiOps
                .create()
                .updateView(4245, p("background" to colorVal(0xFFF2F3F5L)))
                .toBytes()
        Loader.onOps(liveLightSwitch, liveLightSwitch.size)

        checkEq(ThemeEngine.currentPreset(), ThemePreset.LIGHT, "ThemeEngine switched live to LIGHT")
        checkEq(host.appliedSynthetic.size, 2, "atomic live re-skin batch applied to HostInvoker")

        val reskinBatch = host.appliedSynthetic[1]
        checkCond(reskinBatch.size() > 10, "reskin batch updates Overview + open Cinder Page views")
        var repaintedOverviewRow = false
        var repaintedCanvasToLight = false
        for (op in reskinBatch.ops()) {
            checkEq(op.opcode(), UiOps.OP_UPDATE, "every op in live reskin batch is OP_UPDATE")
            if (op.viewId() == overviewRowId) {
                checkEq(
                    op.findProp("background").value.asLong() and 0xFFFFFFFFL,
                    ManaPalette.LIGHT[ColorToken.BG_BASE_SECONDARY],
                    "overview Cinder row repainted to Light card bg",
                )
                repaintedOverviewRow = true
            }
            val bgProp = op.findProp("background")
            if (bgProp != null && (bgProp.value.asLong() and 0xFFFFFFFFL) == 0xFFF2F3F5L) {
                repaintedCanvasToLight = true
            }
        }
        checkCond(repaintedOverviewRow, "Overview Cinder row repainted live")
        checkCond(repaintedCanvasToLight, "Cinder Page canvas/header repainted live to Light")

        val backEvent = UiOps.ViewEvent.buildBackEvent()
        checkCond(Loader.onDispatch(backEvent) == null, "hardware back closed Cinder page")
        checkEq(ThemeEngine.bindingCount(CinderSettings.THEME_SCOPE_PAGE), 0, "page theme bindings unbound on close")
        checkCond(
            ThemeEngine.bindingCount(CinderSettings.THEME_SCOPE_OVERVIEW) > 0,
            "overview theme bindings preserved after page close",
        )
    }

    private fun testScrapedInputBackgroundMustBeNeutral() {
        fun box(id: Int, bg: Long) = UiOps.create().createView(
            id,
            "view",
            p(
                "minHeight" to UiOps.Value.ofInt(40),
                "radius" to UiOps.Value.ofInt(8),
                "borderWidth" to UiOps.Value.ofInt(1),
                "borderColor" to colorVal(0xFF4E5058L),
                "background" to colorVal(bg),
            ),
        )
        // A saturated green view with the search box's shape must NOT become INPUT_BG
        ThemeEngine.observeBatch(box(60, 0xFF008545L).ops(), applyReskin = false)
        checkCond(
            ThemeEngine.color(ColorToken.INPUT_BG) != 0xFF008545L,
            "saturated green rejected as INPUT_BG",
        )
        // A neutral dark input background is still accepted
        ThemeEngine.observeBatch(box(61, 0xFF1E1F22L).ops(), applyReskin = false)
        checkEq(ThemeEngine.color(ColorToken.INPUT_BG), 0xFF1E1F22L, "neutral input bg scraped")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        runTest("testManaIconsCatalogAndIconHelper", ::testManaIconsCatalogAndIconHelper)
        runTest("testBuiltinPresetsAndColdStartMatching", ::testBuiltinPresetsAndColdStartMatching)
        runTest("testNitroCustomThemeOverrideLayering", ::testNitroCustomThemeOverrideLayering)
        runTest("testLiveReskinningAtomicDiffAndUnbind", ::testLiveReskinningAtomicDiffAndUnbind)
        runTest("testCinderSettingsIconsAndLiveThemeSwitch", ::testCinderSettingsIconsAndLiveThemeSwitch)
        runTest("testScrapedInputBackgroundMustBeNeutral", ::testScrapedInputBackgroundMustBeNeutral)
        println("ALL $passed THEME AND ICONS TESTS PASSED")
    }
}
