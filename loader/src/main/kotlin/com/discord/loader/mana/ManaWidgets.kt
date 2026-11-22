package com.discord.loader.mana

import com.discord.loader.ManaUi
import com.discord.loader.UiOps

enum class CalloutTone {
    INFO,
    WARNING,
    DANGER,
    POSITIVE,
    BRAND,
    ;

    fun bgToken(): ColorToken =
        when (this) {
            INFO -> ColorToken.CALLOUT_INFO_BG
            WARNING -> ColorToken.CALLOUT_WARNING_BG
            DANGER -> ColorToken.CALLOUT_DANGER_BG
            POSITIVE -> ColorToken.CALLOUT_POSITIVE_BG
            BRAND -> ColorToken.CALLOUT_BRAND_BG
        }

    fun accentToken(): ColorToken =
        when (this) {
            INFO -> ColorToken.TEXT_LINK
            WARNING -> ColorToken.WARNING
            DANGER -> ColorToken.DANGER
            POSITIVE -> ColorToken.POSITIVE
            BRAND -> ColorToken.BRAND_500
        }

    internal fun defaultIcon(): String =
        when (this) {
            INFO -> ManaIcons.CIRCLE_INFORMATION
            WARNING -> ManaIcons.WARNING
            DANGER -> ManaIcons.WARNING
            POSITIVE -> ManaIcons.CIRCLE_CHECK
            BRAND -> ManaIcons.SPARKLES
        }
}

enum class AvatarBadgeTone {
    ONLINE,
    IDLE,
    DND,
    OFFLINE,
    BRAND,
    ;

    internal fun colorToken(): ColorToken =
        when (this) {
            ONLINE -> ColorToken.POSITIVE
            IDLE -> ColorToken.WARNING
            DND -> ColorToken.DANGER
            OFFLINE -> ColorToken.ICON_MUTED
            BRAND -> ColorToken.BRAND_500
        }
}

data class CheckboxRowHandle(
    val rowId: Int,
    val boxId: Int,
    val checkWrapId: Int,
    val checkIconId: Int,
    val motionSlot: Int,
)

data class SliderRowHandle(
    val rowId: Int,
    val sliderId: Int,
    val valueTextId: Int,
    val range: IntRange,
    val step: Int,
)

data class TextInputHandle(
    val rootId: Int,
    val boxId: Int,
    val inputId: Int,
    val clearBtnId: Int,
    val messageTextId: Int,
    val focusMotionSlot: Int = -1,
)

data class CalloutHandle(
    val rootId: Int,
    val iconId: Int,
    val titleId: Int,
    val bodyId: Int,
)

data class AvatarHandle(
    val rootId: Int,
    val circleId: Int,
    val imageId: Int,
    val initialsTextId: Int,
    val badgeId: Int,
)

internal class ActionSheetOption(
    val label: String,
    val subtitle: String?,
    val icon: String?,
    val danger: Boolean,
    val autoDismiss: Boolean,
    val accessibilityId: String?,
    val refKey: String?,
    val onPress: () -> Unit,
)

internal class ActionSheetCancel(
    val label: String,
    val refKey: String?,
    val onPress: (() -> Unit)?,
)

@ManaDsl
class ActionSheetBuilder internal constructor() {
    internal val options = ArrayList<ActionSheetOption>()
    internal var cancel: ActionSheetCancel? = null

    fun option(
        label: String,
        subtitle: String? = null,
        icon: String? = null,
        danger: Boolean = false,
        autoDismiss: Boolean = true,
        accessibilityId: String? = null,
        refKey: String? = null,
        onPress: () -> Unit,
    ) {
        options.add(
            ActionSheetOption(
                label = label,
                subtitle = subtitle,
                icon = icon,
                danger = danger,
                autoDismiss = autoDismiss,
                accessibilityId = accessibilityId,
                refKey = refKey,
                onPress = onPress,
            ),
        )
    }

    fun cancelButton(
        label: String = "Cancel",
        refKey: String? = null,
        onPress: (() -> Unit)? = null,
    ) {
        cancel = ActionSheetCancel(label = label, refKey = refKey, onPress = onPress)
    }
}

fun actionSheet(
    title: String? = null,
    subtitle: String? = null,
    dismissible: Boolean = true,
    scopeId: String? = null,
    onDismiss: (() -> Unit)? = null,
    content: ActionSheetBuilder.() -> Unit,
): ManaSheet {
    val spec = ActionSheetBuilder()
    spec.content()
    return manaSheet(
        title = title,
        subtitle = subtitle,
        dismissible = dismissible,
        scopeId = scopeId,
        onDismiss = onDismiss,
    ) {
        for (opt in spec.options) {
            actionRow(
                title = opt.label,
                subtitle = opt.subtitle,
                icon = opt.icon,
                destructive = opt.danger,
                accessibilityId = opt.accessibilityId,
                refKey = opt.refKey,
            ) {
                if (opt.autoDismiss) {
                    dismiss()
                }
                opt.onPress()
            }
        }
        val cancelSpec = spec.cancel
        if (cancelSpec != null) {
            button(
                label = cancelSpec.label,
                style = ButtonStyle.SECONDARY,
                refKey = cancelSpec.refKey,
            ) {
                dismiss()
                cancelSpec.onPress?.invoke()
            }
        }
    }
}

internal object ManaWidgets {
    fun emitCheckboxRow(
        scope: ManaScope,
        batch: UiOps,
        theme: ManaUi.Theme,
        palette: ManaPalette,
        pos: ManaUi.RowPosition,
        title: String,
        subtitle: String?,
        state: ManaState<Boolean>,
        icon: String?,
        accessibilityId: String?,
        onChange: ((Boolean) -> Unit)?,
    ): CheckboxRowHandle {
        val checked = state.value
        val rowId = scope.nextId()
        val contentId = scope.nextId()
        val boxWrapId = scope.nextId()
        val boxId = scope.nextId()
        val checkWrapId = scope.nextId()

        val rowProps = ManaUi.baseRowProps(theme, pos, true)
        ManaUi.addProp(rowProps, "role", UiOps.Value.ofString("checkbox"))
        ManaUi.addProp(
            rowProps,
            "accessibilityLabel",
            UiOps.Value.ofString(if (!subtitle.isNullOrEmpty()) "$title, $subtitle" else title),
        )
        ManaUi.addProp(rowProps, "accessibilityChecked", UiOps.Value.ofBool(checked))
        if (!accessibilityId.isNullOrEmpty()) {
            ManaUi.addProp(rowProps, "accessibilityId", UiOps.Value.ofString(accessibilityId))
        }
        ManaUi.addProp(rowProps, "on", ManaUi.stringArray("press"))
        batch.createView(rowId, "view", rowProps)
        ManaUi.bindRowTheme(rowId, true)

        batch.createView(
            contentId,
            "view",
            ManaUi.props(
                ManaUi.prop("direction", UiOps.Value.ofString("row")),
                ManaUi.prop("padding", UiOps.Value.ofFloat4(12f, 12f, 12f, 12f)),
                ManaUi.prop("width", UiOps.Value.ofString("fill")),
                ManaUi.prop("minHeight", UiOps.Value.ofInt(56)),
                ManaUi.prop("align", UiOps.Value.ofString("center")),
            ),
        )

        val contentKids = ArrayList<Int>(3)
        if (!icon.isNullOrEmpty()) {
            contentKids.add(ManaUi.leadingIcon(batch, scope.allocator, icon, theme.primaryText, ColorToken.ICON_PRIMARY))
        }
        contentKids.add(ManaUi.labelBlock(batch, scope.allocator, theme, title, subtitle, theme.primaryText, ColorToken.TEXT_PRIMARY))

        batch.createView(
            boxWrapId,
            "view",
            ManaUi.props(
                ManaUi.prop("margin", UiOps.Value.ofFloat4(16f, 0f, 0f, 0f)),
                ManaUi.prop("align", UiOps.Value.ofString("center")),
                ManaUi.prop("justify", UiOps.Value.ofString("center")),
            ),
        )

        val motionSlot = if (ManaMotion.isWorkletSupported()) scope.allocMotionSlot() else -1

        val boxProps =
            ManaUi.props(
                ManaUi.prop("direction", UiOps.Value.ofString("row")),
                ManaUi.prop("width", UiOps.Value.ofInt(24)),
                ManaUi.prop("height", UiOps.Value.ofInt(24)),
                ManaUi.prop("align", UiOps.Value.ofString("center")),
                ManaUi.prop("justify", UiOps.Value.ofString("center")),
                ManaUi.prop("background", ManaUi.colorVal(if (checked) palette[ColorToken.BRAND_500] else 0L)),
                ManaUi.prop("radius", UiOps.Value.ofInt(6)),
                ManaUi.prop(
                    "borderColor",
                    ManaUi.colorVal(
                        if (checked) palette[ColorToken.BRAND_500] else palette[ColorToken.RADIO_UNCHECKED_BORDER],
                    ),
                ),
                ManaUi.prop("borderWidth", UiOps.Value.ofDouble(1.5)),
            )
        if (motionSlot >= 0) {
            ManaUi.addProp(
                boxProps,
                "worklet",
                ManaMotion.workletValue(
                    ManaMotion.checkboxBoxWorklet(
                        motionSlot,
                        palette[ColorToken.RADIO_UNCHECKED_BORDER],
                        palette[ColorToken.BRAND_500],
                    ),
                ),
            )
            ManaMotion.bindCheckboxThemeWorklet(scope.scopeId, boxId, motionSlot)
        }
        batch.createView(boxId, "view", boxProps)
        if (checked) {
            scope.bindTheme(boxId, "background", ColorToken.BRAND_500)
            scope.bindTheme(boxId, "borderColor", ColorToken.BRAND_500)
        } else {
            scope.bindTheme(boxId, "borderColor", ColorToken.RADIO_UNCHECKED_BORDER)
        }

        val checkWrapProps =
            ManaUi.props(
                ManaUi.prop("direction", UiOps.Value.ofString("row")),
                ManaUi.prop("align", UiOps.Value.ofString("center")),
                ManaUi.prop("justify", UiOps.Value.ofString("center")),
                ManaUi.prop("opacity", UiOps.Value.ofDouble(if (checked) 1.0 else 0.0)),
                ManaUi.prop("scaleFactor", UiOps.Value.ofDouble(if (checked) 1.0 else 0.5)),
            )
        if (motionSlot >= 0) {
            ManaUi.addProp(
                checkWrapProps,
                "worklet",
                ManaMotion.workletValue(ManaMotion.checkboxCheckWorklet(motionSlot)),
            )
        }
        batch.createView(checkWrapId, "view", checkWrapProps)

        val checkIconId =
            ManaUi.icon(
                batch,
                scope.allocator,
                ManaIcons.CHECK,
                16,
                ColorToken.BUTTON_PRIMARY_TEXT,
                palette,
            )

        batch.setChildren(checkWrapId, checkIconId)
        batch.setChildren(boxId, checkWrapId)
        batch.setChildren(boxWrapId, boxId)
        contentKids.add(boxWrapId)
        batch.setChildren(contentId, *contentKids.toIntArray())
        batch.setChildren(rowId, contentId)

        if (motionSlot >= 0) {
            batch.motion(*ManaMotion.initValueWords(motionSlot, if (checked) 1f else 0f))
        }

        val handle = CheckboxRowHandle(rowId, boxId, checkWrapId, checkIconId, motionSlot)

        scope.onPress(rowId) {
            val next = !state.value
            state.value = next
            onChange?.invoke(next)
        }

        state.observe(scope) { nextChecked ->
            if (nextChecked) {
                scope.rebindTheme(boxId, "background", ColorToken.BRAND_500)
                scope.rebindTheme(boxId, "borderColor", ColorToken.BRAND_500)
            } else {
                ThemeEngine.unbindView(boxId)
                scope.bindTheme(boxId, "borderColor", ColorToken.RADIO_UNCHECKED_BORDER)
                if (motionSlot >= 0) {
                    ManaMotion.bindCheckboxThemeWorklet(scope.scopeId, boxId, motionSlot)
                }
            }
            scope.emitPatch { ops ->
                if (!ManaMotion.triggerCheckbox(ops, rowId, motionSlot, nextChecked)) {
                    val pal = ThemeEngine.currentPalette()
                    ops.updateView(
                        rowId,
                        ManaUi.props(ManaUi.prop("accessibilityChecked", UiOps.Value.ofBool(nextChecked))),
                    )
                    ops.updateView(
                        boxId,
                        ManaUi.props(
                            ManaUi.prop(
                                "background",
                                ManaUi.colorVal(if (nextChecked) pal[ColorToken.BRAND_500] else 0L),
                            ),
                            ManaUi.prop(
                                "borderColor",
                                ManaUi.colorVal(
                                    if (nextChecked) pal[ColorToken.BRAND_500] else pal[ColorToken.RADIO_UNCHECKED_BORDER],
                                ),
                            ),
                        ),
                    )
                    ops.updateView(
                        checkWrapId,
                        ManaUi.props(
                            ManaUi.prop("opacity", UiOps.Value.ofDouble(if (nextChecked) 1.0 else 0.0)),
                            ManaUi.prop("scaleFactor", UiOps.Value.ofDouble(if (nextChecked) 1.0 else 0.5)),
                        ),
                    )
                }
            }
        }

        return handle
    }

    fun emitSliderRow(
        scope: ManaScope,
        batch: UiOps,
        theme: ManaUi.Theme,
        palette: ManaPalette,
        pos: ManaUi.RowPosition,
        title: String,
        range: IntRange,
        step: Int,
        state: ManaState<Int>,
        valueFormatter: (Int) -> String,
        subtitle: String?,
        accessibilityId: String?,
        onChange: ((Int) -> Unit)?,
    ): SliderRowHandle {
        val safeMin = range.first
        val safeMax = if (range.last > range.first) range.last else range.first + 1
        val safeStep = step.coerceAtLeast(1)
        val stepsCount = ((safeMax - safeMin) / safeStep).coerceAtLeast(1)

        fun clampToDomain(raw: Int): Int {
            val clamped = raw.coerceIn(safeMin, safeMax)
            val idx = ((clamped - safeMin) / safeStep).coerceIn(0, stepsCount)
            return (safeMin + idx * safeStep).coerceIn(safeMin, safeMax)
        }

        fun toStepIndex(domainVal: Int): Int = ((domainVal.coerceIn(safeMin, safeMax) - safeMin) / safeStep).coerceIn(0, stepsCount)

        fun fromStepIndex(stepIdx: Int): Int = (safeMin + stepIdx.coerceIn(0, stepsCount) * safeStep).coerceIn(safeMin, safeMax)

        val initialDomain = clampToDomain(state.value)
        val initialStep = toStepIndex(initialDomain)

        val rowId = scope.nextId()
        val contentId = scope.nextId()
        val headerRowId = scope.nextId()
        val valueTextId = scope.nextId()
        val sliderId = scope.nextId()

        val rowProps = ManaUi.baseRowProps(theme, pos, false)
        batch.createView(rowId, "view", rowProps)
        ManaUi.bindRowTheme(rowId, false)

        batch.createView(
            contentId,
            "view",
            ManaUi.props(
                ManaUi.prop("direction", UiOps.Value.ofString("column")),
                ManaUi.prop("padding", UiOps.Value.ofFloat4(12f, 12f, 12f, 12f)),
                ManaUi.prop("width", UiOps.Value.ofString("fill")),
                ManaUi.prop("gap", UiOps.Value.ofInt(8)),
                ManaUi.prop("align", UiOps.Value.ofString("stretch")),
            ),
        )

        batch.createView(
            headerRowId,
            "view",
            ManaUi.props(
                ManaUi.prop("direction", UiOps.Value.ofString("row")),
                ManaUi.prop("width", UiOps.Value.ofString("fill")),
                ManaUi.prop("align", UiOps.Value.ofString("center")),
                ManaUi.prop("gap", UiOps.Value.ofInt(8)),
            ),
        )

        val labelId =
            ManaUi.labelBlock(
                batch,
                scope.allocator,
                theme,
                title,
                subtitle,
                theme.primaryText,
                ColorToken.TEXT_PRIMARY,
            )

        batch.createView(
            valueTextId,
            "text",
            ManaUi.props(
                ManaUi.prop("text", UiOps.Value.ofString(valueFormatter(initialDomain))),
                ManaUi.prop("color", ManaUi.colorVal(palette[ColorToken.TEXT_MUTED])),
                ManaUi.prop("fontSize", UiOps.Value.ofInt(14)),
                ManaUi.prop("fontWeight", UiOps.Value.ofInt(600)),
                ManaUi.prop("lineHeight", UiOps.Value.ofInt(18)),
                ManaUi.prop("lineHeightExact", UiOps.Value.ofBool(true)),
            ),
        )
        scope.bindTheme(valueTextId, "color", ColorToken.TEXT_MUTED)
        batch.setChildren(headerRowId, labelId, valueTextId)

        val sliderProps =
            ManaUi.props(
                ManaUi.prop("width", UiOps.Value.ofString("fill")),
                ManaUi.prop("height", UiOps.Value.ofInt(36)),
                ManaUi.prop("steps", UiOps.Value.ofInt(stepsCount)),
                ManaUi.prop("value", UiOps.Value.ofInt(initialStep)),
                ManaUi.prop("minTrackTint", ManaUi.colorVal(palette[ColorToken.BRAND_500])),
                ManaUi.prop("maxTrackTint", ManaUi.colorVal(palette[ColorToken.BG_MOD_SUBTLE])),
                ManaUi.prop("thumbTint", ManaUi.colorVal(palette[ColorToken.SWITCH_THUMB])),
                ManaUi.prop("thumbSize", UiOps.Value.ofInt(20)),
                ManaUi.prop("stepHaptic", UiOps.Value.ofString("selection")),
                ManaUi.prop("accessibilityLabel", UiOps.Value.ofString(title)),
                ManaUi.prop("on", ManaUi.stringArray("change")),
            )
        if (!accessibilityId.isNullOrEmpty()) {
            ManaUi.addProp(sliderProps, "accessibilityId", UiOps.Value.ofString(accessibilityId))
        }
        batch.createView(sliderId, "slider", sliderProps)
        scope.bindTheme(sliderId, "minTrackTint", ColorToken.BRAND_500)
        scope.bindTheme(sliderId, "maxTrackTint", ColorToken.BG_MOD_SUBTLE)
        scope.bindTheme(sliderId, "thumbTint", ColorToken.SWITCH_THUMB)

        batch.setChildren(contentId, headerRowId, sliderId)
        batch.setChildren(rowId, contentId)

        var updatingFromSliderEvent = false
        scope.onChange(sliderId) { stepIdx ->
            val mapped = fromStepIndex(stepIdx)
            updatingFromSliderEvent = true
            try {
                state.value = mapped
            } finally {
                updatingFromSliderEvent = false
            }
            onChange?.invoke(mapped)
        }

        state.observe(scope) { nextVal ->
            val clamped = clampToDomain(nextVal)
            val nextStep = toStepIndex(clamped)
            scope.emitPatch { ops ->
                ops.updateView(
                    valueTextId,
                    ManaUi.props(ManaUi.prop("text", UiOps.Value.ofString(valueFormatter(clamped)))),
                )
                if (!updatingFromSliderEvent) {
                    ops.updateView(
                        sliderId,
                        ManaUi.props(ManaUi.prop("value", UiOps.Value.ofInt(nextStep))),
                    )
                }
            }
        }

        return SliderRowHandle(rowId, sliderId, valueTextId, safeMin..safeMax, safeStep)
    }

    fun emitTextInput(
        scope: ManaScope,
        batch: UiOps,
        palette: ManaPalette,
        label: String?,
        placeholder: String,
        state: ManaState<String>,
        helperText: String?,
        errorState: ManaState<String?>?,
        multiline: Boolean,
        clearable: Boolean,
        accessibilityId: String?,
        onChange: ((String) -> Unit)?,
        onSubmit: ((String) -> Unit)?,
    ): TextInputHandle {
        val rootId = scope.nextId()
        val boxId = scope.nextId()
        val inputId = scope.nextId()
        val clearBtnId = if (clearable) scope.nextId() else -1
        val hasMessageRow = !helperText.isNullOrEmpty() || errorState != null
        val messageTextId = if (hasMessageRow) scope.nextId() else -1

        val initialError = errorState?.value?.takeIf { it.isNotEmpty() }
        val hasInitialError = initialError != null
        val initialBorderToken = if (hasInitialError) ColorToken.DANGER else ColorToken.INPUT_BORDER

        batch.createView(
            rootId,
            "view",
            ManaUi.props(
                ManaUi.prop("direction", UiOps.Value.ofString("column")),
                ManaUi.prop("width", UiOps.Value.ofString("fill")),
                ManaUi.prop("gap", UiOps.Value.ofInt(6)),
                ManaUi.prop("align", UiOps.Value.ofString("stretch")),
            ),
        )

        val rootKids = ArrayList<Int>(3)
        if (!label.isNullOrEmpty()) {
            val labelId = scope.nextId()
            batch.createView(
                labelId,
                "text",
                ManaUi.props(
                    ManaUi.prop("text", UiOps.Value.ofString(label)),
                    ManaUi.prop("color", ManaUi.colorVal(palette[ColorToken.TEXT_SECONDARY])),
                    ManaUi.prop("fontSize", UiOps.Value.ofInt(13)),
                    ManaUi.prop("fontWeight", UiOps.Value.ofInt(600)),
                    ManaUi.prop("lineHeight", UiOps.Value.ofInt(18)),
                    ManaUi.prop("lineHeightExact", UiOps.Value.ofBool(true)),
                ),
            )
            scope.bindTheme(labelId, "color", ColorToken.TEXT_SECONDARY)
            rootKids.add(labelId)
        }

        val focusMotionSlot = if (ManaMotion.isWorkletSupported()) scope.allocMotionSlot() else -1
        val boxProps =
            ManaUi.props(
                ManaUi.prop("direction", UiOps.Value.ofString("row")),
                ManaUi.prop("width", UiOps.Value.ofString("fill")),
                ManaUi.prop("padding", UiOps.Value.ofFloat4(12f, 2f, 8f, 2f)),
                ManaUi.prop("align", UiOps.Value.ofString(if (multiline) "start" else "center")),
                ManaUi.prop("background", ManaUi.colorVal(palette[ColorToken.INPUT_BG])),
                ManaUi.prop("borderColor", ManaUi.colorVal(palette[initialBorderToken])),
                ManaUi.prop("borderWidth", UiOps.Value.ofDouble(1.0)),
                ManaUi.prop("radius", UiOps.Value.ofInt(8)),
            )
        if (focusMotionSlot >= 0) {
            val idleColor = palette[if (hasInitialError) ColorToken.DANGER else ColorToken.INPUT_BORDER]
            val focusColor = palette[if (hasInitialError) ColorToken.DANGER else ColorToken.BRAND_500]
            ManaUi.addProp(
                boxProps,
                "worklet",
                ManaMotion.workletValue(
                    ManaMotion.inputFocusWorklet(focusMotionSlot, idleColor, focusColor),
                ),
            )
            ManaMotion.bindInputFocusThemeWorklet(scope.scopeId, boxId, focusMotionSlot, hasInitialError)
        }
        batch.createView(boxId, "view", boxProps)
        scope.bindTheme(boxId, "background", ColorToken.INPUT_BG)
        scope.bindTheme(boxId, "borderColor", initialBorderToken)

        val inputProps =
            ManaUi.props(
                ManaUi.prop("padding", UiOps.Value.ofFloat4(0f, 8f, 4f, 8f)),
                ManaUi.prop("minHeight", UiOps.Value.ofInt(if (multiline) 84 else 40)),
                ManaUi.prop("flex", UiOps.Value.ofDouble(1.0)),
                ManaUi.prop("placeholderColor", ManaUi.colorVal(palette[ColorToken.INPUT_PLACEHOLDER])),
                ManaUi.prop("placeholder", UiOps.Value.ofString(placeholder)),
                ManaUi.prop("value", UiOps.Value.ofString(state.value)),
                ManaUi.prop("color", ManaUi.colorVal(palette[ColorToken.INPUT_TEXT])),
                ManaUi.prop("fontSize", UiOps.Value.ofInt(16)),
                ManaUi.prop("fontWeight", UiOps.Value.ofInt(400)),
                ManaUi.prop("multiline", UiOps.Value.ofBool(multiline)),
                ManaUi.prop("returnKey", UiOps.Value.ofString(if (multiline) "default" else "done")),
                ManaUi.prop("accessibilityLabel", UiOps.Value.ofString(label ?: placeholder)),
                ManaUi.prop("on", ManaUi.stringArray("changeText", "submit", "focus", "blur")),
            )
        if (!accessibilityId.isNullOrEmpty()) {
            ManaUi.addProp(inputProps, "accessibilityId", UiOps.Value.ofString(accessibilityId))
        }
        batch.createView(inputId, "input", inputProps)
        scope.bindTheme(inputId, "color", ColorToken.INPUT_TEXT)
        scope.bindTheme(inputId, "placeholderColor", ColorToken.INPUT_PLACEHOLDER)

        val boxKids = ArrayList<Int>(2)
        boxKids.add(inputId)

        if (clearBtnId >= 0) {
            val hasText = state.value.isNotEmpty()
            batch.createView(
                clearBtnId,
                "view",
                ManaUi.props(
                    ManaUi.prop("direction", UiOps.Value.ofString("row")),
                    ManaUi.prop("width", UiOps.Value.ofInt(28)),
                    ManaUi.prop("height", UiOps.Value.ofInt(28)),
                    ManaUi.prop("margin", UiOps.Value.ofFloat4(4f, if (multiline) 6f else 0f, 0f, 0f)),
                    ManaUi.prop("align", UiOps.Value.ofString("center")),
                    ManaUi.prop("justify", UiOps.Value.ofString("center")),
                    ManaUi.prop("radius", UiOps.Value.ofInt(14)),
                    ManaUi.prop("opacity", UiOps.Value.ofDouble(if (hasText) 1.0 else 0.0)),
                    ManaUi.prop("enabled", UiOps.Value.ofBool(hasText)),
                    ManaUi.prop("role", UiOps.Value.ofString("button")),
                    ManaUi.prop("accessibilityLabel", UiOps.Value.ofString("Clear text")),
                    ManaUi.prop("on", ManaUi.stringArray("press")),
                ),
            )
            val clearIconId =
                ManaUi.icon(
                    batch,
                    scope.allocator,
                    ManaIcons.X_SMALL,
                    16,
                    ColorToken.ICON_MUTED,
                    palette,
                )
            batch.setChildren(clearBtnId, clearIconId)
            boxKids.add(clearBtnId)

            scope.onPress(clearBtnId) {
                if (state.value.isNotEmpty()) {
                    state.value = ""
                    onChange?.invoke("")
                }
            }
        }

        batch.setChildren(boxId, *boxKids.toIntArray())
        rootKids.add(boxId)

        if (messageTextId >= 0) {
            val initialMsg = initialError ?: (helperText ?: "")
            val msgToken = if (hasInitialError) ColorToken.DANGER_TEXT else ColorToken.TEXT_MUTED
            batch.createView(
                messageTextId,
                "text",
                ManaUi.props(
                    ManaUi.prop("text", UiOps.Value.ofString(initialMsg)),
                    ManaUi.prop("color", ManaUi.colorVal(palette[msgToken])),
                    ManaUi.prop("fontSize", UiOps.Value.ofInt(12)),
                    ManaUi.prop("fontWeight", UiOps.Value.ofInt(400)),
                    ManaUi.prop("lineHeight", UiOps.Value.ofInt(16)),
                    ManaUi.prop("lineHeightExact", UiOps.Value.ofBool(true)),
                    ManaUi.prop("width", UiOps.Value.ofString("fill")),
                ),
            )
            scope.bindTheme(messageTextId, "color", msgToken)
            rootKids.add(messageTextId)
        }

        batch.setChildren(rootId, *rootKids.toIntArray())
        if (focusMotionSlot >= 0) {
            batch.motion(*ManaMotion.initValueWords(focusMotionSlot, 0f))
        }

        var updatingFromInputEvent = false
        var isFocused = false

        fun currentHasError(): Boolean = !errorState?.value.isNullOrEmpty()

        fun resolvedBorderToken(): ColorToken =
            when {
                currentHasError() -> ColorToken.DANGER
                isFocused -> ColorToken.BRAND_500
                else -> ColorToken.INPUT_BORDER
            }

        scope.onChangeText(inputId) { text ->
            updatingFromInputEvent = true
            try {
                state.value = text
            } finally {
                updatingFromInputEvent = false
            }
            onChange?.invoke(text)
        }
        scope.onSubmit(inputId) { text ->
            onSubmit?.invoke(if (text.isNotEmpty()) text else state.value)
        }
        scope.onFocus(inputId) {
            if (isFocused) return@onFocus
            isFocused = true
            val borderTok = resolvedBorderToken()
            scope.rebindTheme(boxId, "borderColor", borderTok)
            scope.emitPatch { ops ->
                if (focusMotionSlot >= 0) {
                    ManaMotion.triggerInputFocus(ops, focusMotionSlot, true)
                } else {
                    val pal = ThemeEngine.currentPalette()
                    ops.updateView(
                        boxId,
                        ManaUi.props(
                            ManaUi.prop("borderColor", ManaUi.colorVal(pal[borderTok])),
                            ManaUi.prop("borderWidth", UiOps.Value.ofDouble(1.5)),
                        ),
                    )
                }
            }
        }
        scope.onBlur(inputId) {
            if (!isFocused) return@onBlur
            isFocused = false
            val borderTok = resolvedBorderToken()
            scope.rebindTheme(boxId, "borderColor", borderTok)
            scope.emitPatch { ops ->
                if (focusMotionSlot >= 0) {
                    ManaMotion.triggerInputFocus(ops, focusMotionSlot, false)
                } else {
                    val pal = ThemeEngine.currentPalette()
                    ops.updateView(
                        boxId,
                        ManaUi.props(
                            ManaUi.prop("borderColor", ManaUi.colorVal(pal[borderTok])),
                            ManaUi.prop("borderWidth", UiOps.Value.ofDouble(1.0)),
                        ),
                    )
                }
            }
        }

        state.observe(scope) { next ->
            scope.emitPatch { ops ->
                if (!updatingFromInputEvent) {
                    ops.updateView(
                        inputId,
                        ManaUi.props(ManaUi.prop("value", UiOps.Value.ofString(next))),
                    )
                }
                if (clearBtnId >= 0) {
                    val hasText = next.isNotEmpty()
                    ops.updateView(
                        clearBtnId,
                        ManaUi.props(
                            ManaUi.prop("opacity", UiOps.Value.ofDouble(if (hasText) 1.0 else 0.0)),
                            ManaUi.prop("enabled", UiOps.Value.ofBool(hasText)),
                        ),
                    )
                }
            }
        }

        if (errorState != null && messageTextId >= 0) {
            errorState.observe(scope) { nextErr ->
                val errStr = nextErr?.takeIf { it.isNotEmpty() }
                val isErr = errStr != null
                val borderTok =
                    when {
                        isErr -> ColorToken.DANGER
                        isFocused -> ColorToken.BRAND_500
                        else -> ColorToken.INPUT_BORDER
                    }
                val textTok = if (isErr) ColorToken.DANGER_TEXT else ColorToken.TEXT_MUTED
                val pal = ThemeEngine.currentPalette()
                scope.rebindTheme(boxId, "borderColor", borderTok)
                scope.rebindTheme(messageTextId, "color", textTok)
                if (focusMotionSlot >= 0) {
                    ManaMotion.bindInputFocusThemeWorklet(scope.scopeId, boxId, focusMotionSlot, isErr)
                }
                scope.emitPatch { ops ->
                    val boxPatch =
                        ManaUi.props(
                            ManaUi.prop("borderColor", ManaUi.colorVal(pal[borderTok])),
                        )
                    if (focusMotionSlot >= 0) {
                        val idleColor = pal[if (isErr) ColorToken.DANGER else ColorToken.INPUT_BORDER]
                        val focusColor = pal[if (isErr) ColorToken.DANGER else ColorToken.BRAND_500]
                        ManaUi.addProp(
                            boxPatch,
                            "worklet",
                            ManaMotion.workletValue(
                                ManaMotion.inputFocusWorklet(focusMotionSlot, idleColor, focusColor),
                            ),
                        )
                    }
                    ops.updateView(boxId, boxPatch)
                    ops.updateView(
                        messageTextId,
                        ManaUi.props(
                            ManaUi.prop("text", UiOps.Value.ofString(errStr ?: (helperText ?: ""))),
                            ManaUi.prop("color", ManaUi.colorVal(pal[textTok])),
                        ),
                    )
                }
            }
        }

        return TextInputHandle(rootId, boxId, inputId, clearBtnId, messageTextId, focusMotionSlot)
    }

    fun emitCallout(
        scope: ManaScope,
        batch: UiOps,
        palette: ManaPalette,
        text: String,
        title: String?,
        tone: CalloutTone,
        icon: String?,
    ): CalloutHandle {
        val rootId = scope.nextId()
        val textColId = scope.nextId()
        val bodyId = scope.nextId()
        val bgToken = tone.bgToken()
        val accentToken = tone.accentToken()
        val resolvedIcon = if (!icon.isNullOrEmpty()) icon else tone.defaultIcon()

        batch.createView(
            rootId,
            "view",
            ManaUi.props(
                ManaUi.prop("direction", UiOps.Value.ofString("row")),
                ManaUi.prop("width", UiOps.Value.ofString("fill")),
                ManaUi.prop("padding", UiOps.Value.ofFloat4(12f, 12f, 12f, 12f)),
                ManaUi.prop("gap", UiOps.Value.ofInt(10)),
                ManaUi.prop("align", UiOps.Value.ofString("start")),
                ManaUi.prop("background", ManaUi.colorVal(palette[bgToken])),
                ManaUi.prop("borderColor", ManaUi.colorVal(palette[accentToken])),
                ManaUi.prop("borderWidth", UiOps.Value.ofInt(1)),
                ManaUi.prop("radius", UiOps.Value.ofInt(12)),
            ),
        )
        scope.bindTheme(rootId, "background", bgToken)
        scope.bindTheme(rootId, "borderColor", accentToken)

        val iconId =
            ManaUi.icon(
                batch,
                scope.allocator,
                resolvedIcon,
                20,
                20,
                palette[accentToken],
                accentToken,
                0f,
                1f,
                0f,
                0f,
            )

        batch.createView(
            textColId,
            "view",
            ManaUi.props(
                ManaUi.prop("direction", UiOps.Value.ofString("column")),
                ManaUi.prop("flex", UiOps.Value.ofDouble(1.0)),
                ManaUi.prop("gap", UiOps.Value.ofInt(2)),
                ManaUi.prop("align", UiOps.Value.ofString("stretch")),
            ),
        )

        val colKids = ArrayList<Int>(2)
        var titleId = -1
        if (!title.isNullOrEmpty()) {
            titleId = scope.nextId()
            batch.createView(
                titleId,
                "text",
                ManaUi.props(
                    ManaUi.prop("text", UiOps.Value.ofString(title)),
                    ManaUi.prop("color", ManaUi.colorVal(palette[ColorToken.TEXT_PRIMARY])),
                    ManaUi.prop("fontSize", UiOps.Value.ofInt(14)),
                    ManaUi.prop("fontWeight", UiOps.Value.ofInt(600)),
                    ManaUi.prop("lineHeight", UiOps.Value.ofInt(18)),
                    ManaUi.prop("lineHeightExact", UiOps.Value.ofBool(true)),
                    ManaUi.prop("width", UiOps.Value.ofString("fill")),
                ),
            )
            scope.bindTheme(titleId, "color", ColorToken.TEXT_PRIMARY)
            colKids.add(titleId)
        }

        batch.createView(
            bodyId,
            "text",
            ManaUi.props(
                ManaUi.prop("text", UiOps.Value.ofString(text)),
                ManaUi.prop("color", ManaUi.colorVal(palette[ColorToken.TEXT_PRIMARY])),
                ManaUi.prop("fontSize", UiOps.Value.ofInt(13)),
                ManaUi.prop("fontWeight", UiOps.Value.ofInt(400)),
                ManaUi.prop("lineHeight", UiOps.Value.ofInt(18)),
                ManaUi.prop("lineHeightExact", UiOps.Value.ofBool(true)),
                ManaUi.prop("width", UiOps.Value.ofString("fill")),
            ),
        )
        scope.bindTheme(bodyId, "color", ColorToken.TEXT_PRIMARY)
        colKids.add(bodyId)

        batch.setChildren(textColId, *colKids.toIntArray())
        batch.setChildren(rootId, iconId, textColId)
        return CalloutHandle(rootId, iconId, titleId, bodyId)
    }

    fun emitAvatar(
        scope: ManaScope,
        batch: UiOps,
        palette: ManaPalette,
        uri: String?,
        initials: String?,
        size: Int,
        badgeTone: AvatarBadgeTone?,
        label: String?,
        subtitle: String?,
        onPress: (() -> Unit)?,
    ): AvatarHandle {
        val safeSize = size.coerceIn(20, 128)
        val rootId = scope.nextId()
        val stackId = scope.nextId()
        val circleId = scope.nextId()

        val rootProps =
            ManaUi.props(
                ManaUi.prop("direction", UiOps.Value.ofString("row")),
                ManaUi.prop("align", UiOps.Value.ofString("center")),
                ManaUi.prop("gap", UiOps.Value.ofInt(12)),
            )
        if (onPress != null) {
            ManaUi.addProp(rootProps, "role", UiOps.Value.ofString("button"))
            ManaUi.addProp(rootProps, "on", ManaUi.stringArray("press"))
            if (!label.isNullOrEmpty()) {
                ManaUi.addProp(rootProps, "accessibilityLabel", UiOps.Value.ofString(label))
            }
        }
        batch.createView(rootId, "view", rootProps)
        if (onPress != null) {
            scope.onPress(rootId, onPress)
        }

        batch.createView(
            stackId,
            "stack",
            ManaUi.props(
                ManaUi.prop("width", UiOps.Value.ofInt(safeSize)),
                ManaUi.prop("height", UiOps.Value.ofInt(safeSize)),
            ),
        )

        val hasUri = !uri.isNullOrEmpty()
        val circleBgTok = if (hasUri) ColorToken.BG_BASE_TERTIARY else ColorToken.BRAND_500
        batch.createView(
            circleId,
            "view",
            ManaUi.props(
                ManaUi.prop("direction", UiOps.Value.ofString("row")),
                ManaUi.prop("width", UiOps.Value.ofInt(safeSize)),
                ManaUi.prop("height", UiOps.Value.ofInt(safeSize)),
                ManaUi.prop("radius", UiOps.Value.ofInt(9999)),
                ManaUi.prop("clip", UiOps.Value.ofBool(true)),
                ManaUi.prop("align", UiOps.Value.ofString("center")),
                ManaUi.prop("justify", UiOps.Value.ofString("center")),
                ManaUi.prop("background", ManaUi.colorVal(palette[circleBgTok])),
            ),
        )
        scope.bindTheme(circleId, "background", circleBgTok)

        var imageId = -1
        var initialsTextId = -1
        if (hasUri) {
            imageId = scope.nextId()
            batch.createView(
                imageId,
                "image",
                ManaUi.props(
                    ManaUi.prop("uri", UiOps.Value.ofString(uri)),
                    ManaUi.prop("width", UiOps.Value.ofInt(safeSize)),
                    ManaUi.prop("height", UiOps.Value.ofInt(safeSize)),
                    ManaUi.prop("radius", UiOps.Value.ofInt(9999)),
                    ManaUi.prop("scale", UiOps.Value.ofString("cover")),
                ),
            )
            batch.setChildren(circleId, imageId)
        } else {
            initialsTextId = scope.nextId()
            val formattedInitials = formatInitials(initials)
            val fontSize = (safeSize * 0.4).toInt().coerceIn(11, 24)
            batch.createView(
                initialsTextId,
                "text",
                ManaUi.props(
                    ManaUi.prop("text", UiOps.Value.ofString(formattedInitials)),
                    ManaUi.prop("color", ManaUi.colorVal(palette[ColorToken.BUTTON_PRIMARY_TEXT])),
                    ManaUi.prop("fontSize", UiOps.Value.ofInt(fontSize)),
                    ManaUi.prop("fontWeight", UiOps.Value.ofInt(700)),
                    ManaUi.prop("textAlign", UiOps.Value.ofString("center")),
                ),
            )
            scope.bindTheme(initialsTextId, "color", ColorToken.BUTTON_PRIMARY_TEXT)
            batch.setChildren(circleId, initialsTextId)
        }

        val stackKids = ArrayList<Int>(2)
        stackKids.add(circleId)

        var badgeId = -1
        if (badgeTone != null) {
            badgeId = scope.nextId()
            val badgeSize = (safeSize / 3).coerceIn(10, 18)
            val badgeTok = badgeTone.colorToken()
            batch.createView(
                badgeId,
                "view",
                ManaUi.props(
                    ManaUi.prop("absolute", UiOps.Value.ofBool(true)),
                    ManaUi.prop("paddingBox", UiOps.Value.ofBool(true)),
                    ManaUi.prop("gravity", UiOps.Value.ofString("bottom|end")),
                    ManaUi.prop("width", UiOps.Value.ofInt(badgeSize)),
                    ManaUi.prop("height", UiOps.Value.ofInt(badgeSize)),
                    ManaUi.prop("radius", UiOps.Value.ofInt(9999)),
                    ManaUi.prop("background", ManaUi.colorVal(palette[badgeTok])),
                    ManaUi.prop("borderColor", ManaUi.colorVal(palette[ColorToken.BG_BASE_PRIMARY])),
                    ManaUi.prop("borderWidth", UiOps.Value.ofInt(2)),
                ),
            )
            scope.bindTheme(badgeId, "background", badgeTok)
            scope.bindTheme(badgeId, "borderColor", ColorToken.BG_BASE_PRIMARY)
            stackKids.add(badgeId)
        }
        batch.setChildren(stackId, *stackKids.toIntArray())

        val rootKids = ArrayList<Int>(2)
        rootKids.add(stackId)
        if (!label.isNullOrEmpty()) {
            val colId = scope.nextId()
            val labelId = scope.nextId()
            batch.createView(
                colId,
                "view",
                ManaUi.props(
                    ManaUi.prop("direction", UiOps.Value.ofString("column")),
                    ManaUi.prop("flex", UiOps.Value.ofDouble(1.0)),
                    ManaUi.prop("gap", UiOps.Value.ofInt(2)),
                ),
            )
            batch.createView(
                labelId,
                "text",
                ManaUi.props(
                    ManaUi.prop("text", UiOps.Value.ofString(label)),
                    ManaUi.prop("color", ManaUi.colorVal(palette[ColorToken.TEXT_PRIMARY])),
                    ManaUi.prop("fontSize", UiOps.Value.ofInt(16)),
                    ManaUi.prop("fontWeight", UiOps.Value.ofInt(600)),
                    ManaUi.prop("lineHeight", UiOps.Value.ofInt(20)),
                    ManaUi.prop("lineHeightExact", UiOps.Value.ofBool(true)),
                ),
            )
            scope.bindTheme(labelId, "color", ColorToken.TEXT_PRIMARY)
            val textKids = ArrayList<Int>(2)
            textKids.add(labelId)
            if (!subtitle.isNullOrEmpty()) {
                val subId = scope.nextId()
                batch.createView(
                    subId,
                    "text",
                    ManaUi.props(
                        ManaUi.prop("text", UiOps.Value.ofString(subtitle)),
                        ManaUi.prop("color", ManaUi.colorVal(palette[ColorToken.TEXT_MUTED])),
                        ManaUi.prop("fontSize", UiOps.Value.ofInt(13)),
                        ManaUi.prop("fontWeight", UiOps.Value.ofInt(400)),
                        ManaUi.prop("lineHeight", UiOps.Value.ofInt(17)),
                        ManaUi.prop("lineHeightExact", UiOps.Value.ofBool(true)),
                    ),
                )
                scope.bindTheme(subId, "color", ColorToken.TEXT_MUTED)
                textKids.add(subId)
            }
            batch.setChildren(colId, *textKids.toIntArray())
            rootKids.add(colId)
        }

        batch.setChildren(rootId, *rootKids.toIntArray())
        return AvatarHandle(rootId, circleId, imageId, initialsTextId, badgeId)
    }

    private fun formatInitials(raw: String?): String {
        val trimmed = raw?.trim() ?: ""
        if (trimmed.isEmpty()) return "?"
        val sb = StringBuilder(2)
        for (c in trimmed) {
            if (!c.isWhitespace()) {
                sb.append(c.uppercaseChar())
                if (sb.length == 2) break
            }
        }
        return if (sb.isEmpty()) "?" else sb.toString()
    }
}
