package com.codexrm.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import org.json.JSONObject

internal class TaskMonitor : Service() {
    private var client: CodexClient? = null
    private val notified = LinkedHashSet<String>()
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(DEVICE).orEmpty()
        val device = DeviceStore(this).load().firstOrNull { it.agentId == id && enabled(this, id) }
        if (device == null) { stopSelf(); return START_NOT_STICKY }
        if (client != null) return START_REDELIVER_INTENT
        runningMonitor = this
        notifications.createNotificationChannel(NotificationChannel(STATUS, "后台任务监听", NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel(EVENTS, "任务提醒", NotificationManager.IMPORTANCE_DEFAULT))
        startForeground(1, Notification.Builder(this, STATUS)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("正在监听 Codex 任务")
            .setContentText(device.serverName)
            .setContentIntent(open(device, ""))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true).build())
        client = CodexClient(this, device, object : CodexClient.Listener {
            override fun onState(state: ConnectionState, agent: JSONObject?) {
                if (state == ConnectionState.ONLINE) client?.request("codexrm/info", JSONObject()) { _, _ -> }
            }
            override fun onFailure(message: String) = Unit
            override fun onAuthorizationExpired() { stopSelf() }
            override fun onDirectFallback() = Unit
            override fun onRpcEvent(message: JSONObject) {
                val params = message.objectAt("params")
                val thread = params.string("threadId")
                if (thread.isEmpty()) return
                val method = message.string("method")
                val id = message.string("id")
                if (method == "serverRequest/resolved") {
                    val resolved = params.string("requestId").ifBlank { params.string("id") }
                    if (resolved.isNotEmpty()) notifications.cancel("request:$resolved", 2)
                    return
                }
                val turn = params.objectAt("turn")
                val event = when {
                    id.isNotEmpty() && method == "item/tool/requestUserInput" -> "request:$id" to "Codex 需要回答"
                    id.isNotEmpty() && (method.endsWith("/requestApproval") ||
                        method == "execCommandApproval" || method == "applyPatchApproval") -> "request:$id" to "Codex 需要审批"
                    method == "turn/completed" && turn.string("status") == "completed" ->
                        "done:$thread:${turn.string("id")}" to "Codex 任务已完成"
                    else -> return
                }
                if (!notified.add(event.first)) return
                if (notified.size > 256) notified.remove(notified.first())
                notifications.notify(event.first, 2, Notification.Builder(this@TaskMonitor, EVENTS)
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle(event.second)
                    .setContentText(device.serverName)
                    .setContentIntent(open(device, thread))
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setAutoCancel(true).build())
            }
        }, relayOnly = true).also { it.start() }
        return START_REDELIVER_INTENT
    }

    private fun open(device: Device, thread: String): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).setAction(OPEN)
            .setData(android.net.Uri.Builder().scheme("codexrm-task").authority("open")
                .appendPath(device.agentId).appendPath(thread).build())
            .putExtra(DEVICE, device.agentId).putExtra(THREAD, thread),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        closeClient()
        super.onDestroy()
    }

    private fun closeClient() {
        if (runningMonitor === this) runningMonitor = null
        client?.stop()
        client = null
    }

    companion object {
        const val OPEN = "com.codexrm.mobile.OPEN_TASK"
        const val DEVICE = "device"
        const val THREAD = "thread"
        private const val STATUS = "task-monitor"
        private const val EVENTS = "task-events"
        private var runningMonitor: TaskMonitor? = null

        fun stop(context: Context) {
            runningMonitor?.closeClient()
            context.stopService(Intent(context, TaskMonitor::class.java))
        }
        fun enabled(context: Context, agent: String) =
            context.getSharedPreferences(STATUS, Context.MODE_PRIVATE).getBoolean(agent, false)
        fun enable(context: Context, agent: String, enabled: Boolean) {
            context.getSharedPreferences(STATUS, Context.MODE_PRIVATE).edit().putBoolean(agent, enabled).apply()
        }
    }
}
