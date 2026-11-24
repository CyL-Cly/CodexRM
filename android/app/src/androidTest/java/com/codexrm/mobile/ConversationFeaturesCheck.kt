package com.codexrm.mobile

import android.app.Instrumentation
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.KeyEvent
import android.widget.EditText
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

internal object ConversationFeaturesCheck {
    fun run(test: Instrumentation, args: Bundle): String {
        val context = test.targetContext
        val url = requireNotNull(args.getString("rm"))
        val claim = OkHttpClient().newCall(Request.Builder().url("$url/api/v1/client/claim")
            .post(JSONObject().put("code", requireNotNull(args.getString("pair"))).put("deviceType", "android")
                .toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
            check(it.isSuccessful) { "Could not claim QA pairing: ${it.code}" }
            JSONObject(it.body?.string().orEmpty())
        }
        val agent = claim.getJSONObject("agent")
        val device = Device(claim.getString("clientId"), claim.getString("token"), url, agent.getString("agentId"), "Android QA")
        DeviceStore(context).apply { save(listOf(device)); selectedId = device.agentId; legacySystemWarningShown = true }
        val cacheThread = "offline-qa"
        val cachedItems = JSONArray().put(JSONObject().put("id", "fixture-code").put("type", "agentMessage")
            .put("text", "Needle\n\n```sh\nprintf hello\n```"))
        repeat(32) { index ->
            cachedItems.put(JSONObject().put("id", "fixture-$index").put("type", "agentMessage")
                .put("text", "Cached fixture row $index"))
        }
        ConversationCache(context).write(device.agentId, cacheThread, JSONObject().put("savedAt", System.currentTimeMillis())
            .put("thread", JSONObject().put("id", cacheThread).put("name", "Cache QA").put("turns", JSONArray()
                .put(JSONObject().put("id", "fixture-turn").put("status", "completed").put("items", cachedItems)))).toString())
        await("cache written") { ConversationCache(context).read(device.agentId, cacheThread) != null }
        context.getSharedPreferences("conversation_ui", Context.MODE_PRIVATE).edit().putString("last:${device.agentId}", cacheThread).commit()
        val activity = test.startActivitySync(Intent().setClassName(context.packageName, "com.codexrm.mobile.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun field(name: String) = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }
        fun invoke(name: String, vararg types: Class<*>, values: Array<Any> = emptyArray()): Any? =
            activity.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(activity, *values)
        fun onUi(action: () -> Unit) {
            val error = AtomicReference<Throwable?>()
            test.runOnMainSync { runCatching(action).onFailure(error::set) }
            error.get()?.let { throw it }
        }
        fun atBottom(): Boolean {
            var bottom = false
            onUi { bottom = !(field("messageList").get(activity) as ChatListView).canScrollVertically(1) }
            return bottom
        }
        try {
            if (args.getString("headless") == "true") {
                onUi {
                    check(field("currentThreadId").get(activity) == cacheThread)
                    val adapter = field("adapter").get(activity) as MessageAdapter
                    val list = field("messageList").get(activity) as ChatListView
                    val text = adapter.messageBody(adapter.getView(0, null, list))?.text as Spanned
                    check(text.contains("复制代码"))
                    check(text.getSpans(0, text.length, ClickableSpan::class.java).size == 1)
                }
                val result = AtomicReference<JSONObject?>()
                lateinit var client: CodexClient
                onUi {
                    client = CodexClient(context, device, object : CodexClient.Listener {
                        override fun onFailure(message: String) = Unit
                        override fun onAuthorizationExpired() = Unit
                        override fun onDirectFallback() = Unit
                        override fun onRpcEvent(message: JSONObject) = Unit
                        override fun onState(state: ConnectionState, agent: JSONObject?) {
                            if (state != ConnectionState.ONLINE) return
                            client.request("codexrm/info") { info, _ ->
                                val cwd = info?.string("cwd").orEmpty()
                                if (cwd.isNotBlank()) client.request("codexrm/workspace/diff", JSONObject().put("workspace", cwd)) { value, _ ->
                                    result.set(value)
                                }
                            }
                        }
                    }, relayOnly = true)
                    client.start()
                }
                try {
                    await("workspace diff over relay") { result.get() != null }
                    check(result.get()!!.string("diff").contains("+changed-by-fixture"))
                    check(result.get()!!.arrayAt("untracked").optString(0) == "new.txt")
                } finally {
                    onUi { client.stop() }
                }
                return "coldCache=pass nativeCodeCopySpan=pass workspaceDiffOverRelay=pass"
            }
            await("activity focused") {
                var focused = false
                onUi { focused = activity.hasWindowFocus() }
                focused
            }
            onUi {
                invoke("cancelUpdateOperation")
                check(field("currentThreadId").get(activity) == cacheThread)
                val adapter = field("adapter").get(activity) as MessageAdapter
                val list = field("messageList").get(activity) as ChatListView
                val holder = adapter.getView(0, null, list)
                val view = requireNotNull(adapter.messageBody(holder))
                val text = view.text as Spanned
                check(text.contains("复制代码"))
                text.getSpans(0, text.length, ClickableSpan::class.java).single().onClick(view)
                val copied = activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text
                check(copied?.toString() == "printf hello") { "Code-block clipboard result did not match the source" }
                list.setSelectionFromTop(0, 0)
            }
            await("transcript scrolled above latest") { !atBottom() }
            onUi { invoke("jumpToLatest") }
            await("returned to latest message") { atBottom() }
            onUi {
                invoke("searchConversation")
            }
            test.waitForIdleSync()
            await("search dialog displayed") {
                test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("搜索当前会话")?.isNotEmpty() == true
            }
            fun searchField(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null || node.isEditable) return node
                return (0 until node.childCount).firstNotNullOfOrNull { searchField(node.getChild(it)) }
            }
            val search = requireNotNull(searchField(test.uiAutomation.rootInActiveWindow))
            val input = Bundle().apply {
                putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Needle")
            }
            check(search.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, input))
            await("body search returned result") {
                test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("1 条匹配")?.isNotEmpty() == true
            }
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            test.waitForIdleSync()
            await("search dialog closed") {
                val root = test.uiAutomation.rootInActiveWindow ?: return@await false
                if (root.findAccessibilityNodeInfosByText("搜索当前会话").isEmpty()) true
                else { test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); false }
            }

            await("agent connected") { field("loaded").getBoolean(activity) }
            onUi {
                invoke("newChat", Boolean::class.javaPrimitiveType!!, values = arrayOf(false))
                invoke("acceptExternalIntent", Intent::class.java, values = arrayOf(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "shared-fixture"),
                ))
            }
            test.waitForIdleSync()
            fun clickShareOption(label: String) {
                await("share option $label") {
                    val root = test.uiAutomation.rootInActiveWindow ?: return@await false
                    val item = root.findAccessibilityNodeInfosByText(label).firstOrNull {
                        it.text?.toString() == label
                    } ?: return@await false
                    val clickable = generateSequence(item) { it.parent }.firstOrNull { it.isClickable } ?: return@await false
                    clickable.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            clickShareOption("加入新会话草稿")
            await("external share filled draft") {
                (field("composer").get(activity) as EditText).text.toString().contains("shared-fixture")
            }
            if (args.getString("desktopShare") == "true") {
                val sample = java.io.File(activity.cacheDir, "attachment-camera/CodexRM-Share-QA.bin")
                sample.parentFile?.mkdirs()
                java.io.RandomAccessFile(sample, "rw").use {
                    it.setLength(22L * 1024 * 1024)
                    it.writeUTF("CodexRM isolated share fixture")
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(activity, "${context.packageName}.attachment-camera", sample)
                onUi {
                    invoke("acceptExternalIntent", Intent::class.java, values = arrayOf(
                        Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri),
                    ))
                }
                clickShareOption("发给电脑")
                val receiverName = (field("device").get(activity) as Device).serverName
                clickShareOption(receiverName)
                await("desktop transfer dialog closed") {
                    val root = test.uiAutomation.rootInActiveWindow ?: return@await false
                    root.findAccessibilityNodeInfosByText("发送到 $receiverName").isEmpty()
                }
                check((field("composer").get(activity) as EditText).text.toString().contains("shared-fixture"))
                sample.delete()
            }
            onUi {
                val composer = field("composer").get(activity) as EditText
                composer.setText("approval")
                invoke("sendMessage", Boolean::class.javaPrimitiveType!!, values = arrayOf(false))
            }
            await("approval task started") {
                (field("currentThreadId").get(activity) as String).isNotBlank() && field("running").getBoolean(activity)
            }
            val thread = field("currentThreadId").get(activity) as String
            TaskMonitor.enable(context, device.agentId, true)
            onUi { check(activity.moveTaskToBack(true)) { "Could not move QA task to background" } }
            val notifications = context.getSystemService(NotificationManager::class.java)
            await("pending approval delivered in background") {
                !field("started").getBoolean(activity) &&
                    notifications.activeNotifications.any { it.notification.extras.getString("android.title") == "Codex 需要审批" }
            }
            notifications.activeNotifications.single { it.notification.extras.getString("android.title") == "Codex 需要审批" }
                .notification.contentIntent.send()
            await("notification resumed correct conversation") {
                field("started").getBoolean(activity) && field("currentThreadId").get(activity) == thread
            }
            await("reconnected after notification") { field("online").getBoolean(activity) && field("loaded").getBoolean(activity) }
            val patch = AtomicReference<JSONObject?>()
            val diffError = AtomicReference<Exception?>()
            onUi {
                val client = field("client").get(activity) as CodexClient
                val cwd = (field("info").get(activity) as JSONObject).string("cwd")
                client.request("codexrm/workspace/diff", JSONObject().put("workspace", cwd).put("threadId", thread)) { result, error ->
                    diffError.set(error)
                    patch.set(result)
                }
            }
            await("workspace diff received") { patch.get() != null || diffError.get() != null }
            diffError.get()?.let { throw it }
            check(patch.get()!!.string("diff").contains("+changed-by-fixture"))
            check(patch.get()!!.arrayAt("untracked").optString(0) == "new.txt")
            val selectedThread = AtomicReference<String?>()
            onUi {
                val connection = field("client").get(activity) as CodexClient
                connection.request("thread/start") { result, error ->
                    if (error != null || result == null) return@request
                    val target = result.objectAt("thread")
                    val id = target.string("id")
                    connection.request("thread/name/set", JSONObject().put("threadId", id).put("name", "Share destination QA")) { _, renameError ->
                        if (renameError == null) selectedThread.set(id)
                    }
                }
            }
            await("second chat ready") { !selectedThread.get().isNullOrBlank() }
            await("second chat listed") {
                (field("threadList").get(activity) as ThreadListState).entries.any {
                    it.id == selectedThread.get() && it.title == "Share destination QA"
                }
            }
            onUi {
                invoke("acceptExternalIntent", Intent::class.java, values = arrayOf(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "existing-share-fixture"),
                ))
            }
            clickShareOption("加入对话")
            clickShareOption("Share destination QA")
            await("share filled selected existing chat") {
                field("currentThreadId").get(activity) == selectedThread.get() &&
                    (field("composer").get(activity) as EditText).text.toString().contains("existing-share-fixture")
            }
            return "cache=pass codeCopy=pass latestMessage=pass bodySearch=pass newShareDraft=pass existingShareDraft=pass backgroundApproval=pass notificationRoute=pass workspaceDiff=pass"
        } finally {
            TaskMonitor.enable(context, device.agentId, false)
            context.stopService(Intent(context, TaskMonitor::class.java))
            onUi { activity.finish() }
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        do {
            if (condition()) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Timed out: $label")
    }
}
