package com.codexrm.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MessageImagesTest {
    private fun item(content: JSONArray) = JSONObject().put("type", "userMessage").put("id", "message").put("content", content)
    private fun text(value: String) = JSONObject().put("type", "text").put("text", value)

    @Test fun sentImagesKeepUploadIdentityAndDoNotDuplicateTheLocalImage() {
        val photo = JSONObject().put("id", "photo-id").put("name", "照片.png").put("path", "E:\\uploads\\photo.png")
            .put("mimeType", "image/png").put("size", 1024)
        val content = AttachmentStore.input("看看这张照片", listOf(photo))
        val message = ProtocolFormatter.fromItem(item(content))
        assertEquals("看看这张照片", message.body)
        assertEquals(listOf(MessageImage("E:\\uploads\\photo.png", "照片.png", "photo-id")), message.images)
        // Reopening history parses the same stable image identity without draft data.
        assertEquals(message.images, ProtocolFormatter.fromItem(JSONObject(item(content).toString())).images)
    }

    @Test fun imageOnlyMessagesHaveAnImageAndNoPlaceholderText() {
        val message = ProtocolFormatter.fromItem(item(JSONArray().put(JSONObject().put("type", "localImage").put("path", "/tmp/a.png"))))
        assertEquals("", message.body)
        assertEquals("/tmp/a.png", message.images.single().source)
    }

    @Test fun ordinaryFilesAndMalformedMetadataRemainVisible() {
        val file = JSONObject().put("id", "pdf").put("name", "报告.pdf").put("path", "/tmp/a.pdf").put("mimeType", "application/pdf").put("size", 1024)
        val message = ProtocolFormatter.fromItem(item(AttachmentStore.input("请阅读", listOf(file))))
        assertTrue(message.body.contains("报告.pdf"))
        assertTrue(message.images.isEmpty())
        val malformed = AttachmentStore.MARKER + "not-json"
        assertEquals(malformed, ProtocolFormatter.fromItem(item(JSONArray().put(text(malformed)))).body)
    }

    @Test fun urlAndDataImagesSupportBothProtocolFieldSpellings() {
        val values = JSONArray()
            .put(JSONObject().put("type", "image").put("url", "https://example.test/a.png"))
            .put(JSONObject().put("type", "input_image").put("image_url", JSONObject().put("url", "data:image/png;base64,YQ==")))
        assertEquals(listOf("https://example.test/a.png", "data:image/png;base64,YQ=="), MessageImages.fromItem(item(values)).map { it.source })
    }

    @Test fun toolImageDataAndEmbeddedResourcesAreExtracted() {
        val result = JSONObject().put("content", JSONArray()
            .put(JSONObject().put("type", "image").put("mimeType", "image/png").put("data", "YQ=="))
            .put(JSONObject().put("type", "resource").put("resource", JSONObject().put("mimeType", "image/jpeg").put("blob", "Yg=="))))
        val message = ProtocolFormatter.fromItem(JSONObject().put("id", "tool").put("type", "mcpToolCall").put("result", result))
        assertEquals(2, message.images.size)
        assertEquals("data:image/png;base64,YQ==", message.images.first().source)
        assertFalse(message.body.contains("YQ=="))
        assertFalse(message.body.contains("Yg=="))
    }

    @Test fun generatedAndViewedImagesUsePathsAndInlineGenerationFallback() {
        for (type in listOf("imageView", "imageGeneration")) {
            val item = JSONObject().put("id", "gen").put("type", type).put(if (type == "imageView") "path" else "savedPath", "/tmp/generated.png")
            assertEquals("/tmp/generated.png", ProtocolFormatter.fromItem(item).images.single().source)
        }
        val generated = JSONObject().put("id", "gen").put("type", "imageGeneration").put("result", "YQ==")
        assertEquals("data:image/png;base64,YQ==", ProtocolFormatter.fromItem(generated).images.single().source)
    }

    @Test fun dynamicToolImagesAreDisplayed() {
        val item = JSONObject().put("type", "dynamicToolCall").put("contentItems", JSONArray()
            .put(JSONObject().put("type", "inputImage").put("imageUrl", "https://example.test/tool.png")))
        assertEquals("https://example.test/tool.png", MessageImages.fromItem(item).single().source)
    }

    @Test fun completingAnImageItemInvalidatesTheExistingRow() {
        val existing = ChatMessage("gen", MessageKind.ASSISTANT, "Codex", "").apply { dirty = false }
        ProtocolFormatter.update(existing, JSONObject().put("type", "imageGeneration").put("savedPath", "/tmp/new.png"))
        assertTrue(existing.dirty)
        assertEquals("/tmp/new.png", existing.images.single().source)
    }

    @Test fun imageSourcesPreserveWindowsPathsAndDecodeLocalUris() {
        assertEquals("E:/图片 1.png", MessageImage.fromSource("file:///E:/%E5%9B%BE%E7%89%87%201.png")?.source)
        assertEquals("/mnt/data/a.png", MessageImage.fromSource("sandbox:/mnt/data/a.png")?.source)
        assertEquals("E:\\foo.png", MessageImage.fromSource("E:\\foo.png")?.source)
        assertNull(MessageImage.fromSource("javascript:alert(1)"))
        assertNull(MessageImage.fromSource("content://other.app/private"))
    }

    @Test fun cachesSeparateDevicesAndRelativePathsButUploadsSurviveWorkspaceChanges() {
        val image = MessageImage("preview.png")
        assertNotEquals(image.cacheKey("agent-a", "/one"), image.cacheKey("agent-b", "/one"))
        assertNotEquals(image.cacheKey("agent-a", "/one"), image.cacheKey("agent-a", "/two"))
        val upload = image.copy(attachmentId = "upload-id")
        assertEquals(upload.cacheKey("agent-a", "/one"), upload.cacheKey("agent-a", "/two"))
    }

    @Test fun localHistoryImagesCacheByMessageWithoutChangingAttachmentIdentity() {
        val image = MessageImage("/tmp/preview.png", messageId = "first")
        assertTrue(image.allowsEarlyCache)
        assertNotEquals(image.cacheKey("agent", "/project"), image.copy(messageId = "second").cacheKey("agent", "/project"))
        val upload = image.copy(attachmentId = "file-id")
        assertEquals(upload.cacheKey("agent", "/project"), upload.copy(messageId = "second").cacheKey("agent", "/project"))
    }

    @Test fun mutableAgentPathsAlwaysBypassEarlyMemoryAndDiskHits() {
        assertFalse(MessageImage("preview.png").allowsEarlyCache)
        assertFalse(MessageImage("/tmp/preview.png").allowsEarlyCache)
        assertFalse(MessageImage("E:\\preview.png").allowsEarlyCache)
        assertTrue(MessageImage("preview.png", attachmentId = "upload-id").allowsEarlyCache)
        assertTrue(MessageImage("https://example.test/preview.png").allowsEarlyCache)
        assertTrue(MessageImage("data:image/png;base64,YQ==").allowsEarlyCache)
    }
}
