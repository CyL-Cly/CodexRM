package com.codexrm.mobile

import android.app.Activity
import android.app.Instrumentation
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.core.content.FileProvider
import androidx.core.view.ContentInfoCompat
import androidx.core.view.ViewCompat
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object ClaudeUxCheck {
    fun run(test: Instrumentation) {
        val context = test.targetContext
        var activity: Activity? = null
        val agent = "claude-ux-${java.util.UUID.randomUUID()}"
        val cwd = "/fixture/claude-ux"
        val key = "$agent:workspace:$cwd"
        val store = DeviceStore(context)
        val attachments = AttachmentStore(context)
        val sample = File(context.cacheDir, "attachment-camera/markup-fixture.png")
        var fixtureInstalled = false
        val checks = Bundle()
        val outcome = try {
            check(context.packageName.endsWith(".imageqa"))
            val fixture = Device("fixture", "fixture", "http://127.0.0.1:9", agent, "本地测试")
            val priorDevices = store.load()
            val priorSelected = store.selectedId
            val priorShare = store.shareToNewDraft
            fixtureInstalled = true
            try {
                store.save(listOf(fixture))
                store.selectedId = agent
                store.selectWorkspace(agent, cwd)
                store.shareToNewDraft = true
                store.saveDraft(agent, "", "已有草稿", cwd)
                val screen = test.startActivitySync(Intent(Intent.ACTION_SEND).setClass(context, MainActivity::class.java)
                    .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "待人工确认").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                activity = screen
                fun field(name: String) = screen.javaClass.getDeclaredField(name).apply { isAccessible = true }
                fun invoke(name: String) = screen.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(screen)
                val composer = field("composer").get(screen) as EditText
                test.runOnMainSync {
                    invoke("cancelUpdateOperation")
                    field("started").setBoolean(screen, false)
                    invoke("disconnect")
                    check(composer.text.toString() == "已有草稿\n\n待人工确认") { composer.text.toString() }
                    check(field("currentThreadId").get(screen) == "")
                    check(!field("sending").getBoolean(screen))
                    field("online").setBoolean(screen, true)
                    field("loaded").setBoolean(screen, true)
                    field("info").set(screen, JSONObject().put("attachments", true))
                    invoke("updateControls")
                }
                checks.putBoolean("directShareDraft", true)

                val orderedDraft = "已有草稿\n\n待人工确认\n\n第一个分享\n\n第二个分享"
                test.runOnMainSync {
                    val accept = screen.javaClass.getDeclaredMethod("acceptExternalIntent", Intent::class.java).apply { isAccessible = true }
                    field("sending").setBoolean(screen, true)
                    for (text in listOf("第一个分享", "第二个分享")) {
                        accept.invoke(screen, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text))
                    }
                    field("sending").setBoolean(screen, false)
                    invoke("updateControls")
                }
                var queueState = ""
                try {
                    await("queued shares imported in order") {
                        var imported = false
                        test.runOnMainSync {
                            val actual = (field("composer").get(screen) as EditText).text.toString()
                            imported = actual == orderedDraft
                            queueState = "draft=$actual queue=${(field("pendingShares").get(screen) as Collection<*>).size} " +
                                listOf("resumed", "loading", "sending", "preparingAttachments")
                                    .joinToString { "$it=${field(it).getBoolean(screen)}" }
                        }
                        imported
                    }
                } catch (e: IllegalStateException) { error("${e.message}: $queueState") }
                checks.putBoolean("orderedShares", true)

                val shortcuts = context.getSystemService(ShortcutManager::class.java).dynamicShortcuts
                check(shortcuts.map { it.id }.containsAll(listOf("new_chat", "search_chats")))
                val providers = AppWidgetManager.getInstance(context).getInstalledProvidersForPackage(context.packageName, null)
                check(providers.any { it.provider.className == ChatEntryWidget::class.java.name })
                checks.putBoolean("launcherEntries", true)

                sample.parentFile!!.mkdirs()
                Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }.let { image ->
                    sample.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachment-camera", sample)
                val payload = ContentInfoCompat.Builder(ClipData(ClipDescription("image", arrayOf("image/png")), ClipData.Item(uri)),
                    ContentInfoCompat.SOURCE_CLIPBOARD).build()
                test.runOnMainSync { check(ViewCompat.performReceiveContent(composer, payload) == null) }
                await("pasted image staged") { attachments.load(key).size == 1 && !field("preparingAttachments").getBoolean(screen) }
                checks.putBoolean("clipboardStaged", true)

                fun openMarkup() {
                    test.runOnMainSync {
                        val target = attachments.load(key).single()
                        screen.javaClass.getDeclaredMethod("editAttachmentMarkup", JSONObject::class.java, File::class.java)
                            .apply { isAccessible = true }.invoke(screen, target, attachments.localFile(target))
                    }
                    var state = ""
                    try {
                        await("markup dialog visible") {
                            var visible = false
                            test.runOnMainSync {
                                visible = field("markupDialog").get(screen) != null
                                state = listOf("resumed", "loading", "sending", "openingMarkup", "preparingAttachments")
                                    .joinToString { "$it=${field(it).getBoolean(screen)}" }
                            }
                            visible
                        }
                    } catch (e: IllegalStateException) { error("${e.message}: $state") }
                }
                val original = attachments.load(key).single()
                openMarkup()
                test.runOnMainSync { (field("markupDialog").get(screen) as MiuixDialog).cancel() }
                test.waitForIdleSync()
                check(attachments.load(key).single().string("draftId") == original.string("draftId"))
                openMarkup()
                test.waitForIdleSync()
                test.runOnMainSync {
                    val dialog = field("markupDialog").get(screen) as MiuixDialog
                    val canvas = (dialog.contentView as android.view.ViewGroup).getChildAt(0) as MarkupSurface
                    val x = canvas.width / 2f
                    val y = canvas.height / 2f
                    listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                        MotionEvent.obtain(0, 0, action, x, y, 0).let { canvas.dispatchTouchEvent(it); it.recycle() }
                    }
                    check(canvas.canUndo)
                    check(dialog.getButton(MiuixDialog.POSITIVE).performClick())
                }
                await("markup replaced the same slot") { attachments.load(key).single().string("draftId") != original.string("draftId") }
                val saved = attachments.load(key).single()
                check(saved.string("name").endsWith("-标注.png"))
                val annotated = BitmapFactory.decodeFile(attachments.localFile(saved)?.path)
                check(Color.red(annotated.getPixel(50, 50)) > 180)
                annotated.recycle()
                checks.putBoolean("markupCancelAndSave", true)

                val search = ChatEntryPoints.intent(context, ChatEntryPoints.SEARCH)
                test.runOnMainSync {
                    val method = screen.javaClass.getDeclaredMethod("acceptExternalIntent", Intent::class.java).apply { isAccessible = true }
                    method.invoke(screen, search)
                    check(field("sidebarOpen").getBoolean(screen))
                    check((field("searchInput").get(screen) as EditText).isFocused)
                }
                checks.putBoolean("searchEntryRoutes", true)
                test.runOnMainSync {
                    val shortcutGroups = mutableListOf<KeyboardShortcutGroup>()
                    screen.onProvideKeyboardShortcuts(shortcutGroups, null, 0)
                    check(shortcutGroups.flatMap { it.items }.map { it.keycode }.containsAll(listOf(KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_K)))
                    val ctrlN = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_N, 0, KeyEvent.META_CTRL_ON)
                    check(screen.onKeyShortcut(KeyEvent.KEYCODE_N, ctrlN))
                    check(!field("sidebarOpen").getBoolean(screen))
                    check(composer.text.toString() == orderedDraft)
                }
                checks.putBoolean("keyboardShortcuts", true)
                checkDesktopShareWait(test, fixture)
                checks.putBoolean("desktopShareWait", true)
            } finally {
                activity?.let { test.runOnMainSync { it.finish() }; test.waitForIdleSync() }
                store.save(priorDevices)
                store.selectedId = priorSelected
                store.shareToNewDraft = priorShare
            }
            Activity.RESULT_OK to checks
        } catch (e: Throwable) {
            Activity.RESULT_CANCELED to Bundle().apply { putString("error", e.stackTraceToString()) }
        } finally {
            if (fixtureInstalled) {
                store.saveDraft(agent, "", "", cwd)
                attachments.load(key).forEach { attachments.remove(key, it) }
                sample.delete()
            }
        }
        test.finish(outcome.first, outcome.second)
    }

    private fun checkDesktopShareWait(test: Instrumentation, device: Device) {
        check(NativeP2P.available)
        val client = CodexClient(test.targetContext, device, object : CodexClient.Listener {
            override fun onState(state: ConnectionState, agent: JSONObject?) = Unit
            override fun onRpcEvent(message: JSONObject) = Unit
            override fun onFailure(message: String) = Unit
            override fun onAuthorizationExpired() = Unit
            override fun onDirectFallback() = Unit
        })
        fun field(name: String) = client.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val completed = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        lateinit var cancel: () -> Unit
        test.runOnMainSync {
            // Simulate signaling progress without an external relay or NAT dependency.
            for (name in listOf("active", "online", "directAvailable")) field(name).setBoolean(client, true)
            field("directStatus").set(client, DirectStatus.Negotiating)
            cancel = client.withDesktopShareTransport { completed.countDown() }
            client.withDesktopShareTransport { cancelled.countDown() }.invoke()
        }
        try {
            check(!completed.await(3300, TimeUnit.MILLISECONDS)) { "Active P2P negotiation ended at the old share timeout" }
            test.runOnMainSync { field("directStatus").set(client, DirectStatus.Relay("fixture failure")) }
            check(completed.await(2, TimeUnit.SECONDS)) { "Terminal P2P failure did not release the share wait" }
            check(cancelled.count == 1L) { "Cancelled share still dispatched a callback" }
        } finally { test.runOnMainSync { cancel() } }
    }

    private fun await(reason: String, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < until) {
            if (predicate()) return
            SystemClock.sleep(40)
        }
        error(reason)
    }
}