package com.codexrm.mobile

import android.app.Activity
import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DirectInstrumentation : Instrumentation() {
    private lateinit var args: Bundle
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        try {
            check(targetContext.packageName.endsWith(".p2pqa"))
            check(NativeP2P.available)
            check(!targetContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                "Unlock the device normally before running this test"
            }
            val cm = targetContext.getSystemService(ConnectivityManager::class.java)
            val verifyFiles = args.getString("files") == "true"
            val verifyLan = args.getString("lan") == "true"
            fun onExpectedNetwork(): Boolean {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
                val transport = if (verifyLan) NetworkCapabilities.TRANSPORT_WIFI else NetworkCapabilities.TRANSPORT_CELLULAR
                return caps.hasTransport(transport) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
            check(verifyFiles || onExpectedNetwork()) { "Default network does not match the test" }
            val url = requireNotNull(args.getString("rm"))
            val code = requireNotNull(args.getString("pair"))
            val rounds = (args.getString("rounds")?.toIntOrNull() ?: 20).coerceIn(1, 200)
            val expectedDirect = args.getString("expect") != "relay"
            val request = Request.Builder().url(url.trimEnd('/') + "/api/v1/client/claim")
                .post(JSONObject().put("code", code).put("deviceType", "android")
                    .put("clientName", "P2P QA").toString().toRequestBody("application/json".toMediaType())).build()
            val claim = OkHttpClient().newCall(request).execute().use {
                check(it.isSuccessful) { "Pairing error: HTTP " + it.code }
                JSONObject(it.body?.string().orEmpty())
            }
            val agent = claim.getJSONObject("agent")
            val device = Device(claim.getString("clientId"), claim.getString("token"), url, agent.getString("agentId"), agent.optString("serverName", "P2P QA"))
            val verifyIndicator = args.getString("indicator") == "true"
            if (verifyIndicator || verifyFiles) {
                DeviceStore(targetContext).apply {
                    save(listOf(device))
                    selectedId = device.agentId
                }
            }
            val screen = startActivitySync(Intent().setClassName(targetContext.packageName,
                "com.codexrm.mobile.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = screen
            if (verifyFiles) {
                result.putString("stream", "\n" + DirectFilesCheck.verify(this, screen) + "\n")
                runOnMainSync { screen.finish() }
                finish(Activity.RESULT_OK, result)
                return
            }
            if (verifyIndicator) {
                val detail = checkIndicator(screen, expectedDirect, verifyLan)
                result.putString("stream", "\nconnectionIndicator=$detail\n")
                runOnMainSync { screen.finish() }
                finish(Activity.RESULT_OK, result)
                return
            }
            val durations = mutableListOf<Long>()
            var relay = 0
            for (round in 1..rounds) {
                check(onExpectedNetwork()) { "Default network changed" }
                val relayDone = CountDownLatch(1)
                val directDone = CountDownLatch(1)
                val directElapsed = AtomicReference<Long?>()
                lateinit var client: CodexClient
                var relayStarted = false
                val started = SystemClock.elapsedRealtime()
                runOnMainSync {
                    client = CodexClient(targetContext, device, object : CodexClient.Listener {
                        override fun onState(state: ConnectionState, agent: JSONObject?) {
                            if (state != ConnectionState.ONLINE || relayStarted) return
                            relayStarted = true
                            check(!client.usingDirect)
                            client.request("codexrm/info") { data, error ->
                                if (error == null && data?.has("version") == true) relayDone.countDown()
                            }
                        }
                        override fun onRpcEvent(message: JSONObject) = Unit
                        override fun onAuthorizationExpired() = Unit
                        override fun onDirectFallback() = Unit
                        override fun onFailure(message: String) = Unit
                    })
                    client.start()
                }
                try {
                    val deadline = started + if (expectedDirect) 20_000 else 4_000
                    var directStarted = false
                    while (SystemClock.elapsedRealtime() < deadline && directDone.count != 0L) {
                        runOnMainSync {
                            val path = client.directStatus as? DirectStatus.Connected
                            if (client.usingDirect && (!verifyLan || path?.lan == true) && !directStarted) {
                                directStarted = true
                                client.request("thread/list") { data, error ->
                                    val elapsed = SystemClock.elapsedRealtime() - started
                                    if (error == null && data?.optJSONArray("data") != null && elapsed <= 20_000) {
                                        directElapsed.set(elapsed)
                                        directDone.countDown()
                                    }
                                }
                            }
                        }
                        directDone.await(50, TimeUnit.MILLISECONDS)
                        if (!expectedDirect && relayDone.count == 0L) break
                    }
                    if (relayDone.count == 0L) relay++
                    directElapsed.get()?.let(durations::add)
                    Log.i("CodexRM-P2P-QA", "round=" + round + " relay=" + (relayDone.count == 0L) + " directMs=" + directElapsed.get())
                } finally {
                    runOnMainSync { client.stop() }
                }
            }
            result.putString("stream", "\nplanned=" + rounds + " relay=" + relay + " direct=" + durations.size + " directMs=" + durations.joinToString(",") + "\n")
            check(relay == rounds) { "Missing WSS RPCs" }
            check(when {
                !expectedDirect -> durations.isEmpty()
                verifyLan -> durations.size == rounds
                else -> durations.size * 2 > rounds
            }) { "Unexpected direct success rate" }
            runOnMainSync { screen.finish() }
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            activity?.let { screen -> runOnMainSync { screen.finish() } }
            result.putString("error", failure.toString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun checkIndicator(screen: Activity, direct: Boolean, lan: Boolean): String {
        val label = when {
            !direct -> "RM 中继，查看直连状态"
            lan -> "局域网直连，测试延迟"
            else -> "P2P 直连，测试延迟"
        }
        val deadline = SystemClock.elapsedRealtime() + 25_000
        var clicked = false
        while (!clicked && SystemClock.elapsedRealtime() < deadline) {
            runOnMainSync {
                val views = arrayListOf<View>()
                screen.window.decorView.findViewsWithText(views, label, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                clicked = views.firstOrNull { it.isShown && it.isClickable }?.performClick() == true
            }
            if (!clicked) SystemClock.sleep(100)
        }
        check(clicked) { "Connection indicator did not reach the expected path" }
        val text = if (direct) "往返延迟：" else "未收到 RM 的 P2P 能力通知"
        val dialogDeadline = SystemClock.elapsedRealtime() + 6_000
        while (SystemClock.elapsedRealtime() < dialogDeadline) {
            val root = uiAutomation.rootInActiveWindow
            val detail = root?.findAccessibilityNodeInfosByText(text)?.firstOrNull()?.text?.toString()
            if (detail != null && (!direct || Regex("往返延迟：[1-9][0-9]* ms").matches(detail))) return detail
            SystemClock.sleep(100)
        }
        error("Connection detail dialog did not show the expected result")
    }
}
