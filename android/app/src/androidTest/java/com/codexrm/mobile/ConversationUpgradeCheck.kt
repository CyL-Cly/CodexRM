package com.codexrm.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.webkit.WebView
import android.widget.EditText
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object ConversationUpgradeCheck {
    fun run(test: Instrumentation, args: Bundle) {
        var activity: Activity? = null
        val result = Bundle()
        fun onUi(block: () -> Unit) {
            val problem = AtomicReference<Throwable?>()
            test.runOnMainSync { runCatching(block).onFailure(problem::set) }
            problem.get()?.let { throw it }
        }
        try {
            val context = test.targetContext
            check(context.packageName.endsWith(".imageqa"))
            val rm = requireNotNull(args.getString("rm"))
            val claim = OkHttpClient().newCall(Request.Builder().url("$rm/api/v1/client/claim")
                .post(JSONObject().put("code", requireNotNull(args.getString("pair"))).put("deviceType", "android")
                    .toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
                check(it.isSuccessful) { "QA pairing failed: ${it.code}" }
                JSONObject(it.body?.string().orEmpty())
            }
            val agent = claim.getJSONObject("agent")
            val device = Device(claim.getString("clientId"), claim.getString("token"), rm, agent.getString("agentId"), "Conversation QA")
            DeviceStore(context).apply { save(listOf(device)); selectedId = device.agentId; legacySystemWarningShown = true }
            val events = LinkedBlockingQueue<JSONObject>()
            lateinit var client: CodexClient
            var online = false
            onUi {
                client = CodexClient(context, device, object : CodexClient.Listener {
                    override fun onFailure(message: String) = Unit
                    override fun onAuthorizationExpired() = Unit
                    override fun onDirectFallback() = Unit
                    override fun onRpcEvent(message: JSONObject) { events.offer(message) }
                    override fun onState(state: ConnectionState, agent: JSONObject?) { online = state == ConnectionState.ONLINE }
                }, relayOnly = true)
                client.start()
            }
            await("fixture connection") { var ready = false; onUi { ready = online }; ready }
            fun rpc(method: String, params: JSONObject = JSONObject()): JSONObject {
                val reply = LinkedBlockingQueue<Pair<JSONObject?, Exception?>>()
                onUi { client.request(method, params) { value, error -> reply.offer(value to error) } }
                val (value, error) = checkNotNull(reply.poll(30, TimeUnit.SECONDS)) { "Timeout: $method" }
                return value ?: error("$method failed: $error")
            }
            fun threadId(id: String) = JSONObject().put("threadId", id)
            val source = rpc("thread/start").getJSONObject("thread").getString("id")
            val needle = "CrossHistoryNeedle731"
            repeat(6) { index ->
                val phrase = if (index == 0) "$needle original instruction" else "Follow-up $index"
                val id = rpc("turn/start", threadId(source).put("input", ProtocolFormatter.input(phrase)))
                    .getJSONObject("turn").getString("id")
                val deadline = SystemClock.elapsedRealtime() + 20000
                while (true) {
                    check(SystemClock.elapsedRealtime() < deadline) { "Turn did not finish" }
                    val event = events.poll(1, TimeUnit.SECONDS) ?: continue
                    if (event.string("method") == "turn/completed" && event.objectAt("params").objectAt("turn").string("id") == id) break
                }
            }
            rpc("thread/name/set", threadId(source).put("name", "Source fixture"))
            onUi { client.stop() }
            val screen = test.startActivitySync(Intent().setClassName(context.packageName, "com.codexrm.mobile.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = screen
            fun field(name: String) = screen.javaClass.getDeclaredField(name).apply { isAccessible = true }
            fun invoke(name: String, vararg types: Class<*>, values: Array<Any> = emptyArray()) =
                screen.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(screen, *values)
            fun onScreen(label: String): AccessibilityNodeInfo? = test.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText(label)?.firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
            fun click(label: String) = await("click $label") {
                val node = onScreen(label) ?: return@await false
                generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            fun state(check: () -> Boolean): Boolean { var value = false; onUi { value = check() }; return value }
            await("main loaded") { state { field("loaded").getBoolean(screen) } }
            onUi {
                invoke("showAllConversationSearch")
                val entry = field("referenceSearch").get(screen) as ReferenceSearch
                val query = entry.javaClass.getDeclaredField("input").apply { isAccessible = true }.get(entry) as EditText
                query.setText(needle)
            }
            click("$needle original instruction")
            val adapter = field("adapter").get(screen) as MessageAdapter
            await("old search hit loaded") { state { field("currentThreadId").get(screen) == source && adapter.snapshot().any { it.body == "$needle original instruction" } } }
            onUi {
                val message = adapter.snapshot().first { it.body == "$needle original instruction" }
                invoke("showMessageActions", ChatMessage::class.java, values = arrayOf(message))
            }
            click("用原指令重试")
            await("branch draft") { state {
                val composer = field("composer").get(screen) as EditText
                field("currentThreadId").get(screen) !in listOf("", source) && composer.text.toString() == "$needle original instruction" && adapter.snapshot().none { it.kind == MessageKind.USER }
            } }
            onUi { invoke("showRelatedConversations", String::class.java, values = arrayOf(field("currentThreadId").get(screen))) }
            click("Source fixture · 源会话")
            await("returned to source") { state { field("currentThreadId").get(screen) == source && !field("loading").getBoolean(screen) } }
            val summary = ThreadSummary(source, "Source fixture", "", false)
            onUi { invoke("showSidebarThreadActions", ThreadSummary::class.java, values = arrayOf(summary)) }
            click("置顶会话")
            await("pinned source") { state { (field("pinnedThreadIds").get(screen) as Set<*>).contains(source) } }
            onUi { invoke("showSidebarThreadActions", ThreadSummary::class.java, values = arrayOf(summary)) }
            click("归档")
            click("归档")
            await("archived source") { state { field("currentThreadId").get(screen) != source } }
            onUi { invoke("showArchivedConversations") }
            click("恢复")
            await("restored source") { state { (field("pinnedThreads").get(screen) as List<*>).any { (it as ThreadSummary).id == source } } }
            click("关闭")
            lateinit var diagramDialog: android.app.Dialog
            onUi {
                val code = "graph TD\nA[FixtureStart] --> B[FixtureEnd]"
                val diagramAdapter = MessageAdapter(screen, MessageImageLoader(screen) { ImageEnvironment("mermaid-qa", "", null, false) })
                diagramAdapter.add(ChatMessage("diagram", MessageKind.ASSISTANT, "Codex", "```mermaid\n$code\n```"))
                val row = diagramAdapter.getView(0, null, android.widget.ListView(screen))
                val body = requireNotNull(diagramAdapter.messageBody(row))
                val rendered = body.text as Spanned
                val preview = rendered.getSpans(0, rendered.length, ClickableSpan::class.java).first {
                    rendered.subSequence(rendered.getSpanStart(it), rendered.getSpanEnd(it)).toString() == "预览图表"
                }
                diagramDialog = android.app.Dialog(screen).apply {
                    setContentView(android.widget.ScrollView(screen).apply { addView(row) })
                    show()
                }
                preview.onClick(body)
            }
            fun webView(view: View): WebView? = if (view is WebView) view else (view as? ViewGroup)?.let { group ->
                (0 until group.childCount).firstNotNullOfOrNull { webView(group.getChildAt(it)) }
            }
            fun hasDiagram(label: String) {
                val svg = AtomicReference<String?>()
                await(label) {
                    onUi {
                        WindowInspector.getGlobalWindowViews().asReversed().firstNotNullOfOrNull(::webView)
                            ?.evaluateJavascript("document.querySelector('svg')?.textContent?.includes('FixtureEnd') ?? false", svg::set)
                    }
                    svg.get() == "true"
                }
            }
            hasDiagram("inline SVG")
            click("全屏")
            hasDiagram("full-screen SVG")
            click("源码")
            await("source shown") { test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("FixtureStart")?.isNotEmpty() == true }
            click("关闭图表")
            onUi { diagramDialog.dismiss() }
            result.putString("result", "old search hit, first-turn retry draft, related branches, pin, archive, restore, offline Mermaid SVG and source passed")
            test.finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("error", error.stackTraceToString())
            test.finish(Activity.RESULT_CANCELED, result)
        } finally {
            activity?.let { onUi { it.finish() } }
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(80)
        }
        error("Timed out: $label")
    }
}
