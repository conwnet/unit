package net.conw.unit.browser.page

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintJob
import android.print.PrintManager
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.io.IOException
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.browser.BrowserTab
import net.conw.unit.browser.UrlUtils
import net.conw.unit.ui.Ui

internal class BrowserPdf(private val activity: BrowserActivity) {
    private var active: Export? = null
    val isBusy
        get() = active != null

    fun export() {
        if (isBusy || activity.isFinishing || activity.isDestroyed) return
        val tab = activity.current() ?: return
        val web = tab.web ?: return
        if (!UrlUtils.isWeb(tab.url) || tab.isHome || web.progress < 100) return
        var document: Export? = null
        try {
            val title = tab.title.ifBlank { UrlUtils.host(tab.url) }
            val manager =
                if (activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PRINTING))
                    activity.getSystemService(PrintManager::class.java)
                else null
            if (manager != null) {
                document = Export(tab, web, web.createPrintDocumentAdapter(title))
                active = document
                try {
                    document.job = manager.print(title, document, PrintAttributes.Builder().build())
                    if (document.job != null) return
                } catch (error: RuntimeException) {
                    Log.w(TAG, "System print preview is unavailable", error)
                }
                document.onFinish()
            }
            document = Export(tab, web)
            active = document
            document.waitingForDestination = true
            activity.startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/pdf")
                    .putExtra(
                        Intent.EXTRA_TITLE,
                        title.replace(Regex("[\\\\/\\p{Cntrl}]"), "_").take(120) + ".pdf",
                    ),
                SAVE_REQUEST,
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to start PDF export", error)
            document?.onFinish()
            activity.message(R.string.pdf_failed)
        }
    }

    fun destination(uri: Uri?) {
        val document = active?.takeIf { it.waitingForDestination } ?: return
        document.waitingForDestination = false
        if (uri == null) document.onFinish() else document.save(uri)
    }

    fun cancel(web: WebView? = null) {
        val document = active ?: return
        if (web != null && document.web !== web) return
        try {
            document.job?.cancel()
        } finally {
            document.onFinish()
        }
    }

    private inner class Export(
        private val tab: BrowserTab,
        val web: WebView,
        private val delegate: PrintDocumentAdapter? = null,
    ) : PrintDocumentAdapter() {
        var job: PrintJob? = null
        var waitingForDestination = false
        private var finished = false
        private val visibility = web.visibility
        private val cancellation = CancellationSignal()
        private var destination: Uri? = null
        private var writing = false
        private var saved = false
        private var dialog: AlertDialog? = null

        fun save(uri: Uri) {
            destination = uri
            try {
                dialog = Ui.dialog(activity)
                    .setTitle(R.string.export_pdf_title)
                    .setMessage(R.string.pdf_exporting)
                    .setNegativeButton(R.string.cancel) { _, _ -> cancel() }
                    .create()
                activity.showDialog(requireNotNull(dialog)) { if (active === this) cancel() }
                // Finish pending scroll and layout updates before taking a static snapshot.
                web.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (!finished) write(uri)
                    }
                })
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to prepare PDF export", error)
                fail()
            }
        }

        private fun write(uri: Uri) {
            try {
                val picture = WebPagePdf.capture(web)
                writing = true
                val resolver = activity.contentResolver
                Thread(
                    {
                        val result = runCatching {
                            cancellation.throwIfCanceled()
                            resolver.openOutputStream(uri, "w")?.use {
                                WebPagePdf.write(picture, it, cancellation)
                            } ?: throw IOException("Unable to open the PDF destination")
                        }
                        activity.runOnUiThread {
                            writing = false
                            if (finished) {
                                deleteIncomplete()
                            } else if (result.isSuccess) {
                                saved = true
                                onFinish()
                                activity.message(R.string.pdf_saved)
                            } else {
                                Log.w(TAG, "Unable to write PDF", result.exceptionOrNull())
                                fail()
                            }
                        }
                    },
                    "unit-pdf-export",
                ).start()
            } catch (error: RuntimeException) {
                writing = false
                Log.w(TAG, "Unable to capture the page", error)
                fail()
            }
        }

        private fun fail() {
            onFinish()
            activity.message(R.string.pdf_failed)
        }

        override fun onStart() {
            if (finished) return
            // WebView drawing is undefined while the system print adapter is active.
            web.visibility = View.INVISIBLE
            delegate?.onStart()
        }

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            if (finished) callback.onLayoutCancelled()
            else requireNotNull(delegate).onLayout(oldAttributes, newAttributes, cancellationSignal, callback, extras)
        }

        override fun onWrite(
            pages: Array<out PageRange>,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal,
            callback: WriteResultCallback,
        ) {
            if (finished) callback.onWriteCancelled()
            else requireNotNull(delegate).onWrite(pages, destination, cancellationSignal, callback)
        }

        override fun onFinish() {
            if (finished) return
            finished = true
            if (active === this) active = null
            cancellation.cancel()
            dialog?.dismiss()
            try {
                delegate?.onFinish()
            } finally {
                if (tab.web === web && !activity.isDestroyed) {
                    web.visibility = visibility
                    web.invalidate()
                }
                // The worker closes the stream before removing a cancelled destination.
                if (!writing) deleteIncomplete()
            }
        }

        private fun deleteIncomplete() {
            if (saved) return
            val uri = destination ?: return
            destination = null
            val resolver = activity.contentResolver
            Thread(
                {
                    runCatching {
                        when {
                            uri.scheme == "file" -> File(requireNotNull(uri.path)).delete()
                            DocumentsContract.isDocumentUri(activity, uri) -> DocumentsContract.deleteDocument(resolver, uri)
                            else -> resolver.delete(uri, null, null)
                        }
                    }.onFailure { Log.w(TAG, "Unable to remove incomplete PDF", it) }
                },
                "unit-pdf-cleanup",
            ).start()
        }
    }

    companion object {
        const val SAVE_REQUEST = 12
        private const val TAG = "UnitPdf"
    }
}
