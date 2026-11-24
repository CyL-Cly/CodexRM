package com.codexrm.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

internal object DesktopCloseSendCheck {
    fun run(test: Instrumentation) {
        var activity: Activity? = null
        fun onMain(block: () -> Unit) {
            var failure: Throwable? = null
            test.runOnMainSync { failure = runCatching(block).exceptionOrNull() }
            failure?.let { throw it }
        }
        try {
            val context = test.targetContext
            check(context.packageName.endsWith(".imageqa"))
            val device = Device("mock-client", "mock-token", "http://127.0.0.1:1", "desktop-close-qa", "Desktop close QA")
            DeviceStore(context).legacySystemWarningShown = true
            val screen = test.startActivitySync(Intent().setClassName(context.packageName, MainActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = screen
            fun field(name: String) = screen.javaClass.getDeclaredField(name).apply { isAccessible = true }
            fun composer() = field("composer").get(screen) as EditText
            fun dialog() = field("desktopCloseDialog").get(screen) as? MiuixDialog
            fun pressSend() { check((field("sendButton").get(screen) as View).performClick()) }
            val draft = "继续\n之前的\n对话"
            val threadId = "desktop-close-thread"
            val thread = JSONObject().put("id", threadId).put("cwd", "/qa").put("turns", JSONArray())
            val client = CodexClient(context, device, object : CodexClient.Listener {
                override fun onState(state: ConnectionState, agent: JSONObject?) = Unit
                override fun onRpcEvent(message: JSONObject) = Unit
                override fun onFailure(message: String) = Unit
                override fun onAuthorizationExpired() = Unit
                override fun onDirectFallback() = Unit
            }, relayOnly = true)
            val receive = client.javaClass.getDeclaredMethod("handle", String::class.java, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            val main = Handler(Looper.getMainLooper())
            val calls = mutableListOf<String>()
            val sent = mutableListOf<String>()
            var desktopOpen = true
            var otherFailure = false
            var deactivated = false
            val socket = object : WebSocket {
                override fun request() = Request.Builder().url(device.serverUrl).build()
                override fun queueSize() = 0L
                override fun send(bytes: ByteString) = false
                override fun close(code: Int, reason: String?) = true
                override fun cancel() { deactivated = true }
                override fun send(text: String): Boolean {
                    val request = JSONObject(text).getJSONObject("message")
                    val method = request.getString("method")
                    calls += method
                    val response = JSONObject().put("id", request.getInt("id"))
                    fun reject(message: String) { response.put("error", JSONObject().put("code", -32600).put("message", message)) }
                    when (method) {
                        "turn/start" -> when {
                            otherFailure -> reject("Invalid provider credentials")
                            desktopOpen -> reject("thread not found: $threadId")
                            else -> {
                                val params = request.getJSONObject("params")
                                check(params.getString("threadId") == threadId)
                                sent += params.getJSONArray("input").getJSONObject(0).getString("text")
                                response.put("result", JSONObject().put("turn", JSONObject().put("id", "retry").put("status", "completed")))
                            }
                        }
                        "thread/resume" -> if (desktopOpen) reject("thread $threadId already has an active writer")
                            else response.put("result", JSONObject().put("thread", thread))
                        "codexrm/desktop/close" -> {
                            desktopOpen = false
                            response.put("result", JSONObject().put("closed", 1))
                        }
                        "codexrm/thread/snapshot" -> response.put("result", JSONObject().put("thread", thread))
                    }
                    main.post {
                        if (!deactivated) receive.invoke(client, JSONObject().put("type", "server_message").put("message", response).toString(), false)
                    }
                    return true
                }
            }
            fun waitFor(label: String, condition: () -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 5_000
                do {
                    var ready = false
                    onMain { ready = condition() }
                    if (ready) return
                    SystemClock.sleep(30)
                } while (SystemClock.elapsedRealtime() < deadline)
                error("Timed out: $label")
            }
            onMain {
                screen.javaClass.getDeclaredMethod("disconnect").apply { isAccessible = true }.invoke(screen)
                if (!field("mainBuilt").getBoolean(screen)) {
                    screen.javaClass.getDeclaredMethod("buildMain").apply { isAccessible = true }.invoke(screen)
                }
                field("mainVisible").setBoolean(screen, true)
                screen.setContentView(field("mainRoot").get(screen) as View)
                field("device").set(screen, device)
                field("client").set(screen, client)
                field("online").setBoolean(screen, true)
                field("loaded").setBoolean(screen, true)
                field("info").set(screen, JSONObject().put("desktopControl", true).put("threadSnapshots", true))
                field("outbox").set(screen, OutboxState())
                field("outboxOwnerAgentId").set(screen, device.agentId)
                mapOf("active" to true, "online" to true, "socket" to socket).forEach { (name, value) ->
                    client.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(client, value)
                }
                screen.javaClass.getDeclaredMethod("renderOpenThread", String::class.java, JSONObject::class.java,
                    JSONObject::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                    .invoke(screen, threadId, JSONObject(), thread, false)
                val input = composer()
                check(input.requestFocus())
                input.setText("继续")
                input.setSelection(input.length())
                val editor = EditorInfo()
                val connection = checkNotNull(input.onCreateInputConnection(editor))
                try {
                    check((editor.imeOptions and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_NONE)
                    check(connection.commitText("\n之前的", 1))
                    check(input.text.toString() == "继续\n之前的")
                    input.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    input.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                    check(input.text.toString() == "继续\n之前的\n")
                    check(connection.commitText("对话", 1))
                    input.onEditorAction(EditorInfo.IME_ACTION_SEND)
                    check(input.text.toString() == draft)
                    check("turn/start" !in calls && !field("sending").getBoolean(screen))
                } finally {
                    connection.closeConnection()
                    input.clearFocus()
                }
                pressSend()
            }
            waitFor("writer conflict dialog") { dialog()?.isShowing == true }
            onMain {
                check(calls.takeLast(2) == listOf("turn/start", "thread/resume"))
                check(composer().text.toString() == draft)
                checkNotNull(dialog()).getButton(MiuixDialog.NEGATIVE).performClick()
            }
            waitFor("dialog canceled") { dialog() == null }
            onMain {
                check("codexrm/desktop/close" !in calls && sent.isEmpty())
                check(composer().text.toString() == draft)
                otherFailure = true
                pressSend()
            }
            waitFor("ordinary send failure") { !field("sending").getBoolean(screen) }
            onMain {
                check(dialog() == null && "codexrm/desktop/close" !in calls)
                // A known read-only conversation must still let the user enter a draft and tap Send.
                otherFailure = false
                field("readOnly").setBoolean(screen, true)
                screen.javaClass.getDeclaredMethod("updateControls").apply { isAccessible = true }.invoke(screen)
                check(composer().isEnabled && (field("sendButton").get(screen) as View).isEnabled)
                pressSend()
            }
            waitFor("read-only writer conflict") { dialog()?.isShowing == true }
            onMain { checkNotNull(dialog()).getButton(MiuixDialog.POSITIVE).performClick() }
            waitFor("automatic resend accepted") { sent.isNotEmpty() && !field("sending").getBoolean(screen) }
            onMain {
                check(calls.count { it == "codexrm/desktop/close" } == 1)
                check(calls.drop(calls.indexOf("codexrm/desktop/close")).filterNot { it == "codexrm/thread/snapshot" } ==
                    listOf("codexrm/desktop/close", "thread/resume", "turn/start"))
                check(sent == listOf(draft))
                check(composer().text.isEmpty() && !field("readOnly").getBoolean(screen))
                // Stale read-only state must not prompt again if the writer is already gone.
                field("readOnly").setBoolean(screen, true)
                composer().setText("桌面已退出")
                pressSend()
            }
            waitFor("already released writer") { sent.size == 2 && !field("sending").getBoolean(screen) }
            onMain {
                check(dialog() == null && calls.count { it == "codexrm/desktop/close" } == 1)
                check(sent.last() == "桌面已退出")
            }
            test.finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "Composer newline and desktop close send checks passed\n") })
        } catch (error: Throwable) {
            test.finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "${error.stackTraceToString()}\n") })
        } finally {
            onMain { activity?.finish() }
        }
    }
}
