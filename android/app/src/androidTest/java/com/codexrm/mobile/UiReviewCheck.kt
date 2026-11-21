package com.codexrm.mobile

import android.app.Activity
import android.app.Dialog
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Visual/state-matrix checks for the isolated .imageqa application. */
internal object UiReviewCheck {
    private lateinit var test: ImageRenderingInstrumentation
    private lateinit var screen: Activity
    private lateinit var output: File
    private val checks = JSONArray()

    fun run(instrumentation: ImageRenderingInstrumentation) {
        test = instrumentation
        var activity: Activity? = null
        try {
            val context = test.targetContext
            check(context.packageName.endsWith(".imageqa")) { "Use -Pcodexrm.imageQa for the UI fixture" }
            DeviceStore(context).apply {
                save(emptyList())
                selectedId = ""
                legacySystemWarningShown = true
                darkTheme = true
            }
            val density = context.resources.displayMetrics.density
            val fontScale = context.resources.configuration.fontScale
            output = File(context.getExternalFilesDir(null), "ui-review-$density-$fontScale").apply { mkdirs() }
            activity = test.startActivitySync(
                Intent().setClassName(context.packageName, "com.codexrm.mobile.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            screen = activity
            onMain { invoke("cancelUpdateOperation") }
            capture("01-pair")
            expectText("连接你的电脑")

            onMain { seedMain(online = true) }
            capture("02-home-online")
            expectText("工作目录")
            expectText("默认模型")
            expectAbsent("开始输入")
            expectAbsent("切换工作目录")
            expectAbsent("解释这个项目的结构")
            expectAbsent("检查最近的改动并总结风险")

            onMain { invoke("openSidebar") }
            capture("03-sidebar")
            expectText("新建会话")
            expectText("最近")
            onMain { invoke("toggleSidebar") }

            onMain { invoke("showModelPicker") }
            capture("04-model-picker")
            expectText("选择模型")
            expectText("默认模型")
            expectText("GPT-5")
            setSearchText("no-such-model")
            capture("05-model-search-empty")
            expectText("清除搜索")
            clickText("清除搜索")
            expectText("GPT-5")
            test.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            test.waitForIdleSync()
            SystemClock.sleep(200)
            onMain {
                val options = field("models").get(screen) as List<ModelOption>
                screen.javaClass.getDeclaredMethod("chooseModel", ModelOption::class.java).apply { isAccessible = true }
                    .invoke(screen, options.first())
                invoke("showModelPicker")
            }
            capture("05b-model-before-default")
            clickText("默认模型")
            onMain {
                check(field("selectedModelOption").get(screen) == null) { "Default model did not clear selection" }
                val preference = DeviceStore(screen).modelPreference("ui-agent", "/workspace/demo")
                check(preference.first.isEmpty() && preference.second.isEmpty() && preference.third.isEmpty()) {
                    "Default model preference was not cleared: $preference"
                }
                checks.put(JSONObject().put("defaultModelRestored", true))
                set("currentThread", JSONObject().put("id", "thread-1").put("name", "旧会话").put("model", "gpt-5"))
                set("currentThreadId", "thread-1")
                invoke("useDefaultModel")
                invoke("refreshModelSpinner")
                val restored = field("selectedModelOption").get(screen) as? ModelOption
                check(restored?.model == "gpt-5") { "Thread refresh did not restore its own model for this check" }
                screen.javaClass.getDeclaredMethod("newChat", java.lang.Boolean.TYPE).apply { isAccessible = true }
                    .invoke(screen, false)
                check(field("selectedModelOption").get(screen) == null) { "New chat restored the old thread model after default was chosen" }
                checks.put(JSONObject().put("defaultSurvivesRefreshAndNewChat", true))
                set("running", true)
                (field("composer").get(screen) as EditText).setText("补充当前任务")
                invoke("updateControls")
                check((field("effortSpinner").get(screen) as View).visibility == View.GONE) { "Running toolbar did not release effort width" }
            }
            SystemClock.sleep(7200)
            capture("05c-running-composer")
            onMain {
                set("running", false)
                (field("composer").get(screen) as EditText).setText("")
                invoke("updateControls")
                check((field("effortSpinner").get(screen) as View).visibility == View.VISIBLE)
            }

            onMain { invoke("showAppSettings") }
            capture("06-settings")
            expectText("模型与连接")
            expectText("模型供应商")
            expectText("应用与数据")
            onMain {
                (field("settingsPage").get(screen) as? SettingsPage)?.dismiss()
                invoke("showSettings")
            }
            capture("07-workspace-settings")
            expectText("文件访问")
            expectText("审批策略")
            test.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            test.waitForIdleSync()
            SystemClock.sleep(200)
            verifyEditorSaveGuard()
            SystemClock.sleep(3600)

            onMain {
                val files = field("workspaceFiles").get(screen) as WorkspaceFileUi
                files.open(RemoteFile("/workspace/demo", "", ""))
            }
            capture("08-files-compact")
            expectText("电脑")
            expectText("手机")
            onMain {
                val files = field("workspaceFiles").get(screen) as WorkspaceFileUi
                val manager = field(files, "manager").get(files)!!
                field(manager, "selectionMode").set(manager, true)
                (field(manager, "selected").get(manager) as MutableSet<String>).add("stale-selection")
                manager.javaClass.getDeclaredMethod("updateControls").apply { isAccessible = true }.invoke(manager)
                val bottom = field(manager, "bottom").get(manager) as android.view.ViewGroup
                var action: View? = null
                fun visit(view: View) {
                    if (view.contentDescription == "所选文件的操作") action = view
                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
                }
                visit(bottom)
                check(action?.isEnabled == false) { "Stale selection remained actionable while directory was not ready" }
                manager.javaClass.getDeclaredMethod("clearSelection").apply { isAccessible = true }.invoke(manager)
                checks.put(JSONObject().put("staleSelectionDisabled", true))
                files.closeManager()
            }

            onMain { invoke("toggleTheme") }
            capture("09-home-light")
            onMain { invoke("toggleTheme") }

            onMain { screen.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitForOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            capture("10-landscape")
            expectAbsent("退出阅读模式")
            onMain {
                check((field("topbar").get(screen) as View).visibility == View.GONE)
                check((field("composerZone").get(screen) as View).visibility == View.GONE)
                checks.put(JSONObject().put("readingModeExitRemoved", true))
                val files = field("workspaceFiles").get(screen) as WorkspaceFileUi
                files.open(RemoteFile("/workspace/demo", "", ""))
            }
            capture("11-files-wide")
            onMain {
                (field("workspaceFiles").get(screen) as WorkspaceFileUi).closeManager()
                screen.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            waitForOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

            onMain {
                set("online", false)
                set("loaded", false)
                set("started", true)
                set("connectionState", ConnectionState.OFFLINE)
                invoke("updateControls")
            }
            capture("12-home-offline")
            expectText("电脑")

            test.finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "\nPASS UI review; screenshots=${output.absolutePath}\nchecks=$checks\n")
            })
        } catch (failure: Throwable) {
            test.finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("stream", "\nFAIL UI review: ${failure.stackTraceToString()}\nchecks=$checks\nscreenshots=${if (::output.isInitialized) output.absolutePath else "unavailable"}\n")
            })
        } finally {
            activity?.let { current ->
                test.runOnMainSync {
                    current.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    current.finish()
                }
            }
        }
    }

    private fun seedMain(online: Boolean) {
        if (!(field("mainBuilt").get(screen) as Boolean)) invoke("buildMain")
        val demo = Device("ui-client", "ui-token", "http://127.0.0.1:9", "ui-agent", "演示电脑", "Windows", "1.0.0", online)
        set("devices", mutableListOf(demo))
        set("device", demo)
        set("mainVisible", true)
        set("started", false)
        set("resumed", true)
        set("online", online)
        set("loaded", online)
        set("bootstrapping", false)
        set("bootstrapError", "")
        set("connectionState", if (online) ConnectionState.ONLINE else ConnectionState.OFFLINE)
        set("currentCwd", "/workspace/demo")
        set("currentThread", null)
        set("currentThreadId", "")
        set("info", JSONObject()
            .put("mock", true).put("name", "演示电脑").put("cwd", "/workspace/demo")
            .put("attachments", true).put("providerManagement", true).put("providerModels", true)
            .put("messageQueue", true).put("workspaces", true))
        set("workspaces", listOf(Workspace("Demo Project", "/workspace/demo"), Workspace("Documents", "/workspace/docs")))
        val nativeModels = JSONArray()
            .put(JSONObject().put("model", "gpt-5").put("displayName", "GPT-5")
                .put("defaultReasoningEffort", "medium").put("supportedReasoningEfforts", JSONArray()
                    .put(JSONObject().put("reasoningEffort", "low"))
                    .put(JSONObject().put("reasoningEffort", "medium"))
                    .put(JSONObject().put("reasoningEffort", "high"))))
            .put(JSONObject().put("model", "gpt-4.1").put("displayName", "GPT-4.1"))
        val nativeProvider = JSONObject().put("id", "").put("name", "OpenAI")
            .put("notes", "云端模型").put("models", nativeModels)
        val localProvider = JSONObject().put("id", "local").put("name", "Local")
            .put("notes", "离线模型").put("models", JSONArray()
                .put(JSONObject().put("model", "qwen-coder").put("displayName", "Qwen Coder")))
        set("modelCatalog", ModelCatalog(JSONObject().put("providers", JSONArray().put(nativeProvider).put(localProvider))))
        val threads = field("threadList").get(screen) as ThreadListState
        threads.clear()
        threads.commit(threads.mergePage(emptyList(), listOf(
            ThreadSummary("thread-1", "检查项目构建", "/workspace/demo", true, 200L, 100L),
            ThreadSummary("thread-2", "整理发布说明", "/workspace/docs", false, 150L, 90L),
        )))
        invoke("refreshModelSpinner")
        screen.setContentView(field("mainRoot").get(screen) as View)
        invoke("renderDevices")
        invoke("renderWorkspaces")
        invoke("updateControls")
    }

    private fun verifyEditorSaveGuard() {
        var pending: RpcCallback? = null
        lateinit var page: SettingsPage
        onMain {
            page = SettingsPage(screen as MainActivity, "演示电脑", { null }, { true },
                { _, _, _, callback -> pending = callback }, {}, emptyList())
            page.show()
            SettingsPage::class.java.getDeclaredMethod("showEditor", String::class.java, JSONObject::class.java)
                .apply { isAccessible = true }.invoke(page, "", JSONObject())
            val content = field(page, "content").get(page) as android.view.ViewGroup
            val edits = arrayListOf<EditText>()
            fun visit(view: View) {
                if (view is EditText) edits += view
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            visit(content)
            check(edits.size >= 3) { "Provider editor fields are missing" }
            edits[0].setText("QA Provider")
            edits[2].setText("https://example.invalid")
        }
        capture("07b-provider-editor")
        clickText("保存")
        val version = onMainValue {
            check(field(page, "editorSaving").get(page) as Boolean) { "Editor did not enter saving state" }
            check(pending != null) { "Save callback was not captured" }
            field(page, "pageVersion").get(page) as Int
        }
        capture("07c-provider-saving")
        onMain {
            (field(page, "dialog").get(page) as Dialog).onBackPressed()
            check(field(page, "pageVersion").get(page) as Int == version) { "Saving editor navigated away" }
            check(field(page, "editorSaving").get(page) as Boolean) { "Saving editor allowed discard while in flight" }
        }
        onMain {
            pending?.onComplete(null, IOException("QA save failure"))
            check(!(field(page, "editorSaving").get(page) as Boolean)) { "Editor stayed busy after failure" }
            page.dismiss()
        }
        test.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        test.waitForIdleSync()
        SystemClock.sleep(200)
        checks.put(JSONObject().put("editorSaveGuard", true))
    }

    private fun onMainValue(block: () -> Int): Int {
        var value = 0
        onMain { value = block() }
        return value
    }

    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun field(name: String) = field(screen, name)
    private fun set(name: String, value: Any?) = field(name).set(screen, value)
    private fun invoke(name: String) = screen.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(screen)

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        test.runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }

    private fun capture(name: String) {
        test.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = test.uiAutomation.takeScreenshot() ?: error("No screenshot for $name")
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        checks.put(JSONObject().put("screenshot", name).put("passed", true))
    }

    private fun expectText(text: String) {
        test.waitForIdleSync()
        val root = test.uiAutomation.rootInActiveWindow ?: error("No active window while checking $text")
        val found = root.findAccessibilityNodeInfosByText(text).isNotEmpty()
        checks.put(JSONObject().put("text", text).put("passed", found))
        check(found) { "Missing visible text: $text" }
    }

    private fun expectAbsent(text: String) {
        test.waitForIdleSync()
        val root = test.uiAutomation.rootInActiveWindow ?: error("No active window while checking absence of $text")
        val found = root.findAccessibilityNodeInfosByText(text).any { it.text?.toString() == text }
        checks.put(JSONObject().put("absent", text).put("passed", !found))
        check(!found) { "Unexpected visible action: $text" }
    }

    private fun setSearchText(value: String) {
        test.waitForIdleSync()
        val root = test.uiAutomation.rootInActiveWindow ?: error("No active model picker")
        var search: AccessibilityNodeInfo? = null
        fun visit(node: AccessibilityNodeInfo) {
            if (node.className?.toString() == EditText::class.java.name) search = node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        val target = search ?: error("No model search field")
        check(target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })) { "Cannot enter model search" }
        test.waitForIdleSync()
    }

    private fun clickText(text: String) {
        test.waitForIdleSync()
        val root = test.uiAutomation.rootInActiveWindow ?: error("No active window while clicking $text")
        val candidates = root.findAccessibilityNodeInfosByText(text)
        val node = candidates.firstOrNull { it.text?.toString() == text } ?: candidates.firstOrNull() ?: error("Cannot click $text")
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Text is not clickable: $text" }
        test.waitForIdleSync()
    }

    private fun waitForOrientation(orientation: Int) {
        val expected = if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        } else android.content.res.Configuration.ORIENTATION_PORTRAIT
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            test.waitForIdleSync()
            if (screen.resources.configuration.orientation == expected) {
                SystemClock.sleep(350)
                return
            }
            SystemClock.sleep(50)
        }
        error("Orientation did not change to $expected")
    }
}
