package com.discord.loader.mana

import android.util.Log
import com.discord.loader.CinderSettings
import com.discord.loader.Frame
import com.discord.loader.Loader
import com.discord.loader.ManaUi
import com.discord.loader.ModPlugin
import com.discord.loader.UiOps
import java.util.Arrays
import java.util.LinkedHashMap
import java.util.Locale

data class ManaSearchEntry(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val keywords: String = "",
    val icon: String = ManaIcons.SPARKLES,
    val badge: String? = null,
    val onSelect: (ManaRouter) -> Unit,
)

class ManaSettingsSearch(
    private val router: ManaRouter,
    private val openCinderRoot: () -> Unit,
    private val openPluginPage: (ModPlugin, ManaScope) -> Unit,
) {
    companion object {
        const val SEARCH_INPUT_ACCESSIBILITY_ID: String = "user_settings_search_input"

        private val CUSTOM_LOCK = Any()
        private val CUSTOM_ENTRIES = LinkedHashMap<String, ManaSearchEntry>()

        @JvmStatic
        fun registerEntry(entry: ManaSearchEntry) {
            synchronized(CUSTOM_LOCK) {
                CUSTOM_ENTRIES[entry.id] = entry
            }
        }

        @JvmStatic
        fun unregisterEntry(id: String) {
            synchronized(CUSTOM_LOCK) {
                CUSTOM_ENTRIES.remove(id)
            }
        }

        @JvmStatic
        fun clearEntriesForTests() {
            synchronized(CUSTOM_LOCK) {
                CUSTOM_ENTRIES.clear()
            }
        }

        private fun snapshotCustomEntries(): List<ManaSearchEntry> =
            synchronized(CUSTOM_LOCK) {
                if (CUSTOM_ENTRIES.isEmpty()) emptyList() else ArrayList(CUSTOM_ENTRIES.values)
            }

        @JvmStatic
        fun findSearchInputId(opList: List<UiOps.Op>): Int {
            for (i in 0 until opList.size) {
                val op = opList[i]
                if (op.opcode() == UiOps.OP_CREATE &&
                    SEARCH_INPUT_ACCESSIBILITY_ID == op.propString("accessibilityId")
                ) {
                    return op.viewId()
                }
            }
            return -1
        }

        internal fun insertSectionChild(
            baseChildren: IntArray,
            sectionId: Int,
        ): IntArray {
            if (sectionId < 0) return baseChildren

            val clean = stripChild(baseChildren, sectionId)
            val insertIdx = if (clean.isNotEmpty()) 1 else 0
            val out = IntArray(clean.size + 1)
            if (insertIdx > 0) {
                System.arraycopy(clean, 0, out, 0, insertIdx)
            }
            out[insertIdx] = sectionId
            if (insertIdx < clean.size) {
                System.arraycopy(clean, insertIdx, out, insertIdx + 1, clean.size - insertIdx)
            }
            return out
        }

        internal fun stripChild(
            children: IntArray,
            targetId: Int,
        ): IntArray {
            if (targetId < 0 || children.isEmpty()) return children
            var found = 0
            for (c in children) {
                if (c == targetId) found++
            }
            if (found == 0) return children
            val out = IntArray(children.size - found)
            var w = 0
            for (c in children) {
                if (c != targetId) out[w++] = c
            }
            return out
        }

        internal fun applyChildEdits(
            oldKids: IntArray,
            edits: List<UiOps.ChildEdit>,
        ): IntArray {
            val out = ArrayList<Int>(oldKids.size + 4)
            var cursor = 0
            for (e in edits) {
                var k = 0
                while (k < e.keepCount && cursor < oldKids.size) {
                    out.add(oldKids[cursor++])
                    k++
                }
                cursor = minOf(oldKids.size, cursor + maxOf(0, e.deleteCount))
                val ins = e.insertIds
                if (ins != null) {
                    for (id in ins) {
                        out.add(id)
                    }
                }
            }
            while (cursor < oldKids.size) {
                out.add(oldKids[cursor++])
            }
            val arr = IntArray(out.size)
            for (i in out.indices) arr[i] = out[i]
            return arr
        }
    }

    private val lock = Any()

    private var overviewPhoneId: Int = -1
    private var searchInputId: Int = -1
    private var scrollColId: Int = -1
    private var discordScrollColChildren: IntArray = IntArray(0)

    private var activeQuery: String = ""
    private var injectedScope: ManaScope? = null
    private var injectedSectionId: Int = -1
    private val injectedRowIds = LinkedHashMap<String, Int>()

    val trackedSearchInputId: Int get() = synchronized(lock) { searchInputId }
    val trackedScrollColId: Int get() = synchronized(lock) { scrollColId }
    val currentQuery: String get() = synchronized(lock) { activeQuery }
    val activeSectionId: Int get() = synchronized(lock) { injectedSectionId }
    val activeScopeId: String? get() = synchronized(lock) { injectedScope?.scopeId }
    val activeViewCount: Int get() = synchronized(lock) { injectedScope?.viewCount() ?: 0 }

    fun activeRowIdsSnapshot(): Map<String, Int> = synchronized(lock) { LinkedHashMap(injectedRowIds) }

    fun attachOverview(
        settingsPhoneId: Int,
        searchInputViewId: Int,
        scrollColumnId: Int,
        initialScrollChildren: IntArray,
    ) {
        val oldScope: ManaScope?
        synchronized(lock) {
            oldScope = injectedScope
            injectedScope = null
            injectedSectionId = -1
            injectedRowIds.clear()
            activeQuery = ""
            overviewPhoneId = settingsPhoneId
            searchInputId = searchInputViewId
            scrollColId = scrollColumnId
            discordScrollColChildren = Arrays.copyOf(initialScrollChildren, initialScrollChildren.size)
        }
        oldScope?.unmountWithoutOps()
    }

    fun onDiscordBatch(opList: List<UiOps.Op>): ByteArray? {
        val phoneId: Int
        val inputId: Int
        val colId: Int
        synchronized(lock) {
            phoneId = overviewPhoneId
            inputId = searchInputId
            colId = scrollColId
        }
        if (colId < 0 && inputId < 0 && phoneId < 0) return null

        for (i in 0 until opList.size) {
            val op = opList[i]
            if (op.opcode() == UiOps.OP_DELETE) {
                val del = op.deleteIds() ?: continue
                for (id in del) {
                    if ((phoneId >= 0 && id == phoneId) ||
                        (inputId >= 0 && id == inputId) ||
                        (colId >= 0 && id == colId)
                    ) {
                        detachOverviewWithoutOps()
                        return null
                    }
                }
            }
        }

        if (colId < 0) return null

        var lastColOpIndex = -1
        var updatedCleanKids: IntArray? = null
        synchronized(lock) {
            var running = discordScrollColChildren
            for (i in 0 until opList.size) {
                val op = opList[i]
                if (op.viewId() != colId) continue
                if (op.opcode() == UiOps.OP_CHILDREN && op.childIds() != null) {
                    running = stripChild(op.childIds(), injectedSectionId)
                    updatedCleanKids = running
                    lastColOpIndex = i
                } else if (op.opcode() == UiOps.OP_CHILD_EDITS && op.childEdits() != null) {
                    running = stripChild(applyChildEdits(running, op.childEdits()), injectedSectionId)
                    updatedCleanKids = running
                    lastColOpIndex = i
                }
            }
            if (updatedCleanKids != null) {
                discordScrollColChildren = Arrays.copyOf(updatedCleanKids, updatedCleanKids.size)
            }
        }

        if (lastColOpIndex < 0 || updatedCleanKids == null) return null

        val activeSecId: Int
        val hasQuery: Boolean
        synchronized(lock) {
            activeSecId = injectedSectionId
            hasQuery = activeQuery.isNotEmpty() && injectedScope != null && activeSecId >= 0
        }
        if (!hasQuery) return null

        val mergedKids = insertSectionChild(updatedCleanKids, activeSecId)
        val rewritten = UiOps()
        for (i in 0 until opList.size) {
            val op = opList[i]
            if (op.viewId() == colId &&
                (op.opcode() == UiOps.OP_CHILDREN || op.opcode() == UiOps.OP_CHILD_EDITS)
            ) {
                if (i == lastColOpIndex) {
                    rewritten.setChildren(colId, *mergedKids)
                }
            } else {
                rewritten.add(op)
            }
        }
        return rewritten.toBytes()
    }

    fun onViewEvent(event: UiOps.ViewEvent?): ByteArray? {
        if (event == null || !event.isViewEvent()) return null

        val currentScope = synchronized(lock) { injectedScope }
        if (currentScope != null) {
            if (currentScope.dispatchEvent(event)) {
                return Frame.DROP
            }
            if (currentScope.ownsView(event.viewId())) {
                return Frame.DROP
            }
        }

        val inputId = synchronized(lock) { searchInputId }
        if (inputId >= 0 && event.viewId() == inputId) {
            val evName = event.eventName()
            if ("changeText" == evName || "submit" == evName) {
                val query = ManaScope.extractEventText(event.payload()).trim()
                updateQuery(query)
            }

            return null
        }

        return null
    }

    fun updateQuery(rawQuery: String) {
        val query = rawQuery.trim()
        val colId: Int
        val baseKids: IntArray
        val oldScope: ManaScope?

        synchronized(lock) {
            if (query == activeQuery && (query.isEmpty() || injectedScope != null)) {
                return
            }
            activeQuery = query
            colId = scrollColId
            baseKids = Arrays.copyOf(discordScrollColChildren, discordScrollColChildren.size)
            oldScope = injectedScope
            injectedScope = null
            injectedSectionId = -1
            injectedRowIds.clear()
        }

        val batch = UiOps.create()
        val hadOld = oldScope != null
        oldScope?.unmountInto(batch)

        if (colId < 0) {
            if (!batch.isEmpty()) {
                ManaRuntime.dispatch(batch)
            }
            return
        }

        val matches = if (query.isEmpty()) emptyList() else findMatches(query)
        if (matches.isEmpty()) {
            if (hadOld) {
                batch.setChildren(colId, *baseKids)
            }
            if (!batch.isEmpty()) {
                ManaRuntime.dispatch(batch)
            }
            return
        }

        val newScope = ManaScope(ManaIds.nextScopeId("cinder-search"))
        val theme = ThemeEngine.currentTheme()
        val palette = ThemeEngine.currentPalette()
        val rowIdMap = LinkedHashMap<String, Int>(matches.size)

        val newSectionId =
            newScope.withThemeScope {
                val rowIds = IntArray(matches.size)
                for (i in matches.indices) {
                    val entry = matches[i]
                    val pos = ManaUi.RowPosition.forIndex(i, matches.size)
                    val handle =
                        ManaUi.navRowHandle(
                            batch,
                            newScope.allocator,
                            theme,
                            entry.icon,
                            entry.title,
                            entry.subtitle,
                            null,
                            false,
                            entry.badge,
                            palette[ColorToken.BG_MOD_SUBTLE],
                            ColorToken.BG_MOD_SUBTLE,
                            palette[ColorToken.TEXT_SECONDARY],
                            ColorToken.TEXT_SECONDARY,
                            "cinder.search.${entry.id}",
                            pos,
                        )
                    newScope.onPress(handle.rowId) {
                        try {
                            entry.onSelect(router)
                        } catch (t: Throwable) {
                            Log.e(Loader.TAG, "ManaSettingsSearch: selection threw for ${entry.id}", t)
                        }
                    }
                    rowIds[i] = handle.rowId
                    rowIdMap[entry.id] = handle.rowId
                }
                ManaUi.section(
                    batch,
                    newScope.allocator,
                    theme,
                    "Cinder Results",
                    rowIds,
                    null,
                )
            }

        synchronized(lock) {
            injectedScope = newScope
            injectedSectionId = newSectionId
            injectedRowIds.clear()
            injectedRowIds.putAll(rowIdMap)
        }

        val updatedKids = insertSectionChild(baseKids, newSectionId)
        batch.setChildren(colId, *updatedKids)
        ManaRuntime.dispatch(batch)
    }

    fun detachOverviewWithoutOps() {
        val oldScope: ManaScope?
        synchronized(lock) {
            oldScope = injectedScope
            injectedScope = null
            injectedSectionId = -1
            injectedRowIds.clear()
            activeQuery = ""
            overviewPhoneId = -1
            searchInputId = -1
            scrollColId = -1
            discordScrollColChildren = IntArray(0)
        }
        oldScope?.unmountWithoutOps()
    }

    internal fun findMatches(query: String): List<ManaSearchEntry> {
        val needle = query.trim().lowercase(Locale.US)
        if (needle.isEmpty()) return emptyList()

        val corpus = buildSearchCorpus()
        val results = ArrayList<ManaSearchEntry>()
        for (entry in corpus) {
            if (matchesEntry(entry, needle)) {
                results.add(entry)
            }
        }
        return results
    }

    private fun buildSearchCorpus(): List<ManaSearchEntry> {
        val list = ArrayList<ManaSearchEntry>()

        list.add(
            ManaSearchEntry(
                id = "cinder",
                title = "Cinder",
                keywords = "cinder settings loader plugins options runtime mobile",
                icon = ManaIcons.FIRE,
                badge = "Cinder",
                onSelect = { openCinderRoot() },
            ),
        )

        list.add(
            ManaSearchEntry(
                id = "mana-showcase",
                title = "Mana Showcase",
                keywords = "mana showcase widgets dsl motion demo components",
                icon = ManaIcons.SPARKLES,
                badge = "DevTools",
                onSelect = { r -> r.push(ManaShowcasePage.create()) },
            ),
        )

        val plugins = Loader.getPlugins()
        for (plugin in plugins) {
            if (plugin is CinderSettings || "cinder-settings" == plugin.id()) continue
            val desc = plugin.description()?.takeIf { it.isNotEmpty() }
            val icon = plugin.icon()?.takeIf { it.isNotEmpty() } ?: ManaIcons.PUZZLE_PIECE
            list.add(
                ManaSearchEntry(
                    id = "plugin:${plugin.id()}",
                    title = plugin.name(),
                    keywords = "plugin ${plugin.id()} ${plugin.name()} ${desc ?: ""} v${plugin.version()}",
                    icon = icon,
                    badge = "v${plugin.version()}",
                    onSelect = {
                        val scope = synchronized(lock) { injectedScope } ?: ManaScope()
                        openPluginPage(plugin, scope)
                    },
                ),
            )
        }

        val toggles = CinderSettings.snapshotToggles()
        for (toggle in toggles) {
            list.add(
                ManaSearchEntry(
                    id = "toggle:${toggle.key}",
                    title = toggle.title,
                    subtitle = toggle.subtitle,
                    keywords = "cinder toggle option ${toggle.key} ${toggle.title} ${toggle.subtitle ?: ""}",
                    icon = ManaIcons.WRENCH,
                    badge = "Toggle",
                    onSelect = { openCinderRoot() },
                ),
            )
        }

        val actions = CinderSettings.snapshotActions()
        for (action in actions) {
            list.add(
                ManaSearchEntry(
                    id = "action:${action.id}",
                    title = action.title,
                    subtitle = action.subtitle,
                    keywords = "cinder action ${action.id} ${action.title} ${action.subtitle ?: ""}",
                    icon = action.iconUri,
                    badge = "Action",
                    onSelect = { openCinderRoot() },
                ),
            )
        }

        list.addAll(snapshotCustomEntries())
        return list
    }

    private fun matchesEntry(
        entry: ManaSearchEntry,
        needleLower: String,
    ): Boolean {
        if (entry.title.lowercase(Locale.US).contains(needleLower)) return true
        if (entry.id.lowercase(Locale.US).contains(needleLower)) return true
        val sub = entry.subtitle
        if (sub != null && sub.lowercase(Locale.US).contains(needleLower)) return true
        if (entry.keywords.isNotEmpty() && entry.keywords.lowercase(Locale.US).contains(needleLower)) {
            return true
        }
        return false
    }
}
