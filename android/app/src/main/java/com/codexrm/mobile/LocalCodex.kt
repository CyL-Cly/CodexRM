package com.codexrm.mobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

internal object LocalCodex {
    const val AGENT = "codexrm:android-local"
    const val PACKAGE = "com.codexrm.runtime"
    const val DESCRIPTOR = "$PACKAGE.LocalCodex.v1"
    const val UNSUPPORTED = "本机 Codex 需要 Android 11 或更新版本和 ARM64 处理器。"
    const val PERMISSION = "请开启「Codex 本地运行扩展」的所有文件访问权限。"
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 30 && "arm64-v8a" in Build.SUPPORTED_ABIS

    fun available(context: Context): Boolean = context.packageManager.checkSignatures(context.packageName, PACKAGE) ==
        PackageManager.SIGNATURE_MATCH

    fun installed(context: Context): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) { false }

    fun permissionIntent() = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$PACKAGE"))
    fun serviceIntent() = Intent().setComponent(ComponentName(PACKAGE, "$PACKAGE.RuntimeService"))
}

private const val LOCAL_FOREGROUND_RENEW_MS = 8_000L

internal fun needsLocalForegroundRenewal(manufacturer: String, brand: String): Boolean =
    listOf(manufacturer, brand).any { value ->
        value.equals("oppo", ignoreCase = true) || value.equals("oneplus", ignoreCase = true) ||
            value.equals("realme", ignoreCase = true)
    }

/** Same Agent envelopes as the remote connection, isolated to two anonymous Binder-transferred pipes. */
internal class LocalCodexConnection(
    private val context: Context,
    private val onFrame: (String) -> Unit,
    private val onFailure: (String, Boolean) -> Unit,
) : ServiceConnection {
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean()
    private val queue = ArrayBlockingQueue<ByteArray>(20)
    private val streams = Any()
    private var bound = false
    private var foregroundStarted = false
    private var read: InputStream? = null
    private var write: OutputStream? = null
    private var writer: Thread? = null
    private val renewWhileWorking = needsLocalForegroundRenewal(Build.MANUFACTURER, Build.BRAND)
    private var foregroundRefreshedAt: Long? = null
    private var busy = false
    private val renewForeground = object : Runnable {
        override fun run() {
            if (!busy || closed.get()) return
            if (!refreshForeground()) return
            main.postDelayed(this, LOCAL_FOREGROUND_RENEW_MS)
        }
    }
    private val startupTimeout = Runnable { fail("启动本机 Codex 超时。") }

    fun start() {
        if (!LocalCodex.supported) { fail(LocalCodex.UNSUPPORTED); return }
        if (!LocalCodex.available(context)) {
            fail(if (LocalCodex.installed(context)) "本机运行扩展的签名与 CodexRM 不一致。" else "请先安装「Codex 本地运行扩展」。")
            return
        }
        foregroundStarted = refreshForeground()
        if (!foregroundStarted) { fail("Android 未允许启动本机运行扩展。"); return }
        // Bind through the visible Activity so the extension shares its foreground importance.
        val flags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT or Context.BIND_ADJUST_WITH_ACTIVITY
        bound = runCatching { context.bindService(LocalCodex.serviceIntent(), this, flags) }.getOrDefault(false)
        if (!bound) fail("无法连接本机运行扩展。") else main.postDelayed(startupTimeout, 30_000)
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        if (binder == null) { fail("无法连接本机运行扩展。"); return }
        Thread({
            try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(LocalCodex.DESCRIPTOR)
                    if (!binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)) throw IOException()
                    reply.readException()
                    when (reply.readInt()) {
                        0 -> {
                            val input = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.CREATOR.createFromParcel(reply))
                            val output = try {
                                ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.CREATOR.createFromParcel(reply))
                            } catch (e: Exception) { input.close(); throw e }
                            if (!attach(input, output)) return@Thread
                        }
                        2 -> { fail(LocalCodex.PERMISSION, true); return@Thread }
                        else -> { fail("无法启动本机 Codex，请检查运行扩展。"); return@Thread }
                    }
                } finally { data.recycle(); reply.recycle() }
                val input = synchronized(streams) { read } ?: return@Thread
                input.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (!closed.get()) {
                        val frame = reader.readLine() ?: break
                        main.post {
                            if (!closed.get()) {
                                main.removeCallbacks(startupTimeout)
                                onFrame(frame)
                            }
                        }
                    }
                }
                fail("本机 Codex 已退出。")
            } catch (_: Exception) { fail("本机运行扩展连接中断。") }
        }, "codex-local-reader").start()
    }

    private fun attach(input: InputStream, output: OutputStream): Boolean = synchronized(streams) {
        if (closed.get()) { input.close(); output.close(); return@synchronized false }
        read = input
        write = output
        writer = Thread({
            try {
                while (!closed.get()) {
                    output.write(queue.take())
                    output.flush()
                }
            } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            catch (_: IOException) { fail("本机 Codex 消息发送失败。") }
        }, "codex-local-writer").apply { start() }
        true
    }

    // ColorOS can freeze the separate package even while the Activity is visible and
    // the service is foreground. Renew the already-running service before sending
    // and only while work remains. This wakes the existing pipes, not a new session.
    private fun refreshForeground(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (foregroundRefreshedAt?.let { now - it < LOCAL_FOREGROUND_RENEW_MS } == true) return true
        val started = runCatching { context.startForegroundService(LocalCodex.serviceIntent()) != null }.getOrDefault(false)
        if (started) foregroundRefreshedAt = now
        return started
    }

    fun setBusy(value: Boolean) {
        if (!renewWhileWorking || closed.get() || busy == value) return
        busy = value
        main.removeCallbacks(renewForeground)
        if (busy) main.postDelayed(renewForeground, LOCAL_FOREGROUND_RENEW_MS)
    }

    fun send(frame: String): Boolean = !closed.get() && (!renewWhileWorking || refreshForeground()) &&
        queue.offer((frame + "\n").toByteArray(Charsets.UTF_8))
    private fun fail(message: String, permission: Boolean = false) {
        main.post {
            if (!closed.get()) {
                close()
                onFailure(message, permission)
            }
        }
    }
    override fun onServiceDisconnected(name: ComponentName?) = fail("本机 Codex 已断开。")
    override fun onBindingDied(name: ComponentName?) = fail("本机运行扩展已更新，请重新连接。")
    override fun onNullBinding(name: ComponentName?) = fail("运行扩展拒绝了连接。")

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        main.removeCallbacks(startupTimeout)
        main.removeCallbacks(renewForeground)
        if (bound) { runCatching { context.unbindService(this) }; bound = false }
        if (foregroundStarted) { runCatching { context.stopService(LocalCodex.serviceIntent()) }; foregroundStarted = false }
        synchronized(streams) {
            writer?.interrupt()
            runCatching { write?.close() }
            runCatching { read?.close() }
            read = null; write = null; writer = null
        }
        queue.clear()
    }
}
