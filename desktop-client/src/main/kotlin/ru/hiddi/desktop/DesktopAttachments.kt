package ru.hiddi.desktop

import org.json.JSONObject
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.abs
import kotlin.concurrent.thread
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
    fun descriptor(attachmentId: String) =
        AttachmentDescriptor(
            attachmentId = attachmentId,
            bindingId = bindingId,
            kind = kind,
            key = key,
            iv = iv,
            mimeType = mimeType,
            plainSize = plainSize,
            durationMs = durationMs,
        )
}

data class SanitizedDesktopImage(
    val full: ByteArray,
    val preview: ByteArray,
)

data class RecordedDesktopVoice(
    val pcm: ByteArray,
    val durationMs: Long,
)

/**
 * Stores only ciphertext on disk. The per-file AES key is carried inside the
 * Signal-encrypted message and the descriptor is persisted in the encrypted vault.
 */
class DesktopAttachmentStore(private val directory: Path) {
    init {
        Files.createDirectories(directory)
        setOwnerOnlyPermissions(directory, DIRECTORY_PERMISSIONS)
    }

    fun encrypt(
        plain: ByteArray,
        kind: String,
        mimeType: String,
        durationMs: Long? = null,
    ): PreparedAttachment {
        require(kind in ALLOWED_KINDS) { "Неподдерживаемый тип вложения" }
        require(mimeType in ALLOWED_MIME_TYPES) { "Неподдерживаемый формат вложения" }
        require(plain.isNotEmpty() && plain.size <= MAX_PLAIN_BYTES) {
            "Вложение должно быть не больше 8 МиБ"
        }
        val bindingId = UUID.randomUUID().toString()
        val key = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        try {
            val cipher =
                Cipher.getInstance(AES_GCM).apply {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                    updateAAD(associatedData(bindingId, kind, mimeType))
                }
            return PreparedAttachment(
                bindingId = bindingId,
                kind = kind,
                key = key.base64Url(),
                iv = iv.base64Url(),
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
        validateUuid(attachmentId)
        require(ciphertext.size in 17..MAX_CIPHERTEXT_BYTES) {
            "Некорректный размер зашифрованного вложения"
        }
        val target = path(attachmentId)
        val temporary = Files.createTempFile(directory, ".hiddi-attachment-", ".tmp")
        try {
            Files.write(temporary, ciphertext)
            setOwnerOnlyPermissions(temporary, FILE_PERMISSIONS)
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            setOwnerOnlyPermissions(target, FILE_PERMISSIONS)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun exists(attachmentId: String): Boolean {
        validateUuid(attachmentId)
        return Files.isRegularFile(path(attachmentId))
    }

    fun decrypt(descriptor: AttachmentDescriptor): ByteArray {
        validateDescriptor(descriptor)
        val encrypted = Files.readAllBytes(path(descriptor.attachmentId))
        val key = descriptor.key.base64UrlDecode()
        val iv = descriptor.iv.base64UrlDecode()
        try {
            val cipher =
                Cipher.getInstance(AES_GCM).apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                    updateAAD(
                        associatedData(
                            descriptor.bindingId,
                            descriptor.kind,
                            descriptor.mimeType,
                        ),
                    )
                }
            return cipher.doFinal(encrypted).also {
                require(it.size == descriptor.plainSize) { "Размер вложения не совпадает" }
            }
        } finally {
            encrypted.fill(0)
            key.fill(0)
            iv.fill(0)
        }
    }

    fun delete(attachmentId: String) {
        validateUuid(attachmentId)
        Files.deleteIfExists(path(attachmentId))
    }

    private fun path(attachmentId: String): Path = directory.resolve("$attachmentId.bin")

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
        private val DIRECTORY_PERMISSIONS =
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
        private val FILE_PERMISSIONS =
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            )

        fun envelope(descriptor: AttachmentDescriptor): String =
            descriptor.toJson().put("type", "hiddi.attachment.v1").toString()

        fun parseEnvelope(value: String): AttachmentDescriptor? {
            val json = runCatching { JSONObject(value) }.getOrNull() ?: return null
            if (json.optString("type") != "hiddi.attachment.v1") return null
            return json.toAttachmentDescriptor().also(::validateDescriptor)
        }

        fun validateDescriptor(descriptor: AttachmentDescriptor, allowPreview: Boolean = true) {
            validateUuid(descriptor.attachmentId)
            validateUuid(descriptor.bindingId)
            require(descriptor.kind in ALLOWED_KINDS) { "Неподдерживаемый тип вложения" }
            require(descriptor.mimeType in ALLOWED_MIME_TYPES) {
                "Неподдерживаемый формат вложения"
            }
            require(descriptor.key.base64UrlDecode().size == 32) { "Некорректный ключ вложения" }
            require(descriptor.iv.base64UrlDecode().size == 12) { "Некорректный IV вложения" }
            require(descriptor.plainSize in 1..MAX_PLAIN_BYTES) { "Некорректный размер вложения" }
            descriptor.durationMs?.let {
                require(it in 0..3_600_000) { "Некорректная длительность голосового" }
            }
            descriptor.preview?.let { preview ->
                require(allowPreview && descriptor.kind == IMAGE_KIND) {
                    "Некорректное превью вложения"
                }
                require(preview.kind == IMAGE_KIND && preview.mimeType == JPEG_MIME) {
                    "Некорректный формат превью"
                }
                require(preview.durationMs == null && preview.preview == null) {
                    "Некорректное вложенное превью"
                }
                validateDescriptor(preview, allowPreview = false)
            }
        }

        private fun validateUuid(value: String) {
            require(runCatching { UUID.fromString(value) }.isSuccess) {
                "Некорректный идентификатор вложения"
            }
        }

        private fun associatedData(bindingId: String, kind: String, mimeType: String) =
            "hiddi-attachment-v1\u0000$bindingId\u0000$kind\u0000$mimeType".encodeToByteArray()

        private fun setOwnerOnlyPermissions(
            target: Path,
            permissions: Set<PosixFilePermission>,
        ) {
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(target, permissions)
            }
        }
    }
}

internal fun AttachmentDescriptor.toJson(): JSONObject =
    JSONObject()
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

internal fun JSONObject.toAttachmentDescriptor(): AttachmentDescriptor =
    AttachmentDescriptor(
        attachmentId = getString("attachment_id"),
        bindingId = getString("binding_id"),
        kind = getString("kind"),
        key = getString("key"),
        iv = getString("iv"),
        mimeType = getString("mime"),
        plainSize = getInt("plain_size"),
        durationMs = optLong("duration_ms").takeIf { has("duration_ms") },
        preview = optJSONObject("preview")?.toAttachmentDescriptor(),
    )

fun sanitizeDesktopImage(file: File): SanitizedDesktopImage {
    val source = ImageIO.read(file) ?: error("Не удалось прочитать изображение")
    require(source.width > 0 && source.height > 0) { "Изображение пустое" }
    val full = source.scaledToFit(MAX_FULL_IMAGE_EDGE)
    val preview = full.scaledToFit(MAX_PREVIEW_EDGE)
    return try {
        SanitizedDesktopImage(
            full = full.encodeJpeg(0.90f),
            preview = preview.encodeJpeg(0.72f),
        )
    } finally {
        if (preview !== full) preview.flush()
        if (full !== source) full.flush()
        source.flush()
    }
}

private fun BufferedImage.scaledToFit(maxEdge: Int): BufferedImage {
    val scale = minOf(1f, maxEdge.toFloat() / maxOf(width, height))
    val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
    val target = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
    val graphics = target.createGraphics()
    try {
        graphics.color = Color.BLACK
        graphics.fillRect(0, 0, targetWidth, targetHeight)
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BICUBIC,
        )
        graphics.drawImage(this, 0, 0, targetWidth, targetHeight, null)
    } finally {
        graphics.dispose()
    }
    return target
}

private fun BufferedImage.encodeJpeg(initialQuality: Float): ByteArray {
    var quality = initialQuality
    while (quality >= 0.45f) {
        val output = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            ImageIO.createImageOutputStream(output).use { stream ->
                writer.output = stream
                writer.write(
                    null,
                    IIOImage(this, null, null),
                    writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = quality
                    },
                )
            }
        } finally {
            writer.dispose()
        }
        val bytes = output.toByteArray()
        if (bytes.size in 1..DesktopAttachmentStore.MAX_PLAIN_BYTES) return bytes
        bytes.fill(0)
        quality -= 0.1f
    }
    error("Не удалось уменьшить изображение до 8 МиБ")
}

/** Records mono PCM16LE directly into memory; plaintext audio never touches disk. */
class InMemoryDesktopVoiceRecorder {
    @Volatile
    private var recording = false
    private var line: TargetDataLine? = null
    private var worker: Thread? = null
    private var output: ByteArrayOutputStream? = null
    private var startedAt = 0L

    /** Instantaneous microphone level used only by the local recording indicator. */
    @Volatile
    var level: Float = 0f
        private set

    val isRecording: Boolean get() = recording

    @Synchronized
    fun start() {
        check(!recording) { "Запись уже идёт" }
        val info = DataLine.Info(TargetDataLine::class.java, VOICE_FORMAT)
        check(AudioSystem.isLineSupported(info)) { "Микрофон недоступен" }
        val target = AudioSystem.getLine(info) as TargetDataLine
        target.open(VOICE_FORMAT, SAMPLE_RATE * 2)
        val memory = ByteArrayOutputStream()
        line = target
        output = memory
        startedAt = System.currentTimeMillis()
        level = 0f
        recording = true
        target.start()
        worker =
            thread(name = "hiddi-voice-recorder", isDaemon = true) {
                val buffer = ByteArray(4_096)
                while (recording && memory.size() < MAX_PCM_BYTES) {
                    val count = target.read(buffer, 0, minOf(buffer.size, MAX_PCM_BYTES - memory.size()))
                    if (count > 0) {
                        memory.write(buffer, 0, count)
                        level = pcmLevel(buffer, count)
                    }
                }
            }
    }

    @Synchronized
    fun stop(): RecordedDesktopVoice {
        check(recording) { "Запись не запущена" }
        recording = false
        runCatching { line?.stop() }
        runCatching { line?.close() }
        worker?.join(2_000)
        val pcm = checkNotNull(output).toByteArray()
        cleanup()
        if (pcm.isEmpty()) {
            pcm.fill(0)
            error("Голосовое сообщение пустое")
        }
        val normalized =
            if (pcm.size % 2 == 0) {
                pcm
            } else {
                pcm.copyOf(pcm.size - 1).also { pcm.fill(0) }
            }
        return RecordedDesktopVoice(normalized, duration(normalized.size))
    }

    @Synchronized
    fun cancel() {
        recording = false
        runCatching { line?.stop() }
        runCatching { line?.close() }
        worker?.join(2_000)
        output?.reset()
        cleanup()
        level = 0f
    }

    private fun cleanup() {
        line = null
        worker = null
        output = null
        level = 0f
    }

    private fun duration(byteCount: Int): Long =
        minOf(
            ((byteCount / 2.0 / SAMPLE_RATE) * 1_000).toLong(),
            (System.currentTimeMillis() - startedAt).coerceAtLeast(0),
            MAX_DURATION_MS,
        )

    private fun pcmLevel(buffer: ByteArray, count: Int): Float {
        var peak = 0
        var index = 0
        while (index + 1 < count) {
            val sample = (buffer[index].toInt() and 0xFF) or (buffer[index + 1].toInt() shl 8)
            peak = maxOf(peak, abs(sample.toShort().toInt()))
            index += 2
        }
        return (peak / 32_767f).coerceIn(0.04f, 1f)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_DURATION_MS = 120_000L
        private const val MAX_PCM_BYTES = SAMPLE_RATE * 2 * 120
        private val VOICE_FORMAT = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
    }
}

fun playDesktopVoicePcm(pcm: ByteArray) {
    require(pcm.isNotEmpty() && pcm.size % 2 == 0) { "Некорректное голосовое сообщение" }
    val format =
        AudioFormat(
            InMemoryDesktopVoiceRecorder.SAMPLE_RATE.toFloat(),
            16,
            1,
            true,
            false,
        )
    val info = DataLine.Info(SourceDataLine::class.java, format)
    check(AudioSystem.isLineSupported(info)) { "Вывод звука недоступен" }
    val output = AudioSystem.getLine(info) as SourceDataLine
    try {
        output.open(format)
        output.start()
        var offset = 0
        while (offset < pcm.size) {
            val written = output.write(pcm, offset, pcm.size - offset)
            check(written >= 0) { "Не удалось воспроизвести голосовое сообщение" }
            offset += written
        }
        output.drain()
    } finally {
        runCatching { output.stop() }
        output.close()
    }
}

private const val MAX_FULL_IMAGE_EDGE = 2_560
private const val MAX_PREVIEW_EDGE = 360
