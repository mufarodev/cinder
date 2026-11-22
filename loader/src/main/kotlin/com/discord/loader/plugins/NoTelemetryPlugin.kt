package com.discord.loader.plugins

import android.content.Context
import android.util.Log
import com.discord.loader.BridgeRegistry
import com.discord.loader.CinderSettingsPlugin
import com.discord.loader.Frame
import com.discord.loader.Loader
import com.discord.loader.ModPlugin
import com.discord.loader.mana.ButtonStyle
import com.discord.loader.mana.ManaContext
import com.discord.loader.mana.ManaIcons
import com.discord.loader.mana.ManaPage
import com.discord.loader.mana.ManaToastTone
import com.discord.loader.mana.manaPage
import com.discord.loader.mana.stringState
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class NoTelemetryPlugin : ModPlugin {
    companion object {
        const val PLUGIN_ID = "no-telemetry"
        const val REF_ENABLE_TOGGLE = "no-telemetry.toggle.enabled"
        const val REF_RESET_BUTTON = "no-telemetry.btn.reset"
        const val REF_RESTART_BUTTON = "no-telemetry.btn.restart"

        private val REWRITE_HTTPS = "cinder::".toByteArray(StandardCharsets.US_ASCII)
        private val REWRITE_HTTP = "cndr::/".toByteArray(StandardCharsets.US_ASCII)

        internal enum class UrlCategory {
            NONE,
            SCIENCE,
            ERRMON,
        }

        internal data class UrlRewriteResult(
            val category: UrlCategory,
            val mutatedPayload: ByteArray?,
            val matchedUrl: String?,
        )

        internal fun inspectAndRewriteNetworkingPayload(payload: ByteArray?): UrlRewriteResult {
            if (payload == null || payload.isEmpty()) {
                return UrlRewriteResult(UrlCategory.NONE, null, null)
            }

            var pos = 0
            val len = payload.size
            while (pos < len) {
                val schemeLen = matchHttpSchemeAt(payload, pos)
                if (schemeLen > 0) {
                    val urlStart = pos
                    var urlEnd = pos + schemeLen
                    while (urlEnd < len && isUrlChar(payload[urlEnd].toInt() and 0xFF)) {
                        urlEnd++
                    }
                    val urlStr = String(payload, urlStart, urlEnd - urlStart, StandardCharsets.US_ASCII)
                    val category = classifyUrl(urlStr, schemeLen)
                    if (category != UrlCategory.NONE) {
                        val copy = Arrays.copyOf(payload, payload.size)
                        val replacement = if (schemeLen == 8) REWRITE_HTTPS else REWRITE_HTTP
                        System.arraycopy(replacement, 0, copy, urlStart, replacement.size)
                        return UrlRewriteResult(category, copy, urlStr)
                    }
                    pos = urlEnd
                } else {
                    pos++
                }
            }

            val textLower = String(payload, StandardCharsets.ISO_8859_1).lowercase(Locale.ROOT)
            if (textLower.contains("release-errmon") || textLower.contains("errmon.disabled")) {
                return UrlRewriteResult(UrlCategory.ERRMON, null, "errmon")
            }
            if (hasSciencePathToken(textLower)) {
                return UrlRewriteResult(UrlCategory.SCIENCE, null, "/science")
            }
            return UrlRewriteResult(UrlCategory.NONE, null, null)
        }

        private fun matchHttpSchemeAt(
            buf: ByteArray,
            idx: Int,
        ): Int {
            val rem = buf.size - idx
            if (rem < 7) return 0
            if (asciiLower(buf[idx]) != 'h'.code ||
                asciiLower(buf[idx + 1]) != 't'.code ||
                asciiLower(buf[idx + 2]) != 't'.code ||
                asciiLower(buf[idx + 3]) != 'p'.code
            ) {
                return 0
            }
            if (rem >= 8 &&
                asciiLower(buf[idx + 4]) == 's'.code &&
                buf[idx + 5] == ':'.code.toByte() &&
                buf[idx + 6] == '/'.code.toByte() &&
                buf[idx + 7] == '/'.code.toByte()
            ) {
                return 8
            }
            if (buf[idx + 4] == ':'.code.toByte() &&
                buf[idx + 5] == '/'.code.toByte() &&
                buf[idx + 6] == '/'.code.toByte()
            ) {
                return 7
            }
            return 0
        }

        private fun asciiLower(b: Byte): Int {
            val c = b.toInt() and 0xFF
            return if (c in 'A'.code..'Z'.code) c + 32 else c
        }

        private fun isUrlChar(b: Int): Boolean {
            if (b <= 0x20 || b >= 0x7F) return false
            return b != '"'.code && b != '\''.code && b != '<'.code && b != '>'.code &&
                b != '\\'.code && b != '{'.code && b != '}'.code
        }

        internal fun classifyUrl(
            url: String,
            schemeLen: Int,
        ): UrlCategory {
            val lower = url.lowercase(Locale.ROOT)
            val afterScheme = lower.substring(schemeLen)
            val slashIdx = afterScheme.indexOf('/')
            val queryIdx = afterScheme.indexOf('?')
            val hostEnd =
                when {
                    slashIdx >= 0 && queryIdx >= 0 -> minOf(slashIdx, queryIdx)
                    slashIdx >= 0 -> slashIdx
                    queryIdx >= 0 -> queryIdx
                    else -> afterScheme.length
                }
            val hostPort = afterScheme.substring(0, hostEnd)
            val colonIdx = hostPort.indexOf(':')
            val host = if (colonIdx >= 0) hostPort.substring(0, colonIdx) else hostPort
            val pathAndQuery = if (hostEnd < afterScheme.length) afterScheme.substring(hostEnd) else ""

            if (host.contains("errmon") || host.contains("sentry") || pathAndQuery.contains("/errmon")) {
                return UrlCategory.ERRMON
            }
            if (hasSciencePathToken(pathAndQuery)) {
                return UrlCategory.SCIENCE
            }
            return UrlCategory.NONE
        }

        private fun hasSciencePathToken(s: String): Boolean {
            var from = 0
            while (true) {
                val idx = s.indexOf("/science", from)
                if (idx < 0) return false
                val end = idx + "/science".length
                if (end == s.length) return true
                val next = s[end]
                if (next == '?' || next == '/' || next == '#' || next == '&' ||
                    next == '"' || next == '\'' || next.code <= 0x20 || next.code >= 0x7F
                ) {
                    return true
                }
                from = idx + 1
            }
        }

        private fun disableJavaTelemetry(context: Context): Boolean {
            var disabledAny = false
            try {
                val fa = Class.forName("com.google.firebase.analytics.FirebaseAnalytics")
                val inst = fa.getMethod("getInstance", Context::class.java).invoke(null, context)
                fa.getMethod("setAnalyticsCollectionEnabled", Boolean::class.javaPrimitiveType).invoke(inst, false)
                Log.i(Loader.TAG, "NoTelemetryPlugin: firebase analytics collection disabled")
                disabledAny = true
            } catch (t: Throwable) {
                Log.w(Loader.TAG, "NoTelemetryPlugin: firebase analytics kill skipped: $t")
            }
            try {
                val af = Class.forName("com.appsflyer.AppsFlyerLib")
                val inst = af.getMethod("getInstance").invoke(null)
                af
                    .getMethod("stop", Boolean::class.javaPrimitiveType, Context::class.java)
                    .invoke(inst, java.lang.Boolean.TRUE, context)
                for (mn in arrayOf("setCollectAndroidID", "setCollectIMEI", "setCollectOaid")) {
                    try {
                        af.getMethod(mn, Boolean::class.javaPrimitiveType).invoke(inst, java.lang.Boolean.FALSE)
                    } catch (_: Throwable) {
                    }
                }
                try {
                    af
                        .getMethod("enableTCFDataCollection", Boolean::class.javaPrimitiveType)
                        .invoke(inst, java.lang.Boolean.FALSE)
                } catch (_: Throwable) {
                }
                Log.i(Loader.TAG, "NoTelemetryPlugin: appsflyer telemetry disabled")
                disabledAny = true
            } catch (t: Throwable) {
                Log.w(Loader.TAG, "NoTelemetryPlugin: appsflyer telemetry kill skipped: $t")
            }
            return disabledAny
        }
    }

    private val blockedScience = AtomicInteger()
    private val blockedErrmon = AtomicInteger()
    private val blockedCrashReports = AtomicInteger()
    private val blockedTracking = AtomicInteger()

    @Volatile
    private var javaSdksKilledAtBoot = false

    override fun id(): String = PLUGIN_ID

    override fun version(): String = "1.0.0"

    override fun description(): String = "Blocks Discord science, errmon, crash reports & analytics SDKs"

    override fun icon(): String = ManaIcons.SHIELD_LOCK

    override fun isRequired(): Boolean = false

    override fun defaultEnabled(): Boolean = true

    override fun requiresRestart(): Boolean = true

    fun blockedScienceCount(): Int = blockedScience.get()

    fun blockedErrmonCount(): Int = blockedErrmon.get()

    fun blockedCrashReportsCount(): Int = blockedCrashReports.get()

    fun blockedTrackingCount(): Int = blockedTracking.get()

    fun totalBlockedCount(): Int = blockedScience.get() + blockedErrmon.get() + blockedCrashReports.get() + blockedTracking.get()

    fun wasJavaSdkKilledAtBoot(): Boolean = javaSdksKilledAtBoot

    fun resetCounters() {
        blockedScience.set(0)
        blockedErrmon.set(0)
        blockedCrashReports.set(0)
        blockedTracking.set(0)
    }

    override fun onLoad(context: Context?) {
        val enabled = Loader.isPluginEnabled(this)
        if (!enabled) {
            javaSdksKilledAtBoot = false
            Log.i(Loader.TAG, "NoTelemetryPlugin v${version()} loaded (disabled at boot; Firebase/AppsFlyer left active)")
            return
        }
        if (context != null) {
            javaSdksKilledAtBoot = disableJavaTelemetry(context)
        }
        Log.i(
            Loader.TAG,
            "NoTelemetryPlugin v${version()} loaded (enabled=true, javaSdksKilled=$javaSdksKilledAtBoot)",
        )
    }

    override fun onEnabledChanged(enabled: Boolean) {
        Log.i(
            Loader.TAG,
            "NoTelemetryPlugin toggled -> $enabled (bridge interception updated live; restart recommended for Firebase/AppsFlyer)",
        )
    }

    override fun onHostCall(frame: Frame): ByteArray? {
        val moduleName = BridgeRegistry.nameOf(frame.moduleId()) ?: return null
        val methodName = BridgeRegistry.methodName(frame.methodId())

        when (moduleName) {
            "Networking" -> {
                if ("request" != methodName && "upload" != methodName) return null
                val res = inspectAndRewriteNetworkingPayload(frame.payload())
                when (res.category) {
                    UrlCategory.SCIENCE -> {
                        val n = blockedScience.incrementAndGet()
                        if (n <= 5 || n % 25 == 0) {
                            Log.i(Loader.TAG, "NoTelemetryPlugin blocked /science (#$n)")
                        }
                        return if (res.mutatedPayload != null) {
                            frame.withPayload(res.mutatedPayload)
                        } else {
                            Frame.DROP
                        }
                    }

                    UrlCategory.ERRMON -> {
                        val n = blockedErrmon.incrementAndGet()
                        if (n <= 5 || n % 25 == 0) {
                            Log.i(Loader.TAG, "NoTelemetryPlugin blocked errmon/sentry (#$n)")
                        }
                        return if (res.mutatedPayload != null) {
                            frame.withPayload(res.mutatedPayload)
                        } else {
                            Frame.DROP
                        }
                    }

                    UrlCategory.NONE -> {
                        return null
                    }
                }
            }

            "CrashReporter", "CrashReports" -> {
                val n = blockedCrashReports.incrementAndGet()
                if (n <= 5 || n % 25 == 0) {
                    Log.i(Loader.TAG, "NoTelemetryPlugin dropped $moduleName.$methodName (#$n)")
                }
                return Frame.DROP
            }

            "TelemetryRing" -> {
                if ("setTouchLogging" == methodName || methodName == null) {
                    val n = blockedTracking.incrementAndGet()
                    if (n <= 5 || n % 25 == 0) {
                        Log.i(Loader.TAG, "NoTelemetryPlugin dropped TelemetryRing.$methodName (#$n)")
                    }
                    return Frame.DROP
                }
                return null
            }

            "Tracking" -> {
                if ("request" == methodName || methodName == null) {
                    val n = blockedTracking.incrementAndGet()
                    if (n <= 5 || n % 25 == 0) {
                        Log.i(Loader.TAG, "NoTelemetryPlugin dropped Tracking.$methodName (#$n)")
                    }
                    return Frame.DROP
                }
                return null
            }

            else -> {
                return null
            }
        }
    }

    override fun createSettingsPage(ctx: ManaContext): ManaPage =
        manaPage(
            title = "No Telemetry",
            titleAccessibilityId = "cinder.plugin.no-telemetry.title",
        ) {
            val enabledState = CinderSettingsPlugin.pluginEnabledState(this@NoTelemetryPlugin)
            val scienceText = stringState(blockedScience.get().toString())
            val errmonText = stringState(blockedErrmon.get().toString())
            val crashText = stringState(blockedCrashReports.get().toString())
            val trackingText = stringState(blockedTracking.get().toString())

            restartBanner(
                visibleState = CinderSettingsPlugin.restartPendingState(),
                messageState = CinderSettingsPlugin.restartMessageState(),
                buttonRefKey = REF_RESTART_BUTTON,
            )

            section(title = "Controls") {
                toggleRow(
                    title = "Block Telemetry",
                    subtitle = "Bridge blocking updates immediately; restart applies Firebase/AppsFlyer change",
                    icon = ManaIcons.SHIELD_LOCK,
                    state = enabledState,
                    accessibilityId = REF_ENABLE_TOGGLE,
                    refKey = REF_ENABLE_TOGGLE,
                ) { next ->
                    Loader.setPluginEnabled(this@NoTelemetryPlugin, next)
                }
            }

            section(title = "Blocked This Session") {
                valueRow("Science Requests", scienceText, icon = ManaIcons.EYE_SLASH)
                valueRow("Errmon / Sentry", errmonText, icon = ManaIcons.BUG)
                valueRow("Crash Reports", crashText, icon = ManaIcons.WARNING)
                valueRow("Tracking / Touch Logs", trackingText, icon = ManaIcons.SHIELD_LOCK)
                valueRow(
                    "Boot Analytics SDKs",
                    if (javaSdksKilledAtBoot) "Disabled at Boot" else "Not Disabled at Boot",
                    icon = ManaIcons.LOCK,
                )
                button(
                    label = "Reset Telemetry Counters",
                    style = ButtonStyle.SECONDARY,
                    accessibilityId = REF_RESET_BUTTON,
                    refKey = REF_RESET_BUTTON,
                ) {
                    resetCounters()
                    scienceText.value = "0"
                    errmonText.value = "0"
                    crashText.value = "0"
                    trackingText.value = "0"
                    context.showToast(
                        message = "Telemetry counters reset",
                        icon = ManaIcons.CIRCLE_CHECK,
                        tone = ManaToastTone.POSITIVE,
                    )
                }
            }
        }
}
