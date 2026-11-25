package com.codexrm.mobile

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
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
            check(targetContext.packageName.endsWith(".p2pqa") || targetContext.packageName.endsWith(".filetransferqa") ||
                targetContext.packageName.endsWith(".securerelayqa"))
            if (args.getString("secureRelay") == "true") {
                result.putString("stream", "\n" + SecureRelayCheck.run(this, args) + "\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (args.getString("conversation") == "true") {
                result.putString("stream", "\n" + ConversationFeaturesCheck.run(this, args) + "\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            val transferMode = args.getString("phoneTransfer")
            if (transferMode == "relay" || transferMode == "direct" || transferMode == "cancel") {
                val payload = args.getString("payload") ?: "fixture-from-Android"
                result.putString("stream", "\n" + PhoneTransferCheck.run(this, transferMode, payload) + "\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            check(NativeP2P.available)
            check(!targetContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                "Unlock the device normally before running this test"
            }
            val cm = targetContext.getSystemService(ConnectivityManager::class.java)
            val verifyImageReference = args.getString("imageReference") == "true"
            val verifyFiles = args.getString("files") == "true"
            val verifyLan = args.getString("lan") == "true"
            fun onExpectedNetwork(): Boolean {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
                val transport = if (verifyLan) NetworkCapabilities.TRANSPORT_WIFI else NetworkCapabilities.TRANSPORT_CELLULAR
                return caps.hasTransport(transport) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
            check(transferMode == "seed" || verifyFiles || verifyImageReference || onExpectedNetwork()) { "Default network does not match the test" }
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
            if (transferMode == "seed") {
                DeviceStore(targetContext).apply { save(listOf(device)); selectedId = device.agentId; legacySystemWarningShown = true }
                for (pref in listOf("secure_devices", "ui_state")) {
                    check(targetContext.getSharedPreferences(pref, 0).edit().putBoolean("file_transfer_qa_seed", true).commit())
                }
                result.putString("stream", "\nclientId=${device.clientId}\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            args.getString("shareSpeed")?.let { mode ->
                val mib = (args.getString("mib")?.toIntOrNull() ?: 128).coerceIn(0, 512)
                val screen = startActivitySync(Intent().setClassName(targetContext.packageName,
                    "com.codexrm.mobile.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                activity = screen
                val summary = DirectShareSpeedCheck.run(this, device, mode, mib)
                runOnMainSync { screen.finish() }
                result.putString("stream", "\n$summary\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (args.getString("latency") == "true") {
                check(verifyLan)
                val screen = startActivitySync(Intent().setClassName(targetContext.packageName,
                    "com.codexrm.mobile.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                activity = screen
                val samples = DirectLatencyCheck.run(this, device, rounds,
                    args.getString("gapMs")?.toLongOrNull()?.coerceIn(0, 10_000) ?: 3_000,
                    args.getString("latencyInfo") == "true")
                val summary = "lan=true " + latencySummary(samples)
                Log.i("CodexRM-P2P-QA", summary)
                check(onExpectedNetwork()) { "Default network changed during latency test" }
                args.getString("maxMs")?.toLongOrNull()?.let { limit ->
                    check(samples.all { it <= limit }) { "Latency exceeded ${limit}ms: $summary" }
                }
                runOnMainSync { screen.finish() }
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\n$summary\n") })
                return
            }
            val natMode = args.getString("nat")
            val verifyIndicator = args.getString("indicator") == "true"
            // RPC-only checks must not wait for Android to foreground an Activity.
            if (natMode != null || verifyIndicator || verifyFiles || verifyImageReference) {
                DeviceStore(targetContext).apply {
                    save(listOf(device))
                    selectedId = device.agentId
                }
                val screen = startActivitySync(Intent().setClassName(targetContext.packageName,
                    "com.codexrm.mobile.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                activity = screen
                if (verifyImageReference) {
                    val summary = DirectFilesCheck.verifyImageReference(this, screen)
                    runOnMainSync { screen.finish() }
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\n$summary\n") })
                    return
                }
                if (verifyFiles) {
                    result.putString("stream", "\n" + DirectFilesCheck.verify(this, screen, device,
                        args.getString("customDownloads") == "true", args.getString("batchCopy") == "true", args.getString("otherDriveFile")) + "\n")
                    runOnMainSync { screen.finish() }
                    finish(Activity.RESULT_OK, result)
                    return
                }
                if (natMode != null) {
                    val detail = NatDiagnosticCheck.run(this, screen, natMode, args.getString("natRetry") == "true")
                    check(onExpectedNetwork()) { "Default network changed during NAT diagnosis" }
                    runOnMainSync { screen.finish() }
                    finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\n$detail\n") })
                    return
                }
                if (verifyIndicator) {
                    uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
                        flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    }
                    val count = (args.getString("indicatorRounds")?.toIntOrNull() ?: 1).coerceIn(1, 100)
                    val gap = (args.getString("gapMs")?.toLongOrNull() ?: 2_600).coerceIn(0, 10_000)
                    val details = List(count) { index ->
                        if (index > 0) {
                            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                            SystemClock.sleep(gap)
                        }
                        checkIndicator(screen, expectedDirect, verifyLan)
                    }
                    val samples = details.mapNotNull { indicatorLatency.find(it)?.groupValues?.get(1)?.toLong() }
                    val summary = if (expectedDirect) {
                        "connectionIndicator=${details.last()}\n" + latencySummary(samples)
                    } else "connectionIndicator=${details.last()}"
                    Log.i("CodexRM-P2P-QA", summary)
                    args.getString("maxMs")?.toLongOrNull()?.let { limit ->
                        check(samples.all { it <= limit }) { "Button latency exceeded ${limit}ms: $summary" }
                    }
                    result.putString("stream", "\n$summary\n")
                    runOnMainSync { screen.finish() }
                    finish(Activity.RESULT_OK, result)
                    return
                }
            }
            val reportProgress = args.getString("progress") == "true"
            fun progress(message: String) {
                if (reportProgress) sendStatus(0, Bundle().apply { putString("stream", "\n$message\n") })
            }
            val durations = mutableListOf<Long>()
            var relay = 0
            for (round in 1..rounds) {
                check(onExpectedNetwork()) { "Default network changed" }
                progress("round=$round start")
                val relayDone = CountDownLatch(1)
                val directDone = CountDownLatch(1)
                val directElapsed = AtomicReference<Long?>()
                lateinit var client: CodexClient
                var relayStarted = false
                val directStatus = AtomicReference<String?>()
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
                        override fun onDirectStatusChanged() {
                            directStatus.set(when (val status = client.directStatus) {
                                is DirectStatus.Connected -> if (status.lan) "lan" else "public"
                                is DirectStatus.Negotiating -> "negotiating"
                                is DirectStatus.Relay -> status.reason
                            })
                        }
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
                    val summary = "round=$round relay=${relayDone.count == 0L} directMs=${directElapsed.get()}"
                    Log.i("CodexRM-P2P-QA", summary)
                    progress("$summary status=${directStatus.get()}")
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
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            Log.e("CodexRM-P2P-QA", "Instrumentation failed", failure)
            activity?.let { screen -> runOnMainSync { screen.finish() } }
            result.putString("error", failure.toString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private val indicatorLatency = Regex("往返延迟：([1-9][0-9]*) ms")

    private fun latencySummary(samples: List<Long>): String {
        val p95 = samples.sorted()[(samples.size * 95 + 99) / 100 - 1]
        return "samplesMs=${samples.joinToString(",")} p95Ms=$p95 maxMs=${samples.max()}"
    }

    private fun nodeTexts(node: AccessibilityNodeInfo): Sequence<String> = sequence {
        node.text?.toString()?.let { yield(it) }
        node.contentDescription?.toString()?.let { yield(it) }
        for (i in 0 until node.childCount) node.getChild(i)?.let { yieldAll(nodeTexts(it)) }
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
            val roots = listOfNotNull(uiAutomation.rootInActiveWindow) + uiAutomation.windows.mapNotNull { it.root }
            val detail = roots.asSequence().flatMap(::nodeTexts).firstOrNull {
                it.contains(text) && (!direct || indicatorLatency.containsMatchIn(it))
            }
            if (detail != null) return detail
            SystemClock.sleep(100)
        }
        error("Connection detail dialog did not show the expected result")
    }
}
