package com.codexrm.mobile

import android.content.Context
import java.io.IOException

/** Reads runtime release metadata; download, validation and installation are shared with CodexRM. */
internal object LocalCodexDownload {
    fun release(context: Context): UpdateChecker.VersionInfo {
        val text = try {
            val encoded = BuildConfig.LOCAL_RUNTIME_RELEASE
            if (encoded.isEmpty()) context.assets.open("android-codex-runtime.json").bufferedReader().use { it.readText() }
            else android.util.Base64.decode(encoded, android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
        } catch (_: Exception) { throw IOException("本机运行扩展尚未配置下载地址。") }
        // Runtime manifests always contain an absolute Cloudreve HTTPS URL.
        val url = org.json.JSONObject(text).string("downloadUrl")
        require(url.startsWith("https://", ignoreCase = true)) { "本机运行扩展未配置有效的 HTTPS 下载地址" }
        return UpdateChecker.parseManifest(text, url, UpdateComponent.RUNTIME)
    }

    fun updateAvailable(context: Context): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getPackageInfo(LocalCodex.PACKAGE, 0).longVersionCode
        LocalCodex.available(context) && installed < release(context).versionCode
    }.getOrDefault(false)
}
