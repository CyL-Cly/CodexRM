package com.codexrm.mobile

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale

internal class DownloadFileProvider : FileProvider()

internal class WorkspaceDownloads(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val folders = app.getSharedPreferences("file-manager", Context.MODE_PRIVATE)
    private val files = app.getSharedPreferences("workspace-downloads", Context.MODE_PRIVATE)

    val location: PhoneFolder? get() = folder(DOWNLOAD_FOLDER)
    val browserFolder: PhoneFolder? get() = folder(BROWSER_FOLDER)
    val locationLabel: String get() = location?.label ?: Environment.DIRECTORY_DOWNLOADS

    fun setLocation(uri: Uri?, flags: Int = 0) = setFolder(DOWNLOAD_FOLDER, uri, flags)
    fun setBrowserFolder(uri: Uri, flags: Int) = setFolder(BROWSER_FOLDER, uri, flags)

    private fun folder(setting: String): PhoneFolder? = folders.getString(setting, null)?.let {
        val tree = Uri.parse(it)
        PhoneFolder(tree, DocumentsContract.getTreeDocumentId(tree))
    }

    private fun setFolder(setting: String, uri: Uri?, flags: Int) {
        val previous = folder(setting)?.tree
        if (uri != null) {
            require(DocumentsContract.isTreeUri(uri) && flags and READ_WRITE == READ_WRITE)
            resolver.takePersistableUriPermission(uri, READ_WRITE)
        }
        folders.edit().putString(setting, uri?.toString()).apply()
        if (previous != null && previous != uri) releaseUnusedFolder(previous)
    }

    private fun releaseUnusedFolder(tree: Uri) {
        if (tree == location?.tree || tree == browserFolder?.tree) return
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val referenced = files.all.values.any { value ->
            val document = Uri.parse(value as String)
            document.authority == tree.authority && DocumentsContract.isTreeUri(document) &&
                DocumentsContract.getTreeDocumentId(document) == treeId
        }
        if (!referenced) resolver.persistedUriPermissions.firstOrNull { it.uri == tree }?.let { permission ->
            val mode = (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            runCatching { resolver.releasePersistableUriPermission(tree, mode) }
        }
    }

    fun contains(key: String): Boolean = files.contains(key)

    // Test the saved URI before trying the remote connection. A removed file can be downloaded again.
    fun findReadable(key: String): Uri? {
        val uri = files.getString(key, null)?.let(Uri::parse) ?: return null
        try {
            resolver.openFileDescriptor(uri, "r")?.use { return uri }
        } catch (_: FileNotFoundException) {
            // The file may have been moved or deleted in the phone's file manager.
        } catch (_: SecurityException) {
            // Access to the selected folder may have been revoked.
        } catch (error: IllegalArgumentException) {
            // ExternalStorageProvider rejects deleted tree documents while checking ancestry.
            if (!DocumentsContract.isTreeUri(uri)) throw error
        }
        files.edit().remove(key).apply()
        if (DocumentsContract.isTreeUri(uri)) {
            releaseUnusedFolder(DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri)))
        }
        return null
    }

    fun save(key: String, stage: StagedDownload, folder: PhoneFolder?) {
        val name = stage.name
        require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name) { "文件名无效" }
        val mime = mime(name)
        val uri = when {
            folder != null -> {
                val document = DocumentsContract.createDocument(resolver, folder.uri, mime, name)
                    ?: throw IOException("无法在所选目录创建文件")
                try {
                    copy(stage.file, document)
                } catch (error: Exception) {
                    runCatching { DocumentsContract.deleteDocument(resolver, document) }
                    throw error
                }
                document
            }
            Build.VERSION.SDK_INT >= 29 -> saveToMediaStore(stage, mime)
            else -> saveToLegacyDownloads(stage)
        }
        files.edit().putString(key, uri.toString()).apply()
    }

    private fun copy(file: File, uri: Uri) {
        val stream = resolver.openOutputStream(uri, "w") ?: throw IOException("无法写入目标文件")
        stream.use { output -> file.inputStream().use { it.copyTo(output) } }
    }

    @androidx.annotation.RequiresApi(29)
    private fun saveToMediaStore(stage: StagedDownload, mime: String): Uri {
        val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, stage.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(downloads, values) ?: throw IOException("无法在 Download 创建文件")
        try {
            copy(stage.file, uri)
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, published, null, null) != 1) throw IOException("无法保存至 Download")
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveToLegacyDownloads(stage: StagedDownload): Uri {
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建 Download 目录")
        val stem = stage.name.substringBeforeLast('.')
        val extension = stage.name.substring(stem.length)
        var target = File(directory, stage.name)
        var suffix = 1
        while (!target.createNewFile()) target = File(directory, "$stem (${suffix++})$extension")
        try {
            stage.file.copyTo(target, overwrite = true)
            return FileProvider.getUriForFile(app, "${app.packageName}.downloads", target)
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    companion object {
        private const val DOWNLOAD_FOLDER = "download-tree"
        private const val BROWSER_FOLDER = "tree"
        private const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        fun key(device: Device, source: RemoteFile): String {
            // The Agent resolves both absolute artifact links and workspace-relative list paths.
            val windows = (if (source.computer) source.path else source.workspace).getOrNull(1) == ':'
            fun normalized(value: String): String {
                val path = if (windows) value.replace('\\', '/') else value
                val components = path.split('/').filter { it.isNotEmpty() && it != "." }
                return (if (path.startsWith('/')) "/" else "") + components.joinToString("/")
            }
            val workspace = normalized(source.workspace)
            val path = normalized(source.path)
            val prefix = workspace.trimEnd('/') + "/"
            // Reuse existing download records for files inside the current workspace.
            val outsideWorkspace = source.computer && (workspace.isEmpty() || !path.startsWith(prefix, ignoreCase = windows))
            val keyWorkspace = if (outsideWorkspace) "" else workspace
            val keyPath = when {
                outsideWorkspace -> path
                source.computer -> path.substring(prefix.length)
                else -> path.removePrefix(prefix)
            }
            return JSONArray(listOf(device.serverUrl, device.agentId, keyWorkspace, keyPath)).toString()
        }

        fun mime(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
