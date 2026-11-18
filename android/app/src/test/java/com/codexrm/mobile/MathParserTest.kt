package com.codexrm.mobile

import io.noties.markwon.ext.latex.JLatexMathBlock
import io.noties.markwon.ext.latex.JLatexMathNode
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MathParserTest {
    private val parser = Parser.builder().apply {
        MathParser().configureParser(this)
        inlineParserFactory(MathParser.inlineFactory().build())
        extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
    }.build()

    private fun nodes(source: String): List<Node> {
        val result = ArrayList<Node>()
        fun collect(node: Node) {
            result += node
            var child = node.firstChild
            while (child != null) { collect(child); child = child.next }
        }
        collect(parser.parse(source))
        return result
    }

    private fun inline(source: String) = nodes(source).filterIsInstance<JLatexMathNode>().map { it.latex() }
    private fun blocks(source: String) = nodes(source).filterIsInstance<JLatexMathBlock>().map { it.latex() }
    private fun text(source: String) = nodes(source).filterIsInstance<Text>().joinToString("") { it.literal }

    @Test fun dollarAndParenthesisInlinePreserveLatexCommands() {
        assertEquals(listOf("x_1^2", "\\frac{a}{b}"), inline("行内 \$x_1^2\$ 与 \\(\\frac{a}{b}\\)"))
    }

    @Test fun supportsMultipleInlineExpressionsAndEscapedDollarsWithinMath() {
        assertEquals(listOf("a", "b", "\\text{\\\$} + c"), inline("\$a\$ 和 \$b\$ 与 \$\\text{\\\$} + c\$"))
    }

    @Test fun dollarAndBracketDisplayBlocksSupportSingleAndMultipleLines() {
        assertEquals(listOf("x^2", "\\sum_{i=1}^{n} i"), blocks("\$\$x^2\$\$\n\n\\[\n\\sum_{i=1}^{n} i\n\\]"))
        assertEquals(listOf("x + y"), blocks("\$\$\nx + y\n\$\$"))
    }

    @Test fun displayMathCanStartOnSameLineAndEndOnLaterLine() {
        assertEquals(listOf("\\begin{aligned}\na &= b \\\\\nc &= d\n\\end{aligned}"),
            blocks("\\[\\begin{aligned}\na &= b \\\\\nc &= d\n\\end{aligned}\\]"))
        assertEquals(listOf("x +\ny"), blocks("\$\$x +\ny\$\$"))
    }

    @Test fun environmentsKeepRowSeparatorsAndAlignmentCharacters() {
        for (formula in listOf(
            """\begin{aligned} f(x)&=\int_a^b K(x,t)\,dt \\ \lim_{n\to\infty}a_n&=0 \end{aligned}""",
            """A=\begin{pmatrix}1&2&3\\0&1&4\\5&6&0\end{pmatrix}""",
            """|x|=\begin{cases}x&x\ge0\\-x&x<0\end{cases}""",
        )) {
            assertEquals(listOf(formula), blocks("\\[\n$formula\n\\]"))
            assertEquals(listOf(formula), blocks("\$\$$formula\$\$"))
        }
    }

    @Test fun displayMathMayAppearWithinProse() {
        assertEquals(listOf("x^2", "y^2"), blocks("前文 \$\$x^2\$\$ 中间 \\[y^2\\] 后文"))
        assertEquals("前文  中间  后文", text("前文 \$\$x^2\$\$ 中间 \\[y^2\\] 后文"))
    }

    @Test fun displayClosingDelimiterDoesNotConsumeFollowingParagraph() {
        val source = "\$\$\nx^2\n\$\$\n## 后续标题\n正文"
        assertEquals(listOf("x^2"), blocks(source))
        assertTrue(nodes(source).any { it is Heading })
        assertTrue(text(source).contains("正文"))
    }

    @Test fun codeSpansAndBothFenceStylesStayLiteral() {
        val source = "`\$x\$` 和 `\\(y\\)`\n\n```latex\n\$\$\nx_1\n\$\$\n```\n\n~~~text\n\\[z\\]\n~~~"
        assertTrue(inline(source).isEmpty())
        assertTrue(blocks(source).isEmpty())
        assertEquals(listOf("\$x\$", "\\(y\\)"), nodes(source).filterIsInstance<Code>().map { it.literal })
        assertEquals(2, nodes(source).filterIsInstance<FencedCodeBlock>().size)
    }

    @Test fun indentedCodeIsNotMath() {
        val source = "    \$\$x^2\$\$\n\n    \\[y\\]"
        assertTrue(inline(source).isEmpty())
        assertTrue(blocks(source).isEmpty())
    }

    @Test fun linksKeepDestinationsAndMayContainInlineMathInLabel() {
        val source = "[公式 \$x\$](https://example.com/\$path\$)"
        assertEquals(listOf("x"), inline(source))
        assertEquals("https://example.com/\$path\$", nodes(source).filterIsInstance<Link>().single().destination)
    }

    @Test fun escapedDelimitersStayLiteral() {
        val source = "\\\$x\\\$ 与 \\\\(y\\\\)"
        assertTrue(inline(source).isEmpty())
        assertTrue(blocks(source).isEmpty())
        assertEquals("\$x\$ 与 \\(y\\)", text(source))
    }

    @Test fun currencyDoesNotConsumeLaterMath() {
        val source = "费用 \$5 和 \$10，公式是 \$x+1\$。"
        assertEquals(listOf("x+1"), inline(source))
        assertTrue(text(source).contains("\$5 和 \$10"))
    }

    @Test fun whitespaceBesideSingleDollarIsNotMath() {
        assertTrue(inline("\$ x \$ 与 \$a \$ 与 \$ b\$").isEmpty())
    }

    @Test fun unfinishedInlineMathAndBackslashDelimitersRemainReadable() {
        for (source in listOf("\$x_1", "\\(x_1", "前文 \\[x_1", "\$\$x_1\$")) {
            assertTrue(source, inline(source).isEmpty())
            assertTrue(source, blocks(source).isEmpty())
            assertEquals(source, text(source))
        }
    }

    @Test fun bareAndEmptyBackslashDelimitersKeepTheirSource() {
        for (source in listOf("\\(", "前文 \\[", "\\(\\)", "\\( \\)")) {
            assertTrue(inline(source).isEmpty())
            assertTrue(blocks(source).isEmpty())
            assertEquals(source, text(source))
        }
    }

    @Test fun inlineDisplayClosingDelimiterDoesNotHideTrailingProse() {
        assertEquals(listOf("a"), blocks("\$\$a\$\$ 后文"))
        assertEquals(" 后文", text("\$\$a\$\$ 后文"))
        assertEquals(listOf("b"), blocks("\\[b\\] 后文"))
    }

    @Test fun unfinishedDisplayMathPreservesBlankLinesAndMarkdownCharacters() {
        for (opening in listOf("\$\$", "\\[")) {
            val source = "$opening\n\\frac{a}{b}\n\n*unfinished*"
            assertTrue(blocks(source).isEmpty())
            assertEquals(source, text(source))
        }
    }

    @Test fun streamingExpressionRendersOnlyOnceClosed() {
        val source = "\$\$\n\\frac{a}{b}\n\$\$"
        for (end in 1 until source.length) assertTrue(blocks(source.take(end)).isEmpty())
        assertEquals(listOf("\\frac{a}{b}"), blocks(source))
    }

    @Test fun mathWorksInsideQuotesListsAndTables() {
        assertEquals(listOf("a", "b", "c"), inline("> 引用 \$a\$\n\n- 列表 \$b\$\n\n| 公式 |\n| --- |\n| \$c\$ |"))
        assertEquals(listOf("x^2"), blocks("> \\[\n> x^2\n> \\]"))
    }

    @Test fun markdownStructureSurvivesAlongsideMath() {
        val nodes = nodes("# 标题\n\n**加粗** *斜体* ~~删除~~\n\n> 引用\n\n- 项目\n  - 嵌套 \$x\$\n\n1. 有序\n\n| A | B |\n| --- | --- |\n| a | b |")
        for (type in listOf(Heading::class.java, StrongEmphasis::class.java, Emphasis::class.java,
            Strikethrough::class.java, BlockQuote::class.java, BulletList::class.java,
            OrderedList::class.java, TableBlock::class.java)) {
            assertTrue(type.simpleName, nodes.any { type.isInstance(it) })
        }
    }
}
