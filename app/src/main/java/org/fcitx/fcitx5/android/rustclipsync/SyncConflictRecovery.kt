/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.rustclipsync

/** An instance reset is never treated as an ordinary upload conflict. */
internal class RecoveryPairingRequired : Exception("Sync identity requires pairing")

/** Durable safe-skip recovery. This path never downloads or uploads clipboard bodies. */
internal class SyncConflictRecovery(
    private val load: () -> StoredSession?,
    private val update: ((StoredSession) -> StoredSession) -> StoredSession,
    private val state: (StoredSession) -> ServerState,
    private val cancel: (StoredSession, Outbox) -> Long?,
) {
    fun recover(active: () -> Boolean): Boolean {
        val original = load() ?: return false
        if (!active() || !original.paired || original.logoutPending) return false
        var current = update {
            if (active() && same(original, it)) it.copy(state = it.state.beginConflictRecovery()) else it
        }
        if (!active() || !same(original, current) || !current.state.recoveringConflict) return false

        // Verify the instance before resolving an uncertain upload. A new epoch
        // must not clear a pending transaction or silently replace the identity.
        validate(current, state(current))
        if (!active()) return false
        current.state.pending?.let { pending ->
            val committed = try { cancel(current, pending) }
                catch (failure: HttpFailure) {
                    if (failure.status == 409) throw RecoveryPairingRequired()
                    throw failure
                }
            if (!active()) return false
            current = update {
                if (!active() || !same(original, it) || it.state.pending?.copy?.id != pending.copy.id) it
                else it.copy(state = if (committed == null) it.state.cancelled(pending.copy.id)
                    else it.state.committed(pending.copy.id, committed))
            }
        }
        if (!active() || !same(original, current) || current.state.pending != null) return false
        val remote = state(current)
        validate(current, remote)
        if (!active()) return false
        val finished = update {
            if (active() && same(original, it) && it.state.recoveringConflict && it.state.pending == null)
                it.copy(state = it.state.finishConflictRecovery(remote),
                    observedRevision = maxOf(it.observedRevision, remote.revision)) else it
        }
        return active() && same(original, finished) && !finished.state.recoveringConflict
    }

    private fun same(a: StoredSession, b: StoredSession) =
        a.state.deviceId == b.state.deviceId && a.state.epoch == b.state.epoch && !b.logoutPending

    private fun validate(session: StoredSession, remote: ServerState) {
        if (remote.epoch != session.state.epoch || remote.revision < session.state.cursor)
            throw RecoveryPairingRequired()
    }
}
