package com.codexrm.mobile

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

// App-private files survive process restarts and sent-draft cleanup. Hashes keep
// paths, URLs and credentials out of filenames; partial transfers never hit read().
internal class ImageFileCache(private val directory: File, private val maxBytes: Long = 256L * 1024 * 1024) {
    fun read(key: String): File? = synchronized(lock) {
        target(key).takeIf { it.isFile && it.length() > 0 }?.also { it.setLastModified(System.currentTimeMillis()) }
    }

    fun write(key: String, copy: (OutputStream) -> Unit): File {
        directory.mkdirs()
        val temporary = File.createTempFile("image-", ".part", directory)
        try {
            temporary.outputStream().use(copy)
            if (temporary.length() == 0L || temporary.length() > AttachmentStore.MAX_SIZE) throw IOException("图片大小无效或超过 20 MB")
            return synchronized(lock) {
                val destination = target(key)
                // Mutable Agent paths refresh the same key on every load. Windows does
                // not replace an existing destination with File.renameTo().
                if (destination.exists() && !destination.delete()) throw IOException("无法替换旧图片缓存")
                if (!temporary.renameTo(destination)) throw IOException("无法保存图片缓存")
                destination.setLastModified(System.currentTimeMillis())
                var total = directory.listFiles()?.filter { it.extension == "image" }?.sumOf { it.length() } ?: 0
                directory.listFiles()?.filter { it.extension == "image" && it != destination }
                    ?.sortedBy { it.lastModified() }?.forEach { file ->
                        val size = file.length()
                        if (total > maxBytes && file.delete()) total -= size
                    }
                destination
            }
        } finally { temporary.delete() }
    }

    fun remove(key: String) { synchronized(lock) { target(key).delete() } }

    private fun target(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return File(directory, digest.joinToString("") { "%02x".format(it) } + ".image")
    }

    companion object { private val lock = Any() }
}
