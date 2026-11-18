package com.codexrm.mobile

import android.app.Activity
import android.app.Instrumentation
import android.os.SystemClock
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object DirectFilesCheck {
    fun verify(test: Instrumentation, screen: Activity): String {
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
        val downloaded = AtomicReference<Result<StagedDownload>>()
        val done = CountDownLatch(1)
        test.runOnMainSync {
            DirectFileDownload(client!!).start(screen.cacheDir, source, { _, _ -> }) { downloaded.set(it); done.countDown() }
        }
        check(done.await(25, TimeUnit.SECONDS)) { "Android download timed out" }
        val staged = downloaded.get().getOrThrow()
        val data = try { staged.file.readBytes() } finally { staged.file.delete() }
        fun digest(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
        check(digest(data).contentEquals(digest("artifact-fixture\n".repeat(65_536).toByteArray()))) { "Download checksum differs" }

        val change = JSONObject().put("type", "fileChange").put("status", "completed").put("id", "p2p-qa-file-change")
            .put("changes", JSONArray(listOf(source.path, "reports/internal.go").map { path ->
                JSONObject().put("path", path).put("kind", JSONObject().put("type", "add"))
            }))
        test.runOnMainSync {
            val field = screen.javaClass.getDeclaredField("adapter").apply { isAccessible = true }
            val adapter = field.get(screen) as MessageAdapter
            check(adapter.showFileCards)
            adapter.replace(listOf(ProtocolFormatter.fromItem(change)))
            val row = adapter.getView(0, null, android.widget.ListView(screen))
            val allowed = arrayListOf<View>()
            val rejected = arrayListOf<View>()
            row.findViewsWithText(allowed, "下载 $name", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
            row.findViewsWithText(rejected, "下载 internal.go", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
            check(allowed.size == 1 && rejected.isEmpty()) { "Unexpected download cards" }
        }

        test.runOnMainSync {
            val label = screen.javaClass.getDeclaredField("cwdLabel").apply { isAccessible = true }.get(screen) as View
            check(label.performLongClick()) { "Long press did not open file manager" }
        }
        fun visible(text: String) = test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.isNotEmpty() == true
        waitFor("dual panes and remote directory") { visible("电脑 · 工作区") && visible("手机") && visible("reports") }
        return "workspaceLongPress=true dualPane=true cardFilter=true p2pBytes=${data.size} sha256Match=true"
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
