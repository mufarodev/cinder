package com.discord.loader.mana

import android.util.Log
import com.discord.loader.Loader
import com.discord.loader.ManaUi
import com.discord.loader.UiOps
import java.util.ArrayDeque
import java.util.EnumMap
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import kotlin.math.abs

object ThemeEngine {
    data class ColorBinding(
        val scopeId: String,
        val viewId: Int,
        val propKey: String,
        val token: ColorToken,
    )

    fun interface ThemeListener {
        fun onThemeChanged(
            oldPalette: ManaPalette,
            newPalette: ManaPalette,
        )
    }

    private data class BindingKey(
        val viewId: Int,
        val propKey: String,
    )

    private const val MAX_TRACKED_NATIVE_VIEWS = 2048
    private const val SIGNIFICANT_BG_JUMP_DISTANCE = 12.0

    private val lock = Any()

    @Volatile
    private var resolvedPalette: ManaPalette = ManaPalette.DARK

    @Volatile
    private var resolvedLegacyTheme: ManaUi.Theme = resolvedPalette.toLegacyTheme()

    private var currentPresetState: ThemePreset = ThemePreset.DARK
    private val liveOverrides: EnumMap<ColorToken, Long> = EnumMap(ColorToken::class.java)
    private var liveHeaderHeight: Double = ManaUi.Theme.DEFAULT_HEADER_HEIGHT
    private var liveStatusBarHeight: Double = ManaUi.Theme.DEFAULT_STATUS_BAR_HEIGHT
    private var liveBottomInset: Double = ManaUi.Theme.DEFAULT_BOTTOM_INSET

    private val bindingsByScope: LinkedHashMap<String, LinkedHashMap<BindingKey, ColorBinding>> =
        LinkedHashMap()

    private val listeners: LinkedHashSet<ThemeListener> = LinkedHashSet()

    private val trackedRootIds = LinkedHashSet<Int>()
    private val trackedHeaderIds = LinkedHashSet<Int>()
    private val trackedRowIds = LinkedHashSet<Int>()
    private val trackedDividerIds = LinkedHashSet<Int>()
    private val trackedPrimaryTextIds = LinkedHashSet<Int>()
    private val trackedSecondaryTextIds = LinkedHashSet<Int>()
    private val trackedMutedTextIds = LinkedHashSet<Int>()
    private val trackedPrimaryIconIds = LinkedHashSet<Int>()
    private val trackedMutedIconIds = LinkedHashSet<Int>()

    private val activeScopeStack: ThreadLocal<ArrayDeque<String>> = ThreadLocal()

    @JvmStatic
    fun currentPalette(): ManaPalette = resolvedPalette

    @JvmStatic
    fun currentPreset(): ThemePreset = resolvedPalette.preset

    @JvmStatic
    fun currentTheme(): ManaUi.Theme = resolvedLegacyTheme

    @JvmStatic
    fun color(token: ColorToken): Long = resolvedPalette[token]

    @JvmStatic
    fun beginScope(scopeId: String) {
        if (scopeId.isEmpty()) return
        var stack = activeScopeStack.get()
        if (stack == null) {
            stack = ArrayDeque()
            activeScopeStack.set(stack)
        }
        stack.push(scopeId)
    }

    @JvmStatic
    fun endScope() {
        val stack = activeScopeStack.get() ?: return
        if (!stack.isEmpty()) {
            stack.pop()
        }
        if (stack.isEmpty()) {
            activeScopeStack.remove()
        }
    }

    inline fun <T> withScope(
        scopeId: String,
        block: () -> T,
    ): T {
        beginScope(scopeId)
        return try {
            block()
        } finally {
            endScope()
        }
    }

    @JvmStatic
    fun activeScopeId(): String? {
        val stack = activeScopeStack.get() ?: return null
        return if (stack.isEmpty()) null else stack.peek()
    }

    @JvmStatic
    fun bindActive(
        viewId: Int,
        propKey: String,
        token: ColorToken,
    ) {
        val scope = activeScopeId() ?: return
        bind(scope, viewId, propKey, token)
    }

    @JvmStatic
    fun bind(
        scopeId: String,
        viewId: Int,
        propKey: String,
        token: ColorToken,
    ) {
        if (scopeId.isEmpty() || propKey.isEmpty()) return
        synchronized(lock) {
            var scopeMap = bindingsByScope[scopeId]
            if (scopeMap == null) {
                scopeMap = LinkedHashMap()
                bindingsByScope[scopeId] = scopeMap
            }
            scopeMap[BindingKey(viewId, propKey)] = ColorBinding(scopeId, viewId, propKey, token)
        }
    }

    @JvmStatic
    fun rebind(
        scopeId: String,
        viewId: Int,
        propKey: String,
        token: ColorToken,
    ) {
        bind(scopeId, viewId, propKey, token)
    }

    @JvmStatic
    fun unbindScope(scopeId: String) {
        if (scopeId.isEmpty()) return
        synchronized(lock) {
            bindingsByScope.remove(scopeId)
        }
        ManaMotion.unbindThemeWorklets(scopeId)
    }

    @JvmStatic
    fun unbindView(viewId: Int) {
        synchronized(lock) {
            val scopeIter = bindingsByScope.entries.iterator()
            while (scopeIter.hasNext()) {
                val map = scopeIter.next().value
                val keyIter = map.keys.iterator()
                while (keyIter.hasNext()) {
                    if (keyIter.next().viewId == viewId) {
                        keyIter.remove()
                    }
                }
                if (map.isEmpty()) {
                    scopeIter.remove()
                }
            }
        }
        ManaMotion.unbindThemeWorkletView(viewId)
    }

    @JvmStatic
    @JvmOverloads
    fun bindingCount(scopeId: String? = null): Int =
        synchronized(lock) {
            if (scopeId != null) {
                bindingsByScope[scopeId]?.size ?: 0
            } else {
                var total = 0
                for (map in bindingsByScope.values) {
                    total += map.size
                }
                total
            }
        }

    @JvmStatic
    fun addListener(listener: ThemeListener) {
        synchronized(lock) {
            listeners.add(listener)
        }
    }

    @JvmStatic
    fun removeListener(listener: ThemeListener) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    @JvmStatic
    fun computeReskinOps(
        previous: ManaPalette,
        current: ManaPalette,
    ): UiOps {
        val batch = UiOps.create()
        if (previous == current) return batch

        val snapshot: List<ColorBinding> =
            synchronized(lock) {
                if (bindingsByScope.isEmpty()) {
                    emptyList()
                } else {
                    val list = ArrayList<ColorBinding>()
                    for (scopeMap in bindingsByScope.values) {
                        for (binding in scopeMap.values) {
                            list.add(binding)
                        }
                    }
                    list
                }
            }
        val workletUpdates = ManaMotion.computeThemeReskinWorklets(previous, current)
        if (snapshot.isEmpty() && workletUpdates.isEmpty()) return batch

        val updatesByView = LinkedHashMap<Int, LinkedHashMap<String, Long>>()
        for (binding in snapshot) {
            val oldColor = previous[binding.token]
            val newColor = current[binding.token]
            if (oldColor == newColor) continue
            var props = updatesByView[binding.viewId]
            if (props == null) {
                props = LinkedHashMap()
                updatesByView[binding.viewId] = props
            }
            props[binding.propKey] = newColor
        }

        val affectedViewIds = LinkedHashSet<Int>()
        affectedViewIds.addAll(updatesByView.keys)
        affectedViewIds.addAll(workletUpdates.keys)

        for (viewId in affectedViewIds) {
            val propColors = updatesByView[viewId]
            val workletWords = workletUpdates[viewId]
            val cap = (propColors?.size ?: 0) + (if (workletWords != null) 1 else 0)
            val propList = ArrayList<UiOps.Prop>(cap)
            if (propColors != null) {
                for ((propKey, argb) in propColors) {
                    val p = ManaUi.prop(propKey, ManaUi.colorVal(argb))
                    if (p != null) {
                        propList.add(p)
                    }
                }
            }
            if (workletWords != null) {
                val wp = ManaMotion.workletProp(workletWords)
                if (wp != null) {
                    propList.add(wp)
                }
            }
            if (propList.isNotEmpty()) {
                batch.updateView(viewId, propList)
            }
        }
        return batch
    }

    @JvmStatic
    @JvmOverloads
    fun applyPreset(
        preset: ThemePreset,
        applyReskin: Boolean = true,
    ): UiOps {
        val oldPalette: ManaPalette
        val newPalette: ManaPalette
        val listenerSnap: List<ThemeListener>
        synchronized(lock) {
            oldPalette = resolvedPalette
            currentPresetState = preset
            liveOverrides.clear()
            newPalette =
                ManaPalette.forPreset(preset).withOverrides(
                    overrides = emptyMap(),
                    headerHeight = liveHeaderHeight,
                    statusBarHeight = liveStatusBarHeight,
                    bottomInset = liveBottomInset,
                )
            resolvedPalette = newPalette
            resolvedLegacyTheme = newPalette.toLegacyTheme()
            listenerSnap = if (listeners.isEmpty()) emptyList() else ArrayList(listeners)
        }
        return finishPaletteTransition(oldPalette, newPalette, listenerSnap, applyReskin)
    }

    @JvmStatic
    @JvmOverloads
    fun observeBatch(
        opList: List<UiOps.Op>?,
        applyReskin: Boolean = true,
    ): UiOps {
        if (opList == null || opList.isEmpty()) {
            return UiOps.create()
        }

        val oldPalette: ManaPalette
        val newPalette: ManaPalette
        val listenerSnap: List<ThemeListener>

        synchronized(lock) {
            oldPalette = resolvedPalette

            val scraped = EnumMap<ColorToken, Long>(ColorToken::class.java)
            var observedRootBg: Long = -1L
            var rootBgPriority = 0
            var observedHeaderBg: Long = -1L
            var observedSheetBg: Long = -1L
            var observedHeaderHeight: Double = liveHeaderHeight
            var observedStatusBarHeight: Double = liveStatusBarHeight
            var observedBottomInset: Double = liveBottomInset

            for (i in 0 until opList.size) {
                val op = opList[i]
                val opcode = op.opcode()

                if (opcode == UiOps.OP_DELETE) {
                    val deleted = op.deleteIds()
                    if (deleted != null) {
                        for (id in deleted) {
                            forgetNativeViewLocked(id)
                        }
                    }
                    continue
                }

                val viewId = op.viewId()
                if (UiOps.isSyntheticId(viewId)) continue

                if (opcode == UiOps.OP_CREATE) {
                    val type = op.viewType()
                    when (type) {
                        "view" -> {
                            if ("settings-phone" == op.propString("accessibilityId")) {
                                val bg = extractColorProp(op, "background")
                                if (bg >= 0L && isOpaqueColor(bg)) {
                                    trackIdLocked(trackedRootIds, viewId)
                                    if (rootBgPriority < 3) {
                                        observedRootBg = bg
                                        rootBgPriority = 3
                                    }
                                }
                            }

                            val sh = op.findProp("stackHeader")
                            if (sh != null && sh.value.asBoolean()) {
                                val hProp = op.findProp("height")
                                if (hProp != null) {
                                    val h = hProp.value.asDouble()
                                    if (h in 56.0..160.0) observedHeaderHeight = h
                                }
                                val padProp = op.findProp("padding")
                                val pad = padProp?.value?.asFloat4()
                                if (pad != null && pad[1] in 0f..100f) {
                                    observedStatusBarHeight = pad[1].toDouble()
                                }
                                val bg = extractColorProp(op, "background")
                                if (bg >= 0L && isOpaqueColor(bg)) {
                                    trackIdLocked(trackedHeaderIds, viewId)
                                    observedHeaderBg = bg
                                    if (rootBgPriority < 2) {
                                        observedRootBg = bg
                                        rootBgPriority = 2
                                    }
                                }
                            }

                            if ("fill" == op.propString("width") && "fill" == op.propString("height")) {
                                val bg = extractColorProp(op, "background")
                                if (bg >= 0L && isOpaqueColor(bg) && isLikelyCanvasColor(bg)) {
                                    trackIdLocked(trackedRootIds, viewId)
                                    if (rootBgPriority < 1) {
                                        observedRootBg = bg
                                        rootBgPriority = 1
                                    }
                                }
                            }

                            if ("column" == op.propString("direction")) {
                                val gap = op.findProp("gap")
                                if (gap != null && abs(gap.value.asDouble() - 24.0) < 0.5) {
                                    val pad = op.findProp("padding")?.value?.asFloat4()
                                    if (pad != null && pad[3] in 12f..80f) {
                                        observedBottomInset = pad[3].toDouble()
                                    }
                                }
                            }

                            val role = op.propString("role")
                            if (("button" == role || "switch" == role || "radio" == role) &&
                                op.findProp("background") != null &&
                                op.findProp("activeBackground") != null
                            ) {
                                val rowBg = extractColorProp(op, "background")
                                val rowActive = extractColorProp(op, "activeBackground")
                                trackIdLocked(trackedRowIds, viewId)
                                if (rowBg >= 0L && !scraped.containsKey(ColorToken.BG_BASE_SECONDARY)) {
                                    scraped[ColorToken.BG_BASE_SECONDARY] = rowBg
                                }
                                if (rowActive >= 0L && !scraped.containsKey(ColorToken.BG_ROW_ACTIVE)) {
                                    scraped[ColorToken.BG_ROW_ACTIVE] = rowActive
                                }
                            }

                            val hProp = op.findProp("height")
                            if (hProp != null && hProp.value.asDouble() == 1.0 &&
                                op.findProp("background") != null
                            ) {
                                val div = extractColorProp(op, "background")
                                if (div >= 0L) {
                                    trackIdLocked(trackedDividerIds, viewId)
                                    if (!scraped.containsKey(ColorToken.DIVIDER)) {
                                        scraped[ColorToken.DIVIDER] = div
                                        scraped[ColorToken.BG_MOD_SUBTLE] = div
                                        scraped[ColorToken.SEGMENT_TRACK_BG] = div
                                    }
                                }
                            }

                            val minH = op.findProp("minHeight")
                            val rad = op.findProp("radius")
                            val bw = op.findProp("borderWidth")
                            if (minH != null && abs(minH.value.asDouble() - 40.0) < 0.5 &&
                                rad != null && abs(rad.value.asDouble() - 8.0) < 0.5 &&
                                bw != null && bw.value.asDouble() > 0.0
                            ) {
                                val inBg = extractColorProp(op, "background")
                                val inBorder = extractColorProp(op, "borderColor")
                                if (inBg >= 0L && isNeutralColor(inBg) && !scraped.containsKey(ColorToken.INPUT_BG)) {
                                    scraped[ColorToken.INPUT_BG] = inBg
                                }
                                if (inBorder >= 0L && !scraped.containsKey(ColorToken.INPUT_BORDER)) {
                                    scraped[ColorToken.INPUT_BORDER] = inBorder
                                }
                            }

                            if ("none" == op.propString("pressFeedback")) {
                                val wProp = op.findProp("width")
                                if (wProp != null && hProp != null &&
                                    abs(wProp.value.asDouble() - 56.0) < 0.5 &&
                                    abs(hProp.value.asDouble() - 28.0) < 0.5
                                ) {
                                    val bg = extractColorProp(op, "background")
                                    val bc = extractColorProp(op, "borderColor")
                                    if (bg >= 0L) {
                                        val alpha = ((bg ushr 24) and 0xFFL).toInt()
                                        if (alpha >= 0xC0) {
                                            if (!scraped.containsKey(ColorToken.SWITCH_ON_BG)) {
                                                scraped[ColorToken.SWITCH_ON_BG] = bg
                                                scraped[ColorToken.BRAND_500] = bg
                                                scraped[ColorToken.BUTTON_PRIMARY_BG] = bg
                                            }
                                            if (bc >= 0L && !scraped.containsKey(ColorToken.SWITCH_ON_BORDER)) {
                                                scraped[ColorToken.SWITCH_ON_BORDER] = bc
                                            }
                                        } else {
                                            if (!scraped.containsKey(ColorToken.SWITCH_OFF_BG)) {
                                                scraped[ColorToken.SWITCH_OFF_BG] = bg
                                            }
                                            if (bc >= 0L && !scraped.containsKey(ColorToken.SWITCH_OFF_BORDER)) {
                                                scraped[ColorToken.SWITCH_OFF_BORDER] = bc
                                                scraped[ColorToken.SEGMENT_THUMB_BG] = bc
                                                scraped[ColorToken.SHEET_HANDLE] = bc
                                            }
                                        }
                                    }
                                }
                            }

                            val wProp = op.findProp("width")
                            if (wProp != null && hProp != null &&
                                abs(wProp.value.asDouble() - 20.0) < 0.5 &&
                                abs(hProp.value.asDouble() - 20.0) < 0.5 &&
                                op.findProp("translateX") != null
                            ) {
                                val thumbBg = extractColorProp(op, "background")
                                if (thumbBg >= 0L && !scraped.containsKey(ColorToken.SWITCH_THUMB)) {
                                    scraped[ColorToken.SWITCH_THUMB] = thumbBg
                                }
                            }

                            if (wProp != null && hProp != null &&
                                abs(wProp.value.asDouble() - 24.0) < 0.5 &&
                                abs(hProp.value.asDouble() - 24.0) < 0.5 &&
                                op.findProp("borderColor") != null &&
                                extractColorProp(op, "background") == 0L
                            ) {
                                val rb = extractColorProp(op, "borderColor")
                                if (rb >= 0L && !scraped.containsKey(ColorToken.RADIO_UNCHECKED_BORDER)) {
                                    scraped[ColorToken.RADIO_UNCHECKED_BORDER] = rb
                                }
                            }
                        }

                        "sheet" -> {
                            val bg = extractColorProp(op, "background")
                            if (bg >= 0L && isOpaqueColor(bg)) {
                                observedSheetBg = bg
                                if (rootBgPriority < 1) {
                                    observedRootBg = bg
                                    rootBgPriority = 1
                                }
                            }
                            val hc = extractColorProp(op, "handleColor")
                            if (hc >= 0L && !scraped.containsKey(ColorToken.SHEET_HANDLE)) {
                                scraped[ColorToken.SHEET_HANDLE] = hc
                            }
                        }

                        "input" -> {
                            val ph = extractColorProp(op, "placeholderColor")
                            if (ph >= 0L && !scraped.containsKey(ColorToken.INPUT_PLACEHOLDER)) {
                                scraped[ColorToken.INPUT_PLACEHOLDER] = ph
                            }
                            val tc = extractColorProp(op, "color")
                            if (tc >= 0L && !scraped.containsKey(ColorToken.INPUT_TEXT)) {
                                scraped[ColorToken.INPUT_TEXT] = tc
                            }
                        }

                        "text" -> {
                            val role = op.propString("role")
                            val fs = op.findProp("fontSize")
                            val col = extractColorProp(op, "color")
                            if (col >= 0L) {
                                if ("header" == role && fs != null && abs(fs.value.asDouble() - 16.0) < 0.5) {
                                    trackIdLocked(trackedSecondaryTextIds, viewId)
                                    if (!scraped.containsKey(ColorToken.TEXT_SECONDARY)) {
                                        scraped[ColorToken.TEXT_SECONDARY] = col
                                    }
                                } else if (op.findProp("includeFontPadding") != null &&
                                    fs != null && abs(fs.value.asDouble() - 16.0) < 0.5
                                ) {
                                    if ("Log Out" == op.propString("text")) {
                                        if (!scraped.containsKey(ColorToken.DANGER_TEXT)) {
                                            scraped[ColorToken.DANGER_TEXT] = col
                                        }
                                    } else {
                                        trackIdLocked(trackedPrimaryTextIds, viewId)
                                        if (!scraped.containsKey(ColorToken.TEXT_PRIMARY)) {
                                            scraped[ColorToken.TEXT_PRIMARY] = col
                                        }
                                    }
                                } else if ("right" == op.propString("textAlign")) {
                                    trackIdLocked(trackedMutedTextIds, viewId)
                                    if (!scraped.containsKey(ColorToken.TEXT_MUTED)) {
                                        scraped[ColorToken.TEXT_MUTED] = col
                                    }
                                }
                            }
                        }

                        "image" -> {
                            val uri = op.propString("uri")
                            val tint = extractColorProp(op, "tint")
                            if (uri != null && tint >= 0L) {
                                if (uri == ManaIcons.CHEVRON_SMALL_RIGHT || uri == ManaIcons.ARROW_LARGE_LEFT) {
                                    trackIdLocked(trackedMutedIconIds, viewId)
                                    if (!scraped.containsKey(ColorToken.ICON_MUTED)) {
                                        scraped[ColorToken.ICON_MUTED] = tint
                                    }
                                    if (!scraped.containsKey(ColorToken.TEXT_SECONDARY)) {
                                        scraped[ColorToken.TEXT_SECONDARY] = tint
                                    }
                                } else if (uri == ManaIcons.DOOR_EXIT) {
                                    if (!scraped.containsKey(ColorToken.DANGER_TEXT)) {
                                        scraped[ColorToken.DANGER_TEXT] = tint
                                    }
                                } else if (uri.startsWith(ManaIcons.MANA_PREFIX)) {
                                    val wProp = op.findProp("width")
                                    val hProp = op.findProp("height")
                                    if (wProp == null ||
                                        (
                                            abs(wProp.value.asDouble() - 24.0) < 0.5 &&
                                                hProp != null && abs(hProp.value.asDouble() - 24.0) < 0.5
                                        )
                                    ) {
                                        trackIdLocked(trackedPrimaryIconIds, viewId)
                                        if (!scraped.containsKey(ColorToken.ICON_PRIMARY)) {
                                            scraped[ColorToken.ICON_PRIMARY] = tint
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (opcode == UiOps.OP_UPDATE) {
                    if (trackedRootIds.contains(viewId)) {
                        val bg = extractColorProp(op, "background")
                        if (bg >= 0L && isOpaqueColor(bg) && isLikelyCanvasColor(bg)) {
                            if (rootBgPriority < 3) {
                                observedRootBg = bg
                                rootBgPriority = 3
                            }
                        }
                    }
                    if (trackedHeaderIds.contains(viewId)) {
                        val bg = extractColorProp(op, "background")
                        if (bg >= 0L && isOpaqueColor(bg)) {
                            observedHeaderBg = bg
                            if (rootBgPriority < 2) {
                                observedRootBg = bg
                                rootBgPriority = 2
                            }
                        }
                    }
                    if (trackedRowIds.contains(viewId) ||
                        (op.findProp("background") != null && op.findProp("activeBackground") != null)
                    ) {
                        val rowBg = extractColorProp(op, "background")
                        val rowActive = extractColorProp(op, "activeBackground")
                        if (rowBg >= 0L && !scraped.containsKey(ColorToken.BG_BASE_SECONDARY)) {
                            scraped[ColorToken.BG_BASE_SECONDARY] = rowBg
                        }
                        if (rowActive >= 0L && !scraped.containsKey(ColorToken.BG_ROW_ACTIVE)) {
                            scraped[ColorToken.BG_ROW_ACTIVE] = rowActive
                        }
                    }
                    if (trackedDividerIds.contains(viewId)) {
                        val div = extractColorProp(op, "background")
                        if (div >= 0L && !scraped.containsKey(ColorToken.DIVIDER)) {
                            scraped[ColorToken.DIVIDER] = div
                            scraped[ColorToken.BG_MOD_SUBTLE] = div
                            scraped[ColorToken.SEGMENT_TRACK_BG] = div
                        }
                    }
                    if (trackedPrimaryTextIds.contains(viewId)) {
                        val col = extractColorProp(op, "color")
                        if (col >= 0L && !scraped.containsKey(ColorToken.TEXT_PRIMARY)) {
                            scraped[ColorToken.TEXT_PRIMARY] = col
                        }
                    }
                    if (trackedSecondaryTextIds.contains(viewId)) {
                        val col = extractColorProp(op, "color")
                        if (col >= 0L && !scraped.containsKey(ColorToken.TEXT_SECONDARY)) {
                            scraped[ColorToken.TEXT_SECONDARY] = col
                        }
                    }
                    if (trackedMutedTextIds.contains(viewId)) {
                        val col = extractColorProp(op, "color")
                        if (col >= 0L && !scraped.containsKey(ColorToken.TEXT_MUTED)) {
                            scraped[ColorToken.TEXT_MUTED] = col
                        }
                    }
                    if (trackedPrimaryIconIds.contains(viewId)) {
                        val tint = extractColorProp(op, "tint")
                        if (tint >= 0L && !scraped.containsKey(ColorToken.ICON_PRIMARY)) {
                            scraped[ColorToken.ICON_PRIMARY] = tint
                        }
                    }
                    if (trackedMutedIconIds.contains(viewId)) {
                        val tint = extractColorProp(op, "tint")
                        if (tint >= 0L && !scraped.containsKey(ColorToken.ICON_MUTED)) {
                            scraped[ColorToken.ICON_MUTED] = tint
                        }
                    }
                }
            }

            if (observedRootBg >= 0L) {
                val matchedPreset = ManaPalette.matchPreset(observedRootBg)
                val prevBg = oldPalette[ColorToken.BG_BASE_PRIMARY]
                val presetChanged = matchedPreset != currentPresetState
                val majorBgJump = ManaPalette.rgbDistance(prevBg, observedRootBg) > SIGNIFICANT_BG_JUMP_DISTANCE

                if (presetChanged || majorBgJump) {
                    currentPresetState = matchedPreset
                    liveOverrides.clear()
                }
                scraped[ColorToken.BG_BASE_PRIMARY] = observedRootBg
                if (observedHeaderBg < 0L) {
                    scraped[ColorToken.HEADER_BG] = observedRootBg
                }
                if (observedSheetBg < 0L) {
                    scraped[ColorToken.SHEET_BG] = observedRootBg
                }
            }

            if (observedHeaderBg >= 0L) {
                scraped[ColorToken.HEADER_BG] = observedHeaderBg
            }
            if (observedSheetBg >= 0L) {
                scraped[ColorToken.SHEET_BG] = observedSheetBg
            }

            val scrapedPrimaryText = scraped[ColorToken.TEXT_PRIMARY]
            if (scrapedPrimaryText != null && !scraped.containsKey(ColorToken.ICON_PRIMARY)) {
                scraped[ColorToken.ICON_PRIMARY] = scrapedPrimaryText
            }
            val scrapedSecondaryText = scraped[ColorToken.TEXT_SECONDARY]
            if (scrapedSecondaryText != null && !scraped.containsKey(ColorToken.ICON_MUTED)) {
                scraped[ColorToken.ICON_MUTED] = scrapedSecondaryText
            }

            liveOverrides.putAll(scraped)
            liveHeaderHeight = observedHeaderHeight
            liveStatusBarHeight = observedStatusBarHeight
            liveBottomInset = observedBottomInset

            newPalette =
                ManaPalette.forPreset(currentPresetState).withOverrides(
                    overrides = liveOverrides,
                    headerHeight = liveHeaderHeight,
                    statusBarHeight = liveStatusBarHeight,
                    bottomInset = liveBottomInset,
                )
            resolvedPalette = newPalette
            resolvedLegacyTheme = newPalette.toLegacyTheme()
            listenerSnap = if (listeners.isEmpty()) emptyList() else ArrayList(listeners)
        }

        return finishPaletteTransition(oldPalette, newPalette, listenerSnap, applyReskin)
    }

    private fun finishPaletteTransition(
        oldPalette: ManaPalette,
        newPalette: ManaPalette,
        listenerSnap: List<ThemeListener>,
        applyReskin: Boolean,
    ): UiOps {
        if (oldPalette == newPalette) {
            return UiOps.create()
        }

        for (listener in listenerSnap) {
            try {
                listener.onThemeChanged(oldPalette, newPalette)
            } catch (t: Throwable) {
                Log.w(Loader.TAG, "ThemeEngine listener threw", t)
            }
        }

        val reskin = computeReskinOps(oldPalette, newPalette)
        if (applyReskin && !reskin.isEmpty()) {
            Log.i(
                Loader.TAG,
                "ThemeEngine: live re-skin ${oldPalette.preset} -> ${newPalette.preset} " +
                    "(bg=0x${newPalette[ColorToken.BG_BASE_PRIMARY].toString(16)}, " +
                    "updates=${reskin.size()})",
            )
            Loader.applySyntheticOps(reskin)
        }
        return reskin
    }

    private fun trackIdLocked(
        set: LinkedHashSet<Int>,
        viewId: Int,
    ) {
        if (set.size >= MAX_TRACKED_NATIVE_VIEWS) {
            val iter = set.iterator()
            if (iter.hasNext()) {
                iter.next()
                iter.remove()
            }
        }
        set.add(viewId)
    }

    private fun forgetNativeViewLocked(viewId: Int) {
        trackedRootIds.remove(viewId)
        trackedHeaderIds.remove(viewId)
        trackedRowIds.remove(viewId)
        trackedDividerIds.remove(viewId)
        trackedPrimaryTextIds.remove(viewId)
        trackedSecondaryTextIds.remove(viewId)
        trackedMutedTextIds.remove(viewId)
        trackedPrimaryIconIds.remove(viewId)
        trackedMutedIconIds.remove(viewId)
    }

    private fun extractColorProp(
        op: UiOps.Op,
        name: String,
    ): Long {
        val p = op.findProp(name) ?: return -1L
        val v = p.value ?: return -1L
        val tag = v.tag()
        return if (tag == UiOps.TAG_INT32 || tag == UiOps.TAG_INT64) {
            v.asLong() and 0xFFFFFFFFL
        } else {
            -1L
        }
    }

    /**
     * True when [argb] is grey-ish (RGB channels within 32 of each other). Input backgrounds are
     * neutral in every Discord theme; a saturated match (e.g. a green banner that happens to share
     * the search box's 40dp/8dp/bordered shape) is the wrong view and must not become INPUT_BG.
     */
    private fun isNeutralColor(argb: Long): Boolean {
        val r = ((argb ushr 16) and 0xFFL).toInt()
        val g = ((argb ushr 8) and 0xFFL).toInt()
        val b = (argb and 0xFFL).toInt()
        return maxOf(r, g, b) - minOf(r, g, b) <= 32
    }

    private fun isOpaqueColor(argb: Long): Boolean {
        val alpha = ((argb ushr 24) and 0xFFL).toInt()
        return alpha >= 0xE0
    }

    private fun isLikelyCanvasColor(argb: Long): Boolean {
        val r = ((argb ushr 16) and 0xFFL).toInt()
        val g = ((argb ushr 8) and 0xFFL).toInt()
        val b = (argb and 0xFFL).toInt()
        val maxC = maxOf(r, maxOf(g, b))
        val minC = minOf(r, minOf(g, b))
        val delta = maxC - minC

        return delta <= 110
    }

    @JvmStatic
    fun resetForTests() {
        synchronized(lock) {
            currentPresetState = ThemePreset.DARK
            liveOverrides.clear()
            liveHeaderHeight = ManaUi.Theme.DEFAULT_HEADER_HEIGHT
            liveStatusBarHeight = ManaUi.Theme.DEFAULT_STATUS_BAR_HEIGHT
            liveBottomInset = ManaUi.Theme.DEFAULT_BOTTOM_INSET
            resolvedPalette = ManaPalette.DARK
            resolvedLegacyTheme = resolvedPalette.toLegacyTheme()
            bindingsByScope.clear()
            listeners.clear()
            trackedRootIds.clear()
            trackedHeaderIds.clear()
            trackedRowIds.clear()
            trackedDividerIds.clear()
            trackedPrimaryTextIds.clear()
            trackedSecondaryTextIds.clear()
            trackedMutedTextIds.clear()
            trackedPrimaryIconIds.clear()
            trackedMutedIconIds.clear()
        }
        activeScopeStack.remove()
        ManaMotion.resetForTests()
    }
}
