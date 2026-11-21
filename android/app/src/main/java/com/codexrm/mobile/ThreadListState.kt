package com.codexrm.mobile

internal class ThreadListState {
    var entries: List<ThreadSummary> = emptyList()
        private set

    // A newly created thread can be absent from thread/list until its first turn is persisted.
    private val unlistedIds = mutableSetOf<String>()

    fun addCreated(thread: ThreadSummary) {
        if (entries.any { it.id == thread.id }) return
        unlistedIds.add(thread.id)
        entries = listOf(thread) + entries
    }

    /** Build a candidate refresh without publishing it; only a complete refresh may commit. */
    fun mergePage(accumulated: List<ThreadSummary>, page: List<ThreadSummary>): List<ThreadSummary> {
        val merged = LinkedHashMap<String, ThreadSummary>()
        accumulated.forEach { merged[it.id] = it }
        page.forEach { merged[it.id] = it }
        return merged.values.toList()
    }

    fun commit(values: List<ThreadSummary>) {
        values.forEach { unlistedIds.remove(it.id) }
        entries = values + entries.filter { it.id in unlistedIds }
    }

    fun updateActivity(id: String, active: Boolean, updatedAt: Long) {
        entries = entries.map { if (it.id == id) it.copy(active = active, updatedAt = updatedAt) else it }
    }

    fun remove(id: String) {
        unlistedIds.remove(id)
        entries = entries.filterNot { it.id == id }
    }

    fun clear() {
        unlistedIds.clear()
        entries = emptyList()
    }
}
