package com.codexrm.mobile

/** Tracks only the current thread's detached subscription; stale events cannot revive it. */
internal class DetachedThreadState {
    var threadId: String = ""
        private set

    fun detach(currentThreadId: String, eventThreadId: String): Boolean {
        if (eventThreadId.isEmpty() || eventThreadId != currentThreadId) return false
        threadId = eventThreadId
        return true
    }

    fun loaded(eventThreadId: String): Boolean {
        if (eventThreadId.isEmpty() || eventThreadId != threadId) return false
        threadId = ""
        return true
    }

    fun isDetached(currentThreadId: String): Boolean =
        threadId.isNotEmpty() && threadId == currentThreadId

    fun reset() {
        threadId = ""
    }
}
