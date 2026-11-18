package com.codexrm.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.SystemClock
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.widget.ProgressBar
import android.widget.Toast
import java.io.IOException
import java.util.Locale
import kotlin.concurrent.thread

internal class WorkspaceFileUi(private val activity: Activity, private val client: () -> CodexClient?) {
    private var manager: WorkspaceFilesDialog? = null
    private var transfer: DirectFileDownload? = null
    private var progress: AlertDialog? = null
    private var pendingSave: StagedDownload? = null
    private var saving = false
    private data class FileSize(val bytes: Long?, val expires: Long)
    private val sizes = android.util.LruCache<RemoteFile, FileSize>(128)
    private val pendingStats = HashMap<RemoteFile, MutableList<(Long?) -> Unit>>()
    private var directClient: CodexClient? = null

    fun connectionChanged() {
        val active = client()?.takeIf { it.usingDirect }
        if (active === directClient) return
        directClient = active
        sizes.evictAll(); pendingStats.clear()
        manager?.refreshRemote()
    }

    fun size(source: RemoteFile, complete: (Long?) -> Unit) {
        val active = directClient ?: return complete(null)
        sizes.get(source)?.takeIf { it.expires > SystemClock.elapsedRealtime() }?.let { return complete(it.bytes) }
        pendingStats[source]?.let { it += complete; return }
        if (pendingStats.size >= 8) return complete(null)
        val observers = mutableListOf(complete)
        pendingStats[source] = observers
        active.requestDirectFile("stat", source.params()) { result, _ ->
            if (pendingStats[source] !== observers) return@requestDirectFile
            pendingStats.remove(source)
            val value = result?.optLong("size", -1)?.takeIf { it >= 0 }
            sizes.put(source, FileSize(value, SystemClock.elapsedRealtime() + 30_000))
            observers.forEach { it(value) }
        }
    }

    fun open(source: RemoteFile) {
        manager?.dismiss()
        manager = WorkspaceFilesDialog(activity, source, client, ::chooseFolder, ::download) { manager = null }.also { it.show() }
    }

    fun closeManager() { manager?.dismiss(); manager = null }

    fun activityStopped() {
        transfer?.cancel(); transfer = null
        progress?.dismiss(); progress = null
    }

    fun close() {
        activityStopped(); closeManager()
        pendingSave?.file?.delete(); pendingSave = null
    }

    @Suppress("DEPRECATION")
    private fun chooseFolder() {
        runCatching {
            activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            ), PICK_TREE)
        }.onFailure { toast("无法打开系统文件夹选择器") }
    }

    fun download(source: RemoteFile, name: String, folder: PhoneFolder? = null) {
        val current = client()?.takeIf { it.usingDirect } ?: return toast("P2P 直连已断开")
        if (transfer != null || pendingSave != null || saving) return toast("已有文件正在保存")
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; isIndeterminate = true }
        val status = Ui.text(activity, "正在读取文件…", 13f, Ui.MUTED)
        val layout = Ui.column(activity).apply {
            setPadding(Ui.dp(activity, 24), Ui.dp(activity, 8), Ui.dp(activity, 24), 0)
            addView(status); addView(bar)
        }
        val task = DirectFileDownload(current)
        transfer = task
        val dialog = AlertDialog.Builder(activity).setTitle(name).setView(layout).setNegativeButton("取消") { _, _ -> activityStopped() }.create()
        dialog.setOnCancelListener { activityStopped() }
        progress = dialog
        dialog.show()
        task.start(activity.cacheDir, source, { received, total ->
            bar.isIndeterminate = false
            bar.progress = if (total == 0L) 1000 else (received * 1000 / total).toInt()
            status.text = "${AttachmentStore.sizeLabel(received)} / ${AttachmentStore.sizeLabel(total)}"
        }) { result ->
            transfer = null; progress = null; dialog.dismiss()
            result.fold(onSuccess = { file ->
                if (activity.isDestroyed) file.file.delete()
                else if (folder == null) askWhereToSave(file) else saveTo(file, folder)
            }, onFailure = { toast(it.message ?: "下载失败") })
        }
    }

    @Suppress("DEPRECATION")
    private fun askWhereToSave(file: StagedDownload) {
        pendingSave = file
        runCatching {
            activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime(file.name)
                putExtra(Intent.EXTRA_TITLE, file.name)
            }, SAVE_DOCUMENT)
        }.onFailure {
            pendingSave = null; file.file.delete(); toast("无法打开系统保存窗口")
        }
    }

    fun onActivityResult(code: Int, result: Int, data: Intent?): Boolean {
        when (code) {
            PICK_TREE -> if (result == Activity.RESULT_OK && data?.data != null) {
                runCatching { manager?.setFolder(data.data!!, data.flags) }.onFailure { toast("无法授权此手机目录") }
            }
            SAVE_DOCUMENT -> {
                val file = pendingSave ?: return true
                pendingSave = null
                val uri = data?.data
                if (result == Activity.RESULT_OK && uri != null) saveTo(file, null, uri) else file.file.delete()
            }
            else -> return false
        }
        return true
    }

    private fun saveTo(file: StagedDownload, folder: PhoneFolder?, document: android.net.Uri? = null) {
        saving = true
        thread(name = "codexrm-save-download") {
            var uri = document
            val result = runCatching {
                if (folder != null) uri = DocumentsContract.createDocument(activity.contentResolver, folder.uri, mime(file.name), file.name)
                val target = uri ?: throw IOException("无法创建目标文件")
                activity.contentResolver.openOutputStream(target, "w")?.use { output -> file.file.inputStream().use { it.copyTo(output) } }
                    ?: throw IOException("无法写入目标文件")
            }
            if (result.isFailure) uri?.let { target -> runCatching { DocumentsContract.deleteDocument(activity.contentResolver, target) } }
            file.file.delete()
            activity.runOnUiThread {
                saving = false
                manager?.refreshPhone()
                toast(if (result.isSuccess) "文件已保存" else result.exceptionOrNull()?.message ?: "文件保存失败")
            }
        }
    }

    private fun toast(message: String) {
        if (!activity.isDestroyed) Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private fun mime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    companion object {
        private const val PICK_TREE = 44
        private const val SAVE_DOCUMENT = 45
    }
}
