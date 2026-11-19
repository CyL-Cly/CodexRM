package com.codexrm.mobile

import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.SpannedString
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.text.util.Linkify
import android.util.TypedValue
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
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableSpan
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.image.AsyncDrawableSpan
import org.commonmark.node.Link
import org.commonmark.node.Text

internal class Markdown(
    context: Context,
    images: MessageImageLoader,
    download: (ArtifactCard) -> Unit = {},
) {
    private val imagePlugin = MessageImagesPlugin(context, images)
    private val markwon = Markwon.builder(context)
        .usePlugin(imagePlugin)
        .usePlugin(MarkwonInlineParserPlugin.create(MathParser.inlineFactory()))
        .usePlugin(MathParser())
        .usePlugin(JLatexMathPlugin.create(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, context.resources.displayMetrics)) { builder ->
            builder.inlinesEnabled(true)
            builder.theme().textColor(Ui.TEXT).blockFitCanvas(false)
        })
        .usePlugin(TablePlugin.create { theme ->
            theme.tableBorderColor(Ui.BORDER)
                .tableBorderWidth(Ui.dp(context, 1))
                .tableCellPadding(Ui.dp(context, 6))
                .tableHeaderRowBackgroundColor(Ui.SURFACE_ALT)
                .tableOddRowBackgroundColor(Ui.SURFACE)
        })
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

    fun bind(view: TextView, message: ChatMessage) {
        val rendered = message.rendered as? Spanned ?: when (message.kind) {
            MessageKind.TOOL, MessageKind.ERROR, MessageKind.STATUS -> SpannedString(message.body)
            else -> render(message.body, message.id)
        }.also { message.rendered = it }
        val wide = message.kind != MessageKind.USER ||
            rendered.getSpans(0, rendered.length, TableSpan::class.java).isNotEmpty() ||
            rendered.getSpans(0, rendered.length, AsyncDrawableSpan::class.java).isNotEmpty()
        view.layoutParams.width = if (wide) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        // These hooks load formula drawables and measure tables, and detach the previous
        // message's callbacks when ListView reuses a TextView or a streaming update arrives.
        markwon.setParsedMarkdown(view, rendered)
    }

    fun clear(view: TextView) = markwon.setParsedMarkdown(view, SpannedString(""))

    private fun render(source: String, messageId: String): Spanned {
        imagePlugin.messageId = messageId
        val text = try { markwon.toMarkdown(source) } finally { imagePlugin.messageId = "" }
        if (text is Spannable) {
            for (span in text.getSpans(0, text.length, JLatexAsyncDrawableSpan::class.java)) {
                // The plugin uses the base span for blocks and a subclass for inline math.
                // Align inline math to the text baseline rather than the line's padded center.
                val start = text.getSpanStart(span)
                val end = text.getSpanEnd(span)
                val flags = text.getSpanFlags(span)
                text.removeSpan(span)
                val replacement = if (span.javaClass == JLatexAsyncDrawableSpan::class.java) {
                    ScrollableMathSpan(markwon.configuration().theme(), span.drawable)
                } else {
                    InlineMathSpan(markwon.configuration().theme(), span.drawable)
                }
                text.setSpan(replacement, start, end, flags)
            }
        }
        return text
    }
}

internal object MessageMovement {
    val instance = LinkMovementMethod.getInstance()
}
