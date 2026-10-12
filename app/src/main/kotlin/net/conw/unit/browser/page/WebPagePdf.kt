package net.conw.unit.browser.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.CancellationSignal
import android.webkit.WebView
import androidx.core.graphics.createBitmap
import java.io.OutputStream
import kotlin.math.ceil

internal object WebPagePdf {
    @Suppress("DEPRECATION")
    fun capture(web: WebView): Picture {
        // Older WebViews include the current scroll offset in captured pictures.
        val x = web.scrollX
        val y = web.scrollY
        try {
            web.scrollTo(0, 0)
            val picture = web.capturePicture()
            require(picture.width > 0 && picture.height > 0) { "The page has no drawable content" }
            return picture
        } finally {
            web.scrollTo(x, y)
        }
    }

    fun write(picture: Picture, output: OutputStream, cancellation: CancellationSignal) {
        cancellation.throwIfCanceled()
        val width = minOf(picture.width, MAX_RASTER_WIDTH)
        val height = width * PAGE_HEIGHT / PAGE_WIDTH
        val scale = width.toFloat() / picture.width
        val sourcePageHeight = height / scale
        val pageCount = ceil(picture.height / sourcePageHeight.toDouble()).toInt()
        val bitmap = createBitmap(width, height)
        val document = PdfDocument()
        try {
            val raster = Canvas(bitmap)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            for (index in 0 until pageCount) {
                cancellation.throwIfCanceled()
                bitmap.eraseColor(Color.WHITE)
                val save = raster.save()
                try {
                    raster.scale(scale, scale)
                    raster.translate(0f, -index * sourcePageHeight)
                    picture.draw(raster)
                } finally {
                    raster.restoreToCount(save)
                }
                // PdfDocument retains images until writeTo; a mutable bitmap would overwrite earlier pages.
                val image = requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                try {
                    val page = document.startPage(
                        PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create()
                    )
                    try {
                        page.canvas.drawBitmap(image, null, RectF(0f, 0f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT.toFloat()), paint)
                    } finally {
                        document.finishPage(page)
                    }
                } finally {
                    image.recycle()
                }
            }
            cancellation.throwIfCanceled()
            document.writeTo(output)
            cancellation.throwIfCanceled()
        } finally {
            try {
                document.close()
            } finally {
                bitmap.recycle()
            }
        }
    }

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MAX_RASTER_WIDTH = 1240
}
