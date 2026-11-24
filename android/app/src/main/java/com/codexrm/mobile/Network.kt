package com.codexrm.mobile

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class ApiException(message: String, val status: Int = 0) : IOException(message)
internal fun interface HttpCallback { fun onComplete(result: JSONObject?, error: ApiException?) }

internal object Network {
    private val main = Handler(Looper.getMainLooper())
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val webSocketClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    fun request(
        serverUrl: String,
        path: String,
        token: String = "",
        body: JSONObject? = null,
        httpClient: OkHttpClient = client,
        callback: HttpCallback,
    ): Call {
        val builder = Request.Builder()
            .url(serverUrl.trimEnd('/') + path)
            .header("Accept", "application/json")
        if (token.isNotEmpty()) builder.header("Authorization", "Bearer $token")
        if (body == null) builder.get() else builder.post(body.toString().toRequestBody(jsonType))
        val call = httpClient.newCall(builder.build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { callback.onComplete(null, ApiException(e.message ?: "网络连接失败")) }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        val text = readLimited(it.body)
                        val json = if (text.isEmpty()) JSONObject() else JSONObject(text)
                        if (!it.isSuccessful) {
                            throw ApiException(json.string("error").ifEmpty { "HTTP ${it.code}" }, it.code)
                        }
                        json
                    }
                }
                main.post {
                    result.fold(
                        onSuccess = { callback.onComplete(it, null) },
                        onFailure = {
                            val error = it as? ApiException
                                ?: ApiException(it.message ?: "服务器响应无效")
                            callback.onComplete(null, error)
                        },
                    )
                }
            }
        })
        return call
    }

    private fun readLimited(body: ResponseBody?): String {
        if (body == null) return ""
        body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 1024 * 1024) throw ApiException("服务器响应超过 1 MiB")
                output.write(buffer, 0, count)
            }
            return output.toString("UTF-8")
        }
    }
}
