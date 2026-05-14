package com.rezvani.mesh.utils

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

object BarcodeUtils {

    fun generateQrCodeBitmap(text: String, size: Int = 512): Bitmap? {
        val writer = QRCodeWriter()
        return try {
            val bitMatrix: BitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: WriterException) {
            null
        }
    }

    fun decodeQrBitmap(bitmap: Bitmap): String? {
        // Use ZXing's BarcodeReader or Android ML Kit barcode scanning; here we use ZXing core
        val intArray = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(intArray, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = com.google.zxing.RGBLuminanceSource(bitmap.width, bitmap.height, intArray)
        val binarizer = com.google.zxing.common.HybridBinarizer(source)
        val binaryBitmap = com.google.zxing.BinaryBitmap(binarizer)
        val reader = com.google.zxing.qrcode.QRCodeReader()
        try {
            val result = reader.decode(binaryBitmap)
            return result.text
        } catch (e: Exception) {
            return null
        }
    }
}
