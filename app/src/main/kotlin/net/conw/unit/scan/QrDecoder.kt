package net.conw.unit.scan

import android.graphics.Bitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer

/** One reader per worker, shared by camera frames and imported images. */
internal class QrDecoder {
    private val reader = QRCodeReader()
    private var pixels = ByteArray(0)

    fun decode(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): String? {
        if (pixels.size != width * height) pixels = ByteArray(width * height)
        val plane = buffer.duplicate()
        val start = plane.position()
        repeat(height) { row ->
            if (pixelStride == 1) {
                plane.position(start + row * rowStride)
                plane.get(pixels, row * width, width)
            } else {
                repeat(width) { x ->
                    pixels[row * width + x] = plane.get(start + row * rowStride + x * pixelStride)
                }
            }
        }
        val source = PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false)
        return decode(source)
    }

    fun decode(bitmap: Bitmap): String? {
        val colors = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(colors, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        // Exported QR PNGs can have transparent backgrounds. Render those pixels over white.
        if (bitmap.hasAlpha()) {
            for (index in colors.indices) {
                val color = colors[index]
                val alpha = color ushr 24
                if (alpha == 255) continue
                val white = 255 * (255 - alpha)
                val red = ((color ushr 16 and 255) * alpha + white) / 255
                val green = ((color ushr 8 and 255) * alpha + white) / 255
                val blue = ((color and 255) * alpha + white) / 255
                colors[index] = (255 shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return decode(
            RGBLuminanceSource(bitmap.width, bitmap.height, colors),
            mapOf(DecodeHintType.TRY_HARDER to true),
        )
    }

    private fun decode(source: LuminanceSource, hints: Map<DecodeHintType, *>? = null): String? {
        for (image in arrayOf(source, source.invert())) {
            try {
                return reader.decode(BinaryBitmap(HybridBinarizer(image)), hints).text.takeIf {
                    it.isNotBlank()
                }
            } catch (_: ReaderException) {
                // Most camera frames and some selected images contain no readable QR code.
            } finally {
                reader.reset()
            }
        }
        return null
    }
}
