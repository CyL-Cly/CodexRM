package com.codexrm.mobile

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

internal class ConversationCache(context: Context) {
    private val directory = File(context.filesDir, "conversation-cache")

    private fun key(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun deviceDirectory(agent: String) = File(directory, key(agent))
    private fun file(agent: String, thread: String) = AtomicFile(File(deviceDirectory(agent), key(thread)))

    fun read(agent: String, thread: String): JSONObject? = runCatching {
        JSONObject(file(agent, thread).readFully().decodeToString())
    }.getOrNull()

    fun write(agent: String, thread: String, snapshot: String) {
        if (snapshot.length > 2_000_000) return
        worker.execute {
            val device = deviceDirectory(agent)
            device.mkdirs()
            val file = file(agent, thread)
            runCatching {
                val output = file.startWrite()
                try {
                    output.write(snapshot.toByteArray())
                    file.finishWrite(output)
                } catch (error: Exception) {
                    file.failWrite(output)
                }
                device.listFiles()?.filter { it.name.length == 64 }
                    ?.sortedByDescending(File::lastModified)?.drop(12)?.forEach { AtomicFile(it).delete() }
            }
        }
    }

    fun remove(agent: String, thread: String) {
        worker.execute { file(agent, thread).delete() }
    }

    fun removeAgent(agent: String) {
        worker.execute {
            val device = deviceDirectory(agent)
            device.listFiles()?.forEach(File::delete)
            device.delete()
        }
    }

    companion object {
        private val worker = Executors.newSingleThreadExecutor()
    }
}

internal data class DiffFile(val name: String, val patch: String) {
    private val changes = patch.lineSequence().dropWhile { !it.startsWith("@@") }.mapNotNull { it.firstOrNull() }
    val added = changes.count { it == '+' }
    val removed = changes.count { it == '-' }
}

internal fun diffFiles(patch: String): List<DiffFile> {
    val headers = Regex("(?m)^diff --git .+$").findAll(patch).toList()
    if (headers.isEmpty()) return if (patch.isBlank()) emptyList() else listOf(DiffFile("改动", patch))
    return headers.mapIndexed { index, header ->
        val section = patch.substring(header.range.first, headers.getOrNull(index + 1)?.range?.first ?: patch.length)
        val path = section.lineSequence().firstOrNull { it.startsWith("+++ ") && !it.endsWith("/dev/null") }
            ?: section.lineSequence().firstOrNull { it.startsWith("--- ") }
        DiffFile(path?.substring(4)?.removePrefix("a/")?.removePrefix("b/") ?: header.value.substringAfter(" b/"), section)
    }
}
