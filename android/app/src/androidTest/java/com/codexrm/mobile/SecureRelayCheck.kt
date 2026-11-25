package com.codexrm.mobile

import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object SecureRelayCheck {
    fun run(test: Instrumentation, args: Bundle): String {
        val invitation = SecureInvitation.parse(requireNotNull(args.getString("pair")))
        val server = requireNotNull(args.getString("rm"))
        val paired = AtomicReference<Device?>()
        val failure = AtomicReference<String?>()
        val pairDone = CountDownLatch(1)
        Network.request(server, "/api/v2/client/route", body = JSONObject()
            .put("route", invitation.route).put("deviceType", "android")) { result, error ->
            if (error != null || result == null) {
                failure.set(error?.message ?: "Missing pairing response")
                pairDone.countDown()
            } else {
                val routed = Device.fromClaim(server, result)
                verifySecurePairing(routed, invitation) { key, problem ->
                    if (key != null) paired.set(routed.copy(secureKey = key)) else failure.set(problem)
                    pairDone.countDown()
                }
            }
        }
        check(pairDone.await(20, TimeUnit.SECONDS)) { "Pairing timed out" }
        val device = checkNotNull(paired.get()) { failure.get() ?: "Pairing failed" }
        repeat(2) {
            val rpcDone = CountDownLatch(1)
            lateinit var client: CodexClient
            test.runOnMainSync {
                client = CodexClient(test.targetContext, device, object : CodexClient.Listener {
                    override fun onState(state: ConnectionState, agent: JSONObject?) {
                        if (state == ConnectionState.ONLINE) client.request("codexrm/info") { result, error ->
                            if (result?.optBoolean("mock") != true) failure.set(error?.message ?: "Unexpected RPC response")
                            rpcDone.countDown()
                        }
                    }
                    override fun onRpcEvent(message: JSONObject) = Unit
                    override fun onDirectFallback() = Unit
                    override fun onAuthorizationExpired() { failure.set("Authorization expired"); rpcDone.countDown() }
                    override fun onFailure(message: String) { failure.set(message); rpcDone.countDown() }
                })
                client.start()
            }
            try {
                check(rpcDone.await(15, TimeUnit.SECONDS)) { "Secure RPC timed out" }
                check(failure.get() == null) { failure.get().orEmpty() }
            } finally {
                test.runOnMainSync { client.stop() }
            }
        }
        return "secure-pair-http, secure-rpc, reconnect: OK"
    }
}
