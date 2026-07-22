package ru.hiddi.messenger.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import androidx.core.util.AtomicFile
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

data class AttachmentDescriptor(
    val attachmentId: String,
    val bindingId: String,
    val kind: String,
    val key: String,
    val iv: String,
    val mimeType: String,
    val plainSize: Int,
    val durationMs: Long? = null,
    val preview: AttachmentDescriptor? = null,
)

data class PreparedAttachment(
    val bindingId: String,
    val kind: String,
    val key: String,
    val iv: String,
    val mimeType: String,
    val plainSize: Int,
    val durationMs: Long?,
    val ciphertext: ByteArray,
) {
    fun descriptor(attachmentId: String) = AttachmentDescriptor(
        attachmentId = attachmentId,
        bindingId = bindingId,
        kind = kind,
        key = key,
        iv = iv,
        mimeType = mimeType,
        plainSize = plainSize,
        durationMs = durationMs,
        preview = null,
    )
}

/** Stores only attachment ciphertext. Its key lives inside the Keystore-encrypted chat history. */
class EncryptedAttachmentStore(context: Context) {
    private val directory = context.noBackupFilesDir.resolve("attachments-v1").also { it.mkdirs() }

    fun encrypt(
        plain: ByteArray,
        kind: String,
        mimeType: String,
        durationMs: Long? = null,
    ): PreparedAttachment {
        require(kind in ALLOWED_KINDS) { "Unsupported attachment kind" }
        require(mimeType in ALLOWED_MIME_TYPES) { "Unsupported attachment MIME type" }
        require(plain.isNotEmpty() && plain.size <= MAX_PLAIN_BYTES) { "Attachment is too large" }
        val bindingId = UUID.randomUUID().toString()
        val key = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        try {
            val encodedKey = key.base64Url()
            val encodedIv = iv.base64Url()
            val cipher = Cipher.getInstance(AES_GCM).apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                updateAAD(associatedData(bindingId, kind, mimeType))
            }
            return PreparedAttachment(
                bindingId = bindingId,
                kind = kind,
                key = encodedKey,
                iv = encodedIv,
                mimeType = mimeType,
                plainSize = plain.size,
                durationMs = durationMs,
                ciphertext = cipher.doFinal(plain),
            )
        } finally {
            key.fill(0)
            iv.fill(0)
        }
    }

    fun saveCiphertext(attachmentId: String, ciphertext: ByteArray) {
        requireValidId(attachmentId)
        require(ciphertext.size in 17..MAX_CIPHERTEXT_BYTES) { "Invalid encrypted attachment size" }
        val file = AtomicFile(directory.resolve("$attachmentId.bin"))
        val output = file.startWrite()
        try {
            output.write(ciphertext)
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    fun exists(attachmentId: String): Boolean {
        requireValidId(attachmentId)
        return directory.resolve("$attachmentId.bin").isFile
    }

    fun delete(attachmentId: String) {
        requireValidId(attachmentId)
        AtomicFile(directory.resolve("$attachmentId.bin")).delete()
    }

    fun decrypt(descriptor: AttachmentDescriptor): ByteArray {
        validateDescriptor(descriptor)
        val encrypted = AtomicFile(directory.resolve("${descriptor.attachmentId}.bin"))
            .openRead().use { it.readBytes() }
        val key = descriptor.key.base64UrlDecode()
        val iv = descriptor.iv.base64UrlDecode()
        try {
            val cipher = Cipher.getInstance(AES_GCM).apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                updateAAD(associatedData(descriptor.bindingId, descriptor.kind, descriptor.mimeType))
            }
            return cipher.doFinal(encrypted).also {
                require(it.size == descriptor.plainSize) { "Attachment size mismatch" }
            }
        } finally {
            key.fill(0)
            iv.fill(0)
        }
    }

    companion object {
        const val IMAGE_KIND = "image"
        const val VOICE_KIND = "voice"
        const val JPEG_MIME = "image/jpeg"
        const val AUDIO_MIME = "audio/x-hiddi-pcm16le"
        const val MAX_PLAIN_BYTES = 8 * 1024 * 1024 - 16
        private const val MAX_CIPHERTEXT_BYTES = 8 * 1024 * 1024
        private const val AES_GCM = "AES/GCM/NoPadding"
        private val ALLOWED_KINDS = setOf(IMAGE_KIND, VOICE_KIND)
        private val ALLOWED_MIME_TYPES = setOf(JPEG_MIME, AUDIO_MIME)
        private val random = SecureRandom()

        fun envelope(descriptor: AttachmentDescriptor): String {
            validateDescriptor(descriptor)
            return descriptor.toJson()
                .put("type", "hiddi.attachment.v1")
                .toString()
        }

        private fun AttachmentDescriptor.toJson(): JSONObject = JSONObject()
            .put("attachment_id", attachmentId)
            .put("binding_id", bindingId)
            .put("kind", kind)
            .put("key", key)
            .put("iv", iv)
            .put("mime", mimeType)
            .put("plain_size", plainSize)
            .apply {
                durationMs?.let { put("duration_ms", it) }
                preview?.let { put("preview", it.toJson()) }
            }

        fun parseEnvelope(value: String): AttachmentDescriptor? {
            val json = runCatching { JSONObject(value) }.getOrNull() ?: return null
            if (json.optString("type") != "hiddi.attachment.v1") return null
            return json.toDescriptor().also(::validateDescriptor)
        }

        private fun JSONObject.toDescriptor(): AttachmentDescriptor = AttachmentDescriptor(
            attachmentId = getString("attachment_id"),
            bindingId = getString("binding_id"),
            kind = getString("kind"),
            key = getString("key"),
            iv = getString("iv"),
            mimeType = getString("mime"),
            plainSize = getInt("plain_size"),
            durationMs = optLong("duration_ms").takeIf { has("duration_ms") },
            preview = optJSONObject("preview")?.toDescriptor(),
        )

        private fun validateDescriptor(descriptor: AttachmentDescriptor, allowPreview: Boolean = true) {
            requireValidId(descriptor.attachmentId)
            requireValidId(descriptor.bindingId)
            require(descriptor.kind in ALLOWED_KINDS) { "Unsupported attachment kind" }
            require(descriptor.mimeType in ALLOWED_MIME_TYPES) { "Unsupported attachment MIME type" }
            require(descriptor.key.base64UrlDecode().size == 32) { "Invalid attachment key" }
            require(descriptor.iv.base64UrlDecode().size == 12) { "Invalid attachment IV" }
            require(descriptor.plainSize in 1..MAX_PLAIN_BYTES) { "Invalid attachment size" }
            descriptor.durationMs?.let { require(it in 0..3_600_000) { "Invalid voice duration" } }
            descriptor.preview?.let { preview ->
                require(allowPreview && descriptor.kind == IMAGE_KIND) { "Invalid attachment preview" }
                require(preview.kind == IMAGE_KIND && preview.mimeType == JPEG_MIME) { "Invalid preview type" }
                require(preview.durationMs == null && preview.preview == null) { "Invalid nested preview" }
                validateDescriptor(preview, allowPreview = false)
            }
        }

        private fun requireValidId(value: String) {
            require(runCatching { UUID.fromString(value) }.isSuccess) { "Invalid attachment identifier" }
        }

        private fun associatedData(bindingId: String, kind: String, mimeType: String) =
            "hiddi-attachment-v1\u0000$bindingId\u0000$kind\u0000$mimeType".encodeToByteArray()
    }
}

data class SanitizedImage(val full: ByteArray, val preview: ByteArray)

/** Decodes orientation once, strips metadata, and emits separate full/preview JPEGs. */
fun sanitizeImage(context: Context, uri: Uri): SanitizedImage {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val largest = maxOf(info.size.width, info.size.height)
        if (largest > MAX_FULL_IMAGE_EDGE) {
            val scale = MAX_FULL_IMAGE_EDGE.toFloat() / largest
            decoder.setTargetSize(
                (info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1),
            )
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = false
    }
    val flattened = if (decoded.hasAlpha()) {
        Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888).also { target ->
            Canvas(target).apply {
                drawColor(Color.BLACK)
                drawBitmap(decoded, 0f, 0f, null)
            }
        }
    } else {
        decoded
    }
    val previewLargest = maxOf(flattened.width, flattened.height)
    val previewBitmap = if (previewLargest > MAX_PREVIEW_EDGE) {
        val scale = MAX_PREVIEW_EDGE.toFloat() / previewLargest
        Bitmap.createScaledBitmap(
            flattened,
            (flattened.width * scale).roundToInt().coerceAtLeast(1),
            (flattened.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    } else {
        flattened
    }
    return try {
        SanitizedImage(
            full = flattened.encodeJpeg(FULL_JPEG_QUALITY),
            preview = previewBitmap.encodeJpeg(PREVIEW_JPEG_QUALITY),
        )
    } finally {
        if (previewBitmap !== flattened) previewBitmap.recycle()
        if (flattened !== decoded) flattened.recycle()
        decoded.recycle()
    }
}

private fun Bitmap.encodeJpeg(quality: Int): ByteArray = ByteArrayOutputStream().use { output ->
    check(compress(Bitmap.CompressFormat.JPEG, quality, output)) { "Could not encode image" }
    output.toByteArray().also {
        require(it.isNotEmpty() && it.size <= EncryptedAttachmentStore.MAX_PLAIN_BYTES) {
            "Image is too large after processing"
        }
    }
}

private const val MAX_FULL_IMAGE_EDGE = 2_560
private const val MAX_PREVIEW_EDGE = 360
private const val FULL_JPEG_QUALITY = 90
private const val PREVIEW_JPEG_QUALITY = 72
private fun ByteArray.base64Url() = Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
private fun String.base64UrlDecode() = Base64.decode(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
