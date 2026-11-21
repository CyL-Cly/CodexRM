package com.codexrm.mobile

internal data class ApprovalScope(
    val agentId: String,
    val threadId: String,
    val turnId: String,
) {
    val complete: Boolean get() = agentId.isNotEmpty() && threadId.isNotEmpty() && turnId.isNotEmpty()
}

internal object ApprovalFilter {
    /**
     * A request is shown only in the exact thread it belongs to; a new chat shows
     * no thread-level requests. Missing thread ownership is quarantined rather than
     * guessed, because approving it in an unrelated chat would act on the original
     * request ID. turnId is enforced only when both sides are known, matching the
     * web client, while a stale turn can never leak into a new one.
     */
    fun visible(scope: ApprovalScope?, agentId: String, threadId: String, turnId: String): Boolean {
        if (scope == null || threadId.isEmpty()) return false
        if (scope.agentId != agentId) return false
        if (scope.threadId.isEmpty() || scope.threadId != threadId) return false
        return scope.turnId.isEmpty() || turnId.isEmpty() || scope.turnId == turnId
    }

    /** Batch actions never cross agents or threads, even if their request methods match. */
    fun canBatch(scopes: List<ApprovalScope>): Boolean {
        if (scopes.size < 2 || scopes.any { it.agentId.isEmpty() || it.threadId.isEmpty() }) return false
        return scopes.map { it.agentId to it.threadId }.distinct().size == 1
    }
}
