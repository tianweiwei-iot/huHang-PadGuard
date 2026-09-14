package com.padguard.presentation.util

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 二维码生成工具（基于 ZXing core，与主流 MDM 绑定二维码实现方式一致）
 */
object QrCodeGenerator {

    /**
     * 将文本生成黑白二维码 ImageBitmap
     * @param size 边长（像素），调用方按展示尺寸提供
     */
    fun generate(text: String, size: Int = 512): ImageBitmap? {
        if (text.isBlank()) return null
        val matrix = runCatching {
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                size,
                size,
                mapOf(EncodeHintType.MARGIN to 1)
            )
        }.getOrNull() ?: return null

        val black = android.graphics.Color.BLACK
        val white = android.graphics.Color.WHITE
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) black else white
            }
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap.asImageBitmap()
    }
}
