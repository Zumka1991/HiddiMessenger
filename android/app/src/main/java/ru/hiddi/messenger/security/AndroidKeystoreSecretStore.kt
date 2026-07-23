package ru.hiddi.messenger.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.util.AtomicFile
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts private Signal records at rest; the AES key is non-exportable. */
class AndroidKeystoreSecretStore(
    context: Context,
    fileName: String = "signal-private-material.v1",
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) {
    private val file = AtomicFile(context.noBackupFilesDir.resolve(fileName))

    fun read(): ByteArray? {
        if (!file.baseFile.exists()) return null
        val stored = file.openRead().use { it.readBytes() }
        require(stored.size >= 14) { "Encrypted Signal material is truncated" }
        val buffer = ByteBuffer.wrap(stored)
        require(buffer.get() == FORMAT_VERSION) { "Unsupported Signal material format" }
        val ivSize = buffer.get().toInt() and 0xff
        require(ivSize in 12..16 && buffer.remaining() > ivSize) { "Invalid Signal material IV" }
        val iv = ByteArray(ivSize).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        return cipher(Cipher.DECRYPT_MODE, iv).doFinal(encrypted)
    }

    fun write(plainText: ByteArray) {
        val encryptor = cipher(Cipher.ENCRYPT_MODE)
        val encrypted = encryptor.doFinal(plainText)
        val output = file.startWrite()
        try {
            output.write(byteArrayOf(FORMAT_VERSION, encryptor.iv.size.toByte()))
            output.write(encryptor.iv)
            output.write(encrypted)
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    fun delete() {
        file.delete()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) {
            keyStore.deleteEntry(keyAlias)
        }
    }

    private fun cipher(mode: Int, iv: ByteArray? = null): Cipher = Cipher.getInstance(AES_GCM).apply {
        if (iv == null) init(mode, getOrCreateKey()) else init(mode, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "ru.hiddi.messenger.signal-storage.v1"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val FORMAT_VERSION: Byte = 1
        const val TAG_BITS = 128
    }
}
