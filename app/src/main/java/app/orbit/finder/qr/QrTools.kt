package app.orbit.finder.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

fun qrBitmap(value: String, size: Int = 900): Bitmap {
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
        for (y in 0 until size) for (x in 0 until size) setPixel(x, y, if (matrix[x, y]) Color.rgb(7, 11, 26) else Color.WHITE)
    }
}
