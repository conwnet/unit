package net.conw.unit.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.webkit.URLUtilCompat
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.Locale

/** Scoped storage on Android 10+, a public file and URI grant on Android 9. */
internal object DownloadFiles {
    fun name(url: String, disposition: String?, mime: String?): String {
        val filename =
            URLUtilCompat.guessFileName(url, disposition, mime)
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                .trim()
                .takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "download"
        val dot = filename.lastIndexOf('.')
        val extension = if (dot > 0) filename.substring(dot) else ""
        return if (extension.length < 160)
            filename.removeSuffix(extension).take(160 - extension.length) + extension
        else filename.take(160)
    }

    fun mimeType(filename: String, mime: String?): String? {
        if (mime != null && !mime.equals("application/octet-stream", ignoreCase = true)) return mime
        val extension = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: mime
    }

    private fun uniqueName(filename: String, exists: (String) -> Boolean): String {
        val dot = filename.lastIndexOf('.')
        val stem = if (dot > 0) filename.substring(0, dot) else filename
        val extension = if (dot > 0) filename.substring(dot) else ""
        var name = filename
        var suffix = 1
        while (exists(name)) name = "$stem (${suffix++})$extension"
        return name
    }

    fun create(context: Context, download: Download): Uri =
        if (Build.VERSION.SDK_INT >= 29) {
            val values =
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, download.name)
                    put(MediaStore.Downloads.MIME_TYPE, download.mime ?: "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Unable to create the download file")
        } else {
            val folder =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!folder.mkdirs() && !folder.isDirectory)
                throw IOException("Unable to create the download folder")
            Uri.fromFile(folder.resolve(".unit-${download.id}.part"))
        }

    fun output(context: Context, uri: Uri): OutputStream =
        if (uri.scheme == "file") requireNotNull(uri.path).let { File(it).outputStream() }
        else
            context.contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("Unable to open the download file")

    fun complete(context: Context, download: Download): Download {
        val uri = (requireNotNull(download.uri)).toUri()
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            val name =
                context.contentResolver
                    .query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                    ?.use {
                        if (it.moveToFirst()) it.getString(0) else download.name
                    } ?: download.name
            return download.copy(name = name)
        }
        return synchronized(this) {
            val part = File(requireNotNull(uri.path))
            val folder = requireNotNull(part.parentFile)
            val name = uniqueName(download.name) { folder.resolve(it).exists() }
            val target = folder.resolve(name)
            if (!part.renameTo(target)) throw IOException("Unable to finish the download file")
            download.copy(name = target.name, uri = Uri.fromFile(target).toString())
        }
    }

    fun openIntent(context: Context, download: Download): Intent {
        var uri = (download.uri ?: throw IOException("The download file is unavailable")).toUri()
        if (uri.scheme == "file")
            uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.files",
                    File(requireNotNull(uri.path)),
                )
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, download.mime ?: "application/octet-stream")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun delete(context: Context, download: Download) {
        val uri = download.uri?.let { it.toUri() } ?: return
        if (uri.scheme == "file") Files.deleteIfExists(File(requireNotNull(uri.path)).toPath())
        else if (context.contentResolver.delete(uri, null, null) == 0) {
            val missing =
                context.contentResolver
                    .query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                    ?.use { !it.moveToFirst() }
                    ?: throw IOException("Unable to check the download file")
            if (!missing) throw IOException("Unable to delete the download file")
        }
    }
}
