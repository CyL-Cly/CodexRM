package com.codexrm.mobile

import android.text.style.ClickableSpan
import android.view.View
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import org.commonmark.node.CustomBlock
import org.commonmark.parser.Parser
import org.commonmark.parser.block.AbstractBlockParser
import org.commonmark.parser.block.AbstractBlockParserFactory
import org.commonmark.parser.block.BlockContinue
import org.commonmark.parser.block.BlockStart
import org.commonmark.parser.block.MatchedBlockParser
import org.commonmark.parser.block.ParserState
import org.json.JSONObject

internal data class VisualizationRef(val path: String, val title: String, val wide: Boolean) {
    companion object {
        private const val PREFIX = "\uE200visualize\uE202"
        private const val SUFFIX = "\uE201"
        private val directive = Regex("""::codex-inline-vis\{\s*file="([^"]+)"\s*\}""")
        private val winDrive = Regex("^[A-Za-z]:[/\\\\]")

        fun parse(line: String): VisualizationRef? {
            if (line.length > 8192) return null
            val value = line.trim()
            val json = if (value.startsWith(PREFIX) && value.endsWith(SUFFIX)) {
                runCatching { JSONObject(value.substring(PREFIX.length, value.length - SUFFIX.length)) }.getOrNull()
                    ?: return null
            } else null
            val path = if (json != null) {
                (json.opt("path") as? String)?.takeIf { it.startsWith('/') || winDrive.containsMatchIn(it) } ?: return null
            } else directive.matchEntire(value)?.groupValues?.get(1) ?: return null
            val parts = path.replace('\\', '/').split('/')
            if (path.length > 4096 || path.startsWith("//") || path.startsWith('\\') || parts.any { it == ".." } ||
                path.any { it.isISOControl() } || ':' in path.drop(if (winDrive.containsMatchIn(path)) 2 else 0) ||
                !parts.last().endsWith(".html", ignoreCase = true)) return null
            val title = (json?.opt("title") as? String)?.filterNot { it.isISOControl() }?.trim()?.take(100)
            return VisualizationRef(path, title.orEmpty().ifBlank { parts.last().dropLast(5).replace('-', ' ').take(100) }, json?.optString("mode") == "wide")
        }
    }
}

/** Recognize only top-level assistant content references; code and ordinary HTML stay Markdown. */
internal class VisualizationLinks(private val enabled: () -> Boolean, private val streaming: () -> Boolean) : AbstractMarkwonPlugin() {
    override fun configureParser(builder: Parser.Builder) {
        builder.customBlockParserFactory(VisualizationBlock.Factory(enabled))
    }

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(VisualizationBlock::class.java) { visitor, node ->
            visitor.blockStart(node)
            val start = visitor.length()
            visitor.builder().append(if (streaming()) "正在生成互动图表…" else
                "${if (node.ref.wide) "查看宽幅图表" else "展开互动图表"} · ${node.ref.title.ifBlank { "图表" }}")
            if (!streaming()) visitor.setSpans(start, object : ClickableSpan() {
                override fun onClick(widget: View) { (widget.tag as? MessagePreviews)?.visualization?.toggle(node.ref) }
            })
            visitor.blockEnd(node)
        }
    }
}

internal class VisualizationBlock(val ref: VisualizationRef) : CustomBlock() {
    class Factory(private val enabled: () -> Boolean) : AbstractBlockParserFactory() {
        override fun tryStart(state: ParserState, matched: MatchedBlockParser): BlockStart? {
            if (!enabled() || state.index != 0 || state.indent >= 4) return BlockStart.none()
            val line = state.line.subSequence(state.nextNonSpaceIndex, state.line.length).toString()
            val ref = VisualizationRef.parse(line) ?: return BlockStart.none()
            return BlockStart.of(object : AbstractBlockParser() {
                private val node = VisualizationBlock(ref)
                override fun getBlock() = node
                override fun tryContinue(state: ParserState): BlockContinue? = BlockContinue.none()
            }).atIndex(state.line.length)
        }
    }
}
