package com.codexrm.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal object DirectFilesCheck {
    fun verify(test: Instrumentation, screen: Activity, device: Device, custom: Boolean, batchCopy: Boolean = false, otherDriveFile: String? = null): String {
        var client: CodexClient? = null
        var cwd = ""
        waitFor("active P2P and workspace") {
            test.runOnMainSync {
                client = screen.javaClass.getDeclaredField("client").apply { isAccessible = true }.get(screen) as? CodexClient
                cwd = screen.javaClass.getDeclaredField("currentCwd").apply { isAccessible = true }.get(screen) as String
            }
            client != null && cwd.isNotEmpty() && onMain(test) {
                client!!.usingDirect && (screen.javaClass.getDeclaredField("adapter").apply { isAccessible = true }.get(screen) as MessageAdapter).showFileCards
            }
        }

        val name = "P2P QA.xlsx"
        val source = RemoteFile(cwd, "", "reports/$name")
        val key = WorkspaceDownloads.key(device, source)
        val downloads = WorkspaceDownloads(screen)
        downloads.setLocation(null)
        val change = JSONObject().put("type", "fileChange").put("status", "completed").put("id", "p2p-qa-file-change")
            .put("changes", JSONArray(listOf(source.path, "reports/internal.go").map { path ->
                JSONObject().put("path", path).put("kind", JSONObject().put("type", "add"))
            }))
        val adapter = screen.javaClass.getDeclaredField("adapter").apply { isAccessible = true }.get(screen) as MessageAdapter
        fun clickCard(action: String, rejectInternal: Boolean = false) {
            test.runOnMainSync {
                val row = adapter.getView(0, null, android.widget.ListView(screen))
                val allowed = arrayListOf<View>()
                val rejected = arrayListOf<View>()
                row.findViewsWithText(allowed, "$action $name", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                row.findViewsWithText(rejected, "下载 internal.go", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                check(allowed.size == 1 && (!rejectInternal || rejected.isEmpty())) { "Unexpected file cards for $action" }
                check(allowed.single().performClick())
            }
        }
        test.runOnMainSync { adapter.replace(listOf(ProtocolFormatter.fromItem(change))) }
        clickCard("下载", rejectInternal = true)
        waitFor("file saved without a picker") { downloads.contains(key) }
        val uri = requireNotNull(WorkspaceDownloads(screen).findReadable(key))
        val resolver = screen.contentResolver
        fun verifyBytes(saved: android.net.Uri): Int {
            val data = resolver.openInputStream(saved)!!.use { it.readBytes() }
            fun digest(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
            check(digest(data).contentEquals(digest("artifact-fixture\n".repeat(65_536).toByteArray()))) { "Download checksum differs" }
            return data.size
        }
        val bytes = verifyBytes(uri)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
                check(it.moveToFirst() && it.getString(0) == "${Environment.DIRECTORY_DOWNLOADS}/" && it.getInt(1) == 0)
            }
        }
        fun visible(text: String) = test.uiAutomation.rootInActiveWindow?.matchingTextNodes(text)?.isNotEmpty() == true
        fun clickVisible(text: String, prefix: Boolean = false, longPress: Boolean = false) {
            var match: android.graphics.Rect? = null
            waitFor(text) {
                match = test.uiAutomation.rootInActiveWindow?.matchingTextNodes(text)
                    ?.firstOrNull {
                        val label = (it.text ?: it.contentDescription)?.toString()
                        it.isEnabled && it.isVisibleToUser &&
                            (if (prefix) label?.startsWith(text) == true else label.equals(text, ignoreCase = true))
                    }?.let { android.graphics.Rect().also(it::getBoundsInScreen) }
                match?.isEmpty == false
            }
            val bounds = requireNotNull(match)
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                if (longPress && action == MotionEvent.ACTION_UP) SystemClock.sleep(750)
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
        }
        if (otherDriveFile != null) {
            val parts = otherDriveFile.replace('\\', '/').split('/')
            val drive = parts.first() + "\\"
            val workspaceDrive = cwd.take(2) + "\\"
            check(!workspaceDrive.equals(drive, ignoreCase = true)) { "Expected a fixture on a different drive" }
            clickVisible("电脑文件管理")
            waitFor("computer drive list") { visible("此电脑") && visible(workspaceDrive) && visible(drive) }
            clickVisible(drive)
            clickVisible("上一级")
            waitFor("back to drives") { visible("此电脑") && visible(drive) }
            clickVisible("后退")
            waitFor("back into drive") { visible(parts[1]) }
            parts.drop(1).forEach { clickVisible(it) }
            val otherKey = WorkspaceDownloads.key(device, RemoteFile(cwd, "", otherDriveFile, computer = true))
            waitFor("cross-drive file saved") { downloads.contains(otherKey) }
            val downloaded = requireNotNull(downloads.findReadable(otherKey))
            try {
                check(resolver.openInputStream(downloaded)!!.bufferedReader().use { it.readText() } == "computer-files-fixture")
            } finally {
                resolver.delete(downloaded, null, null)
            }
            clickVisible("关闭文件管理")
            test.runOnMainSync {
                val workspace = screen.javaClass.getDeclaredField("workspaceButton").apply { isAccessible = true }.get(screen) as View
                check(workspace.performLongClick())
            }
            waitFor("workspace shortcut") { visible("reports") }
            clickVisible("上一级")
            waitFor("workspace parent") { visible(cwd.substringAfterLast('\\')) }
            clickVisible("后退")
            waitFor("back to workspace") { visible("reports") }
            clickVisible("关闭文件管理")
            check(resolver.delete(uri, null, null) == 1)
            return "computerDrives=true crossDriveDownload=true workspaceParent=true sha256Match=true"
        }
        test.runOnMainSync {
            val buttons = arrayListOf<View>()
            screen.window.decorView.findViewsWithText(buttons, "打开侧栏", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
            check(buttons.single().performClick())
            buttons.clear()
            screen.window.decorView.findViewsWithText(buttons, "设置", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
            check(buttons.single { it.contentDescription == "设置" }.performClick())
        }
        waitFor("default download location in settings") { visible("下载位置") && visible("Download") && visible("检查更新") }
        check(!visible("文件管理")) { "Settings must not expose a separate file manager" }
        var saved = uri
        if (custom) {
            clickVisible("下载位置")
            clickVisible("选择文件夹")
            clickVisible("Documents")
            clickVisible("CodexRM-D", prefix = true)
            clickVisible(if (visible("使用此文件夹")) "使用此文件夹" else "Use this folder")
            clickVisible(if (visible("允许")) "允许" else "Allow")
            waitFor("authorized custom download directory") { downloads.location != null }
            waitFor("P2P reconnected after the folder picker") {
                onMain(test) {
                    client = screen.javaClass.getDeclaredField("client").apply { isAccessible = true }.get(screen) as? CodexClient
                    client?.usingDirect == true && adapter.showFileCards
                }
            }
            test.runOnMainSync { adapter.replace(listOf(ProtocolFormatter.fromItem(change))) }
            check(resolver.delete(uri, null, null) == 1)
            // Clicking a stale card against an active connection downloads into the new destination.
            clickCard("打开")
            waitFor("file saved to the custom directory") {
                downloads.findReadable(key)?.takeIf { it != uri }?.let { saved = it } != null
            }
            check(android.provider.DocumentsContract.isTreeUri(saved))
            verifyBytes(saved)
            downloads.setLocation(null)
            check(downloads.findReadable(key) == saved) { "Changing location revoked access to a downloaded file" }
            if (batchCopy) {
                val tree = android.provider.DocumentsContract.buildTreeDocumentUri(saved.authority,
                    android.provider.DocumentsContract.getTreeDocumentId(saved))
                downloads.setBrowserFolder(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        } else {
            clickVisible("返回")
        }

        test.runOnMainSync {
            val label = screen.javaClass.getDeclaredField("workspaceButton").apply { isAccessible = true }.get(screen) as View
            check(label.performLongClick()) { "Long press did not open file manager" }
        }
        waitFor("side-by-side panes and remote directory") {
            val root = test.uiAutomation.rootInActiveWindow
            val headings = listOf("电脑，当前窗格", "切换到手机").map { description ->
                root?.findAccessibilityNodeInfosByText(description)
                    ?.firstOrNull { it.contentDescription == description && it.isVisibleToUser }
            }
            val (computer, phone) = headings
            if (computer == null || phone == null || !visible("reports")) false
            else {
                val left = android.graphics.Rect().also(computer::getBoundsInScreen)
                val right = android.graphics.Rect().also(phone::getBoundsInScreen)
                left.top == right.top && left.right <= right.left && left.width() > 0 && right.width() > 0
            }
        }
        clickVisible("reports", longPress = true)
        waitFor("single file action grid") { visible("复制 →") && visible("移动 →") && visible("属性") && visible("关闭") }
        clickVisible("关闭")
        clickVisible("reports")
        waitFor("entered reports") { visible(name) }
        clickVisible("后退")
        waitFor("back to workspace root") { visible("reports") }
        clickVisible("前进")
        waitFor("forward to reports") { visible(name) }
        clickVisible("多选")
        clickVisible(name)
        waitFor("selection count") { visible("已选 1") }
        clickVisible("所选文件的操作")
        waitFor("selection action grid") { visible("复制 →") && visible("移动 →") }
        if (batchCopy) {
            val original = saved
            clickVisible("关闭")
            clickVisible("notes.txt")
            waitFor("two files selected") { visible("已选 2") }
            clickVisible("所选文件的操作")
            waitFor("batch action grid") { visible("已选 2 项") && visible("复制 →") }
            test.uiAutomation.takeScreenshot()?.let { screenshot ->
                screen.cacheDir.resolve("workspace-menu.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
            val notesKey = WorkspaceDownloads.key(device, source.copy(path = "reports/notes.txt"))
            clickVisible("复制 →")
            waitFor("two distinct copies written") {
                downloads.findReadable(key)?.takeIf { it != original }?.let { saved = it } != null && downloads.contains(notesKey)
            }
            verifyBytes(saved)
            val notes = requireNotNull(downloads.findReadable(notesKey))
            check(resolver.openInputStream(notes)!!.bufferedReader().use { it.readText() } == "workspace-copy-fixture")
            if (custom) {
                check(android.provider.DocumentsContract.isTreeUri(notes))
                clickVisible("上一级")
                clickVisible("notes.txt", longPress = true)
                waitFor("phone action grid") { visible("← 复制") && visible("← 移动") }
                clickVisible("重命名")
                waitFor("rename field") {
                    test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("notes.txt")?.any {
                        it.className == "android.widget.EditText" && it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                            android.os.Bundle().apply {
                                putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "renamed.txt")
                            })
                    } == true
                }
                clickVisible("保存")
                clickVisible("renamed.txt", longPress = true)
                clickVisible("删除")
                waitFor("delete confirmation") { visible("删除 renamed.txt？") }
                clickVisible("删除")
                waitFor("phone file removed") { visible("手机") && !visible("renamed.txt") }
                check(android.provider.DocumentsContract.deleteDocument(resolver, original))
            } else {
                check(resolver.delete(notes, null, null) == 1)
                check(resolver.delete(original, null, null) == 1)
            }
        } else {
            clickVisible("关闭")
        }
        val reopened = AtomicReference<Intent>()
        val openCount = AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                reopened.set(intent)
                openCount.incrementAndGet()
                return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
            }
        }
        test.addMonitor(monitor)
        try {
            test.runOnMainSync {
                val ui = screen.javaClass.getDeclaredField("workspaceFiles").apply { isAccessible = true }.get(screen) as WorkspaceFileUi
                ui.closeManager()
                client!!.stop()
                check(!client!!.usingDirect)
                adapter.showFileCards = false
            }
            clickCard("打开")
            waitFor("saved file opened with P2P disconnected") { openCount.get() == 1 }
            check(reopened.get().data == saved && reopened.get().flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            check(downloads.findReadable(key) == saved)
        } finally {
            test.removeMonitor(monitor)
            if (custom) android.provider.DocumentsContract.deleteDocument(resolver, saved) else resolver.delete(saved, null, null)
            check(downloads.findReadable(key) == null)
        }
        return "defaultDownload=true settings=true customDownload=$custom offlineReopen=true dualPane=true cardFilter=true actionGrid=true paneHistory=true batchCopy=$batchCopy p2pBytes=$bytes sha256Match=true"
    }

    fun verifyImageReference(test: Instrumentation, screen: Activity): String {
        check(android.os.Build.VERSION.SDK_INT >= 29)
        var client: CodexClient? = null
        waitFor("active P2P and workspace") {
            onMain(test) {
                client = screen.javaClass.getDeclaredField("client").apply { isAccessible = true }.get(screen) as? CodexClient
                val cwd = screen.javaClass.getDeclaredField("currentCwd").apply { isAccessible = true }.get(screen) as String
                client?.usingDirect == true && cwd.isNotEmpty()
            }
        }
        val loader = screen.javaClass.getDeclaredField("imageLoader").apply { isAccessible = true }.get(screen) as MessageImageLoader
        val id = "image-ref-${SystemClock.elapsedRealtime()}"
        val destination = "reports/%E9%A2%84%E8%A7%88%20A%2BB.png"
        lateinit var body: android.widget.TextView
        lateinit var imageLink: android.text.style.ClickableSpan
        var downloaded: ArtifactCard? = null
        test.runOnMainSync {
            body = android.widget.TextView(screen).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2)
            }
            Markdown(screen, loader) { downloaded = it }.bind(body, ProtocolFormatter.fromItem(JSONObject()
                .put("id", id).put("type", "agentMessage")
                .put("text", "[预览图片]($destination) [下载文件](reports/notes.txt)")))
            val richText = body.text as android.text.Spanned
            val links = richText.getSpans(0, richText.length, android.text.style.ClickableSpan::class.java)
                .associateBy { richText.subSequence(richText.getSpanStart(it), richText.getSpanEnd(it)).toString() }
            requireNotNull(links["下载文件"]).onClick(body)
            check(downloaded?.path == "reports/notes.txt")
            downloaded = null
            imageLink = requireNotNull(links["预览图片"])
            imageLink.onClick(body)
        }
        fun matching(label: String) = arrayListOf<View>().also { found ->
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach {
                it.findViewsWithText(found, label, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
            }
        }
        fun awaitPreview() = waitFor("full-screen image from Agent") {
            onMain(test) {
                val picture = matching("预览 A+B.png").singleOrNull() as? android.widget.ImageView
                val bitmap = (picture?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                bitmap?.width == 2400 && bitmap.height == 960 && bitmap.getPixel(0, 0) == android.graphics.Color.RED
            }
        }
        awaitPreview()
        test.runOnMainSync {
            check(downloaded == null)
            check(matching("关闭图片预览").single().performClick())
            client!!.stop()
            imageLink.onClick(body)
        }
        awaitPreview()
        test.runOnMainSync { check(matching("关闭图片预览").single().performClick()) }
        return "PASS: Codex Markdown image link loaded the Agent PNG over P2P and reopened it offline. Ordinary links still download."
    }

    private fun onMain(test: Instrumentation, action: () -> Boolean): Boolean {
        var value = false
        test.runOnMainSync { value = action() }
        return value
    }

    private fun waitFor(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 25_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        error("Timed out waiting for $label")
    }
}
