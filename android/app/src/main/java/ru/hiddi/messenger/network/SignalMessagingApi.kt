package ru.hiddi.messenger.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import ru.hiddi.messenger.security.AndroidSignalProtocolStore
import ru.hiddi.messenger.security.NativeMlsBridge
import ru.hiddi.messenger.security.SignalStateRepository
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.security.MessageDigest

class SignalMessagingApi(private val repository: SignalStateRepository) {
    suspend fun uploadMlsKeyPackage(profile: AccountProfile, keyPackage: ByteArray) = withContext(Dispatchers.IO) {
        require(keyPackage.isNotEmpty()) { "Пустой MLS KeyPackage" }
        request(
            "PUT",
            "${profile.serverUrl}/v1/groups/key-package",
            JSONObject().put("key_package", keyPackage.b64()).toString(),
            profile.accessToken,
        )
    }

    suspend fun takeMlsKeyPackage(profile: AccountProfile, nickname: String): ByteArray = withContext(Dispatchers.IO) {
        val normalized = nickname.trim().removePrefix("@").lowercase()
        JSONObject(
            request(
                "GET",
                "${profile.serverUrl}/v1/users/${URLEncoder.encode(normalized, Charsets.UTF_8.name())}/mls-key-package",
                null,
                profile.accessToken,
            ),
        ).getString("key_package").decode()
    }

    /** Sends only group routing metadata; MLS state and keys never leave the native core. */
    suspend fun createGroup(
        profile: AccountProfile,
        mlsGroupId: ByteArray,
        members: List<GroupMember>,
    ): String = withContext(Dispatchers.IO) {
        require(mlsGroupId.size in 8..64) { "Некорректный MLS group id" }
        require(members.size <= 31) { "Слишком много участников группы" }
        val memberJson = JSONArray().also { output ->
            members.forEach { member ->
                output.put(
                    JSONObject()
                        .put("nickname", member.nickname.trim().removePrefix("@").lowercase())
                        .put("role", member.role),
                )
            }
        }
        JSONObject(
            request(
                "POST",
                "${profile.serverUrl}/v1/groups",
                JSONObject()
                    .put("group_id", mlsGroupId.b64())
                    .put("members", memberJson)
                    .toString(),
                profile.accessToken,
            ),
        ).getString("group_id")
    }

    suspend fun groupDetails(profile: AccountProfile, groupId: ByteArray): GroupDetails =
        withContext(Dispatchers.IO) {
            val response = JSONObject(
                request(
                    "GET",
                    "${profile.serverUrl}/v1/groups/${groupId.b64()}",
                    null,
                    profile.accessToken,
                ),
            )
            GroupDetails(
                groupId = response.getString("group_id").decode(),
                ownerNickname = response.getString("owner_nickname"),
                members = response.getJSONArray("members").let { members ->
                    (0 until members.length()).map { index ->
                        members.getJSONObject(index).let {
                            GroupMember(
                                nickname = it.getString("nickname"),
                                role = it.getString("role"),
                                deviceId = it.getString("device_id"),
                            )
                        }
                    }
                },
            )
        }

    suspend fun addGroupMember(
        profile: AccountProfile,
        groupId: ByteArray,
        member: GroupMember,
    ) = withContext(Dispatchers.IO) {
        request(
            "POST",
            "${profile.serverUrl}/v1/groups/${groupId.b64()}/members",
            JSONObject()
                .put("nickname", member.nickname.trim().removePrefix("@").lowercase())
                .put("role", member.role)
                .toString(),
            profile.accessToken,
        )
    }

    suspend fun updateGroupMemberRole(
        profile: AccountProfile,
        groupId: ByteArray,
        nickname: String,
        role: String,
    ) = withContext(Dispatchers.IO) {
        request(
            "PUT",
            "${profile.serverUrl}/v1/groups/${groupId.b64()}/members/" +
                "${nickname.trim().removePrefix("@").lowercase()}/role",
            JSONObject().put("role", role).toString(),
            profile.accessToken,
        )
    }

    suspend fun deleteGroup(profile: AccountProfile, groupId: ByteArray) =
        withContext(Dispatchers.IO) {
            request(
                "DELETE",
                "${profile.serverUrl}/v1/groups/${groupId.b64()}",
                null,
                profile.accessToken,
            )
        }

    suspend fun deleteGroupMessage(
        profile: AccountProfile,
        groupId: ByteArray,
        clientEventId: String,
    ) = withContext(Dispatchers.IO) {
        request(
            "DELETE",
            "${profile.serverUrl}/v1/groups/${groupId.b64()}/messages/$clientEventId",
            null,
            profile.accessToken,
        )
    }

    suspend fun pendingGroupDeletions(profile: AccountProfile): List<GroupDeletion> =
        withContext(Dispatchers.IO) {
            val response = JSONArray(
                request(
                    "GET",
                    "${profile.serverUrl}/v1/groups/deletions",
                    null,
                    profile.accessToken,
                ),
            )
            List(response.length()) { index ->
                response.getJSONObject(index).let {
                    GroupDeletion(
                        deletionId = it.getString("deletion_id"),
                        groupId = it.getString("group_id").decode(),
                    )
                }
            }
        }

    suspend fun acknowledgeGroupDeletion(profile: AccountProfile, deletionId: String) =
        withContext(Dispatchers.IO) {
            request(
                "POST",
                "${profile.serverUrl}/v1/groups/deletions/$deletionId",
                null,
                profile.accessToken,
            )
        }

    suspend fun sendGroupEvent(
        profile: AccountProfile,
        groupId: ByteArray,
        clientEventId: String,
        kind: Int,
        recipients: List<String>,
        envelope: ByteArray,
        removeMemberNickname: String? = null,
    ): List<String> = withContext(Dispatchers.IO) {
        require(groupId.size in 8..64) { "Некорректный MLS group id" }
        require(clientEventId.isNotBlank()) { "Некорректный client event id" }
        require(kind in 1..3) { "Некорректный тип MLS event" }
        require(recipients.isNotEmpty() && recipients.size <= 32) { "Некорректные адресаты MLS event" }
        require(NativeMlsBridge.isValidEnvelope(envelope)) { "Некорректный MLS envelope" }
        val recipientJson = JSONArray().also { output ->
            recipients.forEach { output.put(it.trim().removePrefix("@").lowercase()) }
        }
        JSONObject(
            request(
                "POST",
                "${profile.serverUrl}/v1/groups/${groupId.b64()}/events",
                JSONObject()
                    .put("client_event_id", clientEventId)
                    .put("kind", kind)
                    .put("recipient_nicknames", recipientJson)
                    .put("envelope", envelope.b64())
                    .put("remove_member_nickname", removeMemberNickname)
                    .toString(),
                profile.accessToken,
            ),
        ).getJSONArray("event_ids").let { ids ->
            (0 until ids.length()).map(ids::getString)
        }
    }

    suspend fun groupEventInbox(profile: AccountProfile): List<GroupEvent> = withContext(Dispatchers.IO) {
        JSONArray(request("GET", "${profile.serverUrl}/v1/groups/events", null, profile.accessToken))
            .let { events ->
                (0 until events.length()).map { index ->
                    events.getJSONObject(index).let { event ->
                        GroupEvent(
                            eventId = event.getString("event_id"),
                            groupId = event.getString("group_id").decode(),
                            senderNickname = event.getString("sender_nickname"),
                            kind = event.getInt("kind"),
                            envelope = event.getString("envelope").decode(),
                            createdAt = event.getString("created_at"),
                            removesRecipient = event.optBoolean("removes_recipient"),
                        )
                    }
                }
            }
    }

    suspend fun acknowledgeGroupEvent(profile: AccountProfile, eventId: String) = withContext(Dispatchers.IO) {
        request(
            "POST",
            "${profile.serverUrl}/v1/groups/events/$eventId",
            null,
            profile.accessToken,
        )
    }

    suspend fun waitForGroupEvent(profile: AccountProfile): Boolean = withContext(Dispatchers.IO) {
        JSONObject(
            request(
                "GET",
                "${profile.serverUrl}/v1/groups/events/wait",
                null,
                profile.accessToken,
            ),
        ).getBoolean("available")
    }

    suspend fun currentDeviceId(profile: AccountProfile): String = withContext(Dispatchers.IO) {
        JSONObject(
            request(
                "GET",
                "${profile.serverUrl}/v1/devices/current",
                null,
                profile.accessToken,
            ),
        ).getString("device_id")
    }

    suspend fun findUsers(profile: AccountProfile, nickname: String): List<UserSearchResult> = withContext(Dispatchers.IO) {
        val normalized = nickname.trim().removePrefix("@").lowercase()
        val encodedQuery = URLEncoder.encode(normalized, Charsets.UTF_8.name())
        JSONArray(request("GET", "${profile.serverUrl}/v1/users?query=$encodedQuery", null, profile.accessToken))
            .let { items ->
                (0 until items.length()).map { index ->
                    items.getJSONObject(index).userProfile()
                }
            }
    }

    suspend fun currentProfile(profile: AccountProfile): UserSearchResult = withContext(Dispatchers.IO) {
        JSONObject(
            request("GET", "${profile.serverUrl}/v1/profile", null, profile.accessToken),
        ).userProfile()
    }

    suspend fun userProfile(profile: AccountProfile, nickname: String): UserSearchResult =
        withContext(Dispatchers.IO) {
            val normalized = nickname.trim().removePrefix("@").lowercase()
            val encoded = URLEncoder.encode(normalized, Charsets.UTF_8.name())
            JSONObject(
                request("GET", "${profile.serverUrl}/v1/users/$encoded", null, profile.accessToken),
            ).userProfile()
        }

    suspend fun updateProfile(
        profile: AccountProfile,
        displayName: String,
        bio: String,
    ): UserSearchResult = withContext(Dispatchers.IO) {
        JSONObject(
            request(
                "PUT",
                "${profile.serverUrl}/v1/profile",
                JSONObject()
                    .put("display_name", displayName)
                    .put("bio", bio)
                    .toString(),
                profile.accessToken,
            ),
        ).userProfile()
    }

    suspend fun uploadAvatar(profile: AccountProfile, jpeg: ByteArray): String =
        withContext(Dispatchers.IO) {
            require(jpeg.isNotEmpty() && jpeg.size <= 512 * 1024) {
                "Аватар должен быть не больше 512 КиБ"
            }
            JSONObject(
                request(
                    "PUT",
                    "${profile.serverUrl}/v1/profile/avatar",
                    JSONObject().put("image", jpeg.b64()).toString(),
                    profile.accessToken,
                ),
            ).getString("version")
        }

    suspend fun deleteAvatar(profile: AccountProfile) = withContext(Dispatchers.IO) {
        request("DELETE", "${profile.serverUrl}/v1/profile/avatar", null, profile.accessToken)
    }

    suspend fun avatar(profile: AccountProfile, nickname: String): ByteArray =
        withContext(Dispatchers.IO) {
            val normalized = nickname.trim().removePrefix("@").lowercase()
            val encoded = URLEncoder.encode(normalized, Charsets.UTF_8.name())
            JSONObject(
                request(
                    "GET",
                    "${profile.serverUrl}/v1/users/$encoded/avatar",
                    null,
                    profile.accessToken,
                ),
            ).getString("image").decode()
    }

    suspend fun blockedUsers(profile: AccountProfile): Set<String> = withContext(Dispatchers.IO) {
        JSONArray(request("GET", "${profile.serverUrl}/v1/blocks", null, profile.accessToken))
            .let { items ->
                (0 until items.length())
                    .map { items.getJSONObject(it).getString("nickname") }
                    .toSet()
            }
    }

    suspend fun blockUser(profile: AccountProfile, nickname: String) = withContext(Dispatchers.IO) {
        val normalized = nickname.trim().removePrefix("@").lowercase()
        val encoded = URLEncoder.encode(normalized, Charsets.UTF_8.name())
        request("PUT", "${profile.serverUrl}/v1/blocks/$encoded", null, profile.accessToken)
    }

    suspend fun unblockUser(profile: AccountProfile, nickname: String) = withContext(Dispatchers.IO) {
        val normalized = nickname.trim().removePrefix("@").lowercase()
        val encoded = URLEncoder.encode(normalized, Charsets.UTF_8.name())
        request("DELETE", "${profile.serverUrl}/v1/blocks/$encoded", null, profile.accessToken)
    }

    suspend fun serverReachable(profile: AccountProfile): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            JSONObject(request("GET", "${profile.serverUrl}/health", null, null)).getString("status") == "ok"
        }.getOrDefault(false)
    }

    suspend fun waitForIncoming(profile: AccountProfile): Boolean = withContext(Dispatchers.IO) {
        JSONObject(request("GET", "${profile.serverUrl}/v1/messages/wait", null, profile.accessToken)).getBoolean("available")
    }

    suspend fun uploadAttachment(profile: AccountProfile, recipient: String, ciphertext: ByteArray): String =
        withContext(Dispatchers.IO) {
            val normalized = recipient.trim().removePrefix("@").lowercase()
            JSONObject(
                request(
                    "POST",
                    "${profile.serverUrl}/v1/attachments",
                    JSONObject()
                        .put("recipient_nickname", normalized)
                        .put("ciphertext", ciphertext.b64())
                        .toString(),
                    profile.accessToken,
                ),
            ).getString("attachment_id")
        }

    suspend fun uploadGroupAttachment(
        profile: AccountProfile,
        groupId: ByteArray,
        ciphertext: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        JSONObject(
            request(
                "POST",
                "${profile.serverUrl}/v1/groups/${groupId.b64()}/attachments",
                JSONObject().put("ciphertext", ciphertext.b64()).toString(),
                profile.accessToken,
            ),
        ).getString("attachment_id")
    }

    suspend fun downloadAttachment(profile: AccountProfile, attachmentId: String): ByteArray =
        withContext(Dispatchers.IO) {
            JSONObject(
                request(
                    "GET",
                    "${profile.serverUrl}/v1/attachments/$attachmentId",
                    null,
                    profile.accessToken,
                ),
            ).getString("ciphertext").decode()
        }

    suspend fun deleteAttachment(profile: AccountProfile, attachmentId: String) =
        withContext(Dispatchers.IO) {
            request(
                "DELETE",
                "${profile.serverUrl}/v1/attachments/$attachmentId",
                null,
                profile.accessToken,
            )
        }

    suspend fun send(profile: AccountProfile, recipient: String, message: String): String = withContext(Dispatchers.IO) {
        SIGNAL_STATE_MUTEX.withLock {
            val normalized = recipient.trim().removePrefix("@").lowercase()
            val state = resolveRegistrationId(profile, repository.load())
            val store = AndroidSignalProtocolStore(state)
            val local = SignalProtocolAddress(profile.nickname, DEVICE_ID)
            val remote = SignalProtocolAddress(normalized, DEVICE_ID)
            if (!store.containsSession(remote)) SessionBuilder(store, remote, local).process(fetchBundle(profile, normalized))
            val cipher = SessionCipher(store, local, remote).encrypt(message.encodeToByteArray())
            repository.save(store.snapshot())
            val envelope = byteArrayOf(cipher.type.toByte()) + cipher.serialize()
            JSONObject(request("POST", "${profile.serverUrl}/v1/messages", JSONObject()
                .put("recipient_nickname", normalized).put("ciphertext", envelope.b64()).toString(), profile.accessToken))
                .getString("message_id")
        }
    }

    suspend fun messageStatus(profile: AccountProfile, messageId: String): DeliveryStatus = withContext(Dispatchers.IO) {
        val response = JSONObject(request("GET", "${profile.serverUrl}/v1/messages/$messageId", null, profile.accessToken))
        when {
            response.getBoolean("read") -> DeliveryStatus.READ
            response.getBoolean("delivered") -> DeliveryStatus.DELIVERED
            else -> DeliveryStatus.SENT
        }
    }

    suspend fun deleteMessageForEveryone(profile: AccountProfile, messageId: String) =
        withContext(Dispatchers.IO) {
            request(
                "DELETE",
                "${profile.serverUrl}/v1/messages/$messageId?for_everyone=true",
                null,
                profile.accessToken,
            )
        }

    suspend fun pendingMessageDeletions(profile: AccountProfile): List<MessageDeletion> =
        withContext(Dispatchers.IO) {
            val response = JSONArray(
                request(
                    "GET",
                    "${profile.serverUrl}/v1/messages/deletions",
                    null,
                    profile.accessToken,
                ),
            )
            List(response.length()) { index ->
                response.getJSONObject(index).let {
                    MessageDeletion(
                        deletionId = it.getString("deletion_id"),
                        messageId = it.getString("message_id"),
                    )
                }
            }
        }

    suspend fun acknowledgeMessageDeletion(profile: AccountProfile, deletionId: String) =
        withContext(Dispatchers.IO) {
            request(
                "POST",
                "${profile.serverUrl}/v1/messages/deletions/$deletionId",
                null,
                profile.accessToken,
            )
        }

    suspend fun markPeerMessagesRead(profile: AccountProfile, peer: String) = withContext(Dispatchers.IO) {
        val normalized = peer.trim().removePrefix("@").lowercase()
        request("POST", "${profile.serverUrl}/v1/messages/read/$normalized", null, profile.accessToken)
    }

    suspend fun deleteConversationForBoth(profile: AccountProfile, peer: String) = withContext(Dispatchers.IO) {
        val normalized = peer.trim().removePrefix("@").lowercase()
        request("DELETE", "${profile.serverUrl}/v1/conversations/$normalized", null, profile.accessToken)
    }

    suspend fun pendingConversationDeletions(profile: AccountProfile): List<String> = withContext(Dispatchers.IO) {
        val response = JSONArray(request("GET", "${profile.serverUrl}/v1/conversations/deletions", null, profile.accessToken))
        List(response.length()) { response.getJSONObject(it).getString("peer_nickname") }
    }

    /** Deterministic code for an out-of-band identity-key comparison. No secret leaves the device. */
    suspend fun safetyNumber(profile: AccountProfile, peer: String): String = withContext(Dispatchers.IO) {
        val normalized = peer.trim().removePrefix("@").lowercase()
        val remote = JSONObject(request("GET", "${profile.serverUrl}/v1/users/$normalized", null, profile.accessToken))
        val localPublic = IdentityKeyPair(repository.load().keys.identity).publicKey.serialize()
        val participants = listOf(
            "${profile.nickname}\u0000${localPublic.b64()}",
            "${remote.getString("nickname")}\u0000${remote.getString("identity_public_key")}",
        ).sorted()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("hiddi-safety-number-v1\u0000" + participants.joinToString("\u0000")).encodeToByteArray())
        digest.take(30).joinToString("") { "%02x".format(it) }.chunked(5).joinToString(" ")
    }

    suspend fun inbox(profile: AccountProfile): List<DecryptedMessage> = withContext(Dispatchers.IO) {
        SIGNAL_STATE_MUTEX.withLock {
            val items = JSONArray(request("GET", "${profile.serverUrl}/v1/messages", null, profile.accessToken))
            val output = mutableListOf<DecryptedMessage>()
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val state = resolveRegistrationId(profile, repository.load())
                val store = AndroidSignalProtocolStore(state)
                val sender = item.getString("sender_nickname")
                val local = SignalProtocolAddress(profile.nickname, DEVICE_ID)
                val remote = SignalProtocolAddress(sender, DEVICE_ID)
                val raw = item.getString("ciphertext").decode()
                require(raw.isNotEmpty()) { "Получен пустой encrypted envelope" }
                val plain = when (raw.first().toInt()) {
                    CiphertextMessage.PREKEY_TYPE -> SessionCipher(store, local, remote).decrypt(PreKeySignalMessage(raw.drop(1).toByteArray()))
                    CiphertextMessage.WHISPER_TYPE -> SessionCipher(store, local, remote).decrypt(SignalMessage(raw.drop(1).toByteArray()))
                    else -> error("Неизвестный тип сообщения")
                }
                repository.save(store.snapshot())
            request("POST", "${profile.serverUrl}/v1/messages/${item.getString("message_id")}", null, profile.accessToken)
                output += DecryptedMessage(
                    messageId = item.getString("message_id"),
                    senderNickname = sender,
                    text = plain.decodeToString(),
                    createdAt = item.getString("created_at"),
                )
                plain.fill(0)
            }
            output
        }
    }

    private fun fetchBundle(profile: AccountProfile, nickname: String): PreKeyBundle {
        val bundle = JSONObject(request("GET", "${profile.serverUrl}/v1/users/$nickname/prekey-bundle", null, profile.accessToken))
        val signed = bundle.getJSONObject("signed_prekey")
        val kyberSigned = bundle.getJSONObject("kyber_signed_prekey")
        val oneTime = bundle.optJSONObject("one_time_prekey")
        val kyberOneTime = bundle.optJSONObject("kyber_one_time_prekey")
        return PreKeyBundle(bundle.getInt("registration_id"), DEVICE_ID,
            oneTime?.getInt("id") ?: PreKeyBundle.NULL_PRE_KEY_ID, oneTime?.let { ECPublicKey(it.getString("public_key").decode()) },
            signed.getInt("id"), ECPublicKey(signed.getString("public_key").decode()), signed.getString("signature").decode(), IdentityKey(bundle.getString("identity_public_key").decode()),
            kyberOneTime?.getInt("id") ?: PreKeyBundle.NULL_PRE_KEY_ID,
            kyberOneTime?.let { KEMPublicKey(it.getString("public_key").decode()) } ?: KEMPublicKey(kyberSigned.getString("public_key").decode()),
            (kyberOneTime ?: kyberSigned).getString("signature").decode())
    }

    private fun resolveRegistrationId(profile: AccountProfile, state: ru.hiddi.messenger.security.SignalState): ru.hiddi.messenger.security.SignalState {
        if (state.keys.registrationId != 0) return state
        val device = JSONObject(request("GET", "${profile.serverUrl}/v1/devices/current", null, profile.accessToken))
        val migrated = state.copy(keys = state.keys.copy(registrationId = device.getInt("registration_id")))
        repository.save(migrated)
        return migrated
    }

    private fun request(method: String, url: String, body: String?, token: String?): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 15_000; readTimeout = 30_000
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            body?.let { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        body?.let { connection.outputStream.use { stream -> stream.write(it.encodeToByteArray()) } }
        val response = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        check(connection.responseCode in 200..299) { response.takeIf(String::isNotBlank)?.let { JSONObject(it).optString("error", "HTTP ${connection.responseCode}") } ?: "HTTP ${connection.responseCode}" }
        return response
    }

    private companion object {
        const val DEVICE_ID = 1
        val SIGNAL_STATE_MUTEX = Mutex()
    }
}

data class DecryptedMessage(
    val messageId: String,
    val senderNickname: String,
    val text: String,
    val createdAt: String,
)
data class MessageDeletion(val deletionId: String, val messageId: String)
data class UserSearchResult(
    val nickname: String,
    val displayName: String,
    val bio: String,
    val avatarVersion: String?,
)
data class GroupMember(
    val nickname: String,
    val role: String = "member",
    val deviceId: String = "",
)
data class GroupDetails(
    val groupId: ByteArray,
    val ownerNickname: String,
    val members: List<GroupMember>,
)
data class GroupDeletion(val deletionId: String, val groupId: ByteArray)
data class GroupEvent(
    val eventId: String,
    val groupId: ByteArray,
    val senderNickname: String,
    val kind: Int,
    val envelope: ByteArray,
    val createdAt: String,
    val removesRecipient: Boolean,
)
enum class DeliveryStatus { SENT, DELIVERED, READ }
private fun ByteArray.b64() = Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
private fun String.decode() = Base64.decode(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
private fun JSONObject.userProfile() = UserSearchResult(
    nickname = getString("nickname"),
    displayName = optString("display_name"),
    bio = optString("bio"),
    avatarVersion = optString("avatar_version").takeIf(String::isNotBlank),
)
