package com.example.ui.components

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Renders a standards-compliant QR symbol containing the exact server-signed token.
 * Do not replace this with a decorative/hash pattern: scanner devices must decode it.
 */
@Composable
fun QrCodeCanvas(
    token: String,
    modifier: Modifier = Modifier,
    moduleColor: Color = Color(0xFF0F172A),
    backgroundColor: Color = Color.White
) {
    val matrix = remember(token) {
        runCatching {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2
            )
            val encoded = MultiFormatWriter().encode(token, BarcodeFormat.QR_CODE, 512, 512, hints)
            Array(encoded.height) { y -> BooleanArray(encoded.width) { x -> encoded.get(x, y) } }
        }.getOrElse { Array(1) { booleanArrayOf(false) } }
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .border(2.dp, Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val rows = matrix.size
            val columns = matrix.firstOrNull()?.size ?: 0
            if (rows > 1 && columns > 1) {
                val cellWidth = size.width / columns
                val cellHeight = size.height / rows
                for (y in 0 until rows) {
                    for (x in 0 until columns) {
                        if (matrix[y][x]) {
                            drawRect(
                                color = moduleColor,
                                topLeft = Offset(x * cellWidth, y * cellHeight),
                                size = Size(cellWidth, cellHeight)
                            )
                        }
                    }
                }
            }
        }
    }
}
