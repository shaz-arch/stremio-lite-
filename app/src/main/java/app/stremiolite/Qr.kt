package app.stremiolite

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Makes a QR code picture for a link (black on white, with a quiet border). */
object Qr {
    fun make(text: String, size: Int = 480): Bitmap? = runCatching {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m[x, y]) Color.BLACK else Color.WHITE
        Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
    }.getOrNull()
}
