package com.codexrm.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.InputDevice
import android.database.DataSetObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.text.Spanned
import android.text.style.URLSpan
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import io.noties.markwon.image.AsyncDrawableSpan
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Run on a device with the isolated .mathqa debug app. */
class MathRenderingInstrumentation : Instrumentation() {
    private lateinit var root: LinearLayout
    private lateinit var sidebar: SidebarSwipeLayout
    private lateinit var row: LinearLayout
    private lateinit var adapter: MessageAdapter
    private lateinit var message: ChatMessage
    private val checks = JSONArray()
    private var sidebarOpens = 0
    private var width = 1080
    private fun body() = row.getChildAt(1) as MathTextView
    private fun spans() = (body().text as Spanned).let { it.getSpans(0, it.length, AsyncDrawableSpan::class.java).toList() }
    private fun blocks() = spans().filterIsInstance<ScrollableMathSpan>()
    private fun verify(name: String, condition: Boolean) {
        checks.put(JSONObject().put("name", name).put("passed", condition))
        check(condition) { name }
    }
    private fun draw(name: String = "") {
        body().text = body().text
        sidebar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        sidebar.layout(0, 0, sidebar.measuredWidth, sidebar.measuredHeight)
        val bitmap = Bitmap.createBitmap(width, sidebar.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Ui.BACKGROUND)
        sidebar.draw(canvas)
        if (name.isNotEmpty()) File(targetContext.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun rebind() { row = adapter.getView(0, row, root) as LinearLayout; draw() }

    private fun swipe(span: ScrollableMathSpan, dx: Float, dy: Float = 0f) {
        val rect = android.graphics.Rect()
        body().getDrawingRect(rect)
        sidebar.offsetDescendantRectToMyCoords(body(), rect)
        val x = rect.left + body().totalPaddingLeft + span.viewport.centerX()
        val y = rect.top + body().totalPaddingTop + span.viewport.centerY()
        val time = SystemClock.uptimeMillis()
        for (step in 0..6) {
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 6 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(time, time + step * 16L, action, x + dx * step / 6, y + dy * step / 6, 0)
            sidebar.dispatchTouchEvent(event)
            event.recycle()
        }
        if (root.parent === sidebar) draw()
    }

    private fun checkStreamingRows() {
        val pendingReady = CountDownLatch(1)
        val closedReady = CountDownLatch(1)
        lateinit var list: ListView
        lateinit var reply: ChatMessage
        lateinit var head: View
        var firstTop = 0
        var firstPosition = 0
        var datasetUpdates = 0
        val pending = "已生成的正文\n\n\\[\n\\frac{x_1}{y_1}"
        val closed = pending + "\n\\]"
        fun layout() {
            fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
            list.measure(exact(Ui.dp(list.context, 360)), exact(Ui.dp(list.context, 380)))
            list.layout(0, 0, list.measuredWidth, list.measuredHeight)
        }
        runOnMainSync {
            val context = ContextThemeWrapper(targetContext, R.style.Theme_CodexRM)
            val history = List(24) { ChatMessage("history-$it", MessageKind.USER, "", "历史消息 $it") }
            reply = ChatMessage("live", MessageKind.ASSISTANT, "Codex", pending)
            adapter.replace(history + reply)
            adapter.prepareReply(reply, streaming = true)
            list = ListView(context).apply {
                divider = null
                setStackFromBottom(true)
                transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL
                adapter = this@MathRenderingInstrumentation.adapter
            }
            layout()
            list.setSelectionFromTop(3, -6)
            layout()
            head = list.getChildAt(0)
            firstTop = head.top
            firstPosition = list.firstVisiblePosition
            check(list.lastVisiblePosition < history.size)
            adapter.registerDataSetObserver(object : DataSetObserver() {
                override fun onChanged() { datasetUpdates++ }
            })
            adapter.onStreamRendered = { id ->
                adapter.refreshRows(list, setOf(id))
                if (reply.body == pending) pendingReady.countDown() else closedReady.countDown()
            }
        }
        check(pendingReady.await(5, TimeUnit.SECONDS)) { "Timed out waiting for partial reply" }
        runOnMainSync {
            val pendingRow = adapter.getView(list.count - 1, null, list) as LinearLayout
            val text = (pendingRow.getChildAt(1) as MathTextView).text
            verify("unfinished math uses one placeholder without covering earlier prose", text.contains("已生成的正文") && text.contains("…") && !text.contains("frac"))
            reply.change(body = closed)
            adapter.prepareReply(reply, streaming = false)
        }
        check(closedReady.await(5, TimeUnit.SECONDS)) { "Timed out waiting for completed formula" }
        runOnMainSync {
            layout()
            verify("a reply below the viewport does not refresh history or move its anchor", datasetUpdates == 0 &&
                list.firstVisiblePosition == firstPosition && list.getChildAt(0) === head && head.top == firstTop)
            val rendered = reply.rendered as Spanned
            val formula = rendered.getSpans(0, rendered.length, ScrollableMathSpan::class.java).single()
            verify("a closed block already has its real size before binding to a TextView", formula.drawable.hasResult() && formula.drawable.bounds.height() > 1)
            list.setSelection(list.count - 1)
            layout()
            val liveBody = (list.getChildAt(list.childCount - 1) as LinearLayout).getChildAt(1) as MathTextView
            verify("returning to the latest reply shows the prepared formula", liveBody.text.contains("已生成的正文") && (liveBody.text as Spanned)
                .getSpans(0, liveBody.text.length, ScrollableMathSpan::class.java).single().drawable.hasResult())
        }
    }

    private fun onMain(block: () -> Unit) {
        var error: Throwable? = null
        runOnMainSync { try { block() } catch (failure: Throwable) { error = failure } }
        error?.let { throw it }
    }

    private fun checkHistoryPrepend() {
        val screen = startActivitySync(Intent().setClassName(targetContext.packageName, "com.codexrm.mobile.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val type = screen.javaClass
        fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }.get(screen)
        val prependHistory = type.getDeclaredMethod("prependHistory", List::class.java).apply { isAccessible = true }
        lateinit var chat: ChatListView
        lateinit var chatAdapter: MessageAdapter
        val reply = ChatMessage("current-reply", MessageKind.ASSISTANT, "Codex",
            (1..100).joinToString("\n\n") { "Line $it of the current reply." })
        val tools = List(8) { ChatMessage("old-tool-$it", MessageKind.TOOL, "Command $it", "Output $it") }
        val olderPrompt = ChatMessage("older-prompt", MessageKind.USER, "User", "Older prompt")
        fun visibleRow() = (0 until chat.childCount).asSequence().map { chat.getChildAt(it) }
            .first { it.bottom > chat.listPaddingTop }
        fun awaitLayout() {
            val end = SystemClock.uptimeMillis() + 3_000
            while (SystemClock.uptimeMillis() < end) {
                var ready = false
                onMain { ready = !chat.isLayoutRequested && chat.height > 0 && chat.childCount > 0 }
                if (ready) return
                Thread.sleep(20)
            }
            error("Chat did not lay out the history test rows")
        }
        try {
            onMain {
                type.getDeclaredMethod("buildMain").apply { isAccessible = true }.invoke(screen)
                (field("welcome") as View).visibility = View.GONE
                chat = field("messageList") as ChatListView
                chatAdapter = field("adapter") as MessageAdapter
                chatAdapter.replace(listOf(reply))
                screen.setContentView(field("mainRoot") as View)
            }
            awaitLayout()
            onMain { chat.setSelectionFromTop(0, -137) }
            awaitLayout()
            var top = 0
            onMain {
                val row = visibleRow()
                check(chatAdapter.boundMessage(row) === reply && row.top < chat.listPaddingTop)
                top = row.top
                prependHistory.invoke(screen, listOf(olderPrompt) + tools)
            }
            awaitLayout()
            onMain {
                val row = visibleRow()
                verify("history with collapsed tools keeps the current reply at the same pixel",
                    chatAdapter.count == 3 && chatAdapter.boundMessage(row) === reply && row.top == top)
                val boundary = ChatMessage("boundary-tool", MessageKind.TOOL, "Current command", "Current output")
                chatAdapter.replace(listOf(boundary, reply))
                chat.setSelectionFromTop(0, -5)
            }
            awaitLayout()
            onMain {
                val row = visibleRow()
                check(chatAdapter.boundMessage(row)?.id == "boundary-tool" && !chatAdapter.boundGroupHeader(row))
                top = row.top
                prependHistory.invoke(screen, listOf(olderPrompt) + tools)
            }
            awaitLayout()
            onMain {
                val row = visibleRow()
                verify("a tool that becomes a group header keeps its position",
                    chatAdapter.boundMessage(row)?.id == "boundary-tool" && chatAdapter.boundGroupHeader(row) && row.top == top)
                val expandedTool = ChatMessage("expanded-tool", MessageKind.TOOL, "Command output",
                    (1..80).joinToString("\n") { "Output line $it" })
                chatAdapter.replace(listOf(expandedTool, reply))
                chatAdapter.reveal(expandedTool.id)
                chat.setSelectionFromTop(0, -137)
            }
            awaitLayout()
            onMain {
                val row = visibleRow()
                check(chatAdapter.boundMessage(row)?.id == "expanded-tool")
                check(chatAdapter.messageBody(row)?.visibility == View.VISIBLE)
                top = row.top
                prependHistory.invoke(screen, listOf(olderPrompt) + tools)
            }
            awaitLayout()
            onMain {
                val row = visibleRow()
                verify("a tool remains expanded and at the same pixel when it is grouped",
                    chatAdapter.boundMessage(row)?.id == "expanded-tool" && !chatAdapter.boundGroupHeader(row) &&
                        chatAdapter.messageBody(row)?.visibility == View.VISIBLE && row.top == top)
                val extra = ChatMessage("search-hit", MessageKind.USER, "User", "Search result")
                prependHistory.invoke(screen, listOf(extra))
                chat.scrollToMessage(chatAdapter.positionOf(extra.id))
            }
            awaitLayout()
            onMain {
                verify("selecting a search result takes precedence over a pending history anchor",
                    chatAdapter.boundMessage(visibleRow())?.id == "search-hit")
            }
        } finally {
            onMain { screen.finish() }
        }
    }

    private fun checkChatReading() {
        val screen = startActivitySync(Intent().setClassName(targetContext.packageName, "com.codexrm.mobile.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val type = screen.javaClass
        fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        lateinit var chat: ListView
        lateinit var chatAdapter: MessageAdapter
        val paragraph = "A line of reading material for the rotation check, with enough words to wrap in portrait. "
        val math = (1..48).joinToString("+") { "x_{$it}^{2}" }
        val table = "| Distribution | Formula |\n| --- | --- |\n| First | ${'$'}$math${'$'} |\n| Second | ${'$'}x_2${'$'} |"
        val inline = "${'$'}$math${'$'} after the inline formula."
        val reply = ChatMessage("qa-long", MessageKind.ASSISTANT, "Codex",
            (1..20).joinToString("\n\n") { "Section $it. ${paragraph.repeat(3)}" } +
                "\n\n\$\$\n$math\n\$\$\n\n" + table + "\n\n" + inline + "\n\n" + paragraph.repeat(80))
        fun setOrientation(orientation: Int) {
            onMain { screen.requestedOrientation = orientation }
            val deadline = SystemClock.uptimeMillis() + 5000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                waitForIdleSync()
                onMain {
                    ready = if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) chat.width > chat.height &&
                        screen.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    else chat.width < chat.height && screen.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
                }
                if (!ready) Thread.sleep(35)
            }
            check(ready) { "Device did not finish rotating the chat" }
            Thread.sleep(350)
            waitForIdleSync()
        }
        fun replyBody() = chatAdapter.messageBody(chat.getChildAt(1 - chat.firstVisiblePosition)!!) as MathTextView
        fun formula(): ScrollableMathSpan {
            val text = replyBody().text as Spanned
            return text.getSpans(0, text.length, ScrollableMathSpan::class.java).single()
        }
        fun gesture(x: Float, y: Float, dx: Float, dy: Float) {
            val start = SystemClock.uptimeMillis()
            fun send(action: Int, step: Int) {
                val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x + dx * step / 12, y + dy * step / 12, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { check(uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            send(MotionEvent.ACTION_DOWN, 0)
            for (step in 1..12) {
                send(MotionEvent.ACTION_MOVE, step)
                Thread.sleep(18)
            }
            send(MotionEvent.ACTION_UP, 12)
            waitForIdleSync()
        }
        try {
            onMain {
                screen.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                type.getDeclaredMethod("buildMain").apply { isAccessible = true }.invoke(screen)
                field("mainVisible").setBoolean(screen, true)
                (field("welcome").get(screen) as View).visibility = View.GONE
                chat = field("messageList").get(screen) as ListView
                chatAdapter = field("adapter").get(screen) as MessageAdapter
                chatAdapter.replace(listOf(ChatMessage("qa-head", MessageKind.USER, "User", "Prompt"), reply,
                    ChatMessage("qa-tail", MessageKind.ASSISTANT, "Codex", paragraph.repeat(65))))
                screen.setContentView(field("mainRoot").get(screen) as View)
                chat.setSelectionFromTop(1, 0)
            }
            setOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            var formulaX = 0f
            var formulaY = 0f
            val formulaBounds = Rect()
            onMain {
                val body = replyBody()
                val span = formula()
                check(span.drawable.hasResult()) { "Chat formula was not rendered" }
                val text = body.text as Spanned
                val layout = body.layout
                val line = layout.getLineForOffset(text.getSpanStart(span))
                val formulaTop = body.top + body.totalPaddingTop + layout.getLineTop(line)
                chat.setSelectionFromTop(1, -formulaTop + chat.height / 3)
            }
            Thread.sleep(220)
            waitForIdleSync()
            onMain {
                val body = replyBody()
                val span = formula()
                val xy = IntArray(2).also(body::getLocationOnScreen)
                check(span.scrollRange > 0 && !span.viewport.isEmpty) { "Scrolled-to formula is not overflowing or drawn" }
                check(body.isHardwareAccelerated)
                val textX = xy[0] + body.totalPaddingLeft - body.scrollX
                val textY = xy[1] + body.totalPaddingTop - body.scrollY
                formulaX = textX + span.viewport.centerX()
                formulaY = textY + span.viewport.centerY()
                formulaBounds.set(textX + span.viewport.left.toInt(), textY + span.viewport.top.toInt(),
                    textX + span.viewport.right.toInt(), textY + span.viewport.top.toInt() + span.drawable.bounds.height())
            }
            fun formulaPixels(bounds: Rect = formulaBounds): IntArray {
                Thread.sleep(150) // Allow HWUI to present the post-gesture frame.
                val display = checkNotNull(uiAutomation.takeScreenshot())
                return try {
                    val pixels = IntArray(bounds.width() * bounds.height())
                    display.getPixels(pixels, 0, bounds.width(), bounds.left, bounds.top,
                        bounds.width(), bounds.height())
                    pixels
                } finally { display.recycle() }
            }
            val beforePan = formulaPixels()
            val distance = chat.width * .32f
            gesture(formulaX, formulaY, -distance, distance * .18f)
            onMain { verify("a diagonal left swipe pans a long formula inside the real, scrolled chat ListView", formula().offset > distance / 2) }
            val afterPan = formulaPixels()
            verify("left swipe redraws the formula glyphs on the actual hardware-rendered screen",
                beforePan.indices.count { beforePan[it] != afterPan[it] } > 100)
            gesture(formulaX, formulaY, distance, 0f)
            onMain {
                verify("right swipe returns the chat formula to the start without opening the sidebar", formula().offset < 2f && !field("sidebarOpen").getBoolean(screen))
            }
            verify("right swipe restores the original visible formula pixels", beforePan.contentEquals(formulaPixels()))
            onMain {
                // A ListView in a fling can consume DOWN without delivering it to the text child.
                val chatRoot = field("mainRoot").get(screen) as View
                val xy = IntArray(2).also(chatRoot::getLocationOnScreen)
                chat.fling(1400)
                val t = SystemClock.uptimeMillis()
                for ((step, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).withIndex()) {
                    val event = MotionEvent.obtain(t, t + step * 16L, action,
                        formulaX - xy[0] - if (step == 0) 0f else distance, formulaY - xy[1], 0)
                    try { chatRoot.dispatchTouchEvent(event) } finally { event.recycle() }
                }
                val span = formula()
                val panned = span.offset > distance / 2
                replyBody().panOverflow(span, -Float.MAX_VALUE)
                verify("the formula pans even when ListView begins the gesture in a fling", panned)
            }
            val topBefore = replyBodyOnMainTop(chat)
            gesture(formulaX, formulaY, 0f, -distance)
            onMain { verify("a vertical drag starting on the formula scrolls the chat", chat.getChildAt(0).top < topBefore && formula().offset < 2f) }

            fun <T : HorizontalOverflow> overflowSpans(type: Class<T>): List<T> {
                val text = replyBody().text as Spanned
                return text.getSpans(0, text.length, type).sortedBy(text::getSpanStart)
            }
            fun bringIntoView(span: HorizontalOverflow) {
                onMain {
                    chat.smoothScrollBy(0, 0)
                    val body = replyBody()
                    val line = body.layout.getLineForOffset((body.text as Spanned).getSpanStart(span))
                    val top = body.top + body.totalPaddingTop + body.layout.getLineTop(line)
                    chat.setSelectionFromTop(1, chat.height / 4 - top)
                }
                Thread.sleep(220)
                waitForIdleSync()
            }
            fun boundsOf(span: HorizontalOverflow): Rect {
                val bounds = Rect()
                onMain {
                    val body = replyBody()
                    span.viewport.roundOut(bounds)
                    val xy = IntArray(2).also(body::getLocationOnScreen)
                    bounds.offset(xy[0] + body.totalPaddingLeft - body.scrollX,
                        xy[1] + body.totalPaddingTop - body.scrollY)
                }
                return bounds
            }

            lateinit var tableRows: List<MathTableRow>
            lateinit var inlineFormula: InlineMathSpan
            onMain {
                tableRows = overflowSpans(MathTableRow::class.java)
                inlineFormula = overflowSpans(InlineMathSpan::class.java).single()
            }
            bringIntoView(tableRows.first())
            val headerBounds = boundsOf(tableRows.first())
            val cellBounds = boundsOf(tableRows[1])
            onMain {
                val table = tableRows.first().table
                verify("math table uses aligned columns with room for the widest formula",
                    tableRows.size == 3 && tableRows.all { it.table === table } &&
                        table.widths[1] >= inlineFormula.drawable.bounds.width() + table.padding * 2 &&
                        tableRows.zipWithNext().all { (a, b) -> a.viewport.bottom <= b.viewport.top })
            }
            val beforeHeader = formulaPixels(headerBounds)
            val beforeCell = formulaPixels(cellBounds)
            gesture(cellBounds.exactCenterX(), cellBounds.exactCenterY(), -distance, 0f)
            val afterHeader = formulaPixels(headerBounds)
            val afterCell = formulaPixels(cellBounds)
            onMain {
                verify("swiping one cell pans all rows of the table and redraws them",
                    tableRows.first().table.offset > distance / 2 && !beforeHeader.contentEquals(afterHeader) &&
                        !beforeCell.contentEquals(afterCell))
            }
            bringIntoView(inlineFormula)
            val inlineBounds = boundsOf(inlineFormula)
            val beforeInline = formulaPixels(inlineBounds)
            gesture(inlineBounds.exactCenterX(), inlineBounds.exactCenterY(), -distance, 0f)
            val afterInline = formulaPixels(inlineBounds)
            verify("an overflowing inline formula redraws as it pans inside the chat",
                beforeInline.indices.count { beforeInline[it] != afterInline[it] } > 100)

            var anchor = 0
            fun screenLine(offset: Int): Int {
                val body = replyBody()
                return chat.getChildAt(1 - chat.firstVisiblePosition).top + body.top + body.totalPaddingTop -
                    body.scrollY + body.layout.getLineTop(body.layout.getLineForOffset(offset))
            }
            onMain {
                chat.smoothScrollBy(0, 0)
                val body = replyBody()
                // Choose the start of a visual line, not a paragraph boundary, so it must reflow.
                anchor = body.layout.getLineStart(43)
                val row = chat.getChildAt(1 - chat.firstVisiblePosition)
                chat.setSelectionFromTop(1, row.top - screenLine(anchor) - 8)
            }
            Thread.sleep(220)
            onMain { check(screenLine(anchor) == chat.paddingTop - 8) { "Portrait anchor setup: ${screenLine(anchor)}" } }
            setOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            onMain {
                verify("phone landscape hides the chat top bar and composer",
                    (field("topbar").get(screen) as View).visibility == View.GONE &&
                        (field("composerZone").get(screen) as View).visibility == View.GONE)
                verify("portrait to landscape keeps the same character at the top of the chat (${screenLine(anchor)} vs ${chat.paddingTop - 8})",
                    kotlin.math.abs(screenLine(anchor) - (chat.paddingTop - 8)) <= 2)
            }
            setOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            var proseX = 0f
            var proseY = 0f
            onMain {
                verify("phone portrait restores the chat top bar and composer",
                    (field("topbar").get(screen) as View).visibility == View.VISIBLE &&
                        (field("composerZone").get(screen) as View).visibility == View.VISIBLE)
                verify("landscape to portrait returns to the original line and offset",
                    kotlin.math.abs(screenLine(anchor) - (chat.paddingTop - 8)) <= 2)
                val xy = IntArray(2).also(chat::getLocationOnScreen)
                proseX = xy[0] + chat.width * .25f
                proseY = xy[1] + chat.height * .20f
            }
            gesture(proseX, proseY, distance, 0f)
            onMain {
                verify("right swipe over regular prose still opens the sidebar", field("sidebarOpen").getBoolean(screen))
                (field("sidebarShade").get(screen) as View).performClick()
                chat.setSelectionFromTop(2, -20_000)
            }
            Thread.sleep(220)
            onMain { check(chat.getChildAt(chat.childCount - 1).bottom == chat.height - chat.paddingBottom) { "Bottom anchor setup" } }
            setOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            onMain {
                verify("when following a very long last message, rotation keeps the end visible",
                    chat.lastVisiblePosition == chat.count - 1 && chat.getChildAt(chat.childCount - 1).bottom == chat.height - chat.paddingBottom)
            }
            setOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            var lastMessageLine = 0
            val longTurn = JSONObject().put("id", "qa-turn").put("status", "completed")
                .put("items", JSONArray().put(JSONObject().put("id", "qa-tail")
                    .put("type", "agentMessage").put("text", paragraph.repeat(65))))
            fun response(turns: JSONArray) = JSONObject().put("id", "qa-thread").put("turns", turns)
            val transcript = response(JSONArray().put(longTurn))
            val renderOpen = type.getDeclaredMethod("renderOpenThread", String::class.java, JSONObject::class.java,
                JSONObject::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            fun reload(thread: JSONObject) {
                renderOpen.invoke(screen, "qa-thread", JSONObject().put("nextCursor", "page-2"), thread, false)
            }
            fun tailLineTop(): Int {
                val row = chat.getChildAt(chatAdapter.positionOf("qa-tail") - chat.firstVisiblePosition) ?: return Int.MAX_VALUE
                val body = chatAdapter.messageBody(row)!!
                return row.top + body.top + body.totalPaddingTop + body.layout.getLineTop(body.layout.getLineForOffset(lastMessageLine))
            }
            onMain {
                field("currentThreadId").set(screen, "qa-thread")
                field("currentThread").set(screen, transcript)
                field("historyOlder").setBoolean(screen, true)
                field("historyCursor").set(screen, "older-page")
                @Suppress("UNCHECKED_CAST")
                (field("historyMessageIds").get(screen) as MutableSet<String>).addAll(listOf("qa-head", "qa-long"))
                val tail = chat.getChildAt(2 - chat.firstVisiblePosition)!!
                lastMessageLine = chatAdapter.messageBody(tail)!!.layout.getLineStart(40)
                chat.setSelectionFromTop(2, tail.top - tailLineTop() - 7)
            }
            waitForIdleSync()
            onMain {
                check(tailLineTop() == chat.paddingTop - 7) { "Reading position setup failed" }
                check(screen.moveTaskToBack(true))
            }
            fun awaitStarted(expected: Boolean) {
                val deadline = SystemClock.uptimeMillis() + 5000
                while (SystemClock.uptimeMillis() < deadline) {
                    var matches = false
                    onMain { matches = field("started").getBoolean(screen) == expected }
                    if (matches) return
                    Thread.sleep(50)
                }
                error("Activity started flag did not change to $expected")
            }
            awaitStarted(false)
            targetContext.startActivity(Intent().setClassName(targetContext.packageName, screen.javaClass.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitStarted(true)
            // Feed the same callback used by thread/resume after the activity returns to the foreground.
            onMain { reload(response(JSONArray().put(longTurn))) }
            waitForIdleSync()
            onMain {
                verify("a resume keeps the line and pixel offset inside the last long message",
                    tailLineTop() == chat.paddingTop - 7)
                verify("a resume keeps previously loaded history and its pagination cursor",
                    chatAdapter.getItem(0).id == "qa-head" && field("historyCursor").get(screen) == "older-page")
                chat.setSelectionFromTop(chat.count - 1, -20_000)
            }
            waitForIdleSync()
            onMain {
                check(chat.getChildAt(chat.childCount - 1).bottom == chat.height - chat.paddingBottom) { "Resume bottom setup failed" }
                val newTurn = JSONObject().put("id", "qa-new-turn").put("status", "completed")
                    .put("items", JSONArray().put(JSONObject().put("id", "qa-new")
                        .put("type", "agentMessage").put("text", paragraph.repeat(65))))
                reload(response(JSONArray().put(longTurn).put(newTurn)))
            }
            waitForIdleSync()
            onMain {
                verify("a resume follows a new long reply to its end when already at the bottom",
                    chat.lastVisiblePosition == chat.count - 1 &&
                        chat.getChildAt(chat.childCount - 1).bottom == chat.height - chat.paddingBottom)
            }
        } finally {
            onMain { screen.finish() }
        }
    }

    private fun replyBodyOnMainTop(chat: ListView): Int {
        var top = 0
        onMain { top = chat.getChildAt(0).top }
        return top
    }

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var error = ""
        try {
            check(targetContext.packageName.endsWith(".mathqa")) { "Use an isolated .mathqa application" }
            runOnMainSync {
                val context = ContextThemeWrapper(targetContext, R.style.Theme_CodexRM)
                root = Ui.column(context)
                sidebar = SidebarSwipeLayout(context, { true }, { 0f }, {}, { open -> if (open) sidebarOpens++ })
                sidebar.addView(root)
                adapter = MessageAdapter(context, MessageImageLoader(context) { ImageEnvironment("math-qa", "", null, false) })
                message = ChatMessage("math", MessageKind.ASSISTANT, "Codex", """
                    # Markdown 与 LaTeX

                    **粗体** · *斜体* · ~~删除线~~

                    行内公式：${'$'}E=mc^2${'$'}，以及 \(x_1+x_2\)。

                    多行对齐：
                    \[
                    \begin{aligned} f(x)&=\int_a^b K(x,t)\,dt \\ \lim_{n\to\infty}a_n&=0 \end{aligned}
                    \]

                    矩阵：
                    ${'$'}${'$'}A=\begin{pmatrix}1&2&3\\0&1&4\\5&6&0\end{pmatrix}${'$'}${'$'}

                    分段函数：
                    \[|x|=\begin{cases}x&x\ge0\\-x&x<0\end{cases}\]

                    超长公式一（左右滑动）：
                    \[${(1..30).joinToString("+") { "\\frac{x_{$it}^2}{1+x_{$it}}" }}=R\]

                    超长公式二：
                    \[${(1..35).joinToString("+") { "y_{$it}" }}=S\]

                    正文保持正常换行，公式拖动不会影响其他公式和正文。正文保持正常换行，公式拖动不会影响其他公式和正文。

                    [https://display.example.com](https://target.example.com) 与 https://raw.example.com

                    ```latex
                    ${'$'}literal${'$'} 与 \(code\)
                    ```

                    金额：${'$'}5 与 ${'$'}10。
                """.trimIndent())
                adapter.add(message)
                row = adapter.getView(0, null, root) as LinearLayout
                root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                draw()
            }
            Thread.sleep(1200)
            runOnMainSync {
                draw(); draw("final-native-render")
                verify("aligned, pmatrix, cases and long formulas load", spans().size == 7 && spans().all { it.drawable.hasResult() })
                verify("inline baseline preserved", spans().filterIsInstance<InlineMathSpan>().size == 2)
                val fitted = blocks().filter { it.scrollRange == 0f }
                verify("short display formulas are centered at natural size", fitted.size == 3 && fitted.all {
                    kotlin.math.abs(it.viewport.centerX() - body().formulaWidth / 2f) < 2f &&
                        it.viewport.width() == it.drawable.bounds.width().toFloat()
                })
                val overflowing = blocks().filter { it.scrollRange > 0 }
                verify("two formulas overflow at natural font size", overflowing.size == 2 && overflowing.all { it.drawable.bounds.width() > body().width })
                val first = overflowing[0]
                val second = overflowing[1]
                val height = body().height
                swipe(first, -350f)
                verify("left swipe pans formula", first.offset > 250f)
                verify("formula scrolling is independent and prose layout is stable", second.offset == 0f && body().height == height && body().scrollX == 0)
                swipe(first, 350f)
                verify("right swipe returns to formula start without opening sidebar", first.offset < 1f && sidebarOpens == 0)
                val previous = first.offset
                swipe(first, 0f, -120f)
                verify("vertical gesture does not pan formula", first.offset == previous)
                // A real scrolling parent must regain vertical gestures after direction detection.
                sidebar.removeView(root)
                val vertical = ScrollView(root.context).apply { addView(root) }
                sidebar.addView(vertical)
                sidebar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
                sidebar.layout(0, 0, width, 600)
                vertical.scrollTo(0, row.top + body().top + first.viewport.top.toInt() - 250)
                val scrollBefore = vertical.scrollY
                swipe(first, 0f, -240f)
                verify("vertical gesture scrolls the message parent", vertical.scrollY > scrollBefore && first.offset == previous)
                sidebar.removeView(vertical); vertical.removeView(root); sidebar.addView(root)
                draw()
                first.panBy(Float.MAX_VALUE)
                draw("scrolled-end")
                verify("formula end is reachable", first.offset == first.scrollRange && first.offset > 1000)
                width = 720
                draw(); draw()
                verify("narrow viewport preserves overflow", first.viewport.width() < 720 && first.scrollRange > 0 && first.offset <= first.scrollRange)
                width = 1080
                draw(); draw()
                val text = body().text as Spanned
                val links = text.getSpans(0, text.length, URLSpan::class.java).map { it.url }
                verify("Markdown links and plain URLs survive", links.contains("https://target.example.com") && links.contains("https://raw.example.com") && !links.contains("https://display.example.com"))
                verify("code and currency stay literal", text.contains("\$literal\$") && text.contains("\$5 与 \$10"))
                val old = spans().map { it.drawable }
                message.change(body = "\$\$\n\\begin{pmatrix}1&2\\\\3&4\\end{pmatrix}")
                rebind()
                verify("streaming incomplete source is preserved and old callbacks detach", body().text.toString() == message.body && spans().isEmpty() && old.none { it.isAttached })
                message.change(body = message.body + "\n\$\$")
                rebind()
            }
            Thread.sleep(700)
            runOnMainSync {
                draw(); draw()
                verify("streaming matrix renders when closed with reset scroll state", blocks().single().drawable.hasResult() && blocks().single().offset == 0f)
                val old = spans().map { it.drawable }
                val tool = ChatMessage("tool", MessageKind.TOOL, "Command", "# literal\n\$HOME\n\\(source\\)")
                adapter.replace(listOf(tool)); rebind()
                verify("collapsed tool releases formulas", old.none { it.isAttached } && body().visibility == View.GONE)
                row.getChildAt(0).performClick(); rebind()
                verify("expanded tool output stays literal", body().text.toString() == tool.body && spans().isEmpty())
                adapter.replace(listOf(message))
                verify("new adapter data clears colored span cache", message.rendered == null)
            }
            checkStreamingRows()
            checkHistoryPrepend()
            checkChatReading()
        } catch (failure: Throwable) { error = failure.stackTraceToString() }
        val result = JSONObject().put("checks", checks).put("error", error)
        File(targetContext.getExternalFilesDir(null), "final-native-results.json").writeText(result.toString(2))
        finish(if (error.isEmpty()) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("result", result.toString()) })
    }
}
