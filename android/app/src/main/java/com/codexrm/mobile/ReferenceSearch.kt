package com.codexrm.mobile

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

internal enum class SearchKind(val title: String, val icon: String) {
    ALL("全部", "search"), CHAT("聊天", "chat"), PROJECT("项目", "folder"), IMAGE("图像", "image"), FILE("文档", "file")
}
internal data class SearchResult(val id: String, val kind: SearchKind, val name: String, val excerpt: String, val open: () -> Unit)

internal object ReferenceUi {
    val card get() = if (Ui.dark) 0xff2e2e2e.toInt() else Ui.SURFACE
    val raised get() = if (Ui.dark) 0xff404040.toInt() else Ui.SURFACE_ALT
    val border get() = if (Ui.dark) 0xff4b4b4b.toInt() else Ui.BORDER
    val blue get() = 0xff345fe5.toInt()
    fun circle(activity: Activity, icon: String, title: String, size: Int = 48, action: () -> Unit): FrameLayout =
        Ui.iconButton(activity, icon, title, size) { action() }.apply {
            background = Ui.ripple(card, Ui.dp(context, size / 2).toFloat(), border)
            val side = Ui.dp(context, if (size >= 48) 23 else 20)
            (getChildAt(0) as IconView).apply {
                setColor(Ui.TEXT)
                layoutParams = FrameLayout.LayoutParams(side, side, Gravity.CENTER)
            }
        }
}

internal class ReferenceSearch(
    private val activity: Activity,
    private val local: (String) -> List<SearchResult>,
    private val fetch: (String, String, (List<SearchResult>, String, String) -> Unit) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val dialog = PredictiveBackDialog(activity)
    private val view = Ui.column(activity).apply { setBackgroundColor(AppearanceArea.CHAT.baseColor()) }
    private val tabs = Ui.column(activity, false).apply { gravity = Gravity.CENTER_VERTICAL }
    private val panel = FrameLayout(activity)
    private val list = Ui.column(activity)
    private val scroll = ScrollView(activity).apply { isFillViewport = false; addView(list) }
    private val input = Ui.input(activity, "搜索").apply {
        isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH; background = null; textSize = 17f
        setPadding(Ui.dp(context, 12), 0, 0, 0)
        filters = arrayOf(android.text.InputFilter.LengthFilter(250))
    }
    private val remote = linkedMapOf<String, SearchResult>()
    private var current = SearchKind.ALL
    private var sequence = 0
    private var next = ""
    private var busy = false
    private var issue = ""
    private var term = ""
    private val search = Runnable { runSearch() }
    private val clear = Ui.iconButton(activity, "close", "清除搜索关键词", 38) { input.setText("") }

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle("搜索")
        view.addView(tabs, LinearLayout.LayoutParams(-1, Ui.dp(activity, 60)).apply {
            marginStart = Ui.dp(activity, 12); marginEnd = Ui.dp(activity, 12)
        })
        panel.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        view.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = Ui.column(activity, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            val pad = Ui.dp(context, 12)
            setPadding(pad, pad, pad, pad)
        }
        val pill = Ui.column(activity, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(ReferenceUi.card, Ui.dp(context, 25).toFloat(), ReferenceUi.border)
        }
        pill.addView(IconView(activity, "search", Ui.MUTED), LinearLayout.LayoutParams(Ui.dp(activity, 23), Ui.dp(activity, 23)).apply { marginStart = Ui.dp(activity, 14) })
        pill.addView(input, LinearLayout.LayoutParams(0, -1, 1f))
        pill.addView(clear)
        footer.addView(pill, LinearLayout.LayoutParams(0, Ui.dp(activity, 50), 1f))
        footer.addView(ReferenceUi.circle(activity, "close", "关闭搜索", 50, ::dismiss), LinearLayout.LayoutParams(Ui.dp(activity, 50), Ui.dp(activity, 50)).apply { marginStart = Ui.dp(activity, 12) })
        view.addView(footer, LinearLayout.LayoutParams(-1, -2))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val value = s.toString().trim()
                if (value == term) return
                term = value; sequence++; remote.clear(); issue = ""; next = ""; busy = value.isNotBlank()
                handler.removeCallbacks(search)
                scroll.scrollTo(0, 0)
                draw()
                if (busy) handler.postDelayed(search, 280)
            }
        })
        input.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) false
            else { handler.removeCallbacks(search); if (busy) runSearch(); true }
        }
        WindowCompat.setDecorFitsSystemWindows(dialog.window!!, false)
        Ui.applyWindowInsets(view)
        dialog.setBackContent(view, BackMotion.PAGE)
        dialog.setOnDismissListener { handler.removeCallbacks(search); sequence++ }
        dialog.window!!.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
        dialog.window!!.setLayout(-1, -1)
        draw()
        input.requestFocus()
        input.post { if (dialog.isShowing) WindowCompat.getInsetsController(dialog.window!!, input).show(WindowInsetsCompat.Type.ime()) }
    }

    fun dismiss() { if (dialog.isShowing) dialog.dismiss() }
    private fun runSearch(cursor: String = "") {
        if (term.isBlank() || !dialog.isShowing) return
        val ticket = ++sequence
        busy = true; issue = ""; draw()
        fetch(term, cursor) { hits, more, error ->
            if (ticket != sequence || !dialog.isShowing) return@fetch
            hits.forEach { remote[it.id] = it }
            next = more; issue = error; busy = false; draw()
        }
    }

    private fun draw() {
        clear.visibility = if (term.isEmpty()) View.INVISIBLE else View.VISIBLE
        tabs.visibility = if (term.isEmpty()) View.GONE else View.VISIBLE
        tabs.removeAllViews()
        for (type in SearchKind.entries) {
            val tab = Ui.text(activity, type.title, 16f, if (type == current) Ui.TEXT else Ui.MUTED, type == current).apply {
                gravity = Gravity.CENTER
                isSelected = type == current
                background = Ui.ripple(if (isSelected) ReferenceUi.raised else Color.TRANSPARENT, Ui.dp(context, 22).toFloat())
                setOnClickListener { current = type; scroll.scrollTo(0, 0); draw() }
            }
            tabs.addView(tab, LinearLayout.LayoutParams(0, Ui.dp(activity, 45), 1f))
        }
        panel.findViewWithTag<View>("empty")?.let(panel::removeView)
        list.removeAllViews()
        list.setPadding(Ui.dp(activity, 16), 0, Ui.dp(activity, 16), Ui.dp(activity, 4))
        if (term.isEmpty()) {
            val empty = Ui.column(activity).apply { tag = "empty"; gravity = Gravity.CENTER; isClickable = false }
            val icon = FrameLayout(activity).apply {
                background = Ui.rounded(ReferenceUi.raised, Ui.dp(context, 11).toFloat())
                val side = Ui.dp(context, 24)
                addView(IconView(context, "search", Ui.TEXT), FrameLayout.LayoutParams(side, side, Gravity.CENTER))
            }
            empty.addView(icon, LinearLayout.LayoutParams(Ui.dp(activity, 42), Ui.dp(activity, 42)))
            empty.addView(Ui.text(activity, "搜索聊天、文件和项目", 17f).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(activity, 18) })
            panel.addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            return
        }
        val hits = (remote.values + local(term).filterNot { it.id in remote }).filter { current == SearchKind.ALL || current == it.kind }
        if (hits.isEmpty() && busy && (current == SearchKind.ALL || current == SearchKind.CHAT)) {
            repeat(6) {
                val skeleton = Ui.column(activity, false).apply { gravity = Gravity.CENTER_VERTICAL }
                val rounded = { radius: Int -> Ui.rounded(ReferenceUi.raised, Ui.dp(activity, radius).toFloat()) }
                skeleton.addView(View(activity).apply { background = rounded(11) }, LinearLayout.LayoutParams(Ui.dp(activity, 42), Ui.dp(activity, 42)))
                skeleton.addView(View(activity).apply { background = rounded(10) }, LinearLayout.LayoutParams(0, Ui.dp(activity, 19), 1f).apply { marginStart = Ui.dp(activity, 12); marginEnd = Ui.dp(activity, 96) })
                list.addView(skeleton, LinearLayout.LayoutParams(-1, Ui.dp(activity, 53)))
            }
        }
        hits.forEach { item ->
            val row = Ui.column(activity, false).apply {
                gravity = Gravity.CENTER_VERTICAL; minimumHeight = Ui.dp(context, 73)
                background = Ui.ripple(Color.TRANSPARENT, Ui.dp(context, 12).toFloat())
                setOnClickListener { dismiss(); item.open() }
            }
            val frame = FrameLayout(activity).apply { background = Ui.rounded(Color.TRANSPARENT, Ui.dp(context, 11).toFloat(), ReferenceUi.border) }
            frame.addView(IconView(activity, item.kind.icon, Ui.TEXT), FrameLayout.LayoutParams(Ui.dp(activity, 24), Ui.dp(activity, 24), Gravity.CENTER))
            row.addView(frame, LinearLayout.LayoutParams(Ui.dp(activity, 42), Ui.dp(activity, 42)))
            val copy = Ui.column(activity)
            fun line(text: String, size: Float, color: Int) = Ui.text(activity, text, size, color).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            copy.addView(line(item.name, 16f, Ui.TEXT))
            if (item.excerpt.isNotBlank()) copy.addView(line(item.excerpt, 14f, Ui.MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(activity, 4) })
            row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(activity, 13) })
            list.addView(row)
        }
        if (hits.isEmpty() && !busy) list.addView(Ui.text(activity, "没有找到匹配结果", 14f, Ui.MUTED))
        if (issue.isNotBlank() && (current == SearchKind.ALL || current == SearchKind.CHAT)) list.addView(Ui.text(activity, issue, 13f, Ui.MUTED))
        if (!busy && next.isNotBlank() && (current == SearchKind.CHAT || current == SearchKind.ALL)) {
            list.addView(Ui.button(activity, "继续搜索其余历史") { runSearch(next) })
        }
    }
}