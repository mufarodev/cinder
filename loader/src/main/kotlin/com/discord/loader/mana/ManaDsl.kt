package com.discord.loader.mana

import android.util.Log
import com.discord.loader.Loader
import com.discord.loader.ManaUi
import com.discord.loader.UiOps

@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class ManaDsl

@JvmInline
value class ManaViewId(
    val raw: Int,
) {
    val isSynthetic: Boolean get() = UiOps.isSyntheticId(raw)
}

enum class ManaTone {
    DEFAULT,
    ACCENT,
    DANGER,
    MUTED,
    ;

    fun resolveColor(theme: ManaUi.Theme): Long =
        when (this) {
            DEFAULT -> theme.primaryText
            ACCENT -> theme.brandAccent
            DANGER -> theme.dangerText
            MUTED -> theme.mutedText
        }
}

fun ManaUi.Theme.colorOf(tone: ManaTone): Long = tone.resolveColor(this)

fun Any?.toViewWireValue(): UiOps.Value =
    when (this) {
        null -> UiOps.Value.ofNull()
        is UiOps.Value -> this
        is Boolean -> UiOps.Value.ofBool(this)
        is Int -> UiOps.Value.ofInt(this)
        is Long -> UiOps.Value.ofLong(this)
        is Float -> UiOps.Value.ofDouble(this.toDouble())
        is Double -> UiOps.Value.ofDouble(this)
        is String -> UiOps.Value.ofString(this)
        is ManaViewId -> UiOps.Value.ofInt(this.raw)
        is IntArray -> UiOps.Value.ofWordList(*this)
        else -> UiOps.Value.ofString(this.toString())
    }

data class ManaNodeMeta(
    val id: Int,
    val type: String,
    val tag: String? = null,
)

data class ManaBatch(
    val rootId: Int,
    val ops: UiOps,
    val nodes: List<ManaNodeMeta>,
    val allocatedIds: IntArray,
) {
    val opCount: Int get() = ops.size()

    fun toBytes(): ByteArray = ops.toBytes()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ManaBatch) return false
        return rootId == other.rootId &&
            nodes == other.nodes &&
            allocatedIds.contentEquals(other.allocatedIds) &&
            ops.size() == other.ops.size()
    }

    override fun hashCode(): Int {
        var result = rootId
        result = 31 * result + nodes.hashCode()
        result = 31 * result + allocatedIds.contentHashCode()
        result = 31 * result + ops.size()
        return result
    }
}

@ManaDsl
class ManaPropsScope {
    private val entries = ArrayList<UiOps.Prop>()

    fun prop(
        propId: Int,
        value: Any?,
    ) {
        entries.add(UiOps.Prop(propId, value.toViewWireValue()))
    }

    fun prop(
        name: String,
        value: Any?,
    ) {
        entries.add(UiOps.Prop.named(name, value.toViewWireValue()))
    }

    fun propIfKnown(
        name: String,
        value: Any?,
    ): Boolean {
        val id = UiOps.propId(name)
        if (id < 0) return false
        entries.add(UiOps.Prop(id, value.toViewWireValue()))
        return true
    }

    fun build(): List<UiOps.Prop> = entries
}

@ManaDsl
class ManaOpsScope(
    val theme: ManaUi.Theme,
    allocator: ManaUi.IdAllocator,
) {
    private val tracker = ManaUi.TrackingAllocator(allocator)
    val ops: UiOps = UiOps.create()
    private val createdNodes = ArrayList<ManaNodeMeta>()
    internal var rootId: Int = -1

    fun nextId(): Int = tracker.nextId()

    fun view(
        type: String = "view",
        tag: String? = null,
        props: ManaPropsScope.() -> Unit = {},
    ): Int {
        val id = tracker.nextId()
        val propScope = ManaPropsScope()
        propScope.props()
        ops.createView(id, type, propScope.build())
        createdNodes.add(ManaNodeMeta(id = id, type = type, tag = tag))
        if (rootId < 0) {
            rootId = id
        }
        return id
    }

    fun label(
        text: String,
        tone: ManaTone = ManaTone.DEFAULT,
        fontSize: Double = 16.0,
        tag: String? = null,
    ): Int {
        val resolvedColor = theme.colorOf(tone)
        return view(type = "text", tag = tag) {
            propIfKnown("text", text)
            propIfKnown("color", resolvedColor)
            propIfKnown("fontSize", fontSize)
        }
    }

    fun children(
        parentId: Int,
        vararg childIds: Int,
    ) {
        ops.setChildren(parentId, *childIds)
    }

    fun repeatViews(
        range: IntRange,
        type: String = "view",
        onEach: (Int, Int) -> Unit,
    ): IntArray {
        val ids = ArrayList<Int>()
        for (i in range) {
            val id = view(type = type, tag = "item-$i")
            onEach(i, id)
            ids.add(id)
        }
        val out = IntArray(ids.size)
        for (i in ids.indices) {
            out[i] = ids[i]
        }
        return out
    }

    internal fun finish(): ManaBatch =
        ManaBatch(
            rootId = rootId,
            ops = ops,
            nodes = createdNodes.toList(),
            allocatedIds = tracker.toIntArray(),
        )
}

fun monotonicAllocator(startId: Int = UiOps.SYNTHETIC_ID_BASE): ManaUi.IdAllocator {
    var next = startId
    return ManaUi.IdAllocator { next++ }
}

fun manaBatch(
    theme: ManaUi.Theme = ManaUi.Theme.defaults(),
    allocator: ManaUi.IdAllocator = monotonicAllocator(),
    block: ManaOpsScope.() -> Unit,
): ManaBatch {
    val scope = ManaOpsScope(theme, allocator)
    scope.block()
    return scope.finish()
}

object ManaDslRuntime {
    @JvmStatic
    fun verifyStartup(): String {
        val theme = ManaUi.Theme.defaults()
        val seenTags = ArrayList<String>()
        val batch =
            manaBatch(theme = theme) {
                val root =
                    view(type = "view", tag = "dsl-root") {
                        prop(1, theme.colorOf(ManaTone.ACCENT))
                        prop(2, ManaUi.SWITCH_SPRING)
                    }
                val items =
                    repeatViews(0..1, type = "text") { idx, id ->
                        seenTags.add("$idx:${ManaViewId(id).isSynthetic}")
                    }
                children(root, *items)
            }
        val wire = batch.toBytes()
        val parsed = UiOps.parse(wire)
        val parsedOps = parsed?.size() ?: -1
        val summary =
            "Mana Kotlin DSL ready (ops=$parsedOps, ids=${batch.allocatedIds.size}, " +
                "wire=${wire.size}B, checks=${seenTags.joinToString(",")})"
        Log.i(Loader.TAG, summary)
        return summary
    }
}
