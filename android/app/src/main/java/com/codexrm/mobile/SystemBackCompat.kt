package com.codexrm.mobile

import android.annotation.TargetApi
import android.app.Activity
import android.os.Build
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * Predictive-back helper that keeps the platform callback types out of
 * [MainActivity]'s own class loading and construction path.
 *
 * Referencing that type from a MainActivity field initializer crashes Android 12
 * (API 31/32) at startup even when guarded by `SDK_INT`: ART resolves the callback
 * lambda's interface while verifying the constructor, and the interface does not
 * exist below API 33. All public signatures here use platform-neutral types, and the
 * concrete callbacks live in SDK-gated classes.
 */
internal object SystemBackCompat {
    fun createCallback(
        onStarted: (Float) -> Unit,
        onProgressed: (Float) -> Unit,
        onCancelled: () -> Unit,
        onBack: () -> Unit,
    ): Any? = when {
        Build.VERSION.SDK_INT >= 34 -> Api34.createCallback(onStarted, onProgressed, onCancelled, onBack)
        Build.VERSION.SDK_INT >= 33 -> Api33.createCallback(onBack)
        else -> null
    }

    fun register(activity: Activity, callback: Any?) {
        if (Build.VERSION.SDK_INT >= 33 && callback != null) Api33.register(activity, callback)
    }

    fun unregister(activity: Activity, callback: Any?) {
        if (Build.VERSION.SDK_INT >= 33 && callback != null) Api33.unregister(activity, callback)
    }

    @TargetApi(33)
    private object Api33 {
        fun createCallback(onBack: () -> Unit): Any = OnBackInvokedCallback { onBack() }

        fun register(activity: Activity, callback: Any) {
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback as OnBackInvokedCallback,
            )
        }

        fun unregister(activity: Activity, callback: Any) {
            activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback as OnBackInvokedCallback)
        }
    }

    @TargetApi(34)
    private object Api34 {
        fun createCallback(
            onStarted: (Float) -> Unit,
            onProgressed: (Float) -> Unit,
            onCancelled: () -> Unit,
            onBack: () -> Unit,
        ): Any = object : OnBackAnimationCallback {
            override fun onBackStarted(backEvent: BackEvent) = onStarted(backEvent.progress)
            override fun onBackProgressed(backEvent: BackEvent) = onProgressed(backEvent.progress)
            override fun onBackCancelled() = onCancelled()
            override fun onBackInvoked() = onBack()
        }
    }
}
