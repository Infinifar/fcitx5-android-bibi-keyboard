/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.rustclipsync

import android.content.Context
import java.security.SecureRandom
import java.util.UUID

/** Pairing and logout run on a background thread. */
internal object SyncSetup {
    fun pair(context: Context, url: String, user: String, password: String): String {
        val transport = SyncTransport(url)
        val store = SyncStore(context)
        val previous = store.load()
        check(previous == null || (!previous.paired && previous.url == url)) { "already paired" }
        val candidate = previous ?: StoredSession(
            url, token(), paired = false, enabled = true,
            state = SyncState("", UUID.randomUUID().toString(), 0),
        ).also(store::save)
        // The token was saved before the request: retrying after a lost response is safe.
        val (epoch, baseline) = transport.register(candidate.state.deviceId, candidate.token, user, password)
        store.update { current ->
            check(!current.paired && current.state.deviceId == candidate.state.deviceId && current.url == url)
            current.copy(paired = true, enabled = true, state = current.state.copy(epoch = epoch, cursor = baseline), observedRevision = baseline)
        }
        return candidate.state.deviceId
    }

    fun disconnect(context: Context) {
        val store = SyncStore(context)
        val session = store.load() ?: return
        if (session.paired) {
            try {
                SyncDirectionController(store::load, store::update,
                    SyncDirectionTransport(SyncTransport(session.url))).use { it.revoke() }
            } catch (failure: HttpFailure) {
                // A confirmed invalidated token cannot retain an active server session.
                if (failure.status != 401 || failure.code != "UNAUTHENTICATED") throw failure
            }
        }
        store.clear()
    }

    private fun token(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val digits = "0123456789abcdef"
        return buildString(64) {
            bytes.forEach { value ->
                val n = value.toInt() and 0xff
                append(digits[n ushr 4]); append(digits[n and 15])
            }
        }
    }
}
