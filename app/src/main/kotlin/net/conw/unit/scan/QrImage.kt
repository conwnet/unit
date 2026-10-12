package net.conw.unit.scan

import android.content.ContentResolver
import android.graphics.ImageDecoder
import android.net.Uri
import kotlin.math.max

/** Reads only the selected URI, on a worker, without retaining the image or its permission. */
internal object QrImage {
    private const val MAX_SIDE = 2048

    fun decode(resolver: ContentResolver, uri: Uri): String? {
        val bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _
                ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val size = info.size
                val side = max(size.width, size.height)
                if (side > MAX_SIDE) {
                    decoder.setTargetSize(
                        max(1, (size.width.toLong() * MAX_SIDE / side).toInt()),
                        max(1, (size.height.toLong() * MAX_SIDE / side).toInt()),
                    )
                }
            }
        return try {
            QrDecoder().decode(bitmap)
        } finally {
            bitmap.recycle()
        }
    }
}
