package com.codexrm.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object VisualizationRenderingCheck {
    fun run(instrumentation: Instrumentation, liveChart: Boolean) = with(instrumentation) {
        val output = Bundle()
        var activity: Activity? = null
        var loader: MessageImageLoader? = null
        try {
            check(targetContext.packageName.endsWith(".imageqa")) { "Use -Pcodexrm.imageQa" }
            val screen = startActivitySync(Intent().setClassName(targetContext.packageName, MainActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).also { activity = it }
            val fragment = """
                <button id="mark" class="btn" data-tooltip="Point: 12" data-tooltip-placement="bottom" style="margin:12px 0 0 12px">Filter</button>
                <i data-lucide="circle-check"></i><div id="chart"></div>
                <script>
                let taps = 0, opaque = false, networkDenied = false, state = false, clickInfo;
                try { parent.document.body; } catch { opaque = true; }
                document.querySelector('#mark').onclick = async event => {
                  clickInfo = [event.pointerType, event.detail];
                  taps++;
                  await openai.setWidgetState({privateContent:{taps}});
                  state = openai.widgetState.privateContent.taps === taps;
                };
                fetch('https://example.invalid/codexrm-visualization-qa').catch(() => {networkDenied = true;});
                function renderedChart(root) {
                  return !!root.querySelector('svg') || [...root.querySelectorAll('*')].some(node =>
                    node.shadowRoot && renderedChart(node.shadowRoot));
                }
                addEventListener('message', event => {
                  if (event.source !== parent || event.data !== 'visualization-qa') return;
                  const r = document.querySelector('#mark').getBoundingClientRect();
                  parent.postMessage({test: 'visualization-qa', taps, opaque, networkDenied, state,
                    theme: openai.visualizationTheme, size: openai.visualizationStyleVariables['--font-size-base'],
                    tooltip: document.querySelector('[role=tooltip]')?.textContent,
                    icon: !!document.querySelector('svg.lucide'),
                    floating: [!!window.FloatingUICore, !!window.FloatingUIDOM], clickInfo,
                    chart: renderedChart(document.querySelector('#chart')),
                    chartStatus: document.querySelector('#chart')?.firstElementChild?.shadowRoot?.textContent?.slice(-180),
                    x: r.x+r.width/2, y: r.y+r.height/2,
                    width: innerWidth}, '*');
                });
                </script>
            """.trimIndent() + if (liveChart) """
                <script type="module">
                import {mountChart} from 'https://persistent.oaistatic.com/plugins/visualize/charts/1.8.1/element.js';
                mountChart(document.querySelector('#chart'), {id:'qa', title:'Monthly usage',
                  spec:{type:'area',x:'month',y:'usage',yLabel:'GB'},columns:['month','usage'],rows:[['Jan',12],['Feb',18],['Mar',31]]});
                </script>
            """.trimIndent() else ""
            lateinit var row: ViewGroup
            lateinit var web: WebView
            var reads = 0
            val path = "/workspace/monthly-usage.html"
            main(this) {
                val imageLoader = MessageImageLoader(screen) { ImageEnvironment("visualization-qa", "/workspace", null, false) }
                    .also { loader = it }
                val adapter = MessageAdapter(screen, imageLoader, loadVisualization = { ref, done ->
                    check(ref.path == path)
                    reads++
                    done(fragment, null)
                })
                val normal = "\uE200visualize\uE202" + JSONObject().put("path", path) + "\uE201"
                val wideRef = "\uE200visualize\uE202" + JSONObject().put("path", path).put("mode", "wide") + "\uE201"
                adapter.replace(listOf(ChatMessage("qa", MessageKind.ASSISTANT, "Codex", "说明\n$normal\n\n$wideRef\n\n结论")))
                row = adapter.getView(0, null, Ui.column(screen)) as ViewGroup
                screen.setContentView(row)
                val body = checkNotNull(adapter.messageBody(row))
                val text = body.text as Spanned
                check("说明" in text && "结论" in text && "展开互动图表" in text && "查看宽幅图表" in text)
                check(path !in text)
                val links = text.getSpans(0, text.length, ClickableSpan::class.java).sortedBy(text::getSpanStart)
                check(links.size == 2)
                check(reads == 0)
                links[0].onClick(body)
                check(reads == 1)
                web = checkNotNull(findWeb(row))
                check(!web.settings.allowFileAccess && !web.settings.allowContentAccess && web.settings.blockNetworkLoads)
                links[1].onClick(body)
                check(reads == 2 && findWeb(row) == null) { "wide did not move into fullscreen" }
            }
            // Close fullscreen, then exercise the reparented WebView with a real touch event.
            waitForIdleSync()
            SystemClock.sleep(250)
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            var restored: WebView? = null
            for (attempt in 0 until 20) {
                main(this) { restored = findWeb(row) }
                if (restored != null) break
                SystemClock.sleep(100)
            }
            web = checkNotNull(restored) { "fullscreen did not return the WebView to chat" }
            var observed: JSONObject? = null
            val deadline = SystemClock.uptimeMillis() + 25_000
            while (SystemClock.uptimeMillis() < deadline) {
                eval(this, web, """if(!window.qaHook){window.qaHook=true;addEventListener('message', e=>{if(e.origin==='null' && e.source===document.querySelector('iframe').contentWindow && e.data?.test==='visualization-qa')window.qa=e.data})};document.querySelector('iframe')?.contentWindow.postMessage('visualization-qa','*'); null""")
                val value = eval(this, web, "window.qa || null")
                if (value.startsWith("{") && JSONObject(value).let { it.optBoolean("icon") && (!liveChart || it.optBoolean("chart")) }) {
                    observed = JSONObject(value)
                    break
                }
                Thread.sleep(180)
            }
            val first = checkNotNull(observed) { "Chart assets did not initialize: ${eval(this, web, "window.qa || null")}" }
            check(first.optBoolean("opaque") && first.optBoolean("networkDenied")) { "iframe was not isolated: $first" }
            check(first.optString("size") == "14px" && first.optString("theme") == if (Ui.dark) "dark" else "light")
            check(eval(this, web, "document.querySelector('iframe').getAttribute('sandbox')") == "\"allow-scripts\"")
            val location = IntArray(2)
            var scale = 1.0
            main(this) { web.getLocationOnScreen(location); scale = web.width / first.getDouble("width") }
            val x = (location[0] + first.getDouble("x") * scale).toFloat()
            val y = (location[1] + first.getDouble("y") * scale).toFloat()
            val start = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = .15f }
                val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, 1, arrayOf(pointer), arrayOf(coords),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                sendPointerSync(event)
                event.recycle()
            }
            var touched = JSONObject()
            for (attempt in 0 until 15) {
                eval(this, web, "document.querySelector('iframe').contentWindow.postMessage('visualization-qa','*'); null")
                Thread.sleep(100)
                touched = JSONObject(eval(this, web, "window.qa || {}"))
                if (touched.optBoolean("state") && touched.optString("tooltip") == "Point: 12") break
            }
            check(touched.optInt("taps") == 1 && touched.optBoolean("state") && touched.optString("tooltip") == "Point: 12") { "touch failed: $touched" }
            main(this) { screen.setContentView(View(screen)); check(findWeb(row) == null) }
            output.putString("stream", "\nVisualization: chat links, wide view, opaque frame, theme, Lucide, touch tooltip/state, teardown; liveChart=$liveChart\n")
            finish(Activity.RESULT_OK, output)
        } catch (failure: Throwable) {
            output.putString("stream", "\n${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, output)
        } finally {
            main(this) { loader?.close(); activity?.finish() }
        }
    }

    private fun main(test: Instrumentation, action: () -> Unit) {
        var result: Result<Unit>? = null
        test.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }

    private fun eval(test: Instrumentation, web: WebView, js: String): String {
        val done = CountDownLatch(1)
        var result = "null"
        test.runOnMainSync { web.evaluateJavascript(js) { result = it; done.countDown() } }
        check(done.await(3, TimeUnit.SECONDS)) { "WebView evaluateJavascript timed out" }
        return result
    }

    private fun findWeb(view: View): WebView? = if (view is WebView) view else (view as? ViewGroup)?.let { group ->
        (0 until group.childCount).firstNotNullOfOrNull { findWeb(group.getChildAt(it)) }
    }
}
