package com.codexrm.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerStateTest {
    @Test fun attachmentDraftsAreBoundToAgentThreadAndWorkspace() {
        val agentA = ComposerBinding("agent-a", "", "/one")
        assertNotEquals(agentA.attachmentKey { it }, agentA.copy(agentId = "agent-b").attachmentKey { it })
        assertNotEquals(agentA.attachmentKey { it }, agentA.copy(cwd = "/two").attachmentKey { it })
        assertNotEquals(agentA.attachmentKey { it }, agentA.copy(threadId = "thread-a").attachmentKey { it })
        assertEquals("agent-a:thread:thread-a", agentA.copy(threadId = "thread-a").attachmentKey { it })
    }

    @Test fun delayedRestoreCannotOverwriteNavigationOrNewEdits() {
        val binding = ComposerBinding("agent-a", "", "/one")
        val token = ComposerRestoreToken(binding, 4)
        assertTrue(token.canApply(binding, 4))
        assertFalse(token.canApply(binding.copy(agentId = "agent-b"), 4))
        assertFalse(token.canApply(binding.copy(threadId = "thread-b"), 4))
        assertFalse(token.canApply(binding, 5))
    }

    @Test fun successfulSendRemovesOnlyTheSubmittedPrefix() {
        assertEquals("", ComposerText.afterSuccessfulSend("original", "original"))
        assertEquals("\nnew input", ComposerText.afterSuccessfulSend("original\nnew input", "original"))
        assertEquals("replacement", ComposerText.afterSuccessfulSend("replacement", "original"))
        assertEquals("prefix original", ComposerText.afterSuccessfulSend("prefix original", "original"))
        assertEquals("new input", ComposerText.afterSuccessfulSend("new input", ""))
    }

    @Test fun failedSendRestoresOriginalBeforeNewInput() {
        assertEquals("original", ComposerText.afterFailedSend("", "original"))
        assertEquals("original\n\nnew input", ComposerText.afterFailedSend("new input", "original"))
        assertEquals("new input", ComposerText.afterFailedSend("new input", ""))
    }
}
