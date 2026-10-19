package com.example

import com.example.ui.components.boardingQrMatrix
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.*
import org.junit.Test

class BoardingQrTest {
    @Test fun `boarding QR decodes to the exact server token`() {
        val tokens = listOf(
            "RP1:sub_preview:passenger_preview:route_preview:server-signature",
            "RP1:" + "signed_payload.".repeat(35),
            "{\"bookingId\":\"preview\",\"signature\":\"not-a-valid-pass\"}",
            "RP1:የፈተና-ኮድ"
        )
        for (token in tokens) {
            val matrix = requireNotNull(boardingQrMatrix(token))
            val scale = 6
            val width = matrix.width * scale
            val pixels = IntArray(width * width) { index ->
                if (matrix[(index % width) / scale, (index / width) / scale]) 0xff101820.toInt() else 0xffffffff.toInt()
            }
            val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, width, pixels))))
            assertEquals(token, decoded.text)
        }
    }

    @Test fun `missing or oversized tokens cannot produce a misleading boarding image`() {
        assertNull(boardingQrMatrix(""))
        assertNull(boardingQrMatrix("   "))
        assertNull(boardingQrMatrix("x".repeat(10000)))
    }
}
