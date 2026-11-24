package com.codexrm.mobile

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.activity.BackEventCompat
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.window.DialogWindowProvider

internal object PredictiveBackCheck {
    fun run(test: ImageRenderingInstrumentation, realGesture: Boolean) {
        var activity: Activity? = null
        try {
            val context = test.targetContext
            check(context.packageName.endsWith(".imageqa")) { "Use the isolated image QA package" }
            check(!realGesture || Build.VERSION.SDK_INT >= 34) { "Requires Android 14+ and gesture navigation" }
            DeviceStore(context).legacySystemWarningShown = true
            val screen = test.startActivitySync(
                Intent().setClassName(context.packageName, "com.codexrm.mobile.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            activity = screen
            lateinit var settings: SettingsPage
            test.runOnMainSync {
                settings = SettingsPage(screen, "测试电脑", { null }, { true }, { _, _, _, _ -> }, {},
                    sections = { agent -> listOf(SettingsPage.Section("测试", actions = listOf(agent),
                        choice = SettingsPage.Choice("测试选项", listOf("第一项", "第二项"), { 0 }, {}))) },
                    agentSections = { models -> listOf(SettingsPage.Section("连接", actions = listOf(models))) },
                )
                settings.show()
            }
            expectText(test, "电脑 Agent 设置")
            val settingsRoot = activeContent(test)
            verifySwipe(test, realGesture, settingsRoot, commit = false, page = true)
            click(test, "电脑 Agent 设置")
            expectText(test, "模型供应商")
            val agentPage = activeContent(test)
            verifySwipe(test, realGesture, agentPage, commit = false, right = true, page = true, previousPage = settingsRoot)
            verifySwipe(test, realGesture, agentPage, commit = true, right = true, page = true, previousPage = settingsRoot)
            expectText(test, "电脑 Agent 设置")

            click(test, "测试选项")
            expectText(test, "第二项")
            val dropdown = activeContent(test)
            verifySwipe(test, realGesture, dropdown, commit = false)
            expectText(test, "第二项")
            verifySwipe(test, realGesture, dropdown, commit = true)
            expectText(test, "电脑 Agent 设置")
            verifySwipe(test, realGesture, activeContent(test), commit = true, page = true)
            await("settings dismiss") { onMain(test) { !settings.isShowing } }

            lateinit var dialog: MiuixDialog
            var cancels = 0
            test.runOnMainSync {
                dialog = MiuixDialog.Builder(screen).setTitle("弹窗返回测试").setNegativeButton("关闭", null).show()
                dialog.setOnCancelListener { cancels++ }
            }
            expectText(test, "弹窗返回测试")
            val popup = activeContent(test)
            test.runOnMainSync { dialog.setCancelable(false) }
            test.waitForIdleSync()
            onMain(test) {
                val dispatcher = checkNotNull(popup.findViewTreeOnBackPressedDispatcherOwner()).onBackPressedDispatcher
                val edge = BackEventCompat.EDGE_LEFT
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, edge))
                dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, 0.65f, edge))
                check(popup.translationX == 0f && popup.alpha == 1f)
                dispatcher.onBackPressed()
                check(dialog.isShowing && cancels == 0)
                dialog.setCancelable(true)
            }
            test.waitForIdleSync()
            verifySwipe(test, realGesture, popup, commit = false, right = true)
            check(onMain(test) { dialog.isShowing && cancels == 0 })
            verifySwipe(test, realGesture, popup, commit = true)
            await("dialog cancel callback") { onMain(test) { !dialog.isShowing && cancels == 1 } }

            lateinit var sheet: PredictiveBackDialog
            lateinit var panel: View
            test.runOnMainSync {
                sheet = PredictiveBackDialog(screen)
                panel = View(screen).apply { minimumHeight = Ui.dp(screen, 250) }
                sheet.setBackContent(panel, BackMotion.SHEET)
                sheet.window?.setGravity(android.view.Gravity.BOTTOM)
                sheet.show()
                sheet.window?.setLayout(-1, -2)
            }
            verifySwipe(test, realGesture, panel, commit = false, vertical = true)
            verifySwipe(test, realGesture, panel, commit = true, vertical = true)
            await("sheet dismiss") { onMain(test) { !sheet.isShowing } }

            test.finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "PASS: ${if (realGesture) "Android edge swipe" else "back dispatcher"} progress and commit, " +
                    "dispatcher cancellation, and non-cancelable dialog behavior.\n")
            })
        } catch (error: Throwable) {
            test.finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", error.stackTraceToString()) })
        } finally {
            activity?.let { screen -> test.runOnMainSync { screen.finish() } }
        }
    }

    private fun activeContent(test: ImageRenderingInstrumentation): View = onMain(test) {
        val decor = WindowInspector.getGlobalWindowViews().last { it.hasWindowFocus() }
        fun composeDialog(view: View): View? = when {
            view is DialogWindowProvider -> view
            view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { composeDialog(view.getChildAt(it)) }
            else -> null
        }
        composeDialog(decor) ?: checkNotNull(decor.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0))
    }

    private fun verifySwipe(
        test: ImageRenderingInstrumentation, realGesture: Boolean, view: View, commit: Boolean,
        right: Boolean = false, vertical: Boolean = false, page: Boolean = false, previousPage: View? = null,
    ) {
        await("back target gets focus") { onMain(test) { view.hasWindowFocus() && view.width > 0 && view.height > 0 } }
        if (!realGesture || !commit) {
            onMain(test) {
                val dispatcher = checkNotNull(view.findViewTreeOnBackPressedDispatcherOwner()).onBackPressedDispatcher
                val edge = if (right) BackEventCompat.EDGE_RIGHT else BackEventCompat.EDGE_LEFT
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, edge))
                val initialX = view.translationX
                val initialY = view.translationY
                dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, 0.65f, edge))
                val moved = when {
                    vertical -> view.translationY > initialY
                    right -> view.translationX < initialX
                    else -> view.translationX > initialX
                }
                check(moved) {
                    "Current window did not follow back progress"
                }
                if (page) {
                    val direction = if (right) -1 else 1
                    check(kotlin.math.abs(view.translationX - initialX - direction * view.width * 0.65f) < 1f)
                    check(view.translationY == initialY && view.scaleX == 1f && view.scaleY == 1f && view.alpha == 1f)
                    if (previousPage != null) {
                        val host = view.parent as ViewGroup
                        check(previousPage.parent === host && host.indexOfChild(previousPage) < host.indexOfChild(view))
                        check(previousPage.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
                    }
                }
                if (commit) dispatcher.onBackPressed() else dispatcher.dispatchOnBackCancelled()
                if (!page || !commit) {
                    check(view.translationX == initialX && view.translationY == initialY && view.alpha == 1f) {
                        "Back left a transformed view"
                    }
                    check(previousPage?.parent == null)
                }
            }
            return
        }
        onMain(test) { view.windowInsetsController?.hide(WindowInsets.Type.ime()) }
        await("IME hidden before testing app back") {
            onMain(test) { view.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true }
        }
        // Give the edge handler time to settle after a focus or page change.
        SystemClock.sleep(450)
        val metrics = test.targetContext.resources.displayMetrics
        val bounds = test.targetContext.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        val start = if (right) bounds.right - 2f else 2f
        val direction = if (right) -1 else 1
        val y = if (vertical) bounds.bottom - 140 * metrics.density else bounds.height() * 0.52f
        val end = start + direction * 140 * metrics.density
        val initial = onMain(test) { view.translationX to view.translationY }
        val command = "input swipe $start $y $end $y 650"
        ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(command)).use { output ->
            await("view follows the edge gesture") {
                onMain(test) {
                    if (vertical) view.translationY > initial.second + 1f
                    else (view.translationX - initial.first) * direction > 1f
                }
            }
            val error = output.bufferedReader().readText()
            check(error.isBlank()) { error }
        }
    }

    private fun expectText(test: ImageRenderingInstrumentation, text: String) {
        await("visible text: $text") { node(test.uiAutomation.rootInActiveWindow, text) != null }
    }

    private fun click(test: ImageRenderingInstrumentation, text: String) {
        var clicked = false
        await("click: $text") {
            var current = node(test.uiAutomation.rootInActiveWindow, text)
            while (current != null && !clicked) {
                clicked = current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                current = current.parent
            }
            clicked
        }
    }

    private fun node(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.text?.toString() == text && root.isVisibleToUser) return root
        return (0 until root.childCount).firstNotNullOfOrNull { node(root.getChild(it), text) }
    }

    private fun <T> onMain(test: ImageRenderingInstrumentation, block: () -> T): T {
        var result: Result<T>? = null
        test.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun await(reason: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 4_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $reason" }
            SystemClock.sleep(32)
        }
    }
}
