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
import android.widget.TextView
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

    fun run(instrumentation: ImageRenderingInstrumentation, settingsOnly: Boolean = false, workspaceOnly: Boolean = false, composerOnly: Boolean = false) {
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
            // The startup update dialog may already be opening when instrumentation gets control.
            val startupDeadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < startupDeadline) {
                test.waitForIdleSync()
                val activeWindow = test.uiAutomation.rootInActiveWindow
                if (activeWindow?.matchingTextNodes("稍后")?.isNotEmpty() == true) {
                    clickText("稍后")
                    break
                }
                SystemClock.sleep(100)
            }
            capture("01-pair")
            expectText("连接你的电脑")

            onMain { seedMain(online = true) }
            if (composerOnly) {
                verifyRunningComposer()
                test.finish(Activity.RESULT_OK, Bundle().apply {
                    putString("stream", "PASS: compact running composer; screenshots=${output.absolutePath}\n")
                })
                return
            }
            if (workspaceOnly) {
                verifyWorkspacePicker()
                test.finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: workspace directory picker\n") })
                return
            }
            capture("02-home-online")
            expectText("工作目录")
            expectText("选择模型")
            expectAbsent("默认模型")
            expectAbsent("开始输入")
            expectAbsent("切换工作目录")
            expectAbsent("解释这个项目的结构")
            expectAbsent("检查最近的改动并总结风险")

            onMain { invoke("openSidebar") }
            capture("03-sidebar")
            expectText("新建会话")
            expectText("最近")
            clickText("工具")
            capture("03a-tools")
            expectText("连接与重试")
            expectText("已归档会话")
            goBack()
            onMain { invoke("openSidebar") }
            clickText("设置")
            capture("03b-settings-dark")
            expectText("深色模式")
            clickText("背景与透明度")
            expectText("聊天背景")
            expectText("遮罩不透明度")
            clickText("更换聊天背景")
            expectText("选择本地图片")
            expectText("随机图片 · 每 3 天更新")
            expectText("AI 拟人图库")
            clickText("取消")
            clickText("返回")
            expectText("通知")
            expectText("后台任务通知")
            val originalShareMode = DeviceStore(screen).shareToNewDraft
            clickText("系统分享")
            capture("03e-share-dropdown")
            clickText(if (originalShareMode) "每次选择去向" else "直接加入新会话草稿")
            check(DeviceStore(screen).shareToNewDraft != originalShareMode) { "Share mode was not saved" }
            clickText("系统分享")
            clickText(if (originalShareMode) "直接加入新会话草稿" else "每次选择去向")
            check(DeviceStore(screen).shareToNewDraft == originalShareMode) { "Share mode was not restored" }
            clickText("深色模式")
            capture("03c-settings-light")
            check(!DeviceStore(screen).darkTheme) { "Light theme was not saved" }
            clickText("深色模式")
            check(DeviceStore(screen).darkTheme) { "Dark theme was not saved" }
            clickText("电脑 Agent 设置")
            capture("03d-agent-settings")
            expectAbsent("后台任务通知")
            clickText("工作区设置")
            expectText("审批策略")
            val originalSandbox = DeviceStore(screen).sandbox
            clickText("文件访问")
            capture("03f-workspace-dropdown")
            clickText(if (originalSandbox == "read-only") "允许修改工作目录" else "只读")
            clickText("取消")
            check(DeviceStore(screen).sandbox == originalSandbox) { "Canceled workspace setting was saved" }
            expectText("模型供应商")
            goBack()
            expectText("深色模式")
            goBack()
            if (settingsOnly) {
                test.finish(Activity.RESULT_OK, Bundle().apply {
                    putString("stream", "PASS: sidebar tools, theme switch, Agent settings and back navigation. Screenshots: ${output.absolutePath}\n")
                })
                return
            }

            clickText("选择设备")
            expectText("添加新设备")
            capture("03g-device-menu")
            goBack()
            verifyWorkspacePicker()
            clickText("添加文件或图片")
            expectText("拍照")
            expectText("照片")
            expectText("文件")
            capture("03h-attachment-sheet")
            goBack()
            clickText("选择模型")
            expectText("配置")
            clickText("强度")
            expectText("选择思考强度")
            clickText("取消")
            clickText("模型")
            capture("04-model-picker")
            expectText("选择模型")
            expectAbsent("默认模型")
            expectText("GPT-5")
            setSearchText("no-such-model")
            capture("05-model-search-empty")
            expectText("清除搜索")
            clickText("清除搜索")
            capture("05a-model-after-clear")
            expectText("GPT-5")
            goBack()
            clickText("完成")
            onMain {
                val options = field("models").get(screen) as List<ModelOption>
                val chosen = options.first { it.model == "gpt-4.1" }
                screen.javaClass.getDeclaredMethod("chooseModel", ModelOption::class.java).apply { isAccessible = true }
                    .invoke(screen, chosen)
                val preference = DeviceStore(screen).modelPreference("ui-agent", "/workspace/demo")
                check(preference.second == "gpt-4.1") { "Recent model was not cached: $preference" }
                set("currentThread", JSONObject().put("id", "thread-1").put("name", "旧会话").put("model", "gpt-5"))
                set("currentThreadId", "thread-1")
                invoke("refreshModelChoices")
                val restored = field("selectedModelOption").get(screen) as? ModelOption
                check(restored?.model == "gpt-5") { "Old conversation did not keep its model: ${restored?.model}" }
                screen.javaClass.getDeclaredMethod("newChat", java.lang.Boolean.TYPE).apply { isAccessible = true }
                    .invoke(screen, false)
                val recent = field("selectedModelOption").get(screen) as? ModelOption
                check(recent?.model == "gpt-4.1") { "New chat did not use the cached model: ${recent?.model}" }
                checks.put(JSONObject().put("recentModelForNewChat", true).put("threadKeepsOwnModel", true))
            }
            verifyRunningComposer()

            onMain { invoke("showAppSettings") }
            capture("06-settings")
            expectText("电脑与连接")
            expectText("文件与系统分享")
            clickText("电脑 Agent 设置")
            expectText("模型供应商")
            clickText("工作区设置")
            capture("07-workspace-settings")
            expectText("文件访问")
            expectText("审批策略")
            test.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            test.waitForIdleSync()
            SystemClock.sleep(200)
            onMain { (field("settingsPage").get(screen) as? SettingsPage)?.dismiss() }
            verifyEditorSaveGuard()
            SystemClock.sleep(3600)

            onMain {
                val files = field("workspaceFiles").get(screen) as WorkspaceFileUi
                files.open(RemoteFile("/workspace/demo", "", ""))
            }
            capture("08-files-compact")
            expectText("电脑")
            expectText("手机")
            clickText("文件管理菜单")
            expectText("刷新两侧目录")
            expectText("选择手机目录")
            goBack()
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

            onMain { setDarkTheme(false) }
            capture("09-home-light")
            onMain { setDarkTheme(true) }

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

    private fun verifyRunningComposer() {
        val composer = field("composer").get(screen) as EditText
        val steer = field("steerButton").get(screen) as View
        val send = field("sendButton").get(screen) as View
        val stop = field("stopButton").get(screen) as View
        val model = field("modelButton").get(screen) as TextView
        val modelSlot = model.parent as View
        val toolbar = send.parent as View
        val info = field("info").get(screen) as JSONObject
        val previousModel = field("selectedModelOption").get(screen)
        val previousEffort = field("effortSelection").get(screen)
        val example = ModelOption("", "gpt-6.2-sol", "gpt-6.2-sol", "high", listOf("high"))
        fun checkToolbarBounds() {
            check(steer.parent === stop.parent && steer.parent === send.parent)
            check(modelSlot.right <= steer.left && steer.right <= stop.left && stop.right <= send.left)
            check(send.right <= toolbar.width && send.width == Ui.dp(screen, 48)) { "Send button was clipped" }
            check(steer.height == send.height)
            check(model.width == minOf(Ui.dp(screen, 80), modelSlot.width)) { "Model slot changed size based on its label" }
        }
        onMain {
            check(steer.visibility == View.GONE && model.visibility == View.VISIBLE)
            set("selectedModelOption", example)
            set("effortSelection", "high")
            invoke("updateModelButton")
            check(model.text.toString() == "6.2-sol")
            set("running", true)
            invoke("updateControls")
            check(steer.visibility == View.VISIBLE && !steer.isEnabled)
            composer.setText("补充当前任务")
            check(steer.isEnabled && send.isEnabled && send.contentDescription == "排队发送")
        }
        expectText("立即发送")
        expectAbsent("发送优先指令")
        capture("05c-running-composer")
        val toolbarWidthParam = toolbar.layoutParams.width
        val narrowWidth = minOf(toolbar.width, Ui.dp(screen, 274)) // 320 dp window minus composer gutters and padding.
        val originalModelWidth = onMainValue {
            checkToolbarBounds()
            set("selectedModelOption", example.copy(displayName = "gpt-custom-provider-with-a-very-long-model-name"))
            invoke("updateModelButton")
            model.width
        }
        test.waitForIdleSync()
        onMain {
            check(model.width == originalModelWidth)
            toolbar.layoutParams = toolbar.layoutParams.apply { width = narrowWidth }
        }
        capture("05d-running-composer-narrow")
        onMain {
            check(toolbar.width == narrowWidth)
            checkToolbarBounds()
            check(model.layout.getEllipsisCount(0) > 0) { "Long model name was not ellipsized" }
            toolbar.layoutParams = toolbar.layoutParams.apply { width = toolbarWidthParam }
            set("selectedModelOption", previousModel)
            set("effortSelection", previousEffort)
            invoke("updateModelButton")
            info.put("messageQueue", false)
            invoke("updateControls")
            check(steer.isEnabled && !send.isEnabled)
            set("sending", true)
            invoke("updateControls")
            check(!steer.isEnabled && !send.isEnabled)
            set("sending", false)
            info.put("messageQueue", true)
            set("running", false)
            composer.setText("")
            invoke("updateControls")
            check(steer.visibility == View.GONE && model.visibility == View.VISIBLE)
        }
    }

    private fun verifyWorkspacePicker() {
        clickText("选择模型")
        clickText("工作区")
        expectText("/workspace/docs")
        clickText("浏览其他目录")
        expectText("添加工作区")
        expectText("显示隐藏目录")
        expectText("添加此文件夹")
        expectText("设备未连接")
        goBack()
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
            .put("messageQueue", true).put("workspaces", true).put("conversationTools", true))
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
        invoke("refreshModelChoices")
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
                { _, _, _, callback -> pending = callback }, {}, { emptyList() })
            page.show()
            SettingsPage::class.java.getDeclaredMethod("showEditor", String::class.java, JSONObject::class.java)
                .apply { isAccessible = true }.invoke(page, "", JSONObject())
            val content = field(page, "content").get(page) as android.view.ViewGroup
            val edits = arrayListOf<MiuixFieldView>()
            fun visit(view: View) {
                if (view is MiuixFieldView) edits += view
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            visit(content)
            check(edits.size >= 3) { "Provider editor fields are missing" }
            edits[0].text = "QA Provider"
            edits[2].text = "https://example.invalid"
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

    private fun setDarkTheme(dark: Boolean) {
        screen.javaClass.getDeclaredMethod("setDarkTheme", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(screen, dark)
    }

    private fun goBack() {
        check(test.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        test.waitForIdleSync()
        SystemClock.sleep(250)
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
        val found = awaitTextNodes(text).isNotEmpty()
        checks.put(JSONObject().put("text", text).put("passed", found))
        check(found) { "Missing visible text: $text" }
    }

    private fun expectAbsent(text: String) {
        test.waitForIdleSync()
        val root = test.uiAutomation.rootInActiveWindow ?: error("No active window while checking absence of $text")
        val found = root.matchingTextNodes(text).any { it.text?.toString() == text }
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

    private fun awaitTextNodes(text: String): List<AccessibilityNodeInfo> {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            test.waitForIdleSync()
            val matches = test.uiAutomation.rootInActiveWindow?.matchingTextNodes(text).orEmpty()
            if (matches.isNotEmpty()) return matches
            SystemClock.sleep(80)
        } while (SystemClock.uptimeMillis() < deadline)
        return emptyList()
    }

    private fun clickText(text: String) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            val candidates = test.uiAutomation.rootInActiveWindow?.matchingTextNodes(text).orEmpty()
                .sortedByDescending { it.text?.toString() == text || it.contentDescription?.toString() == text }
            for (node in candidates) {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if (target?.isEnabled == true && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    test.waitForIdleSync()
                    return
                }
            }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Cannot click $text")
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

// Compose exposes virtual nodes through traversal, even on devices where text search misses them.
internal fun AccessibilityNodeInfo.matchingTextNodes(query: String): List<AccessibilityNodeInfo> {
    val nativeMatches = findAccessibilityNodeInfosByText(query)
    return buildList {
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser && (node.text?.contains(query) == true || node.contentDescription?.contains(query) == true)) {
                add(node)
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(this@matchingTextNodes)
        nativeMatches.filter { it.isVisibleToUser }.forEach { if (it !in this) add(it) }
    }
}
