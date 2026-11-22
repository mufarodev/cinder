package com.discord.loader.plugins

import android.content.Context
import android.util.Log
import com.discord.loader.CinderNative
import com.discord.loader.Loader
import com.discord.loader.ModPlugin
import com.discord.loader.mana.ManaContext
import com.discord.loader.mana.ManaIcons
import com.discord.loader.mana.ManaPage
import com.discord.loader.mana.manaPage

class PhoenixAccessPlugin : ModPlugin {
    companion object {
        const val PLUGIN_ID = "release-access"
        const val EXPERIMENT_NAME = "2026-11-release-app-access"
        const val EXPERIMENT_TREATMENT = "Variant 1 (Enabled)"

        internal fun shortHex(hex: String?): String {
            if (hex.isNullOrEmpty()) return "Unavailable"
            return if (hex.length <= 16) hex else hex.substring(0, 16) + "\u2026"
        }

        internal fun verificationSummary(
            manifest: CinderNative.PatchManifestInfo,
            liveBuildId: String?,
        ): String {
            if (liveBuildId.isNullOrEmpty()) {
                return if (CinderNative.getHostBase() == 0L) "Pending Host Load" else "Build ID Unavailable"
            }
            if (manifest.buildId.isEmpty()) {
                return "Live ELF Loaded (No Manifest)"
            }
            return if (manifest.buildId.equals(liveBuildId, ignoreCase = true)) {
                "Verified (Exact Match)"
            } else {
                "Build Drift Detected"
            }
        }
    }

    override fun id(): String = PLUGIN_ID

    override fun version(): String = "1.0.0"

    override fun description(): String = "Unlocks Discord Phoenix mobile access & verifies native host patches"

    override fun icon(): String = ManaIcons.KEY

    override fun isRequired(): Boolean = true

    override fun onLoad(context: Context?) {
        val manifest = CinderNative.getPatchManifestInfo()
        val liveBid = CinderNative.getLiveBuildId()
        val hostBase = CinderNative.getHostBase()
        Log.i(
            Loader.TAG,
            "PhoenixAccessPlugin v${version()} loaded ($EXPERIMENT_NAME=$EXPERIMENT_TREATMENT" +
                ", manifestBuild=${shortHex(manifest.buildId)}" +
                ", liveBuild=${shortHex(liveBid)}" +
                ", hostBase=0x${java.lang.Long.toHexString(hostBase)}" +
                ", patches=${manifest.patchTags.size}" +
                ", status=${verificationSummary(manifest, liveBid)})",
        )
    }

    override fun createSettingsPage(ctx: ManaContext): ManaPage =
        manaPage(
            title = "Release Access",
            subtitle = "Core Plugin",
            titleAccessibilityId = "cinder.plugin.release-access.title",
        ) {
            val manifest = CinderNative.getPatchManifestInfo()
            val liveBuildId = CinderNative.getLiveBuildId()
            val hostBase = CinderNative.getHostBase()
            val verification = verificationSummary(manifest, liveBuildId)

            section(title = "Access Override") {
                valueRow("Experiment", EXPERIMENT_NAME, icon = ManaIcons.KEY)
                valueRow("Treatment", EXPERIMENT_TREATMENT, icon = ManaIcons.CIRCLE_CHECK)
            }

            section(title = "Host ELF Verification") {
                valueRow("Verification", verification, icon = ManaIcons.SHIELD_LOCK)
                valueRow(
                    "Host Load Base",
                    if (hostBase != 0L) "0x${java.lang.Long.toHexString(hostBase)}" else "Pending Host Load",
                    icon = ManaIcons.LINK,
                )
                valueRow("Live Build ID", shortHex(liveBuildId), icon = ManaIcons.FILE)
                valueRow("Manifest Build ID", shortHex(manifest.buildId), icon = ManaIcons.FILE)
            }

            section(title = "Native Host Patches") {
                if (manifest.patchTags.isEmpty()) {
                    valueRow("Active Patches", "Fallback static table", icon = ManaIcons.WRENCH)
                } else {
                    for (tag in manifest.patchTags) {
                        valueRow(tag, "Active", icon = ManaIcons.CIRCLE_CHECK)
                    }
                }
                if (manifest.runtimeFlags.isNotEmpty()) {
                    for ((flagName, vaddr) in manifest.runtimeFlags) {
                        valueRow(
                            "Flag: $flagName",
                            "0x${java.lang.Long.toHexString(vaddr)}",
                            icon = ManaIcons.SPEEDOMETER,
                        )
                    }
                }
            }
        }
}
