package com.codexrm.mobile

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.text.Spanned
import android.text.style.URLSpan
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import io.noties.markwon.image.AsyncDrawableSpan
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Run against an isolated, minified .mathqa build to cover reflection and native gestures. */
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

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var error = ""
        try {
            check(targetContext.packageName.endsWith(".mathqa")) { "Use an isolated .mathqa application" }
            runOnMainSync {
                val context = ContextThemeWrapper(targetContext, R.style.Theme_CodexRM)
                root = Ui.column(context)
                sidebar = SidebarSwipeLayout(context, { true }, { sidebarOpens++ })
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
                verify("aligned, pmatrix, cases and long formulas load in minified Release", spans().size == 7 && spans().all { it.drawable.hasResult() })
                verify("inline baseline preserved", spans().filterIsInstance<InlineMathSpan>().size == 2)
                val overflowing = blocks().filter { it.scrollRange > 0 }
                verify("two formulas overflow at natural font size", overflowing.size == 2 && overflowing.all { it.drawable.bounds.width() > body().width })
                val first = overflowing[0]
                val second = overflowing[1]
                val height = body().height
                swipe(first, -350f)
                verify("left swipe pans formula", first.offset > 250f)
                verify("formula scrolling is independent and prose layout is stable", second.offset == 0f && body().height == height && body().scrollX == 0)
                swipe(first, 350f)
                verify("right swipe returns to formula start without opening sidebar", first.offset == 0f && sidebarOpens == 0)
                val previous = first.offset
                swipe(first, 0f, -120f)
                verify("vertical gesture does not pan formula", first.offset == previous)
                // A real scrolling parent must regain vertical gestures after direction detection.
                sidebar.removeView(root)
                val vertical = ScrollView(root.context).apply { addView(root) }
                sidebar.addView(vertical)
                sidebar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY))
                sidebar.layout(0, 0, width, 1200)
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
        } catch (failure: Throwable) { error = failure.stackTraceToString() }
        val result = JSONObject().put("checks", checks).put("error", error)
        File(targetContext.getExternalFilesDir(null), "final-native-results.json").writeText(result.toString(2))
        finish(if (error.isEmpty()) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("result", result.toString()) })
    }
}
