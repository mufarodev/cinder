package com.discord.loader.mana

import android.os.Handler
import android.os.Looper
import com.discord.loader.ManaUi
import com.discord.loader.UiOps

enum class ManaToastTone {
    DEFAULT,
    BRAND,
    POSITIVE,
    DANGER,
    ;

    internal fun bgToken(): ColorToken =
        when (this) {
            DEFAULT -> ColorToken.BG_BASE_TERTIARY
            BRAND -> ColorToken.BRAND_500
            POSITIVE -> ColorToken.POSITIVE
            DANGER -> ColorToken.DANGER
        }

    internal fun fgToken(): ColorToken =
        when (this) {
            DEFAULT -> ColorToken.TEXT_PRIMARY
            BRAND, POSITIVE, DANGER -> ColorToken.BUTTON_PRIMARY_TEXT
        }
}

data class ManaToast(
    val message: String,
    val icon: String? = null,
    val tone: ManaToastTone = ManaToastTone.DEFAULT,
    val durationMs: Long = DEFAULT_DURATION_MS,
) {
    companion object {
        const val DEFAULT_DURATION_MS: Long = 2600L
    }
}

fun interface ToastDelayScheduler {
    fun schedule(
        delayMs: Long,
        task: Runnable,
    ): () -> Unit
}

object ManaToastScheduler {
    private val ANDROID_SCHEDULER =
        ToastDelayScheduler { delayMs, task ->
            val handler = getMainHandler()
            if (handler == null) {

                ({})
            } else {
                handler.postDelayed(task, delayMs)
                (
                    {
                        try {
                            handler.removeCallbacks(task)
                        } catch (_: Throwable) {
                        }
                    }
                )
            }
        }

    @Volatile
    private var mainHandler: Handler? = null

    @Volatile
    var scheduler: ToastDelayScheduler = ANDROID_SCHEDULER

    private fun getMainHandler(): Handler? {
        val existing = mainHandler
        if (existing != null) return existing
        return try {
            val looper = Looper.getMainLooper() ?: return null
            val created = Handler(looper)
            mainHandler = created
            created
        } catch (_: Throwable) {
            null
        }
    }

    fun schedule(
        delayMs: Long,
        task: Runnable,
    ): () -> Unit = scheduler.schedule(delayMs, task)

    @JvmStatic
    fun resetForTests() {
        scheduler = ANDROID_SCHEDULER
    }
}

internal object ManaToastRenderer {
    const val REF_PILL_ID = "toast:pill"
    const val REF_TEXT_ID = "toast:text"
    const val REF_MOTION_SLOT = "toast:motionSlot"

    fun buildInto(
        toast: ManaToast,
        context: ManaContext,
        batch: UiOps,
        refs: MutableMap<String, Any>,
        onRequestDismiss: () -> Unit,
    ): Int {
        val scope = context.scope
        val theme = context.theme
        val palette = context.palette

        val overlayRootId = scope.nextId()
        val pillId = scope.nextId()
        val textId = scope.nextId()

        val motionSlot = if (ManaMotion.isWorkletSupported()) scope.allocMotionSlot() else -1
        if (motionSlot >= 0) {
            refs[REF_MOTION_SLOT] = motionSlot
        }
        refs[REF_PILL_ID] = pillId
        refs[REF_TEXT_ID] = textId

        val bgTok = toast.tone.bgToken()
        val fgTok = toast.tone.fgToken()
        val bottomPad = (theme.bottomInset + 20.0).toFloat()

        batch.createView(
            overlayRootId,
            "view",
            ManaUi.props(
                ManaUi.prop("direction", UiOps.Value.ofString("row")),
                ManaUi.prop("absolute", UiOps.Value.ofBool(true)),
                ManaUi.prop("paddingBox", UiOps.Value.ofBool(true)),
                ManaUi.prop("gravity", UiOps.Value.ofString("bottom|center")),
                ManaUi.prop("width", UiOps.Value.ofString("fill")),
                ManaUi.prop("padding", UiOps.Value.ofFloat4(16f, 0f, 16f, bottomPad)),
                ManaUi.prop("justify", UiOps.Value.ofString("center")),
                ManaUi.prop("align", UiOps.Value.ofString("center")),
            ),
        )

        val pillProps =
            ManaUi.props(
                ManaUi.prop("direction", UiOps.Value.ofString("row")),
                ManaUi.prop("padding", UiOps.Value.ofFloat4(16f, 10f, 16f, 10f)),
                ManaUi.prop("gap", UiOps.Value.ofInt(8)),
                ManaUi.prop("background", ManaUi.colorVal(palette[bgTok])),
                ManaUi.prop("borderColor", ManaUi.colorVal(palette[ColorToken.DIVIDER])),
                ManaUi.prop("borderWidth", UiOps.Value.ofInt(1)),
                ManaUi.prop("radius", UiOps.Value.ofInt(9999)),
                ManaUi.prop("align", UiOps.Value.ofString("center")),
                ManaUi.prop("justify", UiOps.Value.ofString("center")),
                ManaUi.prop("role", UiOps.Value.ofString("button")),
                ManaUi.prop("accessibilityLabel", UiOps.Value.ofString(toast.message)),
                ManaUi.prop("on", ManaUi.stringArray("press")),
            )
        if (motionSlot >= 0) {
            val pillWorklet =
                ManaMotion.sampledWorklet(
                    slotId = motionSlot,
                    stops = floatArrayOf(0f, 1f),
                    ManaMotion.SampledTrack.floats(ManaMotion.PROP_OPACITY, 0f, 1f),
                    ManaMotion.SampledTrack.floats(ManaMotion.PROP_TRANSLATE_Y, 16f, 0f),
                )
            ManaUi.addProp(pillProps, "worklet", ManaMotion.workletValue(pillWorklet))
        }
        batch.createView(pillId, "view", pillProps)
        scope.bindTheme(pillId, "background", bgTok)
        scope.bindTheme(pillId, "borderColor", ColorToken.DIVIDER)
        scope.onPress(pillId) { onRequestDismiss() }

        val pillKids = ArrayList<Int>(2)
        if (!toast.icon.isNullOrEmpty()) {
            val iconId = ManaUi.icon(batch, scope.allocator, toast.icon, 18, fgTok, palette)
            pillKids.add(iconId)
        }

        batch.createView(
            textId,
            "text",
            ManaUi.props(
                ManaUi.prop("text", UiOps.Value.ofString(toast.message)),
                ManaUi.prop("color", ManaUi.colorVal(palette[fgTok])),
                ManaUi.prop("fontSize", UiOps.Value.ofInt(14)),
                ManaUi.prop("fontWeight", UiOps.Value.ofInt(600)),
                ManaUi.prop("lineHeight", UiOps.Value.ofInt(18)),
                ManaUi.prop("lineHeightExact", UiOps.Value.ofBool(true)),
                ManaUi.prop("maxLines", UiOps.Value.ofInt(2)),
            ),
        )
        scope.bindTheme(textId, "color", fgTok)
        pillKids.add(textId)

        batch.setChildren(pillId, *pillKids.toIntArray())
        batch.setChildren(overlayRootId, pillId)

        if (motionSlot >= 0) {
            ManaMotion
                .batch()
                .initValue(motionSlot, 0f)
                .animateSpring(motionSlot, 1f, ManaMotion.SpringConfig.MANA_SWITCH)
                .appendTo(batch)
        }

        return overlayRootId
    }
}
