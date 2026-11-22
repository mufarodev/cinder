package com.discord.loader.mana

import android.util.Log
import com.discord.loader.Frame
import com.discord.loader.Loader
import com.discord.loader.ManaUi
import com.discord.loader.UiOps
import java.util.Arrays
import java.util.LinkedHashMap

enum class OverlayKind {
    SHEET,
    DIALOG,
    TOAST,
}

class MountedManaPage internal constructor(
    val page: ManaPage,
    val scope: ManaScope,
    val rootId: Int,
    val screenBoxId: Int,
    internal val baseScreenBoxChildren: IntArray,
    val backButtonId: Int,
    val mountedInNavigator: Boolean,
    val hostNavigatorId: Int,
    private val refs: Map<String, Any>,
) {
    @Suppress("UNCHECKED_CAST")
    fun <T> ref(key: String): T? = refs[key] as? T

    fun refKeys(): Set<String> = refs.keys
}

class MountedOverlay internal constructor(
    val kind: OverlayKind,
    val scope: ManaScope,
    val rootId: Int,
    val hostParentId: Int,
    val dismissible: Boolean,
    internal val onDismiss: (() -> Unit)?,
    private val refs: Map<String, Any>,
) {
    @Suppress("UNCHECKED_CAST")
    fun <T> ref(key: String): T? = refs[key] as? T

    fun refKeys(): Set<String> = refs.keys
}

class ManaRouter
    @JvmOverloads
    constructor(
        private val dispatcher: ManaPatchDispatcher? = null,
    ) {
        companion object {
            private const val APPEARED_SWALLOW_WINDOW_MS: Long = 900L
        }

        private val lock = Any()

        private var navigatorId: Int = -1
        private var baseNavChildren: IntArray? = null
        private var baseNavIndex: Int = -1

        private var fallbackOverlayHostId: Int = -1
        private var fallbackOverlayBaseChildren: IntArray? = null

        private val pageStack = ArrayList<MountedManaPage>()

        private var activeSheet: MountedOverlay? = null
        private var activeDialog: MountedOverlay? = null
        private var activeToast: MountedOverlay? = null

        private var swallowAppearedUntilMs: Long = 0L
        private var swallowAppearedNavId: Int = -1

        val depth: Int get() = synchronized(lock) { pageStack.size }
        val isEmpty: Boolean get() = synchronized(lock) { pageStack.isEmpty() }
        val topPage: MountedManaPage? get() = synchronized(lock) { pageStack.lastOrNull() }
        val pages: List<MountedManaPage> get() = synchronized(lock) { ArrayList(pageStack) }
        val currentNavigatorId: Int get() = synchronized(lock) { navigatorId }
        val currentSheet: MountedOverlay? get() = synchronized(lock) { activeSheet }
        val currentDialog: MountedOverlay? get() = synchronized(lock) { activeDialog }
        val currentToast: MountedOverlay? get() = synchronized(lock) { activeToast }

        fun attachNavigator(
            navId: Int,
            children: IntArray,
            index: Int,
        ) {
            synchronized(lock) {
                navigatorId = navId
                baseNavChildren = Arrays.copyOf(children, children.size)
                baseNavIndex = index
            }
        }

        fun setFallbackOverlayHost(
            hostParentId: Int,
            baseChildren: IntArray,
        ) {
            synchronized(lock) {
                fallbackOverlayHostId = hostParentId
                fallbackOverlayBaseChildren = Arrays.copyOf(baseChildren, baseChildren.size)
            }
        }

        fun observeDiscordBatch(opList: List<UiOps.Op>) {
            val navId: Int
            val fbHostId: Int
            synchronized(lock) {
                navId = navigatorId
                fbHostId = fallbackOverlayHostId
            }
            if (navId < 0 && fbHostId < 0) return

            var updatedNavKids: IntArray? = null
            var updatedNavIdx = Int.MIN_VALUE
            var updatedFbKids: IntArray? = null

            for (i in 0 until opList.size) {
                val op = opList[i]
                val vid = op.viewId()
                val code = op.opcode()
                if (navId >= 0 && vid == navId) {
                    if (code == UiOps.OP_CHILDREN && op.childIds() != null) {
                        updatedNavKids = op.childIds()
                    } else if (code == UiOps.OP_CHILD_EDITS && op.childEdits() != null) {
                        val base = synchronized(lock) { updatedNavKids ?: baseNavChildren }
                        if (base != null) {
                            updatedNavKids = applyChildEdits(base, op.childEdits())
                        }
                    } else if (code == UiOps.OP_UPDATE) {
                        val idxProp = op.findProp("index")
                        if (idxProp != null) {
                            updatedNavIdx = idxProp.value.asInt()
                        }
                    }
                } else if (fbHostId >= 0 && vid == fbHostId) {
                    if (code == UiOps.OP_CHILDREN && op.childIds() != null) {
                        updatedFbKids = op.childIds()
                    } else if (code == UiOps.OP_CHILD_EDITS && op.childEdits() != null) {
                        val base = synchronized(lock) { updatedFbKids ?: fallbackOverlayBaseChildren }
                        if (base != null) {
                            updatedFbKids = applyChildEdits(base, op.childEdits())
                        }
                    }
                }
            }

            val tornDownScopes = ArrayList<ManaScope>()
            synchronized(lock) {
                if (updatedFbKids != null) {
                    fallbackOverlayBaseChildren = Arrays.copyOf(updatedFbKids, updatedFbKids.size)
                }
                if (updatedNavKids != null || updatedNavIdx != Int.MIN_VALUE) {
                    if (updatedNavKids != null) {
                        baseNavChildren = Arrays.copyOf(updatedNavKids, updatedNavKids.size)
                    }
                    if (updatedNavIdx != Int.MIN_VALUE) {
                        baseNavIndex = updatedNavIdx
                    }
                    if (pageStack.isNotEmpty() && pageStack.any { it.mountedInNavigator && it.hostNavigatorId == navId }) {
                        activeDialog?.let {
                            tornDownScopes.add(it.scope)
                            activeDialog = null
                        }
                        activeSheet?.let {
                            tornDownScopes.add(it.scope)
                            activeSheet = null
                        }
                        activeToast?.let {
                            tornDownScopes.add(it.scope)
                            activeToast = null
                        }
                        for (j in pageStack.lastIndex downTo 0) {
                            tornDownScopes.add(pageStack[j].scope)
                        }
                        pageStack.clear()
                    }
                }
            }
            for (scope in tornDownScopes) {
                scope.unmountWithoutOps()
            }
        }

        fun push(page: ManaPage): MountedManaPage {
            val scope =
                ManaScope(
                    scopeId = page.scopeId ?: ManaIds.nextScopeId("mana-page"),
                    dispatcher = dispatcher,
                )
            val context = ManaContext(this, scope)
            val refs = LinkedHashMap<String, Any>()
            val batch = UiOps.create()
            val theme = ThemeEngine.currentTheme()
            val palette = ThemeEngine.currentPalette()

            val mounted =
                scope.withThemeScope {
                    val builder = ManaPageBuilder(context, scope, refs)
                    page.content(builder)

                    val headerActionIds = builder.emitHeaderActions(batch, palette)
                    val scaffold =
                        ManaUi.pageScaffold(
                            batch,
                            scope.allocator,
                            theme,
                            page.title,
                            page.subtitle,
                            page.titleAccessibilityId,
                            if (headerActionIds.isEmpty()) null else headerActionIds,
                        )

                    val sectionIds = IntArray(builder.sectionEmitters.size)
                    for (i in builder.sectionEmitters.indices) {
                        sectionIds[i] = builder.sectionEmitters[i](batch, theme, palette)
                    }
                    val initialChildren =
                        builder.attachRestartBanner(
                            batch,
                            theme,
                            palette,
                            scaffold.contentColumnId,
                            sectionIds,
                        )
                    scaffold.finish(batch, *initialChildren)

                    scope.onPress(scaffold.backButtonId) { pop() }

                    val navSnap =
                        synchronized(lock) {
                            val nid = navigatorId
                            val base = baseNavChildren
                            if (nid >= 0 && base != null) {
                                val currentNavKids = buildNavigatorChildrenLocked(base)
                                Pair(nid, currentNavKids)
                            } else {
                                null
                            }
                        }

                    val mountedInNav: Boolean
                    val hostNavId: Int
                    val rootViewId: Int
                    if (navSnap != null) {
                        mountedInNav = true
                        hostNavId = navSnap.first
                        rootViewId = scaffold.rootId
                        ManaUi.appendNavigatorPush(batch, hostNavId, navSnap.second, scaffold.rootId)
                    } else {
                        mountedInNav = false
                        hostNavId = -1
                        val modalId = scope.nextId()
                        rootViewId = modalId
                        batch.createView(
                            modalId,
                            "modal",
                            ManaUi.props(
                                ManaUi.prop("fullscreen", UiOps.Value.ofBool(true)),
                                ManaUi.prop("dismissible", UiOps.Value.ofBool(true)),
                                ManaUi.prop("on", ManaUi.stringArray("dismiss")),
                            ),
                        )
                        batch.setChildren(modalId, scaffold.rootId)
                        scope.onDismiss(modalId) { pop() }
                    }

                    MountedManaPage(
                        page = page,
                        scope = scope,
                        rootId = rootViewId,
                        screenBoxId = scaffold.screenBoxId,
                        baseScreenBoxChildren = scaffold.baseScreenBoxChildren(),
                        backButtonId = scaffold.backButtonId,
                        mountedInNavigator = mountedInNav,
                        hostNavigatorId = hostNavId,
                        refs = refs,
                    )
                }

            synchronized(lock) {
                pageStack.add(mounted)
            }
            dispatch(batch)
            return mounted
        }

        fun pop(): Boolean {
            val top: MountedManaPage
            val hostedOverlays = ArrayList<MountedOverlay>()
            val remainingNavKids: IntArray?
            val targetIndex: Int

            synchronized(lock) {
                if (pageStack.isEmpty()) return false
                top = pageStack.removeAt(pageStack.lastIndex)

                detachOverlaysForHostLocked(top.screenBoxId, hostedOverlays)

                val base = baseNavChildren
                if (top.mountedInNavigator && top.hostNavigatorId >= 0 && base != null) {
                    remainingNavKids = buildNavigatorChildrenLocked(base)
                    targetIndex =
                        if (pageStack.isEmpty()) {
                            if (baseNavIndex >= 0) baseNavIndex else (base.size - 1)
                        } else {
                            remainingNavKids.size - 1
                        }
                    swallowAppearedUntilMs = System.currentTimeMillis() + APPEARED_SWALLOW_WINDOW_MS
                    swallowAppearedNavId = top.hostNavigatorId
                } else {
                    remainingNavKids = null
                    targetIndex = -1
                }
            }

            val batch = UiOps.create()
            val extraDeleteIds = ArrayList<Int>()
            val extraScopes = ArrayList<String>()
            for (ov in hostedOverlays) {
                val ovIds = ov.scope.teardownForDeferredMotionRelease()
                if ((ov.kind == OverlayKind.SHEET || ov.kind == OverlayKind.DIALOG) && ovIds.isNotEmpty()) {
                    batch.deleteViews(*ovIds)
                } else {
                    for (id in ovIds) extraDeleteIds.add(id)
                }
                extraScopes.add(ov.scope.scopeId)
            }

            if (remainingNavKids != null && top.hostNavigatorId >= 0) {
                top.scope.unmountNavigatorPageInto(
                    batch = batch,
                    navId = top.hostNavigatorId,
                    remainingNavChildren = remainingNavKids,
                    targetIndex = targetIndex,
                    extraDeleteIds = if (extraDeleteIds.isEmpty()) null else extraDeleteIds.toIntArray(),
                    extraScopesToRelease = if (extraScopes.isEmpty()) null else extraScopes,
                )
            } else {
                val pageIds = top.scope.teardownForDeferredMotionRelease()
                val combined = IntArray(pageIds.size + extraDeleteIds.size)
                System.arraycopy(pageIds, 0, combined, 0, pageIds.size)
                for (i in extraDeleteIds.indices) {
                    combined[pageIds.size + i] = extraDeleteIds[i]
                }
                if (combined.isNotEmpty()) {
                    batch.deleteViews(*combined)
                }
                val mb = ManaMotion.batch()
                ManaMotion.unbindThemeWorklets(top.scope.scopeId)
                mb.freeSlots(ManaMotion.slots.releaseScope(top.scope.scopeId))
                for (s in extraScopes) {
                    ManaMotion.unbindThemeWorklets(s)
                    mb.freeSlots(ManaMotion.slots.releaseScope(s))
                }
                mb.appendTo(batch)
            }

            dispatch(batch)
            return true
        }

        fun popToRoot(): Int {
            val poppedPages: List<MountedManaPage>
            val hostedOverlays = ArrayList<MountedOverlay>()
            val navId: Int
            val baseKids: IntArray?
            val targetIndex: Int

            synchronized(lock) {
                if (pageStack.isEmpty()) return 0
                poppedPages = ArrayList(pageStack)
                pageStack.clear()

                for (p in poppedPages) {
                    detachOverlaysForHostLocked(p.screenBoxId, hostedOverlays)
                }

                val anyInNav = poppedPages.any { it.mountedInNavigator && it.hostNavigatorId >= 0 }
                val base = baseNavChildren
                if (anyInNav && navigatorId >= 0 && base != null) {
                    navId = navigatorId
                    baseKids = Arrays.copyOf(base, base.size)
                    targetIndex = if (baseNavIndex >= 0) baseNavIndex else (base.size - 1)
                    swallowAppearedUntilMs = System.currentTimeMillis() + APPEARED_SWALLOW_WINDOW_MS
                    swallowAppearedNavId = navId
                } else {
                    navId = -1
                    baseKids = null
                    targetIndex = -1
                }
            }

            val batch = UiOps.create()
            val allDeleteIds = ArrayList<Int>()
            val mb = ManaMotion.batch()

            for (ov in hostedOverlays) {
                val ids = ov.scope.teardownForDeferredMotionRelease()
                if ((ov.kind == OverlayKind.SHEET || ov.kind == OverlayKind.DIALOG) && ids.isNotEmpty()) {
                    batch.deleteViews(*ids)
                } else {
                    for (id in ids) allDeleteIds.add(id)
                }
                ManaMotion.unbindThemeWorklets(ov.scope.scopeId)
                mb.freeSlots(ManaMotion.slots.releaseScope(ov.scope.scopeId))
            }

            for (i in poppedPages.lastIndex downTo 0) {
                val scope = poppedPages[i].scope
                val ids = scope.teardownForDeferredMotionRelease()
                for (id in ids) allDeleteIds.add(id)
                ManaMotion.unbindThemeWorklets(scope.scopeId)
                mb.freeSlots(ManaMotion.slots.releaseScope(scope.scopeId))
            }

            if (navId >= 0 && baseKids != null) {
                batch.updateView(navId, ManaUi.props(ManaUi.prop("index", UiOps.Value.ofInt(targetIndex))))
                if (allDeleteIds.isNotEmpty()) {
                    batch.deleteViews(*allDeleteIds.toIntArray())
                }
                batch.setChildren(navId, *baseKids)
            } else if (allDeleteIds.isNotEmpty()) {
                batch.deleteViews(*allDeleteIds.toIntArray())
            }
            mb.appendTo(batch)

            dispatch(batch)
            return poppedPages.size
        }

        fun presentSheet(sheet: ManaSheet): MountedOverlay {
            dismissOverlay(OverlayKind.SHEET, invokeOnDismiss = false)
            return mountOverlay(
                kind = OverlayKind.SHEET,
                scopeId = sheet.scopeId ?: ManaIds.nextScopeId("mana-sheet"),
                dismissible = sheet.dismissible,
                onDismiss = sheet.onDismiss,
            ) { context, batch, refs ->
                ManaSheetRenderer.buildInto(sheet, context, batch, refs) {
                    dismissSheet(invokeOnDismiss = true)
                }
            }
        }

        @JvmOverloads
        fun dismissSheet(invokeOnDismiss: Boolean = true): Boolean = dismissOverlay(OverlayKind.SHEET, invokeOnDismiss)

        fun showDialog(dialog: ManaDialog): MountedOverlay {
            dismissOverlay(OverlayKind.DIALOG, invokeOnDismiss = false)
            return mountOverlay(
                kind = OverlayKind.DIALOG,
                scopeId = dialog.scopeId ?: ManaIds.nextScopeId("mana-dialog"),
                dismissible = dialog.dismissible,
                onDismiss = dialog.onDismiss,
            ) { context, batch, refs ->
                ManaDialogRenderer.buildInto(dialog, context, batch, refs) {
                    dismissDialog(invokeOnDismiss = true)
                }
            }
        }

        @JvmOverloads
        fun dismissDialog(invokeOnDismiss: Boolean = true): Boolean = dismissOverlay(OverlayKind.DIALOG, invokeOnDismiss)

        fun showToast(toast: ManaToast): MountedOverlay {
            dismissOverlay(OverlayKind.TOAST, invokeOnDismiss = false)
            val mounted =
                mountOverlay(
                    kind = OverlayKind.TOAST,
                    scopeId = ManaIds.nextScopeId("mana-toast"),
                    dismissible = true,
                    onDismiss = null,
                ) { context, batch, refs ->
                    ManaToastRenderer.buildInto(toast, context, batch, refs) {
                        dismissToast()
                    }
                }
            if (toast.durationMs > 0L) {
                val toastScope = mounted.scope
                val cancelTimer =
                    ManaToastScheduler.schedule(toast.durationMs) {
                        val stillActive = synchronized(lock) { activeToast === mounted }
                        if (stillActive) {
                            dismissToast()
                        }
                    }
                toastScope.addDisposer(cancelTimer)
            }
            return mounted
        }

        @JvmOverloads
        fun showToast(
            message: String,
            icon: String? = null,
            tone: ManaToastTone = ManaToastTone.DEFAULT,
            durationMs: Long = ManaToast.DEFAULT_DURATION_MS,
        ): MountedOverlay = showToast(ManaToast(message, icon, tone, durationMs))

        fun dismissToast(): Boolean = dismissOverlay(OverlayKind.TOAST, invokeOnDismiss = false)

        private fun mountOverlay(
            kind: OverlayKind,
            scopeId: String,
            dismissible: Boolean,
            onDismiss: (() -> Unit)?,
            build: (ManaContext, UiOps, MutableMap<String, Any>) -> Int,
        ): MountedOverlay {
            val scope = ManaScope(scopeId = scopeId, dispatcher = dispatcher)
            val context = ManaContext(this, scope)
            val refs = LinkedHashMap<String, Any>()
            val batch = UiOps.create()

            val overlay =
                scope.withThemeScope {
                    val contentRootId = build(context, batch, refs)
                    val resolvedHost = synchronized(lock) { resolveOverlayHostLocked() }

                    if (resolvedHost != null) {
                        val hostParentId = resolvedHost.first
                        val built = MountedOverlay(kind, scope, contentRootId, hostParentId, dismissible, onDismiss, refs)
                        val updatedHostChildren =
                            synchronized(lock) {
                                setActiveOverlayLocked(kind, built)
                                computeHostChildrenLocked(hostParentId, resolvedHost.second)
                            }
                        batch.setChildren(hostParentId, *updatedHostChildren)
                        built
                    } else if (kind == OverlayKind.SHEET || kind == OverlayKind.DIALOG) {
                        val built = MountedOverlay(kind, scope, contentRootId, -1, dismissible, onDismiss, refs)
                        synchronized(lock) {
                            setActiveOverlayLocked(kind, built)
                        }
                        built
                    } else {
                        val modalId = scope.nextId()
                        batch.createView(
                            modalId,
                            "modal",
                            ManaUi.props(
                                ManaUi.prop("fullscreen", UiOps.Value.ofBool(true)),
                                ManaUi.prop("dismissible", UiOps.Value.ofBool(dismissible)),
                                ManaUi.prop("on", ManaUi.stringArray("dismiss")),
                            ),
                        )
                        batch.setChildren(modalId, contentRootId)
                        scope.onDismiss(modalId) {
                            if (dismissible) {
                                dismissOverlay(kind, invokeOnDismiss = true)
                            }
                        }
                        val built = MountedOverlay(kind, scope, modalId, -1, dismissible, onDismiss, refs)
                        synchronized(lock) {
                            setActiveOverlayLocked(kind, built)
                        }
                        built
                    }
                }

            dispatch(batch)
            return overlay
        }

        private fun dismissOverlay(
            kind: OverlayKind,
            invokeOnDismiss: Boolean,
        ): Boolean {
            val removed: MountedOverlay
            val hostParentId: Int
            val updatedHostChildren: IntArray?

            synchronized(lock) {
                val current = getActiveOverlayLocked(kind) ?: return false
                removed = current
                setActiveOverlayLocked(kind, null)
                hostParentId = current.hostParentId
                if (hostParentId >= 0) {
                    val base = findBaseChildrenForHostLocked(hostParentId)
                    updatedHostChildren =
                        if (base != null) {
                            computeHostChildrenLocked(hostParentId, base)
                        } else {
                            null
                        }
                } else {
                    updatedHostChildren = null
                }
            }

            val batch = UiOps.create()
            removed.scope.unmountInto(batch, includeDelete = true, releaseMotionIntoBatch = true)
            if (hostParentId >= 0 && updatedHostChildren != null) {
                batch.setChildren(hostParentId, *updatedHostChildren)
            }
            dispatch(batch)

            if (invokeOnDismiss && removed.onDismiss != null) {
                try {
                    removed.onDismiss.invoke()
                } catch (t: Throwable) {
                    Log.e(Loader.TAG, "ManaRouter overlay onDismiss threw ($kind)", t)
                }
            }
            return true
        }

        fun onBackPress(): Boolean {
            val dlg = synchronized(lock) { activeDialog }
            if (dlg != null) {
                if (dlg.dismissible) {
                    dismissDialog(invokeOnDismiss = true)
                }
                return true
            }
            val sheet = synchronized(lock) { activeSheet }
            if (sheet != null) {
                if (sheet.dismissible) {
                    dismissSheet(invokeOnDismiss = true)
                }
                return true
            }
            if (!isEmpty) {
                pop()
                return true
            }
            return false
        }

        fun onViewEvent(event: UiOps.ViewEvent?): ByteArray? {
            if (event == null || !event.isViewEvent()) return null

            val vid = event.viewId()
            val evName = event.eventName()

            val navMatch: Int
            val hasPages: Boolean
            var swallowAppeared = false
            synchronized(lock) {
                navMatch = navigatorId
                hasPages = pageStack.isNotEmpty()
                if (!hasPages && vid == swallowAppearedNavId && System.currentTimeMillis() <= swallowAppearedUntilMs) {
                    swallowAppeared = true
                    if ("appeared" == evName) {
                        swallowAppearedNavId = -1
                    }
                }
            }

            if (navMatch >= 0 && vid == navMatch) {
                if ("navigate" == evName && hasPages) {
                    val targetIndex = extractNavigateIndex(event.payload())
                    if (targetIndex != null) {
                        popDownToNavigatorIndex(targetIndex)
                    } else {
                        pop()
                    }
                    return Frame.DROP
                }
                if ("appeared" == evName && (hasPages || swallowAppeared)) {
                    return Frame.DROP
                }
            }

            if (!event.isSyntheticView()) return null

            val targetScope =
                synchronized(lock) {
                    findOwningScopeLocked(vid)
                } ?: return null

            targetScope.dispatchEvent(event)
            return Frame.DROP
        }

        fun resetForTests() {
            val scopes = ArrayList<ManaScope>()
            synchronized(lock) {
                activeDialog?.let {
                    scopes.add(it.scope)
                    activeDialog = null
                }
                activeSheet?.let {
                    scopes.add(it.scope)
                    activeSheet = null
                }
                activeToast?.let {
                    scopes.add(it.scope)
                    activeToast = null
                }
                for (i in pageStack.lastIndex downTo 0) {
                    scopes.add(pageStack[i].scope)
                }
                pageStack.clear()
                navigatorId = -1
                baseNavChildren = null
                baseNavIndex = -1
                fallbackOverlayHostId = -1
                fallbackOverlayBaseChildren = null
                swallowAppearedUntilMs = 0L
                swallowAppearedNavId = -1
            }
            for (s in scopes) {
                s.unmountWithoutOps()
            }
        }

        private fun popDownToNavigatorIndex(targetIndex: Int) {
            val popsNeeded =
                synchronized(lock) {
                    val base = baseNavChildren ?: return@synchronized 1
                    val currentTopIndex = base.size + pageStack.size - 1
                    if (targetIndex < 0 || targetIndex >= currentTopIndex) {
                        1
                    } else {
                        (currentTopIndex - targetIndex).coerceIn(1, pageStack.size)
                    }
                }
            for (i in 0 until popsNeeded) {
                if (!pop()) break
            }
        }

        private fun extractNavigateIndex(payload: UiOps.Value?): Int? {
            if (payload == null || payload.isNull()) return null
            return when (payload.tag()) {
                UiOps.TAG_INT32, UiOps.TAG_INT64 -> {
                    payload.asInt()
                }

                UiOps.TAG_STRING_MAP -> {
                    val map = payload.asStringMap() ?: return null
                    for (nv in map) {
                        if (nv.key == "index" && (nv.value.tag() == UiOps.TAG_INT32 || nv.value.tag() == UiOps.TAG_INT64)) {
                            return nv.value.asInt()
                        }
                    }
                    null
                }

                UiOps.TAG_PROP_MAP -> {
                    val map = payload.asPropMap() ?: return null
                    for (prop in map) {
                        if (prop.rawNameOrNull() == "index" &&
                            (prop.value.tag() == UiOps.TAG_INT32 || prop.value.tag() == UiOps.TAG_INT64)
                        ) {
                            return prop.value.asInt()
                        }
                    }
                    null
                }

                else -> {
                    null
                }
            }
        }

        private fun findOwningScopeLocked(viewId: Int): ManaScope? {
            activeDialog?.let { if (it.scope.ownsView(viewId)) return it.scope }
            activeSheet?.let { if (it.scope.ownsView(viewId)) return it.scope }
            activeToast?.let { if (it.scope.ownsView(viewId)) return it.scope }
            for (i in pageStack.lastIndex downTo 0) {
                val scope = pageStack[i].scope
                if (scope.ownsView(viewId)) return scope
            }
            return null
        }

        private fun buildNavigatorChildrenLocked(base: IntArray): IntArray {
            var extra = 0
            for (p in pageStack) {
                if (p.mountedInNavigator && p.hostNavigatorId == navigatorId) extra++
            }
            val out = Arrays.copyOf(base, base.size + extra)
            var cursor = base.size
            for (p in pageStack) {
                if (p.mountedInNavigator && p.hostNavigatorId == navigatorId) {
                    out[cursor++] = p.rootId
                }
            }
            return out
        }

        private fun resolveOverlayHostLocked(): Pair<Int, IntArray>? {
            val top = pageStack.lastOrNull()
            if (top != null) {
                return Pair(top.screenBoxId, top.baseScreenBoxChildren)
            }
            val fbId = fallbackOverlayHostId
            val fbKids = fallbackOverlayBaseChildren
            if (fbId >= 0 && fbKids != null) {
                return Pair(fbId, fbKids)
            }
            return null
        }

        private fun findBaseChildrenForHostLocked(hostParentId: Int): IntArray? {
            for (i in pageStack.lastIndex downTo 0) {
                if (pageStack[i].screenBoxId == hostParentId) {
                    return pageStack[i].baseScreenBoxChildren
                }
            }
            if (fallbackOverlayHostId == hostParentId) {
                return fallbackOverlayBaseChildren
            }
            return null
        }

        private fun computeHostChildrenLocked(
            hostParentId: Int,
            baseChildren: IntArray,
        ): IntArray {
            val extra = ArrayList<Int>(3)
            activeSheet?.let { if (it.hostParentId == hostParentId) extra.add(it.rootId) }
            activeDialog?.let { if (it.hostParentId == hostParentId) extra.add(it.rootId) }
            activeToast?.let { if (it.hostParentId == hostParentId) extra.add(it.rootId) }
            val out = Arrays.copyOf(baseChildren, baseChildren.size + extra.size)
            for (i in extra.indices) {
                out[baseChildren.size + i] = extra[i]
            }
            return out
        }

        private fun detachOverlaysForHostLocked(
            hostParentId: Int,
            out: MutableList<MountedOverlay>,
        ) {
            activeDialog?.let {
                if (it.hostParentId == hostParentId) {
                    out.add(it)
                    activeDialog = null
                }
            }
            activeSheet?.let {
                if (it.hostParentId == hostParentId) {
                    out.add(it)
                    activeSheet = null
                }
            }
            activeToast?.let {
                if (it.hostParentId == hostParentId) {
                    out.add(it)
                    activeToast = null
                }
            }
        }

        private fun getActiveOverlayLocked(kind: OverlayKind): MountedOverlay? =
            when (kind) {
                OverlayKind.SHEET -> activeSheet
                OverlayKind.DIALOG -> activeDialog
                OverlayKind.TOAST -> activeToast
            }

        private fun setActiveOverlayLocked(
            kind: OverlayKind,
            overlay: MountedOverlay?,
        ) {
            when (kind) {
                OverlayKind.SHEET -> activeSheet = overlay
                OverlayKind.DIALOG -> activeDialog = overlay
                OverlayKind.TOAST -> activeToast = overlay
            }
        }

        private fun dispatch(ops: UiOps): Boolean {
            if (ops.isEmpty()) return false
            val target = dispatcher ?: ManaRuntime.patchDispatcher
            return target.dispatch(ops)
        }

        private fun applyChildEdits(
            current: IntArray,
            edits: List<UiOps.ChildEdit>,
        ): IntArray {
            val out = ArrayList<Int>(current.size + 4)
            var cursor = 0
            for (e in 0 until edits.size) {
                val ce = edits[e]
                val keepEnd = minOf(current.size, cursor + ce.keepCount)
                while (cursor < keepEnd) {
                    out.add(current[cursor++])
                }
                cursor = minOf(current.size, cursor + ce.deleteCount)
                for (ins in ce.insertIds) {
                    out.add(ins)
                }
            }
            while (cursor < current.size) {
                out.add(current[cursor++])
            }
            return out.toIntArray()
        }
    }
