/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.utils

import android.content.ClipDescription
import android.os.Build

/** The API-33 constant and the older literal identify the same Android flag. */
@Suppress("DEPRECATION")
internal fun clipboardIsSensitive(description: ClipDescription?): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
    return try {
        val extras = description?.extras ?: return false
        val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"
        if (!extras.containsKey(key)) false else extras[key] as? Boolean ?: true
    } catch (_: Exception) {
        // Unreadable or malformed metadata must not silently become public text.
        true
    }
}
