/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard.db

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.fcitx.fcitx5.android.utils.timestamp
import org.fcitx.fcitx5.android.utils.clipboardIsSensitive

@Entity(tableName = ClipboardEntry.TABLE_NAME)
data class ClipboardEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val text: String,
    val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "-1")
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = ClipDescription.MIMETYPE_TEXT_PLAIN)
    val type: String = ClipDescription.MIMETYPE_TEXT_PLAIN,
    @ColumnInfo(defaultValue = "0")
    val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val sensitive: Boolean = false
) {
    /** Editing and copying a marked history entry must not declassify it. */
    fun toClipData(value: String = text): ClipData = ClipData.newPlainText("", value).apply {
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
    }

    companion object {
        const val BULLET = "•"

        const val TABLE_NAME = "clipboard"

        fun fromClipData(
            clipData: ClipData,
            transformer: ((String) -> String)? = null
        ): ClipboardEntry? {
            val desc = clipData.description
            // TODO: handle multiple items (when does this happen?)
            val item = clipData.getItemAt(0) ?: return null
            val str = item.text?.toString() ?: return null
            val sensitive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                clipboardIsSensitive(desc)
            } else {
                false
            }
            return ClipboardEntry(
                text = if (transformer != null) transformer(str) else str,
                timestamp = clipData.timestamp(),
                type = desc.getMimeType(0),
                sensitive = sensitive
            )
        }
    }
}
