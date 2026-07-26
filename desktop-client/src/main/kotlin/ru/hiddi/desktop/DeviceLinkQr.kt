package ru.hiddi.desktop

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.imageio.ImageIO

private const val DEVICE_LINK_QR_PREFIX = "hiddi-device-link-v1:"

data class DeviceLinkQr(
    val serverUrl: String,
    val code: String,
)

fun readDeviceLinkQr(file: File): DeviceLinkQr =
    ImageIO.read(file)?.let(::readDeviceLinkQr)
        ?: throw IllegalArgumentException("Не удалось открыть изображение с QR-кодом")

fun readDeviceLinkQr(image: BufferedImage): DeviceLinkQr {
    val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    val text = runCatching {
        MultiFormatReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels))),
        ).text
    }.getOrElse { throw IllegalArgumentException("На изображении не найден QR-код Hiddi", it) }
    return parseDeviceLinkQr(text)
}

fun parseDeviceLinkQr(text: String): DeviceLinkQr {
    require(text.startsWith(DEVICE_LINK_QR_PREFIX)) { "Это не QR-код привязки Hiddi" }
    val decoded = runCatching {
        String(Base64.getUrlDecoder().decode(text.removePrefix(DEVICE_LINK_QR_PREFIX)), StandardCharsets.UTF_8)
    }.getOrElse { throw IllegalArgumentException("QR-код привязки повреждён", it) }
    val separator = decoded.indexOf('\n')
    require(separator > 0 && separator < decoded.lastIndex) { "QR-код привязки имеет неверный формат" }
    val serverUrl = decoded.substring(0, separator).trimEnd('/')
    val code = decoded.substring(separator + 1)
    val uri = runCatching { URI(serverUrl) }.getOrNull()
    require(uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank()) {
        "QR-код содержит неверный адрес сервера"
    }
    require(code.length >= 32 && code.all { it.isLetterOrDigit() || it in "-_" }) {
        "QR-код содержит неверный код привязки"
    }
    return DeviceLinkQr(serverUrl, code)
}
