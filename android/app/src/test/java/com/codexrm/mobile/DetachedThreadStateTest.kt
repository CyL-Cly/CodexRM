package com.codexrm.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetachedThreadStateTest {
    @Test fun detachedPollsOnlyTheCurrentThread() {
        val state = DetachedThreadState()
        assertFalse(state.detach("thread-a", "thread-b"))
        assertFalse(state.isDetached("thread-a"))
        assertTrue(state.detach("thread-a", "thread-a"))
        assertTrue(state.isDetached("thread-a"))
        assertFalse(state.isDetached("thread-b"))
    }

    @Test fun loadedAndResetRejectStaleCallbacks() {
        val state = DetachedThreadState()
        state.detach("thread-a", "thread-a")
        assertFalse(state.loaded("thread-b"))
        assertTrue(state.isDetached("thread-a"))
        assertTrue(state.loaded("thread-a"))
        assertFalse(state.loaded("thread-a"))
        state.detach("thread-a", "thread-a")
        state.reset()
        assertFalse(state.isDetached("thread-a"))
        assertFalse(state.loaded("thread-a"))
    }
}
