package net.conw.unit.browser

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.core.net.toUri
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.browser.page.BrowserPdf
import net.conw.unit.scan.ScanActivity
import net.conw.unit.ui.Ui

/** Owns pending system requests and releases their callbacks when the Activity closes. */
internal class BrowserRequests(private val activity: BrowserActivity) {
    private var files: ValueCallback<Array<Uri>>? = null
    private var permissionCallback: ((Boolean) -> Unit)? = null

    fun sharePage() {
        val tab = activity.current() ?: return
        if (!UrlUtils.isWeb(tab.url)) return
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, tab.url)
                putExtra(Intent.EXTRA_SUBJECT, tab.title)
            }
        activity.startActivity(Intent.createChooser(intent, null))
    }

    fun external(url: String) {
        try {
            val parsed =
                if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                else Intent(Intent.ACTION_VIEW, url.toUri())
            val scheme = parsed.data?.scheme?.lowercase()
            if (scheme in setOf("javascript", "file", "content", "data", "about", "blob")) {
                activity.message(
                    if (scheme == "blob") R.string.unsupported_download
                    else R.string.unsupported_link
                )
                return
            }
            val fallback = parsed.getStringExtra("browser_fallback_url")
            // Forward only the destination and package, never arbitrary extras or URI grants.
            val safe =
                Intent(Intent.ACTION_VIEW, parsed.data)
                    .setPackage(parsed.`package`)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
            activity.showDialog(
                Ui.dialog(activity)
                    .setTitle(R.string.open_app_title)
                    .setMessage(
                        activity.getString(
                            R.string.open_app_message,
                            if (activity.current()?.isHome == true) activity.getString(R.string.home)
                            else UrlUtils.host(activity.currentUrl()),
                            safe.data?.scheme ?: activity.getString(R.string.external_app),
                        )
                    )
                    .setPositiveButton(R.string.open) { _, _ ->
                        try {
                            activity.startActivity(safe)
                        } catch (_: ActivityNotFoundException) {
                            if (UrlUtils.isWeb(fallback))
                                activity.navigate(requireNotNull(fallback))
                            else activity.message(R.string.no_link_app)
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .create()
            )
        } catch (_: Exception) {
            activity.message(R.string.unsupported_link)
        }
    }

    fun chooseFiles(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ) {
        finishFiles(null)
        files = callback
        activity.fullscreen.hold()
        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        val types = params.acceptTypes.filter { it.contains('/') }
        if (types.size == 1) intent.type = types.first()
        else if (types.size > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
        intent.putExtra(
            Intent.EXTRA_ALLOW_MULTIPLE,
            params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE,
        )
        try {
            activity.startActivityForResult(intent, PICK_FILES)
        } catch (_: ActivityNotFoundException) {
            finishFiles(null)
            activity.message(R.string.no_file_picker)
        }
    }

    fun scan() {
        activity.startActivityForResult(
            Intent(activity, ScanActivity::class.java).putExtra("incognito", activity.isIncognito),
            SCAN,
        )
    }

    fun permissions(permissions: Array<String>, callback: (Boolean) -> Unit) {
        if (
            permissions.all {
                activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            callback(true)
            return
        }
        if (permissionCallback != null) {
            callback(false)
            return
        }
        permissionCallback = callback
        activity.fullscreen.hold()
        activity.requestPermissions(permissions, PERMISSIONS)
    }

    fun activityResult(request: Int, result: Int, data: Intent?) {
        when (request) {
            BrowserPdf.SAVE_REQUEST ->
                activity.pdf.destination(
                    if (result == android.app.Activity.RESULT_OK) data?.data else null
                )
            SCAN ->
                if (result == android.app.Activity.RESULT_OK)
                    data?.getStringExtra("url")?.takeIf(UrlUtils::isWeb)?.let(activity::navigate)
            PICK_FILES -> {
                val uris =
                    if (result == android.app.Activity.RESULT_OK && data != null) {
                        data.clipData?.let { clip ->
                            List(clip.itemCount) { clip.getItemAt(it).uri }
                        } ?: listOfNotNull(data.data)
                    } else emptyList()
                finishFiles(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
            }
        }
    }

    fun permissionResult(request: Int, results: IntArray) {
        if (request == PERMISSIONS)
            finishPermissions(
                results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }
            )
    }

    private fun finishFiles(value: Array<Uri>?) {
        val callback = files ?: return
        files = null
        activity.fullscreen.release()
        callback.onReceiveValue(value)
    }

    private fun finishPermissions(granted: Boolean) {
        val callback = permissionCallback ?: return
        permissionCallback = null
        activity.fullscreen.release()
        callback(granted)
    }

    fun destroy() {
        finishFiles(null)
        if (permissionCallback != null) {
            permissionCallback = null
            activity.fullscreen.release()
        }
    }

    private companion object {
        const val PICK_FILES = 10
        const val SCAN = 11
        const val PERMISSIONS = 20
    }
}
