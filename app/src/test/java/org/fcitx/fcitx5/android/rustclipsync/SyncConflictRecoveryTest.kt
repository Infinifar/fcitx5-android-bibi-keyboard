/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.rustclipsync

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SyncConflictRecoveryTest {
    private val first = LocalCopy("old-copy", "synthetic-old", 1)
    private val initial = StoredSession("https://fixture.invalid", "synthetic-token", true, true,
        SyncState("epoch", "device", 4).localCopy(first))
    private val remote = ServerState("epoch", 12, 0, RemoteMessage("delivery", 8, "other"), 9)

    private class Fixture(var saved: StoredSession, val remote: ServerState) {
        var cancellations = 0
        var reads = 0
        var failSave = false
        var failCancel = false
        var failAfterCancel = false
        var committed: Long? = null
        var onState: () -> Unit = {}
        fun engine() = SyncConflictRecovery({ saved }, { change ->
            if (failSave) throw IOException("synthetic save failure")
            change(saved).also { saved = it }
        }, {
            reads++
            onState()
            if (failAfterCancel && cancellations > 0) throw IOException("synthetic connection failure")
            remote
        }, { _, _ ->
            assertTrue("the recovery decision must be durable before cancellation", saved.state.recoveringConflict)
            cancellations++
            if (failCancel) throw IOException("synthetic cancellation failure")
            committed
        })
    }

    @Test fun unsentExpiredCopyIsSkippedAndOnlyFreshCopiesCanUpload() {
        val fixture = Fixture(initial, remote)
        assertTrue(fixture.engine().recover { true })
        assertEquals(0, fixture.cancellations)
        assertNull(fixture.saved.state.pending)
        assertEquals(12L, fixture.saved.state.cursor)
        assertEquals(Ack("delivery", 8, "skipped"), fixture.saved.state.ack)
        val fresh = LocalCopy("fresh", "synthetic-fresh", 2)
        assertEquals(Outbox(fresh, 12, false), fixture.saved.state.localCopy(fresh).pending)
    }

    @Test fun uncertainUploadAndQueuedCopiesStayDiscardedAcrossFailedCancellationAndRestart() {
        val fixture = Fixture(initial.copy(state = initial.state.markAttempted()
            .localCopy(LocalCopy("queued", "synthetic-queued", 2))), remote)
        fixture.failCancel = true
        assertThrows(IOException::class.java) { fixture.engine().recover { true } }
        assertTrue(fixture.saved.state.recoveringConflict)
        assertTrue(fixture.saved.state.discardPending)
        assertNull(fixture.saved.state.queued)
        assertEquals(fixture.saved.state, fixture.saved.state.localCopy(LocalCopy("during", "synthetic", 3)))
        assertThrows(IllegalArgumentException::class.java) {
            fixture.saved.state.prepareApply(requireNotNull(remote.message))
        }
        fixture.failCancel = false
        assertTrue(fixture.engine().recover { true })
        assertNull(fixture.saved.state.pending)
        assertFalse(fixture.saved.state.recoveringConflict)
        assertEquals(Ack("delivery", 8, "skipped"), fixture.saved.state.ack)
    }

    @Test fun failureAfterCancellationResumesWithoutResendingOrCancellingAnotherCopy() {
        val fixture = Fixture(initial.copy(state = initial.state.markAttempted()), remote)
        fixture.failAfterCancel = true
        assertThrows(IOException::class.java) { fixture.engine().recover { true } }
        assertNull(fixture.saved.state.pending)
        assertTrue(fixture.saved.state.recoveringConflict)
        fixture.failAfterCancel = false
        assertTrue(fixture.engine().recover { true })
        assertEquals(1, fixture.cancellations)
    }

    @Test fun cancellationRecognizesAnAlreadyCommittedUploadWithoutRollingItBack() {
        val fixture = Fixture(initial.copy(state = initial.state.markAttempted()),
            ServerState("epoch", 5, 0, null, null))
        fixture.committed = 5
        assertTrue(fixture.engine().recover { true })
        assertEquals(5L, fixture.saved.state.cursor)
        assertEquals(Ack(first.id, 5, "self"), fixture.saved.state.ack)
        assertNull(fixture.saved.state.pending)
    }

    @Test fun saveFailureStopsBeforeAnyNetworkOrCancellation() {
        val fixture = Fixture(initial, remote)
        fixture.failSave = true
        assertThrows(IOException::class.java) { fixture.engine().recover { true } }
        assertEquals(initial, fixture.saved)
        assertEquals(0, fixture.reads)
        assertEquals(0, fixture.cancellations)
    }

    @Test fun instanceResetIsNotAnAutomaticConflictRecovery() {
        val fixture = Fixture(initial.copy(state = initial.state.markAttempted()), remote.copy(epoch = "new-epoch"))
        assertThrows(RecoveryPairingRequired::class.java) { fixture.engine().recover { true } }
        assertEquals(first.id, fixture.saved.state.pending?.copy?.id)
        assertEquals(0, fixture.cancellations)
    }

    @Test fun stoppedGenerationLeavesRecoveryDurableForTheNextRun() {
        var active = true
        val fixture = Fixture(initial.copy(state = initial.state.markAttempted()), remote)
        fixture.onState = { active = false }
        assertFalse(fixture.engine().recover { active })
        assertEquals(0, fixture.cancellations)
        assertTrue(fixture.saved.state.recoveringConflict)
        fixture.onState = {}
        assertTrue(fixture.engine().recover { true })
    }
}
