package com.codexrm.mobile

import android.app.Instrumentation
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object DirectLatencyCheck {
    fun run(test: Instrumentation, device: Device, rounds: Int, gapMs: Long, info: Boolean): List<Long> {
        lateinit var client: CodexClient
        val connected = CountDownLatch(1)
        test.runOnMainSync {
            client = CodexClient(test.targetContext, device, object : CodexClient.Listener {
                override fun onState(state: ConnectionState, agent: JSONObject?) = Unit
                override fun onRpcEvent(message: JSONObject) = Unit
                override fun onFailure(message: String) = Unit
                override fun onAuthorizationExpired() = Unit
                override fun onDirectFallback() = Unit
                override fun onDirectStatusChanged() {
                    if ((client.directStatus as? DirectStatus.Connected)?.lan == true) connected.countDown()
                }
            })
            client.start()
        }
        try {
            check(connected.await(20, TimeUnit.SECONDS)) { "LAN direct did not connect" }
            return List(rounds) {
                SystemClock.sleep(gapMs)
                val done = CountDownLatch(1)
                var result: Long? = null
                var error: Exception? = null
                test.runOnMainSync {
                    check((client.directStatus as? DirectStatus.Connected)?.lan == true) { "LAN direct disconnected" }
                    val measured: (Long?, Exception?) -> Unit = { latency, failure ->
                        result = latency
                        error = failure
                        done.countDown()
                    }
                    if (info) {
                        val started = SystemClock.elapsedRealtimeNanos()
                        client.request("codexrm/info", timeoutMs = 5_000) { _, failure ->
                            measured(((SystemClock.elapsedRealtimeNanos() - started + 500_000) / 1_000_000).coerceAtLeast(1), failure)
                        }
                    } else client.measureDirectLatency(measured)
                }
                check(done.await(6, TimeUnit.SECONDS)) { "LAN measurement did not finish" }
                error?.let { throw it }
                checkNotNull(result)
            }
        } finally {
            test.runOnMainSync { client.stop() }
        }
    }
}
