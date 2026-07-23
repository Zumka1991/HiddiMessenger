package ru.hiddi.messenger.security

import android.content.Context
import java.nio.ByteBuffer

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

    /** Prepares and opens the encrypted OpenMLS SQLite storage for this app process. */
    fun preparePersistentStorage(context: Context): Boolean =
        loaded && runCatching {
            nativeConfigureStorageKey(MlsStorageKeyStore(context.applicationContext).loadOrCreate()) &&
                nativeInitializePersistentStorage(
                    context.applicationContext.noBackupFilesDir.resolve("group-mls.sqlite").absolutePath,
                )
        }.getOrDefault(false)

    /** Creates a local one-member MLS group; transport is handled separately. */
    fun createLocalGroup(deviceId: String): ByteArray? =
        if (loaded) nativeCreateLocalGroup(deviceId.encodeToByteArray()) else null

    /** Explicit deletion only; failed network registration must be retried, not erased. */
    fun deleteLocalGroup(groupId: ByteArray): Boolean = loaded && nativeDeleteLocalGroup(groupId)

    /** Public MLS KeyPackage; its matching private state remains in encrypted native storage. */
    fun createKeyPackage(deviceId: String): ByteArray? =
        if (loaded) nativeCreateKeyPackage(deviceId.encodeToByteArray()) else null

    /**
     * Validates a public KeyPackage in OpenMLS, advances the local epoch and
     * returns distinct opaque envelopes for existing members and the invitee.
     */
    fun addMember(groupId: ByteArray, keyPackage: ByteArray): MlsAddMemberResult? =
        if (!loaded) {
            null
        } else {
            nativeAddMember(groupId, keyPackage)?.let(MlsAddMemberResult::decode)
        }

    /** Advances the local epoch and returns an authenticated Remove Commit. */
    fun removeMember(groupId: ByteArray, memberDeviceId: String): ByteArray? =
        if (loaded && memberDeviceId.isNotBlank()) {
            nativeRemoveMember(groupId, memberDeviceId.encodeToByteArray())
                ?.takeIf(::isValidEnvelope)
        } else {
            null
        }

    /** Applies a cryptographically valid Welcome and persists the joined MLS group. */
    fun processWelcome(welcomeEnvelope: ByteArray): ByteArray? =
        if (loaded && isValidEnvelope(welcomeEnvelope)) {
            nativeProcessWelcome(welcomeEnvelope)
        } else {
            null
        }

    fun createApplicationMessage(groupId: ByteArray, plaintext: ByteArray): ByteArray? =
        if (loaded && plaintext.isNotEmpty()) {
            nativeCreateApplicationMessage(groupId, plaintext)
        } else {
            null
        }

    fun processApplicationMessage(groupId: ByteArray, envelope: ByteArray): ByteArray? =
        if (loaded && isValidEnvelope(envelope)) {
            nativeProcessApplicationMessage(groupId, envelope)
        } else {
            null
        }

    fun processCommit(groupId: ByteArray, envelope: ByteArray): Boolean =
        loaded && isValidEnvelope(envelope) && nativeProcessCommit(groupId, envelope)

    private external fun nativeIsValidEnvelope(encoded: ByteArray): Boolean
    private external fun nativeConfigureStorageKey(key: ByteArray): Boolean
    private external fun nativeInitializePersistentStorage(path: String): Boolean
    private external fun nativeCreateLocalGroup(deviceIdentity: ByteArray): ByteArray?
    private external fun nativeDeleteLocalGroup(groupId: ByteArray): Boolean
    private external fun nativeCreateKeyPackage(deviceIdentity: ByteArray): ByteArray?
    private external fun nativeAddMember(groupId: ByteArray, keyPackage: ByteArray): ByteArray?
    private external fun nativeRemoveMember(groupId: ByteArray, memberIdentity: ByteArray): ByteArray?
    private external fun nativeProcessWelcome(welcomeEnvelope: ByteArray): ByteArray?
    private external fun nativeCreateApplicationMessage(groupId: ByteArray, plaintext: ByteArray): ByteArray?
    private external fun nativeProcessApplicationMessage(groupId: ByteArray, envelope: ByteArray): ByteArray?
    private external fun nativeProcessCommit(groupId: ByteArray, envelope: ByteArray): Boolean
}

data class MlsAddMemberResult(
    val commitEnvelope: ByteArray,
    val welcomeEnvelope: ByteArray,
) {
    companion object {
        private const val BUNDLE_VERSION: Byte = 1

        fun decode(encoded: ByteArray): MlsAddMemberResult {
            require(encoded.size >= 8) { "Повреждён MLS add-member bundle" }
            val buffer = ByteBuffer.wrap(encoded)
            require(buffer.get() == BUNDLE_VERSION) { "Неподдерживаемая версия MLS bundle" }
            val commitSize = buffer.int
            require(commitSize in 3..buffer.remaining() - 3) { "Некорректный MLS Commit" }
            val commit = ByteArray(commitSize).also(buffer::get)
            val welcome = ByteArray(buffer.remaining()).also(buffer::get)
            require(NativeMlsBridge.isValidEnvelope(commit)) { "Некорректный MLS Commit envelope" }
            require(NativeMlsBridge.isValidEnvelope(welcome)) { "Некорректный MLS Welcome envelope" }
            return MlsAddMemberResult(commit, welcome)
        }
    }
}
