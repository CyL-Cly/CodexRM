package com.codexrm.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject

internal object MermaidPreview {
    const val MAX_SOURCE_LENGTH = 50_000
    private const val origin = "https://mermaid.codexrm.invalid/"
    private val assets = mapOf("index.html" to "text/html", "preview.js" to "application/javascript",
        "mermaid.min.js" to "application/javascript")

    fun view(context: Context, source: String, dark: Boolean, zoom: Boolean): WebView = WebView(context).apply {
        setBackgroundColor(Ui.BACKGROUND)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        settings.builtInZoomControls = zoom
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url.toString()
                val file = url.removePrefix(origin)
                val mime = assets[file]
                if (!url.startsWith(origin) || mime == null) return WebResourceResponse("text/plain", "UTF-8", null)
                return WebResourceResponse(mime, "UTF-8", context.assets.open("mermaid/$file"))
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url == "${origin}index.html") view.evaluateJavascript(
                    "window.showDiagram(${JSONObject.quote(source)}, $dark)", null,
                )
            }
        }
        loadUrl("${origin}index.html")
    }

    fun show(context: Context, source: String, dark: Boolean) {
        val dialog = PredictiveBackDialog(context, android.R.style.Theme_Material_NoActionBar)
        val content = Ui.column(context).apply { setBackgroundColor(Ui.BACKGROUND) }
        val controls = Ui.column(context, false)
        val holder = FrameLayout(context)
        val canPreview = source.length <= MAX_SOURCE_LENGTH
        val web = if (canPreview) view(context, source, dark, zoom = true) else null
        val code = ScrollView(context).apply {
            addView(Ui.text(context, source, 14f, Ui.TEXT).apply {
                typeface = Typeface.MONOSPACE
                textSize = 14f
                setTextIsSelectable(true)
                val padding = Ui.dp(context, 16)
                setPadding(padding, padding, padding, padding)
            })
        }
        fun display(preview: Boolean) {
            web?.visibility = if (preview) View.VISIBLE else View.GONE
            code.visibility = if (preview) View.GONE else View.VISIBLE
        }
        controls.addView(Ui.iconButton(context, "back", "关闭图表") { dialog.dismiss() })
        controls.addView(Ui.button(context, "图表") { display(true) }.apply { isEnabled = canPreview })
        controls.addView(Ui.button(context, "源码") { display(false) })
        controls.addView(Ui.iconButton(context, "copy", "复制图表源码") {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Mermaid", source))
        })
        if (web != null) holder.addView(web, FrameLayout.LayoutParams(-1, -1))
        holder.addView(code, FrameLayout.LayoutParams(-1, -1))
        content.addView(controls, LinearLayout.LayoutParams(-1, -2))
        content.addView(holder, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setBackContent(content)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener {
            holder.removeAllViews()
            web?.destroy()
        }
        dialog.show()
        display(canPreview)
    }
}

internal class InlineMermaid(private val panel: LinearLayout) {
    private var source: String? = null
    private var preview: WebView? = null

    fun toggle(code: String) {
        val context = panel.context
        if (code.length > MermaidPreview.MAX_SOURCE_LENGTH) {
            MermaidPreview.show(context, code, Ui.dark)
            return
        }
        val wasOpen = code == source
        clear()
        if (wasOpen) return
        source = code
        val toolbar = Ui.column(context, false)
        toolbar.addView(Ui.button(context, "全屏") { MermaidPreview.show(context, code, Ui.dark) })
        toolbar.addView(Ui.button(context, "收起图表") { clear() })
        panel.addView(toolbar)
        val web = MermaidPreview.view(context, code, Ui.dark, zoom = false).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
        preview = web
        panel.addView(web, LinearLayout.LayoutParams(-1, Ui.dp(context, 240)))
        panel.visibility = View.VISIBLE
    }

    fun clear() {
        panel.removeAllViews()
        panel.visibility = View.GONE
        preview?.destroy()
        preview = null
        source = null
    }
}
