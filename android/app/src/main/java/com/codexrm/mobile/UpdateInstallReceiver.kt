package com.codexrm.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

internal class UpdateInstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirmation != null) {
                    runCatching { context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { reportFailure(context, intent, "无法打开系统安装确认界面") }
                } else {
                    reportFailure(context, intent, "系统安装器未提供确认界面")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                toast(context, "CodexRM 更新安装完成")
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    runCatching { context.startActivity(launch) }
                }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> reportFailure(context, intent, "已取消安装更新")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf { it.isNotBlank() } ?: "未知错误"
                reportFailure(context, intent, "更新安装失败：$message")
            }
        }
    }

    private fun reportFailure(context: Context, status: Intent, message: String) {
        val requestId = status.getStringExtra(EXTRA_REQUEST_ID)
        if (requestId != null) {
            val failure = Intent(ACTION_INSTALL_FAILED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_REQUEST_ID, requestId)
            context.sendBroadcast(failure)
        }
        toast(context, message)
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.codexrm.mobile.action.UPDATE_INSTALL_STATUS"
        const val ACTION_INSTALL_FAILED = "com.codexrm.mobile.action.UPDATE_INSTALL_FAILED"
        const val EXTRA_REQUEST_ID = "com.codexrm.mobile.extra.UPDATE_REQUEST_ID"
    }
}
