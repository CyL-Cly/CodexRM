package com.codexrm.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SpokenReplyTest {
    @Test fun skipsFencedCodeAndImageUrls() {
        val raw = "# 结果\n\n见 [报告](https://example.com)，运行 `test`。\n\n```bash\nrm example\n```\n\n![图](https://example.com/image.png)"
        assertEquals("结果\n见 报告，运行 test。", SpokenReply.text(raw))
        assertEquals(Locale.CHINESE, SpokenReply.language(SpokenReply.text(raw), Locale.ENGLISH))
    }

    @Test fun chunksUsePunctuationAndPreserveSurrogatePairs() {
        assertEquals(listOf("一二三。", "四五六。"), SpokenReply.chunks("一二三。四五六。", 6).toList())
        val original = "a😀b😀c😀"
        val chunks = SpokenReply.chunks(original, 2).toList()
        assertEquals(original, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 2 && !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
    }
}