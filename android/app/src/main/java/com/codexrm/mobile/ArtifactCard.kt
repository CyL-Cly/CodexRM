package com.codexrm.mobile

import org.json.JSONObject
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Link
import org.commonmark.parser.Parser
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

internal data class ArtifactCard(val path: String, val name: String, val type: String, val change: String, val linked: Boolean = false) {
    companion object {
        private val office = "doc docx docm dot dotx xls xlsx xlsm xlsb xlt xltx ppt pptx pptm pps ppsx pot potx odt ods odp wps et dps rtf pdf".split(' ').toSet()
        private val image = "png jpg jpeg gif webp bmp svg ico tiff tif heic heif avif dng".split(' ').toSet()
        private val audio = "mp3 wav flac aac m4a ogg oga opus wma amr aiff mid midi".split(' ').toSet()
        private val video = "mp4 webm mov mkv avi m4v mpeg mpg 3gp ts flv wmv rmvb".split(' ').toSet()
        private val markdown by lazy { Parser.builder().build() }
        private val drive = Regex("^[A-Za-z]:[/\\\\]")
        private val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

        private val lineAnchor = Regex("#L\\d+(?:C\\d+)?(?:-L\\d+(?:C\\d+)?)?$")
        private val windowsLine = Regex(":\\d+(?::\\d+)?$")

        fun fromLink(destination: String): ArtifactCard? =
            linkPath(destination)?.let { card(it, "产物", linked = true) }

        fun fromItem(item: JSONObject): List<ArtifactCard> {
            if (item.string("type") == "agentMessage") return fromLinks(item.string("text"))
            if (item.string("type") != "fileChange" || item.string("status") != "completed") return emptyList()
            val files = item.arrayAt("changes")
            return (0 until files.length()).asSequence().mapNotNull { index ->
                val file = files.optJSONObject(index) ?: return@mapNotNull null
                val kind = file.objectAt("kind")
                val operation = when (kind.string("type")) {
                    "add" -> "新增"
                    "update" -> "修改"
                    else -> return@mapNotNull null
                }
                val path = kind.string("move_path").ifEmpty { file.string("path") }
                card(path, operation)
            }.distinctBy { it.path }.toList()
        }

        private fun fromLinks(value: String): List<ArtifactCard> {
            if (!value.contains('[') || !value.contains(']')) return emptyList()
            val files = LinkedHashMap<String, ArtifactCard>()
            markdown.parse(value.take(65_536)).accept(object : AbstractVisitor() {
                override fun visit(link: Link) {
                    if (files.size >= 8) return
                    val artifact = fromLink(link.destination) ?: return
                    files.putIfAbsent(artifact.path, artifact)
                }
            })
            return files.values.toList()
        }

        private fun linkPath(destination: String): String? {
            val raw = destination.replace(lineAnchor, "")
            val decoded = runCatching {
                if (raw.startsWith("file:", ignoreCase = true)) {
                    val uri = URI(raw)
                    if (uri.rawAuthority != null || uri.rawQuery != null || uri.rawFragment != null) return null
                    uri.path ?: return null
                } else {
                    val local = raw.removePrefix("sandbox:")
                    if ('#' in local || '?' in local) return null
                    URLDecoder.decode(local.replace("+", "%2B"), "UTF-8")
                }
            }.getOrNull() ?: return null
            if (decoded.startsWith("//") || decoded.startsWith('\\') || decoded.any { it.isISOControl() }) return null
            val withoutLeadingSlash = decoded.removePrefix("/")
            val windows = drive.containsMatchIn(withoutLeadingSlash)
            if (!windows && scheme.containsMatchIn(decoded)) return null
            val path = if (windows) withoutLeadingSlash.replace(windowsLine, "") else decoded
            return path.takeIf { it.isNotBlank() && !it.endsWith('/') && !it.endsWith('\\') }
        }

        private fun card(path: String, operation: String, linked: Boolean = false): ArtifactCard? {
            val name = path.substringAfterLast('/').substringAfterLast('\\')
            if (name.isBlank() || name == "." || name == "..") return null
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val type = when (ext) {
                in office -> if (ext == "pdf") "PDF" else "Office 文档"
                in image -> "图片"
                in audio -> "音频"
                in video -> "视频"
                "apk" -> "APK"
                else -> if (linked) "文件" else return null
            }
            return ArtifactCard(path, name, type, operation, linked)
        }
    }
}
