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
        val component = UpdateComponent.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_COMPONENT) } ?: UpdateComponent.APP // Older CodexRM sessions had no component extra.
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                dispatch(
                    context,
                    intent,
                    ACTION_INSTALL_INTERACTIVE,
                    status,
                    parcelableIntent(intent, Intent.EXTRA_INTENT),
                    null,
                )
            }
            PackageInstaller.STATUS_SUCCESS -> {
                dispatch(context, intent, ACTION_INSTALL_FINISHED, status, null, null)
                toast(context, "${component.label}安装完成")
                if (component != UpdateComponent.APP) return
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    runCatching { context.startActivity(launch) }
                }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> dispatch(
                context,
                intent,
                ACTION_INSTALL_FAILED,
                status,
                null,
                "已取消安装更新",
            )
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf { it.isNotBlank() } ?: "未知错误"
                dispatch(
                    context,
                    intent,
                    ACTION_INSTALL_FAILED,
                    status,
                    null,
                    "更新安装失败：$detail",
                )
            }
        }
    }

    private fun dispatch(
        context: Context,
        status: Intent,
        action: String,
        statusCode: Int,
        confirmation: Intent?,
        message: String?,
    ) {
        val requestId = status.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val outgoing = Intent(action)
            .setPackage(context.packageName)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra(EXTRA_OWN_SESSION_ID, status.getIntExtra(EXTRA_OWN_SESSION_ID, -1))
            .putExtra(EXTRA_STATUS, statusCode)
        if (confirmation != null) outgoing.putExtra(EXTRA_CONFIRM_INTENT, confirmation)
        if (message != null) outgoing.putExtra(EXTRA_MESSAGE, message)
        context.sendBroadcast(outgoing)
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.codexrm.mobile.action.UPDATE_INSTALL_STATUS"
        const val ACTION_INSTALL_FAILED = "com.codexrm.mobile.action.UPDATE_INSTALL_FAILED"
        const val ACTION_INSTALL_INTERACTIVE = "com.codexrm.mobile.action.UPDATE_INSTALL_INTERACTIVE"
        const val ACTION_INSTALL_FINISHED = "com.codexrm.mobile.action.UPDATE_INSTALL_FINISHED"
        const val EXTRA_REQUEST_ID = "com.codexrm.mobile.extra.UPDATE_REQUEST_ID"
        const val EXTRA_COMPONENT = "com.codexrm.mobile.extra.UPDATE_COMPONENT"
        const val EXTRA_OWN_SESSION_ID = "com.codexrm.mobile.extra.UPDATE_OWN_SESSION_ID"
        const val EXTRA_STATUS = "com.codexrm.mobile.extra.UPDATE_INSTALL_STATUS_CODE"
        const val EXTRA_MESSAGE = "com.codexrm.mobile.extra.UPDATE_INSTALL_MESSAGE"
        const val EXTRA_CONFIRM_INTENT = "com.codexrm.mobile.extra.UPDATE_INSTALL_CONFIRM"

        internal fun interactiveConfirmation(intent: Intent): Intent? =
            parcelableIntent(intent, EXTRA_CONFIRM_INTENT)
    }
}

@Suppress("DEPRECATION")
private fun parcelableIntent(intent: Intent, name: String): Intent? =
    if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(name, Intent::class.java)
    } else {
        intent.getParcelableExtra(name)
    }
