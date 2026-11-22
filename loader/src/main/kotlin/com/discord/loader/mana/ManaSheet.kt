package com.discord.loader.mana

import com.discord.loader.ManaUi
import com.discord.loader.UiOps

class ManaSheet(
    val title: String? = null,
    val subtitle: String? = null,
    val dismissible: Boolean = true,
    val closedOffsetDp: Float = DEFAULT_CLOSED_OFFSET_DP,
    val scopeId: String? = null,
    val onDismiss: (() -> Unit)? = null,
    val expanded: Boolean = false,
    internal val content: ManaSheetBuilder.() -> Unit,
) {
    companion object {
        const val DEFAULT_CLOSED_OFFSET_DP: Float = 420f
    }
}

fun manaSheet(
    title: String? = null,
    subtitle: String? = null,
    dismissible: Boolean = true,
    closedOffsetDp: Float = ManaSheet.DEFAULT_CLOSED_OFFSET_DP,
    scopeId: String? = null,
    onDismiss: (() -> Unit)? = null,
    expanded: Boolean = false,
    content: ManaSheetBuilder.() -> Unit,
): ManaSheet =
    ManaSheet(
        title = title,
        subtitle = subtitle,
        dismissible = dismissible,
        closedOffsetDp = closedOffsetDp,
        scopeId = scopeId,
        onDismiss = onDismiss,
        expanded = expanded,
        content = content,
    )

@ManaDsl
class ManaSheetBuilder internal constructor(
    context: ManaContext,
    scope: ManaScope,
    refs: MutableMap<String, Any>,
) : ManaSectionBuilder(context, scope, refs) {
    fun section(
        title: String? = null,
        footer: String? = null,
        isRadioGroup: Boolean = false,
        content: ManaSectionBuilder.() -> Unit,
    ) {
        val nested = ManaSectionBuilder(context, scope, refs)
        nested.content()
        if (nested.entries.isEmpty()) return
        entries.add(
            Entry.Block { batch, theme, pal ->
                nested.emitSectionInto(batch, theme, pal, title, footer, isRadioGroup)
            },
        )
    }

    fun dismiss(): Boolean = router.dismissSheet()
}

internal object ManaSheetRenderer {
    const val REF_SHEET_ID = "sheet:root"
    const val REF_SCROLL_ID = "sheet:scroll"
    const val REF_BODY_ID = "sheet:body"

    fun buildInto(
        sheet: ManaSheet,
        context: ManaContext,
        batch: UiOps,
        refs: MutableMap<String, Any>,
        onRequestDismiss: () -> Unit,
    ): Int {
        val scope = context.scope
        val theme = context.theme
        val palette = context.palette

        val builder = ManaSheetBuilder(context, scope, refs)
        sheet.content(builder)

        val sheetId = scope.nextId()
        val scrollId = scope.nextId()
        val bodyColId = scope.nextId()

        refs[REF_SHEET_ID] = sheetId
        refs[REF_SCROLL_ID] = scrollId
        refs[REF_BODY_ID] = bodyColId

        val sheetProps =
            ManaUi.props(
                ManaUi.prop("background", ManaUi.colorVal(palette[ColorToken.SHEET_BG])),
                ManaUi.prop("radiusTopLeft", UiOps.Value.ofInt(24)),
                ManaUi.prop("radiusTopRight", UiOps.Value.ofInt(24)),
                ManaUi.prop("handleColor", ManaUi.colorVal(palette[ColorToken.SHEET_HANDLE])),
                ManaUi.prop("mana", UiOps.Value.ofBool(true)),
                ManaUi.prop("dismissLabel", UiOps.Value.ofString("Dismiss")),
                ManaUi.prop("dismissible", UiOps.Value.ofBool(sheet.dismissible)),
                ManaUi.prop("on", ManaUi.stringArray("dismiss", "exitEnd")),
            )
        if (sheet.expanded) {
            ManaUi.addProp(sheetProps, "expanded", UiOps.Value.ofBool(true))
        }
        batch.createView(sheetId, "sheet", sheetProps)
        scope.bindTheme(sheetId, "background", ColorToken.SHEET_BG)
        scope.bindTheme(sheetId, "handleColor", ColorToken.SHEET_HANDLE)
        scope.onDismiss(sheetId) {
            if (sheet.dismissible) {
                onRequestDismiss()
            }
        }
        scope.onExitEnd(sheetId) {
            onRequestDismiss()
        }

        batch.createView(
            scrollId,
            "scroll",
            ManaUi.props(
                ManaUi.prop("width", UiOps.Value.ofString("fill")),
                ManaUi.prop("keyboardShouldPersistTaps", UiOps.Value.ofString("never")),
                ManaUi.prop("keyboardDismiss", UiOps.Value.ofString("interactive")),
            ),
        )

        val bottomPad = (theme.bottomInset + 16.0).toFloat()
        batch.createView(
            bodyColId,
            "view",
            ManaUi.props(
                ManaUi.prop("direction", UiOps.Value.ofString("column")),
                ManaUi.prop("padding", UiOps.Value.ofFloat4(16f, 8f, 16f, bottomPad)),
                ManaUi.prop("width", UiOps.Value.ofString("fill")),
                ManaUi.prop("gap", UiOps.Value.ofInt(16)),
                ManaUi.prop("align", UiOps.Value.ofString("stretch")),
            ),
        )

        val bodyKids = ArrayList<Int>(builder.entries.size + 1)
        if (!sheet.title.isNullOrEmpty() || !sheet.subtitle.isNullOrEmpty()) {
            val hdrColId = scope.nextId()
            batch.createView(
                hdrColId,
                "view",
                ManaUi.props(
                    ManaUi.prop("direction", UiOps.Value.ofString("column")),
                    ManaUi.prop("gap", UiOps.Value.ofInt(4)),
                    ManaUi.prop("width", UiOps.Value.ofString("fill")),
                    ManaUi.prop("align", UiOps.Value.ofString("stretch")),
                ),
            )
            val hdrKids = ArrayList<Int>(2)
            if (!sheet.title.isNullOrEmpty()) {
                val titleId = scope.nextId()
                batch.createView(
                    titleId,
                    "text",
                    ManaUi.props(
                        ManaUi.prop("text", UiOps.Value.ofString(sheet.title)),
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
                hdrKids.add(titleId)
            }
            if (!sheet.subtitle.isNullOrEmpty()) {
                val subId = scope.nextId()
                batch.createView(
                    subId,
                    "text",
                    ManaUi.props(
                        ManaUi.prop("text", UiOps.Value.ofString(sheet.subtitle)),
                        ManaUi.prop("color", ManaUi.colorVal(palette[ColorToken.TEXT_MUTED])),
                        ManaUi.prop("fontSize", UiOps.Value.ofInt(14)),
                        ManaUi.prop("fontWeight", UiOps.Value.ofInt(400)),
                        ManaUi.prop("lineHeight", UiOps.Value.ofInt(18)),
                        ManaUi.prop("lineHeightExact", UiOps.Value.ofBool(true)),
                        ManaUi.prop("textAlign", UiOps.Value.ofString("center")),
                    ),
                )
                scope.bindTheme(subId, "color", ColorToken.TEXT_MUTED)
                hdrKids.add(subId)
            }
            batch.setChildren(hdrColId, *hdrKids.toIntArray())
            bodyKids.add(hdrColId)
        }

        val emitted = builder.emitMixedEntriesInto(batch, theme, palette)
        for (id in emitted) {
            bodyKids.add(id)
        }

        batch.setChildren(bodyColId, *bodyKids.toIntArray())
        batch.setChildren(scrollId, bodyColId)
        batch.setChildren(sheetId, scrollId)

        return sheetId
    }
}
