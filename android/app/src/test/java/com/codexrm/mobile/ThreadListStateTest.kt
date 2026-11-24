package com.codexrm.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadListStateTest {
    private val old = ThreadSummary("old", "Previous task", "/workspace", active = false)
    private val created = ThreadSummary("new", "New session", "/workspace", active = false, updatedAt = 10)

    @Test fun `new thread appears immediately and survives a stale refresh`() {
        val state = ThreadListState()
        state.commit(state.mergePage(emptyList(), listOf(old)))
        state.addCreated(created)
        assertEquals(listOf(created, old), state.entries)

        state.commit(state.mergePage(emptyList(), listOf(old)))
        assertEquals(listOf(old, created), state.entries)
        state.commit(state.mergePage(emptyList(), listOf(old)))
        assertEquals(listOf(old, created), state.entries)
    }

    @Test fun `later page replaces the temporary entry with server metadata`() {
        val state = ThreadListState()
        state.addCreated(created)
        state.commit(state.mergePage(emptyList(), listOf(old)))
        val previousPage = state.mergePage(emptyList(), listOf(old))
        state.updateActivity(created.id, active = true, updatedAt = 20)
        state.commit(state.mergePage(previousPage, emptyList()))
        assertEquals(listOf(old, created.copy(active = true, updatedAt = 20)), state.entries)

        val listed = created.copy(title = "First prompt", active = true, updatedAt = 21)
        state.commit(state.mergePage(previousPage, listOf(listed)))
        assertEquals(listOf(old, listed), state.entries)

        state.commit(state.mergePage(emptyList(), listOf(old)))
        assertEquals(listOf(old), state.entries)
    }

    @Test fun `a delayed creation response does not overwrite a thread already listed`() {
        val state = ThreadListState()
        val listed = created.copy(title = "First prompt")
        state.commit(state.mergePage(emptyList(), listOf(listed)))
        state.addCreated(created)
        assertEquals(listOf(listed), state.entries)
    }

    @Test fun `archive and device reset clear temporary entries`() {
        val state = ThreadListState()
        state.addCreated(created)
        state.remove(created.id)
        state.commit(state.mergePage(emptyList(), listOf(old)))
        assertEquals(listOf(old), state.entries)

        state.addCreated(created)
        state.clear()
        state.commit(state.mergePage(emptyList(), emptyList()))
        assertEquals(emptyList<ThreadSummary>(), state.entries)
    }

    @Test fun `background monitoring includes active conversations outside the current list`() {
        val state = ThreadListState()
        state.commit(listOf(old.copy(active = true), created))
        state.updateActivity("unlisted", active = true, updatedAt = 20)
        assertEquals(setOf(old.id, "unlisted"), state.activeIds)

        state.updateActivity(old.id, active = false, updatedAt = 21)
        assertEquals(setOf("unlisted"), state.activeIds)
        state.updateActivity("unlisted", active = false, updatedAt = 22)
        assertEquals(emptySet<String>(), state.activeIds)
    }

    @Test fun `incomplete paging never replaces the committed list`() {
        val state = ThreadListState()
        state.commit(state.mergePage(emptyList(), listOf(old)))
        val staged = state.mergePage(emptyList(), listOf(created))
        assertEquals(listOf(old), state.entries)
        state.commit(staged)
        assertEquals(listOf(created), state.entries)
    }
}
