package com.codexrm.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ReplacementSpan
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.ext.latex.JLatexMathBlock
import io.noties.markwon.ext.latex.JLatexMathNode
import io.noties.markwon.ext.tables.TableSpan
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.node.Image
import org.commonmark.node.Node
import kotlin.math.ceil

/** Lay out formula tables with shared column widths and a shared horizontal offset. */
internal class MathTables(context: Context) : AbstractMarkwonPlugin() {
    private val padding = Ui.dp(context, 7)
    private val proseWidth = Ui.dp(context, 240)

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(TableBlock::class.java) { visitor, table ->
            visitor.blockStart(table)
            val start = visitor.length()
            if (table.contains { it is JLatexMathNode || it is JLatexMathBlock } && !table.contains { it is Image }) {
                renderTable(visitor, table)
            } else {
                // Keep Markwon's row and drawable scheduling for ordinary tables.
                visitor.visitChildren(table)
            }
            visitor.setSpans(start, TableSpan())
            visitor.blockEnd(table)
        }
    }

    private fun renderTable(visitor: MarkwonVisitor, table: TableBlock) {
        val rows = table.children().flatMap { it.children() }.map { row ->
            row.children().filterIsInstance<TableCell>().map { cell ->
                val start = visitor.length()
                visitor.visitChildren(cell)
                MathTableCell(visitor.builder().removeFromEnd(start), cell.alignment)
            }.toList()
        }.filter { it.isNotEmpty() }.toList()
        if (rows.isEmpty()) return
        val layout = MathTableLayout(rows, padding, proseWidth)
        rows.indices.forEach { index ->
            if (index > 0) visitor.ensureNewLine()
            val start = visitor.length()
            visitor.builder().append(' ')
            visitor.setSpans(start, MathTableRow(layout, index))
        }
    }

    private fun Node.children() = generateSequence(firstChild) { it.next }

    private fun Node.contains(matches: (Node) -> Boolean): Boolean =
        children().any { matches(it) || it.contains(matches) }
}

internal data class MathTableCell(val text: CharSequence, val alignment: TableCell.Alignment?)

internal class MathTableLayout(
    private val rows: List<List<MathTableCell>>,
    val padding: Int,
    private val proseWidth: Int,
) {
    var widths = IntArray(0)
        private set
    private var cells = emptyList<List<StaticLayout>>()
    var offset = 0f
    private var viewportWidth = 0
    val width: Int get() = widths.sum()

    fun measure(available: Int, paint: Paint) {
        if (available == viewportWidth && cells.isNotEmpty()) return
        viewportWidth = available
        val textPaint = if (paint is TextPaint) TextPaint().apply { set(paint) } else TextPaint(paint)
        widths = IntArray(rows.maxOf { it.size }) { padding * 2 + 1 }
        val preferredProseWidth = proseWidth.coerceAtMost(available).coerceAtLeast(1)
        for (row in rows) for ((index, cell) in row.withIndex()) {
            val preferred = ceil(Layout.getDesiredWidth(cell.text, textPaint)).toInt().coerceIn(1, preferredProseWidth)
            val math = (cell.text as? android.text.Spanned)?.let { text ->
                val inline = text.getSpans(0, text.length, InlineMathSpan::class.java).maxOfOrNull { it.drawable.bounds.width() } ?: 0
                val block = text.getSpans(0, text.length, ScrollableMathSpan::class.java).maxOfOrNull { it.drawable.bounds.width() } ?: 0
                maxOf(inline, block)
            } ?: 0
            widths[index] = maxOf(widths[index], maxOf(preferred, math) + padding * 2)
        }
        val extra = (available - width).coerceAtLeast(0)
        for (index in widths.indices) widths[index] += extra / widths.size + if (index < extra % widths.size) 1 else 0
        cells = rows.map { row -> row.mapIndexed { index, cell ->
            val alignment = when (cell.alignment) {
                TableCell.Alignment.CENTER -> Layout.Alignment.ALIGN_CENTER
                TableCell.Alignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            StaticLayout.Builder.obtain(cell.text, 0, cell.text.length, textPaint, widths[index] - padding * 2)
                .setAlignment(alignment).setIncludePad(false).build()
        } }
    }

    fun row(index: Int): List<StaticLayout> = cells[index]
}

internal class MathTableRow(val table: MathTableLayout, private val index: Int) : ReplacementSpan(), HorizontalOverflow {
    override val viewport = RectF()
    override val scrollRange: Float get() = (table.width - viewport.width()).coerceAtLeast(0f)
    private val brush = Paint()

    override fun panBy(distance: Float) {
        table.offset = (table.offset + distance).coerceIn(0f, scrollRange)
    }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val available = contentWidth(text).coerceAtLeast(1)
        table.measure(available, paint)
        fm?.apply {
            ascent = -rowHeight()
            descent = 0
            top = ascent
            bottom = descent
        }
        return minOf(available, table.width)
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val rowTop = y - rowHeight()
        val rowBottom = maxOf(y, bottom)
        val width = minOf(table.width.toFloat(), (contentWidth(text) - x).coerceAtLeast(1f))
        viewport.set(x, rowTop.toFloat(), x + width, rowBottom.toFloat())
        val saved = canvas.save()
        try {
            if (canvas.clipRect(viewport)) {
                panBy(0f)
                canvas.translate(x - table.offset, viewport.top)
                drawCells(canvas, rowBottom - rowTop, paint.color)
            }
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    private fun rowHeight(): Int = table.row(index).maxOf { it.height } + table.padding * 2

    private fun drawCells(canvas: Canvas, height: Int, textColor: Int) {
        brush.color = when {
            index == 0 -> Ui.SURFACE_ALT
            index % 2 == 1 -> Ui.SURFACE
            else -> Ui.BACKGROUND
        }
        canvas.drawRect(0f, 0f, table.width.toFloat(), height.toFloat(), brush)
        val cells = table.row(index)
        var left = 0f
        for ((column, width) in table.widths.withIndex()) {
            val right = left + width
            val cell = cells.getOrNull(column)
            if (cell != null) {
                val saved = canvas.save()
                try {
                    canvas.clipRect(left + table.padding, 0f, right - table.padding, height.toFloat())
                    canvas.translate(left + table.padding, table.padding.toFloat())
                    // TextView can measure with a different paint color than it draws with.
                    cell.paint.color = textColor
                    cell.draw(canvas)
                } finally {
                    canvas.restoreToCount(saved)
                }
            }
            brush.color = Ui.BORDER
            canvas.drawRect(left, 0f, left + 1, height.toFloat(), brush)
            left = right
        }
        canvas.drawRect(left - 1, 0f, left, height.toFloat(), brush)
        canvas.drawRect(0f, height - 1f, left, height.toFloat(), brush)
        if (index == 0) canvas.drawRect(0f, 0f, left, 1f, brush)
    }
}
