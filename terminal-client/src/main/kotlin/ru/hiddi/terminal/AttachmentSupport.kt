package ru.hiddi.terminal

import org.json.JSONArray
import org.json.JSONObject
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AttachmentSupport {
    fun receiveIfAttachment(
        client: HttpClient,
        state: JSONObject,
        plaintext: String,
        dataDir: Path,
    ): String? {
        val descriptor = runCatching { JSONObject(plaintext) }.getOrNull() ?: return null
        if (descriptor.optString("type") != TYPE) return null
        validate(descriptor)
        val id = descriptor.getString("attachment_id")
        val previewSize = descriptor.optJSONObject("preview")?.let {
            receivePart(client, state, it, dataDir)
        }
        val clearSize = receivePart(client, state, descriptor, dataDir)
        val attachments = state.optJSONArray("attachments") ?: JSONArray().also { state.put("attachments", it) }
        if ((0 until attachments.length()).none { attachments.getJSONObject(it).optString("attachment_id") == id }) {
            attachments.put(JSONObject(descriptor.toString()))
        }
        return when (descriptor.getString("kind")) {
            "image" -> "📷 Зашифрованное фото проверено: $clearSize байт" +
                (previewSize?.let { ", превью $it байт" } ?: "") + ", id=$id"
            "voice" -> "🎙 Зашифрованный войс проверен: ${descriptor.optLong("duration_ms")} мс, id=$id"
            else -> error("Неподдерживаемый тип вложения")
        }
    }

    fun list(state: JSONObject) {
        val attachments = state.optJSONArray("attachments") ?: JSONArray()
        if (attachments.length() == 0) {
            println("Вложений нет.")
            return
        }
        for (index in 0 until attachments.length()) {
            val item = attachments.getJSONObject(index)
            println("${item.getString("attachment_id")}  ${item.getString("kind")}  ${item.getInt("plain_size")} bytes")
        }
    }

    fun export(state: JSONObject, dataDir: Path, id: String, output: Path) {
        UUID.fromString(id)
        val attachments = state.optJSONArray("attachments") ?: error("Вложение не найдено")
        val descriptor = (0 until attachments.length())
            .map(attachments::getJSONObject)
            .firstOrNull { it.getString("attachment_id") == id }
            ?: error("Вложение не найдено")
        val ciphertext = Files.readAllBytes(attachmentPath(dataDir, id))
        val clear = decrypt(descriptor, ciphertext)
        try {
            output.parent?.let(Files::createDirectories)
            Files.write(output, clear)
            println("Экспортировано: ${output.toAbsolutePath()}")
            println("Внимание: экспортированный файл больше не зашифрован vault-ключом Hiddi.")
        } finally {
            clear.fill(0)
        }
    }

    private fun receivePart(client: HttpClient, state: JSONObject, descriptor: JSONObject, dataDir: Path): Int {
        val id = descriptor.getString("attachment_id")
        val response = request(
            client,
            "GET",
            "${state.getString("server")}/v1/attachments/$id",
            null,
            state.getString("access_token"),
        ) as JSONObject
        val ciphertext = response.getString("ciphertext").base64UrlDecode()
        val clear = decrypt(descriptor, ciphertext)
        return try {
            require(clear.size == descriptor.getInt("plain_size")) { "Размер вложения не совпадает" }
            saveCiphertext(dataDir, id, ciphertext)
            clear.size
        } finally {
            clear.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun validate(descriptor: JSONObject, allowPreview: Boolean = true) {
        UUID.fromString(descriptor.getString("attachment_id"))
        UUID.fromString(descriptor.getString("binding_id"))
        require(descriptor.getString("kind") in setOf("image", "voice"))
        require(descriptor.getString("mime") in setOf("image/jpeg", "audio/x-hiddi-pcm16le"))
        require(descriptor.getString("key").base64UrlDecode().size == 32)
        require(descriptor.getString("iv").base64UrlDecode().size == 12)
        require(descriptor.getInt("plain_size") in 1 until 8 * 1024 * 1024)
        descriptor.optJSONObject("preview")?.let { preview ->
            require(allowPreview && descriptor.getString("kind") == "image")
            require(preview.getString("kind") == "image" && preview.getString("mime") == "image/jpeg")
            require(!preview.has("preview") && !preview.has("duration_ms"))
            validate(preview, allowPreview = false)
        }
    }

    private fun decrypt(descriptor: JSONObject, ciphertext: ByteArray): ByteArray {
        validate(descriptor)
        val key = descriptor.getString("key").base64UrlDecode()
        val iv = descriptor.getString("iv").base64UrlDecode()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                updateAAD(
                    "hiddi-attachment-v1\u0000${descriptor.getString("binding_id")}\u0000${descriptor.getString("kind")}\u0000${descriptor.getString("mime")}".encodeToByteArray(),
                )
            }
            return cipher.doFinal(ciphertext)
        } finally {
            key.fill(0)
            iv.fill(0)
        }
    }

    private fun saveCiphertext(dataDir: Path, id: String, ciphertext: ByteArray) {
        val path = attachmentPath(dataDir, id)
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".hiddi-attachment-", ".tmp")
        try {
            Files.write(temporary, ciphertext)
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun attachmentPath(dataDir: Path, id: String): Path {
        UUID.fromString(id)
        return dataDir.resolve("attachments-v1").resolve("$id.bin")
    }

    private const val TYPE = "hiddi.attachment.v1"
}
