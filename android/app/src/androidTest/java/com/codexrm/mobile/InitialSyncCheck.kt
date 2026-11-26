package com.codexrm.mobile

import android.app.Instrumentation
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object InitialSyncCheck {
    fun run(test: Instrumentation, device: Device, expectLan: Boolean): String {
        val done = CountDownLatch(2)
        lateinit var client: CodexClient
        var started = false
        var startedAt = 0L
        var delay = 0L
        var status: DirectStatus? = null
        var threads: JSONObject? = null
        var threadStatus: DirectStatus? = null
        var failure: Exception? = null
        test.runOnMainSync {
            client = CodexClient(test.targetContext, device, object : CodexClient.Listener {
                override fun onState(state: ConnectionState, agent: JSONObject?) {
                    if (state != ConnectionState.ONLINE || started) return
                    started = true
                    delay = SystemClock.elapsedRealtime() - startedAt
                    status = client.directStatus
                    client.request("codexrm/info", timeoutMs = 5_000) { result, error ->
                        if (error != null || result?.optBoolean("mock") != true) {
                            failure = error ?: IllegalStateException("Expected a mock Agent for startup QA")
                        }
                        done.countDown()
                    }
                    threadStatus = client.directStatus
                    client.request("thread/list", JSONObject().put("limit", 10), timeoutMs = 5_000) { list, problem ->
                        threads = list
                        failure = failure ?: problem
                        done.countDown()
                    }
                }
                override fun onRpcEvent(message: JSONObject) = Unit
                override fun onFailure(message: String) = Unit
                override fun onAuthorizationExpired() = Unit
                override fun onDirectFallback() = Unit
            })
            startedAt = SystemClock.elapsedRealtime()
            client.start()
        }
        try {
            check(done.await(15, TimeUnit.SECONDS)) { "No initial sync response" }
            failure?.let { throw it }
            checkNotNull(threads?.optJSONArray("data")) { "Missing first conversation list" }
            if (expectLan) {
                check((status as? DirectStatus.Connected)?.lan == true) { "First RPC was not LAN direct: $status after ${delay}ms" }
            } else {
                check(status !is DirectStatus.Connected && delay < 1_500L) { "Expected immediate relay sync: $status after ${delay}ms" }
            }
            check(threadStatus == status) { "Transport changed before requesting the first conversation list: $threadStatus" }
            return "First device info and conversation list: ${if (expectLan) "LAN direct" else "RM relay"}; initial channel ready after ${delay}ms"
        } finally {
            test.runOnMainSync { client.stop() }
        }
    }
}
