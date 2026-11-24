package com.codexrm.mobile

import android.app.Instrumentation
import android.net.Uri
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object DirectShareSpeedCheck {
    fun run(test: Instrumentation, device: Device, mode: String, mib: Int): String {
        val bytes = mib.toLong() shl 20
        val file = File.createTempFile("share-speed-", ".bin", test.targetContext.cacheDir)
        val digest = MessageDigest.getInstance("SHA-256")
        val random = Random(42)
        DigestOutputStream(file.outputStream().buffered(), digest).use { output ->
            val buffer = ByteArray(1 shl 20)
            repeat(mib) { random.nextBytes(buffer); output.write(buffer) }
        }
        val expected = digest.digest().joinToString("") { "%02x".format(it) }
        val done = CountDownLatch(1)
        var failure: Exception? = null
        var received: JSONObject? = null
        var elapsed = 0L
        lateinit var client: CodexClient
        var started = false
        var directAtFinish = false
        val wantDirect = mode != "relay"
        try {
            test.runOnMainSync {
                client = CodexClient(test.targetContext, device, object : CodexClient.Listener {
                    override fun onState(state: ConnectionState, agent: JSONObject?) {
                        if ((!wantDirect || mode == "staged") && state == ConnectionState.ONLINE) transfer()
                    }
                    override fun onRpcEvent(message: JSONObject) = Unit
                    override fun onFailure(message: String) = Unit
                    override fun onAuthorizationExpired() = Unit
                    override fun onDirectFallback() = Unit
                    override fun onDirectStatusChanged() {
                        if (wantDirect && client.usingDirect) transfer()
                    }
                    private fun transfer() {
                        if (started) return
                        started = true
                        val params = JSONObject().put("name", "codexrm-share-speed-$mode.bin").put("size", bytes)
                            .put("destination", "downloads").put("sender", "CodexRM Share QA")
                        val start = SystemClock.elapsedRealtimeNanos()
                        val complete = RpcCallback { result, error ->
                            elapsed = SystemClock.elapsedRealtimeNanos() - start
                            directAtFinish = client.usingDirect
                            received = result
                            failure = error
                            done.countDown()
                        }
                        when (mode) {
                            "legacy" -> client.uploadAttachment(params, file::inputStream, {}, complete = complete)
                            "staged" -> DesktopShare(test.targetContext, client).send(SharedContent("", listOf(Uri.fromFile(file))), {}) { count, error ->
                                complete.onComplete(JSONObject().put("name", file.name).put("size", if (count == 1) bytes else -1), error)
                            }
                            else -> client.uploadDesktopFile(params, file, {}, complete)
                        }
                    }
                }, relayOnly = !wantDirect)
                client.start()
            }
            check(done.await(250, TimeUnit.SECONDS)) { "Timed out waiting for $mode transfer (started=$started)" }
            if (!wantDirect) {
                check(received == null && failure?.message?.contains("P2P") == true) { "Desktop share did not reject relay: $failure" }
                return "mode=$mode rejected=true"
            }
            failure?.let { throw it }
            val result = requireNotNull(received)
            check(result.getLong("size") == bytes)
            check(directAtFinish == wantDirect) { "Expected direct=$wantDirect but got $directAtFinish" }
            val rate = bytes / (elapsed / 1e9) / (1 shl 20)
            return "mode=$mode bytes=$bytes elapsedMs=${elapsed / 1_000_000} MiB/s=$rate sha256=$expected file=${result.getString("name")}"
        } finally {
            test.runOnMainSync { client.stop() }
            file.delete()
        }
    }
}
