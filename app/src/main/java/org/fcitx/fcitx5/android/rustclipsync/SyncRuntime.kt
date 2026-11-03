/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.rustclipsync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.preference.PreferenceManager
import android.provider.Settings
import org.fcitx.fcitx5.android.utils.clipboardIsSensitive
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal const val RCS_DIRECTION = "rustclipsync_direction"
internal const val RCS_ENABLED = "rustclipsync_enabled"
internal const val RCS_ALLOW_SENSITIVE = "rustclipsync_allow_sensitive"
internal const val RCS_STATUS = "rustclipsync_status"
internal const val RCS_PAIRED = "rustclipsync_paired"
internal const val RCS_DEVICE_ID = "rustclipsync_device_id"
internal const val RCS_REVIEW_PENDING = "rustclipsync_review_pending"

/** One sender and one long-poll worker, with all clipboard access on the main thread. */
@Suppress("DEPRECATION")
internal class SyncRuntime(private val service: Context, private val activeImeOverride: (() -> Boolean)? = null) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(service)
    private val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val store = SyncStore(service)
    private val handler = Handler(Looper.getMainLooper())
    private val runId = AtomicLong()
    private val generation = AtomicLong()
    @Volatile private var localQueue = LocalQueue()
    private val echo = AtomicReference<ClipboardEcho?>(null)
    @Volatile private var running = false
    private var sender: ScheduledExecutorService? = null
    private var localWriter: java.util.concurrent.ExecutorService? = null
    private var poller: java.util.concurrent.ExecutorService? = null
    private var nextSendAt = 0L
    private var sendBackoff = 1L
    @Volatile private var control: SyncDirectionController? = null

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == RCS_ENABLED || key == RCS_DIRECTION) reconcile()
        if (key == RCS_ALLOW_SENSITIVE) generation.incrementAndGet()
    }

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!running || !prefs.getBoolean(RCS_ENABLED, false) || !direction().sends || !isDefaultIme()) return@OnPrimaryClipChangedListener
        val eventGeneration = generation.incrementAndGet()
        val clip = try { clipboard.primaryClip } catch (_: Exception) { null }
        val now = SystemClock.elapsedRealtime()
        val expected = echo.get()
        val marker = if (Build.VERSION.SDK_INT >= 24) clip?.description?.extras?.getString(MARKER) else null
        if (expected?.matches(marker, now) == true) {
            return@OnPrimaryClipChangedListener
        }
        echo.set(null)
        val sensitive = clipboardIsSensitive(clip?.description)
        val allowSensitive = prefs.getBoolean(RCS_ALLOW_SENSITIVE, false)
        val text = try { if ((!sensitive || allowSensitive) && clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null }
            catch (_: Exception) { null }
        val candidate = text?.takeIf { it.isNotEmpty() && it.length <= SyncStore.MAX_TEXT_BYTES }
        val value = if (sensitive && !allowSensitive) null else candidate
        enqueueLocal(LocalUpdate(value, sensitive, runId.get(), eventGeneration))
    }

    fun create() {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        reconcile()
    }

    fun destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        stop()
    }

    private fun direction() = SyncDirection.preference(prefs.getString(RCS_DIRECTION, null))

    private fun reconcile() {
        if (running) {
            if (!prefs.getBoolean(RCS_ENABLED, false)) prefs.edit().putBoolean(RCS_REVIEW_PENDING, true).apply()
            stop()
        }
        // Even paused sessions report paused=true; ordinary process shutdown is not logout.
        start()
    }

    private fun start() {
        if (running) return
        if (Build.VERSION.SDK_INT < 24) { status("unsupported"); return }
        running = true
        localQueue = LocalQueue()
        nextSendAt = 0
        sendBackoff = 1
        if (prefs.getString(RCS_STATUS, "") == "session_revoked") status("connecting")
        val run = runId.incrementAndGet()
        generation.incrementAndGet()
        val worker = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "RustClipSync-sender").apply { isDaemon = true }
        }
        val receiver = Executors.newSingleThreadExecutor { task ->
            Thread(task, "RustClipSync-receiver").apply { isDaemon = true }
        }
        val writer = Executors.newSingleThreadExecutor { task ->
            Thread(task, "RustClipSync-local-state").apply { isDaemon = true }
        }
        sender = worker
        localWriter = writer
        poller = receiver
        clipboard.addPrimaryClipChangedListener(listener)
        worker.execute {
            try {
                var current = store.load()
                if (current == null || !current.paired || current.logoutPending) { status("not_paired"); return@execute }
                if (current.state.applying != null) current = store.update { it.copy(state = it.state.recoverAmbiguousApply()) }
                if (current.state.pending != null && prefs.getString(RCS_STATUS, "") in setOf("conflict", "expired"))
                    current = store.update { it.copy(state = it.state.beginConflictRecovery()) }
                if (current.state.pending == null && !current.state.recoveringConflict &&
                    prefs.getString(RCS_STATUS, "") in setOf("conflict", "expired", "recovering")) status("connecting")
                if (current.state.pending != null && !current.state.discardPending && !current.state.recoveringConflict)
                    reviewRecoveredCopy(run, current)
                if (prefs.getBoolean(RCS_REVIEW_PENDING, false)) {
                    val afterReview = store.load()
                    if (afterReview?.state?.pending != null)
                        current = store.update { it.copy(state = it.state.beginConflictRecovery()) }
                    prefs.edit().putBoolean(RCS_REVIEW_PENDING, false).apply()
                }
                if (active(run)) {
                    control = SyncDirectionController(store::load, store::update, SyncDirectionTransport(SyncTransport(current.url)))
                    receiver.execute { receiveLoop(run) }
                }
            } catch (_: Exception) { status("storage_error") }
        }
        worker.scheduleWithFixedDelay({ if (active(run)) sendOnce(run) }, 1, 1, TimeUnit.SECONDS)
    }

    private fun stop() {
        if (!running) return
        running = false
        runId.incrementAndGet()
        generation.incrementAndGet()
        clipboard.removePrimaryClipChangedListener(listener)
        echo.set(null)
        sender?.shutdownNow()
        localWriter?.shutdownNow()
        poller?.shutdownNow()
        sender = null
        localWriter = null
        poller = null
        // Closing is done off the main thread: an outstanding control ACK is bounded.
        val previous = control; control = null
        if (previous != null) Thread({ previous.close() }, "RustClipSync-close").start()
        status("disabled")
    }

    private fun active(run: Long) = running && runId.get() == run

    private fun isDefaultIme(): Boolean = activeImeOverride?.invoke() ?: try {
        val current = Settings.Secure.getString(service.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val component = ComponentName.unflattenFromString(current ?: "")
        component?.packageName == service.packageName && component.className == service.javaClass.name
    } catch (_: Exception) { false }

    private fun same(a: StoredSession, b: StoredSession) = a.state.deviceId == b.state.deviceId && a.state.epoch == b.state.epoch

    private fun enqueueLocal(value: LocalUpdate) {
        val queue = localQueue
        val schedule = synchronized(queue) {
            queue.latest = value
            queue.busy.set(1)
            if (queue.scheduled) false else { queue.scheduled = true; true }
        }
        if (!schedule) return
        try {
            val writer = localWriter ?: throw java.util.concurrent.RejectedExecutionException()
            writer.execute {
                while (true) {
                    val next = synchronized(queue) {
                        val item = queue.latest
                        queue.latest = null
                        if (item == null) { queue.scheduled = false; queue.busy.set(0) }
                        item
                    } ?: break
                    try {
                        if (!active(next.run)) continue
                        val safe = next.text?.takeIf { it.toByteArray(Charsets.UTF_8).size <= SyncStore.MAX_TEXT_BYTES }
                        store.update { current ->
                            if (!active(next.run) || next.generation != generation.get() || !current.paired || !current.enabled || !direction().sends) current
                            else current.copy(state = if (safe == null || (next.sensitive && !prefs.getBoolean(RCS_ALLOW_SENSITIVE, false))) current.state.localCleared()
                                else current.state.localCopy(LocalCopy(UUID.randomUUID().toString(), safe, System.currentTimeMillis(), next.sensitive), current.observedRevision))
                        }
                    } catch (_: Exception) { status("storage_error") }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(queue) { queue.latest = null; queue.scheduled = false; queue.busy.set(0) }
        }
    }

    /** A copy may have changed while the input method process was not running. */
    private fun reviewRecoveredCopy(run: Long, previous: StoredSession) {
        val expected = previous.state.queued ?: previous.state.pending?.copy ?: return
        val startGeneration = generation.get()
        val known = AtomicBoolean(false)
        val snapshot = AtomicReference<Pair<String?, Boolean>?>(null)
        val latch = CountDownLatch(1)
        handler.post {
            try {
                if (active(run) && isDefaultIme()) {
                    val clip = clipboard.primaryClip
                    val sensitive = clipboardIsSensitive(clip?.description)
                    val text = if ((!sensitive || prefs.getBoolean(RCS_ALLOW_SENSITIVE, false)) && clip != null && clip.itemCount > 0)
                        clip.getItemAt(0).text?.toString() else null
                    snapshot.set(text to sensitive)
                    known.set(true)
                }
            } catch (_: Exception) { }
            finally { latch.countDown() }
        }
        if (!latch.await(5, TimeUnit.SECONDS) || !known.get()) {
            store.update { latest -> if (active(run) && same(previous, latest))
                latest.copy(state = latest.state.beginConflictRecovery()) else latest }
            return
        }
        if (!active(run) || generation.get() != startGeneration || localQueue.busy.get() != 0) return
        val current = snapshot.get()
        if (current == null || current.first != expected.text || current.second != expected.sensitive ||
            (expected.sensitive && !prefs.getBoolean(RCS_ALLOW_SENSITIVE, false))) {
            store.update { latest ->
                if (same(previous, latest) && latest.state == previous.state &&
                    generation.get() == startGeneration && localQueue.busy.get() == 0)
                    latest.copy(state = latest.state.beginConflictRecovery()) else latest
            }
        }
    }

    private fun sendOnce(run: Long) {
        try {
            if (prefs.getString(RCS_STATUS, "") in setOf("re_pair", "storage_error", "protocol_unsupported", "logout_unconfirmed", "session_revoked")) return
            if (SystemClock.elapsedRealtime() < nextSendAt) return
            if (localQueue.busy.get() != 0) return
            var session = store.load() ?: return
            if (!session.paired || session.logoutPending || !active(run)) return
            val selected = direction()
            val paused = !prefs.getBoolean(RCS_ENABLED, false)
            session = control?.ensure(selected, paused) { active(run) } ?: return
            if (!active(run)) return
            session = enforceSensitivePolicy(run, session)
            val transport = SyncTransport(session.url)
            if (paused) return
            val pendingCopy = session.state.pending
            val now = System.currentTimeMillis()
            if (session.state.recoveringConflict ||
                (pendingCopy != null && (prefs.getString(RCS_STATUS, "") in setOf("conflict", "expired") ||
                    now - pendingCopy.copy.createdAtMs > MAX_RETRY_MS || now + CLOCK_SKEW_MS < pendingCopy.copy.createdAtMs ||
                    (pendingCopy.attempted && pendingCopy.baseRevision < session.observedRevision)))) {
                recoverConflict(run, transport)
                return
            }
            session.state.ack?.let { ack ->
                try { transport.acknowledge(session, ack) }
                catch (e: HttpFailure) {
                    if (e.status != 410) throw e
                    val current = transport.state(session)
                    if (current.epoch != session.state.epoch || current.revision <= ack.revision) {
                        status("re_pair")
                        return
                    }
                }
                if (!active(run)) return
                session = store.update { current ->
                    if (same(session, current)) current.copy(state = current.state.acknowledged(ack)) else current
                }
            }
            // ACK can block while the user changes policy. Recheck the latest
            // value before choosing cancellation or upload.
            session = enforceSensitivePolicy(run, session)
            val pending = session.state.pending ?: return
            if (session.state.queued != null || session.state.discardPending) {
                val revision = transport.cancel(session, pending)
                if (!active(run)) return
                store.update { current ->
                    if (!same(session, current) || current.state.pending?.copy?.id != pending.copy.id) current
                    else current.copy(state = if (revision == null) current.state.cancelled(pending.copy.id)
                        else current.state.committed(pending.copy.id, revision))
                }
            } else {
                if (!selected.sends) return
                session = store.update { current ->
                    if (!same(session, current) || current.state.pending?.copy?.id != pending.copy.id ||
                        current.state.queued != null || current.state.discardPending) current
                    else current.copy(state = current.state.markAttempted())
                }
                val sending = session.state.pending ?: return
                if (sending.copy.id != pending.copy.id || !sending.attempted || session.state.queued != null ||
                    session.state.discardPending || localQueue.busy.get() != 0) return
                session = enforceSensitivePolicy(run, session)
                if (session.state.discardPending || session.state.queued != null ||
                    session.state.pending?.copy?.id != sending.copy.id) return
                if (!active(run) || !prefs.getBoolean(RCS_ENABLED, false) || !direction().sends) return
                val revision = transport.send(session, sending)
                if (!active(run) || !prefs.getBoolean(RCS_ENABLED, false) || !direction().sends) return
                store.update { current ->
                    if (!same(session, current) || current.state.pending?.copy?.id != sending.copy.id) current
                    else current.copy(state = current.state.committed(sending.copy.id, revision), observedRevision = maxOf(current.observedRevision, revision))
                }
            }
            nextSendAt = 0
            sendBackoff = 1
            connectedStatus()
        } catch (_: RecoveryPairingRequired) {
            if (active(run)) status("re_pair")
        } catch (e: HttpFailure) {
            if (!active(run)) return
            when (e.status) {
                401 -> status("re_pair")
                403 -> if (e.code == "DIRECTION_FORBIDDEN") { control?.invalidate(); connectedStatus("offline"); deferSend() } else status("re_pair")
                409 -> if (control?.recoverLease(e) { active(run) } == true) { connectedStatus("offline"); deferSend() }
                    else if (e.code == "SESSION_REVOKED") status("session_revoked") else status("conflict")
                426 -> status("protocol_unsupported")
                else -> { connectedStatus("offline"); deferSend() }
            }
        } catch (_: java.io.IOException) { if (active(run)) { control?.invalidate(); connectedStatus("offline"); deferSend() } }
        catch (_: Exception) { if (active(run)) status("storage_error") }
    }

    private fun recoverConflict(run: Long, transport: SyncTransport) {
        generation.incrementAndGet()
        status("recovering")
        try {
            val complete = SyncConflictRecovery(store::load, store::update, transport::state, transport::cancel)
                .recover { active(run) && prefs.getBoolean(RCS_ENABLED, false) }
            if (complete) {
                nextSendAt = 0
                sendBackoff = 1
                status("connected")
            }
        } finally {
            // Do not turn copies observed while cancelling/skipping into fresh uploads.
            generation.incrementAndGet()
        }
    }

    private fun enforceSensitivePolicy(run: Long, session: StoredSession): StoredSession {
        if (session.state.withSensitivePolicy(prefs.getBoolean(RCS_ALLOW_SENSITIVE, false)) == session.state)
            return session
        return store.update { current ->
            if (!active(run) || !same(session, current)) current
            else current.copy(state = current.state.withSensitivePolicy(prefs.getBoolean(RCS_ALLOW_SENSITIVE, false)))
        }
    }

    private fun receiveLoop(run: Long) {
        var backoff = 1L
        while (active(run)) {
            try {
                val session = store.load() ?: break
                if (!session.paired || session.logoutPending) break
                val selected = direction()
                val paused = !prefs.getBoolean(RCS_ENABLED, false)
                val ready = control?.ensure(selected, paused) { active(run) } ?: continue
                if (paused || !selected.receives) { if (!sleep(1)) break; continue }
                val gen = generation.get()
                val remote = SyncTransport(ready.url).waitForChanges(ready)
                if (!active(run)) break
                if (remote != null) {
                    receive(run, gen, ready, remote)
                    // A local pending copy, a hidden IME, or a guarded write can
                    // leave this revision unprocessed; avoid a hot poll loop.
                    val after = store.load()
                    if (after != null && remote.message?.revision?.let { after.state.cursor < it } == true && !sleep(5)) break
                }
                connectedStatus()
                backoff = 1
            } catch (e: HttpFailure) {
                if (!active(run)) break
                when (e.status) {
                    400, 401 -> { status("re_pair"); break }
                    403 -> if (e.code == "DIRECTION_FORBIDDEN") { control?.invalidate(); connectedStatus("offline") } else { status("re_pair"); break }
                    409 -> if (control?.recoverLease(e) { active(run) } == true) connectedStatus("offline")
                        else { status(if (e.code == "SESSION_REVOKED") "session_revoked" else "re_pair"); break }
                    426 -> { status("protocol_unsupported"); break }
                    else -> { control?.invalidate(); connectedStatus("offline") }
                }
                if (!sleep(backoff)) break
                backoff = (backoff * 2).coerceAtMost(30)
            } catch (_: java.io.IOException) {
                if (!active(run)) break
                control?.invalidate()
                connectedStatus("offline")
                if (!sleep(backoff)) break
                backoff = (backoff * 2).coerceAtMost(30)
            } catch (_: Exception) {
                if (!active(run)) break
                status("storage_error")
                break
            }
        }
    }

    private fun receive(run: Long, gen: Long, session: StoredSession, remote: ServerState) {
        if (!active(run) || !prefs.getBoolean(RCS_ENABLED, false) || !direction().receives) return
        if (remote.epoch != session.state.epoch) { status("re_pair"); return }
        store.update { current -> if (same(session, current)) current.copy(observedRevision = maxOf(current.observedRevision, remote.revision)) else current }
        val message = remote.message ?: return
        if (remote.ackRevision > session.state.cursor) {
            store.update { current ->
                if (same(session, current) && current.state.cursor < remote.ackRevision)
                    current.copy(state = current.state.copy(cursor = remote.ackRevision)) else current
            }
            return
        }
        if (message.revision <= session.state.cursor || !active(run) || localQueue.busy.get() != 0) return
        val latest = store.load() ?: return
        if (!same(session, latest) || latest.state.pending != null || latest.state.applying != null || latest.state.recoveringConflict ||
            message.revision <= latest.state.cursor || gen != generation.get()) return
        if (message.sourceDeviceId == session.state.deviceId) {
            store.update { current ->
                if (same(session, current) && current.state.pending == null && current.state.applying == null && !current.state.recoveringConflict &&
                    message.revision > current.state.cursor && gen == generation.get())
                    current.copy(state = current.state.remoteSelf(message)) else current
            }
            return
        }
        if (message.sensitive && !prefs.getBoolean(RCS_ALLOW_SENSITIVE, false)) {
            store.update { current ->
                if (same(session, current) && current.state.pending == null && current.state.applying == null && !current.state.recoveringConflict &&
                    message.revision > current.state.cursor && gen == generation.get())
                    current.copy(state = current.state.copy(cursor = message.revision,
                        ack = Ack(message.id, message.revision, "skipped"))) else current
            }
            return
        }
        if (!isDefaultIme()) return
        val text = try { SyncTransport(session.url).content(session, message) }
            catch (e: HttpFailure) { if (e.status == 410) return else throw e }
        require(text.toByteArray(Charsets.UTF_8).size.toLong() == remote.size)
        val confirmed = SyncTransport(session.url).state(session)
        if (confirmed.epoch != remote.epoch || confirmed.message != message) return
        if (!active(run) || !prefs.getBoolean(RCS_ENABLED, false) || !direction().receives || gen != generation.get() || localQueue.busy.get() != 0) return
        val journaled = store.update { current ->
            if (same(session, current) && current.state.pending == null && current.state.applying == null && !current.state.recoveringConflict &&
                message.revision > current.state.cursor && gen == generation.get() && localQueue.busy.get() == 0)
                current.copy(state = current.state.prepareApply(message)) else current
        }
        if (journaled.state.applying != message) return
        val latch = CountDownLatch(1)
        val allowed = AtomicBoolean(true)
        val result = AtomicReference(ClipboardApplyResult.NotAttempted)
        handler.post {
            try {
                if (allowed.compareAndSet(true, false) && active(run) && prefs.getBoolean(RCS_ENABLED, false) && direction().receives && isDefaultIme() &&
                    (!message.sensitive || prefs.getBoolean(RCS_ALLOW_SENSITIVE, false)) &&
                    gen == generation.get() && localQueue.busy.get() == 0) {
                    val clip = ClipData.newPlainText("RustClipSync", text)
                    if (Build.VERSION.SDK_INT >= 24) clip.description.extras = PersistableBundle().apply {
                        putString(MARKER, message.id)
                        if (message.sensitive) putBoolean("android.content.extra.IS_SENSITIVE", true)
                        if (Build.VERSION.SDK_INT >= 34) putBoolean("android.content.extra.IS_REMOTE_DEVICE", true)
                    }
                    result.set(applyClipboardWithEcho(message.id, SystemClock::elapsedRealtime, { echo.set(it) }) {
                        clipboard.setPrimaryClip(clip)
                    })
                }
            } catch (_: Exception) { echo.set(null) }
            finally { latch.countDown() }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) {
            allowed.set(false)
            // Keep the journal if the main thread may already have started the write.
            if (!latch.await(1, TimeUnit.SECONDS)) { status("storage_error"); return }
        }
        store.update { current ->
            if (same(session, current) && current.state.applying == message)
                current.copy(state = current.state.finishApply(message.id, result.get())) else current
        }
    }

    private fun sleep(seconds: Long): Boolean = try { Thread.sleep(seconds * 1_000); true }
        catch (_: InterruptedException) { false }

    private fun deferSend() {
        nextSendAt = SystemClock.elapsedRealtime() + sendBackoff * 1_000
        sendBackoff = (sendBackoff * 2).coerceAtMost(30)
    }

    private fun status(value: String) { prefs.edit().putString(RCS_STATUS, value).apply() }
    private fun connectedStatus(value: String = "connected") {
        if (prefs.getString(RCS_STATUS, "") !in setOf("conflict", "expired", "recovering", "re_pair", "storage_error", "protocol_unsupported", "logout_unconfirmed", "session_revoked")) status(value)
    }

    private data class LocalUpdate(val text: String?, val sensitive: Boolean, val run: Long, val generation: Long)
    private class LocalQueue {
        var latest: LocalUpdate? = null
        var scheduled = false
        val busy = AtomicInteger()
    }
    private companion object {
        const val MARKER = "io.github.infinifar.rustclipsync.message"
        const val MAX_RETRY_MS = 7L * 24 * 60 * 60 * 1_000
        const val CLOCK_SKEW_MS = 5L * 60 * 1_000
    }
}
