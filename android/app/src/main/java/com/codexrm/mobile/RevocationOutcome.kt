package com.codexrm.mobile

internal enum class RevocationOutcome { DONE, WAIT_FOR_NETWORK, FAILED }

internal fun revocationOutcome(errorStatus: Int?, stillConnected: () -> Boolean): RevocationOutcome = when {
    // A 401 from this endpoint means the old token is already unusable.
    errorStatus == null || errorStatus == 401 -> RevocationOutcome.DONE
    errorStatus == 0 && !stillConnected() -> RevocationOutcome.WAIT_FOR_NETWORK
    else -> RevocationOutcome.FAILED
}
