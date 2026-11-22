package com.discord.loader.plugins.messagelogger

import android.content.Context
import android.content.SharedPreferences
import java.util.LinkedHashSet

enum class DeleteStyle(val label: String) {
    INLINE_TAG("Inline [Deleted] Tag"),
    STRIKETHROUGH("Strikethrough"),
    RED_TEXT("Red Text"),
}

class MessageLogConfig {
    @Volatile var logDeletes: Boolean = true
    @Volatile var logEdits: Boolean = true
    @Volatile var inlineEdits: Boolean = true
    @Volatile var logDeletedAttachments: Boolean = true
    @Volatile var deleteStyle: DeleteStyle = DeleteStyle.RED_TEXT
    @Volatile var ignoreBots: Boolean = true
    @Volatile var ignoreSelf: Boolean = false
    @Volatile var ignoreUsersRaw: String = ""
    @Volatile var ignoreChannelsRaw: String = ""
    @Volatile var ignoreGuildsRaw: String = ""
    @Volatile var maxCachedMessages: Int = DEFAULT_MAX_CACHED

    @Volatile var mergeIntoHistory: Boolean = true

    fun ignoreUsers(): Set<String> = parseIds(ignoreUsersRaw)
    fun ignoreChannels(): Set<String> = parseIds(ignoreChannelsRaw)
    fun ignoreGuilds(): Set<String> = parseIds(ignoreGuildsRaw)

    fun load(context: Context?) {
        val sp = prefs(context) ?: return
        try {
            logDeletes = sp.getBoolean(KEY_LOG_DELETES, logDeletes)
            logEdits = sp.getBoolean(KEY_LOG_EDITS, logEdits)
            inlineEdits = sp.getBoolean(KEY_INLINE_EDITS, inlineEdits)
            logDeletedAttachments = sp.getBoolean(KEY_LOG_DELETED_ATTACHMENTS, logDeletedAttachments)
            deleteStyle = runCatching {
                DeleteStyle.valueOf(sp.getString(KEY_DELETE_STYLE, deleteStyle.name) ?: deleteStyle.name)
            }.getOrDefault(deleteStyle)
            ignoreBots = sp.getBoolean(KEY_IGNORE_BOTS, ignoreBots)
            ignoreSelf = sp.getBoolean(KEY_IGNORE_SELF, ignoreSelf)
            ignoreUsersRaw = sp.getString(KEY_IGNORE_USERS, ignoreUsersRaw) ?: ignoreUsersRaw
            ignoreChannelsRaw = sp.getString(KEY_IGNORE_CHANNELS, ignoreChannelsRaw) ?: ignoreChannelsRaw
            ignoreGuildsRaw = sp.getString(KEY_IGNORE_GUILDS, ignoreGuildsRaw) ?: ignoreGuildsRaw
            maxCachedMessages = sp.getInt(KEY_MAX_CACHED, maxCachedMessages).coerceIn(MIN_MAX_CACHED, MAX_MAX_CACHED)
            mergeIntoHistory = sp.getBoolean(KEY_MERGE_HISTORY, mergeIntoHistory)
        } catch (_: Throwable) {

        }
    }

    fun save(context: Context?) {
        val sp = prefs(context) ?: return
        try {
            sp.edit()
                .putBoolean(KEY_LOG_DELETES, logDeletes)
                .putBoolean(KEY_LOG_EDITS, logEdits)
                .putBoolean(KEY_INLINE_EDITS, inlineEdits)
                .putBoolean(KEY_LOG_DELETED_ATTACHMENTS, logDeletedAttachments)
                .putString(KEY_DELETE_STYLE, deleteStyle.name)
                .putBoolean(KEY_IGNORE_BOTS, ignoreBots)
                .putBoolean(KEY_IGNORE_SELF, ignoreSelf)
                .putString(KEY_IGNORE_USERS, ignoreUsersRaw)
                .putString(KEY_IGNORE_CHANNELS, ignoreChannelsRaw)
                .putString(KEY_IGNORE_GUILDS, ignoreGuildsRaw)
                .putInt(KEY_MAX_CACHED, maxCachedMessages)
                .putBoolean(KEY_MERGE_HISTORY, mergeIntoHistory)
                .apply()
        } catch (_: Throwable) {
        }
    }

    companion object {
        const val PREF_FILE = "cinder.message_logger"
        const val DEFAULT_MAX_CACHED = 1000
        const val MIN_MAX_CACHED = 200
        const val MAX_MAX_CACHED = 2000

        private const val KEY_LOG_DELETES = "logDeletes"
        private const val KEY_LOG_EDITS = "logEdits"
        private const val KEY_INLINE_EDITS = "inlineEdits"
        private const val KEY_LOG_DELETED_ATTACHMENTS = "logDeletedAttachments"
        private const val KEY_DELETE_STYLE = "deleteStyle"
        private const val KEY_IGNORE_BOTS = "ignoreBots"
        private const val KEY_IGNORE_SELF = "ignoreSelf"
        private const val KEY_IGNORE_USERS = "ignoreUsers"
        private const val KEY_IGNORE_CHANNELS = "ignoreChannels"
        private const val KEY_IGNORE_GUILDS = "ignoreGuilds"
        private const val KEY_MAX_CACHED = "maxCachedMessages"
        private const val KEY_MERGE_HISTORY = "mergeIntoHistory"

        fun parseIds(raw: String?): Set<String> {
            if (raw.isNullOrBlank()) return emptySet()
            val out = LinkedHashSet<String>()
            for (part in raw.split(',', '\n', '\r', ' ', ';')) {
                val id = part.trim()
                if (id.isNotEmpty()) out.add(id)
            }
            return out
        }

        private fun prefs(context: Context?): SharedPreferences? {
            if (context == null) return null
            return try {
                context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
