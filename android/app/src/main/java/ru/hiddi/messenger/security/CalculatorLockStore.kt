package ru.hiddi.messenger.security

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.json.JSONObject

/** PIN gate metadata is itself Keystore-encrypted; a PIN is never stored. */
class CalculatorLockStore(context: Context) {
    private val store = AndroidKeystoreSecretStore(
        context.applicationContext,
        fileName = "calculator-lock.v1",
        keyAlias = "ru.hiddi.messenger.calculator-lock.v1",
    )

    fun enabled(): Boolean = read()?.optBoolean("enabled", false) == true

    fun setPin(pin: CharArray) {
        require(pin.size in 4..12 && pin.all(Char::isDigit)) { "PIN должен содержать от 4 до 12 цифр" }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val hash = derive(pin, salt)
        try {
            write(JSONObject().put("enabled", true).put("salt", Base64.encodeToString(salt, Base64.NO_WRAP)).put("hash", Base64.encodeToString(hash, Base64.NO_WRAP)))
        } finally {
            pin.fill('\u0000'); salt.fill(0); hash.fill(0)
        }
    }

    fun verify(pin: CharArray): Boolean {
        val value = read() ?: return false
        val salt = Base64.decode(value.getString("salt"), Base64.NO_WRAP)
        val expected = Base64.decode(value.getString("hash"), Base64.NO_WRAP)
        val actual = derive(pin, salt)
        return try { actual.contentEquals(expected) } finally { pin.fill('\u0000'); salt.fill(0); expected.fill(0); actual.fill(0) }
    }

    fun disable() = store.delete()

    private fun read(): JSONObject? = store.read()?.let { bytes -> try { JSONObject(bytes.decodeToString()) } finally { bytes.fill(0) } }
    private fun write(value: JSONObject) { val bytes = value.toString().encodeToByteArray(); try { store.write(bytes) } finally { bytes.fill(0) } }
    private fun derive(pin: CharArray, salt: ByteArray): ByteArray = PBEKeySpec(pin, salt, ITERATIONS, HASH_BYTES * 8).let { spec ->
        try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    private companion object { const val SALT_BYTES = 16; const val HASH_BYTES = 32; const val ITERATIONS = 210_000 }
}
