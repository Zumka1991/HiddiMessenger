package ru.hiddi.terminal

import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64

/** Creates a Signal PQXDH device, registers it and uploads public prekeys. */
object RegisterCommand {
    fun run(args: List<String>) {
        val server = args.option("--server") ?: usage()
        val nickname = args.option("--nickname") ?: usage()
        val invite = args.option("--invite") ?: usage()
        val dataDir = Path.of(args.option("--data-dir") ?: defaultDataDirectory())
        require(server.startsWith("https://") || server.startsWith("http://127.0.0.1") || server.startsWith("http://localhost")) {
            "Для удалённого сервера используйте HTTPS. HTTP разрешён только для локальной разработки."
        }
        val console = System.console() ?: error("Регистрация требует интерактивный терминал для парольной фразы хранилища ключей.")
        val passphrase = console.readPassword("Парольная фраза для локальных ключей: ")
        val confirmation = console.readPassword("Повторите парольную фразу: ")
        try {
            require(passphrase.contentEquals(confirmation)) { "Парольные фразы не совпадают" }
            val vault = EncryptedVault(dataDir.resolve("signal-device.v1"))
            require(!vault.exists()) { "Устройство уже создано: ${dataDir.resolve("signal-device.v1")}" }

            println("Создаю Signal PQXDH prekeys…")
            val device = SignalDevice.create()
            val client = HttpClient.newHttpClient()
            val registration = post(client, "$server/v1/auth/register", device.registrationJson(nickname, invite), null)
            val token = registration.getString("access_token")
            check(registration.getInt("registration_id") == device.registrationId) { "Сервер вернул другой registration_id" }

            // Commit private state immediately after successful account creation.
            // If publication is interrupted, a later `prekeys sync` command can use this vault.
            vault.write(device.privateState(registration, server, nickname).toString().encodeToByteArray(), passphrase)
            put(client, "$server/v1/devices/prekeys", device.publicPrekeysJson(), token)
            println("Устройство @$nickname зарегистрировано. Приватные ключи сохранены в $dataDir")
        } finally {
            passphrase.fill('\u0000')
            confirmation.fill('\u0000')
        }
    }

    private fun post(client: HttpClient, url: String, body: JSONObject, token: String?): JSONObject =
        request(client, "POST", url, body, token)

    private fun put(client: HttpClient, url: String, body: JSONObject, token: String?): JSONObject =
        request(client, "PUT", url, body, token)

    private fun request(client: HttpClient, method: String, url: String, body: JSONObject, token: String?): JSONObject {
        val builder = HttpRequest.newBuilder(URI(url))
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body.toString()))
        token?.let { builder.header("Authorization", "Bearer $it") }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            response.body().takeIf(String::isNotBlank)?.let { JSONObject(it).optString("error", "HTTP ${response.statusCode()}") }
                ?: "HTTP ${response.statusCode()}"
        }
        return response.body().takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
    }

    private fun List<String>.option(name: String): String? = indexOf(name).takeIf { it >= 0 }?.let { index -> getOrNull(index + 1) }
    private fun usage(): Nothing = error("Использование: hiddi-terminal register --server https://host --nickname nick --invite CODE [--data-dir PATH]")
    private fun defaultDataDirectory(): String = Path.of(System.getProperty("user.home"), ".local", "share", "hiddi-terminal").toString()
}

private class SignalDevice private constructor(
    val registrationId: Int,
    private val identity: IdentityKeyPair,
    private val signed: SignedPreKeyRecord,
    private val kyberSigned: KyberPreKeyRecord,
    private val classical: List<PreKeyRecord>,
    private val kyber: List<KyberPreKeyRecord>,
) {
    fun registrationJson(nickname: String, invite: String): JSONObject = JSONObject()
        .put("nickname", nickname)
        .put("invite_code", invite)
        .put("registration_id", registrationId)
        .put("identity_public_key", identity.publicKey.serialize().base64Url())

    fun publicPrekeysJson(): JSONObject = JSONObject()
        .put("signed_prekey", signed.publicJson())
        .put("kyber_signed_prekey", kyberSigned.publicJson())
        .put("one_time_prekeys", JSONArray(classical.map(PreKeyRecord::publicJson)))
        .put("kyber_one_time_prekeys", JSONArray(kyber.map(KyberPreKeyRecord::publicJson)))

    fun privateState(registration: JSONObject, server: String, nickname: String): JSONObject = JSONObject()
        .put("format", 1)
        .put("server", server.trimEnd('/'))
        .put("nickname", nickname)
        .put("account_id", registration.getString("account_id"))
        .put("device_id", registration.getString("device_id"))
        .put("access_token", registration.getString("access_token"))
        .put("registration_id", registrationId)
        .put("identity", identity.serialize().base64Url())
        .put("signed_prekey", signed.serialize().base64Url())
        .put("kyber_signed_prekey", kyberSigned.serialize().base64Url())
        .put("one_time_prekeys", JSONArray(classical.map { it.serialize().base64Url() }))
        .put("kyber_one_time_prekeys", JSONArray(kyber.map { it.serialize().base64Url() }))

    companion object {
        private const val ONE_TIME_COUNT = 100

        fun create(): SignalDevice {
            val identity = IdentityKeyPair.generate()
            val signedPair = ECKeyPair.generate()
            val signedSignature = identity.privateKey.calculateSignature(signedPair.publicKey.serialize())
            val signed = SignedPreKeyRecord(1, System.currentTimeMillis(), signedPair, signedSignature)
            val kyberPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val kyberSignature = identity.privateKey.calculateSignature(kyberPair.publicKey.serialize())
            val kyberSigned = KyberPreKeyRecord(2, System.currentTimeMillis(), kyberPair, kyberSignature)
            val classical = (0 until ONE_TIME_COUNT).map { PreKeyRecord(10_000 + it, ECKeyPair.generate()) }
            val kyber = (0 until ONE_TIME_COUNT).map { offset ->
                val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
                KyberPreKeyRecord(20_000 + offset, System.currentTimeMillis(), pair, identity.privateKey.calculateSignature(pair.publicKey.serialize()))
            }
            return SignalDevice(SecureRandom().nextInt(16_380) + 1, identity, signed, kyberSigned, classical, kyber)
        }
    }
}

private fun PreKeyRecord.publicJson(): JSONObject = JSONObject().put("id", id).put("public_key", keyPair.publicKey.serialize().base64Url())
private fun SignedPreKeyRecord.publicJson(): JSONObject = JSONObject().put("id", id).put("public_key", keyPair.publicKey.serialize().base64Url()).put("signature", signature.base64Url())
private fun KyberPreKeyRecord.publicJson(): JSONObject = JSONObject().put("id", id).put("public_key", keyPair.publicKey.serialize().base64Url()).put("signature", signature.base64Url())
fun ByteArray.base64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)
