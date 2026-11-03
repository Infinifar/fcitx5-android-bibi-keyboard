/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.rustclipsync

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SyncDirectionTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    private val epoch = "00000000-0000-4000-8000-000000000002"
    private class Remote : DirectionTransport {
        var version = 0L
        var snapshots = 0
        var failures = 0
        var failRevoke = false
        val operations = mutableListOf<DirectionOperation>()
        override fun snapshot(session: StoredSession): DirectionSnapshot {
            snapshots++
            return DirectionSnapshot(null, version, true)
        }
        override fun report(session: StoredSession, operation: DirectionOperation): DirectionControl {
            operations += operation
            if (failures-- > 0 || (operation.revoke && failRevoke)) throw IOException("synthetic lost ACK")
            version = operation.version
            return DirectionControl(operation.sessionId, version, operation.direction, operation.paused)
        }
    }
    private inner class Fixture {
        var stored = StoredSession("https://example.test", "ab".repeat(32), true, true, SyncState(epoch, id, 4))
        val remote = Remote()
        val controller = SyncDirectionController({ stored }, { transform -> transform(stored).also { stored = it } }, remote)
    }

    @Test fun ambiguousReportPersistsAndRetriesExactOperation() {
        val f = Fixture()
        f.remote.failures = 1
        assertThrows(IOException::class.java) { f.controller.ensure(SyncDirection.RECEIVE_ONLY, false) { true } }
        assertNull(f.stored.control)
        val pending = requireNotNull(f.stored.controlPending)
        val result = f.controller.ensure(SyncDirection.RECEIVE_ONLY, false) { true }
        assertEquals(listOf(pending, pending), f.remote.operations)
        assertEquals(1, f.remote.snapshots)
        assertEquals(pending.sessionId, result.control?.sessionId)
        assertNull(result.controlPending)
    }

    @Test fun pauseRetainsDirectionAndPairingAndBlocksLocalCapabilities() {
        val f = Fixture()
        f.controller.ensure(SyncDirection.PUSH_ONLY, false) { true }
        val paused = f.controller.ensure(SyncDirection.PUSH_ONLY, true) { true }
        assertTrue(paused.paired)
        assertFalse(paused.enabled)
        assertEquals(SyncDirection.PUSH_ONLY, paused.direction)
        assertTrue(paused.control!!.paused)
        assertEquals(2L, paused.control.version)
        assertFalse(f.remote.operations.last().connect)
        assertTrue(SyncDirection.PUSH_ONLY.sends)
        assertFalse(SyncDirection.PUSH_ONLY.receives)
        assertFalse(SyncDirection.RECEIVE_ONLY.sends)
        assertTrue(SyncDirection.RECEIVE_ONLY.receives)
    }

    @Test fun supersededRunCannotAcceptAckOrEnableData() {
        val f = Fixture()
        var active = true
        val remote = object : DirectionTransport {
            override fun snapshot(session: StoredSession) = DirectionSnapshot(null, 0, true)
            override fun report(session: StoredSession, operation: DirectionOperation): DirectionControl {
                active = false
                return DirectionControl(operation.sessionId, operation.version, operation.direction, operation.paused)
            }
        }
        val controller = SyncDirectionController({ f.stored }, { transform -> transform(f.stored).also { f.stored = it } }, remote)
        assertThrows(IllegalStateException::class.java) { controller.ensure(SyncDirection.BIDIRECTIONAL, false) { active } }
        assertNull(f.stored.control)
        assertNotNull(f.stored.controlPending)
    }

    @Test fun unconfirmedLogoutRetainsCredentialsAndRetriesExactRevocation() {
        val f = Fixture()
        f.controller.ensure(SyncDirection.BIDIRECTIONAL, false) { true }
        f.remote.failRevoke = true
        assertThrows(IOException::class.java) { f.controller.revoke() }
        assertTrue(f.stored.logoutPending)
        assertFalse(f.stored.enabled)
        assertEquals("ab".repeat(32), f.stored.token)
        val pending = requireNotNull(f.stored.controlPending)
        assertTrue(pending.revoke)
        assertThrows(IllegalStateException::class.java) { f.controller.ensure(SyncDirection.BIDIRECTIONAL, false) { true } }
        f.remote.failRevoke = false
        f.controller.revoke()
        assertEquals(pending, f.remote.operations.last())
        assertNull(f.stored.controlPending)
    }

    @Test fun onlyCurrentGenerationLeaseExpiryCanClaimFreshSession() {
        val f = Fixture()
        val first = f.controller.ensure(SyncDirection.RECEIVE_ONLY, false) { true }
        val revoked = HttpFailure(409, "SESSION_REVOKED")
        val offline = HttpFailure(409, "SESSION_OFFLINE")
        assertFalse(f.controller.recoverLease(revoked) { true })
        assertFalse(f.controller.recoverLease(offline) { false })
        f.controller.ensure(SyncDirection.RECEIVE_ONLY, false) { true }
        assertEquals(1, f.remote.operations.size)
        assertTrue(f.controller.recoverLease(offline) { true })
        val reconnected = f.controller.ensure(SyncDirection.RECEIVE_ONLY, false) { true }
        assertNotEquals(first.control!!.sessionId, reconnected.control!!.sessionId)
        assertEquals(2L, reconnected.control.version)
        assertTrue(f.remote.operations.last().connect)
        assertEquals(SyncDirection.RECEIVE_ONLY, reconnected.control.direction)
    }

    @Test fun newGlobalBaselineDoesNotSuppressOlderEligibleDelivery() {
        val state = SyncState(epoch, id, 4)
        val incoming = RemoteMessage("00000000-0000-4000-8000-000000000003", 6,
            "00000000-0000-4000-8000-000000000004")
        val queued = state.localCopy(LocalCopy("copy", "fixture", 0), baseRevision = 12)
        assertEquals(4L, queued.cursor)
        assertEquals(12L, queued.pending!!.baseRevision)
        assertEquals(6L, state.prepareApply(incoming).finishApply(incoming.id, true).cursor)
    }
}
