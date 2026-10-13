package net.conw.unit.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import net.conw.unit.browser.UrlUtils
import net.conw.unit.download.Download
import net.conw.unit.download.DownloadState
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Both browsing processes share this database; private tab sessions stay in memory.
 * Explicit Closeable support keeps use() compatible with Android 9.
 */
internal class BrowserStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "unit.db", null, 1), Closeable {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE bookmarks (url TEXT PRIMARY KEY, title TEXT NOT NULL, time INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE history (url TEXT PRIMARY KEY, title TEXT NOT NULL, time INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE permissions (origin TEXT, resource TEXT, allowed INTEGER NOT NULL, PRIMARY KEY(origin,resource))"
        )
        // Stable task IDs keep delayed callbacks and notification actions away from newer jobs.
        db.execSQL(
            "CREATE TABLE downloads (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, name TEXT NOT NULL, mime TEXT, private INTEGER NOT NULL, state TEXT NOT NULL, bytes INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL DEFAULT -1, uri TEXT, error TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("Database version mismatch: $oldVersion -> $newVersion")
    }

    fun get(key: String): String? =
        readableDatabase
            .query(
                "settings",
                arrayOf("value"),
                "key=?",
                arrayOf(key),
                null,
                null,
                null,
            )
            .use { if (it.moveToFirst()) it.getString(0) else null }

    fun get(key: String, fallback: String): String = get(key) ?: fallback

    fun flag(key: String) = get(key, "false") == "true"

    fun put(key: String, value: String) {
        val values =
            ContentValues().apply {
                put("key", key)
                put("value", value)
            }
        writableDatabase.insertWithOnConflict(
            "settings",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun put(key: String, value: Boolean) = put(key, value.toString())

    fun session(): Session {
        val value = get("session") ?: return Session(emptyList(), 0)
        return try {
            val saved = JSONObject(value)
            val tabs = saved.getJSONArray("tabs")
            val pages = buildList {
                repeat(tabs.length()) { index ->
                    val item = tabs.getJSONObject(index)
                    val url = item.getString("url")
                    if (UrlUtils.isWeb(url) || url == UrlUtils.HOME)
                        add(Page(url, item.getString("title")))
                }
            }
            Session(pages, saved.getInt("selected").coerceIn(0, maxOf(0, pages.lastIndex)))
        } catch (_: JSONException) {
            Session(emptyList(), 0)
        }
    }

    fun session(pages: List<Page>, selected: Int) {
        val tabs = JSONArray()
        pages.forEach {
            tabs.put(
                JSONObject()
                    .put("url", if (UrlUtils.isWeb(it.url)) it.url else UrlUtils.HOME)
                    .put("title", it.title)
            )
        }
        put("session", JSONObject().put("tabs", tabs).put("selected", selected).toString())
    }

    fun savePage(bookmark: Boolean, url: String, title: String?) {
        if (!UrlUtils.isWeb(url)) return
        val values =
            ContentValues().apply {
                put("url", url)
                put("title", title?.takeIf { it.isNotBlank() } ?: url)
                put("time", System.currentTimeMillis())
            }
        writableDatabase.insertWithOnConflict(
            if (bookmark) "bookmarks" else "history",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        if (!bookmark)
            writableDatabase.execSQL(
                "DELETE FROM history WHERE url NOT IN (SELECT url FROM history ORDER BY time DESC LIMIT 2000)"
            )
    }

    fun pages(bookmarks: Boolean): List<Page> =
        readableDatabase
            .query(
                if (bookmarks) "bookmarks" else "history",
                null,
                null,
                null,
                null,
                null,
                "time DESC",
            )
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(Page(cursor.getString(0), cursor.getString(1)))
                }
            }

    fun deletePage(bookmarks: Boolean, url: String?) {
        writableDatabase.delete(
            if (bookmarks) "bookmarks" else "history",
            url?.let { "url=?" },
            url?.let { arrayOf(it) },
        )
    }

    fun permission(origin: String, resource: String): Boolean? =
        readableDatabase
            .query(
                "permissions",
                arrayOf("allowed"),
                "origin=? AND resource=?",
                arrayOf(origin, resource),
                null,
                null,
                null,
            )
            .use { if (it.moveToFirst()) it.getInt(0) == 1 else null }

    fun permission(origin: String, resource: String, allowed: Boolean) {
        val values =
            ContentValues().apply {
                put("origin", origin)
                put("resource", resource)
                put("allowed", if (allowed) 1 else 0)
            }
        writableDatabase.insertWithOnConflict(
            "permissions",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun clearPermissions() {
        writableDatabase.delete("permissions", null, null)
    }

    fun addDownload(url: String, filename: String, incognito: Boolean): Download {
        val values =
            ContentValues().apply {
                put("url", url)
                put("name", filename)
                put("private", if (incognito) 1 else 0)
                put("state", DownloadState.QUEUED.name)
            }
        val id = writableDatabase.insertOrThrow("downloads", null, values)
        return Download(id, url, filename, null, incognito)
    }

    fun downloads(): List<Download> = queryDownloads(null, null)

    fun download(id: Long): Download? = queryDownloads("id=?", arrayOf(id.toString())).firstOrNull()

    private fun queryDownloads(selection: String?, arguments: Array<String>?): List<Download> =
        readableDatabase
            .query(
                "downloads",
                arrayOf(
                    "id",
                    "url",
                    "name",
                    "mime",
                    "private",
                    "state",
                    "bytes",
                    "total",
                    "uri",
                    "error",
                ),
                selection,
                arguments,
                null,
                null,
                "id DESC",
            )
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(
                        Download(
                            cursor.getLong(0),
                            cursor.getString(1),
                            cursor.getString(2),
                            cursor.getString(3),
                            cursor.getInt(4) == 1,
                            DownloadState.valueOf(cursor.getString(5)),
                            cursor.getLong(6),
                            cursor.getLong(7),
                            cursor.getString(8),
                            cursor.getString(9),
                        )
                    )
                }
            }

    fun updateDownload(download: Download) {
        val values =
            ContentValues().apply {
                put("name", download.name)
                put("mime", download.mime)
                put("state", download.state.name)
                put("bytes", download.bytes)
                put("total", download.total)
                put("uri", download.uri)
                put("error", download.error)
            }
        writableDatabase.update("downloads", values, "id=?", arrayOf(download.id.toString()))
    }

    fun deleteDownload(id: Long) {
        writableDatabase.delete("downloads", "id=?", arrayOf(id.toString()))
    }

    data class Page(val url: String, val title: String)

    data class Session(val pages: List<Page>, val selected: Int)
}
