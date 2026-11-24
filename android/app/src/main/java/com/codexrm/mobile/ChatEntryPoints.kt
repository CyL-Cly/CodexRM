package com.codexrm.mobile

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.widget.RemoteViews
import java.util.UUID

internal object ChatEntryPoints {
    const val NEW_CHAT = "com.codexrm.mobile.action.NEW_CHAT"
    const val SEARCH = "com.codexrm.mobile.action.SEARCH_CHATS"

    fun intent(context: Context, action: String) = Intent(context, MainActivity::class.java).setAction(action)

    fun canPinThread(context: Context): Boolean =
        context.getSystemService(ShortcutManager::class.java)?.isRequestPinShortcutSupported == true

    fun pinThread(context: Context, agentId: String, threadId: String, title: String): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        val id = UUID.nameUUIDFromBytes("$agentId\n$threadId".toByteArray(Charsets.UTF_8))
        val open = intent(context, TaskMonitor.OPEN)
            .putExtra(TaskMonitor.DEVICE, agentId)
            .putExtra(TaskMonitor.THREAD, threadId)
        return runCatching {
            manager.requestPinShortcut(ShortcutInfo.Builder(context, "thread-$id")
                .setShortLabel(title.ifBlank { "打开会话" }.ellipsizeShortcutLabel())
                .setIcon(Icon.createWithResource(context, R.drawable.ic_launcher))
                .setIntent(open)
                .build(), null)
        }.getOrDefault(false)
    }

    private fun String.ellipsizeShortcutLabel(): String {
        if (codePointCount(0, length) <= 24) return this
        return substring(0, offsetByCodePoints(0, 23)) + "…"
    }

    fun registerShortcuts(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        runCatching {
            val entries = listOf(
                Triple("new_chat", NEW_CHAT, R.string.chat_entry_new),
                Triple("search_chats", SEARCH, R.string.chat_entry_search),
            )
            val registered = manager.dynamicShortcuts.map { it.id }.toSet()
            val missing = entries.filterNot { it.first in registered }.take(manager.maxShortcutCountPerActivity)
            if (missing.isEmpty()) return
            manager.addDynamicShortcuts(missing.map { (id, action, label) ->
                ShortcutInfo.Builder(context, id)
                    .setShortLabel(context.getString(label))
                    .setIcon(Icon.createWithResource(context, R.drawable.ic_launcher))
                    .setIntent(intent(context, action))
                    .build()
            })
        }
    }
}

class ChatEntryWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.chat_entry_widget)
        fun bind(view: Int, code: Int, action: String) {
            views.setOnClickPendingIntent(view, PendingIntent.getActivity(context, code,
                ChatEntryPoints.intent(context, action), PendingIntent.FLAG_IMMUTABLE))
        }
        bind(R.id.widget_new_chat, 410, ChatEntryPoints.NEW_CHAT)
        bind(R.id.widget_search_chats, 411, ChatEntryPoints.SEARCH)
        manager.updateAppWidget(ids, views)
    }
}