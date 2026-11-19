package com.codexrm.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtifactCardTest {
    @Test fun explicitLocalMarkdownLinksAreCandidates() {
        val text = """
            [构建结果](outputs/test%20build.apk)
            [Windows](C:/project/演示.pptx)
            [文件](file:///C:/project/reports/summary.pdf)
            [图片](sandbox:/mnt/data/sample.png)
            [公网](https://example.com/releases/out.apk)
            [远程文件](file://NAS/exports/report.docx)
            [相对网络](//example.com/out.mp4)
            [代码](/E:/code/Go/CodexRM/src/App.kt:42:7)
            [归档](output/result+notes.zip)
            [说明](README)
            [内部锚点](#L12)
            [目录](outputs/)
            [网络](https://example.com/src/App.kt#L42)
            [Android 内容](content://provider/item)
            [APK](E:/code/Go/CodexRM/android/app/build/outputs/apk/debug/app-debug.apk)
        """.trimIndent()
        val cards = ProtocolFormatter.fromItem(JSONObject().put("type", "agentMessage").put("text", text)).files
        assertEquals(listOf(
            "outputs/test build.apk", "C:/project/演示.pptx", "C:/project/reports/summary.pdf", "/mnt/data/sample.png",
            "E:/code/Go/CodexRM/src/App.kt", "output/result+notes.zip", "README",
            "E:/code/Go/CodexRM/android/app/build/outputs/apk/debug/app-debug.apk",
        ), cards.map { it.path })
        assertEquals(true, cards.all { it.linked })
    }

    @Test fun onlyCompletedOfficeMediaAndApkChangesAppear() {
        fun change(path: String, kind: String = "add", destination: String? = null) = JSONObject()
            .put("path", path).put("kind", JSONObject().put("type", kind).put("move_path", destination))
        val item = JSONObject().put("id", "patch").put("type", "fileChange").put("status", "completed").put("changes", JSONArray(listOf(
            change("reports/Q4.XLSX"), change("C:\\work\\video.mp4", "update"), change("old.tmp", "update", "out/new.apk"),
            change("asset.jpg"), change("asset.jpg"), change("out/guide.pdf"), change("index.html"), change("main.go"),
            change("out/settings.zip"), change("recording.mp3", "delete"),
        )))
        val message = ProtocolFormatter.fromItem(item)
        assertEquals(listOf("Q4.XLSX", "video.mp4", "new.apk", "asset.jpg", "guide.pdf"), message.files.map { it.name })
        assertEquals(listOf("新增", "修改", "修改", "新增", "新增"), message.files.map { it.change })
        ProtocolFormatter.update(message, item.put("status", "failed"))
        assertEquals(emptyList<ArtifactCard>(), message.files)
    }
}
