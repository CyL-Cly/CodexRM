package com.codexrm.mobile

import android.content.Context
import android.graphics.Rect
import android.util.TypedValue
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.ext.latex.JLatexMathBlock
import io.noties.markwon.ext.latex.JLatexMathNode
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableLoader
import io.noties.markwon.image.ImageSizeResolver
import ru.noties.jlatexmath.JLatexMathDrawable

/** Give TextView the final formula size before its first layout. */
internal class PreparedMath(context: Context) : AbstractMarkwonPlugin() {
    private val textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, context.resources.displayMetrics)
    private val naturalSize = object : ImageSizeResolver() {
        override fun resolveImageSize(drawable: AsyncDrawable) = Rect(drawable.result!!.bounds)
    }

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(JLatexMathBlock::class.java) { visitor, node ->
            visitor.blockStart(node)
            append(visitor, node.latex(), block = true)
            visitor.blockEnd(node)
        }
        builder.on(JLatexMathNode::class.java) { visitor, node ->
            append(visitor, node.latex(), block = false)
        }
    }

    private fun append(visitor: MarkwonVisitor, latex: String, block: Boolean) {
        val start = visitor.length()
        visitor.builder().append(latex.replace('\n', ' ').trim())
        val result = try {
            // The TeX engine shares parser data between instances.
            synchronized(texLock) {
                JLatexMathDrawable.builder(verticalDelimiter.replace(latex) { command ->
                    if (command.value.endsWith("Vert")) "\\Vert" else "\\vert"
                }).textSize(textSize).color(Ui.TEXT).build()
            }
        } catch (_: RuntimeException) {
            // Leave malformed TeX readable as text.
            return
        }
        result.setBounds(0, 0, result.intrinsicWidth.coerceAtLeast(1), result.intrinsicHeight.coerceAtLeast(1))
        val drawable = AsyncDrawable(latex, AsyncDrawableLoader.noOp(), naturalSize, null)
        drawable.setResult(result)
        val theme = visitor.configuration().theme()
        visitor.setSpans(start, if (block) ScrollableMathSpan(theme, drawable) else InlineMathSpan(theme, drawable))
    }

    private companion object {
        val texLock = Any()
        val verticalDelimiter = Regex("\\\\[lr](?:vert|Vert)(?![a-zA-Z])")
    }
}
