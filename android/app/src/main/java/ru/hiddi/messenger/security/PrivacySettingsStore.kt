package ru.hiddi.messenger.security

import android.content.Context
import org.json.JSONObject

/** Privacy preferences encrypted by a non-exportable Android Keystore key. */
class PrivacySettingsStore(context: Context) {
    private val store =
        AndroidKeystoreSecretStore(
            context.applicationContext,
            fileName = "privacy-settings.v1",
        )

    @Synchronized
    fun invisibleMode(): Boolean {
        val plain = store.read() ?: return false
        return try {
            JSONObject(plain.decodeToString()).optBoolean("invisible_mode", false)
        } finally {
            plain.fill(0)
        }
    }

    @Synchronized
    fun setInvisibleMode(enabled: Boolean) {
        val plain = JSONObject().put("invisible_mode", enabled).toString().encodeToByteArray()
        try {
            store.write(plain)
        } finally {
            plain.fill(0)
        }
    }
}
