package ru.hiddi.messenger.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKey
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
import ru.hiddi.messenger.security.SignalStateRepository
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

class SignalMessagingApi(private val repository: SignalStateRepository) {
    suspend fun findUsers(profile: AccountProfile, nickname: String): List<UserSearchResult> = withContext(Dispatchers.IO) {
        val normalized = nickname.trim().removePrefix("@").lowercase()
        val encodedQuery = URLEncoder.encode(normalized, Charsets.UTF_8.name())
        JSONArray(request("GET", "${profile.serverUrl}/v1/users?query=$encodedQuery", null, profile.accessToken))
            .let { items -> (0 until items.length()).map { UserSearchResult(items.getJSONObject(it).getString("nickname")) } }
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
                output += DecryptedMessage(sender, plain.decodeToString(), item.getString("created_at"))
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

data class DecryptedMessage(val senderNickname: String, val text: String, val createdAt: String)
data class UserSearchResult(val nickname: String)
enum class DeliveryStatus { SENT, DELIVERED, READ }
private fun ByteArray.b64() = Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
private fun String.decode() = Base64.decode(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
