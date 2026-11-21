package com.codexrm.mobile

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Spanned
import android.text.SpannedString
import android.widget.ListView
import java.util.concurrent.Executors
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView

internal class MessageAdapter(
    private val context: Context,
    private val images: MessageImageLoader,
    private val fileSize: (ArtifactCard, (Long?) -> Unit) -> Unit = { _, done -> done(null) },
    private val download: (ArtifactCard) -> Unit = {},
    private val isDownloaded: (ArtifactCard) -> Boolean = { false },
    private val answerQuestions: (ChatMessage) -> Unit = {},
    private val answerState: (ChatMessage) -> String? = { null },
) : BaseAdapter() {
    private val messages = ArrayList<ChatMessage>()
    private val displayRows = ArrayList<MessageDisplayRow>()
    private val expanded = HashSet<String>()
    // Any member can keep a group open while streaming or paging changes its boundaries.
    private val expandedGroupMessageIds = HashSet<String>()
    private val markdown by lazy { Markdown(context, images, download) }
    private val streamMarkdown by lazy { Markdown(context, images, download) }
    private val handler = Handler(Looper.getMainLooper())
    private data class RenderRequest(val text: String, val streaming: Boolean)
    private class StreamRow(val message: ChatMessage) {
        var requested: RenderRequest? = null
        var submitted: RenderRequest? = null
        var displayed: Spanned = SpannedString("")
    }
    private val streamRows = LinkedHashMap<String, StreamRow>()
    private var rendering = false
    var onStreamRendered: (String) -> Unit = {}

    // Keep the most recent request per row while the worker is busy. Showing an
    // already prepared prefix keeps long replies incremental even under load.
    fun prepareReply(message: ChatMessage, streaming: Boolean, previous: CharSequence? = message.rendered) {
        val row = streamRows.getOrPut(message.id) {
            StreamRow(message).also { it.displayed = previous as? Spanned ?: it.displayed }
        }
        row.requested = RenderRequest(message.body, streaming)
        renderNext()
    }

    private fun renderNext() {
        if (rendering) return
        val row = streamRows.values.firstOrNull { it.requested != it.submitted } ?: return
        val request = row.requested ?: return
        row.submitted = request
        rendering = true
        renderWorker.execute {
            val result = try {
                streamMarkdown.render(request.text, row.message.id, request.streaming)
            } catch (_: RuntimeException) {
                SpannedString(request.text)
            }
            handler.post {
                rendering = false
                val latest = row.requested
                if (streamRows[row.message.id] === row && latest != null &&
                    (latest == request || (request.streaming && latest.text.startsWith(request.text)))) {
                    row.displayed = result
                    if (!request.streaming && row.requested == request) {
                        row.message.rendered = result
                        streamRows.remove(row.message.id)
                    }
                    row.message.dirty = true
                    onStreamRendered(row.message.id)
                }
                renderNext()
            }
        }
    }

    fun refreshRows(list: ListView, ids: Set<String>) {
        if (ids.isEmpty()) return
        val first = list.firstVisiblePosition
        val last = list.getChildAt(list.childCount - 1) ?: return
        val visible = (0 until list.childCount).filter { offset ->
            val row = displayRows.getOrNull(first + offset) ?: return@filter false
            val candidates = if (row.groupHeader && row.groupExpanded) row.group!!.messages else listOf(row.message)
            candidates.any { it.id in ids && it.dirty }
        }
        if (visible.isEmpty()) return
        if (list.lastVisiblePosition == count - 1 && last.bottom <= list.height - list.paddingBottom) {
            // Let ListView follow the bottom only if it was already in view.
            notifyDataSetChanged()
        } else {
            for (offset in visible) {
                val child = list.getChildAt(offset)
                val holder = child.tag as? Holder ?: continue
                if (holder.boundMessage === displayRows[first + offset].message) getView(first + offset, child, list)
            }
        }
    }

    var showFileCards = false
        set(value) {
            if (field == value) return
            field = value
            refreshFileCards()
        }

    fun refreshFileCards() {
        messages.filter { it.files.isNotEmpty() }.forEach { it.dirty = true }
        notifyDataSetChanged()
    }

    private fun rebuildDisplayRows() {
        val rows = MessageDisplayGrouping.rows(messages, expandedGroupMessageIds)
        displayRows.clear()
        displayRows.addAll(rows)
    }

    private fun changed() {
        rebuildDisplayRows()
        notifyDataSetChanged()
    }

    fun replace(values: List<ChatMessage>) {
        val retained = values.associateBy { it.id }
        streamRows.values.removeAll { retained[it.message.id] !== it.message }
        messages.clear()
        messages.addAll(values)
        values.forEach { it.dirty = true; it.rendered = null }
        changed()
    }
    fun add(message: ChatMessage) { messages += message; changed() }
    fun replaceMessage(id: String, message: ChatMessage) {
        streamRows.remove(id)
        messages.replaceMessage(id, message)
        changed()
    }
    fun prepend(values: List<ChatMessage>) {
        // Preserve an open standalone tool when the older page groups it with other tools.
        val first = displayRows.firstOrNull()
        if (values.lastOrNull()?.kind == MessageKind.TOOL && first?.message?.kind == MessageKind.TOOL &&
            first.group == null && first.message.id in expanded
        ) {
            expandedGroupMessageIds += first.message.id
        }
        messages.addAll(0, values)
        changed()
    }
    fun remove(id: String) {
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) { streamRows.remove(id); messages.removeAt(index); changed() }
    }
    fun clear() { streamRows.clear(); messages.clear(); changed() }
    fun message(id: String): ChatMessage? = messages.firstOrNull { it.id == id }
    fun snapshot(): List<ChatMessage> = ArrayList(messages)
    override fun getCount(): Int = displayRows.size
    override fun getItem(position: Int): ChatMessage = displayRows[position].message
    fun getItemOrNull(position: Int): ChatMessage? = displayRows.getOrNull(position)?.message
    override fun getItemId(position: Int): Long = position.toLong()

    fun messageBody(row: View): TextView? = (row.tag as? Holder)?.body
    fun boundMessage(row: View): ChatMessage? = (row.tag as? Holder)?.boundMessage
    fun boundGroupHeader(row: View): Boolean = (row.tag as? Holder)?.boundRow?.groupHeader == true
    fun positionOf(id: String, groupHeader: Boolean? = null): Int =
        MessageDisplayGrouping.positionOf(displayRows, id, groupHeader)
    fun reveal(id: String) {
        val group = MessageDisplayGrouping.groupContaining(messages, id)
        if (group != null) {
            if (!group.isExpanded(expandedGroupMessageIds)) {
                expandedGroupMessageIds += id
                group.latest.dirty = true
                rebuildDisplayRows()
            }
        } else if (expanded.add(id)) {
            message(id)?.dirty = true
        }
    }

    private fun toggleGroup(group: ToolOperationGroup) {
        if (group.isExpanded(expandedGroupMessageIds)) {
            expandedGroupMessageIds.removeAll(group.messages.map { it.id }.toSet())
        } else {
            expandedGroupMessageIds += group.latest.id
        }
        group.latest.dirty = true
        changed()
    }

    private class Holder(
        val root: LinearLayout,
        val header: LinearLayout,
        val iconFrame: FrameLayout,
        val title: TextView,
        val status: TextView,
        val body: TextView,
        val copy: TextView,
        val answer: TextView,
        val images: LinearLayout,
        val diagram: InlineMermaid,
        var answerStatus: String? = null,
        var boundMessage: ChatMessage? = null,
        var boundRow: MessageDisplayRow? = null,
    )

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val holder = convertView?.tag as? Holder ?: createHolder()
        val row = displayRows[position]
        val message = row.message
        val groupHeader = row.groupHeader
        val groupContent = row.groupContent
        val answerStatus = if (groupHeader || message.questions.isEmpty()) null else answerState(message)
        if (holder.boundRow == row && holder.boundMessage === message && !message.dirty && holder.answerStatus == answerStatus) return holder.root
        holder.answerStatus = answerStatus
        val sameMessage = holder.boundMessage === message && holder.boundRow?.groupHeader == groupHeader
        val prepared = streamRows[message.id]?.displayed
        if (!sameMessage) holder.diagram.clear()
        holder.boundMessage = message
        holder.boundRow = row
        // An expanded header and its latest content share a message. Let the content clear dirty.
        if (!groupHeader || !row.groupExpanded) message.dirty = false
        val collapsible = message.kind == MessageKind.TOOL
        val open = when {
            groupHeader -> row.groupExpanded
            groupContent -> true
            else -> expanded.contains(message.id)
        }
        val arrowTitle = if (groupHeader) message.title.ifEmpty { "操作" } else message.title
        holder.title.text = if (collapsible && !groupContent) "${if (open) "⌄" else "›"}  $arrowTitle" else message.title
        holder.header.visibility = if (message.title.isEmpty() && !groupHeader) View.GONE else View.VISIBLE
        holder.header.isFocusable = collapsible && !groupContent
        holder.iconFrame.visibility = if (message.kind == MessageKind.ASSISTANT) View.VISIBLE else View.GONE
        val status = if (groupHeader) {
            listOf(message.status, "${row.group!!.messages.size} 条操作").filter(String::isNotBlank).joinToString(" · ")
        } else message.status
        holder.status.text = status
        holder.status.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
        if (!open && message.title == "思考过程" && message.body.isNotBlank()) {
            holder.status.text = listOf(status, message.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(72))
                .filter(String::isNotBlank).joinToString(" · ")
            holder.status.visibility = View.VISIBLE
        }
        holder.body.visibility = if (groupHeader || (collapsible && !open) || (prepared ?: message.body).isEmpty()) View.GONE else View.VISIBLE
        holder.images.removeAllViews()
        val fileCards = if (groupHeader) emptyList() else message.files.mapNotNull { file ->
            val saved = isDownloaded(file)
            if (showFileCards || saved) file to saved else null
        }
        holder.images.visibility = if (groupHeader || (message.images.isEmpty() && fileCards.isEmpty())) View.GONE else View.VISIBLE
        if (holder.images.visibility == View.VISIBLE) for (source in message.images) {
            val image = source.copy(messageId = message.id)
            val card = Ui.column(context).apply {
                background = Ui.rounded(Ui.SURFACE_ALT, Ui.dp(context, 12).toFloat(), Ui.BORDER)
                setPadding(Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6))
            }
            val preview = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                maxHeight = Ui.dp(context, 320)
                contentDescription = image.name
                visibility = View.GONE
            }
            val caption = Ui.text(context, "${image.name} · 正在加载…", 12f, Ui.MUTED).apply { setPadding(4, 6, 4, 6) }
            card.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(caption)
            holder.images.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Ui.dp(context, 6) })
            val token = Any()
            card.tag = token
            fun load() {
                caption.text = "${image.name} · 正在加载…"
                images.load(image) { result ->
                    if (holder.boundMessage !== message || card.parent !== holder.images || card.tag !== token) return@load
                    result.fold(onSuccess = { bitmap ->
                        preview.setImageBitmap(bitmap)
                        preview.visibility = View.VISIBLE
                        caption.text = "${image.name} · 点击查看"
                        card.setOnClickListener { showImagePreview(context, images, image, bitmap) }
                    }, onFailure = { error ->
                        caption.text = "${image.name} · ${error.message.orEmpty()} · 点击重试"
                        card.setOnClickListener { load() }
                    })
                }
            }
            load()
        }
        for ((file, saved) in fileCards) {
            val card = Ui.column(context, false).apply {
                if (file.linked && !saved) visibility = View.GONE
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = Ui.dp(context, 58)
                setPadding(Ui.dp(context, 10), Ui.dp(context, 7), Ui.dp(context, 10), Ui.dp(context, 7))
                background = Ui.ripple(Ui.SURFACE_ALT, Ui.dp(context, 8).toFloat())
                setOnClickListener { download(file) }
                contentDescription = if (saved) "打开 ${file.name}" else "下载 ${file.name}"
            }
            card.addView(IconView(context, "file", Ui.TEXT), LinearLayout.LayoutParams(Ui.dp(context, 26), Ui.dp(context, 26)))
            val labels = Ui.column(context).apply { setPadding(Ui.dp(context, 9), 0, Ui.dp(context, 6), 0) }
            val name = Ui.text(context, file.name, 14f, Ui.TEXT, bold = true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            val detail = Ui.text(context, if (saved) "${file.type} · 已下载" else "${file.type} · ${file.change}", 12f, Ui.MUTED)
            labels.addView(name); labels.addView(detail)
            card.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(IconView(context, if (saved) "arrow" else "update", Ui.MUTED), LinearLayout.LayoutParams(Ui.dp(context, 20), Ui.dp(context, 20)))
            holder.images.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(Ui.dp(context, 8), Ui.dp(context, 2), Ui.dp(context, 8), Ui.dp(context, 7))
            })
            if (!saved) fileSize(file) { size ->
                if (size != null && card.parent === holder.images) {
                    detail.text = "${file.type} · ${AttachmentStore.sizeLabel(size)} · ${file.change}"
                    card.visibility = View.VISIBLE
                }
            }
        }
        holder.body.setTextColor(if (message.kind == MessageKind.ERROR) Ui.DANGER else if (message.kind == MessageKind.TOOL) Ui.MUTED else Ui.TEXT)
        holder.root.gravity = if (message.kind == MessageKind.USER) Gravity.END else Gravity.START
        holder.root.background = if (collapsible && fileCards.isEmpty()) Ui.rounded(Ui.SURFACE, Ui.dp(context, 10).toFloat(), Ui.BORDER) else null
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
        if (!groupHeader && (!collapsible || open)) markdown.bind(holder.body, message, prepared, preserveMathScroll = sameMessage)
        else markdown.clear(holder.body)
        (holder.body.layoutParams as LinearLayout.LayoutParams).gravity = if (message.kind == MessageKind.USER) Gravity.END else Gravity.START
        holder.copy.visibility = if (message.kind == MessageKind.ASSISTANT && message.body.isNotEmpty()) View.VISIBLE else View.GONE
        holder.copy.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("CodexRM", message.body))
            android.widget.Toast.makeText(context, "回复已复制", android.widget.Toast.LENGTH_SHORT).show()
        }
        holder.answer.visibility = if (groupHeader || message.questions.isEmpty()) View.GONE else View.VISIBLE
        holder.answer.text = answerStatus ?: "回答问题"
        holder.answer.isEnabled = answerStatus == null
        holder.answer.setOnClickListener { answerQuestions(message) }
        holder.header.setOnClickListener(null)
        holder.header.contentDescription = if (collapsible && !groupContent) {
            (if (open) "收起 " else "展开 ") + arrowTitle
        } else null
        if (groupHeader) holder.header.setOnClickListener { toggleGroup(row.group!!) }
        else if (collapsible && !groupContent) holder.header.setOnClickListener {
            if (!expanded.add(message.id)) expanded.remove(message.id)
            message.dirty = true
            notifyDataSetChanged()
        }
        return holder.root
    }

    private companion object {
        val renderWorker = Executors.newSingleThreadExecutor()
    }

    private fun createHolder(): Holder {
        val root = Ui.column(context)
        val header = Ui.column(context, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(context, 48)
            isFocusable = true
            setPadding(Ui.dp(context, 2), Ui.dp(context, 6), Ui.dp(context, 2), Ui.dp(context, 6))
        }
        val iconFrame = FrameLayout(context).apply {
            background = Ui.rounded(Color.TRANSPARENT, Ui.dp(context, 6).toFloat(), Ui.BORDER)
            addView(IconView(context, "terminal", Ui.TEXT), FrameLayout.LayoutParams(Ui.dp(context, 13), Ui.dp(context, 13), Gravity.CENTER))
        }
        header.addView(iconFrame, LinearLayout.LayoutParams(Ui.dp(context, 22), Ui.dp(context, 22)))
        val title = Ui.text(context, size = 12f, color = Ui.MUTED, bold = true).apply { setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), 0) }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val status = Ui.text(context, size = 12f, color = Ui.MUTED)
        header.addView(status)
        val body = MathTextView(context).apply {
            textSize = 14f; setTextColor(Ui.TEXT); gravity = Gravity.CENTER_VERTICAL
            setTextIsSelectable(true); movementMethod = MessageMovement.instance; highlightColor = 0x4465d6ad
            setLinkTextColor(Ui.GREEN); breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
        }
        val copy = Ui.text(context, "复制回复", 12f, Ui.DIM).apply {
            minimumWidth = Ui.dp(context, 48)
            minimumHeight = Ui.dp(context, 48)
            gravity = Gravity.CENTER
            contentDescription = "复制回复到剪贴板"
            isFocusable = true
            background = Ui.ripple(Color.TRANSPARENT, Ui.dp(context, 8).toFloat())
            setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 12), 0)
        }
        val images = Ui.column(context)
        val diagramPanel = Ui.column(context).apply { visibility = View.GONE }
        val diagram = InlineMermaid(diagramPanel)
        body.tag = diagram
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = diagram.clear()
        })
        val answer = Ui.button(context, "回答问题", primary = true) {}
        root.addView(header)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(diagramPanel, LinearLayout.LayoutParams(-1, -2))
        root.addView(images, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(answer)
        root.addView(copy)
        return Holder(root, header, iconFrame, title, status, body, copy, answer, images, diagram).also { root.tag = it }
    }
}
