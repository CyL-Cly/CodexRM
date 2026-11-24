package com.codexrm.mobile

import org.json.JSONObject

internal data class NatDiagnosticResult(
    val phone: JSONObject?,
    val agent: JSONObject?,
    val reason: String? = null,
    val usesP2PStun: Boolean = false,
    val retryAtMs: Long = 0,
) {
    fun displayText(): String {
        if (phone == null && agent == null && reason != null) return reason
        val reports = listOfNotNull(phone, agent)
        val fields = reports.flatMap { report -> listOf("rfc3489", "mapping", "filtering").map { report.optString(it) } }
        val advice = when {
            usesP2PStun && "unsupported" in fields ->
                "RM 当前使用直连 STUN。若要完成分类，请管理员配置专用诊断端点（CODEXRM_P2P_NAT_STUN）。"
            "unsupported" in fields -> "诊断端点不支持所需协议，请管理员检查 STUN 配置。"
            "no_response" in fields || "unknown" in fields ->
                "部分探测未得到有效结果。可稍后重测，或换一个网络排查 UDP 路径。"
            else -> null
        }
        return listOfNotNull(section("手机", phone), section("Agent", agent), reason, advice,
            "结果仅适用于本次 IPv4 UDP 测试路径。超时不等于 UDP 被封锁，也不代表不能直连。")
            .joinToString("\n\n")
    }

    private fun section(title: String, report: JSONObject?): String {
        if (report == null) return "$title：未收到诊断结果"
        val sample = when (report.optString("mappingSample")) {
            "stable" -> "多个 STUN 目的地观察到相同映射"
            "varies", "linear" -> "不同 STUN 目的地观察到不同映射或出口"
            else -> null
        }
        return "$title\nRFC 3489：${label(report.optString("rfc3489"))}\n" +
            "RFC 5780：映射 ${label(report.optString("mapping"))}；过滤 ${filteringLabel(report.optString("filtering"))}" +
            (sample?.let { "\n补充观察：$it（不是完整分类）" } ?: "")
    }

    private fun filteringLabel(value: String) = when (value) {
        "address_dependent" -> "仅接受已联系的 IP"
        "address_port_dependent" -> "仅接受已联系的 IP 和端口"
        else -> label(value)
    }

    private fun label(value: String) = when (value) {
        "open_internet" -> "无 NAT，开放 UDP"
        "udp_firewall" -> "无地址转换，UDP 入站受限"
        "full_cone" -> "全锥型 NAT"
        "restricted_cone" -> "地址受限锥型 NAT"
        "port_restricted_cone" -> "端口受限锥型 NAT"
        "symmetric" -> "对称型 NAT"
        "endpoint_independent" -> "端点无关"
        "address_dependent" -> "依赖目的 IP"
        "address_port_dependent" -> "依赖目的 IP 和端口"
        "unsupported" -> "STUN 不支持完整测试"
        "no_response" -> "STUN 未响应此协议"
        else -> "无法判定"
    }
}
