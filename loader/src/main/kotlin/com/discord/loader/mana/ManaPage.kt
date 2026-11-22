package com.discord.loader.mana

import com.discord.loader.Loader
import com.discord.loader.ManaAnimator
import com.discord.loader.ManaUi
import com.discord.loader.UiOps

enum class ButtonStyle {
    PRIMARY,
    SECONDARY,
    DANGER,
    GHOST,
    LINK,
    ;

    internal fun toManaVariant(): ManaUi.ButtonVariant =
        when (this) {
            PRIMARY -> ManaUi.ButtonVariant.PRIMARY
            SECONDARY -> ManaUi.ButtonVariant.SECONDARY
            DANGER -> ManaUi.ButtonVariant.DANGER
            GHOST -> ManaUi.ButtonVariant.GHOST
            LINK -> ManaUi.ButtonVariant.LINK
        }
}

enum class ButtonSize {
    SM,
    MD,
    LG,
    ;

    internal fun toManaSize(): ManaUi.ButtonSize =
        when (this) {
            SM -> ManaUi.ButtonSize.SM
            MD -> ManaUi.ButtonSize.MD
            LG -> ManaUi.ButtonSize.LG
        }
}

enum class TextStyle {
    HEADING,
    BODY,
    CAPTION,
    MUTED,
}

enum class BadgeTone {
    BRAND,
    DANGER,
    POSITIVE,
    SUBTLE,
    ;

    internal fun bgToken(): ColorToken =
        when (this) {
            BRAND -> ColorToken.BRAND_500
            DANGER -> ColorToken.DANGER
            POSITIVE -> ColorToken.POSITIVE
            SUBTLE -> ColorToken.BG_MOD_SUBTLE
        }

    internal fun fgToken(): ColorToken =
        when (this) {
            SUBTLE -> ColorToken.TEXT_PRIMARY
            BRAND, DANGER, POSITIVE -> ColorToken.BUTTON_PRIMARY_TEXT
        }
}

data class RadioOption<T>(
    val value: T,
    val title: String,
    val subtitle: String? = null,
    val accessibilityId: String? = null,
)

internal class HeaderActionSpec(
    val icon: String,
    val label: String,
    val accessibilityId: String?,
    val refKey: String?,
    val onPress: () -> Unit,
)

internal class RestartBannerSpec(
    val visibleState: ManaState<Boolean>,
    val messageState: ManaState<String>,
    val title: String,
    val buttonLabel: String,
    val buttonAccessibilityId: String,
    val buttonRefKey: String?,
    val onRestart: () -> Unit,
)

class ManaPage(
    val title: String,
    val subtitle: String? = null,
    val scopeId: String? = null,
    val titleAccessibilityId: String? = null,
    internal val content: ManaPageBuilder.() -> Unit,
)

fun manaPage(
    title: String,
    subtitle: String? = null,
    scopeId: String? = null,
    titleAccessibilityId: String? = null,
    content: ManaPageBuilder.() -> Unit,
): ManaPage =
    ManaPage(
        title = title,
        subtitle = subtitle,
        scopeId = scopeId,
        titleAccessibilityId = titleAccessibilityId,
        content = content,
    )

@ManaDsl
open class ManaSectionBuilder internal constructor(
    val context: ManaContext,
    val scope: ManaScope,
    internal val refs: MutableMap<String, Any>,
) {
    val router: ManaRouter get() = context.router
    val palette: ManaPalette get() = context.palette

    internal sealed class Entry {
        class TableRow(
            val emit: (UiOps, ManaUi.Theme, ManaPalette, ManaUi.RowPosition) -> Int,
        ) : Entry()

        class Block(
            val emit: (UiOps, ManaUi.Theme, ManaPalette) -> Int,
        ) : Entry()
    }

    internal val entries = ArrayList<Entry>()
    internal var hasRadioGroup: Boolean = false

    fun navRow(
        title: String,
        subtitle: String? = null,
        icon: String? = null,
        trailing: String? = null,
        trailingState: ManaState<String>? = null,
        badge: String? = null,
        badgeState: ManaState<String>? = null,
        badgeTone: BadgeTone = BadgeTone.BRAND,
        accessibilityId: String? = null,
        refKey: String? = null,
        onPress: () -> Unit,
    ) {
        entries.add(
            Entry.TableRow { batch, theme, pal, pos ->
                val initialTrailing = trailingState?.value ?: trailing
                val initialBadge = badgeState?.value ?: badge
                val badgeBgTok = badgeTone.bgToken()
                val badgeFgTok = badgeTone.fgToken()
                val handle =
                    ManaUi.navRowHandle(
                        batch,
                        scope.allocator,
                        theme,
                        icon,
                        title,
                        subtitle,
                        initialTrailing,
                        trailingState != null,
                        initialBadge,
                        pal[badgeBgTok],
                        badgeBgTok,
                        pal[badgeFgTok],
                        badgeFgTok,
                        accessibilityId,
                        pos,
                    )
                scope.onPress(handle.rowId, onPress)
                if (trailingState != null && handle.trailingTextId >= 0) {
                    trailingState.observe(scope) { next ->
                        scope.emitPatch { ops ->
                            ops.updateView(
                                handle.trailingTextId,
                                ManaUi.props(ManaUi.prop("text", UiOps.Value.ofString(next))),
                            )
                        }
                    }
                }
                if (badgeState != null && handle.badgeTextId >= 0) {
                    badgeState.observe(scope) { next ->
                        scope.emitPatch { ops ->
                            ops.updateView(
                                handle.badgeTextId,
                                ManaUi.props(ManaUi.prop("text", UiOps.Value.ofString(next))),
                            )
                        }
                    }
                }
                if (refKey != null) {
                    refs[refKey] = handle
                }
                handle.rowId
            },
        )
    }

    fun navToggleRow(
        title: String,
        subtitle: String? = null,
        icon: String? = null,
        state: ManaState<Boolean>,
        accessibilityId: String? = null,
        refKey: String? = null,
        onOpen: () -> Unit,
        onToggle: ((Boolean) -> Unit)? = null,
    ) {
        entries.add(
            Entry.TableRow { batch, theme, _, pos ->
                val handle =
                    ManaUi.toggleRow(
                        batch,
                        scope.allocator,
                        theme,
                        icon,
                        title,
                        subtitle,
                        state.value,
                        accessibilityId,
                        pos,
                    )

                scope.onPress(handle.rowId) { onOpen() }
                scope.onPress(handle.toggle.trackId) {
                    val next = !state.value
                    state.value = next
                    onToggle?.invoke(next)
                }

                state.observe(scope) { checked ->
                    scope.rebindTheme(
                        handle.toggle.trackId,
                        "background",
                        if (checked) ColorToken.SWITCH_ON_BG else ColorToken.SWITCH_OFF_BG,
                    )
                    scope.rebindTheme(
                        handle.toggle.trackId,
                        "borderColor",
                        if (checked) ColorToken.SWITCH_ON_BORDER else ColorToken.SWITCH_OFF_BORDER,
                    )
                    if (handle.toggle.motionSlot >= 0) {
                        scope.emitPatch { ops ->
                            ManaMotion.triggerToggleRow(ops, handle, checked)
                        }
                    } else {
                        ManaAnimator.animateToggleRow(handle, checked)
                    }
                }

                if (refKey != null) {
                    refs[refKey] = handle
                }
                handle.rowId
            },
        )
    }

    fun toggleRow(
        title: String,
        subtitle: String? = null,
        icon: String? = null,
        state: ManaState<Boolean>,
        accessibilityId: String? = null,
        refKey: String? = null,
        onChanged: ((Boolean) -> Unit)? = null,
    ) {
        entries.add(
            Entry.TableRow { batch, theme, _, pos ->
                val handle =
                    ManaUi.toggleRow(
                        batch,
                        scope.allocator,
                        theme,
                        icon,
                        title,
                        subtitle,
                        state.value,
                        accessibilityId,
                        pos,
                    )
                val flipAction = {
                    val next = !state.value
                    state.value = next
                    onChanged?.invoke(next)
                }

                scope.onPress(handle.rowId) { flipAction() }
                scope.onPress(handle.toggle.trackId) { flipAction() }

                state.observe(scope) { checked ->
                    scope.rebindTheme(
                        handle.toggle.trackId,
                        "background",
                        if (checked) ColorToken.SWITCH_ON_BG else ColorToken.SWITCH_OFF_BG,
                    )
                    scope.rebindTheme(
                        handle.toggle.trackId,
                        "borderColor",
                        if (checked) ColorToken.SWITCH_ON_BORDER else ColorToken.SWITCH_OFF_BORDER,
                    )
                    if (handle.toggle.motionSlot >= 0) {
                        scope.emitPatch { ops ->
                            ManaMotion.triggerToggleRow(ops, handle, checked)
                        }
                    } else {
                        ManaAnimator.animateToggleRow(handle, checked)
                    }
                }

                if (refKey != null) {
                    refs[refKey] = handle
                }
                handle.rowId
            },
        )
    }

    fun toggleRow(
        title: String,
        subtitle: String? = null,
        icon: String? = null,
        checked: Boolean,
        accessibilityId: String? = null,
        refKey: String? = null,
        onChanged: (Boolean) -> Unit,
    ) {
        toggleRow(
            title = title,
            subtitle = subtitle,
            icon = icon,
            state = booleanState(checked),
            accessibilityId = accessibilityId,
            refKey = refKey,
            onChanged = onChanged,
        )
    }

    fun valueRow(
        title: String,
        value: String,
        subtitle: String? = null,
        icon: String? = null,
        refKey: String? = null,
        onPress: (() -> Unit)? = null,
    ) {
        entries.add(
            Entry.TableRow { batch, theme, _, pos ->
                val handle =
                    ManaUi.valueRowHandle(
                        batch,
                        scope.allocator,
                        theme,
                        icon,
                        title,
                        subtitle,
                        value,
                        false,
                        onPress != null,
                        pos,
                    )
                if (onPress != null) {
                    scope.onPress(handle.rowId, onPress)
                }
                if (refKey != null) {
                    refs[refKey] = handle
                }
                handle.rowId
            },
        )
    }

    fun valueRow(
        title: String,
        value: ManaState<String>,
        subtitle: String? = null,
        icon: String? = null,
        refKey: String? = null,
        onPress: (() -> Unit)? = null,
    ) {
        entries.add(
            Entry.TableRow { batch, theme, _, pos ->
                val handle =
                    ManaUi.valueRowHandle(
                        batch,
                        scope.allocator,
                        theme,
                        icon,
                        title,
                        subtitle,
                        value.value,
                        true,
                        onPress != null,
                        pos,
                    )
                if (onPress != null) {
                    scope.onPress(handle.rowId, onPress)
                }
                if (handle.valueTextId >= 0) {
                    value.observe(scope) { next ->
                        scope.emitPatch { ops ->
                            ops.updateView(
                                handle.valueTextId,
                                ManaUi.props(ManaUi.prop("text", UiOps.Value.ofString(next))),
                            )
                        }
                    }
                }
                if (refKey != null) {
                    refs[refKey] = handle
                }
                handle.rowId
            },
        )
    }

    fun actionRow(
        title: String,
        subtitle: String? = null,
        icon: String? = null,
        destructive: Boolean = false,
        accessibilityId: String? = null,
        refKey: String? = null,
        onPress: () -> Unit,
    ) {
        entries.add(
            Entry.TableRow { batch, theme, _, pos ->
                val rowId =
                    ManaUi.actionRow(
                        batch,
                        scope.allocator,
                        theme,
                        icon,
                        title,
                        subtitle,
                        destructive,
                        accessibilityId,
                        pos,
                    )
                scope.onPress(rowId, onPress)
                if (refKey != null) {
                    refs[refKey] = rowId
                }
                rowId
            },
        )
    }

    fun <T> radioGroup(
        options: List<RadioOption<T>>,
        selected: ManaState<T>,
        refKey: String? = null,
        onSelected: ((T) -> Unit)? = null,
    ) {
        if (options.isEmpty()) return
        hasRadioGroup = true
        val handles = arrayOfNulls<ManaUi.RadioRowHandle>(options.size)
        var currentSelectedIdx = options.indexOfFirst { it.value == selected.value }

        for (index in options.indices) {
            val opt = options[index]
            entries.add(
                Entry.TableRow { batch, theme, _, pos ->
                    val isSelected = (opt.value == selected.value)
                    val handle =
                        ManaUi.radioRow(
                            batch,
                            scope.allocator,
                            theme,
                            opt.title,
                            opt.subtitle,
                            isSelected,
                            opt.accessibilityId,
                            pos,
                        )
                    handles[index] = handle
                    scope.onPress(handle.rowId) {
                        if (selected.value != opt.value) {
                            selected.value = opt.value
                        }
                        onSelected?.invoke(opt.value)
                    }
                    if (index == options.lastIndex) {
                        if (refKey != null) {
                            val completed = ArrayList<ManaUi.RadioRowHandle>(options.size)
                            for (h in handles) {
                                if (h != null) completed.add(h)
                            }
                            refs[refKey] = completed
                        }
                        selected.observe(scope) { nextVal ->
                            val prevIdx = currentSelectedIdx
                            val nextIdx = options.indexOfFirst { it.value == nextVal }
                            if (prevIdx == nextIdx) return@observe
                            currentSelectedIdx = nextIdx
                            val t = ThemeEngine.currentTheme()
                            scope.emitPatch { ops ->
                                if (prevIdx in handles.indices) {
                                    val prevHandle = handles[prevIdx]
                                    if (prevHandle != null) {
                                        rebindRadioTheme(prevHandle, false)
                                        if (!ManaMotion.triggerRadioRow(ops, prevHandle, false)) {
                                            ManaUi.updateRadioRow(ops, prevHandle, false, t)
                                        }
                                    }
                                }
                                if (nextIdx in handles.indices) {
                                    val nextHandle = handles[nextIdx]
                                    if (nextHandle != null) {
                                        rebindRadioTheme(nextHandle, true)
                                        if (!ManaMotion.triggerRadioRow(ops, nextHandle, true)) {
                                            ManaUi.updateRadioRow(ops, nextHandle, true, t)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    handle.rowId
                },
            )
        }
    }

    private fun rebindRadioTheme(
        handle: ManaUi.RadioRowHandle,
        selected: Boolean,
    ) {
        if (selected) {
            scope.rebindTheme(handle.outerCircleId, "background", ColorToken.BRAND_500)
            scope.rebindTheme(handle.outerCircleId, "borderColor", ColorToken.BRAND_500)
        } else {
            ThemeEngine.unbindView(handle.outerCircleId)
            scope.bindTheme(handle.outerCircleId, "borderColor", ColorToken.RADIO_UNCHECKED_BORDER)
            if (handle.motionSlot >= 0) {
                ManaMotion.bindRadioThemeWorklet(scope.scopeId, handle.outerCircleId, handle.motionSlot)
            }
        }
    }

    fun checkboxRow(
        title: String,
        subtitle: String? = null,
        state: ManaState<Boolean>,
        icon: String? = null,
        accessibilityId: String? = null,
        refKey: String? = null,
        onChange: ((Boolean) -> Unit)? = null,
    ) {
        entries.add(
            Entry.TableRow { batch, theme, pal, pos ->
                val handle =
                    ManaWidgets.emitCheckboxRow(
                        scope = scope,
                        batch = batch,
                        theme = theme,
                        palette = pal,
                        pos = pos,
                        title = title,
                        subtitle = subtitle,
                        state = state,
                        icon = icon,
                        accessibilityId = accessibilityId,
                        onChange = onChange,
                    )
                if (refKey != null) {
                    refs[refKey] = handle
                }
                handle.rowId
            },
        )
    }

    fun checkboxRow(
        title: String,
        subtitle: String? = null,
        checked: Boolean,
        icon: String? = null,
        accessibilityId: String? = null,
        refKey: String? = null,
        onChange: (Boolean) -> Unit,
    ) {
        checkboxRow(
            title = title,
            subtitle = subtitle,
            state = booleanState(checked),
            icon = icon,
            accessibilityId = accessibilityId,
            refKey = refKey,
            onChange = onChange,
        )
    }

    fun sliderRow(
        title: String,
        range: IntRange,
        step: Int = 1,
        state: ManaState<Int>,
        valueFormatter: (Int) -> String = { it.toString() },
        subtitle: String? = null,
        accessibilityId: String? = null,
        refKey: String? = null,
        onChange: ((Int) -> Unit)? = null,
    ) {
        entries.add(
            Entry.TableRow { batch, theme, pal, pos ->
                val handle =
                    ManaWidgets.emitSliderRow(
                        scope = scope,
                        batch = batch,
                        theme = theme,
                        palette = pal,
                        pos = pos,
                        title = title,
                        range = range,
                        step = step,
                        state = state,
                        valueFormatter = valueFormatter,
                        subtitle = subtitle,
                        accessibilityId = accessibilityId,
                        onChange = onChange,
                    )
                if (refKey != null) {
                    refs[refKey] = handle
                }
                handle.rowId
            },
        )
    }

    fun segmentedControl(
        labels: List<String>,
        selectedIndex: ManaState<Int>,
        refKey: String? = null,
        onSelected: ((Int) -> Unit)? = null,
    ) {
        if (labels.isEmpty()) return
        entries.add(
            Entry.Block { batch, theme, _ ->
                val arr = Array(labels.size) { i -> labels[i] }
                val count = labels.size
                val initialIdx = selectedIndex.value.coerceIn(0, labels.lastIndex)
                val handle = ManaUi.segmentedControl(batch, scope.allocator, theme, arr, initialIdx)

                var measuredTabWidth = 0.0
                var measuredHeight = 0.0

                if (handle.trackId >= 0 && handle.pillId >= 0) {
                    scope.onLayout(handle.trackId) { widthDp, heightDp ->
                        if (widthDp <= 0.0) return@onLayout
                        val tabW = widthDp / count.toDouble()
                        val pillH = if (heightDp > 0.0) heightDp else 34.0
                        val sizeChanged =
                            kotlin.math.abs(tabW - measuredTabWidth) > 0.25 ||
                                kotlin.math.abs(pillH - measuredHeight) > 0.25
                        measuredTabWidth = tabW
                        measuredHeight = pillH
                        if (!sizeChanged) return@onLayout

                        val currentIdx = selectedIndex.value.coerceIn(0, labels.lastIndex)
                        val targetX = currentIdx * tabW
                        scope.emitPatch { ops ->
                            val pillPatch =
                                ManaUi.props(
                                    ManaUi.prop("width", UiOps.Value.ofDouble(tabW)),
                                    ManaUi.prop("height", UiOps.Value.ofDouble(pillH)),
                                    ManaUi.prop("opacity", UiOps.Value.ofDouble(1.0)),
                                )
                            if (handle.motionSlot < 0) {
                                ManaUi.addProp(pillPatch, "translateX", UiOps.Value.ofDouble(targetX))
                            }
                            ops.updateView(handle.pillId, pillPatch)
                            if (handle.motionSlot >= 0) {
                                ManaMotion
                                    .batch()
                                    .setValue(handle.motionSlot, targetX.toFloat())
                                    .appendTo(ops)
                            }
                        }
                    }
                }

                for (i in labels.indices) {
                    scope.onPress(handle.tabIds[i]) {
                        if (selectedIndex.value != i) {
                            selectedIndex.value = i
                        }
                        onSelected?.invoke(i)
                    }
                }

                selectedIndex.observe(scope) { nextIdx ->
                    val clamped = nextIdx.coerceIn(0, labels.lastIndex)
                    val t = ThemeEngine.currentTheme()
                    for (i in handle.labelIds.indices) {
                        val active = (i == clamped)
                        scope.rebindTheme(
                            handle.labelIds[i],
                            "color",
                            if (active) ColorToken.TEXT_PRIMARY else ColorToken.TEXT_MUTED,
                        )
                    }
                    scope.emitPatch { ops ->
                        ManaUi.updateSegmentedControl(ops, handle, clamped, t)
                        if (measuredTabWidth > 0.0 && handle.pillId >= 0) {
                            val targetX = clamped * measuredTabWidth
                            if (handle.motionSlot >= 0) {
                                ManaMotion.triggerSegmentedPillX(ops, handle.motionSlot, targetX.toFloat())
                            } else {
                                ops.updateView(
                                    handle.pillId,
                                    ManaUi.props(ManaUi.prop("translateX", UiOps.Value.ofDouble(targetX))),
                                )
                            }
                        }
                    }
                }

                if (refKey != null) {
                    refs[refKey] = handle
                }
                handle.rootId
            },
        )
    }

    fun button(
        label: String,
        icon: String? = null,
        style: ButtonStyle = ButtonStyle.PRIMARY,
        size: ButtonSize = ButtonSize.LG,
        enabledState: ManaState<Boolean>? = null,
        accessibilityId: String? = null,
        refKey: String? = null,
        onPress: () -> Unit,
    ) {
        entries.add(
            Entry.Block { batch, theme, _ ->
                val initialEnabled = enabledState?.value ?: true
                val btnId =
                    ManaUi.button(
                        batch,
                        scope.allocator,
                        theme,
                        label,
                        icon,
                        style.toManaVariant(),
                        size.toManaSize(),
                        initialEnabled,
                        accessibilityId,
                    )
                scope.onPress(btnId) {
                    if (enabledState == null || enabledState.value) {
                        onPress()
                    }
                }
                if (enabledState != null) {
                    enabledState.observe(scope) { enabled ->
                        scope.emitPatch { ops ->
                            ops.updateView(
                                btnId,
                                ManaUi.props(
                                    ManaUi.prop("enabled", UiOps.Value.ofBool(enabled)),
                                    ManaUi.prop("opacity", UiOps.Value.ofDouble(if (enabled) 1.0 else 0.5)),
                                ),
                            )
                        }
                    }
                }
                if (refKey != null) {
                    refs[refKey] = btnId
                }
                btnId
            },
        )
    }

    fun textInput(
        label: String? = null,
        placeholder: String = "",
        state: ManaState<String>,
        helperText: String? = null,
        errorState: ManaState<String?>? = null,
        multiline: Boolean = false,
        clearable: Boolean = true,
        accessibilityId: String? = null,
        refKey: String? = null,
        onSubmit: ((String) -> Unit)? = null,
        onChange: ((String) -> Unit)? = null,
    ) {
        entries.add(
            Entry.Block { batch, _, pal ->
                val handle =
                    ManaWidgets.emitTextInput(
                        scope = scope,
                        batch = batch,
                        palette = pal,
                        label = label,
                        placeholder = placeholder,
                        state = state,
                        helperText = helperText,
                        errorState = errorState,
                        multiline = multiline,
                        clearable = clearable,
                        accessibilityId = accessibilityId,
                        onChange = onChange,
                        onSubmit = onSubmit,
                    )
                if (refKey != null) {
                    refs[refKey] = handle
                }
                handle.rootId
            },
        )
    }

    fun callout(
        text: String,
        title: String? = null,
        tone: CalloutTone = CalloutTone.INFO,
        icon: String? = null,
        refKey: String? = null,
    ) {
        entries.add(
            Entry.Block { batch, _, pal ->
                val handle =
                    ManaWidgets.emitCallout(
                        scope = scope,
                        batch = batch,
                        palette = pal,
                        text = text,
                        title = title,
                        tone = tone,
                        icon = icon,
                    )
                if (refKey != null) {
                    refs[refKey] = handle
                }
                handle.rootId
            },
        )
    }

    fun banner(
        text: String,
        title: String? = null,
        tone: CalloutTone = CalloutTone.INFO,
        icon: String? = null,
        refKey: String? = null,
    ) = callout(text = text, title = title, tone = tone, icon = icon, refKey = refKey)

    fun avatar(
        uri: String? = null,
        initials: String? = null,
        size: Int = 40,
        badgeTone: AvatarBadgeTone? = null,
        label: String? = null,
        subtitle: String? = null,
        refKey: String? = null,
        onPress: (() -> Unit)? = null,
    ) {
        entries.add(
            Entry.Block { batch, _, pal ->
                val handle =
                    ManaWidgets.emitAvatar(
                        scope = scope,
                        batch = batch,
                        palette = pal,
                        uri = uri,
                        initials = initials,
                        size = size,
                        badgeTone = badgeTone,
                        label = label,
                        subtitle = subtitle,
                        onPress = onPress,
                    )
                if (refKey != null) {
                    refs[refKey] = handle
                }
                handle.rootId
            },
        )
    }

    fun searchInput(
        placeholder: String = "Search",
        state: ManaState<String>,
        accessibilityId: String? = null,
        refKey: String? = null,
        onChange: ((String) -> Unit)? = null,
        onSubmit: ((String) -> Unit)? = null,
    ) {
        entries.add(
            Entry.Block { batch, theme, _ ->
                val handle =
                    ManaUi.searchInput(
                        batch,
                        scope.allocator,
                        theme,
                        placeholder,
                        state.value,
                        accessibilityId,
                    )
                var updatingFromInputEvent = false
                scope.onChangeText(handle.inputId) { text ->
                    updatingFromInputEvent = true
                    try {
                        state.value = text
                    } finally {
                        updatingFromInputEvent = false
                    }
                    onChange?.invoke(text)
                }
                scope.onSubmit(handle.inputId) { text ->
                    onSubmit?.invoke(if (text.isNotEmpty()) text else state.value)
                }
                var isFocused = false
                scope.onFocus(handle.inputId) {
                    if (isFocused) return@onFocus
                    isFocused = true
                    scope.rebindTheme(handle.rootId, "borderColor", ColorToken.BRAND_500)
                    scope.emitPatch { ops ->
                        if (handle.focusMotionSlot >= 0) {
                            ManaMotion.triggerInputFocus(ops, handle.focusMotionSlot, true)
                        } else {
                            val pal = ThemeEngine.currentPalette()
                            ops.updateView(
                                handle.rootId,
                                ManaUi.props(
                                    ManaUi.prop("borderColor", ManaUi.colorVal(pal[ColorToken.BRAND_500])),
                                    ManaUi.prop("borderWidth", UiOps.Value.ofDouble(1.5)),
                                ),
                            )
                        }
                    }
                }
                scope.onBlur(handle.inputId) {
                    if (!isFocused) return@onBlur
                    isFocused = false
                    scope.rebindTheme(handle.rootId, "borderColor", ColorToken.INPUT_BORDER)
                    scope.emitPatch { ops ->
                        if (handle.focusMotionSlot >= 0) {
                            ManaMotion.triggerInputFocus(ops, handle.focusMotionSlot, false)
                        } else {
                            val pal = ThemeEngine.currentPalette()
                            ops.updateView(
                                handle.rootId,
                                ManaUi.props(
                                    ManaUi.prop("borderColor", ManaUi.colorVal(pal[ColorToken.INPUT_BORDER])),
                                    ManaUi.prop("borderWidth", UiOps.Value.ofDouble(1.0)),
                                ),
                            )
                        }
                    }
                }

                state.observe(scope) { next ->
                    if (updatingFromInputEvent) return@observe
                    scope.emitPatch { ops ->
                        ops.updateView(
                            handle.inputId,
                            ManaUi.props(ManaUi.prop("value", UiOps.Value.ofString(next))),
                        )
                    }
                }

                if (refKey != null) {
                    refs[refKey] = handle
                }
                handle.rootId
            },
        )
    }

    fun text(
        content: String,
        style: TextStyle = TextStyle.BODY,
        tone: ManaTone? = null,
        align: String? = null,
        refKey: String? = null,
    ) {
        entries.add(
            Entry.Block { batch, _, pal ->
                val id = emitStyledText(batch, pal, content, style, tone, align)
                if (refKey != null) refs[refKey] = id
                id
            },
        )
    }

    fun text(
        state: ManaState<String>,
        style: TextStyle = TextStyle.BODY,
        tone: ManaTone? = null,
        align: String? = null,
        refKey: String? = null,
    ) {
        entries.add(
            Entry.Block { batch, _, pal ->
                val id = emitStyledText(batch, pal, state.value, style, tone, align)
                state.observe(scope) { next ->
                    scope.emitPatch { ops ->
                        ops.updateView(id, ManaUi.props(ManaUi.prop("text", UiOps.Value.ofString(next))))
                    }
                }
                if (refKey != null) refs[refKey] = id
                id
            },
        )
    }

    fun badge(
        label: String,
        tone: BadgeTone = BadgeTone.BRAND,
        refKey: String? = null,
    ) {
        entries.add(
            Entry.Block { batch, _, pal ->
                val pair = emitThemedBadge(batch, pal, label, tone)
                if (refKey != null) refs[refKey] = pair.first
                pair.first
            },
        )
    }

    fun badge(
        state: ManaState<String>,
        tone: BadgeTone = BadgeTone.BRAND,
        refKey: String? = null,
    ) {
        entries.add(
            Entry.Block { batch, _, pal ->
                val pair = emitThemedBadge(batch, pal, state.value, tone)
                val textId = pair.second
                state.observe(scope) { next ->
                    scope.emitPatch { ops ->
                        ops.updateView(textId, ManaUi.props(ManaUi.prop("text", UiOps.Value.ofString(next))))
                    }
                }
                if (refKey != null) refs[refKey] = pair.first
                pair.first
            },
        )
    }

    internal fun emitSectionInto(
        batch: UiOps,
        theme: ManaUi.Theme,
        palette: ManaPalette,
        headerTitle: String?,
        footerText: String?,
        explicitRadioGroup: Boolean,
    ): Int {
        val isPureTableRows = entries.isNotEmpty() && entries.all { it is Entry.TableRow }
        if (isPureTableRows) {
            val rowIds = IntArray(entries.size)
            for (i in entries.indices) {
                val entry = entries[i] as Entry.TableRow
                val pos = ManaUi.RowPosition.forIndex(i, entries.size)
                rowIds[i] = entry.emit(batch, theme, palette, pos)
            }
            return ManaUi.section(
                batch,
                scope.allocator,
                theme,
                headerTitle,
                rowIds,
                footerText,
                explicitRadioGroup || hasRadioGroup,
            )
        }

        val outerId = scope.nextId()
        batch.createView(
            outerId,
            "view",
            ManaUi.props(
                ManaUi.prop("direction", UiOps.Value.ofString("column")),
                ManaUi.prop("width", UiOps.Value.ofString("fill")),
                ManaUi.prop("align", UiOps.Value.ofString("stretch")),
            ),
        )

        val outerKids = ArrayList<Int>(4)
        if (!headerTitle.isNullOrEmpty()) {
            val headerId = scope.nextId()
            batch.createView(
                headerId,
                "text",
                ManaUi.props(
                    ManaUi.prop("text", UiOps.Value.ofString(headerTitle)),
                    ManaUi.prop("color", ManaUi.colorVal(theme.headerText)),
                    ManaUi.prop("fontSize", UiOps.Value.ofInt(16)),
                    ManaUi.prop("fontWeight", UiOps.Value.ofInt(500)),
                    ManaUi.prop("lineHeight", UiOps.Value.ofInt(20)),
                    ManaUi.prop("lineHeightExact", UiOps.Value.ofBool(true)),
                    ManaUi.prop("margin", UiOps.Value.ofFloat4(0f, 0f, 0f, 8f)),
                    ManaUi.prop("role", UiOps.Value.ofString("header")),
                ),
            )
            scope.bindTheme(headerId, "color", ColorToken.TEXT_SECONDARY)
            outerKids.add(headerId)
        }

        val bodyKids = emitMixedEntriesInto(batch, theme, palette, explicitRadioGroup)
        if (bodyKids.size == 1) {
            outerKids.add(bodyKids[0])
        } else if (bodyKids.isNotEmpty()) {
            val colId = scope.nextId()
            batch.createView(
                colId,
                "view",
                ManaUi.props(
                    ManaUi.prop("direction", UiOps.Value.ofString("column")),
                    ManaUi.prop("width", UiOps.Value.ofString("fill")),
                    ManaUi.prop("gap", UiOps.Value.ofInt(12)),
                    ManaUi.prop("align", UiOps.Value.ofString("stretch")),
                ),
            )
            batch.setChildren(colId, *bodyKids)
            outerKids.add(colId)
        }

        if (!footerText.isNullOrEmpty()) {
            val footerWrapId = scope.nextId()
            val footerTxtId = scope.nextId()
            batch.createView(
                footerWrapId,
                "view",
                ManaUi.props(
                    ManaUi.prop("margin", UiOps.Value.ofFloat4(0f, 8f, 0f, 0f)),
                    ManaUi.prop("width", UiOps.Value.ofString("fill")),
                    ManaUi.prop("align", UiOps.Value.ofString("stretch")),
                ),
            )
            batch.createView(
                footerTxtId,
                "text",
                ManaUi.props(
                    ManaUi.prop("text", UiOps.Value.ofString(footerText)),
                    ManaUi.prop("color", ManaUi.colorVal(theme.mutedText)),
                    ManaUi.prop("fontSize", UiOps.Value.ofInt(12)),
                    ManaUi.prop("fontWeight", UiOps.Value.ofInt(400)),
                    ManaUi.prop("lineHeight", UiOps.Value.ofInt(16)),
                    ManaUi.prop("lineHeightExact", UiOps.Value.ofBool(true)),
                    ManaUi.prop("width", UiOps.Value.ofString("fill")),
                ),
            )
            scope.bindTheme(footerTxtId, "color", ColorToken.TEXT_MUTED)
            batch.setChildren(footerWrapId, footerTxtId)
            outerKids.add(footerWrapId)
        }

        batch.setChildren(outerId, *outerKids.toIntArray())
        return outerId
    }

    internal fun emitMixedEntriesInto(
        batch: UiOps,
        theme: ManaUi.Theme,
        palette: ManaPalette,
        isRadio: Boolean = false,
    ): IntArray {
        val out = ArrayList<Int>(entries.size)
        var i = 0
        while (i < entries.size) {
            val e = entries[i]
            if (e is Entry.TableRow) {
                var j = i
                while (j < entries.size && entries[j] is Entry.TableRow) {
                    j++
                }
                val count = j - i
                val rowIds = IntArray(count)
                for (k in 0 until count) {
                    val rowEntry = entries[i + k] as Entry.TableRow
                    rowIds[k] = rowEntry.emit(batch, theme, palette, ManaUi.RowPosition.forIndex(k, count))
                }
                val cardId =
                    ManaUi.section(
                        batch,
                        scope.allocator,
                        theme,
                        null,
                        rowIds,
                        null,
                        isRadio || hasRadioGroup,
                    )
                out.add(cardId)
                i = j
            } else {
                val block = e as Entry.Block
                out.add(block.emit(batch, theme, palette))
                i++
            }
        }
        return out.toIntArray()
    }

    private fun emitStyledText(
        batch: UiOps,
        palette: ManaPalette,
        content: String,
        style: TextStyle,
        tone: ManaTone?,
        align: String?,
    ): Int {
        val id = scope.nextId()
        val fontSize: Int
        val fontWeight: Int
        val lineHeight: Int
        val defaultToken: ColorToken
        when (style) {
            TextStyle.HEADING -> {
                fontSize = 16
                fontWeight = 600
                lineHeight = 20
                defaultToken = ColorToken.TEXT_PRIMARY
            }

            TextStyle.BODY -> {
                fontSize = 14
                fontWeight = 500
                lineHeight = 20
                defaultToken = ColorToken.TEXT_PRIMARY
            }

            TextStyle.CAPTION -> {
                fontSize = 12
                fontWeight = 500
                lineHeight = 16
                defaultToken = ColorToken.TEXT_SECONDARY
            }

            TextStyle.MUTED -> {
                fontSize = 12
                fontWeight = 400
                lineHeight = 16
                defaultToken = ColorToken.TEXT_MUTED
            }
        }
        val colorToken =
            when (tone) {
                ManaTone.DEFAULT -> ColorToken.TEXT_PRIMARY
                ManaTone.ACCENT -> ColorToken.BRAND_500
                ManaTone.DANGER -> ColorToken.DANGER_TEXT
                ManaTone.MUTED -> ColorToken.TEXT_MUTED
                null -> defaultToken
            }
        val props =
            ManaUi.props(
                ManaUi.prop("text", UiOps.Value.ofString(content)),
                ManaUi.prop("color", ManaUi.colorVal(palette[colorToken])),
                ManaUi.prop("fontSize", UiOps.Value.ofInt(fontSize)),
                ManaUi.prop("fontWeight", UiOps.Value.ofInt(fontWeight)),
                ManaUi.prop("lineHeight", UiOps.Value.ofInt(lineHeight)),
                ManaUi.prop("lineHeightExact", UiOps.Value.ofBool(true)),
                ManaUi.prop("width", UiOps.Value.ofString("fill")),
            )
        if (!align.isNullOrEmpty()) {
            ManaUi.addProp(props, "textAlign", UiOps.Value.ofString(align))
        }
        batch.createView(id, "text", props)
        scope.bindTheme(id, "color", colorToken)
        return id
    }

    private fun emitThemedBadge(
        batch: UiOps,
        palette: ManaPalette,
        label: String,
        tone: BadgeTone,
    ): Pair<Int, Int> {
        val wrapId = scope.nextId()
        val pillId = scope.nextId()
        val txtId = scope.nextId()
        val bgTok = tone.bgToken()
        val fgTok = tone.fgToken()

        batch.createView(
            wrapId,
            "view",
            ManaUi.props(
                ManaUi.prop("direction", UiOps.Value.ofString("row")),
                ManaUi.prop("align", UiOps.Value.ofString("center")),
            ),
        )
        batch.createView(
            pillId,
            "view",
            ManaUi.props(
                ManaUi.prop("direction", UiOps.Value.ofString("row")),
                ManaUi.prop("padding", UiOps.Value.ofFloat4(8f, 2f, 8f, 2f)),
                ManaUi.prop("height", UiOps.Value.ofInt(22)),
                ManaUi.prop("minWidth", UiOps.Value.ofInt(22)),
                ManaUi.prop("align", UiOps.Value.ofString("center")),
                ManaUi.prop("justify", UiOps.Value.ofString("center")),
                ManaUi.prop("background", ManaUi.colorVal(palette[bgTok])),
                ManaUi.prop("radius", UiOps.Value.ofInt(9999)),
            ),
        )
        scope.bindTheme(pillId, "background", bgTok)

        batch.createView(
            txtId,
            "text",
            ManaUi.props(
                ManaUi.prop("text", UiOps.Value.ofString(label)),
                ManaUi.prop("color", ManaUi.colorVal(palette[fgTok])),
                ManaUi.prop("fontSize", UiOps.Value.ofInt(12)),
                ManaUi.prop("fontWeight", UiOps.Value.ofInt(600)),
                ManaUi.prop("lineHeight", UiOps.Value.ofInt(16)),
                ManaUi.prop("lineHeightExact", UiOps.Value.ofBool(true)),
                ManaUi.prop("textAlign", UiOps.Value.ofString("center")),
            ),
        )
        scope.bindTheme(txtId, "color", fgTok)

        batch.setChildren(pillId, txtId)
        batch.setChildren(wrapId, pillId)
        return Pair(wrapId, txtId)
    }
}

@ManaDsl
class ManaPageBuilder internal constructor(
    val context: ManaContext,
    val scope: ManaScope,
    internal val refs: MutableMap<String, Any>,
) {
    val router: ManaRouter get() = context.router
    val palette: ManaPalette get() = context.palette

    internal val headerActions = ArrayList<HeaderActionSpec>()
    internal val sectionEmitters = ArrayList<(UiOps, ManaUi.Theme, ManaPalette) -> Int>()
    internal var restartBannerSpec: RestartBannerSpec? = null

    fun headerAction(
        icon: String,
        label: String,
        accessibilityId: String? = null,
        refKey: String? = null,
        onPress: () -> Unit,
    ) {
        headerActions.add(HeaderActionSpec(icon, label, accessibilityId, refKey, onPress))
    }

    fun restartBanner(
        visibleState: ManaState<Boolean>,
        messageState: ManaState<String>,
        title: String = "Restart Required",
        buttonLabel: String = "Restart Discord",
        buttonAccessibilityId: String = "cinder.btn.restart",
        buttonRefKey: String? = null,
        onRestart: () -> Unit = { Loader.restartApp() },
    ) {
        restartBannerSpec =
            RestartBannerSpec(
                visibleState = visibleState,
                messageState = messageState,
                title = title,
                buttonLabel = buttonLabel,
                buttonAccessibilityId = buttonAccessibilityId,
                buttonRefKey = buttonRefKey,
                onRestart = onRestart,
            )
    }

    fun section(
        title: String? = null,
        footer: String? = null,
        isRadioGroup: Boolean = false,
        content: ManaSectionBuilder.() -> Unit,
    ) {
        val sb = ManaSectionBuilder(context, scope, refs)
        sb.content()
        if (sb.entries.isEmpty()) return
        sectionEmitters.add { batch, theme, pal ->
            sb.emitSectionInto(batch, theme, pal, title, footer, isRadioGroup)
        }
    }

    fun searchInput(
        placeholder: String = "Search",
        state: ManaState<String>,
        accessibilityId: String? = null,
        refKey: String? = null,
        onChange: ((String) -> Unit)? = null,
        onSubmit: ((String) -> Unit)? = null,
    ) {
        section {
            searchInput(placeholder, state, accessibilityId, refKey, onChange, onSubmit)
        }
    }

    fun button(
        label: String,
        icon: String? = null,
        style: ButtonStyle = ButtonStyle.PRIMARY,
        size: ButtonSize = ButtonSize.LG,
        enabledState: ManaState<Boolean>? = null,
        accessibilityId: String? = null,
        refKey: String? = null,
        onPress: () -> Unit,
    ) {
        section {
            button(label, icon, style, size, enabledState, accessibilityId, refKey, onPress)
        }
    }

    fun callout(
        text: String,
        title: String? = null,
        tone: CalloutTone = CalloutTone.INFO,
        icon: String? = null,
        refKey: String? = null,
    ) {
        section {
            callout(text, title, tone, icon, refKey)
        }
    }

    fun banner(
        text: String,
        title: String? = null,
        tone: CalloutTone = CalloutTone.INFO,
        icon: String? = null,
        refKey: String? = null,
    ) {
        section {
            banner(text, title, tone, icon, refKey)
        }
    }

    fun segmentedControl(
        labels: List<String>,
        selectedIndex: ManaState<Int>,
        refKey: String? = null,
        onSelected: ((Int) -> Unit)? = null,
    ) {
        section {
            segmentedControl(labels, selectedIndex, refKey, onSelected)
        }
    }

    internal fun attachRestartBanner(
        initialBatch: UiOps,
        initialTheme: ManaUi.Theme,
        initialPalette: ManaPalette,
        contentColumnId: Int,
        baseSectionIds: IntArray,
    ): IntArray {
        val spec = restartBannerSpec ?: return baseSectionIds

        var bannerRootId = -1
        var bannerViewIds = IntArray(0)
        var bannerBodyTextId = -1

        fun emitBannerInto(
            targetBatch: UiOps,
            t: ManaUi.Theme,
            pal: ManaPalette,
        ): Int {
            val tracker = ManaUi.TrackingAllocator(scope.allocator)
            val rootId = tracker.nextId()
            val headerRowId = tracker.nextId()
            val textColId = tracker.nextId()
            val titleId = tracker.nextId()
            val bodyId = tracker.nextId()
            val bgToken = CalloutTone.WARNING.bgToken()
            val accentToken = CalloutTone.WARNING.accentToken()

            targetBatch.createView(
                rootId,
                "view",
                ManaUi.props(
                    ManaUi.prop("direction", UiOps.Value.ofString("column")),
                    ManaUi.prop("width", UiOps.Value.ofString("fill")),
                    ManaUi.prop("padding", UiOps.Value.ofFloat4(12f, 12f, 12f, 12f)),
                    ManaUi.prop("gap", UiOps.Value.ofInt(10)),
                    ManaUi.prop("align", UiOps.Value.ofString("stretch")),
                    ManaUi.prop("background", ManaUi.colorVal(pal[bgToken])),
                    ManaUi.prop("borderColor", ManaUi.colorVal(pal[accentToken])),
                    ManaUi.prop("borderWidth", UiOps.Value.ofInt(1)),
                    ManaUi.prop("radius", UiOps.Value.ofInt(12)),
                ),
            )
            scope.bindTheme(rootId, "background", bgToken)
            scope.bindTheme(rootId, "borderColor", accentToken)

            targetBatch.createView(
                headerRowId,
                "view",
                ManaUi.props(
                    ManaUi.prop("direction", UiOps.Value.ofString("row")),
                    ManaUi.prop("width", UiOps.Value.ofString("fill")),
                    ManaUi.prop("gap", UiOps.Value.ofInt(10)),
                    ManaUi.prop("align", UiOps.Value.ofString("start")),
                ),
            )

            val iconId =
                ManaUi.icon(
                    targetBatch,
                    tracker,
                    ManaIcons.WARNING,
                    20,
                    20,
                    pal[accentToken],
                    accentToken,
                    0f,
                    1f,
                    0f,
                    0f,
                )

            targetBatch.createView(
                textColId,
                "view",
                ManaUi.props(
                    ManaUi.prop("direction", UiOps.Value.ofString("column")),
                    ManaUi.prop("flex", UiOps.Value.ofDouble(1.0)),
                    ManaUi.prop("gap", UiOps.Value.ofInt(2)),
                    ManaUi.prop("align", UiOps.Value.ofString("stretch")),
                ),
            )

            targetBatch.createView(
                titleId,
                "text",
                ManaUi.props(
                    ManaUi.prop("text", UiOps.Value.ofString(spec.title)),
                    ManaUi.prop("color", ManaUi.colorVal(pal[ColorToken.TEXT_PRIMARY])),
                    ManaUi.prop("fontSize", UiOps.Value.ofInt(14)),
                    ManaUi.prop("fontWeight", UiOps.Value.ofInt(600)),
                    ManaUi.prop("lineHeight", UiOps.Value.ofInt(18)),
                    ManaUi.prop("lineHeightExact", UiOps.Value.ofBool(true)),
                    ManaUi.prop("width", UiOps.Value.ofString("fill")),
                ),
            )
            scope.bindTheme(titleId, "color", ColorToken.TEXT_PRIMARY)

            targetBatch.createView(
                bodyId,
                "text",
                ManaUi.props(
                    ManaUi.prop("text", UiOps.Value.ofString(spec.messageState.value)),
                    ManaUi.prop("color", ManaUi.colorVal(pal[ColorToken.TEXT_SECONDARY])),
                    ManaUi.prop("fontSize", UiOps.Value.ofInt(13)),
                    ManaUi.prop("fontWeight", UiOps.Value.ofInt(400)),
                    ManaUi.prop("lineHeight", UiOps.Value.ofInt(18)),
                    ManaUi.prop("lineHeightExact", UiOps.Value.ofBool(true)),
                    ManaUi.prop("width", UiOps.Value.ofString("fill")),
                ),
            )
            scope.bindTheme(bodyId, "color", ColorToken.TEXT_SECONDARY)

            targetBatch.setChildren(textColId, titleId, bodyId)
            targetBatch.setChildren(headerRowId, iconId, textColId)

            val btnId =
                ManaUi.button(
                    targetBatch,
                    tracker,
                    t,
                    spec.buttonLabel,
                    ManaIcons.REFRESH,
                    ManaUi.ButtonVariant.PRIMARY,
                    ManaUi.ButtonSize.MD,
                    true,
                    spec.buttonAccessibilityId,
                )
            scope.onPress(btnId) { spec.onRestart() }
            if (spec.buttonRefKey != null) {
                refs[spec.buttonRefKey] = btnId
            }

            targetBatch.setChildren(rootId, headerRowId, btnId)
            bannerRootId = rootId
            bannerBodyTextId = bodyId
            bannerViewIds = tracker.toIntArray()
            return rootId
        }

        val initialKids =
            if (spec.visibleState.value) {
                val rootId = emitBannerInto(initialBatch, initialTheme, initialPalette)
                IntArray(baseSectionIds.size + 1).also { out ->
                    out[0] = rootId
                    System.arraycopy(baseSectionIds, 0, out, 1, baseSectionIds.size)
                }
            } else {
                baseSectionIds
            }

        spec.visibleState.observe(scope) { visible ->
            if (visible && bannerRootId < 0) {
                scope.emitPatch { ops ->
                    scope.withThemeScope {
                        val rootId = emitBannerInto(ops, ThemeEngine.currentTheme(), ThemeEngine.currentPalette())
                        val nextKids = IntArray(baseSectionIds.size + 1)
                        nextKids[0] = rootId
                        System.arraycopy(baseSectionIds, 0, nextKids, 1, baseSectionIds.size)
                        ops.setChildren(contentColumnId, *nextKids)
                    }
                }
            } else if (!visible && bannerRootId >= 0) {
                val toDelete = bannerViewIds
                bannerRootId = -1
                bannerBodyTextId = -1
                bannerViewIds = IntArray(0)
                if (spec.buttonRefKey != null) {
                    refs.remove(spec.buttonRefKey)
                }
                scope.untrackViewIds(toDelete)
                scope.emitPatch { ops ->
                    ops.setChildren(contentColumnId, *baseSectionIds)
                    if (toDelete.isNotEmpty()) {
                        ops.deleteViews(*toDelete)
                    }
                }
            }
        }

        spec.messageState.observe(scope) { nextMsg ->
            val bodyId = bannerBodyTextId
            if (bodyId >= 0) {
                scope.emitPatch { ops ->
                    ops.updateView(bodyId, ManaUi.props(ManaUi.prop("text", UiOps.Value.ofString(nextMsg))))
                }
            }
        }

        return initialKids
    }

    internal fun emitHeaderActions(
        batch: UiOps,
        palette: ManaPalette,
    ): IntArray {
        if (headerActions.isEmpty()) return IntArray(0)
        val out = IntArray(headerActions.size)
        for (i in headerActions.indices) {
            val spec = headerActions[i]
            val btnId = scope.nextId()
            val btnProps =
                ManaUi.props(
                    ManaUi.prop("direction", UiOps.Value.ofString("row")),
                    ManaUi.prop("width", UiOps.Value.ofInt(36)),
                    ManaUi.prop("height", UiOps.Value.ofInt(36)),
                    ManaUi.prop("align", UiOps.Value.ofString("center")),
                    ManaUi.prop("justify", UiOps.Value.ofString("center")),
                    ManaUi.prop("radius", UiOps.Value.ofInt(18)),
                    ManaUi.prop("role", UiOps.Value.ofString("button")),
                    ManaUi.prop("accessibilityLabel", UiOps.Value.ofString(spec.label)),
                    ManaUi.prop("accessible", UiOps.Value.ofBool(true)),
                    ManaUi.prop("hitSlop", UiOps.Value.ofInt(6)),
                    ManaUi.prop("on", ManaUi.stringArray("press")),
                )
            if (!spec.accessibilityId.isNullOrEmpty()) {
                ManaUi.addProp(btnProps, "accessibilityId", UiOps.Value.ofString(spec.accessibilityId))
            }
            batch.createView(btnId, "view", btnProps)
            val iconId =
                ManaUi.icon(
                    batch,
                    scope.allocator,
                    spec.icon,
                    22,
                    ColorToken.ICON_PRIMARY,
                    palette,
                )
            batch.setChildren(btnId, iconId)
            scope.onPress(btnId, spec.onPress)
            if (spec.refKey != null) {
                refs[spec.refKey] = btnId
            }
            out[i] = btnId
        }
        return out
    }
}
