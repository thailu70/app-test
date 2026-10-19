package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.floor

/** Encode the exact server-issued boarding token, including the QR quiet zone. */
internal fun boardingQrMatrix(token: String): BitMatrix? {
    if (token.isBlank()) return null
    return try {
        QRCodeWriter().encode(token, BarcodeFormat.QR_CODE, 0, 0, mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 4
        ))
    } catch (_: IllegalArgumentException) { null }
      catch (_: com.google.zxing.WriterException) { null }
}

@Composable
fun QrCodeCanvas(
    token: String,
    modifier: Modifier = Modifier,
    moduleColor: Color = Color(0xFF101820),
    backgroundColor: Color = Color.White
) {
    val matrix = remember(token) { boardingQrMatrix(token) }
    Box(
        modifier = modifier.aspectRatio(1f).background(backgroundColor)
            .semantics { contentDescription = if (matrix == null) "Boarding code unavailable" else "Boarding QR code. Show this to your driver." },
        contentAlignment = Alignment.Center
    ) {
        if (matrix == null) {
            Text("Boarding code unavailable. Refresh your pass.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Canvas(Modifier.fillMaxSize()) {
            val cell = floor(minOf(size.width, size.height) / matrix.width).coerceAtLeast(1f)
            val left = floor((size.width - cell * matrix.width) / 2f)
            val top = floor((size.height - cell * matrix.height) / 2f)
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(moduleColor, Offset(left + x * cell, top + y * cell), Size(cell, cell))
            }
        }
    }
}
