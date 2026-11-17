package com.codexrm.mobile

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.util.Linkify
import java.util.regex.Pattern

internal object Markdown {
    private val inline = Pattern.compile("\\*\\*([^*]+)\\*\\*|`([^`]+)`")

    fun render(text: String): CharSequence {
        val output = SpannableStringBuilder()
        var code = false
        val lines = text.split('\n')
        for ((index, line) in lines.withIndex()) {
            if (line.trimStart().startsWith("```")) {
                code = !code
                if (index != lines.lastIndex) output.append('\n')
                continue
            }
            val start = output.length
            if (code) {
                output.append(line)
                output.setSpan(TypefaceSpan("monospace"), start, output.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                val heading = Regex("^(#{1,3})\\s+(.+)$").find(line)
                val content = heading?.groupValues?.get(2) ?: line
                appendInline(output, content)
                if (heading != null) {
                    val scale = when (heading.groupValues[1].length) { 1 -> 1.3f; 2 -> 1.18f; else -> 1.08f }
                    output.setSpan(RelativeSizeSpan(scale), start, output.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    output.setSpan(StyleSpan(Typeface.BOLD), start, output.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            if (index != lines.lastIndex) output.append('\n')
        }
        Linkify.addLinks(output, Linkify.WEB_URLS)
        return output
    }

    private fun appendInline(output: SpannableStringBuilder, text: String) {
        val matcher = inline.matcher(text)
        var cursor = 0
        while (matcher.find()) {
            output.append(text.substring(cursor, matcher.start()))
            val start = output.length
            val bold = matcher.group(1)
            output.append(bold ?: matcher.group(2))
            output.setSpan(
                if (bold != null) StyleSpan(Typeface.BOLD) else TypefaceSpan("monospace"),
                start,
                output.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            cursor = matcher.end()
        }
        output.append(text.substring(cursor))
    }
}

internal object MessageMovement {
    val instance = LinkMovementMethod.getInstance()
}
