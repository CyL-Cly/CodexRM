package com.codexrm.mobile

/** One-shot grace period for startup RPCs. All calls run on the main thread. */
internal class InitialSyncGate(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
) {
    private var pending: Runnable? = null
    val waiting: Boolean get() = pending != null

    fun await(canTryLan: Boolean, sync: () -> Unit) {
        if (waiting) return
        if (!canTryLan) {
            sync()
            return
        }
        val task = object : Runnable {
            override fun run() {
                if (pending !== this) return
                cancel()
                sync()
            }
        }
        pending = task
        postDelayed(task, 2_500L)
    }

    fun release() { pending?.run() }

    fun cancel() {
        val task = pending ?: return
        pending = null
        remove(task)
    }
}
