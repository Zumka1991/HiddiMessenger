package ru.hiddi.messenger.security

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

private const val PREFIX = "hiddi-safety-v1:"
private const val DEVICE_LINK_PREFIX = "hiddi-device-link-v1:"

/** QR carries a public comparison fingerprint only; it never contains an identity private key. */
fun safetyQrBitmap(safetyNumber: String, size: Int = 720): Bitmap {
    val matrix = QRCodeWriter().encode(PREFIX + safetyNumber.replace(" ", ""), BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        for (y in 0 until size) for (x in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
}

/** QR contains a short-lived, one-use device-link capability. Do not share its screenshot. */
fun deviceLinkQrBitmap(serverUrl: String, linkCode: String, size: Int = 720): Bitmap {
    val payload = "$serverUrl\n$linkCode".toByteArray(Charsets.UTF_8)
    val encoded = Base64.encodeToString(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    val matrix = QRCodeWriter().encode(DEVICE_LINK_PREFIX + encoded, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        for (y in 0 until size) for (x in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
}

fun readSafetyQr(resolver: ContentResolver, uri: Uri): String? {
    val bitmap = resolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) ?: return null
    return readSafetyQr(bitmap)
}

fun readSafetyQr(bitmap: Bitmap): String? {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val text = runCatching {
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))).text
    }.getOrNull() ?: return null
    return text.removePrefix(PREFIX).takeIf { text.startsWith(PREFIX) && it.matches(Regex("[0-9a-f]{60}")) }
}
