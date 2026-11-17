package com.codexrm.mobile

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

internal class MessageAdapter(private val context: Context) : BaseAdapter() {
    private val messages = ArrayList<ChatMessage>()
    private val expanded = HashSet<String>()

    fun replace(values: List<ChatMessage>) { messages.clear(); messages.addAll(values); values.forEach { it.dirty = true }; notifyDataSetChanged() }
    fun add(message: ChatMessage) { messages += message; notifyDataSetChanged() }
    fun prepend(values: List<ChatMessage>) { messages.addAll(0, values); notifyDataSetChanged() }
    fun remove(id: String) {
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) { messages.removeAt(index); notifyDataSetChanged() }
    }
    fun clear() { messages.clear(); notifyDataSetChanged() }
    fun message(id: String): ChatMessage? = messages.firstOrNull { it.id == id }
    fun snapshot(): List<ChatMessage> = ArrayList(messages)
    override fun getCount(): Int = messages.size
    override fun getItem(position: Int): ChatMessage = messages[position]
    override fun getItemId(position: Int): Long = position.toLong()

    private class Holder(
        val root: LinearLayout,
        val header: LinearLayout,
        val iconFrame: FrameLayout,
        val title: TextView,
        val status: TextView,
        val body: TextView,
        val copy: TextView,
        var boundId: String = "",
    )

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val holder = convertView?.tag as? Holder ?: createHolder()
        val message = getItem(position)
        if (holder.boundId == message.id && !message.dirty) return holder.root
        holder.boundId = message.id
        message.dirty = false
        val collapsible = message.kind == MessageKind.TOOL
        val open = expanded.contains(message.id)
        holder.title.text = if (collapsible) "${if (open) "⌄" else "›"}  ${message.title}" else message.title
        holder.header.visibility = if (message.title.isEmpty()) View.GONE else View.VISIBLE
        holder.iconFrame.visibility = if (message.kind == MessageKind.ASSISTANT) View.VISIBLE else View.GONE
        holder.status.text = message.status
        holder.status.visibility = if (message.status.isEmpty()) View.GONE else View.VISIBLE
        holder.body.visibility = if (collapsible && !open) View.GONE else View.VISIBLE
        if (!collapsible || open) {
            holder.body.text = message.rendered ?: Markdown.render(message.body).also { message.rendered = it }
        }
        holder.body.setTextColor(if (message.kind == MessageKind.ERROR) Ui.DANGER else if (message.kind == MessageKind.TOOL) Ui.MUTED else Ui.TEXT)
        holder.root.gravity = if (message.kind == MessageKind.USER) Gravity.END else Gravity.START
        holder.root.background = if (collapsible) Ui.rounded(Ui.SURFACE, Ui.dp(context, 10).toFloat(), Ui.BORDER) else null
        holder.root.setPadding(
            Ui.dp(context, if (collapsible) 0 else 14),
            Ui.dp(context, if (collapsible) 0 else 10),
            Ui.dp(context, if (collapsible) 0 else 14),
            Ui.dp(context, if (collapsible) 0 else 10),
        )
        val padding = Ui.dp(context, if (message.kind == MessageKind.USER) 14 else if (collapsible) 14 else 2)
        holder.body.setPadding(padding, Ui.dp(context, if (message.kind == MessageKind.ASSISTANT) 2 else 10), padding, padding)
        holder.body.background = when (message.kind) {
            MessageKind.USER -> Ui.rounded(Ui.SURFACE_ALT, Ui.dp(context, 19).toFloat())
            MessageKind.ERROR -> Ui.rounded(0x08ed9c96, Ui.dp(context, 9).toFloat(), 0x30ed9c96)
            else -> null
        }
        holder.body.textSize = if (collapsible) 12f else 14f
        holder.body.setLineSpacing(0f, if (collapsible) 1.35f else 1.55f)
        holder.body.typeface = if (collapsible) android.graphics.Typeface.MONOSPACE else android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL)
        (holder.body.layoutParams as LinearLayout.LayoutParams).gravity = if (message.kind == MessageKind.USER) Gravity.END else Gravity.START
        holder.copy.visibility = if (message.kind == MessageKind.ASSISTANT && message.body.isNotEmpty()) View.VISIBLE else View.GONE
        holder.copy.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("CodexRM", message.body))
            android.widget.Toast.makeText(context, "回复已复制", android.widget.Toast.LENGTH_SHORT).show()
        }
        holder.header.setOnClickListener(null)
        if (collapsible) holder.header.setOnClickListener {
            if (!expanded.add(message.id)) expanded.remove(message.id)
            message.dirty = true
            notifyDataSetChanged()
        }
        return holder.root
    }

    private fun createHolder(): Holder {
        val root = Ui.column(context)
        val header = Ui.column(context, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 2), Ui.dp(context, 6), Ui.dp(context, 2), Ui.dp(context, 6))
        }
        val iconFrame = FrameLayout(context).apply {
            background = Ui.rounded(Color.TRANSPARENT, Ui.dp(context, 6).toFloat(), Ui.BORDER)
            addView(IconView(context, "terminal", Ui.TEXT), FrameLayout.LayoutParams(Ui.dp(context, 13), Ui.dp(context, 13), Gravity.CENTER))
        }
        header.addView(iconFrame, LinearLayout.LayoutParams(Ui.dp(context, 22), Ui.dp(context, 22)))
        val title = Ui.text(context, size = 11f, color = Ui.MUTED, bold = true).apply { setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), 0) }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val status = Ui.text(context, size = 10f, color = Ui.MUTED)
        header.addView(status)
        val body = Ui.text(context, size = 14f).apply {
            setTextIsSelectable(true); movementMethod = MessageMovement.instance; highlightColor = 0x4465d6ad
            setLinkTextColor(Ui.GREEN); breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
        }
        val copy = Ui.text(context, "复制回复", 10f, Ui.DIM).apply { setPadding(Ui.dp(context, 4), Ui.dp(context, 5), Ui.dp(context, 4), 0) }
        root.addView(header)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(copy)
        return Holder(root, header, iconFrame, title, status, body, copy).also { root.tag = it }
    }
}
