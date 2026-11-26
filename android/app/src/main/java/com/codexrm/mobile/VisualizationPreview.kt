package com.codexrm.mobile

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

internal typealias VisualizationLoader = (VisualizationRef, (String?, String?) -> Unit) -> Unit

internal class MessagePreviews(val diagram: InlineMermaid, val visualization: InlineVisualization) {
    fun clear() { diagram.clear(); visualization.clear() }
}

internal object VisualizationPreview {
    const val MAX_BYTES = 1 shl 20
    private const val MARKER = "<!--__INLINE_VISUALIZATION_FRAGMENT__-->"
    private const val SHELL = "https://visualize.codexrm.invalid/index.html"
    private const val ASSETS = "https://visualize-assets.codexrm.invalid/"
    private const val CHARTS = "https://persistent.oaistatic.com/plugins/visualize/charts/"
    private val allowedChartResource = Regex("${Regex.escape(CHARTS)}(?:[0-9]{1,3}(?:\\.[0-9]{1,3}){2}|[0-9a-f]{64})/(?:element|runtime)\\.js")
    private val bundledScripts = setOf("floating-core.js", "floating-dom.js", "lucide.js")
    private val chartClient by lazy {
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(8, TimeUnit.SECONDS).build()
    }

    internal fun allowsChartResource(url: String) = allowedChartResource.matches(url)

    private fun chartScript(url: String): WebResourceResponse? = runCatching {
        chartClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val body = response.body ?: return null
            if (response.code != 200 || body.contentType()?.subtype !in setOf("javascript", "ecmascript")) return null
            val limit = 3L shl 20
            val source = body.source()
            if (body.contentLength() > limit || source.request(limit + 1)) return null
            scriptResponse(source.readByteArray().inputStream())
        }
    }.getOrNull()

    private fun scriptResponse(stream: java.io.InputStream) = WebResourceResponse(
        "application/javascript", "utf-8", 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), stream,
    )

    internal fun html(context: Context, fragment: String, dark: Boolean): String {
        require(fragment.isNotBlank() && fragment.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        fun asset(file: String) = context.assets.open("visualize/$file").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val theme = if (dark) "dark" else "light"
        val csp = "default-src 'none'; script-src 'unsafe-inline' $ASSETS $CHARTS; " +
            "style-src 'unsafe-inline'; img-src data: blob:; font-src data:; media-src data: blob:; " +
            "connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none';"
        val hostGlobals = """
            (() => {
              let state = null;
              const globals = {
                theme: '$theme', visualizationTheme: '$theme',
                visualizationStyleVariables: Object.freeze({'--font-size-base': '14px'}),
                get widgetState() { return state; },
                statePersistence: 'none', stateModelContext: 'none',
                async setWidgetState(value) {
                  const json = JSON.stringify(value ?? null);
                  if (json.length > 32768) throw new Error('Widget state is too large');
                  state = JSON.parse(json);
                  dispatchEvent(new CustomEvent('openai:set_globals', {detail: {globals}}));
                }
              };
              window.openai = Object.freeze(globals);
              document.documentElement.style.setProperty('--font-size-base', globals.visualizationStyleVariables['--font-size-base']);
              addEventListener('DOMContentLoaded', () =>
                dispatchEvent(new CustomEvent('openai:set_globals', {detail: {globals}})), {once:true});
            })();
        """.trimIndent()
        val template = asset("visualize.html")
        check(template.startsWith(MARKER))
        val runtime = fragment + template.removePrefix(MARKER)
        val inner = """<!doctype html><html data-theme="$theme"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="referrer" content="no-referrer">
            <meta http-equiv="Content-Security-Policy" content="$csp frame-src 'none'">
            <style>${asset("visualize.css")}</style><script>$hostGlobals</script>
            </head><body>$runtime</body></html>"""
        // srcdoc is an HTML attribute, not a script/template literal. The untrusted
        // fragment cannot break into the outer document. Never add allow-same-origin.
        return """<!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="referrer" content="no-referrer">
            <meta http-equiv="Content-Security-Policy" content="$csp frame-src 'self'">
            <style>html,body{margin:0;height:100%;overflow:hidden;color-scheme:$theme}iframe{display:block;width:100%;height:100%;border:0}</style>
            </head><body><iframe title="互动图表" sandbox="allow-scripts" referrerpolicy="no-referrer" srcdoc="${attribute(inner)}"></iframe></body></html>"""
    }

    private fun attribute(value: String) = buildString(value.length) {
        for (char in value) append(when (char) { '&' -> "&amp;"; '"' -> "&quot;"; '<' -> "&lt;"; '>' -> "&gt;"; else -> char })
    }

    fun view(context: Context, fragment: String, dark: Boolean, failed: (WebView) -> Unit): WebView {
        val page = html(context, fragment, dark).toByteArray(Charsets.UTF_8)
        return WebView(context).apply {
            setBackgroundColor(Ui.BACKGROUND)
            contentDescription = "互动图表"
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.domStorageEnabled = false
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val url = request.url.toString()
                    if (request.method == "GET") {
                        if (request.isForMainFrame && url == SHELL) return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(page))
                        if (!request.isForMainFrame) {
                            val file = url.removePrefix(ASSETS)
                            if (url.startsWith(ASSETS) && file in bundledScripts) {
                                return scriptResponse(context.assets.open("visualize/$file"))
                            }
                            // Fetch pinned modules without forwarding WebView headers/cookies or following redirects.
                            if (allowsChartResource(url)) chartScript(url)?.let { return it }
                        }
                    }
                    return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    failed(view)
                    return true
                }
            }
            loadUrl(SHELL)
        }
    }
}

internal class InlineVisualization(private val panel: LinearLayout, private val load: VisualizationLoader) {
    private var active: VisualizationRef? = null
    private var session = 0L
    private var web: WebView? = null
    private var fullScreen: PredictiveBackDialog? = null

    fun toggle(ref: VisualizationRef) {
        val wasOpen = active == ref
        clear()
        if (wasOpen) return
        active = ref
        val request = session
        val context = panel.context
        panel.visibility = View.VISIBLE
        val controls = Ui.column(context, false)
        val status = Ui.text(context, "正在读取互动图表…", 12f, Ui.MUTED)
        panel.addView(controls, LinearLayout.LayoutParams(-1, -2))
        panel.addView(status, LinearLayout.LayoutParams(-1, -2).apply { setMargins(Ui.dp(context, 12), 0, 0, 0) })
        controls.addView(Ui.button(context, "收起图表") { clear() })
        load(ref) { html, error ->
            if (session != request) return@load
            if (html.isNullOrBlank() || html.toByteArray(Charsets.UTF_8).size > VisualizationPreview.MAX_BYTES) {
                status.text = error?.take(180) ?: "互动图表文件不可用或超出 1 MiB"
                controls.addView(Ui.button(context, "重试") { clear(); toggle(ref) })
                return@load
            }
            val mount = FrameLayout(context)
            val preview = runCatching {
                VisualizationPreview.view(context, html, Ui.dark) { crashed ->
                    if (web === crashed) {
                        web = null
                        (crashed.parent as? ViewGroup)?.removeView(crashed)
                        crashed.destroy()
                        fullScreen?.dismiss()
                        mount.visibility = View.GONE
                        status.text = "图表渲染已停止，请重新打开"
                        status.visibility = View.VISIBLE
                    }
                }
            }.getOrElse {
                Log.w("VisualizationPreview", "Cannot start visualization WebView", it)
                Log.w("VisualizationPreview", "Cannot start visualization WebView", it)
                status.text = "无法启动图表预览"
                return@load
            }
            web = preview
            status.visibility = View.GONE
            mount.addView(preview, FrameLayout.LayoutParams(-1, -1))
            panel.addView(mount, LinearLayout.LayoutParams(-1, Ui.dp(context, 340)))
            controls.addView(Ui.button(context, "全屏") { openFullScreen(ref, preview, mount) })
            if (ref.wide) openFullScreen(ref, preview, mount)
        }
    }

    private fun openFullScreen(ref: VisualizationRef, preview: WebView, mount: FrameLayout) {
        if (fullScreen != null || web !== preview || !panel.isAttachedToWindow) return
        val context = panel.context
        val dialog = PredictiveBackDialog(context, android.R.style.Theme_Material_NoActionBar)
        val content = Ui.column(context).apply { setBackgroundColor(Ui.BACKGROUND) }
        val toolbar = Ui.column(context, false)
        toolbar.addView(Ui.iconButton(context, "back", "关闭图表") { dialog.dismiss() })
        toolbar.addView(Ui.text(context, ref.title.ifBlank { "互动图表" }, 16f))
        content.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        mount.removeView(preview)
        content.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setBackContent(content)
        dialog.setOnDismissListener {
            if (fullScreen === dialog) fullScreen = null
            if (web === preview) {
                content.removeView(preview)
                mount.addView(preview, FrameLayout.LayoutParams(-1, -1))
            }
        }
        fullScreen = dialog
        dialog.show()
    }

    fun clear() {
        session++
        active = null
        val view = web
        web = null
        val dialog = fullScreen
        fullScreen = null
        dialog?.dismiss()
        (view?.parent as? ViewGroup)?.removeView(view)
        view?.destroy()
        panel.removeAllViews()
        panel.visibility = View.GONE
    }
}
