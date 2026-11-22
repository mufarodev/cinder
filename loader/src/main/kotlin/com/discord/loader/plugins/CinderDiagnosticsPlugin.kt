package com.discord.loader.plugins

import android.content.Context
import android.util.Log
import com.discord.loader.BridgeRegistry
import com.discord.loader.CinderSettings
import com.discord.loader.Frame
import com.discord.loader.Loader
import com.discord.loader.ModPlugin
import com.discord.loader.UiOps
import com.discord.loader.mana.ButtonStyle
import com.discord.loader.mana.ManaContext
import com.discord.loader.mana.ManaIcons
import com.discord.loader.mana.ManaPage
import com.discord.loader.mana.ManaShowcasePage
import com.discord.loader.mana.ManaToastTone
import com.discord.loader.mana.booleanState
import com.discord.loader.mana.manaPage
import com.discord.loader.mana.stringState
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Arrays
import java.util.Collections
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class CinderDiagnosticsPlugin : ModPlugin {
    companion object {
        const val PLUGIN_ID = "cinder-diagnostics"
        const val REF_VERBOSE_TOGGLE = "diagnostics.toggle.verbose"
        const val REF_DUMP_TOGGLE = "diagnostics.toggle.dump"
        const val REF_RESET_BUTTON = "diagnostics.btn.reset"
        const val REF_SHOWCASE_ROW = "diagnostics.row.showcase"

        private const val MAX_SEEN_CALLS = 512
        private const val MAX_SETTINGS_DUMPS = 4
        private const val MAX_FILE_DUMPS = 150
        private const val MIN_FILE_DUMP_BYTES = 16
        private const val MAX_DUMP_LINES = 80
        private const val MAX_LINE_CHARS = 320

        private val SETTINGS_MARKERS =
            arrayOf(
                "settings-search",
                "Staff Settings",
                "settings-show-dev-tools",
                "Mana Components",
                "devtools-playgrounds",
            )
        private val SETTINGS_MARKER_BYTES: Array<ByteArray> =
            Array(SETTINGS_MARKERS.size) { i ->
                SETTINGS_MARKERS[i].toByteArray(StandardCharsets.UTF_8)
            }

        private fun buildParentMap(opList: List<UiOps.Op>): Map<Int, Int> {
            val parentOf = HashMap<Int, Int>()
            for (i in 0 until opList.size) {
                val op = opList[i]
                if (op.opcode() == UiOps.OP_CHILDREN) {
                    val kids = op.childIds()
                    if (kids != null) {
                        val pid = op.viewId()
                        for (k in kids) parentOf[k] = pid
                    }
                } else if (op.opcode() == UiOps.OP_CHILD_EDITS) {
                    val edits = op.childEdits()
                    if (edits != null) {
                        val pid = op.viewId()
                        for (ce in edits) {
                            val ins = ce.insertIds
                            if (ins != null) {
                                for (k in ins) parentOf[k] = pid
                            }
                        }
                    }
                }
            }
            return parentOf
        }

        private fun formatOpLine(
            sb: StringBuilder,
            idx: Int,
            op: UiOps.Op,
            parentOf: Map<Int, Int>,
            clamped: Boolean,
        ) {
            when (op.opcode()) {
                UiOps.OP_CHILDREN -> {
                    val kids = op.childIds()
                    sb
                        .append("  [")
                        .append(idx)
                        .append("] children parent=")
                        .append(op.viewId())
                        .append(" count=")
                        .append(kids.size)
                        .append(" [")
                    val lim = if (clamped) minOf(kids.size, 24) else kids.size
                    for (k in 0 until lim) {
                        if (k > 0) sb.append(',')
                        sb.append(kids[k])
                    }
                    if (kids.size > lim) sb.append(",...")
                    sb.append(']')
                }

                UiOps.OP_CHILD_EDITS -> {
                    val edits = op.childEdits()
                    sb
                        .append("  [")
                        .append(idx)
                        .append("] childEdits parent=")
                        .append(op.viewId())
                        .append(" edits=")
                        .append(edits.size)
                    val lim = if (clamped) minOf(edits.size, 6) else edits.size
                    for (e in 0 until lim) {
                        val ce = edits[e]
                        sb
                            .append(" {k=")
                            .append(ce.keepCount)
                            .append(",d=")
                            .append(ce.deleteCount)
                            .append(",ins=")
                            .append(Arrays.toString(ce.insertIds))
                            .append('}')
                    }
                }

                UiOps.OP_CREATE, UiOps.OP_UPDATE -> {
                    val parent = parentOf[op.viewId()]
                    sb
                        .append("  [")
                        .append(idx)
                        .append("] ")
                        .append(op.opName())
                        .append(" id=")
                        .append(op.viewId())
                    if (parent != null) sb.append(" parent=").append(parent)
                    if (op.viewType() != null) sb.append(" type=").append(op.viewType())
                    val props = op.props()
                    if (props != null && props.isNotEmpty()) {
                        sb.append(" {")
                        for (p in 0 until props.size) {
                            if (p > 0) sb.append(", ")
                            sb.append(props[p].toString())
                            if (clamped && sb.length > MAX_LINE_CHARS) {
                                sb.append(", ...")
                                break
                            }
                        }
                        sb.append('}')
                    }
                }

                UiOps.OP_DELETE -> {
                    val ids = op.deleteIds()
                    sb
                        .append("  [")
                        .append(idx)
                        .append("] delete count=")
                        .append(ids?.size ?: 0)
                        .append(' ')
                        .append(Arrays.toString(ids))
                }

                UiOps.OP_ROOT -> {
                    sb
                        .append("  [")
                        .append(idx)
                        .append("] root id=")
                        .append(op.viewId())
                }

                UiOps.OP_FOCUS -> {
                    sb
                        .append("  [")
                        .append(idx)
                        .append("] focus has=")
                        .append(op.hasFocusTarget())
                        .append(" id=")
                        .append(op.viewId())
                }

                UiOps.OP_SCROLL_TO -> {
                    sb
                        .append("  [")
                        .append(idx)
                        .append("] scrollTo id=")
                        .append(op.viewId())
                        .append(" opts=")
                        .append(op.scrollOptions())
                }

                UiOps.OP_MOTION -> {
                    val words = op.motionWords()
                    sb
                        .append("  [")
                        .append(idx)
                        .append("] motion words=")
                        .append(words?.size ?: 0)
                    if (!clamped && words != null) {
                        sb.append(' ').append(Arrays.toString(words))
                    }
                }

                else -> {
                    sb
                        .append("  [")
                        .append(idx)
                        .append("] ")
                        .append(op.opName())
                }
            }
        }

        private fun matchMarker(
            buf: ByteArray,
            length: Int,
        ): String? {
            val lim = minOf(length, buf.size)
            for (m in SETTINGS_MARKER_BYTES.indices) {
                if (indexOfBytes(buf, lim, SETTINGS_MARKER_BYTES[m]) >= 0) {
                    return SETTINGS_MARKERS[m]
                }
            }
            return null
        }

        private fun indexOfBytes(
            haystack: ByteArray,
            haystackLen: Int,
            needle: ByteArray,
        ): Int {
            val nLen = needle.size
            if (nLen == 0 || haystackLen < nLen) return -1
            val first = needle[0]
            val max = haystackLen - nLen
            for (i in 0..max) {
                if (haystack[i] != first) continue
                var j = 1
                while (j < nLen && haystack[i + j] == needle[j]) j++
                if (j == nLen) return i
            }
            return -1
        }

        private fun dumpSettingsOps(
            dumpNum: Int,
            marker: String,
            opsBuf: ByteArray,
            length: Int,
        ) {
            val batch = UiOps.parse(opsBuf, length)
            if (batch == null) {
                Log.w(Loader.TAG, "SETTINGS_DUMP #$dumpNum trigger=$marker failed to parse (${length}B)")
                return
            }
            val opList = batch.ops()
            val parentOf = buildParentMap(opList)
            Log.i(
                Loader.TAG,
                "SETTINGS_DUMP #$dumpNum trigger=\"$marker\" ops=${opList.size} bytes=$length propTable=${UiOps.propCount()}",
            )
            var logged = 0
            for (i in 0 until opList.size) {
                if (logged >= MAX_DUMP_LINES) break
                val op = opList[i]
                val code = op.opcode()
                if (code == UiOps.OP_CHILDREN || code == UiOps.OP_CHILD_EDITS ||
                    code == UiOps.OP_CREATE || code == UiOps.OP_UPDATE
                ) {
                    val sb = StringBuilder()
                    formatOpLine(sb, i, op, parentOf, true)
                    Log.i(Loader.TAG, clamp(sb.toString()))
                    logged++
                }
            }
            if (logged >= MAX_DUMP_LINES && opList.size > logged) {
                Log.i(Loader.TAG, "SETTINGS_DUMP #$dumpNum truncated ($logged/${opList.size} ops shown)")
            }
        }

        private fun clamp(s: String): String = if (s.length <= MAX_LINE_CHARS) s else s.substring(0, MAX_LINE_CHARS - 3) + "..."

        private fun cleanupStaleDevicePrefs(context: Context) {
            try {
                val dp = File(context.filesDir, "device_prefs.json")
                if (!dp.exists()) return
                val raw = String(Files.readAllBytes(dp.toPath()), StandardCharsets.UTF_8)
                val o = JSONObject(raw)
                if (o.has("libdiscord.routing_key") || o.has("libdiscord.errmon.disabled_until")) {
                    o.remove("libdiscord.routing_key")
                    o.remove("libdiscord.errmon.disabled_until")
                    FileOutputStream(dp).use { it.write(o.toString().toByteArray(StandardCharsets.UTF_8)) }
                    Log.i(Loader.TAG, "removed stale experiment keys from device_prefs.json")
                }
            } catch (t: Throwable) {
                Log.w(Loader.TAG, "device_prefs cleanup failed", t)
            }
        }

        private fun hex(b: ByteArray?): String {
            if (b == null) return ""
            val lim = minOf(b.size, 48)
            val sb = StringBuilder(lim * 2 + 8)
            for (i in 0 until lim) {
                sb.append(String.format(Locale.US, "%02x", b[i].toInt() and 0xff))
            }
            if (b.size > lim) sb.append("...").append(b.size).append('B')
            return sb.toString()
        }
    }

    private val calls = AtomicInteger()
    private val ops = AtomicInteger()
    private val dispatches = AtomicInteger()
    private val settingsDumps = AtomicInteger()
    private val fileDumps = AtomicInteger()

    @Volatile
    private var lastDumpToggle = false

    @Volatile
    private var dumpsDir: File? = null
    private val seen: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    override fun id(): String = PLUGIN_ID

    override fun version(): String = "1.0.0"

    override fun description(): String = "Bridge diagnostics, ViewWire op-dumping & Mana Showcase"

    override fun icon(): String = ManaIcons.WRENCH

    override fun isRequired(): Boolean = true

    fun hostCallCount(): Int = calls.get()

    fun uiOpBatchCount(): Int = ops.get()

    fun dispatchCount(): Int = dispatches.get()

    fun fileDumpCount(): Int = fileDumps.get()

    fun resetCounters() {
        calls.set(0)
        ops.set(0)
        dispatches.set(0)
        settingsDumps.set(0)
        fileDumps.set(0)
        seen.clear()
    }

    override fun createSettingsPage(ctx: ManaContext): ManaPage =
        manaPage(
            title = "Cinder Diagnostics",
            subtitle = "Core Plugin",
            titleAccessibilityId = "cinder.plugin.cinder-diagnostics.title",
        ) {
            val verbose = booleanState(CinderSettings.isVerboseBridgeLogsEnabled())
            val dumpOps = booleanState(CinderSettings.isDumpSettingsOpsEnabled())
            val callsText = stringState(calls.get().toString())
            val opsText = stringState(ops.get().toString())
            val dispatchesText = stringState(dispatches.get().toString())
            val dumpsText = stringState(fileDumps.get().toString())

            section(title = "Diagnostics") {
                toggleRow(
                    title = "Verbose Bridge Logs",
                    subtitle = "Log first-seen host bridge calls & periodic op batch counts",
                    icon = ManaIcons.BUG,
                    state = verbose,
                    accessibilityId = REF_VERBOSE_TOGGLE,
                    refKey = REF_VERBOSE_TOGGLE,
                ) { next ->
                    CinderSettings.setToggleValue(CinderSettings.KEY_VERBOSE_BRIDGE_LOGS, next)
                }
                toggleRow(
                    title = "Dump Settings Ops",
                    subtitle = "Write decoded Settings UI-op batches to files/dumps/",
                    icon = ManaIcons.FOLDER,
                    state = dumpOps,
                    accessibilityId = REF_DUMP_TOGGLE,
                    refKey = REF_DUMP_TOGGLE,
                ) { next ->
                    CinderSettings.setToggleValue(CinderSettings.KEY_DUMP_SETTINGS_OPS, next)
                }
            }

            section(title = "Live Counters") {
                valueRow("Host Bridge Calls", callsText, icon = ManaIcons.LINK)
                valueRow("UI-Op Batches", opsText, icon = ManaIcons.SPEEDOMETER)
                valueRow("Dispatch Events", dispatchesText, icon = ManaIcons.APPS)
                valueRow("Files Dumped", dumpsText, icon = ManaIcons.FOLDER)
                button(
                    label = "Reset Diagnostic Counters",
                    style = ButtonStyle.SECONDARY,
                    accessibilityId = REF_RESET_BUTTON,
                    refKey = REF_RESET_BUTTON,
                ) {
                    resetCounters()
                    callsText.value = "0"
                    opsText.value = "0"
                    dispatchesText.value = "0"
                    dumpsText.value = "0"
                    context.showToast(
                        message = "Diagnostic counters reset",
                        icon = ManaIcons.CIRCLE_CHECK,
                        tone = ManaToastTone.POSITIVE,
                    )
                }
            }

            section(title = "Developer Tools") {
                navRow(
                    title = "Mana Showcase",
                    subtitle = null,
                    icon = ManaIcons.SPARKLES,
                    accessibilityId = REF_SHOWCASE_ROW,
                    refKey = REF_SHOWCASE_ROW,
                ) {
                    context.push(ManaShowcasePage.create())
                }
            }
        }

    override fun onLoad(context: Context?) {
        Log.i(
            Loader.TAG,
            "CinderDiagnosticsPlugin v${version()} loaded (registry=${BridgeRegistry.size()}" +
                ", propNames=${UiOps.propCount()}" +
                ", pressPropId=${UiOps.propId("press")}" +
                ", Updates=${BridgeRegistry.moduleId("Updates")}" +
                ", Updates.developerMode=${BridgeRegistry.methodId("Updates", "developerMode")}" +
                ", Database=${BridgeRegistry.moduleId("Database")}" +
                ", Storage=${BridgeRegistry.moduleId("Storage")})",
        )

        if (context == null) return

        try {
            val ext = context.getExternalFilesDir("dumps")
            if (ext != null) {
                if (!ext.exists()) ext.mkdirs()
                dumpsDir = ext
            }
        } catch (_: Throwable) {
        }

        cleanupStaleDevicePrefs(context)
    }

    override fun onHostCall(frame: Frame): ByteArray? {
        val count = calls.incrementAndGet()
        if (!CinderSettings.isVerboseBridgeLogsEnabled()) {
            return null
        }
        if (seen.size < MAX_SEEN_CALLS) {
            val key = BridgeRegistry.describe(frame)
            if (seen.add(key)) {
                Log.i(
                    Loader.TAG,
                    "host call first-seen $key (m${frame.moduleId()}.${frame.methodId()}, len=${frame.payloadLength()})",
                )
            }
        }
        if (BridgeRegistry.matches(frame, "Updates", "developerMode")) {
            Log.i(
                Loader.TAG,
                "Updates.developerMode request callId=${frame.callId()} len=${frame.payloadLength()}",
            )
        }
        if (count % 1000 == 0) {
            Log.i(Loader.TAG, "host calls so far: $count")
        }
        return null
    }

    override fun onHostResponse(
        request: Frame,
        response: Frame,
    ): ByteArray? {
        if (!CinderSettings.isVerboseBridgeLogsEnabled()) {
            return null
        }
        if (BridgeRegistry.matches(request, "Updates", "developerMode")) {
            Log.i(
                Loader.TAG,
                "Updates.developerMode response callId=${response.callId()} len=${response.payloadLength()} hex=${hex(response.payload())}",
            )
        }
        return null
    }

    override fun onDispatch(frame: Frame): ByteArray? {
        dispatches.incrementAndGet()
        return null
    }

    override fun onUiOps(
        opsBuf: ByteArray?,
        length: Int,
    ): ByteArray? {
        val count = ops.incrementAndGet()
        if (CinderSettings.isVerboseBridgeLogsEnabled() && (count <= 5 || count % 200 == 0)) {
            Log.i(Loader.TAG, "ui ops batch #$count bytes=$length")
        }
        val dumpEnabled = CinderSettings.isDumpSettingsOpsEnabled()
        if (!dumpEnabled) {
            lastDumpToggle = false
            return null
        }
        if (!lastDumpToggle) {
            lastDumpToggle = true
            settingsDumps.set(0)
            fileDumps.set(0)
            clearOldDumps()
        }
        if (opsBuf != null && length > 0) {
            val marker = matchMarker(opsBuf, length)
            if (marker != null && settingsDumps.get() < MAX_SETTINGS_DUMPS) {
                val dumpNum = settingsDumps.incrementAndGet()
                if (dumpNum <= MAX_SETTINGS_DUMPS) {
                    dumpSettingsOps(dumpNum, marker, opsBuf, length)
                }
            }
            if ((marker != null || length >= MIN_FILE_DUMP_BYTES) &&
                dumpsDir != null &&
                fileDumps.get() < MAX_FILE_DUMPS
            ) {
                val fnum = fileDumps.incrementAndGet()
                if (fnum <= MAX_FILE_DUMPS) {
                    writeFullBatchDump(fnum, marker, opsBuf, length)
                }
            }
        }
        return null
    }

    override fun onLog(
        level: Int,
        message: String?,
    ) {
        Log.i(Loader.TAG, "[g$level] $message")
    }

    private fun clearOldDumps() {
        val dir = dumpsDir
        if (dir == null || !dir.isDirectory) return
        val existing = dir.listFiles() ?: return
        for (f in existing) {
            if (f.isFile && f.name.startsWith("batch_")) {
                f.delete()
            }
        }
    }

    private fun writeFullBatchDump(
        num: Int,
        marker: String?,
        opsBuf: ByteArray,
        length: Int,
    ) {
        val dir = dumpsDir ?: return
        val batch = UiOps.parse(opsBuf, length) ?: return
        val opList = batch.ops()
        if (opList.isEmpty()) return
        val parentOf = buildParentMap(opList)

        val out = StringBuilder(maxOf(1024, opList.size * 96))
        out
            .append("# batch=")
            .append(num)
            .append(" marker=")
            .append(marker ?: "none")
            .append(" ops=")
            .append(opList.size)
            .append(" bytes=")
            .append(length)
            .append(" propTable=")
            .append(UiOps.propCount())
            .append('\n')

        for (i in 0 until opList.size) {
            formatOpLine(out, i, opList[i], parentOf, false)
            out.append('\n')
        }

        val name = String.format(Locale.US, "batch_%03d.txt", num)
        val file = File(dir, name)
        try {
            FileOutputStream(file).use { it.write(out.toString().toByteArray(StandardCharsets.UTF_8)) }
            Log.i(
                Loader.TAG,
                "DUMP_FILE $name ops=${opList.size} bytes=$length" +
                    if (marker != null) " marker=\"$marker\"" else "",
            )
        } catch (t: Throwable) {
            Log.w(Loader.TAG, "writeFullBatchDump failed: $t")
        }
    }
}
