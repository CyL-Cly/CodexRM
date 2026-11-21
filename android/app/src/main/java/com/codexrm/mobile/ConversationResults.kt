package com.codexrm.mobile

import android.app.AlertDialog
import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject

internal class ConversationResults(
    context: Context,
    title: String,
    show: (AlertDialog.Builder) -> AlertDialog,
) {
    val content = Ui.column(context).apply {
        setPadding(Ui.dp(context, 20), Ui.dp(context, 8), Ui.dp(context, 20), Ui.dp(context, 12))
    }
    val results = Ui.column(context)
    val status = Ui.text(context, "", 13f, Ui.MUTED)
    val more = Ui.button(context, "继续加载") {}
    val dialog: AlertDialog = show(AlertDialog.Builder(context).setTitle(title).setView(content)
        .setPositiveButton("关闭", null))

    init {
        content.addView(status)
        val scroll = ScrollView(context).apply {
            addView(results)
            isFillViewport = true
        }
        content.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply {
            height = (context.resources.displayMetrics.heightPixels * 0.40).toInt()
        })
        content.addView(more)
        more.visibility = android.view.View.GONE
    }

    fun row(title: String, details: String, click: () -> Unit): LinearLayout {
        val context = results.context
        return Ui.column(context).apply {
            val padding = Ui.dp(context, 10)
            setPadding(padding, padding, padding, padding)
            background = Ui.ripple(Ui.SURFACE_ALT, Ui.dp(context, 12).toFloat())
            isFocusable = true
            setOnClickListener { click() }
            addView(Ui.text(context, title, 15f, Ui.TEXT))
            if (details.isNotBlank()) addView(Ui.text(context, details, 13f, Ui.MUTED))
            results.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(context, 6) })
        }
    }
}

internal data class ConversationJump(val thread: String, val item: String, val archived: Boolean)
internal data class BranchDraft(val thread: String, val text: String, val files: List<JSONObject>, val needsAttachments: Boolean)
