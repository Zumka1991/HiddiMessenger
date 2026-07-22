package ru.hiddi.messenger.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.hiddi.messenger.security.CryptoBoundary
import ru.hiddi.messenger.security.AndroidKeystoreSecretStore
import ru.hiddi.messenger.security.PublicPreKey
import java.net.HttpURLConnection
import java.net.URL

class RegistrationApi(private val crypto: CryptoBoundary) {
    suspend fun register(serverUrl: String, nickname: String, inviteCode: String): RegisteredDevice =
        withContext(Dispatchers.IO) {
            require(serverUrl.startsWith("https://") || serverUrl.startsWith("http://")) { "Некорректный адрес сервера" }
            val baseUrl = serverUrl.trimEnd('/')
            val bundle = crypto.createRegistrationBundle()
            val registration = post(
                "$baseUrl/v1/auth/register",
                JSONObject()
                    .put("nickname", nickname)
                    .put("invite_code", inviteCode)
                    .put("registration_id", bundle.registrationId)
                    .put("identity_public_key", bundle.identityPublicKey.base64Url())
                    .toString(),
                null,
            )
            val token = registration.getString("access_token")
            val registrationId = registration.getInt("registration_id")
            check(registrationId == bundle.registrationId) { "Сервер вернул другой идентификатор устройства" }
            put(
                "$baseUrl/v1/devices/prekeys",
                JSONObject()
                    .put("signed_prekey", bundle.signedPreKey.toJson())
                    .put("kyber_signed_prekey", bundle.kyberSignedPreKey.toJson())
                    .put("one_time_prekeys", bundle.oneTimePreKeys.toJsonArray())
                    .put("kyber_one_time_prekeys", bundle.kyberOneTimePreKeys.toJsonArray())
                    .toString(),
                token,
            )
            RegisteredDevice(
                accountId = registration.getString("account_id"),
                deviceId = registration.getString("device_id"),
                registrationId = registrationId,
                accessToken = token,
            )
        }

    private fun post(url: String, body: String, token: String?): JSONObject = request("POST", url, body, token)
    private fun put(url: String, body: String, token: String?): JSONObject = request("PUT", url, body, token)

    private fun request(method: String, url: String, body: String, token: String?): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        connection.outputStream.use { it.write(body.toByteArray()) }
        val responseBody = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        check(connection.responseCode in 200..299) { JSONObject(responseBody).optString("error", "HTTP ${connection.responseCode}") }
        return if (responseBody.isBlank()) JSONObject() else JSONObject(responseBody)
    }
}

class AccountStore(context: android.content.Context) {
    private val store = AndroidKeystoreSecretStore(
        context,
        fileName = "account-token.v1",
        keyAlias = "ru.hiddi.messenger.account-token.v1",
    )

    fun save(profile: AccountProfile) = store.write(
        JSONObject()
            .put("server", profile.serverUrl.trimEnd('/'))
            .put("nickname", profile.nickname.removePrefix("@").lowercase())
            .put("token", profile.accessToken)
            .toString().encodeToByteArray(),
    )

    fun read(): AccountProfile? = runCatching {
        store.read()?.decodeToString()?.let(::JSONObject)?.let {
            AccountProfile(it.getString("server"), it.getString("nickname"), it.getString("token"))
        }
    }.getOrNull()

    fun recoverLegacy(serverUrl: String, nickname: String): AccountProfile? {
        val raw = store.read()?.decodeToString() ?: return null
        if (runCatching { JSONObject(raw) }.isSuccess) return null
        val profile = AccountProfile(serverUrl.trimEnd('/'), nickname.removePrefix("@").lowercase(), raw)
        save(profile)
        return profile
    }

    fun hasLegacyToken(): Boolean = store.read()?.decodeToString()?.let { runCatching { JSONObject(it) }.isFailure } == true
}

data class RegisteredDevice(
    val accountId: String,
    val deviceId: String,
    val registrationId: Int,
    val accessToken: String,
)

data class AccountProfile(val serverUrl: String, val nickname: String, val accessToken: String)

private fun ByteArray.base64Url(): String = Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
private fun PublicPreKey.toJson(): JSONObject = JSONObject().put("id", id).put("public_key", publicKey.base64Url()).also { json ->
    signature?.let { json.put("signature", it.base64Url()) }
}
private fun List<PublicPreKey>.toJsonArray(): JSONArray = JSONArray().also { array -> forEach { array.put(it.toJson()) } }
