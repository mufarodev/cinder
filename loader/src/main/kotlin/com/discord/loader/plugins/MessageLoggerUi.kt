package com.discord.loader.plugins

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.discord.loader.CinderSettingsPlugin
import com.discord.loader.Loader
import com.discord.loader.mana.BadgeTone
import com.discord.loader.mana.ButtonStyle
import com.discord.loader.mana.ManaContext
import com.discord.loader.mana.ManaIcons
import com.discord.loader.mana.ManaPage
import com.discord.loader.mana.ManaToastTone
import com.discord.loader.mana.confirmDialog
import com.discord.loader.mana.intState
import com.discord.loader.mana.manaPage
import com.discord.loader.mana.stringState
import com.discord.loader.plugins.messagelogger.DeleteStyle
import com.discord.loader.plugins.messagelogger.LoggedMessage
import com.discord.loader.plugins.messagelogger.MessageLogConfig

object MessageLoggerUi {
    const val REF_ENABLE_TOGGLE = "message-logger.toggle.enabled"
    const val REF_VIEW_LOG = "message-logger.nav.view-log"
    const val REF_CLEAR_ALL = "message-logger.btn.clear-all"
    const val REF_SEARCH = "message-logger.search"
    const val REF_FILTER = "message-logger.filter"

    private const val MAX_VIEWER_ROWS = 100

    fun settingsPage(plugin: MessageLoggerPlugin, ctx: ManaContext): ManaPage = manaPage(
        title = "Message Logger",
        titleAccessibilityId = "cinder.plugin.message-logger.title",
    ) {
        val cfg = plugin.config
        val enabledState = CinderSettingsPlugin.pluginEnabledState(plugin)
        val deletedState = stringState(plugin.store.loggedDeletes.get().toString())
        val editsState = stringState(plugin.store.loggedEdits.get().toString())
        val cachedState = stringState(plugin.store.size().toString())
        val styleState = stringState(cfg.deleteStyle.label)

        fun save() = cfg.save(Loader.getContext())

        section(title = "Status & Quick Access") {
            valueRow("Logged Deletes", deletedState, icon = ManaIcons.TRASH)
            valueRow("Logged Edits", editsState, icon = ManaIcons.CLOCK)
            valueRow("Cached Messages", cachedState, icon = ManaIcons.CHAT)
            navRow(
                title = "View Message Log",
                subtitle = "Search deleted and edited messages",
                icon = ManaIcons.CHAT,
                accessibilityId = REF_VIEW_LOG,
                refKey = REF_VIEW_LOG,
            ) {
                ctx.push(viewerPage(plugin, ctx, "", 0))
            }
        }

        section(title = "Plugin") {
            toggleRow(
                title = "Enable Message Logger",
                subtitle = "Bridge hooks update immediately; no restart required",
                icon = ManaIcons.CLOCK,
                state = enabledState,
                accessibilityId = REF_ENABLE_TOGGLE,
                refKey = REF_ENABLE_TOGGLE,
            ) { next -> Loader.setPluginEnabled(plugin, next) }
        }

        section(title = "Logging") {
            toggleRow(
                title = "Log Deleted Messages",
                subtitle = "Rewrite deletes so original content stays in chat",
                icon = ManaIcons.TRASH,
                checked = cfg.logDeletes,
            ) { next -> cfg.logDeletes = next; save() }

            toggleRow(
                title = "Log Edited Messages",
                subtitle = "Track previous versions of edited messages",
                icon = ManaIcons.CLOCK,
                checked = cfg.logEdits,
            ) { next -> cfg.logEdits = next; save() }

            toggleRow(
                title = "Inline Edit History",
                subtitle = "Show prior edits above the current content",
                icon = ManaIcons.CHAT,
                checked = cfg.inlineEdits,
            ) { next -> cfg.inlineEdits = next; save() }

            toggleRow(
                title = "Log Deleted Attachments",
                subtitle = "Keep removed images and files visible",
                icon = ManaIcons.FILE,
                checked = cfg.logDeletedAttachments,
            ) { next -> cfg.logDeletedAttachments = next; save() }

            toggleRow(
                title = "Keep Across Reloads",
                subtitle = "Re-inject cached deleted messages when switching channels",
                icon = ManaIcons.GLOBE_EARTH,
                checked = cfg.mergeIntoHistory,
            ) { next -> cfg.mergeIntoHistory = next; save() }

            navRow(
                title = "Deleted Message Style",
                subtitle = "How the original content is marked",
                icon = ManaIcons.WARNING,
                trailingState = styleState,
            ) {
                ctx.presentActionSheet(title = "Deleted Message Style") {
                    for (style in DeleteStyle.entries) {
                        option(label = style.label) {
                            cfg.deleteStyle = style
                            styleState.value = style.label
                            save()
                        }
                    }
                    cancelButton()
                }
            }

            sliderRow(
                title = "Cache Size",
                subtitle = "Bounded LRU cache size (mobile safety cap)",
                range = MessageLogConfig.MIN_MAX_CACHED..MessageLogConfig.MAX_MAX_CACHED,
                step = 100,
                state = intState(cfg.maxCachedMessages),
                valueFormatter = { "$it messages" },
            ) { value ->
                cfg.maxCachedMessages = value
                plugin.store.setMaxCached(value)
                cachedState.value = plugin.store.size().toString()
                save()
            }
        }

        section(title = "Filters") {
            toggleRow(
                title = "Ignore Bots",
                subtitle = "Do not log bot messages",
                icon = ManaIcons.OBJECT,
                checked = cfg.ignoreBots,
            ) { next -> cfg.ignoreBots = next; save() }

            toggleRow(
                title = "Ignore Self",
                subtitle = "Do not log your own messages",
                icon = ManaIcons.USER_CIRCLE,
                checked = cfg.ignoreSelf,
            ) { next -> cfg.ignoreSelf = next; save() }

            textInput(
                label = "Ignore Users (IDs)",
                placeholder = "Snowflake IDs, comma or newline separated",
                state = stringState(cfg.ignoreUsersRaw),
                multiline = true,
            ) { text -> cfg.ignoreUsersRaw = text; save() }

            textInput(
                label = "Ignore Channels (IDs)",
                placeholder = "Channel or parent IDs",
                state = stringState(cfg.ignoreChannelsRaw),
                multiline = true,
            ) { text -> cfg.ignoreChannelsRaw = text; save() }

            textInput(
                label = "Ignore Servers (IDs)",
                placeholder = "Guild IDs",
                state = stringState(cfg.ignoreGuildsRaw),
                multiline = true,
            ) { text -> cfg.ignoreGuildsRaw = text; save() }
        }

        section(title = "Danger Zone") {
            button(
                label = "Clear All Logged History",
                style = ButtonStyle.DANGER,
                icon = ManaIcons.TRASH,
                accessibilityId = REF_CLEAR_ALL,
                refKey = REF_CLEAR_ALL,
            ) {
                ctx.showDialog(
                    confirmDialog(
                        title = "Clear Message History?",
                        message = "This removes all locally cached deleted and edited messages.",
                        confirmLabel = "Clear History",
                        destructive = true,
                    ) {
                        plugin.store.clear()
                        deletedState.value = "0"
                        editsState.value = "0"
                        cachedState.value = "0"
                        ctx.showToast(
                            message = "Message history cleared",
                            icon = ManaIcons.CIRCLE_CHECK,
                            tone = ManaToastTone.POSITIVE,
                        )
                    },
                )
            }
        }
    }

    fun viewerPage(
        plugin: MessageLoggerPlugin,
        ctx: ManaContext,
        query: String,
        filterIndex: Int,
    ): ManaPage = manaPage(
        title = "Message Log",
        titleAccessibilityId = "cinder.plugin.message-logger.log-title",
    ) {
        val searchState = stringState(query)
        val filterState = intState(filterIndex)
        val matches = filtered(plugin, query, filterIndex)

        fun rebuild(newQuery: String, newFilter: Int) {
            ctx.pop()
            ctx.push(viewerPage(plugin, ctx, newQuery, newFilter))
        }

        section(title = "Filter") {
            searchInput(
                placeholder = "Search author, content or channel ID",
                state = searchState,
                accessibilityId = REF_SEARCH,
                refKey = REF_SEARCH,
                onSubmit = { text -> rebuild(text, filterState.value) },
            )
            segmentedControl(
                labels = listOf("Logged", "Deleted", "Edited"),
                selectedIndex = filterState,
                refKey = REF_FILTER,
            ) { index -> rebuild(searchState.value, index) }
            valueRow("Matching Entries", matches.size.toString(), icon = ManaIcons.CHAT)
        }

        if (matches.isNotEmpty()) {
            section(title = "Entries") {
                for (message in matches.take(MAX_VIEWER_ROWS)) {
                    val badge = when {
                        message.deleted -> "Deleted"
                        message.editHistory.isNotEmpty() -> "Edited x${message.editHistory.size}"
                        else -> "Logged"
                    }
                    navRow(
                        title = message.authorName ?: message.authorId,
                        subtitle = message.preview,
                        icon = if (message.deleted) ManaIcons.TRASH else ManaIcons.CLOCK,
                        badge = badge,
                        badgeTone = if (message.deleted) BadgeTone.DANGER else BadgeTone.BRAND,
                        refKey = "log-entry:${message.id}",
                    ) {
                        ctx.push(detailPage(plugin, ctx, message.channelId, message.id))
                    }
                }
            }
            if (matches.size > MAX_VIEWER_ROWS) {
                section(title = "Note") {
                    badge("Showing first $MAX_VIEWER_ROWS of ${matches.size} entries")
                }
            }
        } else {
            section(title = "Entries") {
                badge("No matching messages", tone = BadgeTone.SUBTLE)
            }
        }
    }

    fun detailPage(
        plugin: MessageLoggerPlugin,
        ctx: ManaContext,
        channelId: String,
        id: String,
    ): ManaPage = manaPage(title = "Message Details") {
        val message = plugin.store.get(channelId, id)
        if (message == null) {
            section(title = "Entry") {
                badge("This entry is no longer cached", tone = BadgeTone.DANGER)
            }
            return@manaPage
        }

        section(title = "Message") {
            valueRow("Author", message.authorName ?: message.authorId, icon = ManaIcons.USER_CIRCLE)
            valueRow("Author ID", message.authorId, icon = ManaIcons.USER_CIRCLE)
            valueRow("Channel ID", message.channelId, icon = ManaIcons.CHAT)
            valueRow("Guild ID", message.guildId ?: "—", icon = ManaIcons.GLOBE_EARTH)
            valueRow("Message ID", message.id, icon = ManaIcons.LINK)
            valueRow("Sent", message.timestamp ?: "—", icon = ManaIcons.CLOCK)
            valueRow("Edited", message.editedTimestamp ?: "—", icon = ManaIcons.CLOCK)
            valueRow(
                "Status",
                if (message.deleted) "Deleted" else if (message.editHistory.isNotEmpty()) "Edited" else "Logged",
                icon = ManaIcons.CIRCLE_CHECK,
            )
        }

        section(title = if (message.deleted) "Original Content" else "Latest Content") {
            badge(message.content ?: "(no content)")
        }

        if (message.editHistory.isNotEmpty()) {
            section(title = "Edit History (oldest → newest)") {
                for (entry in message.editHistory) {
                    badge("${entry.timestamp ?: "unknown"}: ${entry.content}")
                }
            }
        }

        if (message.attachments.isNotEmpty()) {
            section(title = "Attachments") {
                for (attachment in message.attachments) {
                    valueRow(
                        attachment.filename ?: attachment.id,
                        if (attachment.deleted) "Removed" else "Present",
                        subtitle = attachment.url,
                        icon = ManaIcons.FILE,
                    )
                }
            }
        }

        section(title = "Actions") {
            button(label = "Copy Original Content", icon = ManaIcons.COPY, style = ButtonStyle.SECONDARY) {
                copyToClipboard(message.content ?: "")
                ctx.showToast("Content copied", icon = ManaIcons.CIRCLE_CHECK, tone = ManaToastTone.POSITIVE)
            }
            button(label = "Clear Entry", icon = ManaIcons.TRASH, style = ButtonStyle.DANGER) {
                plugin.store.remove(channelId, id)
                ctx.pop()
                ctx.showToast("Entry cleared", icon = ManaIcons.CIRCLE_CHECK, tone = ManaToastTone.POSITIVE)
            }
        }
    }

    private fun filtered(plugin: MessageLoggerPlugin, query: String, filterIndex: Int): List<LoggedMessage> {
        val needle = query.trim().lowercase()
        return plugin.store.snapshot().filter { msg ->
            val matchesFilter = when (filterIndex) {
                1 -> msg.deleted
                2 -> msg.editHistory.isNotEmpty()
                else -> msg.deleted || msg.editHistory.isNotEmpty() || msg.attachments.any { it.deleted }
            }
            val matchesQuery = needle.isEmpty() ||
                (msg.authorName ?: "").lowercase().contains(needle) ||
                (msg.content ?: "").lowercase().contains(needle) ||
                msg.channelId.contains(needle) ||
                msg.id.contains(needle)
            matchesFilter && matchesQuery
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val ctx = Loader.getContext() ?: return
            val manager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            manager.setPrimaryClip(ClipData.newPlainText("Cinder MessageLogger", text))
        } catch (_: Throwable) {

        }
    }
}
