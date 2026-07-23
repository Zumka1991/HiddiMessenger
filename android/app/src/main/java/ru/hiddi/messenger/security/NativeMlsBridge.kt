package ru.hiddi.messenger.security

import android.content.Context

/**
 * Deliberately tiny JNI boundary for the Rust/OpenMLS core.
 *
 * The app remains operational if a development APK was built without the native library, but
 * group creation will stay disabled. No Java string, message plaintext, or key is accepted here.
 */
object NativeMlsBridge {
    private val loaded: Boolean = runCatching {
        System.loadLibrary("hiddi_group_mls_core")
        true
    }.getOrDefault(false)

    val isAvailable: Boolean get() = loaded

    fun isValidEnvelope(encoded: ByteArray): Boolean = loaded && nativeIsValidEnvelope(encoded)

    /** Prepares the encrypted OpenMLS SQLite storage for this app process. */
    fun preparePersistentStorage(context: Context): Boolean =
        loaded && runCatching {
            nativeConfigureStorageKey(MlsStorageKeyStore(context.applicationContext).loadOrCreate())
        }.getOrDefault(false)

    private external fun nativeIsValidEnvelope(encoded: ByteArray): Boolean
    private external fun nativeConfigureStorageKey(key: ByteArray): Boolean
}
