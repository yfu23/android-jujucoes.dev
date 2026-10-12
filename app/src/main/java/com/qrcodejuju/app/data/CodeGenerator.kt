package com.qrcodejuju.app.data

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter

object CodeGenerator {
    /** Formats offered on the Create screen: display name to ZXing format. */
    val formats: List<Pair<String, BarcodeFormat>> = listOf(
        "QR Code" to BarcodeFormat.QR_CODE,
        "Code 128" to BarcodeFormat.CODE_128,
        "Code 39" to BarcodeFormat.CODE_39,
        "Code 93" to BarcodeFormat.CODE_93,
        "Codabar" to BarcodeFormat.CODABAR,
        "EAN-13" to BarcodeFormat.EAN_13,
        "EAN-8" to BarcodeFormat.EAN_8,
        "UPC-A" to BarcodeFormat.UPC_A,
        "UPC-E" to BarcodeFormat.UPC_E,
        "ITF" to BarcodeFormat.ITF,
        "PDF417" to BarcodeFormat.PDF_417,
        "Aztec" to BarcodeFormat.AZTEC,
        "Data Matrix" to BarcodeFormat.DATA_MATRIX,
    )

    fun displayName(format: String): String =
        formats.firstOrNull { it.second.name == format }?.first ?: format

    private fun is2D(format: BarcodeFormat) = format in setOf(
        BarcodeFormat.QR_CODE, BarcodeFormat.AZTEC, BarcodeFormat.DATA_MATRIX
    )

    /** Returns a bitmap, or a failure whose message is suitable to show the user. */
    fun generate(text: String, format: BarcodeFormat): Result<Bitmap> = runCatching {
        require(text.isNotEmpty()) { "Enter some text" }
        val (w, h) = if (is2D(format)) 800 to 800 else 900 to 350
        val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
        val matrix = MultiFormatWriter().encode(text, format, w, h, hints)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix[i % matrix.width, i / matrix.width]) Color.BLACK else Color.WHITE
        }
        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }.recoverCatching { e ->
        throw IllegalArgumentException(
            e.message?.takeIf { it.isNotBlank() } ?: "This text can't be encoded as that format"
        )
    }
}
