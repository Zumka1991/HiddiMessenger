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
    fun addMember(
        groupId: ByteArray,
        keyPackage: ByteArray,
        operationId: String,
        context: ByteArray,
    ): MlsAddMemberResult? =
        if (!loaded) {
            null
        } else {
            nativeAddMember(groupId, keyPackage, operationId, context)?.let(MlsAddMemberResult::decode)
        }

    /** Advances the local epoch and returns an authenticated Remove Commit. */
    fun removeMember(
        groupId: ByteArray,
        memberDeviceId: String,
        operationId: String,
        context: ByteArray,
    ): ByteArray? =
        if (loaded && memberDeviceId.isNotBlank()) {
            nativeRemoveMember(
                groupId,
                memberDeviceId.encodeToByteArray(),
                operationId,
                context,
            )
                ?.takeIf(::isValidEnvelope)
        } else {
            null
        }

    fun pendingMembershipOperations(): List<PendingMlsMembershipOperation> =
        if (loaded) {
            nativePendingMembershipOperations()?.let(PendingMlsMembershipOperation::decodeAll)
                ?: error("Не удалось прочитать журнал MLS")
        } else {
            emptyList()
        }

    fun acknowledgeMembershipOperation(operationId: String): Boolean =
        loaded && nativeAckMembershipOperation(operationId)

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
    private external fun nativeAddMember(
        groupId: ByteArray,
        keyPackage: ByteArray,
        operationId: String,
        context: ByteArray,
    ): ByteArray?
    private external fun nativeRemoveMember(
        groupId: ByteArray,
        memberIdentity: ByteArray,
        operationId: String,
        context: ByteArray,
    ): ByteArray?
    private external fun nativePendingMembershipOperations(): ByteArray?
    private external fun nativeAckMembershipOperation(operationId: String): Boolean
    private external fun nativeProcessWelcome(welcomeEnvelope: ByteArray): ByteArray?
    private external fun nativeCreateApplicationMessage(groupId: ByteArray, plaintext: ByteArray): ByteArray?
    private external fun nativeProcessApplicationMessage(groupId: ByteArray, envelope: ByteArray): ByteArray?
    private external fun nativeProcessCommit(groupId: ByteArray, envelope: ByteArray): Boolean
}

enum class PendingMlsMembershipKind { ADD_MEMBER, REMOVE_MEMBER }

data class PendingMlsMembershipOperation(
    val operationId: String,
    val kind: PendingMlsMembershipKind,
    val groupId: ByteArray,
    val context: ByteArray,
    val commitEnvelope: ByteArray,
    val welcomeEnvelope: ByteArray?,
) {
    companion object {
        private const val JOURNAL_VERSION: Byte = 1

        fun decodeAll(encoded: ByteArray): List<PendingMlsMembershipOperation> {
            val buffer = ByteBuffer.wrap(encoded)
            require(buffer.remaining() >= 5 && buffer.get() == JOURNAL_VERSION) {
                "Повреждён журнал MLS"
            }
            val count = buffer.int
            require(count in 0..10_000) { "Некорректное число записей MLS" }
            val result = buildList(count) {
                repeat(count) {
                    val id = buffer.bytes(buffer.unsignedShort()).decodeToString()
                    val kind = when (buffer.get().toInt()) {
                        1 -> PendingMlsMembershipKind.ADD_MEMBER
                        2 -> PendingMlsMembershipKind.REMOVE_MEMBER
                        else -> error("Некорректный тип операции MLS")
                    }
                    val groupId = buffer.bytes(buffer.unsignedShort())
                    val context = buffer.bytes(buffer.positiveInt())
                    val commit = buffer.bytes(buffer.positiveInt())
                    val welcomeSize = buffer.positiveInt()
                    val welcome = if (welcomeSize == 0) null else buffer.bytes(welcomeSize)
                    require(NativeMlsBridge.isValidEnvelope(commit)) { "Повреждён MLS Commit" }
                    require(welcome == null || NativeMlsBridge.isValidEnvelope(welcome)) {
                        "Повреждён MLS Welcome"
                    }
                    add(
                        PendingMlsMembershipOperation(
                            id,
                            kind,
                            groupId,
                            context,
                            commit,
                            welcome,
                        ),
                    )
                }
            }
            require(!buffer.hasRemaining()) { "Лишние данные в журнале MLS" }
            return result
        }

        private fun ByteBuffer.unsignedShort(): Int {
            require(remaining() >= Short.SIZE_BYTES) { "Обрезан журнал MLS" }
            return short.toInt() and 0xffff
        }

        private fun ByteBuffer.positiveInt(): Int {
            require(remaining() >= Int.SIZE_BYTES) { "Обрезан журнал MLS" }
            return int.also { require(it >= 0) { "Некорректная длина MLS" } }
        }

        private fun ByteBuffer.bytes(size: Int): ByteArray {
            require(size <= remaining()) { "Обрезан журнал MLS" }
            return ByteArray(size).also(::get)
        }
    }
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
