package com.codexrm.mobile

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

private val desktopUploadWorker = Executors.newSingleThreadExecutor()
internal const val DESKTOP_SHARE_DIRECT_REQUIRED = "发送到电脑需要 P2P 直连，请重新连接后重试"

internal fun CodexClient.uploadDesktopFile(metadata: JSONObject, file: File, progress: (Int) -> Unit, complete: RpcCallback) {
    withDesktopShareTransport { transport ->
        if (transport == null) complete.onComplete(null, IOException(DESKTOP_SHARE_DIRECT_REQUIRED))
        else uploadDesktopFile(transport, metadata, file, progress, complete)
    }
}

internal fun CodexClient.uploadDesktopFile(
    transport: DirectTransport, metadata: JSONObject, file: File, progress: (Int) -> Unit, complete: RpcCallback,
) {
    val size = metadata.optLong("size")
    val main = Handler(Looper.getMainLooper())
    val params = JSONObject(metadata.toString()).put("uploadStream", true)
    progress(0)
    requestDesktopShare(transport, "begin", params) { result, error ->
        if (error != null || result == null) {
            complete.onComplete(null, error ?: IOException("文件上传失败"))
            return@requestDesktopShare
        }
        val id = result.string("id")
        val reference = JSONObject().put("id", id)
        fun fail(failure: Exception) {
            requestDesktopShare(transport, "abort", reference) { _, _ -> }
            complete.onComplete(null, failure)
        }
        if (!result.optBoolean("uploadStream")) {
            uploadAttachment(metadata, file::inputStream, progress, begun = result, desktopTransport = transport, complete = complete)
            return@requestDesktopShare
        }
        val poll = object : Runnable {
            private var last = 0
            override fun run() {
                val percent = transport.uploadProgress()
                if (percent > last) {
                    last = percent
                    progress(percent)
                }
                main.postDelayed(this, 200)
            }
        }
        main.postDelayed(poll, 200)
        desktopUploadWorker.execute {
            val sent = runCatching { transport.upload(id, file, size) }.getOrDefault(false)
            main.post {
                main.removeCallbacks(poll)
                if (!sent) {
                    fail(IOException("直连文件上传失败"))
                    return@post
                }
                requestDesktopShare(transport, "finish", reference) { finished, failure ->
                    if (finished == null || failure != null) fail(failure ?: IOException("文件保存失败"))
                    else {
                        progress(100)
                        complete.onComplete(finished, null)
                    }
                }
            }
        }
    }
}
