package com.codexrm.mobile

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.SpannedString
import android.text.method.LinkMovementMethod
import android.text.style.AlignmentSpan
import android.text.style.URLSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.util.Linkify
import android.view.ViewGroup
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.LinkResolverDef
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.SpannableBuilder
import io.noties.markwon.core.CoreProps
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableSpan
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.image.AsyncDrawableSpan
import org.commonmark.node.Link
import org.commonmark.node.Text
import org.commonmark.node.FencedCodeBlock

internal class Markdown(
    context: Context,
    images: MessageImageLoader,
    download: (ArtifactCard) -> Unit = {},
) {
    private var streaming = false
    private val imagePlugin = MessageImagesPlugin(context, images)
    private val markwon = Markwon.builder(context)
        .usePlugin(imagePlugin)
        .usePlugin(MarkwonInlineParserPlugin.create(MathParser.inlineFactory { streaming }))
        .usePlugin(MathParser { streaming })
        .usePlugin(PreparedMath(context))
        .usePlugin(TablePlugin.create { theme ->
            theme.tableBorderColor(Ui.BORDER)
                .tableBorderWidth(Ui.dp(context, 1))
                .tableCellPadding(Ui.dp(context, 6))
                .tableHeaderRowBackgroundColor(Ui.SURFACE_ALT)
                .tableOddRowBackgroundColor(Ui.SURFACE)
        })
        .usePlugin(MathTables(context))
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TaskListPlugin.create(Ui.GREEN, Ui.MUTED, Ui.BACKGROUND))
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                val externalLinks = LinkResolverDef()
                builder.linkResolver { view, destination ->
                    val file = ArtifactCard.fromLink(destination)
                    if (file != null) {
                        download(file)
                    } else if (!destination.startsWith("file:", ignoreCase = true) &&
                        !destination.startsWith("sandbox:", ignoreCase = true)) {
                        externalLinks.resolve(view, destination)
                    }
                }
            }

            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder.linkColor(Ui.GREEN)
                    .blockQuoteColor(Ui.MUTED)
                    .codeBackgroundColor(Ui.SURFACE_ALT)
                    .codeBlockBackgroundColor(Ui.SURFACE)
                    .codeTextColor(Ui.TEXT)
                    .codeBlockTextColor(Ui.TEXT)
                    .headingBreakColor(Ui.BORDER)
                    .thematicBreakColor(Ui.BORDER)
            }

            override fun configureVisitor(builder: MarkwonVisitor.Builder) {
                builder.on(FencedCodeBlock::class.java) { visitor, node ->
                    val start = visitor.length()
                    val code = node.literal.removeSuffix("\n")
                    val language = node.info.orEmpty().substringBefore(' ').ifBlank { "代码" }
                    visitor.blockStart(node)
                    val heading = visitor.length()
                    visitor.builder().append("$language   ·   复制代码")
                    visitor.setSpans(heading, object : ClickableSpan() {
                        override fun onClick(widget: android.view.View) {
                            widget.context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText(language, code))
                        }
                    })
                    if (language.equals("mermaid", ignoreCase = true) && !streaming && code.isNotBlank()) {
                        visitor.builder().append("   ?   ")
                        val diagram = visitor.length()
                        visitor.builder().append("预览图表")
                        visitor.setSpans(diagram, object : ClickableSpan() {
                            override fun onClick(widget: android.view.View) {
                                val inline = widget.tag as? InlineMermaid
                                if (inline == null) MermaidPreview.show(widget.context, code, Ui.dark)
                                else inline.toggle(code)
                            }
                        })
                    }
                    val preview = code.lineSequence().take(100).joinToString("\n").take(10_000)
                    visitor.builder().append('\n').append(highlightCode(preview, node.info.orEmpty())).append('\n')
                    if (preview.length < code.length) {
                        val expand = visitor.length()
                        visitor.builder().append("查看完整代码")
                        visitor.setSpans(expand, object : ClickableSpan() {
                            override fun onClick(widget: android.view.View) {
                                showLongCode(widget.context, language, code, node.info.orEmpty())
                            }
                        })
                        visitor.builder().append('\n')
                    }
                    CoreProps.CODE_BLOCK_INFO.set(visitor.renderProps(), node.info.orEmpty())
                    visitor.setSpansForNodeOptional(node, start)
                    visitor.blockEnd(node)
                }
                builder.on(Text::class.java) { visitor, node ->
                    val start = visitor.length()
                    visitor.builder().append(node.literal)
                    var parent = node.parent
                    while (parent != null && parent !is Link) parent = parent.parent
                    // Linkify only prose nodes, leaving explicit Markdown links and code intact.
                    val linked = SpannableString(node.literal)
                    if (parent == null && Linkify.addLinks(linked, Linkify.WEB_URLS)) {
                        val factory = visitor.configuration().spansFactory().require(Link::class.java)
                        for (link in linked.getSpans(0, linked.length, URLSpan::class.java)) {
                            CoreProps.LINK_DESTINATION.set(visitor.renderProps(), link.url)
                            SpannableBuilder.setSpans(
                                visitor.builder(), factory.getSpans(visitor.configuration(), visitor.renderProps()),
                                start + linked.getSpanStart(link), start + linked.getSpanEnd(link),
                            )
                        }
                    }
                }
            }
        })
        .build()

    fun bind(view: TextView, message: ChatMessage, prepared: Spanned? = null, preserveMathScroll: Boolean = false): Boolean {
        val rendered = prepared ?: message.rendered as? Spanned ?: when (message.kind) {
            MessageKind.TOOL, MessageKind.ERROR, MessageKind.STATUS -> SpannedString(message.body)
            else -> render(message.body, message.id)
        }.also { message.rendered = it }
        val user = message.kind == MessageKind.USER
        val imageSpans = if (user) rendered.getSpans(0, rendered.length, AsyncDrawableSpan::class.java) else emptyArray()
        val wide = !user || imageSpans.isNotEmpty() || rendered.getSpans(0, rendered.length, TableSpan::class.java).isNotEmpty()
        view.layoutParams.width = if (wide) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        if (preserveMathScroll) {
            val previous = view.text as? Spanned
            val old = previous?.getSpans(0, previous.length, ScrollableMathSpan::class.java).orEmpty()
            val next = rendered.getSpans(0, rendered.length, ScrollableMathSpan::class.java)
            for ((before, after) in old.zip(next)) {
                if (before.drawable.destination == after.drawable.destination) after.restoreOffset(before.offset)
            }
        }
        // Keep inline Markdown images in their prose. A standalone image takes the sender's side.
        var aligned: SpannableString? = null
        for (image in imageSpans) {
            val start = rendered.getSpanStart(image)
            val end = rendered.getSpanEnd(image)
            val lineStart = rendered.lastIndexOf('\n', start - 1) + 1
            val lineEnd = rendered.indexOf('\n', end).let { if (it < 0) rendered.length else it }
            if (!(lineStart until start).all { rendered[it].isWhitespace() } ||
                !(end until lineEnd).all { rendered[it].isWhitespace() }) continue
            val text = aligned ?: SpannableString(rendered).also { aligned = it }
            val paragraphEnd = if (lineEnd < rendered.length) lineEnd + 1 else lineEnd
            text.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), lineStart, paragraphEnd, Spanned.SPAN_PARAGRAPH)
        }
        // Bind table and image hooks and detach image callbacks from the old content.
        markwon.setParsedMarkdown(view, aligned ?: rendered)
        return imageSpans.isNotEmpty()
    }

    fun clear(view: TextView) = markwon.setParsedMarkdown(view, SpannedString(""))

    fun render(source: String, messageId: String, streaming: Boolean = false): Spanned {
        imagePlugin.messageId = messageId
        this.streaming = streaming
        return try {
            markwon.toMarkdown(source)
        } finally {
            imagePlugin.messageId = ""
            this.streaming = false
        }
    }

    private fun highlightCode(code: String, info: String): CharSequence {
        if (info.isBlank() || info == "text" || info == "plaintext" || code.length > 50_000) return code
        val text = SpannableString(code)
        for (match in codeToken.findAll(code)) {
            val color = when {
                match.groups[1] != null -> Ui.MUTED
                match.groups[2] != null -> Ui.GREEN
                else -> Ui.TEXT
            }
            if (color != Ui.TEXT) text.setSpan(ForegroundColorSpan(color), match.range.first, match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    private fun showLongCode(context: Context, language: String, code: String, info: String) {
        val chunks = code.lineSequence().chunked(80).map { it.joinToString("\n") }.toList()
        val list = android.widget.ListView(context)
        list.adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = chunks.size
            override fun getItem(position: Int) = chunks[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                val view = convertView as? TextView ?: Ui.text(context, "", 12f).apply {
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    val spacing = Ui.dp(context, 14)
                    setPadding(spacing, Ui.dp(context, 4), spacing, Ui.dp(context, 4))
                }
                view.text = highlightCode(getItem(position), info)
                return view
            }
        }
        MiuixDialog.Builder(context).setTitle(language).setView(list)
            .setNeutralButton("复制代码") { _, _ ->
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(language, code))
            }.setPositiveButton("关闭", null).show()
    }

    private companion object {
        val codeToken = Regex("""(//[^\r\n]*|#[^\r\n]*)|("(?:\\.|[^"\\\r\n])*"|'(?:\\.|[^'\\\r\n])*'|\b(?:if|else|for|while|return|function|func|class|def|val|var|let|const|import|from|package|true|false|null|nil)\b)""")
    }
}

internal object MessageMovement {
    val instance = LinkMovementMethod.getInstance()
}
