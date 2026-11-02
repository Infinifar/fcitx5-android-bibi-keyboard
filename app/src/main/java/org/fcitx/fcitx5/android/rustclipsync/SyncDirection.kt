/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.rustclipsync

/** A pause is separate from direction, so it never changes the user's selection. */
internal enum class SyncDirection {
    PUSH_ONLY, RECEIVE_ONLY, BIDIRECTIONAL;
    val sends get() = this != RECEIVE_ONLY
    val receives get() = this != PUSH_ONLY
    companion object {
        fun preference(value: String?): SyncDirection = entries.firstOrNull { it.name == value } ?: BIDIRECTIONAL
    }
}

internal data class DirectionControl(
    val sessionId: String,
    val version: Long,
    val direction: SyncDirection,
    val paused: Boolean,
)

/** Persist before sending; WS failure retries this exact operation over HTTP. */
internal data class DirectionOperation(
    val sessionId: String,
    val version: Long,
    val direction: SyncDirection,
    val paused: Boolean,
    val connect: Boolean,
    val revoke: Boolean = false,
)

internal const val MAX_CONTROL_VERSION = 9_007_199_254_740_991L
internal const val PROTOCOL_VERSION = "3"
