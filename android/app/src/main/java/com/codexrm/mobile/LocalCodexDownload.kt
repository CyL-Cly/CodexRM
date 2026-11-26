package com.codexrm.mobile

import android.content.Context
import android.content.pm.PackageManager
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal object LocalCodexDownload {
    private const val APK = "CodexRM-Local-Runtime.apk"
    private val http by lazy {
        Network.client.newBuilder().followSslRedirects(false).callTimeout(12, TimeUnit.MINUTES).build()
    }

    @Synchronized
    fun download(context: Context, progress: (Int) -> Unit): File {
        val release = try {
            context.assets.open("android-codex-runtime.json").bufferedReader().use { JSONObject(it.readText()) }
        } catch (_: Exception) { throw IOException("本机运行扩展尚未配置下载地址。") }
        val url = release.string("downloadUrl").toHttpUrlOrNull()
        if (url == null || !url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty())
            throw IOException("本机运行扩展的下载地址不是有效的 HTTPS 地址。")
        val size = release.optLong("fileSize")
        val expected = release.string("sha256")
        val versionCode = release.optLong("versionCode")
        if (size !in 1..300_000_000 || versionCode <= 0 || !expected.matches(Regex("[a-fA-F0-9]{64}")))
            throw IOException("本机运行扩展的下载清单无效。")
        val dir = File(context.cacheDir, "updates/local-codex").apply { mkdirs() }
        val apk = File(dir, APK)
        if (apk.isFile && apk.length() == size && apk.inputStream().use { digest(it) }.equals(expected, ignoreCase = true)) {
            verify(context, apk, versionCode)
            return apk
        }
        val partial = File(dir, "$APK.part")
        try {
            val md = MessageDigest.getInstance("SHA-256")
            var count = 0L
            var lastPercent = -1
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful || !response.request.url.isHttps) throw IOException("运行扩展下载失败（HTTP ${response.code}）。")
                val body = response.body ?: throw IOException("运行扩展下载内容为空。")
                if (body.contentLength() >= 0 && body.contentLength() != size) throw IOException("运行扩展大小与清单不一致。")
                body.byteStream().use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        if (count > size) throw IOException("运行扩展大小超出清单。")
                        md.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                        val percent = (100 * count / size).toInt()
                        if (percent != lastPercent) { lastPercent = percent; progress(percent) }
                    }
                } }
            }
            if (count != size || !md.digest().hex().equals(expected, ignoreCase = true)) throw IOException("运行扩展校验失败。")
            if (apk.exists() && !apk.delete() || !partial.renameTo(apk)) throw IOException("无法保存运行扩展安装包。")
            try { verify(context, apk, versionCode) } catch (e: Exception) { apk.delete(); throw e }
            return apk
        } finally { partial.delete() }
    }

    @Suppress("DEPRECATION")
    private fun verify(context: Context, file: File, version: Long) {
        val pm = context.packageManager
        val own = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
        val apk = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        val a = own?.apkContentsSigners?.toSet().orEmpty()
        val b = apk?.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        if (apk == null || apk.packageName != LocalCodex.PACKAGE || apk.longVersionCode != version || a.isEmpty() || a != b)
            throw IOException("运行扩展的包名、版本或签名验证失败。")
    }

    private fun digest(input: java.io.InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            md.update(buffer, 0, count)
        }
        return md.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
