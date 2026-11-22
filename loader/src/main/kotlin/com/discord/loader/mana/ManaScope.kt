package com.discord.loader.mana

import android.util.Log
import com.discord.loader.Loader
import com.discord.loader.ManaAnimator
import com.discord.loader.ManaUi
import com.discord.loader.UiOps
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicInteger

object ManaIds {
    const val SYNTHETIC_START: Int = UiOps.SYNTHETIC_ID_BASE + 0x1000

    private val nextViewId = AtomicInteger(SYNTHETIC_START)
    private val nextScopeSeq = AtomicInteger(1)

    @JvmField
    val allocator: ManaUi.IdAllocator = ManaUi.IdAllocator { nextViewId.getAndIncrement() }

    @JvmStatic
    fun nextId(): Int = nextViewId.getAndIncrement()

    @JvmStatic
    fun nextScopeId(prefix: String = "mana-scope"): String = "$prefix-${nextScopeSeq.getAndIncrement()}"

    @JvmStatic
    fun resetForTests() {
        nextViewId.set(SYNTHETIC_START)
        nextScopeSeq.set(1)
    }
}

fun interface ManaPatchDispatcher {
    fun dispatch(ops: UiOps): Boolean
}

object ManaRuntime {
    private val DEFAULT_DISPATCHER =
        ManaPatchDispatcher { ops ->
            if (ops.isEmpty()) false else Loader.applySyntheticOps(ops)
        }

    @Volatile
    var patchDispatcher: ManaPatchDispatcher = DEFAULT_DISPATCHER

    @JvmStatic
    fun dispatch(ops: UiOps): Boolean {
        if (ops.isEmpty()) return false
        return patchDispatcher.dispatch(ops)
    }

    @JvmStatic
    fun resetForTests() {
        patchDispatcher = DEFAULT_DISPATCHER
        ManaIds.resetForTests()
        ManaToastScheduler.resetForTests()
    }
}

class ManaScope
    @JvmOverloads
    constructor(
        val scopeId: String = ManaIds.nextScopeId("mana-scope"),
        private val dispatcher: ManaPatchDispatcher? = null,
    ) {
        private data class EventKey(
            val viewId: Int,
            val eventName: String,
        )

        private val lock = Any()
        private val ownedIds = LinkedHashSet<Int>()
        private val handlers = LinkedHashMap<EventKey, (UiOps.ViewEvent) -> Unit>()
        private val disposers = ArrayList<() -> Unit>()

        @Volatile
        private var mounted: Boolean = true

        val allocator: ManaUi.IdAllocator =
            ManaUi.IdAllocator {
                val id = ManaIds.nextId()
                synchronized(lock) {
                    ownedIds.add(id)
                }
                id
            }

        val isMounted: Boolean get() = mounted

        fun nextId(): Int = allocator.nextId()

        fun trackViewId(viewId: Int) {
            synchronized(lock) {
                ownedIds.add(viewId)
            }
        }

        fun untrackViewIds(ids: IntArray) {
            if (ids.isEmpty()) return
            synchronized(lock) {
                val idSet = HashSet<Int>(ids.size * 2)
                for (id in ids) {
                    ownedIds.remove(id)
                    idSet.add(id)
                }
                val it = handlers.entries.iterator()
                while (it.hasNext()) {
                    if (idSet.contains(it.next().key.viewId)) {
                        it.remove()
                    }
                }
            }
            ManaAnimator.cancelForViews(ids)
            for (id in ids) {
                ThemeEngine.unbindView(id)
            }
        }

        fun ownsView(viewId: Int): Boolean =
            synchronized(lock) {
                mounted && ownedIds.contains(viewId)
            }

        fun viewIds(): IntArray =
            synchronized(lock) {
                val out = IntArray(ownedIds.size)
                var i = 0
                for (id in ownedIds) {
                    out[i++] = id
                }
                out
            }

        fun viewCount(): Int = synchronized(lock) { ownedIds.size }

        fun handlerCount(): Int = synchronized(lock) { handlers.size }

        fun disposerCount(): Int = synchronized(lock) { disposers.size }

        inline fun <T> withThemeScope(block: () -> T): T = ThemeEngine.withScope(scopeId, block)

        fun allocMotionSlot(): Int = ManaMotion.allocSlot(scopeId)

        fun bindTheme(
            viewId: Int,
            propKey: String,
            token: ColorToken,
        ) {
            ThemeEngine.bind(scopeId, viewId, propKey, token)
        }

        fun rebindTheme(
            viewId: Int,
            propKey: String,
            token: ColorToken,
        ) {
            ThemeEngine.rebind(scopeId, viewId, propKey, token)
        }

        fun addDisposer(disposer: () -> Unit) {
            val runNow =
                synchronized(lock) {
                    if (!mounted) {
                        true
                    } else {
                        disposers.add(disposer)
                        false
                    }
                }
            if (runNow) {
                safeInvoke("disposer", disposer)
            }
        }

        fun on(
            viewId: Int,
            eventName: String,
            handler: (UiOps.ViewEvent) -> Unit,
        ) {
            synchronized(lock) {
                if (!mounted) return
                handlers[EventKey(viewId, eventName)] = handler
            }
        }

        fun onPress(
            viewId: Int,
            handler: () -> Unit,
        ) {
            on(viewId, "press") { handler() }
        }

        fun onLongPress(
            viewId: Int,
            handler: () -> Unit,
        ) {
            on(viewId, "longPress") { handler() }
        }

        fun onChange(
            viewId: Int,
            handler: (Int) -> Unit,
        ) {
            on(viewId, "change") { ev -> handler(extractEventInt(ev.payload())) }
        }

        fun onChangeText(
            viewId: Int,
            handler: (String) -> Unit,
        ) {
            on(viewId, "changeText") { ev -> handler(extractEventText(ev.payload())) }
        }

        fun onSubmit(
            viewId: Int,
            handler: (String) -> Unit,
        ) {
            on(viewId, "submit") { ev -> handler(extractEventText(ev.payload())) }
        }

        fun onFocus(
            viewId: Int,
            handler: () -> Unit,
        ) {
            on(viewId, "focus") { handler() }
        }

        fun onBlur(
            viewId: Int,
            handler: () -> Unit,
        ) {
            on(viewId, "blur") { handler() }
        }

        fun onDismiss(
            viewId: Int,
            handler: () -> Unit,
        ) {
            on(viewId, "dismiss") { handler() }
        }

        fun onExitEnd(
            viewId: Int,
            handler: () -> Unit,
        ) {
            on(viewId, "exitEnd") { handler() }
        }

        fun onLayout(
            viewId: Int,
            handler: (width: Double, height: Double) -> Unit,
        ) {
            on(viewId, "layout") { ev ->
                val dims = extractEventLayout(ev.payload())
                if (dims != null) {
                    handler(dims.first, dims.second)
                }
            }
        }

        fun dispatchEvent(event: UiOps.ViewEvent): Boolean {
            if (!mounted || !event.isViewEvent()) return false
            val name = event.eventName() ?: return false
            val handler =
                synchronized(lock) {
                    if (!mounted) null else handlers[EventKey(event.viewId(), name)]
                } ?: return false
            try {
                handler(event)
            } catch (t: Throwable) {
                Log.e(Loader.TAG, "ManaScope($scopeId) handler for view=${event.viewId()} $name threw", t)
            }
            return true
        }

        fun emitPatch(build: (UiOps) -> Unit): Boolean {
            if (!mounted) return false
            val ops = UiOps.create()
            build(ops)
            return emitPatch(ops)
        }

        fun emitPatch(ops: UiOps): Boolean {
            if (!mounted || ops.isEmpty()) return false
            val target = dispatcher ?: ManaRuntime.patchDispatcher
            return target.dispatch(ops)
        }

        @JvmOverloads
        fun unmountInto(
            batch: UiOps,
            includeDelete: Boolean = true,
            releaseMotionIntoBatch: Boolean = true,
        ): IntArray {
            val ids = teardownInMemory() ?: return IntArray(0)
            if (includeDelete && ids.isNotEmpty()) {
                batch.deleteViews(*ids)
            }
            if (releaseMotionIntoBatch) {
                ManaMotion.releaseScopeInto(scopeId, batch)
            } else {
                ManaMotion.unbindThemeWorklets(scopeId)
                ManaMotion.slots.releaseScope(scopeId)
            }
            return ids
        }

        fun unmountNavigatorPageInto(
            batch: UiOps,
            navId: Int,
            remainingNavChildren: IntArray,
            targetIndex: Int,
            extraDeleteIds: IntArray? = null,
            extraScopesToRelease: List<String>? = null,
        ): IntArray {
            val ids = teardownInMemory() ?: return IntArray(0)
            batch.updateView(
                navId,
                ManaUi.props(ManaUi.prop("index", UiOps.Value.ofInt(targetIndex))),
            )
            val combinedDelete =
                if (extraDeleteIds != null && extraDeleteIds.isNotEmpty()) {
                    IntArray(ids.size + extraDeleteIds.size).also { out ->
                        System.arraycopy(ids, 0, out, 0, ids.size)
                        System.arraycopy(extraDeleteIds, 0, out, ids.size, extraDeleteIds.size)
                    }
                } else {
                    ids
                }
            if (combinedDelete.isNotEmpty()) {
                batch.deleteViews(*combinedDelete)
            }
            batch.setChildren(navId, *remainingNavChildren)

            val mb = ManaMotion.batch()
            ManaMotion.unbindThemeWorklets(scopeId)
            mb.freeSlots(ManaMotion.slots.releaseScope(scopeId))
            if (extraScopesToRelease != null) {
                for (extraScope in extraScopesToRelease) {
                    ManaMotion.unbindThemeWorklets(extraScope)
                    mb.freeSlots(ManaMotion.slots.releaseScope(extraScope))
                }
            }
            mb.appendTo(batch)
            return ids
        }

        fun unmountWithoutOps(): IntArray {
            val ids = teardownInMemory() ?: return IntArray(0)
            ManaMotion.unbindThemeWorklets(scopeId)
            ManaMotion.slots.releaseScope(scopeId)
            return ids
        }

        internal fun teardownForDeferredMotionRelease(): IntArray = teardownInMemory() ?: IntArray(0)

        fun unmount(): Boolean {
            val batch = UiOps.create()
            val ids = unmountInto(batch)
            if (ids.isEmpty() && batch.isEmpty()) return false
            if (batch.isEmpty()) return true
            val target = dispatcher ?: ManaRuntime.patchDispatcher
            return target.dispatch(batch)
        }

        private fun teardownInMemory(): IntArray? {
            val ids: IntArray
            val disposerSnap: List<() -> Unit>
            synchronized(lock) {
                if (!mounted) return null
                mounted = false
                ids = IntArray(ownedIds.size)
                var i = 0
                for (id in ownedIds) {
                    ids[i++] = id
                }
                ownedIds.clear()
                handlers.clear()
                disposerSnap = if (disposers.isEmpty()) emptyList() else ArrayList(disposers)
                disposers.clear()
            }
            if (ids.isNotEmpty()) {
                ManaAnimator.cancelForViews(ids)
            }
            for (d in disposerSnap) {
                safeInvoke("disposer", d)
            }
            ThemeEngine.unbindScope(scopeId)
            return ids
        }

        private fun safeInvoke(
            label: String,
            fn: () -> Unit,
        ) {
            try {
                fn()
            } catch (t: Throwable) {
                Log.w(Loader.TAG, "ManaScope($scopeId) $label threw during unmount", t)
            }
        }

        companion object {
            @JvmStatic
            fun extractEventText(payload: UiOps.Value?): String {
                if (payload == null || payload.isNull()) return ""
                return when (payload.tag()) {
                    UiOps.TAG_STRING -> {
                        payload.asString() ?: ""
                    }

                    UiOps.TAG_STRING_MAP -> {
                        val map = payload.asStringMap() ?: return ""
                        for (nv in map) {
                            if (nv.key == "text" || nv.key == "value") {
                                return nv.value.asString() ?: ""
                            }
                        }
                        ""
                    }

                    UiOps.TAG_PROP_MAP -> {
                        val map = payload.asPropMap() ?: return ""
                        for (prop in map) {
                            val n = prop.rawNameOrNull()
                            if (n == "text" || n == "value") {
                                return prop.value.asString() ?: ""
                            }
                        }
                        ""
                    }

                    else -> {
                        payload.asString() ?: ""
                    }
                }
            }

            @JvmStatic
            @JvmOverloads
            fun extractEventInt(
                payload: UiOps.Value?,
                defaultValue: Int = 0,
            ): Int {
                if (payload == null || payload.isNull()) return defaultValue
                return when (payload.tag()) {
                    UiOps.TAG_INT32 -> {
                        payload.asInt()
                    }

                    UiOps.TAG_INT64 -> {
                        payload.asLong().toInt()
                    }

                    UiOps.TAG_FLOAT64 -> {
                        payload.asDouble().toInt()
                    }

                    UiOps.TAG_PROP_MAP -> {
                        val map = payload.asPropMap() ?: return defaultValue
                        for (prop in map) {
                            val n = prop.rawNameOrNull()
                            if (n == "value" || n == "progress") {
                                return extractEventInt(prop.value, defaultValue)
                            }
                        }
                        defaultValue
                    }

                    UiOps.TAG_STRING_MAP -> {
                        val map = payload.asStringMap() ?: return defaultValue
                        for (nv in map) {
                            if (nv.key == "value" || nv.key == "progress") {
                                return extractEventInt(nv.value, defaultValue)
                            }
                        }
                        defaultValue
                    }

                    UiOps.TAG_STRING -> {
                        payload.asString()?.toIntOrNull() ?: defaultValue
                    }

                    else -> {
                        defaultValue
                    }
                }
            }

            @JvmStatic
            fun extractEventLayout(payload: UiOps.Value?): Pair<Double, Double>? {
                if (payload == null || payload.isNull()) return null
                var w = Double.NaN
                var h = Double.NaN
                when (payload.tag()) {
                    UiOps.TAG_PROP_MAP -> {
                        val map = payload.asPropMap() ?: return null
                        for (prop in map) {
                            val n = prop.rawNameOrNull()
                            if (n == "width") {
                                w = extractNumericDouble(prop.value)
                            } else if (n == "height") {
                                h = extractNumericDouble(prop.value)
                            }
                        }
                    }

                    UiOps.TAG_STRING_MAP -> {
                        val map = payload.asStringMap() ?: return null
                        for (nv in map) {
                            if (nv.key == "width") {
                                w = extractNumericDouble(nv.value)
                            } else if (nv.key == "height") {
                                h = extractNumericDouble(nv.value)
                            }
                        }
                    }

                    else -> {
                        return null
                    }
                }
                if (w.isNaN() || h.isNaN()) return null
                return Pair(w, h)
            }

            private fun extractNumericDouble(v: UiOps.Value?): Double {
                if (v == null || v.isNull()) return Double.NaN
                return when (v.tag()) {
                    UiOps.TAG_FLOAT64 -> v.asDouble()
                    UiOps.TAG_INT32 -> v.asInt().toDouble()
                    UiOps.TAG_INT64 -> v.asLong().toDouble()
                    else -> Double.NaN
                }
            }
        }
    }
