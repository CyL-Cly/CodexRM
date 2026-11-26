package com.codexrm.mobile

import android.app.Instrumentation
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object InitialSyncCheck {
    fun run(test: Instrumentation, device: Device, expectLan: Boolean): String {
        val done = CountDownLatch(1)
        lateinit var client: CodexClient
        var started = false
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
                    val onlineAt = SystemClock.elapsedRealtime()
                    client.awaitInitialSync {
                        delay = SystemClock.elapsedRealtime() - onlineAt
                        status = client.directStatus
                        client.request("codexrm/info", timeoutMs = 5_000) { result, error ->
                            if (error != null || result?.optBoolean("mock") != true) {
                                failure = error ?: IllegalStateException("Expected a mock Agent for startup QA")
                                done.countDown()
                            } else {
                                threadStatus = client.directStatus
                                client.request("thread/list", JSONObject().put("limit", 10), timeoutMs = 5_000) { list, problem ->
                                    threads = list
                                    failure = problem
                                    done.countDown()
                                }
                            }
                        }
                    }
                }
                override fun onRpcEvent(message: JSONObject) = Unit
                override fun onFailure(message: String) = Unit
                override fun onAuthorizationExpired() = Unit
                override fun onDirectFallback() = Unit
            })
            client.start()
        }
        try {
            check(done.await(15, TimeUnit.SECONDS)) { "No initial sync response" }
            failure?.let { throw it }
            checkNotNull(threads?.optJSONArray("data")) { "Missing first conversation list" }
            if (expectLan) {
                check((status as? DirectStatus.Connected)?.lan == true) { "First RPC was not LAN direct: $status after ${delay}ms" }
            } else {
                check(status !is DirectStatus.Connected && delay in 2_500L..3_500L) { "Expected bounded relay fallback: $status after ${delay}ms" }
            }
            check(threadStatus == status) { "Transport changed before requesting the first conversation list: $threadStatus" }
            return "First device info and conversation list: ${if (expectLan) "LAN direct" else "RM relay"}; sync started after ${delay}ms"
        } finally {
            test.runOnMainSync { client.stop() }
        }
    }
}
