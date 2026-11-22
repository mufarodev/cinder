package com.discord.loader.mana

import com.discord.loader.ManaUi
import kotlin.math.sqrt

enum class ThemePreset(
    val isDark: Boolean,
    val anchorBg: Long,
) {
    LIGHT(isDark = false, anchorBg = 0xFFF2F3F5L),
    ASH(isDark = true, anchorBg = 0xFF242429L),
    DARK(isDark = true, anchorBg = 0xFF1A1A1EL),
    ONYX(isDark = true, anchorBg = 0xFF070709L),
}

class ManaPalette private constructor(
    val preset: ThemePreset,
    private val colors: LongArray,
    val headerHeight: Double,
    val statusBarHeight: Double,
    val bottomInset: Double,
) {
    operator fun get(token: ColorToken): Long = colors[token.ordinal]

    fun color(token: ColorToken): Long = colors[token.ordinal]

    fun withOverrides(
        overrides: Map<ColorToken, Long>,
        headerHeight: Double = this.headerHeight,
        statusBarHeight: Double = this.statusBarHeight,
        bottomInset: Double = this.bottomInset,
    ): ManaPalette {
        if (overrides.isEmpty() &&
            headerHeight == this.headerHeight &&
            statusBarHeight == this.statusBarHeight &&
            bottomInset == this.bottomInset
        ) {
            return this
        }
        val next = colors.copyOf()
        for ((token, argb) in overrides) {
            next[token.ordinal] = argb and 0xFFFFFFFFL
        }
        return ManaPalette(preset, next, headerHeight, statusBarHeight, bottomInset)
    }

    fun withOverride(
        token: ColorToken,
        argb: Long,
    ): ManaPalette {
        val masked = argb and 0xFFFFFFFFL
        if (colors[token.ordinal] == masked) return this
        val next = colors.copyOf()
        next[token.ordinal] = masked
        return ManaPalette(preset, next, headerHeight, statusBarHeight, bottomInset)
    }

    fun toLegacyTheme(): ManaUi.Theme =
        ManaUi.Theme(
            this[ColorToken.BG_BASE_PRIMARY],
            this[ColorToken.BG_BASE_SECONDARY],
            this[ColorToken.BG_ROW_ACTIVE],
            this[ColorToken.TEXT_PRIMARY],
            this[ColorToken.TEXT_SECONDARY],
            this[ColorToken.TEXT_MUTED],
            this[ColorToken.DIVIDER],
            this[ColorToken.BRAND_500],
            this[ColorToken.DANGER_TEXT],
            this[ColorToken.DANGER],
            this[ColorToken.SWITCH_ON_BG],
            this[ColorToken.SWITCH_ON_BORDER],
            this[ColorToken.SWITCH_OFF_BG],
            this[ColorToken.SWITCH_OFF_BORDER],
            this[ColorToken.SWITCH_THUMB],
            this[ColorToken.RADIO_UNCHECKED_BORDER],
            this[ColorToken.SEGMENT_TRACK_BG],
            this[ColorToken.SEGMENT_THUMB_BG],
            this[ColorToken.BUTTON_BORDER],
            this[ColorToken.BUTTON_PRIMARY_TEXT],
            this[ColorToken.SHEET_HANDLE],
            headerHeight,
            statusBarHeight,
            bottomInset,
        )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ManaPalette) return false
        return preset == other.preset &&
            colors.contentEquals(other.colors) &&
            headerHeight == other.headerHeight &&
            statusBarHeight == other.statusBarHeight &&
            bottomInset == other.bottomInset
    }

    override fun hashCode(): Int {
        var result = preset.hashCode()
        result = 31 * result + colors.contentHashCode()
        result = 31 * result + headerHeight.hashCode()
        result = 31 * result + statusBarHeight.hashCode()
        result = 31 * result + bottomInset.hashCode()
        return result
    }

    override fun toString(): String =
        "ManaPalette(preset=$preset, bg=0x${this[ColorToken.BG_BASE_PRIMARY].toString(16)}, " +
            "text=0x${this[ColorToken.TEXT_PRIMARY].toString(16)})"

    companion object {
        private val TOKEN_COUNT = ColorToken.values().size

        private fun buildPreset(
            preset: ThemePreset,
            init: (LongArray) -> Unit,
        ): ManaPalette {
            val arr = LongArray(TOKEN_COUNT)
            init(arr)
            return ManaPalette(
                preset = preset,
                colors = arr,
                headerHeight = ManaUi.Theme.DEFAULT_HEADER_HEIGHT,
                statusBarHeight = ManaUi.Theme.DEFAULT_STATUS_BAR_HEIGHT,
                bottomInset = ManaUi.Theme.DEFAULT_BOTTOM_INSET,
            )
        }

        private fun LongArray.setToken(
            token: ColorToken,
            argb: Long,
        ) {
            this[token.ordinal] = argb and 0xFFFFFFFFL
        }

        @JvmField
        val ASH: ManaPalette =
            buildPreset(ThemePreset.ASH) { c ->
                c.setToken(ColorToken.BG_BASE_PRIMARY, 0xFF242429L)
                c.setToken(ColorToken.BG_BASE_SECONDARY, 0x1497979FL)
                c.setToken(ColorToken.BG_BASE_TERTIARY, 0xFF202024L)
                c.setToken(ColorToken.BG_MOD_SUBTLE, 0x1F97979FL)
                c.setToken(ColorToken.BG_ROW_ACTIVE, 0x12FFFFFFL)
                c.setToken(ColorToken.HEADER_BG, 0xFF242429L)
                c.setToken(ColorToken.SHEET_BG, 0xFF242429L)
                c.setToken(ColorToken.SHEET_HANDLE, 0x3397979FL)
                c.setToken(ColorToken.TEXT_PRIMARY, 0xFFFBFBFBL)
                c.setToken(ColorToken.TEXT_SECONDARY, 0xFFABACB2L)
                c.setToken(ColorToken.TEXT_MUTED, 0xFF96979EL)
                c.setToken(ColorToken.TEXT_LINK, 0xFF4D96EEL)
                c.setToken(ColorToken.ICON_PRIMARY, 0xFFFBFBFBL)
                c.setToken(ColorToken.ICON_MUTED, 0xFFABACB2L)
                c.setToken(ColorToken.DIVIDER, 0x1F97979FL)
                c.setToken(ColorToken.BRAND_500, 0xFF5865F2L)
                c.setToken(ColorToken.DANGER, 0xFFD22D39L)
                c.setToken(ColorToken.DANGER_TEXT, 0xFFF87E7AL)
                c.setToken(ColorToken.POSITIVE, 0xFF23A55AL)
                c.setToken(ColorToken.WARNING, 0xFFF0B232L)
                c.setToken(ColorToken.CALLOUT_INFO_BG, 0x244D96EEL)
                c.setToken(ColorToken.CALLOUT_WARNING_BG, 0x24F0B232L)
                c.setToken(ColorToken.CALLOUT_DANGER_BG, 0x24D22D39L)
                c.setToken(ColorToken.CALLOUT_POSITIVE_BG, 0x2423A55AL)
                c.setToken(ColorToken.CALLOUT_BRAND_BG, 0x245865F2L)
                c.setToken(ColorToken.BUTTON_PRIMARY_BG, 0xFF5865F2L)
                c.setToken(ColorToken.BUTTON_PRIMARY_TEXT, 0xFFFFFFFFL)
                c.setToken(ColorToken.BUTTON_BORDER, 0x14FFFFFFL)
                c.setToken(ColorToken.INPUT_BG, 0x1F000000L)
                c.setToken(ColorToken.INPUT_BORDER, 0x3397979FL)
                c.setToken(ColorToken.INPUT_TEXT, 0xFFEFEFF1L)
                c.setToken(ColorToken.INPUT_PLACEHOLDER, 0xFF8F9097L)
                c.setToken(ColorToken.SWITCH_ON_BG, 0xFF5865F2L)
                c.setToken(ColorToken.SWITCH_ON_BORDER, 0x1F97979FL)
                c.setToken(ColorToken.SWITCH_OFF_BG, 0x1F000000L)
                c.setToken(ColorToken.SWITCH_OFF_BORDER, 0x3397979FL)
                c.setToken(ColorToken.SWITCH_THUMB, 0xFFFFFFFFL)
                c.setToken(ColorToken.RADIO_UNCHECKED_BORDER, 0xA397979FL)
                c.setToken(ColorToken.SEGMENT_TRACK_BG, 0x1F97979FL)
                c.setToken(ColorToken.SEGMENT_THUMB_BG, 0x3397979FL)
            }

        @JvmField
        val DARK: ManaPalette =
            buildPreset(ThemePreset.DARK) { c ->
                c.setToken(ColorToken.BG_BASE_PRIMARY, 0xFF1A1A1EL)
                c.setToken(ColorToken.BG_BASE_SECONDARY, 0x1497979FL)
                c.setToken(ColorToken.BG_BASE_TERTIARY, 0xFF121214L)
                c.setToken(ColorToken.BG_MOD_SUBTLE, 0x1F97979FL)
                c.setToken(ColorToken.BG_ROW_ACTIVE, 0x12FFFFFFL)
                c.setToken(ColorToken.HEADER_BG, 0xFF1A1A1EL)
                c.setToken(ColorToken.SHEET_BG, 0xFF1A1A1EL)
                c.setToken(ColorToken.SHEET_HANDLE, 0x3397979FL)
                c.setToken(ColorToken.TEXT_PRIMARY, 0xFFFBFBFBL)
                c.setToken(ColorToken.TEXT_SECONDARY, 0xFFABACB2L)
                c.setToken(ColorToken.TEXT_MUTED, 0xFF94959CL)
                c.setToken(ColorToken.TEXT_LINK, 0xFF4D96EEL)
                c.setToken(ColorToken.ICON_PRIMARY, 0xFFFBFBFBL)
                c.setToken(ColorToken.ICON_MUTED, 0xFFABACB2L)
                c.setToken(ColorToken.DIVIDER, 0x1F97979FL)
                c.setToken(ColorToken.BRAND_500, 0xFF5865F2L)
                c.setToken(ColorToken.DANGER, 0xFFD22D39L)
                c.setToken(ColorToken.DANGER_TEXT, 0xFFF87E7AL)
                c.setToken(ColorToken.POSITIVE, 0xFF23A55AL)
                c.setToken(ColorToken.WARNING, 0xFFF0B232L)
                c.setToken(ColorToken.CALLOUT_INFO_BG, 0x244D96EEL)
                c.setToken(ColorToken.CALLOUT_WARNING_BG, 0x24F0B232L)
                c.setToken(ColorToken.CALLOUT_DANGER_BG, 0x24D22D39L)
                c.setToken(ColorToken.CALLOUT_POSITIVE_BG, 0x2423A55AL)
                c.setToken(ColorToken.CALLOUT_BRAND_BG, 0x245865F2L)
                c.setToken(ColorToken.BUTTON_PRIMARY_BG, 0xFF5865F2L)
                c.setToken(ColorToken.BUTTON_PRIMARY_TEXT, 0xFFFFFFFFL)
                c.setToken(ColorToken.BUTTON_BORDER, 0x14FFFFFFL)
                c.setToken(ColorToken.INPUT_BG, 0x29000000L)
                c.setToken(ColorToken.INPUT_BORDER, 0x3397979FL)
                c.setToken(ColorToken.INPUT_TEXT, 0xFFEFEFF1L)
                c.setToken(ColorToken.INPUT_PLACEHOLDER, 0xFF8F9097L)
                c.setToken(ColorToken.SWITCH_ON_BG, 0xFF5865F2L)
                c.setToken(ColorToken.SWITCH_ON_BORDER, 0x1F97979FL)
                c.setToken(ColorToken.SWITCH_OFF_BG, 0x1F000000L)
                c.setToken(ColorToken.SWITCH_OFF_BORDER, 0x3397979FL)
                c.setToken(ColorToken.SWITCH_THUMB, 0xFFFFFFFFL)
                c.setToken(ColorToken.RADIO_UNCHECKED_BORDER, 0xA397979FL)
                c.setToken(ColorToken.SEGMENT_TRACK_BG, 0x1F97979FL)
                c.setToken(ColorToken.SEGMENT_THUMB_BG, 0x3397979FL)
            }

        @JvmField
        val ONYX: ManaPalette =
            buildPreset(ThemePreset.ONYX) { c ->
                c.setToken(ColorToken.BG_BASE_PRIMARY, 0xFF070709L)
                c.setToken(ColorToken.BG_BASE_SECONDARY, 0xFF131416L)
                c.setToken(ColorToken.BG_BASE_TERTIARY, 0xFF000000L)
                c.setToken(ColorToken.BG_MOD_SUBTLE, 0x1F97979FL)
                c.setToken(ColorToken.BG_ROW_ACTIVE, 0x1AFFFFFFL)
                c.setToken(ColorToken.HEADER_BG, 0xFF070709L)
                c.setToken(ColorToken.SHEET_BG, 0xFF0F1012L)
                c.setToken(ColorToken.SHEET_HANDLE, 0x3397979FL)
                c.setToken(ColorToken.TEXT_PRIMARY, 0xFFFBFBFBL)
                c.setToken(ColorToken.TEXT_SECONDARY, 0xFFABACB2L)
                c.setToken(ColorToken.TEXT_MUTED, 0xFF82838BL)
                c.setToken(ColorToken.TEXT_LINK, 0xFF4D96EEL)
                c.setToken(ColorToken.ICON_PRIMARY, 0xFFFBFBFBL)
                c.setToken(ColorToken.ICON_MUTED, 0xFFABACB2L)
                c.setToken(ColorToken.DIVIDER, 0x1F97979FL)
                c.setToken(ColorToken.BRAND_500, 0xFF5865F2L)
                c.setToken(ColorToken.DANGER, 0xFFD22D39L)
                c.setToken(ColorToken.DANGER_TEXT, 0xFFF87E7AL)
                c.setToken(ColorToken.POSITIVE, 0xFF23A55AL)
                c.setToken(ColorToken.WARNING, 0xFFF0B232L)
                c.setToken(ColorToken.CALLOUT_INFO_BG, 0x284D96EEL)
                c.setToken(ColorToken.CALLOUT_WARNING_BG, 0x28F0B232L)
                c.setToken(ColorToken.CALLOUT_DANGER_BG, 0x28D22D39L)
                c.setToken(ColorToken.CALLOUT_POSITIVE_BG, 0x2823A55AL)
                c.setToken(ColorToken.CALLOUT_BRAND_BG, 0x285865F2L)
                c.setToken(ColorToken.BUTTON_PRIMARY_BG, 0xFF5865F2L)
                c.setToken(ColorToken.BUTTON_PRIMARY_TEXT, 0xFFFFFFFFL)
                c.setToken(ColorToken.BUTTON_BORDER, 0x14FFFFFFL)
                c.setToken(ColorToken.INPUT_BG, 0xFF131416L)
                c.setToken(ColorToken.INPUT_BORDER, 0x3397979FL)
                c.setToken(ColorToken.INPUT_TEXT, 0xFFFBFBFBL)
                c.setToken(ColorToken.INPUT_PLACEHOLDER, 0xFF82838BL)
                c.setToken(ColorToken.SWITCH_ON_BG, 0xFF5865F2L)
                c.setToken(ColorToken.SWITCH_ON_BORDER, 0x1F97979FL)
                c.setToken(ColorToken.SWITCH_OFF_BG, 0x2997979FL)
                c.setToken(ColorToken.SWITCH_OFF_BORDER, 0x3D97979FL)
                c.setToken(ColorToken.SWITCH_THUMB, 0xFFFFFFFFL)
                c.setToken(ColorToken.RADIO_UNCHECKED_BORDER, 0xA397979FL)
                c.setToken(ColorToken.SEGMENT_TRACK_BG, 0x1F97979FL)
                c.setToken(ColorToken.SEGMENT_THUMB_BG, 0x3397979FL)
            }

        @JvmField
        val LIGHT: ManaPalette =
            buildPreset(ThemePreset.LIGHT) { c ->
                c.setToken(ColorToken.BG_BASE_PRIMARY, 0xFFF2F3F5L)
                c.setToken(ColorToken.BG_BASE_SECONDARY, 0xFFFFFFFFL)
                c.setToken(ColorToken.BG_BASE_TERTIARY, 0xFFE3E5E8L)
                c.setToken(ColorToken.BG_MOD_SUBTLE, 0x14060607L)
                c.setToken(ColorToken.BG_ROW_ACTIVE, 0x14060607L)
                c.setToken(ColorToken.HEADER_BG, 0xFFF2F3F5L)
                c.setToken(ColorToken.SHEET_BG, 0xFFF2F3F5L)
                c.setToken(ColorToken.SHEET_HANDLE, 0x33060607L)
                c.setToken(ColorToken.TEXT_PRIMARY, 0xFF060607L)
                c.setToken(ColorToken.TEXT_SECONDARY, 0xFF4E5058L)
                c.setToken(ColorToken.TEXT_MUTED, 0xFF5C5E66L)
                c.setToken(ColorToken.TEXT_LINK, 0xFF006CE7L)
                c.setToken(ColorToken.ICON_PRIMARY, 0xFF060607L)
                c.setToken(ColorToken.ICON_MUTED, 0xFF4E5058L)
                c.setToken(ColorToken.DIVIDER, 0x1F060607L)
                c.setToken(ColorToken.BRAND_500, 0xFF5865F2L)
                c.setToken(ColorToken.DANGER, 0xFFDA373CL)
                c.setToken(ColorToken.DANGER_TEXT, 0xFFC62828L)
                c.setToken(ColorToken.POSITIVE, 0xFF1A7F46L)
                c.setToken(ColorToken.WARNING, 0xFFCA8A04L)
                c.setToken(ColorToken.CALLOUT_INFO_BG, 0x1A006CE7L)
                c.setToken(ColorToken.CALLOUT_WARNING_BG, 0x1FCA8A04L)
                c.setToken(ColorToken.CALLOUT_DANGER_BG, 0x1ADA373CL)
                c.setToken(ColorToken.CALLOUT_POSITIVE_BG, 0x1A1A7F46L)
                c.setToken(ColorToken.CALLOUT_BRAND_BG, 0x1A5865F2L)
                c.setToken(ColorToken.BUTTON_PRIMARY_BG, 0xFF5865F2L)
                c.setToken(ColorToken.BUTTON_PRIMARY_TEXT, 0xFFFFFFFFL)
                c.setToken(ColorToken.BUTTON_BORDER, 0x14000000L)
                c.setToken(ColorToken.INPUT_BG, 0x14060607L)
                c.setToken(ColorToken.INPUT_BORDER, 0x29060607L)
                c.setToken(ColorToken.INPUT_TEXT, 0xFF060607L)
                c.setToken(ColorToken.INPUT_PLACEHOLDER, 0xFF5C5E66L)
                c.setToken(ColorToken.SWITCH_ON_BG, 0xFF5865F2L)
                c.setToken(ColorToken.SWITCH_ON_BORDER, 0x14000000L)
                c.setToken(ColorToken.SWITCH_OFF_BG, 0x29060607L)
                c.setToken(ColorToken.SWITCH_OFF_BORDER, 0x33060607L)
                c.setToken(ColorToken.SWITCH_THUMB, 0xFFFFFFFFL)
                c.setToken(ColorToken.RADIO_UNCHECKED_BORDER, 0x804E5058L)
                c.setToken(ColorToken.SEGMENT_TRACK_BG, 0x14060607L)
                c.setToken(ColorToken.SEGMENT_THUMB_BG, 0xFFFFFFFFL)
            }

        @JvmStatic
        fun forPreset(preset: ThemePreset): ManaPalette =
            when (preset) {
                ThemePreset.LIGHT -> LIGHT
                ThemePreset.ASH -> ASH
                ThemePreset.DARK -> DARK
                ThemePreset.ONYX -> ONYX
            }

        @JvmStatic
        fun luminance(argb: Long): Double {
            val r = ((argb ushr 16) and 0xFFL).toDouble() / 255.0
            val g = ((argb ushr 8) and 0xFFL).toDouble() / 255.0
            val b = (argb and 0xFFL).toDouble() / 255.0
            return 0.2126 * r + 0.7152 * g + 0.0722 * b
        }

        @JvmStatic
        fun rgbDistance(
            a: Long,
            b: Long,
        ): Double {
            val dr = ((a ushr 16) and 0xFFL).toDouble() - ((b ushr 16) and 0xFFL).toDouble()
            val dg = ((a ushr 8) and 0xFFL).toDouble() - ((b ushr 8) and 0xFFL).toDouble()
            val db = (a and 0xFFL).toDouble() - (b and 0xFFL).toDouble()
            return sqrt(dr * dr + dg * dg + db * db)
        }

        @JvmStatic
        fun matchPreset(screenBgArgb: Long): ThemePreset {
            val lum = luminance(screenBgArgb)
            if (lum >= 0.50) {
                return ThemePreset.LIGHT
            }
            val darkCandidates = arrayOf(ThemePreset.ONYX, ThemePreset.DARK, ThemePreset.ASH)
            var best = ThemePreset.DARK
            var bestScore = Double.MAX_VALUE
            for (candidate in darkCandidates) {
                val candLum = luminance(candidate.anchorBg)
                val lumDiff = kotlin.math.abs(lum - candLum)
                val distNorm = rgbDistance(screenBgArgb, candidate.anchorBg) / 441.67295593
                val score = lumDiff * 0.80 + distNorm * 0.20
                if (score < bestScore) {
                    bestScore = score
                    best = candidate
                }
            }
            return best
        }
    }
}
