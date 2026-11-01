/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.rustclipsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncStateTest {
    private val initial = SyncState("epoch", "this-device", 4)
    private fun local(id: String, text: String) = LocalCopy(id, text, 1)

    @Test fun offlineCopiesCollapseToLatestBeforeFirstAttempt() {
        val state = initial.localCopy(local("a", "same"))
            .localCopy(local("b", "different"))
            .localCopy(local("c", "same"))
        assertEquals("c", state.pending?.copy?.id)
        assertEquals(4L, state.pending?.baseRevision)
        assertNull(state.queued)
    }

    @Test fun uncertainUploadMustBeResolvedBeforeLatestCopyIsSent() {
        val state = initial.localCopy(local("a", "old")).markAttempted()
            .localCopy(local("b", "middle")).localCopy(local("c", "new"))
        assertEquals("a", state.pending?.copy?.id)
        assertEquals("c", state.queued?.id)
        assertThrows(IllegalArgumentException::class.java) { state.markAttempted() }
        val cancelled = state.cancelled("a")
        assertEquals("c", cancelled.pending?.copy?.id)
        assertEquals(4L, cancelled.pending?.baseRevision)
        val committed = state.committed("a", 5)
        assertEquals("c", committed.pending?.copy?.id)
        assertEquals(5L, committed.pending?.baseRevision)
        assertEquals(Ack("a", 5, "self"), committed.ack)
    }

    @Test fun applyJournalPreventsBlindReplayAfterCrash() {
        val message = RemoteMessage("m", 5, "other")
        val journaled = initial.prepareApply(message)
        assertEquals(initial.cursor, journaled.cursor)
        val restarted = journaled.recoverAmbiguousApply()
        assertEquals(5L, restarted.cursor)
        assertEquals(Ack("m", 5, "skipped"), restarted.ack)
        assertNull(restarted.applying)
        assertEquals(initial, journaled.finishApply("m", false))
    }

    @Test fun identicalTextCanBeTwoDistinctEvents() {
        val first = initial.localCopy(local("a", "same")).markAttempted().committed("a", 5)
        val second = first.localCopy(local("b", "same"))
        assertEquals("b", second.pending?.copy?.id)
        assertEquals(5L, second.pending?.baseRevision)
    }

    @Test fun localPendingBlocksRemoteApply() {
        val state = initial.localCopy(local("a", "local"))
        assertThrows(IllegalArgumentException::class.java) {
            state.prepareApply(RemoteMessage("b", 5, "other"))
        }
    }

    @Test fun clearCancelsUncertainOldUploadAndStopsRetries() {
        val unsent = initial.localCopy(local("a", "text")).localCleared()
        assertNull(unsent.pending)
        val attempted = initial.localCopy(local("a", "text")).markAttempted().localCleared()
        assertEquals(true, attempted.discardPending)
        assertNull(attempted.cancelled("a").pending)
        val alreadyCommitted = attempted.committed("a", 5)
        assertNull(alreadyCommitted.pending)
        assertEquals(5L, alreadyCommitted.cursor)
    }

    @Test fun sensitiveMarkerSurvivesOutboxTransitions() {
        val copy = LocalCopy("sensitive", "fixture", 1, sensitive = true)
        val state = initial.localCopy(copy).markAttempted()
        assertEquals(true, state.pending?.copy?.sensitive)
        val later = state.localCopy(local("next", "normal")).committed("sensitive", 5)
        assertEquals(false, later.pending?.copy?.sensitive)
    }

    @Test fun writeThatThrowsAfterSideEffectIsSkippedWithoutReplay() {
        val message = RemoteMessage("remote", 5, "other")
        val journaled = initial.prepareApply(message)
        var writes = 0
        val result = applyClipboardWithEcho(message.id, { 100L }, {}) {
            writes++
            throw java.io.IOException("fixture write outcome unknown")
        }
        val recovered = journaled.finishApply(message.id, result)
        assertEquals(1, writes)
        assertNull(recovered.applying)
        assertEquals(5L, recovered.cursor)
        assertEquals(Ack(message.id, 5, "skipped"), recovered.ack)
        assertThrows(IllegalArgumentException::class.java) { recovered.prepareApply(message) }
    }

    @Test fun unwrittenReceiveCanBeRetriedAndSuccessfulReceiveIsApplied() {
        val message = RemoteMessage("remote", 5, "other")
        val journaled = initial.prepareApply(message)
        assertEquals(initial, journaled.finishApply(message.id, ClipboardApplyResult.NotAttempted))
        assertEquals(Ack(message.id, 5, "applied"),
            journaled.finishApply(message.id, ClipboardApplyResult.Applied).ack)
    }

    @Test fun slowClipboardWriteDoesNotConsumeEchoWindow() {
        for (fails in listOf(false, true)) {
            var now = 100L
            var marker: ClipboardEcho? = null
            val result = applyClipboardWithEcho("remote", { now }, { marker = it }) {
                now += 5_000
                assertEquals(true, marker?.matches("remote", now))
                if (fails) throw java.io.IOException("fixture write outcome unknown")
            }
            assertEquals(if (fails) ClipboardApplyResult.Unknown else ClipboardApplyResult.Applied, result)
            assertEquals(true, marker?.matches("remote", now + 2_999))
            assertEquals(false, marker?.matches("remote", now + 3_001))
            assertEquals(false, marker?.matches("different-copy", now))
        }
    }

    @Test fun latestSensitivePolicyDiscardsOutboxWithoutResendingUncertainText() {
        val sensitive = LocalCopy("secret", "fixture", 1, sensitive = true)
        val pending = initial.localCopy(sensitive)
        assertNull(pending.withSensitivePolicy(false).pending)
        val uncertain = pending.markAttempted().withSensitivePolicy(false)
        assertEquals(true, uncertain.discardPending)
        assertNull(uncertain.cancelled("secret").pending)
        assertEquals(pending, pending.withSensitivePolicy(true))
        val publicLatest = pending.markAttempted().localCopy(local("new", "public"))
        assertEquals(publicLatest, publicLatest.withSensitivePolicy(false))
    }
}
