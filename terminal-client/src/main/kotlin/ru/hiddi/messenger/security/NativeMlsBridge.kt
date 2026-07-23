package ru.hiddi.messenger.security

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Desktop JNI boundary for the same Rust/OpenMLS core used by Android.
 *
 * The native library is loaded from HIDDI_MLS_LIBRARY when packaged, or from
 * the repository build directory during development.
 */
object NativeMlsBridge {
    private val loaded = runCatching {
        val configured = System.getenv("HIDDI_MLS_LIBRARY")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
        val candidates = listOfNotNull(
            configured,
            Path.of("group-mls-core", "target", "release", libraryName()),
            Path.of("..", "group-mls-core", "target", "release", libraryName()),
        ).map { it.toAbsolutePath().normalize() }
        val library = candidates.firstOrNull(Files::isRegularFile)
            ?: error("Rust/OpenMLS library not found. Run `cargo build --release` in group-mls-core.")
        System.load(library.toString())
        true
    }.getOrDefault(false)

    val isAvailable: Boolean get() = loaded

    fun initialize(storageKey: ByteArray, path: Path): Boolean =
        loaded &&
            storageKey.size == 64 &&
            nativeConfigureStorageKey(storageKey) &&
            nativeInitializePersistentStorage(path.toAbsolutePath().normalize().toString())

    fun createLocalGroup(deviceId: String): ByteArray? =
        if (loaded) nativeCreateLocalGroup(deviceId.encodeToByteArray()) else null

    fun deleteLocalGroup(groupId: ByteArray): Boolean =
        loaded && nativeDeleteLocalGroup(groupId)

    fun createKeyPackage(deviceId: String): ByteArray? =
        if (loaded) nativeCreateKeyPackage(deviceId.encodeToByteArray()) else null

    fun addMember(groupId: ByteArray, keyPackage: ByteArray): MlsAddMemberResult? =
        if (loaded) nativeAddMember(groupId, keyPackage)?.let(MlsAddMemberResult::decode) else null

    fun removeMember(groupId: ByteArray, memberDeviceId: String): ByteArray? =
        if (loaded && memberDeviceId.isNotBlank()) {
            nativeRemoveMember(groupId, memberDeviceId.encodeToByteArray())
                ?.takeIf(::nativeIsValidEnvelope)
        } else {
            null
        }

    fun processWelcome(envelope: ByteArray): ByteArray? =
        if (loaded && nativeIsValidEnvelope(envelope)) nativeProcessWelcome(envelope) else null

    fun createApplicationMessage(groupId: ByteArray, plaintext: ByteArray): ByteArray? =
        if (loaded && plaintext.isNotEmpty()) nativeCreateApplicationMessage(groupId, plaintext) else null

    fun processApplicationMessage(groupId: ByteArray, envelope: ByteArray): ByteArray? =
        if (loaded && nativeIsValidEnvelope(envelope)) {
            nativeProcessApplicationMessage(groupId, envelope)
        } else {
            null
        }

    fun processCommit(groupId: ByteArray, envelope: ByteArray): Boolean =
        loaded && nativeIsValidEnvelope(envelope) && nativeProcessCommit(groupId, envelope)

    private fun libraryName(): String = when {
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) ->
            "hiddi_group_mls_core.dll"
        System.getProperty("os.name").contains("Mac", ignoreCase = true) ->
            "libhiddi_group_mls_core.dylib"
        else -> "libhiddi_group_mls_core.so"
    }

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
        fun decode(encoded: ByteArray): MlsAddMemberResult {
            require(encoded.size >= 8 && encoded[0] == 1.toByte()) { "Повреждён MLS add-member bundle" }
            val input = ByteBuffer.wrap(encoded, 1, encoded.size - 1)
            val commitSize = input.int
            require(commitSize in 3..input.remaining() - 3) { "Некорректный MLS Commit" }
            val commit = ByteArray(commitSize).also(input::get)
            val welcome = ByteArray(input.remaining()).also(input::get)
            return MlsAddMemberResult(commit, welcome)
        }
    }
}
