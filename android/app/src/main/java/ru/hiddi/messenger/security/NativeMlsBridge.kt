package ru.hiddi.messenger.security

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

    private external fun nativeIsValidEnvelope(encoded: ByteArray): Boolean
}
