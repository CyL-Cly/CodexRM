package com.codexrm.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal data class MessageImage(
    val source: String,
    val name: String = "图片",
    val attachmentId: String = "",
    val messageId: String = "",
) {
    fun cacheKey(scope: String, cwd: String): String = "$scope\n" + when {
        attachmentId.isNotEmpty() -> "attachment:$attachmentId"
        else -> {
            val resource = if (isRemote || source.startsWith("/") || WINDOWS_PATH.containsMatchIn(source)) source else "$cwd\n$source"
            if (isRemote || messageId.isEmpty()) resource else "message:$messageId\n$resource"
        }
    }

    val isRemote: Boolean get() = source.startsWith("https://", true) || source.startsWith("http://", true) || source.startsWith("data:image/", true)

    // Local files are mutable. Bind their cached copies to the original message.
    val allowsEarlyCache: Boolean get() = isRemote || attachmentId.isNotEmpty() || messageId.isNotEmpty()

    companion object {
        private val WINDOWS_PATH = Regex("^[A-Za-z]:[/\\\\]")

        fun fromSource(raw: String, name: String = "图片", attachmentId: String = ""): MessageImage? {
            var source = raw.trim()
            if (source.startsWith("sandbox:")) source = source.removePrefix("sandbox:")
            if (source.startsWith("file:", true)) {
                source = runCatching { URI(source).path }.getOrNull().orEmpty()
                if (source.matches(Regex("^/[A-Za-z]:/.*"))) source = source.drop(1)
            }
            if (source.isEmpty()) return null
            val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").find(source)?.value
            if (scheme != null && !WINDOWS_PATH.containsMatchIn(source) &&
                !source.startsWith("https://", true) && !source.startsWith("http://", true) &&
                !source.startsWith("data:image/", true)) return null
            return MessageImage(source, name.ifBlank { "图片" }, attachmentId)
        }
    }
}

internal object MessageImages {
    fun printable(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().also { result ->
            val image = value.string("type") in setOf("image", "inputImage", "input_image", "output_image", "image_url") ||
                value.string("mimeType").startsWith("image/")
            value.keys().forEach { key ->
                result.put(key, if (image && key in setOf("data", "blob")) "[图片]" else printable(value.opt(key)))
            }
        }
        is JSONArray -> JSONArray().also { result -> for (index in 0 until value.length()) result.put(printable(value.opt(index))) }
        is String -> if (value.startsWith("data:image/", true)) "[图片]" else value
        else -> value
    }

    fun attachment(line: String): JSONObject? = if (line.startsWith(AttachmentStore.MARKER)) {
        runCatching { JSONObject(line.removePrefix(AttachmentStore.MARKER)) }.getOrNull()
            ?.takeIf { it.string("name").isNotEmpty() && it.string("path").isNotEmpty() }
    } else null

    fun isImage(file: JSONObject): Boolean = file.string("mimeType").startsWith("image/")

    fun fromItem(item: JSONObject): List<MessageImage> {
        val images = LinkedHashMap<String, MessageImage>()
        fun add(source: String, name: String = "图片", id: String = "") {
            MessageImage.fromSource(source, name, id)?.let { images.putIfAbsent(it.source, it) }
        }
        fun content(array: JSONArray) {
            for (index in 0 until array.length()) {
                val value = array.optJSONObject(index) ?: continue
                when (value.string("type")) {
                    "image", "inputImage", "input_image", "output_image", "image_url", "localImage" -> {
                        val source = listOf(value.string("url"), value.string("path"), value.string("imageUrl"),
                            value.string("image_url").takeUnless { value.opt("image_url") is JSONObject }.orEmpty(),
                            value.objectAt("image_url").string("url")).firstOrNull { it.isNotEmpty() }.orEmpty()
                        if (source.isNotEmpty()) add(source)
                        else if (value.string("data").isNotEmpty() && value.string("mimeType").startsWith("image/")) {
                            add("data:${value.string("mimeType")};base64,${value.string("data")}")
                        }
                    }
                    "resource" -> {
                        val resource = value.objectAt("resource")
                        if (resource.string("mimeType").startsWith("image/") && resource.string("blob").isNotEmpty())
                            add("data:${resource.string("mimeType")};base64,${resource.string("blob")}")
                    }
                }
            }
        }
        val values = item.arrayAt("content")
        // Metadata comes first so a matching localImage uses the durable upload ID.
        for (index in 0 until values.length()) {
            values.optJSONObject(index)?.string("text")?.lineSequence()?.forEach { line ->
                attachment(line)?.takeIf(::isImage)?.let { add(it.string("path"), it.string("name"), it.string("id")) }
            }
        }
        content(values)
        content(item.objectAt("result").arrayAt("content"))
        content(item.arrayAt("contentItems"))
        if (item.string("type") in setOf("imageView", "imageGeneration")) {
            val path = listOf("path", "savedPath", "outputPath", "imageUrl", "url").map(item::string).firstOrNull { it.isNotEmpty() }
            if (path != null) add(path)
            else if (item.string("type") == "imageGeneration" && item.opt("result") is String && item.string("result").isNotEmpty()) {
                val data = item.string("result")
                add(if (data.startsWith("data:image/")) data else "data:image/png;base64,$data")
            }
        }
        return images.values.toList()
    }
}
