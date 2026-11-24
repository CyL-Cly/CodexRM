package com.codexrm.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        // An edit inserted before the submitted text already contains that text.
        assertEquals("new\noriginal", ComposerText.afterFailedSend("new\noriginal", "original"))
    }

    @Test fun relayImagesUseSupportedMimeTypesOrExtensionsForUnknownTypes() {
        assertTrue(AttachmentStore.isRelayImage("camera", "image/jpeg"))
        assertTrue(AttachmentStore.isRelayImage("preview.WeBp", ""))
        assertTrue(AttachmentStore.isRelayImage("preview.GIF", "application/octet-stream"))
        assertFalse(AttachmentStore.isRelayImage("fake.png", "text/plain"))
        assertFalse(AttachmentStore.isRelayImage("drawing.svg", "image/svg+xml"))
        assertFalse(AttachmentStore.isRelayImage("notes.pdf", ""))
    }

    @Test fun queuedDraftPreservesExistingTextAndUploadedAttachments() {
        val photo = JSONObject().put("id", "uploaded-image").put("name", "photo.png")
            .put("mimeType", "image/png").put("path", "/uploads/photo.png").put("size", 128)
        val file = JSONObject().put("id", "uploaded-file").put("name", "notes.txt")
            .put("mimeType", "text/plain").put("path", "/uploads/notes.txt").put("size", 64)
        val input = AttachmentStore.input("first line\nsecond line", listOf(photo, file))
        val draft = checkNotNull(QueuedComposerDraft.fromInput(input))

        assertEquals("existing draft\n\nfirst line\nsecond line", draft.appendTo("existing draft"))
        assertEquals(listOf("uploaded-image", "uploaded-file"), draft.files.map { it.string("id") })
        assertEquals(input.toString(), AttachmentStore.input(draft.text, draft.files).toString())
    }

    @Test fun queueEditDoesNotDropImagesTheComposerCannotRestore() {
        val input = JSONArray().put(JSONObject().put("type", "localImage").put("path", "/missing-metadata.png"))
        assertNull(QueuedComposerDraft.fromInput(input))
    }
}
