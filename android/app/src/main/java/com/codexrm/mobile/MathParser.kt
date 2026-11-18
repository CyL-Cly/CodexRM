package com.codexrm.mobile

import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.ext.latex.JLatexMathBlock
import io.noties.markwon.ext.latex.JLatexMathNode
import io.noties.markwon.inlineparser.InlineProcessor
import io.noties.markwon.inlineparser.MarkwonInlineParser
import org.commonmark.node.CustomBlock
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.parser.block.AbstractBlockParser
import org.commonmark.parser.block.AbstractBlockParserFactory
import org.commonmark.parser.block.BlockContinue
import org.commonmark.parser.block.BlockStart
import org.commonmark.parser.block.MatchedBlockParser
import org.commonmark.parser.block.ParserState

/** Math is parsed within CommonMark, so code fences, code spans and link destinations stay literal. */
internal class MathParser : AbstractMarkwonPlugin() {
    override fun configureParser(builder: Parser.Builder) {
        builder.customBlockParserFactory(DisplayMathParser.Factory())
    }

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(DisplayMathBlock::class.java) { visitor, node ->
            visitor.blockStart(node)
            visitor.visitChildren(node)
            visitor.blockEnd(node)
        }
    }

    companion object {
        fun inlineFactory(): MarkwonInlineParser.FactoryBuilder = MarkwonInlineParser.factoryBuilderNoDefaults().apply {
            addInlineProcessor(MathInlineProcessor('$'))
            addInlineProcessor(MathInlineProcessor('\\'))
            includeDefaults()
        }
    }
}

private class MathInlineProcessor(private val character: Char) : InlineProcessor() {
    override fun specialCharacter(): Char = character

    override fun parse(): Node? {
        if (character == '$' && input.getOrNull(index - 1) == '$') return null
        val opening = when {
            character == '$' && input.startsWith("\$\$", index) -> "\$\$"
            character == '$' -> "\$"
            input.startsWith("\\(", index) -> "\\("
            input.startsWith("\\[", index) -> "\\["
            else -> return null
        }
        val closing = when (opening) { "\\(" -> "\\)"; "\\[" -> "\\]"; else -> opening }
        val start = index + opening.length
        val singleDollar = opening == "\$"
        if (start >= input.length) {
            if (character != '\\') return null
            index = start
            return text(opening)
        }
        if (singleDollar && input[start].isWhitespace()) return null
        var end = start
        while (end < input.length) {
            if (singleDollar && input[end] == '\n') return null
            if (input.startsWith(closing, end) && !isEscaped(input, end)) {
                if (singleDollar && (input[end - 1].isWhitespace() || input.getOrNull(end + 1)?.isDigit() == true)) {
                    return null
                }
                val latex = input.substring(start, end)
                if (latex.isBlank()) {
                    if (character != '\\') return null
                    index = end + closing.length
                    return text(opening + latex + closing)
                }
                index = end + closing.length
                return if (opening == "\$\$" || opening == "\\[") {
                    JLatexMathBlock().apply { latex(latex) }
                } else {
                    JLatexMathNode().apply { latex(latex) }
                }
            }
            end++
        }
        // Keep backslashes in unfinished \(...\) and \[...\] expressions during streaming.
        if (character == '\\') {
            index = start
            return text(opening)
        }
        return null
    }
}

private class DisplayMathBlock : CustomBlock()

private class DisplayMathParser(private val opening: String, private val closing: String) : AbstractBlockParser() {
    private val node = DisplayMathBlock()
    private val lines = ArrayList<String>()
    private var closed = false

    override fun getBlock() = node

    override fun tryContinue(state: ParserState): BlockContinue? =
        if (closed) BlockContinue.none() else BlockContinue.atIndex(state.index)

    override fun addLine(line: CharSequence) {
        val content = line.toString()
        lines += content
        val start = if (lines.size == 1) opening.length else 0
        val end = content.trimEnd().length - closing.length
        closed = end >= start && content.startsWith(closing, end) && !isEscaped(content, end)
    }

    override fun closeBlock() {
        val source = lines.joinToString("\n")
        val latex = if (closed) source.trimEnd().drop(opening.length).dropLast(closing.length).trim() else ""
        node.appendChild(if (latex.isNotEmpty()) {
            JLatexMathBlock().apply { latex(latex) }
        } else {
            // Streaming and incomplete expressions remain readable until their closing delimiter arrives.
            Paragraph().apply { appendChild(Text(source)) }
        })
    }

    class Factory : AbstractBlockParserFactory() {
        override fun tryStart(state: ParserState, matchedBlockParser: MatchedBlockParser): BlockStart? {
            if (state.indent >= 4) return BlockStart.none()
            val content = state.line.subSequence(state.nextNonSpaceIndex, state.line.length).toString()
            val opening = when {
                content.startsWith("\$\$") -> "\$\$"
                content.startsWith("\\[") -> "\\["
                else -> return BlockStart.none()
            }
            val closing = if (opening == "\\[") "\\]" else opening
            // A closing delimiter followed by prose belongs to the inline parser.
            val rest = content.drop(opening.length).trimEnd()
            if (rest.contains(closing) && !rest.endsWith(closing)) return BlockStart.none()
            return BlockStart.of(DisplayMathParser(opening, closing)).atIndex(state.nextNonSpaceIndex)
        }
    }
}

private fun isEscaped(source: String, index: Int): Boolean {
    var cursor = index - 1
    while (cursor >= 0 && source[cursor] == '\\') cursor--
    return (index - cursor - 1) % 2 != 0
}
