package com.codexrm.mobile

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class DeviceRevokeJob : JobService() {
    private val store by lazy { DeviceStore(this) }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private var active: JobParameters? = null
    private var call: Call? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (store.pendingRevocations().isEmpty()) return false
        active = params
        draining = true
        val client = params.network?.let { network ->
            Network.client.newBuilder()
                .socketFactory(network.socketFactory)
                .dns(object : Dns {
                    override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
                })
                .callTimeout(15, TimeUnit.SECONDS)
                .build()
        }
        handler.post { if (active === params) reportNext(params, client) }
        return true
    }

    private fun reportNext(params: JobParameters, client: OkHttpClient?) {
        val pending = store.pendingRevocations().firstOrNull()
        if (pending == null || client == null || !stillConnected(params)) {
            finish(params, reschedule = pending != null)
            return
        }
        val done: (ApiException?) -> Unit = done@{ error ->
            if (active !== params) return@done
            call = null
            when (revocationOutcome(error?.status, stillConnected = { stillConnected(params) })) {
                RevocationOutcome.WAIT_FOR_NETWORK -> {
                    finish(params, reschedule = true)
                    return@done
                }
                RevocationOutcome.DONE -> store.finishRevocation(pending, failed = false)
                RevocationOutcome.FAILED -> {
                    store.finishRevocation(pending, failed = true)
                    onFailure?.invoke()
                }
            }
            handler.post { if (active === params) reportNext(params, client) }
        }
        try {
            call = Network.request(pending.serverUrl, "/api/v1/client/revoke", pending.token, JSONObject(), client) { _, error -> done(error) }
        } catch (_: IllegalArgumentException) {
            done(ApiException("解除授权请求无效"))
        }
    }

    private fun stillConnected(params: JobParameters): Boolean =
        params.network?.let { connectivity.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

    private fun finish(params: JobParameters, reschedule: Boolean) {
        active = null
        draining = false
        jobFinished(params, reschedule)
    }

    override fun onStopJob(params: JobParameters): Boolean {
        active = null
        draining = false
        call?.cancel()
        call = null
        return store.pendingRevocations().isNotEmpty()
    }

    companion object {
        private const val JOB_ID = 0x4358524D
        // Lifecycle and job callbacks are all on the main thread. Background failures are kept in DeviceStore.
        private var draining = false
        var onFailure: (() -> Unit)? = null

        fun schedule(context: Context): Boolean {
            if (draining) return true // The running job also reads new entries before it finishes.
            return runCatching {
                val scheduler = context.getSystemService(JobScheduler::class.java)
                val job = JobInfo.Builder(JOB_ID, ComponentName(context, DeviceRevokeJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setEstimatedNetworkBytes(1024, 1024)
                    .setPersisted(true)
                if (Build.VERSION.SDK_INT >= 31) {
                    val expedited = runCatching { scheduler.schedule(job.setExpedited(true).build()) }.getOrNull()
                    if (expedited == JobScheduler.RESULT_SUCCESS) return true
                    job.setExpedited(false)
                }
                scheduler.schedule(job.build()) == JobScheduler.RESULT_SUCCESS
            }.getOrDefault(false)
        }
    }
}
