package com.codexrm.mobile

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

internal data class TransferEntry(val id: String, val name: String, val size: Long, val created: Long, val state: String, val batch: String = "") {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("size", size).put("created", created).put("state", state).put("batch", batch)
    companion object {
        const val MAX_FILE = 512L shl 20
        fun parse(json: JSONObject, state: String = json.string("state")): TransferEntry? {
            val id = json.string("id")
            val name = json.string("name")
            val size = json.optLong("size", -1)
            if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id || name.isBlank() ||
                name.length > 255 || name.any { it == '/' || it == '\\' || it.isISOControl() } || name == "." || name == ".." ||
                size !in 0..MAX_FILE || state !in setOf("pending", "sent", "received", "remote", "cancelledSend", "cancelledIncoming", "cancelledReceive")) return null
            val batch = json.string("batch").takeIf { runCatching { UUID.fromString(it).toString() }.getOrNull() == it }.orEmpty()
            return TransferEntry(id, name, size, json.optLong("created"), state, batch)
        }
    }
}

internal fun completedPhoneTransferBatch(
    entries: List<TransferEntry>, staging: Set<String>, eligible: (String) -> Boolean,
): String? {
    val pending = entries.filter { it.state == "pending" }.mapTo(HashSet()) { it.batch }
    return entries.firstOrNull { it.state == "sent" && it.batch.isNotEmpty() && it.batch !in pending &&
        it.batch !in staging && eligible(it.batch) }?.batch
}

internal class TransferStorage(private val context: Context) {
    private val application = context.applicationContext
    private fun dir(owner: Device): File {
        val pair = listOf(owner.serverUrl, owner.agentId, owner.clientId).joinToString("\u0000")
        val key = MessageDigest.getInstance("SHA-256").digest(pair.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(application.filesDir, "file-transfer/$key")
    }
    fun payload(owner: Device, item: TransferEntry): File = File(dir(owner), "${item.id}.data")
    fun entries(owner: Device): List<TransferEntry> = dir(owner).listFiles { file -> file.name.endsWith(".json") }
        ?.mapNotNull { file ->
            runCatching { TransferEntry.parse(JSONObject(AtomicFile(file).readFully().decodeToString())) }.getOrNull()
                ?.takeIf { "${it.id}.json" == file.name }
        }?.sortedByDescending { it.created }.orEmpty()

    private fun record(owner: Device, item: TransferEntry) {
        val folder = dir(owner)
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("无法创建文件传输缓存")
        val file = AtomicFile(File(folder, "${item.id}.json"))
        val stream = file.startWrite()
        try {
            stream.write(item.json().toString().toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized fun sent(owner: Device, item: TransferEntry) {
        record(owner, item.copy(state = "sent"))
        payload(owner, item).delete()
        pruneHistory(owner)
    }

    @Synchronized fun cancel(owner: Device, item: TransferEntry) {
        record(owner, item.copy(state = if (item.state == "pending") "cancelledSend" else "cancelledIncoming", batch = ""))
        payload(owner, item).delete()
        pruneHistory(owner)
    }

    @Synchronized fun acknowledgeCancellation(owner: Device, item: TransferEntry) {
        record(owner, item.copy(state = "cancelledReceive"))
        pruneHistory(owner)
    }

    @Synchronized fun finishBatch(owner: Device, batch: String) {
        entries(owner).filter { it.state == "sent" && it.batch == batch }.forEach {
            record(owner, it.copy(batch = ""))
        }
        pruneHistory(owner)
    }

    private fun pruneHistory(owner: Device) {
        entries(owner).filter { it.state == "cancelledSend" || it.state == "cancelledReceive" || (it.state == "sent" && it.batch.isEmpty()) }
            .drop(200).forEach { remove(owner, it) }
    }

    @Synchronized fun remove(owner: Device, item: TransferEntry) {
        AtomicFile(File(dir(owner), "${item.id}.json")).delete()
        payload(owner, item).delete()
    }

    @Synchronized fun receive(owner: Device, item: TransferEntry, staged: StagedDownload) {
        if (staged.name != item.name || staged.file.length() != item.size) throw IOException("接收的文件与缓存记录不一致")
        checkRoom(owner, item.size)
        val target = payload(owner, item)
        if (!staged.file.renameTo(target)) throw IOException("无法保存到接收缓存")
        try { record(owner, item.copy(state = "received")) }
        catch (error: Exception) { target.delete(); throw error }
    }

    @Synchronized fun stage(owner: Device, uri: Uri?, text: String, batch: String = ""): TransferEntry {
        checkRoom(owner, 0)
        var name = "分享内容.txt"
        val resolver = application.contentResolver
        if (uri != null) {
            name = uri.lastPathSegment.orEmpty().substringAfterLast('/')
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0).orEmpty().ifBlank { name }
                    if (!cursor.isNull(1)) checkRoom(owner, cursor.getLong(1).coerceAtLeast(0))
                }
            }
        }
        name = name.map { if (it in "<>:\"/\\|?*" || it.isISOControl()) '_' else it }.joinToString("")
            .take(180).trim().trimEnd('.').ifBlank { "文件" }
        val item = TransferEntry(UUID.randomUUID().toString(), name, 0, System.currentTimeMillis(), "pending", batch)
        val target = payload(owner, item)
        val maxSize = minOf(TransferEntry.MAX_FILE, (2L shl 30) - entries(owner).filter { it.state != "sent" }.sumOf { it.size })
        try {
            val input = if (uri == null) text.byteInputStream() else resolver.openInputStream(uri) ?: throw IOException("无法读取文件")
            val written = input.use { source ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count == -1) break
                        size += count
                        if (size > maxSize) throw IOException("单个文件最大 512 MB，或文件传输缓存已满")
                        output.write(buffer, 0, count)
                    }
                    size
                }
            }
            return item.copy(size = written).also { record(owner, it) }
        } catch (error: Exception) { target.delete(); throw error }
    }

    private fun checkRoom(owner: Device, additional: Long) {
        if (additional > TransferEntry.MAX_FILE) throw IOException("单个文件最大 512 MB")
        val folder = dir(owner)
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("无法创建文件传输缓存")
        val files = entries(owner).filter { it.state == "pending" || it.state == "received" }
        val known = files.mapTo(HashSet()) { "${it.id}.data" }
        folder.listFiles { file -> file.name.endsWith(".data") && file.name !in known }?.forEach { it.delete() }
        if (files.size >= 64 || files.sumOf { it.size } + additional > (2L shl 30)) throw IOException("文件传输缓存已满，请清理缓存")
    }

    companion object {
        val worker = Executors.newSingleThreadExecutor()
        val sender: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }
}