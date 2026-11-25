package com.codexrm.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.database.DataSetObserver
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

internal object SnapshotRenderingCheck {
    fun run(test: Instrumentation) {
        var activity: Activity? = null
        try {
            val context = test.targetContext
            check(context.packageName.endsWith(".imageqa"))
            val screen = test.startActivitySync(Intent().setClassName(context.packageName, MainActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = screen
            val type = screen.javaClass
            fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
            val applySnapshot = type.getDeclaredMethod("replaceLatestSnapshot", JSONObject::class.java, String::class.java)
                .apply { isAccessible = true }
            fun snapshot(reply: String) = JSONObject().put("id", "snapshot-check").put("name", "Snapshot check")
                .put("status", JSONObject().put("type", "active"))
                .put("turns", JSONArray().put(JSONObject().put("id", "turn").put("status", "completed")
                    .put("items", JSONArray()
                        .put(JSONObject().put("id", "reply").put("type", "agentMessage").put("text", "**stable reply**"))
                        .put(JSONObject().put("id", "tail").put("type", "agentMessage").put("text", reply)))))
            fun onMain(block: () -> Unit) {
                var failure: Throwable? = null
                test.runOnMainSync { failure = runCatching(block).exceptionOrNull() }
                failure?.let { throw it }
            }
            var notifications = 0
            lateinit var adapter: MessageAdapter
            lateinit var list: ChatListView
            onMain {
                if (field("mainBuilt").get(screen) != true) {
                    type.getDeclaredMethod("buildMain").apply { isAccessible = true }.invoke(screen)
                }
                field("currentThreadId").set(screen, "snapshot-check")
                field("readOnly").setBoolean(screen, false)
                screen.setContentView(field("mainRoot").get(screen) as View)
                list = field("messageList").get(screen) as ChatListView
                adapter = list.adapter as MessageAdapter
                applySnapshot.invoke(screen, snapshot("initial tail"), null)
                adapter.registerDataSetObserver(object : DataSetObserver() {
                    override fun onChanged() { notifications++ }
                })
            }
            onMain {
                // Exercise the real ListView even if the device is locked and no window frame is drawn.
                fun layoutList() {
                    val width = Ui.dp(screen, 360)
                    val height = Ui.dp(screen, 480)
                    list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    list.layout(0, 0, width, height)
                }
                layoutList()
                val initial = adapter.snapshot()
                val stable = initial.first()
                val drawing = stable.rendered ?: error("Initial reply was not rendered")
                val row = (0 until list.childCount).map(list::getChildAt).first { adapter.boundMessage(it) === stable }
                val position = checkNotNull(list.readingPosition())
                repeat(3) { applySnapshot.invoke(screen, snapshot("initial tail"), null) }
                check(notifications == 0) { "Identical snapshots notified the adapter $notifications times" }
                check(adapter.snapshot().zip(initial).all { (a, b) -> a === b })
                check(list.readingPosition() == position && !list.isLayoutRequested) { "Unchanged snapshot requested a relayout" }
                check(adapter.boundMessage(row) === stable && stable.rendered === drawing)

                applySnapshot.invoke(screen, snapshot("updated tail"), null)
                check(notifications == 1 && adapter.message("tail")?.body == "updated tail")
                check(adapter.message("reply") === stable && stable.rendered === drawing)
                layoutList()
                check((0 until list.childCount).map(list::getChildAt).any {
                    adapter.boundMessage(it)?.id == "tail" && adapter.messageBody(it)?.text.toString() == "updated tail"
                })
            }
            onMain {
                field("readOnly").setBoolean(screen, false)
                checkReconnectErrors(screen, adapter)
                checkApprovalQueue(screen)
                checkToolCardStyle(screen, list, adapter)
            }
            test.finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "Snapshot rendering, reconnect error, approval queue and chat card checks passed\n") })
        } catch (error: Throwable) {
            test.finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", error.stackTraceToString()) })
        } finally {
            activity?.let { screen -> test.runOnMainSync { screen.finish() } }
        }
    }

    private fun checkToolCardStyle(screen: Activity, list: ChatListView, adapter: MessageAdapter) {
        val type = screen.javaClass
        fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        val renderBootstrap = type.getDeclaredMethod("renderBootstrapState").apply { isAccessible = true }
        field("online").setBoolean(screen, true)
        field("bootstrapping").setBoolean(screen, true)
        field("bootstrapError").set(screen, "")
        val area = field("bootstrapArea").get(screen) as LinearLayout
        val commands = List(3) { ChatMessage("check-$it", MessageKind.TOOL, "运行命令", "command output", "退出码 0") }
        fun measure(view: View, width: Int) = view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        fun compareRow(row: LinearLayout): LinearLayout {
            val syncCard = area.getChildAt(0) as LinearLayout
            val label = syncCard.getChildAt(0) as TextView
            val toolCard = row.getChildAt(0) as LinearLayout
            val header = toolCard.getChildAt(0) as LinearLayout
            val title = header.getChildAt(1) as TextView
            val syncBackground = syncCard.background as GradientDrawable
            val toolBackground = toolCard.background as GradientDrawable
            check(syncBackground.cornerRadius == toolBackground.cornerRadius)
            check(syncBackground.color?.defaultColor == toolBackground.color?.defaultColor)
            check(header.paddingLeft == syncCard.paddingLeft && header.paddingTop == syncCard.paddingTop)
            check(header.paddingRight == syncCard.paddingRight && header.paddingBottom == syncCard.paddingBottom)
            check(title.currentTextColor == label.currentTextColor && title.textSize == label.textSize && !title.typeface.isBold)
            val left = row.paddingLeft + list.paddingLeft
            val right = row.paddingRight + list.paddingRight
            val composer = area.parent as View
            check(left == composer.paddingLeft && right == composer.paddingRight)
            val width = Ui.dp(screen, 360)
            measure(row, width - list.paddingLeft - list.paddingRight)
            measure(syncCard, width - left - right)
            check(toolCard.measuredHeight == syncCard.measuredHeight) { "Collapsed cards must have the same height" }
            return header
        }
        for (dark in listOf(true, false)) {
            Ui.configure(screen, dark)
            adapter.bubbleOpacity = 100
            renderBootstrap.invoke(screen)
            adapter.replace(commands)
            val row = adapter.getView(0, null, list) as LinearLayout
            val header = compareRow(row)
            check((header.getChildAt(2) as TextView).text == "退出码 0 · 3 条操作")
            adapter.bubbleOpacity = 65
            renderBootstrap.invoke(screen)
            compareRow(adapter.getView(0, row, list) as LinearLayout)
            check(header.performClick() && adapter.count == 4)
            val detail = adapter.getView(1, null, list)
            check(adapter.messageBody(detail)?.text.toString() == "command output")
            check(header.performClick() && adapter.count == 1)
            adapter.replace(commands.take(1))
            val single = compareRow(adapter.getView(0, row, list) as LinearLayout)
            check(single.performClick())
            check(adapter.messageBody(adapter.getView(0, row, list))?.visibility == View.VISIBLE)
        }
    }

    private fun checkReconnectErrors(screen: Activity, adapter: MessageAdapter) {
        val handleEvent = screen.javaClass.getDeclaredMethod("handleRpcEvent", JSONObject::class.java).apply { isAccessible = true }
        fun event(method: String, params: JSONObject, threadId: String = "snapshot-check") {
            handleEvent.invoke(screen, JSONObject().put("method", method).put("params", params.put("threadId", threadId)))
        }
        fun turnEvent(method: String, id: String, status: String, failure: String? = null) {
            val turn = JSONObject().put("id", id).put("status", status)
            if (failure != null) turn.put("error", JSONObject().put("message", failure))
            event(method, JSONObject().put("turn", turn))
        }
        fun report(id: String, body: String, retry: Boolean = true, threadId: String = "snapshot-check") {
            event("error", JSONObject().put("turnId", id).put("willRetry", retry)
                .put("error", JSONObject().put("message", body)), threadId)
        }
        fun errors() = adapter.snapshot().filter { it.kind == MessageKind.ERROR }

        turnEvent("turn/started", "failed-turn", "inProgress")
        report("failed-turn", "Reconnecting... 1/5")
        val retryId = errors().single().id
        for (attempt in 2..5) {
            val text = "Reconnecting... $attempt/5"
            report("failed-turn", text)
            val message = errors().single()
            check(message.id == retryId && message.body == text)
        }
        report("failed-turn", "Error from another thread", threadId = "other-thread")
        check(errors().single().body == "Reconnecting... 5/5")
        report("failed-turn", "Connection failed", retry = false)
        check(errors().single().body == "Connection failed")
        turnEvent("turn/completed", "failed-turn", "failed", "Retry limit reached")
        check(errors().single().body == "Retry limit reached")

        turnEvent("turn/started", "successful-turn", "inProgress")
        report("successful-turn", "Reconnecting... 1/5")
        check(errors().map { it.body } == listOf("Retry limit reached", "Reconnecting... 1/5"))
        turnEvent("turn/completed", "successful-turn", "completed")
        check(errors().single().body == "Retry limit reached")
    }

    private fun checkApprovalQueue(screen: Activity) {
        val type = screen.javaClass
        fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        val handleEvent = type.getDeclaredMethod("handleRpcEvent", JSONObject::class.java).apply { isAccessible = true }
        val area = field("approvalArea").get(screen) as LinearLayout
        val online = field("online")
        val loaded = field("loaded")
        val device = field("device")
        val previousDevice = device.get(screen)
        val previousOnline = online.getBoolean(screen)
        val previousLoaded = loaded.getBoolean(screen)
        device.set(screen, Device("qa-client", "", "", "qa-agent", "QA"))
        online.setBoolean(screen, true)
        loaded.setBoolean(screen, true)
        fun request(id: String, thread: String = "snapshot-check") {
            handleEvent.invoke(screen, JSONObject().put("id", id).put("method", "item/commandExecution/requestApproval")
                .put("params", JSONObject().put("threadId", thread).put("command", id)))
        }
        fun reply(method: String, id: String) {
            handleEvent.invoke(screen, JSONObject().put("method", "codexrm/request/$method").put("params", JSONObject().put("id", id)))
        }
        fun children(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) yieldAll(children(view.getChildAt(i)))
            }
        }
        fun commandCards(): List<String> = children(area).filterIsInstance<TextView>()
            .map { it.text.toString() }.filter { it.startsWith("cmd-") }.toList()
        fun button(label: String) = children(area).filterIsInstance<MiuixButtonView>().singleOrNull { it.text == label }
        @Suppress("UNCHECKED_CAST")
        val responding = field("responding").get(screen) as MutableSet<String>
        try {
            request("cmd-other", "other-thread")
            request("cmd-first")
            request("cmd-second")
            request("cmd-third")
            check(commandCards() == listOf("cmd-first"))
            check(button("全部允许 (3)")?.isEnabled == true && button("全部拒绝")?.isEnabled == true)

            responding.add("String:cmd-first")
            request("cmd-third") // A repeated delivery must not reorder the remaining approvals.
            check(commandCards() == listOf("cmd-first"))
            check(button("允许一次")?.isEnabled == false && button("全部拒绝")?.isEnabled == false)
            reply("failed", "cmd-first")
            check(commandCards() == listOf("cmd-first") && button("允许一次")?.isEnabled == true)

            reply("resolved", "cmd-first")
            check(commandCards() == listOf("cmd-second") && button("全部允许 (2)")?.isEnabled == true)
            reply("resolved", "cmd-second")
            check(commandCards() == listOf("cmd-third") && button("全部拒绝") == null)
            reply("resolved", "cmd-third")
            check(commandCards().isEmpty())
        } finally {
            device.set(screen, previousDevice)
            online.setBoolean(screen, previousOnline)
            loaded.setBoolean(screen, previousLoaded)
        }
    }
}
