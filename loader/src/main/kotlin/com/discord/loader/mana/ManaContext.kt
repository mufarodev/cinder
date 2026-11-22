package com.discord.loader.mana

import com.discord.loader.ManaUi

class ManaContext(
    val router: ManaRouter,
    val scope: ManaScope,
) {
    val palette: ManaPalette get() = ThemeEngine.currentPalette()

    val theme: ManaUi.Theme get() = ThemeEngine.currentTheme()

    fun color(token: ColorToken): Long = palette[token]

    fun push(page: ManaPage): MountedManaPage = router.push(page)

    fun pop(): Boolean = router.pop()

    fun popToRoot(): Int = router.popToRoot()

    fun presentSheet(sheet: ManaSheet): MountedOverlay = router.presentSheet(sheet)

    fun presentActionSheet(
        title: String? = null,
        subtitle: String? = null,
        dismissible: Boolean = true,
        onDismiss: (() -> Unit)? = null,
        content: ActionSheetBuilder.() -> Unit,
    ): MountedOverlay =
        router.presentSheet(
            actionSheet(
                title = title,
                subtitle = subtitle,
                dismissible = dismissible,
                onDismiss = onDismiss,
                content = content,
            ),
        )

    fun dismissSheet(): Boolean = router.dismissSheet()

    fun showDialog(dialog: ManaDialog): MountedOverlay = router.showDialog(dialog)

    fun dismissDialog(): Boolean = router.dismissDialog()

    fun showToast(
        message: String,
        icon: String? = null,
        tone: ManaToastTone = ManaToastTone.DEFAULT,
        durationMs: Long = ManaToast.DEFAULT_DURATION_MS,
    ): MountedOverlay = router.showToast(ManaToast(message, icon, tone, durationMs))

    fun showToast(toast: ManaToast): MountedOverlay = router.showToast(toast)

    fun dismissToast(): Boolean = router.dismissToast()
}
