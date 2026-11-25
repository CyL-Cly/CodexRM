package com.codexrm.mobile

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.util.concurrent.atomic.AtomicReference

internal object PhoneTransferCheck {
    fun run(test: Instrumentation, mode: String, outgoing: String): String {
        val context = test.targetContext
        val owner = DeviceStore(context).load().single()
        val activity = test.startActivitySync(Intent().setClassName(context.packageName, "com.codexrm.mobile.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun <T> onUi(action: () -> T): T {
            val result = AtomicReference<Result<T>>()
            test.runOnMainSync { result.set(runCatching(action)) }
            return result.get().getOrThrow()
        }
        fun field(obj: Any, name: String) = obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obj)
        fun call(name: String) = activity.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
        fun capture(name: String) {
            SystemClock.sleep(650)
            val image = checkNotNull(test.uiAutomation.takeScreenshot())
            File(context.filesDir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        fun await(label: String, predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 40_000
            while (SystemClock.elapsedRealtime() < end) {
                if (predicate()) return
                SystemClock.sleep(200)
            }
            error("Timeout: $label")
        }
        fun buttons(view: View, label: String): List<MiuixButtonView> = when (view) {
            is MiuixButtonView -> if (view.text == label && view.isShown && view.isEnabled) listOf(view) else emptyList()
            is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it), label) }
            else -> emptyList()
        }
        fun pageButtons(label: String) = buttons(field(activity, "transferPage") as View, label)
        val storage = TransferStorage(context)
        val other = owner.copy(clientId = owner.clientId + "-unrelated")
        val ui = onUi { field(activity, "transfers") as PhoneTransferUi }
        try {
            await("Agent capability") { onUi { (field(activity, "info") as org.json.JSONObject).optBoolean("phoneTransfer") } }
            if (mode == "relay" || mode == "cancel") {
                onUi { ui.enqueue(owner, SharedContent(outgoing, emptyList())); call("openTransfers") }
                await("outgoing cached and remote pending, no P2P") {
                    onUi { (field(ui, "status") as? TextView)?.text?.toString().orEmpty().let { it.contains("缓存管道") && it.contains("1 个待发送 · 1 个待接收") } }
                }
                check(storage.entries(owner).single().state == "pending")
                check(storage.entries(other).isEmpty())
                onUi {
                    check((field(activity, "topbarTitle") as TextView).text == "文件传输助手")
                    check((field(activity, "transferPage") as View).isShown)
                    check(!(field(activity, "chatBody") as View).isShown)
                }
                if (mode == "cancel") {
                    await("both queued transfers offer cancellation") { onUi { pageButtons("取消传输").size == 2 } }
                    onUi { pageButtons("取消传输").forEach { check(it.performClick()) } }
                    await("both cancellations persisted") {
                        storage.entries(owner).map { it.state }.toSet() == setOf("cancelledSend", "cancelledIncoming")
                    }
                    val restored = TransferStorage(context).entries(owner)
                    check(restored.none { storage.payload(owner, it).exists() })
                    check(storage.entries(other).isEmpty())
                    return "cancelled=true; outgoingCacheRemoved=true; offlineIncomingCancellationPersisted=true"
                }
                capture("file-transfer-cache")
                onUi { activity.onBackPressed() }
                SystemClock.sleep(2600)
                onUi {
                    val header = field(activity, "topbar") as ViewGroup
                    val title = field(activity, "topbarTitle") as TextView
                    val info = field(activity, "info") as org.json.JSONObject
                    val originalMock = info.opt("mock")
                    try {
                        info.put("mock", true)
                        call("updateControls")
                        check(title.isShown && title.text == "CodexRM")
                        check((0 until header.childCount).map(header::getChildAt).none { it is TextView && it.text == "演示模式" })
                        check((0 until header.childCount).map(header::getChildAt).filter { it.visibility == View.VISIBLE }
                            .all { it.width > 0 && it.left >= 0 && it.right <= header.width })
                    } finally {
                        info.put("mock", originalMock)
                        call("updateControls")
                    }
                }
                capture("chat-compose")
                onUi { call("showConfiguration") }
                capture("chat-configuration")
                onUi { (field(activity, "configSheet") as PredictiveBackDialog).dismiss(); call("showAttachmentSheet") }
                capture("chat-attachments")
                onUi { (field(activity, "attachmentSheet") as PredictiveBackDialog).dismiss(); call("openSidebar") }
                capture("chat-sidebar")
                onUi { call("openSearchEntry") }
                capture("chat-search-empty")
                onUi {
                    val search = field(activity, "referenceSearch") ?: error("missing search")
                    val input = field(search, "input") as android.widget.EditText
                    check(input.isFocused)
                    input.setText("fixture")
                }
                capture("chat-search-results")
                return "relayOnly=true; pendingUpload=1; pendingDownload=1; scopedPair=true; unclippedHeader=true; demoBadge=false; UI screenshots captured"
            }
            onUi { call("openTransfers") }
            await("both files delivered over P2P") {
                val rows = storage.entries(owner)
                rows.any { it.state == "sent" } && rows.any { it.state == "received" }
            }
            val received = storage.entries(owner).single { it.state == "received" }
            check(storage.payload(owner, received).readText() == "fixture from Windows\n")
            check(storage.entries(other).isEmpty())
            await("P2P status") { onUi { (field(ui, "status") as? TextView)?.text?.toString().orEmpty().contains("P2P 直连") } }
            capture("file-transfer-direct")
            if (Build.VERSION.SDK_INT < 29) {
                test.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            await("received file offers save") { onUi { pageButtons("保存").size == 1 } }
            onUi { check(pageButtons("保存").single().performClick()) }
            await("saved file offers open without a location picker") { onUi { pageButtons("打开").size == 1 } }
            val downloads = WorkspaceDownloads(context)
            val key = WorkspaceDownloads.transferKey(owner, received.id)
            val saved = checkNotNull(downloads.findReadable(key))
            check(saved.scheme == "content")
            check(context.contentResolver.openInputStream(saved)?.bufferedReader()?.use { it.readText() } == "fixture from Windows\n")
            check(!downloads.contains(WorkspaceDownloads.transferKey(other, received.id)))
            val monitor = test.addMonitor(IntentFilter(Intent.ACTION_VIEW).apply {
                addDataScheme("content")
                addDataType("text/plain")
            }, null, true)
            try {
                onUi { check(pageButtons("打开").single().performClick()) }
                await("open dispatches the saved file to Android") { onUi { monitor.hits == 1 } }
            } finally { test.removeMonitor(monitor) }
            return "p2p=true; androidToWindows=sent; windowsToAndroid=received; contentVerified=true; scopedPair=true; savedToDownloads=true; openDispatched=true"
        } finally { onUi { activity.finish() } }
    }
}