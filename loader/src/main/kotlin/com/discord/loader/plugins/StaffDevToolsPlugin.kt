package com.discord.loader.plugins

import android.content.Context
import android.util.Log
import com.discord.loader.CinderNative
import com.discord.loader.CinderSettingsPlugin
import com.discord.loader.Loader
import com.discord.loader.ModPlugin
import com.discord.loader.mana.ManaContext
import com.discord.loader.mana.ManaIcons
import com.discord.loader.mana.ManaPage
import com.discord.loader.mana.manaPage
import com.discord.loader.mana.stringState

class StaffDevToolsPlugin : ModPlugin {
    companion object {
        const val PLUGIN_ID = "staff-devtools"
        const val REF_ENABLE_TOGGLE = "staff-devtools.toggle.enabled"
        const val REF_FLAG_STATUS = "staff-devtools.value.flag"

        fun formatNativeFlagStatus(): String {
            if (!CinderNative.isStaffDevToolsSupported()) {
                return "Unsupported (No .bss slot)"
            }
            val flag = CinderNative.readStaffDevToolsFlag()
            return when (flag) {
                1 -> {
                    "ON (0x1)"
                }

                0 -> {
                    "OFF (0x0)"
                }

                else -> {
                    when {
                        CinderNative.getHostBase() == 0L -> "Pending Host Load"
                        !CinderNative.verifyHostBuildId() -> "Blocked (Build Drift)"
                        else -> "Unavailable"
                    }
                }
            }
        }
    }

    @Volatile
    private var appContext: Context? = null

    override fun id(): String = PLUGIN_ID

    override fun name(): String = "Staff DevTools"

    override fun version(): String = "1.0.0"

    override fun description(): String = "Unlocks Discord Staff Settings & Developer Tools at runtime"

    override fun icon(): String = ManaIcons.SPARKLES

    override fun isRequired(): Boolean = false

    override fun defaultEnabled(): Boolean = true

    override fun requiresRestart(): Boolean = false

    override fun onLoad(context: Context?) {
        appContext = context
        val enabled = Loader.isPluginEnabled(this)
        val appliedNow = CinderNative.setStaffDevTools(enabled)
        syncOtaDeveloperMode(context, enabled)
        Log.i(
            Loader.TAG,
            "StaffDevToolsPlugin v${version()} loaded (enabled=$enabled" +
                ", nativeApplied=$appliedNow" +
                ", pending=${CinderNative.hasPendingStaffDevTools()}" +
                ", flagVaddr=0x${java.lang.Long.toHexString(maxOf(0L, CinderNative.getStaffDevToolsFlagVaddr()))})",
        )
    }

    override fun onEnabledChanged(enabled: Boolean) {
        val appliedNow = CinderNative.setStaffDevTools(enabled)
        syncOtaDeveloperMode(appContext, enabled)
        Log.i(
            Loader.TAG,
            "StaffDevToolsPlugin toggled -> $enabled (nativeApplied=$appliedNow" +
                ", liveStatus=${formatNativeFlagStatus()})",
        )
    }

    override fun createSettingsPage(ctx: ManaContext): ManaPage =
        manaPage(
            title = "Staff DevTools",
            titleAccessibilityId = "cinder.plugin.staff-devtools.title",
        ) {
            val enabledState = CinderSettingsPlugin.pluginEnabledState(this@StaffDevToolsPlugin)
            val flagStatus = stringState(formatNativeFlagStatus())
            val vaddr = CinderNative.getStaffDevToolsFlagVaddr()

            enabledState.observe(scope) {
                flagStatus.value = formatNativeFlagStatus()
            }

            section(title = "Controls") {
                toggleRow(
                    title = "Enable Staff DevTools",
                    subtitle = "Shows Staff Settings, Experiments & DevTools playgrounds",
                    icon = ManaIcons.SPARKLES,
                    state = enabledState,
                    accessibilityId = REF_ENABLE_TOGGLE,
                    refKey = REF_ENABLE_TOGGLE,
                ) { next ->
                    Loader.setPluginEnabled(this@StaffDevToolsPlugin, next)
                    flagStatus.value = formatNativeFlagStatus()
                }
            }

            section(title = "Native Predicate") {
                valueRow("Live .bss Predicate", flagStatus, icon = ManaIcons.SPEEDOMETER, refKey = REF_FLAG_STATUS)
                valueRow(
                    "Flag Virtual Address",
                    if (vaddr > 0L) "0x${java.lang.Long.toHexString(vaddr)}" else "None",
                    icon = ManaIcons.LINK,
                )
                valueRow(
                    "Build ID Verification",
                    if (CinderNative.verifyHostBuildId()) "Verified" else "Pending / Unverified",
                    icon = ManaIcons.SHIELD_LOCK,
                )
            }
        }

    private fun syncOtaDeveloperMode(
        context: Context?,
        enabled: Boolean,
    ) {
        if (context == null) return
        try {
            val sp = context.getSharedPreferences("libdiscord.ota", Context.MODE_PRIVATE)
            val current = sp.getBoolean("developerMode", false)
            if (current != enabled) {
                sp.edit().putBoolean("developerMode", enabled).commit()
                Log.i(Loader.TAG, "StaffDevToolsPlugin: libdiscord.ota developerMode=$enabled")
            }
        } catch (t: Throwable) {
            Log.w(Loader.TAG, "StaffDevToolsPlugin: developerMode pref write failed", t)
        }
    }
}
