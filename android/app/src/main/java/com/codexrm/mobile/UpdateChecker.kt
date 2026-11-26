package com.codexrm.mobile

import android.annotation.TargetApi
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneId

internal object UpdateChecker {
    const val MAX_PACKAGE_SIZE = 512L * 1024L * 1024L
    internal const val SILENT_INSTALL_FALLBACK_MS = 20_000L
    private const val MAX_MANIFEST_SIZE = 1024 * 1024
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val manifestUrl = updateEndpoint(BuildConfig.DEFAULT_UPDATE_SERVER, "android-update.json")
    private val historyUrl = updateEndpoint(BuildConfig.DEFAULT_UPDATE_SERVER, "release-history.json")
    val isAvailable: Boolean get() = manifestUrl != null

    data class VersionInfo(
        val versionCode: Long,
        val versionName: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val fileSize: Long,
        val sha256: String,
    )

    @Suppress("DEPRECATION")
    fun currentVersionCode(context: Context): Long =
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).longVersionCode
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }

    @Suppress("DEPRECATION")
    fun currentVersionName(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName.orEmpty()
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    internal fun shouldUseSilentInstallFlag(sdkInt: Int): Boolean = sdkInt >= 31

    internal fun shouldRetryWithInteractiveInstall(
        status: Int,
        silentAttempt: Boolean,
        interactiveAlreadyStarted: Boolean,
    ): Boolean {
        if (!silentAttempt || interactiveAlreadyStarted) return false
        return when (status) {
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_TIMEOUT -> true
            else -> false
        }
    }

    internal fun isUserCancelledInstall(status: Int): Boolean =
        status == PackageInstaller.STATUS_FAILURE_ABORTED

    fun requestInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    data class ReleaseNote(val versionName: String, val date: String, val notes: String)

    internal fun normalizeUpdateServer(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        if (!value.startsWith("https://", ignoreCase = true) || value.any { it == '\\' || it.isWhitespace() }) return null
        val url = value.toHttpUrlOrNull() ?: return null
        if (url.encodedQuery != null || url.encodedFragment != null ||
            '@' in value.substringAfter("://").substringBefore('/')) return null
        return url.toString().trimEnd('/')
    }

    internal fun updateEndpoint(server: String, file: String): String? =
        normalizeUpdateServer(server)?.takeIf(String::isNotEmpty)?.let { "$it/$file" }

    fun fetchLatest(callback: (VersionInfo?, ApiException?) -> Unit): Call {
        val url = checkNotNull(manifestUrl)
        return fetchJson(url, { parseManifest(it, url) }, callback)
    }

    fun fetchHistory(callback: (List<ReleaseNote>?, ApiException?) -> Unit): Call =
        fetchJson(checkNotNull(historyUrl), ::parseHistory, callback)

    internal fun parseHistory(text: String): List<ReleaseNote> {
        val history = JSONObject(text)
        require(history.optInt("schemaVersion") == 1) { "无法识别更新日志格式" }
        val releases = history.getJSONArray("releases")
        return (0 until releases.length()).mapNotNull { index ->
            val release = releases.getJSONObject(index)
            if (release.optString("platform") != "android") return@mapNotNull null
            val publishedAt = release.optString("publishedAt")
            val date = runCatching {
                OffsetDateTime.parse(publishedAt).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate().toString()
            }.getOrDefault("")
            ReleaseNote(release.getString("versionName"), date, release.optString("releaseNotes").trim())
        }
    }

    private fun <T> fetchJson(url: String, parse: (String) -> T, callback: (T?, ApiException?) -> Unit): Call {
        val call = Network.client.newCall(
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .build(),
        )
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { callback(null, ApiException(e.message ?: "网络连接失败")) }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw ApiException("更新服务器返回 HTTP ${it.code}", it.code)
                        parse(readManifest(it.body))
                    }
                }
                main.post {
                    result.fold(
                        onSuccess = { callback(it, null) },
                        onFailure = {
                            val error = it as? ApiException
                                ?: ApiException("更新数据无效：${it.message ?: "无法解析"}")
                            callback(null, error)
                        },
                    )
                }
            }
        })
        return call
    }

    internal fun parseManifest(text: String, manifestUrl: String): VersionInfo {
        require(text.length <= MAX_MANIFEST_SIZE) { "更新清单超过 1 MiB" }
        val json = JSONObject(text)
        val versionCode = json.optLong("versionCode", 0L)
        val versionName = json.optString("versionName", "").trim()
        val rawDownloadUrl = json.optString("downloadUrl", "").trim()
        val fileSize = json.optLong("fileSize", 0L)
        val sha256 = json.optString("sha256", "").trim().lowercase()
        require(versionCode > 0L && versionName.isNotEmpty()) { "缺少有效的版本号" }
        require(fileSize in 1..MAX_PACKAGE_SIZE) { "安装包大小必须在 1 字节到 512 MiB 之间" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "SHA-256 必须是 64 位十六进制字符" }
        require(rawDownloadUrl.isNotEmpty()) { "缺少下载地址" }
        require(!rawDownloadUrl.startsWith("//")) { "下载地址不能使用协议相对路径" }
        val base = manifestUrl.toHttpUrl()
        val download = base.resolve(rawDownloadUrl)
            ?: throw IllegalArgumentException("下载地址必须是 HTTP(S) 地址或相对路径")
        require(download.username.isEmpty() && download.password.isEmpty() && download.fragment == null) {
            "下载地址不能包含凭据或片段"
        }
        return VersionInfo(
            versionCode = versionCode,
            versionName = versionName,
            releaseNotes = json.optString("releaseNotes", "").trim(),
            downloadUrl = download.toString(),
            fileSize = fileSize,
            sha256 = sha256,
        )
    }

    fun download(
        context: Context,
        info: VersionInfo,
        onProgress: (Int) -> Unit,
        onComplete: (File?, ApiException?) -> Unit,
    ): Call {
        val call = Network.client.newCall(
            Request.Builder().url(info.downloadUrl).header("Accept", "application/vnd.android.package-archive").build(),
        )
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { onComplete(null, ApiException(e.message ?: "下载连接失败")) }
            }

            override fun onResponse(call: Call, response: Response) {
                val directory = File(context.cacheDir, "updates")
                val partial = File(directory, "CodexRM-${info.versionCode}.apk.part")
                val target = File(directory, "CodexRM-${info.versionCode}.apk")
                var lastProgress = -1
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw ApiException("下载服务器返回 HTTP ${it.code}", it.code)
                        if (!directory.exists() && !directory.mkdirs()) throw IOException("无法创建更新缓存目录")
                        partial.delete()
                        downloadBody(it.body, partial, info) { progress ->
                            if (progress != lastProgress) {
                                lastProgress = progress
                                main.post { onProgress(progress) }
                            }
                        }
                        if (partial.length() != info.fileSize) throw IOException("安装包大小与清单不一致")
                        if (!sha256Hex(partial).equals(info.sha256, ignoreCase = true)) {
                            throw IOException("安装包 SHA-256 校验失败")
                        }
                        if (target.exists() && !target.delete()) throw IOException("无法替换旧安装包")
                        if (!partial.renameTo(target)) throw IOException("无法保存已校验的安装包")
                        target
                    }
                }
                if (result.isFailure) partial.delete()
                main.post {
                    result.fold(
                        onSuccess = { onComplete(it, null) },
                        onFailure = {
                            val error = it as? ApiException ?: ApiException(it.message ?: "安装包下载失败")
                            onComplete(null, error)
                        },
                    )
                }
            }
        })
        return call
    }

    private fun readManifest(body: ResponseBody?): String {
        if (body == null) throw ApiException("更新服务器未返回清单")
        if (body.contentLength() > MAX_MANIFEST_SIZE) throw ApiException("更新清单超过 1 MiB")
        body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_MANIFEST_SIZE) throw ApiException("更新清单超过 1 MiB")
                output.write(buffer, 0, count)
            }
            return output.toString("UTF-8")
        }
    }

    private fun downloadBody(body: ResponseBody?, outputFile: File, info: VersionInfo, onProgress: (Int) -> Unit) {
        if (body == null) throw ApiException("下载服务器未返回安装包")
        if (body.contentLength() > info.fileSize) throw IOException("下载内容超过清单声明的大小")
        body.byteStream().use { input ->
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(64 * 1024)
                var downloaded = 0L
                onProgress(0)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    downloaded += count
                    if (downloaded > info.fileSize || downloaded > MAX_PACKAGE_SIZE) {
                        throw IOException("下载内容超过清单声明的大小")
                    }
                    output.write(buffer, 0, count)
                    onProgress((downloaded * 100L / info.fileSize).toInt().coerceIn(0, 100))
                }
                output.fd.sync()
            }
        }
    }

    internal fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hex = StringBuilder(64)
        for (byte in digest.digest()) {
            val value = byte.toInt() and 0xff
            hex.append("0123456789abcdef"[value ushr 4])
            hex.append("0123456789abcdef"[value and 15])
        }
        return hex.toString()
    }

    @TargetApi(31)
    private fun configureUserAction31(params: PackageInstaller.SessionParams, silent: Boolean) {
        params.setRequireUserAction(
            if (silent) {
                PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
            } else {
                PackageInstaller.SessionParams.USER_ACTION_REQUIRED
            },
        )
    }

    fun abandonMySessions(context: Context) {
        runCatching {
            val installer = context.packageManager.packageInstaller
            for (session in installer.mySessions) {
                runCatching { installer.abandonSession(session.sessionId) }
            }
        }
    }

    fun launchNormalPackageInstall(context: Context, apk: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.downloads", apk)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        view.clipData = ClipData.newRawUri("CodexRM-update", uri)
        grantPackageUri(context, view, uri)
        if (runCatching { context.startActivity(view) }.isSuccess) return true
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(view) }.isSuccess
    }

    @Suppress("DEPRECATION")
    private fun grantPackageUri(context: Context, intent: Intent, uri: Uri) {
        val matches = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        for (match in matches) {
            val packageName = match.activityInfo?.packageName ?: continue
            runCatching { context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    @Suppress("DEPRECATION")
    fun install(context: Context, apk: File, expectedVersionCode: Long, requestId: String, silent: Boolean) {
        val archive = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(0))
        } else {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        }
        require(archive != null && archive.packageName == context.packageName) { "安装包不是有效的 CodexRM 更新" }
        require(archive.longVersionCode == expectedVersionCode) { "安装包版本与更新清单不一致" }
        val installer = context.packageManager.packageInstaller
        val useSilent = silent && shouldUseSilentInstallFlag(Build.VERSION.SDK_INT)
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Android 12+ can update this app without another confirmation. Keep the
            // calling activity alive; the system kills the process when it applies the
            // package. Devices that reject silent install get the normal confirmation UI.
            if (Build.VERSION.SDK_INT >= 31) configureUserAction31(this, useSilent)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite("CodexRM-update.apk", 0L, apk.length()).use { output ->
                apk.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }
            val statusIntent = Intent(context, UpdateInstallReceiver::class.java)
                .setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS)
                .putExtra(UpdateInstallReceiver.EXTRA_REQUEST_ID, requestId)
            // PackageInstaller fills in status and user-action extras, so it rejects an immutable callback.
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                statusIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0,
            )
            session.commit(pendingIntent.intentSender)
        } catch (error: Exception) {
            session.abandon()
            throw error
        } finally {
            session.close()
        }
    }

}
