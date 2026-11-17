package com.codexrm.mobile

import java.net.URI
import java.net.URLDecoder

internal data class PairingData(val serverUrl: String, val code: String)

internal object PairingLink {
    fun parse(raw: String, fallbackServer: String = ""): PairingData {
        val input = raw.trim()
        require(input.isNotEmpty() && input.length <= 4096) { "配对码为空或过长" }
        if (!input.contains("://")) {
            require(!input.any(Char::isWhitespace)) { "配对码不能包含空白字符" }
            return PairingData(normalizeServer(fallbackServer), input)
        }

        val uri = runCatching { URI(input) }.getOrElse { throw IllegalArgumentException("配对链接格式无效") }
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                val code = parameter(uri.rawFragment, "pair")
                    ?: parameter(uri.rawQuery, "pair")
                    ?: throw IllegalArgumentException("链接中没有 pair 配对码")
                PairingData(normalizeServer(serverWithoutQuery(uri)), code)
            }
            "codexrm" -> {
                val code = parameter(uri.rawQuery, "code")
                    ?: parameter(uri.rawQuery, "pair")
                    ?: throw IllegalArgumentException("链接中没有配对码")
                PairingData(normalizeServer(fallbackServer), code)
            }
            else -> throw IllegalArgumentException("仅支持 http(s) 配对链接")
        }
    }

    fun normalizeServer(raw: String): String {
        var input = raw.trim()
        require(input.isNotEmpty()) { "请输入服务器地址" }
        if (!input.contains("://")) input = "https://$input"
        val uri = runCatching { URI(input) }.getOrElse { throw IllegalArgumentException("服务器地址无效") }
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "服务器仅支持 http 或 https"
        }
        require(uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "服务器地址无效"
        }
        return serverWithoutQuery(uri).trimEnd('/')
    }

    private fun serverWithoutQuery(uri: URI): String {
        val path = (uri.rawPath ?: "").trimEnd('/')
        return URI(uri.scheme.lowercase(), null, uri.host, uri.port, path, null, null).toASCIIString()
    }

    private fun parameter(query: String?, name: String): String? {
        if (query.isNullOrEmpty()) return null
        for (part in query.split('&')) {
            val separator = part.indexOf('=')
            if (separator < 0 || part.substring(0, separator) != name) continue
            val value = URLDecoder.decode(part.substring(separator + 1), "UTF-8").trim()
            require(value.isNotEmpty() && value.length <= 512) { "配对码无效" }
            return value
        }
        return null
    }
}
