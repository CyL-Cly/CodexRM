package com.codexrm.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDisplayGroupingTest {
    private fun message(id: String, kind: MessageKind = MessageKind.TOOL, title: String = "操作-$id") =
        ChatMessage(id, kind, title, "正文-$id")

    @Test fun `consecutive tools collapse to latest operation header`() {
        val messages = listOf(message("think", title = "思考过程"), message("command", title = "运行命令"), message("search", title = "搜索"))
        val rows = MessageDisplayGrouping.rows(messages, emptySet())

        assertEquals(1, rows.size)
        assertTrue(rows.single().groupHeader)
        assertFalse(rows.single().groupExpanded)
        assertSame(messages.last(), rows.single().message)
        assertEquals("搜索", rows.single().message.title)
        assertEquals(listOf("think", "command", "search"), rows.single().group!!.messages.map { it.id })
    }

    @Test fun `expanded group exposes every original operation in chronological order`() {
        val messages = listOf(message("one"), message("two"), message("three"))
        val rows = MessageDisplayGrouping.rows(messages, setOf("two"))

        assertEquals(listOf("three", "one", "two", "three"), rows.map { it.message.id })
        assertTrue(rows.first().groupHeader)
        assertEquals(listOf(true, true, true, true), rows.map { it.groupExpanded })
        assertEquals(listOf("one", "two", "three"), rows.drop(1).map { it.message.id })
        messages.forEachIndexed { index, original -> assertSame(original, rows[index + 1].message) }
    }

    @Test fun `single and nonconsecutive tools keep their original rows`() {
        val standalone = MessageDisplayGrouping.rows(listOf(message("single")), emptySet()).single()
        assertNull(standalone.group)
        assertFalse(standalone.groupHeader)

        for (kind in listOf(MessageKind.USER, MessageKind.ASSISTANT, MessageKind.STATUS, MessageKind.ERROR)) {
            val rows = MessageDisplayGrouping.rows(listOf(message("before"), message("break", kind), message("after")), emptySet())
            assertEquals(listOf("before", "break", "after"), rows.map { it.message.id })
            assertTrue(rows.all { it.group == null && !it.groupHeader })
        }
    }

    @Test fun `separate tool runs collapse independently around other messages`() {
        val rows = MessageDisplayGrouping.rows(listOf(
            message("one"), message("two"), message("answer", MessageKind.ASSISTANT), message("three"), message("four"),
        ), emptySet())

        assertEquals(listOf("two", "answer", "four"), rows.map { it.message.id })
        assertEquals(listOf(2, null, 2), rows.map { it.group?.messages?.size })
    }

    @Test fun `reveal expands target group and positions exact historical operation`() {
        val messages = listOf(message("one"), message("two"), message("three"))
        val collapsed = MessageDisplayGrouping.rows(messages, emptySet())
        assertEquals(-1, MessageDisplayGrouping.positionOf(collapsed, "two"))
        assertEquals(0, MessageDisplayGrouping.positionOf(collapsed, "three"))

        val group = MessageDisplayGrouping.groupContaining(messages, "two")!!
        assertEquals(listOf("one", "two", "three"), group.messages.map { it.id })
        val rows = MessageDisplayGrouping.rows(messages, setOf("two"))
        assertEquals(2, MessageDisplayGrouping.positionOf(rows, "two"))
        assertEquals(3, MessageDisplayGrouping.positionOf(rows, "three"))
        assertEquals(0, MessageDisplayGrouping.positionOf(rows, "three", groupHeader = true))
        assertEquals(3, MessageDisplayGrouping.positionOf(rows, "three", groupHeader = false))
        assertNull(MessageDisplayGrouping.groupContaining(listOf(message("single")), "single"))
    }

    @Test fun `member based expansion survives streaming append and history prepend`() {
        val expanded = setOf("two")
        val streaming = MessageDisplayGrouping.rows(listOf(message("one"), message("two"), message("three")), expanded)
        assertTrue(streaming.first().groupExpanded)
        assertEquals(listOf("one", "two", "three"), streaming.drop(1).map { it.message.id })

        val withHistory = MessageDisplayGrouping.rows(listOf(message("zero"), message("one"), message("two"), message("three")), expanded)
        assertEquals(1, withHistory.count { it.groupHeader })
        assertEquals(listOf("zero", "one", "two", "three"), withHistory.drop(1).map { it.message.id })
    }

    @Test fun `latest collapsed title follows in place streaming updates`() {
        val latest = message("latest", title = "运行命令")
        val messages = listOf(message("thinking"), latest)
        latest.change(title = "搜索", body = "正在搜索最新结果")

        val header = MessageDisplayGrouping.rows(messages, emptySet()).single()
        assertSame(latest, header.message)
        assertEquals("搜索", header.message.title)
        assertEquals("正在搜索最新结果", header.message.body)
    }

    @Test fun `expanded rows retain image and file cards on their original messages`() {
        val image = MessageImage("https://example.test/tool.png", "工具图片")
        val file = ArtifactCard("/tmp/result.pdf", "result.pdf", "PDF", "新增")
        val first = message("one").apply { images = listOf(image) }
        val second = message("two").apply { files = listOf(file) }

        val content = MessageDisplayGrouping.rows(listOf(first, second), setOf("one")).drop(1)
        assertSame(image, content[0].message.images.single())
        assertSame(file, content[1].message.files.single())
        assertSame(first, content[0].message)
        assertSame(second, content[1].message)
    }
}
