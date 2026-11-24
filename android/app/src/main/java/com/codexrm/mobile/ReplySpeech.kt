package com.codexrm.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import java.util.Locale

internal object SpokenReply {
    fun text(markdown: String): String = buildString {
        Parser.builder().build().parse(markdown).accept(object : AbstractVisitor() {
            override fun visit(text: Text) { append(text.literal) }
            override fun visit(code: Code) { append(code.literal) }
            override fun visit(softLineBreak: SoftLineBreak) { append(' ') }
            override fun visit(hardLineBreak: HardLineBreak) { append('\n') }
            override fun visit(paragraph: Paragraph) { super.visit(paragraph); append('\n') }
            override fun visit(heading: Heading) { super.visit(heading); append('\n') }
            override fun visit(image: Image) = Unit
        })
    }.trim()

    fun chunks(text: String, limit: Int): Sequence<String> = sequence {
        require(limit >= 2)
        var start = 0
        while (start < text.length) {
            var end = minOf(start + limit, text.length)
            if (end < text.length) {
                val boundary = (end - 1 downTo start + limit / 2).firstOrNull {
                    text[it].isWhitespace() || text[it] in "。！？；.!?;"
                }
                if (boundary != null) end = boundary + 1
                if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            }
            text.substring(start, end).trim().takeIf(String::isNotEmpty)?.let { yield(it) }
            start = end
        }
    }

    fun language(text: String, fallback: Locale): Locale {
        val prose = text.asSequence().filter(Char::isLetter).take(500).toList()
        return when {
            prose.any { it in '\u3040'..'\u30ff' } -> Locale.JAPANESE
            prose.any { it in '\uac00'..'\ud7af' } -> Locale.KOREAN
            prose.count { it in '\u3400'..'\u9fff' } * 4 > prose.size -> Locale.CHINESE
            prose.isNotEmpty() && prose.all { it in 'a'..'z' || it in 'A'..'Z' } -> Locale.ENGLISH
            else -> fallback
        }
    }
}

/** Uses only voices that the selected Android engine marks as available offline. */
internal class ReplySpeech(context: Context, private val notice: (String) -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var request: Pair<String, String>? = null
    private var utterances: Iterator<String>? = null
    private var utteranceId = 0L

    fun toggle(messageId: String, markdown: String) {
        if (request?.first == messageId) { stop(); return }
        stop()
        val text = SpokenReply.text(markdown)
        if (text.isEmpty()) { notice("这条回复没有可朗读的正文"); return }
        request = messageId to text
        if (ready) startReading() else if (engine == null) initEngine()
    }

    private fun initEngine() {
        engine = TextToSpeech(app) { result -> main.post {
            val current = engine ?: return@post
            if (result != TextToSpeech.SUCCESS) {
                val hadRequest = request != null
                close()
                if (hadRequest) notice("无法启动系统朗读服务")
                return@post
            }
            ready = true
            current.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) = Unit
                override fun onDone(id: String) { main.post { if (id == utteranceId.toString()) next() } }
                @Deprecated("Compatibility with Android voice engines")
                override fun onError(id: String) { main.post { if (id == utteranceId.toString()) failed() } }
            })
            startReading()
        } }
    }

    private fun startReading() {
        val current = engine ?: return
        val text = request?.second ?: return
        val locale = SpokenReply.language(text, Locale.getDefault())
        val voice = runCatching {
            current.voices.orEmpty().filter {
                it.locale.language == locale.language && !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
            }.maxWithOrNull(compareBy({ it.locale.country == locale.country }, { it.quality }))
        }.getOrNull()
        if (voice == null || current.setVoice(voice) != TextToSpeech.SUCCESS) {
            stop()
            notice("系统没有这条回复所需的离线语音，请在系统朗读设置中安装语音包")
            return
        }
        utterances = SpokenReply.chunks(text, TextToSpeech.getMaxSpeechInputLength()).iterator()
        next()
    }

    private fun next() {
        val chunks = utterances ?: return
        if (!chunks.hasNext()) { stop(); return }
        val result = engine?.speak(chunks.next(), TextToSpeech.QUEUE_FLUSH, null, (++utteranceId).toString())
        if (result != TextToSpeech.SUCCESS) failed()
    }

    private fun failed() {
        stop()
        notice("系统朗读失败")
    }

    fun stop() {
        utteranceId++
        request = null
        utterances = null
        engine?.stop()
    }

    fun close() {
        stop()
        engine?.shutdown()
        engine = null
        ready = false
        main.removeCallbacksAndMessages(null)
    }
}