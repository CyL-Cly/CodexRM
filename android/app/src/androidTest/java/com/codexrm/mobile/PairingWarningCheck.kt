package com.codexrm.mobile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import okhttp3.Call
import java.io.File

/** Native-device checks for both HTTP pairing entry points in the isolated .imageqa app. */
internal object PairingWarningCheck {
    private const val warning = "无加密连接可能会泄露你的数据，自行承担风险"
    private const val cancel = "取消连接"
    private const val proceed = "已知晓风险，仍继续连接"

    fun run(test: ImageRenderingInstrumentation) {
        var activity: Activity? = null
        try {
            val context = test.targetContext
            check(context.packageName.endsWith(".imageqa")) { "Use -Pcodexrm.imageQa to protect the user's installed app" }
            DeviceStore(context).apply {
                save(emptyList())
                selectedId = ""
                legacySystemWarningShown = true
                darkTheme = true
            }
            activity = test.startActivitySync(
                Intent().setClassName(context.packageName, "com.codexrm.mobile.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            val screen = activity
            onMain(test) { invoke(screen, "cancelUpdateOperation") }

            submitManualHttp(test, screen)
            expectWarning(test)
            checkPairCall(screen, expected = false, "manual HTTP connected before confirmation")
            capture(test, context, "http-pairing-warning.png")
            clickText(test, cancel)
            checkPairCall(screen, expected = false, "manual cancellation created a pairing request")
            expectWarningAbsent(test)

            submitScannedHttp(test, screen)
            expectWarning(test)
            checkPairCall(screen, expected = false, "scanned HTTP connected before confirmation")
            clickText(test, cancel)
            checkPairCall(screen, expected = false, "scan cancellation created a pairing request")
            expectWarningAbsent(test)

            submitManualHttp(test, screen)
            expectWarning(test)
            clickText(test, proceed)
            checkPairCall(screen, expected = true, "manual confirmation did not start pairing")
            cancelPairCall(test, screen)

            submitScannedHttp(test, screen)
            expectWarning(test)
            clickText(test, proceed)
            checkPairCall(screen, expected = true, "scan confirmation did not start pairing")
            cancelPairCall(test, screen)

            test.finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "PASS: manual/scanned HTTP warnings; cancellation makes no request; confirmation starts pairing.\n")
            })
        } catch (error: Throwable) {
            test.finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            })
        } finally {
            onMain(test) {
                activity?.let { pairCall(it)?.cancel(); it.finish() }
            }
        }
    }

    private fun submitManualHttp(test: ImageRenderingInstrumentation, screen: Activity) {
        onMain(test) {
            (field(screen, "codeInput") as EditText).setText("QA-MANUAL")
            (field(screen, "serverInput") as EditText).setText("http://127.0.0.1:9")
            invoke(screen, "submitManualPair")
        }
        test.waitForIdleSync()
    }

    private fun submitScannedHttp(test: ImageRenderingInstrumentation, screen: Activity) {
        onMain(test) {
            val intent = Intent().putExtra(ScannerActivity.EXTRA_TEXT, "http://127.0.0.1:9/#pair=QA-SCAN")
            MainActivity::class.java.getDeclaredMethod(
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java,
            ).apply { isAccessible = true }.invoke(screen, 42, Activity.RESULT_OK, intent)
        }
        test.waitForIdleSync()
    }

    private fun expectWarning(test: ImageRenderingInstrumentation) {
        expectText(test, "无加密连接")
        expectText(test, warning)
        expectText(test, cancel)
        expectText(test, proceed)
    }

    private fun expectWarningAbsent(test: ImageRenderingInstrumentation) {
        test.waitForIdleSync()
        val root = test.uiAutomation.rootInActiveWindow ?: error("No active window after dismissing warning")
        check(root.findAccessibilityNodeInfosByText(warning).isEmpty()) { "HTTP warning remained visible" }
    }

    private fun expectText(test: ImageRenderingInstrumentation, text: String) {
        test.waitForIdleSync()
        SystemClock.sleep(100)
        val root = test.uiAutomation.rootInActiveWindow ?: error("No active window while checking $text")
        check(root.findAccessibilityNodeInfosByText(text).any { it.text?.toString() == text }) {
            "Missing visible text: $text"
        }
    }

    private fun clickText(test: ImageRenderingInstrumentation, text: String) {
        test.waitForIdleSync()
        val root = test.uiAutomation.rootInActiveWindow ?: error("No active window while clicking $text")
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val node = nodes.firstOrNull { it.isClickable }
            ?: nodes.firstOrNull()?.parent?.takeIf { it.isClickable }
            ?: error("No clickable action: $text")
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Could not click $text" }
        test.waitForIdleSync()
    }

    private fun checkPairCall(screen: Activity, expected: Boolean, message: String) {
        check((pairCall(screen) != null) == expected) { message }
    }

    private fun cancelPairCall(test: ImageRenderingInstrumentation, screen: Activity) =
        onMain(test) { pairCall(screen)?.cancel() }

    private fun pairCall(screen: Activity): Call? = field(screen, "pairCall") as Call?

    private fun field(screen: Activity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(screen)

    private fun invoke(screen: Activity, name: String) {
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(screen)
    }

    private fun onMain(test: ImageRenderingInstrumentation, block: () -> Unit) {
        var failure: Throwable? = null
        test.runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }

    private fun capture(test: ImageRenderingInstrumentation, context: android.content.Context, name: String) {
        test.waitForIdleSync()
        SystemClock.sleep(200)
        val output = File(context.getExternalFilesDir(null), name)
        val bitmap = test.uiAutomation.takeScreenshot() ?: error("No warning screenshot")
        output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
