package com.codexrm.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionCopyTest {
    @Test fun `home guidance exposes the next useful action`() {
        val offline = InteractionCopy.home(online = false, syncing = false, cwd = "")
        assertEquals(HomeAction.CHOOSE_DEVICE, offline.primaryAction)
        assertEquals(HomeAction.RETRY_CONNECTION, offline.secondaryAction)
        assertTrue(offline.connectionActions)

        assertEquals(HomeAction.RETRY_SYNC,
            InteractionCopy.home(online = true, syncing = true, cwd = "/work").secondaryAction)

        val noDirectory = InteractionCopy.home(online = true, syncing = false, cwd = "")
        assertEquals(HomeAction.CHOOSE_WORKSPACE, noDirectory.primaryAction)
        assertEquals(HomeAction.START_INPUT, noDirectory.secondaryAction)
        assertTrue(!noDirectory.connectionActions)

        val ready = InteractionCopy.home(online = true, syncing = false, cwd = "/work")
        assertEquals(HomeAction.START_INPUT, ready.primaryAction)
        assertTrue(!ready.connectionActions)
    }

    @Test fun `send reason prioritizes safety and transient work`() {
        val ready = sendState()
        assertNull(InteractionCopy.sendBlockReason(ready))
        assertEquals("输入消息或添加附件后即可发送。",
            InteractionCopy.sendBlockReason(ready.copy(hasPayload = false)))
        assertTrue(InteractionCopy.sendBlockReason(ready.copy(readOnly = true))!!.contains("只读"))
        assertTrue(InteractionCopy.sendBlockReason(ready.copy(sending = true))!!.contains("等待设备确认"))
        assertTrue(InteractionCopy.sendBlockReason(ready.copy(loading = true))!!.contains("同步会话"))
        assertTrue(InteractionCopy.sendBlockReason(ready.copy(preparingAttachments = true))!!.contains("读取附件"))
        assertTrue(InteractionCopy.sendBlockReason(ready.copy(online = false))!!.contains("离线"))
        assertTrue(InteractionCopy.sendBlockReason(ready.copy(loaded = false))!!.contains("同步设备"))
        assertTrue(InteractionCopy.sendBlockReason(ready.copy(running = true, messageQueue = false))!!.contains("停止任务"))
        assertTrue(InteractionCopy.sendBlockReason(ready.copy(hasAttachments = true, attachmentsSupported = false))!!.contains("不支持附件"))
        assertNull(InteractionCopy.sendBlockReason(ready.copy(running = true, messageQueue = true)))
    }

    @Test fun `model blocking reasons are specific`() {
        assertTrue(InteractionCopy.modelBlockReason(false, readOnly = true, running = false, sending = false, loading = false)!!.contains("只读"))
        assertTrue(InteractionCopy.modelBlockReason(false, readOnly = false, running = true, sending = false, loading = false)!!.contains("运行"))
        assertTrue(InteractionCopy.modelBlockReason(false, readOnly = false, running = false, sending = true, loading = false)!!.contains("发送"))
        assertTrue(InteractionCopy.modelBlockReason(false, readOnly = false, running = false, sending = false, loading = true)!!.contains("同步"))
        assertTrue(InteractionCopy.modelBlockReason(false, readOnly = false, running = false, sending = false, loading = false)!!.contains("离线"))
        assertNull(InteractionCopy.modelBlockReason(true, readOnly = false, running = false, sending = false, loading = false))
    }

    @Test fun `effort labels preserve provider values`() {
        assertEquals("默认", InteractionCopy.effortLabel(""))
        assertEquals("Default", InteractionCopy.effortLabel("Default"))
        assertEquals("none", InteractionCopy.effortLabel("none"))
        assertEquals("minimal", InteractionCopy.effortLabel("minimal"))
        assertEquals("low", InteractionCopy.effortLabel("low"))
        assertEquals("MEDIUM", InteractionCopy.effortLabel("MEDIUM"))
        assertEquals("high", InteractionCopy.effortLabel("high"))
        assertEquals("xhigh", InteractionCopy.effortLabel("xhigh"))
        assertEquals("custom", InteractionCopy.effortLabel("custom"))
    }

    @Test fun `old agent model save error explains the update path`() {
        val message = InteractionCopy.modelSaveError("未知的供应商设置操作")
        assertTrue(message.contains("版本过旧"))
        assertTrue(message.contains("工作区设置"))
        assertTrue(message.contains("更新电脑 Agent"))
        assertTrue(message.contains("改动已保留"))
        assertEquals("网络不可用", InteractionCopy.modelSaveError("网络不可用"))
        assertEquals("保存模型改动失败", InteractionCopy.modelSaveError(null))
    }

    private fun sendState() = SendUiState(
        hasPayload = true,
        online = true,
        loaded = true,
        sending = false,
        loading = false,
        preparingAttachments = false,
        readOnly = false,
        editingQueuedMessage = false,
        running = false,
        messageQueue = true,
        hasAttachments = false,
        attachmentsSupported = true,
    )
}
