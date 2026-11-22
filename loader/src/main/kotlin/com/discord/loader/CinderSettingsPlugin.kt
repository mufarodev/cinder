package com.discord.loader

import android.util.Log
import com.discord.loader.mana.BadgeTone
import com.discord.loader.mana.ManaContext
import com.discord.loader.mana.ManaIcons
import com.discord.loader.mana.ManaIds
import com.discord.loader.mana.ManaMotion
import com.discord.loader.mana.ManaPage
import com.discord.loader.mana.ManaRouter
import com.discord.loader.mana.ManaScope
import com.discord.loader.mana.ManaSettingsSearch
import com.discord.loader.mana.ManaShowcasePage
import com.discord.loader.mana.ManaState
import com.discord.loader.mana.MountedManaPage
import com.discord.loader.mana.ThemeEngine
import com.discord.loader.mana.booleanState
import com.discord.loader.mana.manaPage
import com.discord.loader.mana.stringState
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.HashMap
import java.util.HashSet

internal class CinderSettingsPlugin {
    companion object {
        const val REF_SHOWCASE_ROW = "cinder.row.showcase"
        const val REF_RESTART_BUTTON = "cinder.btn.restart"

        private const val SETTINGS_PHONE_ID = "settings-phone"
        private val SETTINGS_PHONE_BYTES = SETTINGS_PHONE_ID.toByteArray(StandardCharsets.UTF_8)

        private val STATE_LOCK = Any()
        private val PLUGIN_ENABLED_STATES = HashMap<String, ManaState<Boolean>>()
        private val RESTART_PENDING_STATE = booleanState(false)
        private val RESTART_MESSAGE_STATE = stringState("")

        @JvmStatic
        fun pluginEnabledState(plugin: ModPlugin): ManaState<Boolean> {
            val id = plugin.id()
            val current = Loader.isPluginEnabled(plugin)
            val state =
                synchronized(STATE_LOCK) {
                    var existing = PLUGIN_ENABLED_STATES[id]
                    if (existing == null) {
                        existing = booleanState(current)
                        PLUGIN_ENABLED_STATES[id] = existing
                    }
                    existing
                }
            if (state.value != current) {
                state.value = current
            }
            return state
        }

        @JvmStatic
        fun restartPendingState(): ManaState<Boolean> {
            refreshRestartBannerState()
            return RESTART_PENDING_STATE
        }

        @JvmStatic
        fun restartMessageState(): ManaState<String> {
            refreshRestartBannerState()
            return RESTART_MESSAGE_STATE
        }

        @JvmStatic
        fun onPluginStateChanged(
            pluginId: String?,
            enabled: Boolean,
        ) {
            if (pluginId != null) {
                val state = synchronized(STATE_LOCK) { PLUGIN_ENABLED_STATES[pluginId] }
                if (state != null && state.value != enabled) {
                    state.value = enabled
                }
            }
            refreshRestartBannerState()
        }

        @JvmStatic
        fun refreshRestartBannerState() {
            val pending = Loader.pendingRestartPlugins()
            if (pending.isEmpty()) {
                RESTART_MESSAGE_STATE.value = ""
                RESTART_PENDING_STATE.value = false
            } else {
                val msg =
                    if (pending.size == 1) {
                        "${pending[0].name()} was changed. Restart Discord to fully apply startup changes."
                    } else {
                        val names = StringBuilder()
                        for (i in pending.indices) {
                            if (i > 0) names.append(", ")
                            names.append(pending[i].name())
                        }
                        "$names changed. Restart Discord to fully apply startup changes."
                    }
                RESTART_MESSAGE_STATE.value = msg
                RESTART_PENDING_STATE.value = true
            }
        }

        @JvmStatic
        fun resetReactiveStateForTests() {
            synchronized(STATE_LOCK) {
                PLUGIN_ENABLED_STATES.clear()
            }
            RESTART_MESSAGE_STATE.value = ""
            RESTART_PENDING_STATE.value = false
        }

        private fun findSettingsPhoneId(opList: List<UiOps.Op>): Int {
            for (i in 0 until opList.size) {
                val op = opList[i]
                if (op.opcode() == UiOps.OP_CREATE && SETTINGS_PHONE_ID == op.propString("accessibilityId")) {
                    return op.viewId()
                }
            }
            return -1
        }

        private fun isSettingsOverviewBatch(opList: List<UiOps.Op>): Boolean {
            for (i in 0 until opList.size) {
                val op = opList[i]
                if (op.opcode() != UiOps.OP_CREATE) continue
                val acc = op.propString("accessibilityId")
                if ("user_settings_search_input" == acc ||
                    "settings-overview-title" == acc ||
                    "settings.row.account" == acc
                ) {
                    return true
                }
            }
            return false
        }

        private fun containsCreateFor(
            opList: List<UiOps.Op>,
            viewId: Int,
        ): Boolean {
            for (i in 0 until opList.size) {
                val op = opList[i]
                if (op.opcode() == UiOps.OP_CREATE && op.viewId() == viewId) {
                    return true
                }
            }
            return false
        }

        private fun findSettingsScrollColumn(
            settingsPhoneId: Int,
            createById: Map<Int, UiOps.Op>,
            childrenByParent: Map<Int, IntArray>,
            parentOf: Map<Int, Int>,
        ): Int {
            val queue = ArrayList<Int>()
            val visited = HashSet<Int>()
            queue.add(settingsPhoneId)
            visited.add(settingsPhoneId)
            var qi = 0
            while (qi < queue.size) {
                val cur = queue[qi++]
                val op = createById[cur]
                if (op != null && "scroll" == op.viewType()) {
                    val scrollKids = childrenByParent[cur]
                    if (scrollKids != null && scrollKids.isNotEmpty()) {
                        for (kid in scrollKids) {
                            val kidOp = createById[kid]
                            if (kidOp != null && "column" == kidOp.propString("direction")) {
                                return kid
                            }
                        }
                        return scrollKids[0]
                    }
                }
                val kids = childrenByParent[cur]
                if (kids != null) {
                    for (kid in kids) {
                        if (visited.add(kid)) {
                            queue.add(kid)
                        }
                    }
                }
            }

            var anchorId = -1
            for (op in createById.values) {
                if ("settings.row.account" == op.propString("accessibilityId") ||
                    "Account Settings" == op.propString("text")
                ) {
                    anchorId = op.viewId()
                    break
                }
            }
            var cur = anchorId
            var guard = 0
            while (cur != -1 && guard++ < 16) {
                val p = parentOf[cur] ?: break
                val parentOp = createById[p]
                if (parentOp != null && "scroll" == parentOp.viewType()) {
                    return cur
                }
                val curOp = createById[cur]
                if (curOp != null && "column" == curOp.propString("direction") && curOp.findProp("gap") != null) {
                    return cur
                }
                cur = p
            }
            return -1
        }

        private fun indexOfBytes(
            haystack: ByteArray,
            haystackLen: Int,
            needle: ByteArray,
        ): Int {
            val nLen = needle.size
            if (nLen == 0 || haystackLen < nLen) return -1
            val first = needle[0]
            val max = minOf(haystackLen, haystack.size) - nLen
            for (i in 0..max) {
                if (haystack[i] != first) continue
                var j = 1
                while (j < nLen && haystack[i + j] == needle[j]) {
                    j++
                }
                if (j == nLen) return i
            }
            return -1
        }
    }

    val router: ManaRouter = ManaRouter()

    val settingsSearch: ManaSettingsSearch =
        ManaSettingsSearch(
            router = router,
            openCinderRoot = { openCinderPage() },
            openPluginPage = { plugin, scope -> openPluginSettings(plugin, scope) },
        )

    @Volatile
    var overviewRowViewId: Int = -1
        private set

    @Volatile
    var currentTheme: ManaUi.Theme = ThemeEngine.currentTheme()
        private set

    fun onUiOps(
        opsBuf: ByteArray?,
        length: Int,
    ): ByteArray? {
        if (opsBuf == null || length <= 0) return null
        val batch = UiOps.parse(opsBuf, length)
        if (batch == null || batch.isEmpty()) return null
        val opList = batch.ops()

        val hasSettingsPhone = indexOfBytes(opsBuf, length, SETTINGS_PHONE_BYTES) >= 0
        val isOverview = hasSettingsPhone && isSettingsOverviewBatch(opList)

        if (isOverview) {
            ThemeEngine.unbindScope(CinderSettings.THEME_SCOPE_OVERVIEW)
        }

        ManaMotion.observeRustBatch(opList)

        ThemeEngine.observeBatch(opList)
        currentTheme = ThemeEngine.currentTheme()

        router.observeDiscordBatch(opList)

        if (!isOverview) {
            return settingsSearch.onDiscordBatch(opList)
        }

        val settingsPhoneId = findSettingsPhoneId(opList)
        if (settingsPhoneId < 0) {
            return null
        }

        val createById = HashMap<Int, UiOps.Op>()
        val childrenByParent = HashMap<Int, IntArray>()
        val parentOf = HashMap<Int, Int>()
        for (i in 0 until opList.size) {
            val op = opList[i]
            if (op.opcode() == UiOps.OP_CREATE) {
                createById[op.viewId()] = op
            } else if (op.opcode() == UiOps.OP_CHILDREN) {
                val kids = op.childIds()
                if (kids != null) {
                    val pid = op.viewId()
                    childrenByParent[pid] = kids
                    for (kid in kids) {
                        parentOf[kid] = pid
                    }
                }
            }
        }

        discoverAndAttachNavigator(settingsPhoneId, opList, parentOf, childrenByParent)

        val scrollColId = findSettingsScrollColumn(settingsPhoneId, createById, childrenByParent, parentOf)
        if (scrollColId < 0) {
            Log.w(Loader.TAG, "CinderSettings: could not find scroll column in settings-phone overview batch")
            return null
        }

        var scrollColChildrenOpIndex = -1
        var origScrollKids: IntArray? = null
        for (i in opList.size - 1 downTo 0) {
            val op = opList[i]
            if (op.opcode() == UiOps.OP_CHILDREN && op.viewId() == scrollColId) {
                scrollColChildrenOpIndex = i
                origScrollKids = op.childIds()
                break
            }
        }
        if (scrollColChildrenOpIndex < 0 || origScrollKids == null) {
            Log.w(Loader.TAG, "CinderSettings: scroll column $scrollColId has no OP_CHILDREN in batch")
            return null
        }

        val injectedOps = UiOps()
        val theme = currentTheme
        val (cinderRowId, categoryId) =
            ThemeEngine.withScope(CinderSettings.THEME_SCOPE_OVERVIEW) {
                val rowId =
                    ManaUi.navRow(
                        injectedOps,
                        ManaIds.allocator,
                        theme,
                        ManaIcons.FIRE,
                        "Cinder",
                        null,
                        null,
                        "settings.row.cinder",
                        ManaUi.RowPosition.SINGLE,
                    )
                val catId =
                    ManaUi.section(
                        injectedOps,
                        ManaIds.allocator,
                        theme,
                        "Cinder",
                        intArrayOf(rowId),
                        null,
                    )
                Pair(rowId, catId)
            }

        overviewRowViewId = cinderRowId

        val newScrollKids = Arrays.copyOf(origScrollKids, origScrollKids.size + 1)
        newScrollKids[origScrollKids.size] = categoryId

        val searchInputId = ManaSettingsSearch.findSearchInputId(opList)
        settingsSearch.attachOverview(settingsPhoneId, searchInputId, scrollColId, newScrollKids)

        val rewritten = UiOps()
        for (i in 0 until opList.size) {
            if (i == scrollColChildrenOpIndex) {
                rewritten.addAll(injectedOps.ops())
                rewritten.setChildren(scrollColId, *newScrollKids)
            } else {
                rewritten.add(opList[i])
            }
        }

        Log.i(
            Loader.TAG,
            "CinderSettings: injected Cinder category into Settings Overview" +
                " (scrollCol=$scrollColId, searchInput=$searchInputId, rowId=$cinderRowId" +
                ", navId=${router.currentNavigatorId}, preset=${ThemeEngine.currentPreset()}" +
                ", screenBg=0x${java.lang.Long.toHexString(theme.screenBg)})",
        )
        return rewritten.toBytes()
    }

    fun onViewEvent(event: UiOps.ViewEvent?): ByteArray? {
        if (event == null || !event.isViewEvent()) return null

        val viewId = event.viewId()
        val rowId = overviewRowViewId
        if (rowId >= 0 && viewId == rowId) {
            if ("press" == event.eventName() && router.isEmpty) {
                openCinderPage()
            }
            return Frame.DROP
        }

        val routed = router.onViewEvent(event)
        if (routed != null) {
            return routed
        }

        val searchResult = settingsSearch.onViewEvent(event)
        if (searchResult != null) {
            return searchResult
        }

        if (viewId >= ManaIds.SYNTHETIC_START) {
            return Frame.DROP
        }
        return null
    }

    fun onBackPress(): Boolean = router.onBackPress()

    fun openCinderPage() {
        if (!router.isEmpty) return
        currentTheme = ThemeEngine.currentTheme()
        refreshRestartBannerState()
        val mounted = router.push(buildCinderRootPage())
        Log.i(
            Loader.TAG,
            "CinderSettings: opened Cinder page via ManaRouter" +
                " (mode=${if (mounted.mountedInNavigator) "navigator:${mounted.hostNavigatorId}" else "modal"}" +
                ", rootId=${mounted.rootId}, views=${mounted.scope.viewCount()}" +
                ", themeBindings=${ThemeEngine.bindingCount(CinderSettings.THEME_SCOPE_PAGE)})",
        )
    }

    fun openPluginSettings(
        plugin: ModPlugin,
        callerScope: ManaScope,
    ): MountedManaPage {
        currentTheme = ThemeEngine.currentTheme()
        refreshRestartBannerState()
        val ctx = ManaContext(router, callerScope)
        val customPage: ManaPage? =
            try {
                plugin.createSettingsPage(ctx)
            } catch (t: Throwable) {
                Log.e(Loader.TAG, "CinderSettings: createSettingsPage threw for plugin ${plugin.id()}", t)
                null
            }
        return router.push(customPage ?: buildDefaultPluginDetailPage(plugin))
    }

    private fun buildDefaultPluginDetailPage(plugin: ModPlugin): ManaPage =
        manaPage(
            title = plugin.name(),
            titleAccessibilityId = "cinder.plugin.${plugin.id()}.title",
        ) {
            val icon = plugin.icon()?.takeIf { it.isNotEmpty() } ?: ManaIcons.PUZZLE_PIECE

            if (plugin.requiresRestart()) {
                restartBanner(
                    visibleState = restartPendingState(),
                    messageState = restartMessageState(),
                    buttonRefKey = REF_RESTART_BUTTON,
                )
            }

            if (!plugin.isRequired()) {
                section(title = "Status") {
                    toggleRow(
                        title = "Enable ${plugin.name()}",
                        subtitle =
                            if (plugin.requiresRestart()) {
                                "Restart Discord after changing this plugin"
                            } else {
                                "Takes effect immediately"
                            },
                        icon = icon,
                        state = pluginEnabledState(plugin),
                        accessibilityId = "cinder.plugin.${plugin.id()}.enabled",
                    ) { next ->
                        Loader.setPluginEnabled(plugin, next)
                    }
                }
            }

            section(title = "Plugin Details") {
                valueRow("Plugin ID", plugin.id(), icon = icon)
                valueRow("Version", "v${plugin.version()}", icon = ManaIcons.WRENCH)
                if (plugin.isRequired()) {
                    valueRow("Lifecycle", "Core (Always On)", icon = ManaIcons.LOCK)
                }
            }
        }

    private fun buildCinderRootPage(): ManaPage =
        manaPage(
            title = "Cinder",
            scopeId = CinderSettings.THEME_SCOPE_PAGE,
            titleAccessibilityId = "settings-cinder-title",
        ) {
            val allPlugins = Loader.getPlugins()
            val togglablePlugins = ArrayList<ModPlugin>(allPlugins.size)
            val corePlugins = ArrayList<ModPlugin>(allPlugins.size)
            for (p in allPlugins) {
                if (p is CinderSettings || "cinder-settings" == p.id()) continue
                if (p.isRequired()) {
                    corePlugins.add(p)
                } else {
                    togglablePlugins.add(p)
                }
            }
            val toggles = CinderSettings.snapshotToggles()
            val actions = CinderSettings.snapshotActions()

            restartBanner(
                visibleState = restartPendingState(),
                messageState = restartMessageState(),
                buttonRefKey = REF_RESTART_BUTTON,
            )

            section(title = "Runtime") {
                valueRow("Loader", "Cinder Mobile", icon = ManaIcons.FIRE)
                valueRow(
                    "Bridge Registry",
                    "${BridgeRegistry.size()} symbols \u00b7 ${UiOps.propCount()} props",
                    icon = ManaIcons.WRENCH,
                )
                valueRow("Loaded Plugins", allPlugins.size.toString(), icon = ManaIcons.PUZZLE_PIECE)
                valueRow("Theme", ThemeEngine.currentPreset().name, icon = ManaIcons.PAINT_PALETTE)
            }

            if (togglablePlugins.isNotEmpty()) {
                section(title = "Plugins") {
                    for (p in togglablePlugins) {
                        val icon = p.icon()?.takeIf { it.isNotEmpty() } ?: ManaIcons.PUZZLE_PIECE
                        val enabledState = pluginEnabledState(p)
                        navToggleRow(
                            title = p.name(),
                            subtitle = null,
                            icon = icon,
                            state = enabledState,
                            accessibilityId = "cinder.plugin.${p.id()}",
                            refKey = "plugin:${p.id()}",
                            onOpen = { openPluginSettings(p, scope) },
                            onToggle = { next -> Loader.setPluginEnabled(p, next) },
                        )
                    }
                }
            }

            if (corePlugins.isNotEmpty()) {
                section(title = "Core Plugins") {
                    for (p in corePlugins) {
                        val icon = p.icon()?.takeIf { it.isNotEmpty() } ?: ManaIcons.PUZZLE_PIECE
                        navRow(
                            title = p.name(),
                            subtitle = null,
                            icon = icon,
                            badge = "Core",
                            badgeTone = BadgeTone.SUBTLE,
                            trailing = "v${p.version()}",
                            accessibilityId = "cinder.plugin.${p.id()}",
                            refKey = "plugin:${p.id()}",
                        ) {
                            openPluginSettings(p, scope)
                        }
                    }
                }
            }

            if (togglablePlugins.isEmpty() && corePlugins.isEmpty()) {
                section(title = "Plugins") {
                    valueRow("No plugins loaded", "", icon = ManaIcons.PUZZLE_PIECE)
                }
            }

            if (toggles.isNotEmpty()) {
                section(title = "Options") {
                    for (entry in toggles) {
                        val state = booleanState(CinderSettings.getToggleValue(entry.key, entry.defaultValue))
                        toggleRow(
                            title = entry.title,
                            subtitle = entry.subtitle,
                            state = state,
                            accessibilityId = "cinder.toggle.${entry.key}",
                            refKey = "toggle:${entry.key}",
                        ) { next ->
                            CinderSettings.setToggleValue(entry.key, next)
                            Log.i(Loader.TAG, "CinderSettings: toggled ${entry.key} -> $next")
                        }
                    }
                }
            }

            section(title = "Developer Tools") {
                navRow(
                    title = "Mana Showcase",
                    subtitle = null,
                    icon = ManaIcons.SPARKLES,
                    accessibilityId = "cinder.row.showcase",
                    refKey = REF_SHOWCASE_ROW,
                ) {
                    context.push(ManaShowcasePage.create())
                }
            }

            if (actions.isNotEmpty()) {
                section(title = "Actions") {
                    for (entry in actions) {
                        actionRow(
                            title = entry.title,
                            subtitle = entry.subtitle,
                            icon = entry.iconUri,
                            accessibilityId = "cinder.action.${entry.id}",
                            refKey = "action:${entry.id}",
                        ) {
                            val cb = entry.onPress
                            if (cb != null) {
                                try {
                                    cb.run()
                                } catch (t: Throwable) {
                                    Log.e(Loader.TAG, "CinderSettings: action ${entry.id} threw", t)
                                }
                            }
                        }
                    }
                }
            }
        }

    private fun discoverAndAttachNavigator(
        settingsPhoneId: Int,
        opList: List<UiOps.Op>,
        parentOf: Map<Int, Int>,
        childrenByParent: Map<Int, IntArray>,
    ) {
        var cur = settingsPhoneId
        var navId = -1
        var navKids: IntArray? = null
        for (hop in 0 until 4) {
            val pid = parentOf[cur] ?: break
            val kids = childrenByParent[pid]
            if (kids != null && !containsCreateFor(opList, pid)) {
                navId = pid
                navKids = kids
                break
            }
            cur = pid
        }
        if (navId < 0 || navKids == null) return

        var navIdx = navKids.size - 1
        for (i in opList.size - 1 downTo 0) {
            val op = opList[i]
            if (op.opcode() == UiOps.OP_UPDATE && op.viewId() == navId) {
                val idxProp = op.findProp("index")
                if (idxProp != null) {
                    navIdx = idxProp.value.asInt()
                    break
                }
            }
        }

        router.attachNavigator(navId, navKids, navIdx)
    }

    private fun rootPage(): MountedManaPage? = router.pages.firstOrNull()

    fun isPageOpenForTests(): Boolean = !router.isEmpty

    fun pageRootIdForTests(): Int = rootPage()?.rootId ?: -1

    fun backButtonIdForTests(): Int = rootPage()?.backButtonId ?: -1

    fun settingsNavIdForTests(): Int = router.currentNavigatorId

    fun toggleRowIdForTests(key: String): Int = rootPage()?.ref<ManaUi.ToggleRowHandle>("toggle:$key")?.rowId ?: -1

    fun toggleTrackIdForTests(key: String): Int = rootPage()?.ref<ManaUi.ToggleRowHandle>("toggle:$key")?.toggle?.trackId ?: -1

    fun toggleThumbIdForTests(key: String): Int = rootPage()?.ref<ManaUi.ToggleRowHandle>("toggle:$key")?.toggle?.thumbId ?: -1

    fun toggleMotionSlotForTests(key: String): Int = rootPage()?.ref<ManaUi.ToggleRowHandle>("toggle:$key")?.toggle?.motionSlot ?: -1

    fun actionRowIdForTests(id: String): Int = rootPage()?.ref<Int>("action:$id") ?: -1

    fun pluginRowIdForTests(id: String): Int {
        val raw = rootPage()?.ref<Any>("plugin:$id") ?: return -1
        return when (raw) {
            is ManaUi.NavRowHandle -> raw.rowId
            is ManaUi.ToggleRowHandle -> raw.rowId
            else -> -1
        }
    }

    fun pluginSwitchTrackIdForTests(id: String): Int = rootPage()?.ref<ManaUi.ToggleRowHandle>("plugin:$id")?.toggle?.trackId ?: -1

    fun restartButtonIdForTests(): Int = rootPage()?.ref<Int>(REF_RESTART_BUTTON) ?: -1

    fun showcaseRowIdForTests(): Int = rootPage()?.ref<ManaUi.NavRowHandle>(REF_SHOWCASE_ROW)?.rowId ?: -1
}
