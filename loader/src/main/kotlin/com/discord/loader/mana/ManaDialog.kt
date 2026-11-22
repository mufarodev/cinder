package com.discord.loader.mana

import com.discord.loader.ManaUi
import com.discord.loader.UiOps

internal class DialogActionSpec(
    val label: String,
    val style: ButtonStyle,
    val icon: String?,
    val dismissOnPress: Boolean,
    val accessibilityId: String?,
    val refKey: String?,
    val onPress: () -> Unit,
)

class ManaDialog(
    val title: String,
    val message: String? = null,
    val dismissible: Boolean = true,
    val scopeId: String? = null,
    val onDismiss: (() -> Unit)? = null,
    internal val content: ManaDialogBuilder.() -> Unit,
)

fun manaDialog(
    title: String,
    message: String? = null,
    dismissible: Boolean = true,
    scopeId: String? = null,
    onDismiss: (() -> Unit)? = null,
    content: ManaDialogBuilder.() -> Unit,
): ManaDialog =
    ManaDialog(
        title = title,
        message = message,
        dismissible = dismissible,
        scopeId = scopeId,
        onDismiss = onDismiss,
        content = content,
    )

fun confirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Confirm",
    cancelLabel: String = "Cancel",
    destructive: Boolean = false,
    dismissible: Boolean = true,
    onCancel: (() -> Unit)? = null,
    onConfirm: () -> Unit,
): ManaDialog =
    manaDialog(
        title = title,
        message = message,
        dismissible = dismissible,
        onDismiss = onCancel,
    ) {
        if (destructive) {
            dangerAction(confirmLabel, refKey = "dialog:confirm", onPress = onConfirm)
        } else {
            primaryAction(confirmLabel, refKey = "dialog:confirm", onPress = onConfirm)
        }
        secondaryAction(cancelLabel, refKey = "dialog:cancel") {
            onCancel?.invoke()
        }
    }

@ManaDsl
class ManaDialogBuilder internal constructor(
    val context: ManaContext,
    val scope: ManaScope,
) {
    val router: ManaRouter get() = context.router
    internal val actions = ArrayList<DialogActionSpec>()

    fun primaryAction(
        label: String,
        icon: String? = null,
        dismissOnPress: Boolean = true,
        accessibilityId: String? = null,
        refKey: String? = null,
        onPress: () -> Unit,
    ) {
        action(label, ButtonStyle.PRIMARY, icon, dismissOnPress, accessibilityId, refKey, onPress)
    }

    fun secondaryAction(
        label: String,
        icon: String? = null,
        dismissOnPress: Boolean = true,
        accessibilityId: String? = null,
        refKey: String? = null,
        onPress: () -> Unit,
    ) {
        action(label, ButtonStyle.SECONDARY, icon, dismissOnPress, accessibilityId, refKey, onPress)
    }

    fun dangerAction(
        label: String,
        icon: String? = null,
        dismissOnPress: Boolean = true,
        accessibilityId: String? = null,
        refKey: String? = null,
        onPress: () -> Unit,
    ) {
        action(label, ButtonStyle.DANGER, icon, dismissOnPress, accessibilityId, refKey, onPress)
    }

    fun action(
        label: String,
        style: ButtonStyle = ButtonStyle.SECONDARY,
        icon: String? = null,
        dismissOnPress: Boolean = true,
        accessibilityId: String? = null,
        refKey: String? = null,
        onPress: () -> Unit,
    ) {
        actions.add(
            DialogActionSpec(
                label = label,
                style = style,
                icon = icon,
                dismissOnPress = dismissOnPress,
                accessibilityId = accessibilityId,
                refKey = refKey,
                onPress = onPress,
            ),
        )
    }
}

internal object ManaDialogRenderer {
    const val REF_MODAL_ID = "dialog:modal"
    const val REF_CARD_ID = "dialog:card"

    fun buildInto(
        dialog: ManaDialog,
        context: ManaContext,
        batch: UiOps,
        refs: MutableMap<String, Any>,
        onRequestDismiss: () -> Unit,
    ): Int {
        val scope = context.scope
        val theme = context.theme
        val palette = context.palette

        val builder = ManaDialogBuilder(context, scope)
        dialog.content(builder)

        val modalId = scope.nextId()
        val cardId = scope.nextId()
        val titleId = scope.nextId()

        refs[REF_MODAL_ID] = modalId
        refs[REF_CARD_ID] = cardId

        batch.createView(
            modalId,
            "modal",
            ManaUi.props(
                ManaUi.prop("fullscreen", UiOps.Value.ofBool(false)),
                ManaUi.prop("dismissible", UiOps.Value.ofBool(dialog.dismissible)),
                ManaUi.prop("on", ManaUi.stringArray("dismiss", "exitEnd")),
            ),
        )
        scope.onDismiss(modalId) {
            if (dialog.dismissible) {
                onRequestDismiss()
            }
        }
        scope.onExitEnd(modalId) {
            onRequestDismiss()
        }

        batch.createView(
            cardId,
            "view",
            ManaUi.props(
                ManaUi.prop("direction", UiOps.Value.ofString("column")),
                ManaUi.prop("width", UiOps.Value.ofString("fill")),
                ManaUi.prop("padding", UiOps.Value.ofFloat4(20f, 20f, 20f, 20f)),
                ManaUi.prop("gap", UiOps.Value.ofInt(16)),
                ManaUi.prop("background", ManaUi.colorVal(palette[ColorToken.BG_BASE_PRIMARY])),
                ManaUi.prop("borderColor", ManaUi.colorVal(palette[ColorToken.DIVIDER])),
                ManaUi.prop("borderWidth", UiOps.Value.ofInt(1)),
                ManaUi.prop("radius", UiOps.Value.ofInt(16)),
                ManaUi.prop("clip", UiOps.Value.ofBool(true)),
                ManaUi.prop("align", UiOps.Value.ofString("stretch")),
            ),
        )
        scope.bindTheme(cardId, "background", ColorToken.BG_BASE_PRIMARY)
        scope.bindTheme(cardId, "borderColor", ColorToken.DIVIDER)

        val cardKids = ArrayList<Int>(3)
        batch.createView(
            titleId,
            "text",
            ManaUi.props(
                ManaUi.prop("text", UiOps.Value.ofString(dialog.title)),
                ManaUi.prop("color", ManaUi.colorVal(palette[ColorToken.TEXT_PRIMARY])),
                ManaUi.prop("fontSize", UiOps.Value.ofInt(18)),
                ManaUi.prop("fontWeight", UiOps.Value.ofInt(600)),
                ManaUi.prop("lineHeight", UiOps.Value.ofInt(22)),
                ManaUi.prop("lineHeightExact", UiOps.Value.ofBool(true)),
                ManaUi.prop("textAlign", UiOps.Value.ofString("center")),
                ManaUi.prop("role", UiOps.Value.ofString("header")),
            ),
        )
        scope.bindTheme(titleId, "color", ColorToken.TEXT_PRIMARY)
        cardKids.add(titleId)

        if (!dialog.message.isNullOrEmpty()) {
            val msgId = scope.nextId()
            batch.createView(
                msgId,
                "text",
                ManaUi.props(
                    ManaUi.prop("text", UiOps.Value.ofString(dialog.message)),
                    ManaUi.prop("color", ManaUi.colorVal(palette[ColorToken.TEXT_SECONDARY])),
                    ManaUi.prop("fontSize", UiOps.Value.ofInt(14)),
                    ManaUi.prop("fontWeight", UiOps.Value.ofInt(400)),
                    ManaUi.prop("lineHeight", UiOps.Value.ofInt(20)),
                    ManaUi.prop("lineHeightExact", UiOps.Value.ofBool(true)),
                    ManaUi.prop("textAlign", UiOps.Value.ofString("center")),
                ),
            )
            scope.bindTheme(msgId, "color", ColorToken.TEXT_SECONDARY)
            cardKids.add(msgId)
        }

        if (builder.actions.isNotEmpty()) {
            val actionsColId = scope.nextId()
            batch.createView(
                actionsColId,
                "view",
                ManaUi.props(
                    ManaUi.prop("direction", UiOps.Value.ofString("column")),
                    ManaUi.prop("width", UiOps.Value.ofString("fill")),
                    ManaUi.prop("gap", UiOps.Value.ofInt(10)),
                    ManaUi.prop("align", UiOps.Value.ofString("stretch")),
                ),
            )
            val btnIds = IntArray(builder.actions.size)
            for (i in builder.actions.indices) {
                val spec = builder.actions[i]
                val btnId =
                    ManaUi.button(
                        batch,
                        scope.allocator,
                        theme,
                        spec.label,
                        spec.icon,
                        spec.style.toManaVariant(),
                        spec.accessibilityId,
                    )
                btnIds[i] = btnId
                if (spec.refKey != null) {
                    refs[spec.refKey] = btnId
                }
                scope.onPress(btnId) {
                    if (spec.dismissOnPress) {
                        context.router.dismissDialog(invokeOnDismiss = false)
                    }
                    spec.onPress()
                }
            }
            batch.setChildren(actionsColId, *btnIds)
            cardKids.add(actionsColId)
        }

        batch.setChildren(cardId, *cardKids.toIntArray())
        batch.setChildren(modalId, cardId)

        return modalId
    }
}
