package com.codexrm.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.widget.Toast
import kotlin.concurrent.thread

internal class WorkspaceFileUi(
    private val activity: Activity,
    private val client: () -> CodexClient?,
    private val device: () -> Device?,
    private val directFilesSupported: () -> Boolean,
    private val refreshFileCards: () -> Unit,
) {
    private val downloads = WorkspaceDownloads(activity)
    val downloadLocation: String get() = downloads.locationLabel
    val hasCustomDownloadLocation: Boolean get() = downloads.location != null
    private var manager: WorkspaceFilesDialog? = null
    private var transfer: DirectFileDownload? = null
    private var progress: MiuixDialog? = null
    private data class Request(val key: String, val source: RemoteFile, val name: String, val folder: PhoneFolder?)
    private data class DownloadJob(val owner: Device, val requests: List<Request>, val copying: Boolean = false)
    private var pendingPermission: DownloadJob? = null
    private val busy get() = checking || transfer != null || pendingPermission != null || saving
    private var checking = false
    private var lookupEpoch = 0
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

    fun open(source: RemoteFile, computerFiles: Boolean = false, startAtComputer: Boolean = false) {
        manager?.dismiss()
        lateinit var next: WorkspaceFilesDialog
        next = WorkspaceFilesDialog(activity, source, computerFiles, startAtComputer, client, downloads, { chooseFolder(PICK_BROWSER_TREE) },
            ::openOrDownload, ::copyFiles, ::openLocal) {
            if (manager === next) manager = null
        }
        manager = next
        next.show()
    }

    fun closeManager() { manager?.dismiss(); manager = null }

    fun activityStopped() {
        lookupEpoch++
        transfer?.cancel(); transfer = null
        progress?.dismiss(); progress = null
    }

    fun close() {
        activityStopped(); closeManager()
        pendingPermission = null
    }

    fun chooseDownloadLocation() = chooseFolder(PICK_DOWNLOAD_TREE)

    fun resetDownloadLocation() {
        downloads.setLocation(null)
        manager?.refreshPhone()
    }

    @Suppress("DEPRECATION")
    private fun chooseFolder(requestCode: Int) {
        runCatching {
            activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            ), requestCode)
        }.onFailure { toast("无法打开系统文件夹选择器") }
    }

    fun isDownloaded(source: RemoteFile): Boolean = device()?.let { downloads.contains(WorkspaceDownloads.key(it, source)) } == true

    fun openOrDownload(source: RemoteFile, name: String, folder: PhoneFolder? = null) {
        if (busy) return toast("已有文件正在处理")
        val owner = device() ?: return toast("未选择电脑")
        val request = Request(WorkspaceDownloads.key(owner, source), source, name, folder ?: downloads.location)
        val wasDownloaded = downloads.contains(request.key)
        checking = true
        val epoch = lookupEpoch
        thread(name = "codexrm-open-download") {
            val result = runCatching { downloads.findReadable(request.key) }
            activity.runOnUiThread {
                checking = false
                if (epoch != lookupEpoch || activity.isDestroyed || activity.isFinishing || device() != owner) return@runOnUiThread
                result.fold(onSuccess = { file ->
                    if (file != null) openLocal(file, name)
                    else {
                        if (wasDownloaded) refreshFileCards()
                        beginDownload(DownloadJob(owner, listOf(request)))
                    }
                }, onFailure = { toast("无法读取已下载的文件") })
            }
        }
    }

    private fun copyFiles(files: List<Pair<RemoteFile, String>>, folder: PhoneFolder?): Boolean {
        if (busy) {
            toast("已有文件正在处理")
            return false
        }
        val owner = device() ?: return false
        if (files.isEmpty()) return false
        val requests = files.map { (source, name) -> Request(WorkspaceDownloads.key(owner, source), source, name, folder) }
        return beginDownload(DownloadJob(owner, requests, copying = true))
    }

    private fun beginDownload(job: DownloadJob): Boolean {
        if (client()?.usingDirect != true) {
            toast("P2P 直连后可下载此文件")
            return false
        }
        if (!directFilesSupported()) {
            toast("当前 Agent 不支持直连文件下载")
            return false
        }
        if (job.requests.first().folder == null && Build.VERSION.SDK_INT <= 28 &&
            activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingPermission = job
            activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), WRITE_DOWNLOADS)
            return true
        }
        startDownload(job, 0)
        return true
    }

    private fun startDownload(job: DownloadJob, index: Int) {
        val current = client()?.takeIf { it.usingDirect && device() == job.owner }
        if (current == null) {
            reportFailure(job, index, "P2P 直连已断开")
            return
        }
        val request = job.requests[index]
        val bar = MiuixProgressView(activity, linear = true, maximum = 1000).apply {
            isIndeterminate = true
            contentDescription = "文件传输进度"
        }
        val status = Ui.text(activity, "正在读取文件…", 13f, Ui.MUTED).apply {
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val layout = Ui.column(activity).apply {
            setPadding(Ui.dp(activity, 24), Ui.dp(activity, 8), Ui.dp(activity, 24), 0)
            addView(status)
            addView(bar)
        }
        val title = if (job.requests.size == 1) request.name else "${index + 1}/${job.requests.size} · ${request.name}"
        val dialog = MiuixDialog.Builder(activity).setTitle(title).setView(layout)
            .setNegativeButton("取消") { _, _ -> activityStopped() }.create()
        dialog.setOnCancelListener { activityStopped() }
        progress = dialog
        dialog.show()
        val epoch = lookupEpoch
        val task = DirectFileDownload(current)
        transfer = task
        task.start(activity.cacheDir, request.source, { received, total ->
            bar.isIndeterminate = false
            bar.progress = if (total == 0L) 1000 else (received * 1000 / total).toInt()
            bar.contentDescription = "已传输 ${bar.progress / 10}%"
            status.text = "${AttachmentStore.sizeLabel(received)} / ${AttachmentStore.sizeLabel(total)}"
        }) { result ->
            transfer = null
            result.fold(onSuccess = { file ->
                if (activity.isDestroyed) file.file.delete()
                else {
                    status.text = "正在保存文件…"
                    bar.isIndeterminate = true
                    dialog.getButton(MiuixDialog.NEGATIVE).isEnabled = false
                    dialog.setCancelable(false)
                    saveTo(job, index, file, epoch)
                }
            }, onFailure = {
                progress = null
                dialog.dismiss()
                reportFailure(job, index, it.message ?: "下载失败")
            })
        }
    }

    private fun reportFailure(job: DownloadJob, index: Int, message: String) {
        if (job.copying && index > 0) manager?.refreshPhone()
        toast(if (job.copying) "已复制 $index/${job.requests.size} 项 · $message" else message)
    }

    fun onActivityResult(code: Int, result: Int, data: Intent?): Boolean {
        if (code != PICK_BROWSER_TREE && code != PICK_DOWNLOAD_TREE) return false
        if (result != Activity.RESULT_OK || data == null) return true
        val uri = data.data ?: return true
        runCatching {
            if (code == PICK_BROWSER_TREE) manager?.setFolder(uri, data.flags)
            else {
                downloads.setLocation(uri, data.flags)
                manager?.refreshPhone()
                toast("下载位置已设为 ${downloads.locationLabel}")
            }
        }.onFailure { toast("无法授权此手机目录") }
        return true
    }

    fun onRequestPermissionsResult(code: Int, results: IntArray): Boolean {
        if (code != WRITE_DOWNLOADS) return false
        val job = pendingPermission ?: return true
        pendingPermission = null
        if (results.firstOrNull() != PackageManager.PERMISSION_GRANTED) toast("需要存储权限才能保存到 Download")
        else if (device() == job.owner) startDownload(job, 0)
        return true
    }

    private fun saveTo(job: DownloadJob, index: Int, file: StagedDownload, epoch: Int) {
        val request = job.requests[index]
        saving = true
        thread(name = "codexrm-save-download") {
            val result = runCatching { downloads.save(request.key, file, request.folder) }
            file.file.delete()
            activity.runOnUiThread {
                saving = false
                progress?.dismiss()
                progress = null
                if (activity.isDestroyed) return@runOnUiThread
                if (result.isSuccess) refreshFileCards()
                if (result.isSuccess && index < job.requests.lastIndex && epoch == lookupEpoch && !activity.isFinishing) {
                    startDownload(job, index + 1)
                    return@runOnUiThread
                }
                manager?.refreshPhone()
                if (epoch != lookupEpoch || activity.isFinishing) return@runOnUiThread
                val destination = request.folder?.label ?: Environment.DIRECTORY_DOWNLOADS
                when (val error = result.exceptionOrNull()) {
                    null -> toast(if (job.copying) "已复制 ${index + 1} 项到 $destination" else "已保存到 $destination")
                    is SecurityException -> reportFailure(job, index, "无法写入手机目录，请重新选择")
                    else -> reportFailure(job, index, error.message ?: "文件保存失败")
                }
            }
        }
    }

    private fun openLocal(uri: Uri, name: String) {
        try {
            val mime = activity.contentResolver.getType(uri) ?: WorkspaceDownloads.mime(name)
            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            toast("没有可打开此文件的应用")
        } catch (_: SecurityException) {
            toast("无法打开此文件，请检查目录权限")
        }
    }

    private fun toast(message: String) {
        if (!activity.isDestroyed) Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val PICK_BROWSER_TREE = 44
        private const val PICK_DOWNLOAD_TREE = 45
        private const val WRITE_DOWNLOADS = 48
    }
}
