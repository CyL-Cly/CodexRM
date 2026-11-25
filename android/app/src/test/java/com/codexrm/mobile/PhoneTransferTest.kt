package com.codexrm.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

class PhoneTransferTest {
    private fun file(state: String, batch: String = "") =
        TransferEntry(UUID.randomUUID().toString(), "file.txt", 123, 0, state, batch)

    @Test fun batchWaitsForOtherFilesButCanFinishAfterOneIsCancelled() {
        val batch = UUID.randomUUID().toString()
        val first = file("sent", batch)
        val second = file("pending", batch)
        assertNull(completedPhoneTransferBatch(listOf(first, second), emptySet()) { true })
        val partial = listOf(first, second.copy(state = "cancelledSend", batch = ""))
        assertEquals(batch, completedPhoneTransferBatch(partial, emptySet()) { true })
        assertNull(completedPhoneTransferBatch(partial, setOf(batch)) { true })
        assertNull(completedPhoneTransferBatch(partial, emptySet()) { false })
        assertNull(completedPhoneTransferBatch(partial.map { it.copy(batch = "") }, emptySet()) { true })
    }

    @Test fun cancellationAndBatchMetadataCanBeRestoredFromDisk() {
        val batch = UUID.randomUUID().toString()
        for (entry in listOf(file("pending", batch), file("cancelledSend"), file("cancelledIncoming"), file("cancelledReceive"))) {
            assertEquals(entry, TransferEntry.parse(entry.json()))
        }
        val legacy = file("pending")
        assertEquals(legacy, TransferEntry.parse(legacy.json().apply { remove("batch") }))
    }

    @Test fun savedFilesAreLinkedToTheSpecificPairAndTransfer() {
        val owner = Device("phone", "token", "https://relay.example", "agent", "desktop")
        val id = file("received").id
        val key = WorkspaceDownloads.transferKey(owner, id)
        assertEquals(key, WorkspaceDownloads.transferKey(owner.copy(serverName = "renamed"), id))
        assertNotEquals(key, WorkspaceDownloads.transferKey(owner.copy(clientId = "new-pair"), id))
        assertNotEquals(key, WorkspaceDownloads.transferKey(owner.copy(serverUrl = "https://other.example"), id))
        assertNotEquals(key, WorkspaceDownloads.transferKey(owner, file("received").id))
        assertNotEquals(key, WorkspaceDownloads.key(owner, RemoteFile("", "", id)))
    }
}
