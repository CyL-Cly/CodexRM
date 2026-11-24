package com.codexrm.mobile

import android.app.Activity
import android.app.Instrumentation
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/** Real-device QA: trigger the same NAT dialog a user opens, over a paired RM and Agent. */
internal object NatDiagnosticCheck {
    fun run(instrumentation: Instrumentation, activity: Activity, mode: String, retry: Boolean): String {
        require(mode == "complete" || mode == "basic") { "Unknown NAT QA mode: $mode" }
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        fun nodes(query: String) = (listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { it.root })
            .flatMap { it.matchingTextNodes(query) }
        waitFor(25_000, "online relay indicator") {
            var opened = false
            instrumentation.runOnMainSync {
                val views = arrayListOf<View>()
                activity.window.decorView.findViewsWithText(views, "RM 中继，查看直连状态", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                opened = views.firstOrNull { it.isShown && it.isClickable }?.performClick() == true
            }
            Unit.takeIf { opened }
        }
        fun button(label: String): AccessibilityNodeInfo? {
            var target = nodes(label)
                .firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
            while (target != null && !target.isClickable && target.isEnabled) target = target.parent
            return target
        }
        waitFor(5_000, "诊断 button") {
            Unit.takeIf { button("诊断")?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
        }
        fun reportText() = nodes("RFC 3489").mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }
            .firstOrNull { it.contains("手机\nRFC 3489：") && it.contains("Agent\nRFC 3489：") }
        val first = waitFor(20_000, "two completed NAT reports", ::reportText)
        verifyReport(first, mode)
        if (!retry) return "NAT UI ($mode) passed\n$first"

        check(first.contains("冷却结束后可重新诊断")) { first }
        val waiting = waitFor(1_000, "retry button during cooldown") { button("重新诊断") }
        check(!waiting.isEnabled) { "NAT retry button enabled before the RM cooldown ended" }
        waitFor(15_000, "retry button after cooldown") {
            Unit.takeIf { button("重新诊断")?.let { it.isEnabled && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } == true }
        }
        waitFor(3_000, "new diagnostic in progress") {
            Unit.takeIf { nodes("正在同时测试手机和 Agent").isNotEmpty() }
        }
        val retried = waitFor(20_000, "fresh NAT reports after retry") {
            reportText()?.takeIf { it.contains("冷却结束后可重新诊断") }
        }
        verifyReport(retried, mode)
        return "NAT UI ($mode) passed\nNAT retry passed\n$retried"
    }

    private fun verifyReport(report: String, mode: String) {
        val sections = Regex("(?m)^(手机|Agent)\\nRFC 3489：([^\\n]+)\\nRFC 5780：映射 ([^；\\n]+)；过滤 ([^\\n]+)")
            .findAll(report).map { it.groupValues }.toList()
        check(sections.map { it[1] } == listOf("手机", "Agent")) { report }
        for ((_, _, classic, mapping, filtering) in sections) {
            when (mode) {
                "complete" -> {
                    check(classic in setOf("无 NAT，开放 UDP", "无地址转换，UDP 入站受限", "全锥型 NAT", "地址受限锥型 NAT", "端口受限锥型 NAT", "对称型 NAT")) { report }
                    check(mapping in setOf("端点无关", "依赖目的 IP", "依赖目的 IP 和端口")) { report }
                    check(filtering in setOf("端点无关", "仅接受已联系的 IP", "仅接受已联系的 IP 和端口")) { report }
                }
                "basic" -> {
                    check(classic == "STUN 未响应此协议") { report }
                    check(mapping == "STUN 不支持完整测试" && filtering == mapping) { report }
                    check(report.contains("CODEXRM_P2P_NAT_STUN")) { report }
                }
            }
        }
    }

    private fun <T : Any> waitFor(timeoutMs: Long, label: String, check: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            check()?.let { return it }
            SystemClock.sleep(100)
        }
        error("Timed out waiting for $label")
    }
}
