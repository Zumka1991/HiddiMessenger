package ru.hiddi.messenger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Copies one-time credentials without exposing a preview to keyboards or the
 * Android clipboard overlay. The value is removed after a short handoff window.
 */
internal fun copySensitiveText(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText(label, value).apply {
        description.extras = PersistableBundle().apply {
            putBoolean(SENSITIVE_CLIP_EXTRA, true)
        }
    }
    clipboard.setPrimaryClip(clip)

    Handler(Looper.getMainLooper()).postDelayed(
        {
            val current = clipboard.primaryClip
            if (
                current?.itemCount == 1 &&
                current.getItemAt(0).coerceToText(context).toString() == value
            ) {
                clipboard.clearPrimaryClip()
            }
        },
        CLIPBOARD_CLEAR_DELAY_MILLIS,
    )
}

private const val SENSITIVE_CLIP_EXTRA = "android.content.extra.IS_SENSITIVE"
private const val CLIPBOARD_CLEAR_DELAY_MILLIS = 60_000L
