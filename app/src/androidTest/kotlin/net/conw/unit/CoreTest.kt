package net.conw.unit

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import net.conw.unit.browser.BrowserLanguages
import net.conw.unit.browser.HomePage
import net.conw.unit.browser.UrlUtils
import net.conw.unit.data.BrowserPreferences
import net.conw.unit.data.BrowserStore
import net.conw.unit.download.DownloadState
import net.conw.unit.proxy.ProxySettings
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreTest {
    @Test
    fun addressResolutionAndInputValidation() {
        assertTrue(UrlUtils.resolve(" example.com ", "Bing") == "https://example.com")
        assertTrue(UrlUtils.resolve("localhost:8765/test", "Bing") == "http://localhost:8765/test")
        assertTrue(UrlUtils.resolve("hello world", "Bing").endsWith("?q=hello%20world"))
        assertTrue(
            UrlUtils.resolve("javascript:alert(1)", "Bing")
                .startsWith("https://www.bing.com/search?")
        )
        assertTrue(
            listOf(
                    "file:///private",
                    "https:plain-text",
                    "http://",
                    UrlUtils.HOME,
                    "https://unit.invalid/bookmarks",
                )
                .none(UrlUtils::isWeb)
        )
        assertTrue(runCatching { BrowserLanguages.normalize("en-US\r\nX-Test: injected") }.isFailure)
    }

    @Test
    fun homepageDataEscaping() {
        val title = "</script><script>window.injected=true</script> & \u2028"
        val data = HomePage.scriptData(JSONObject().put("title", title).toString())
        assertTrue(!data.contains("</script>") && JSONObject(data).getString("title") == title)
    }

    @Test
    fun startupPages() = withStore { _, store ->
        val preferences = BrowserPreferences(store)
        preferences.homePage = "example.com"
        assertTrue(preferences.startUrl() == "https://example.com")
        assertTrue(preferences.startUrl(incognito = true) == UrlUtils.HOME)
        preferences.homePage = ""
        assertTrue(preferences.startUrl() == UrlUtils.HOME)
    }

    @Test
    fun tabRestorationAndCorruptSessionRecovery() = withStore { isolated, store ->
        assertTrue(store.session().pages.isEmpty())
        store.session(
            listOf(
                BrowserStore.Page(UrlUtils.HOME, "Home"),
                BrowserStore.Page("https://example.com", "Example"),
            ),
            1,
        )
        BrowserStore(isolated).use { reopened ->
            assertTrue(reopened.session().pages.size == 2 && reopened.session().selected == 1)
        }
        store.put(
            "session",
            """{"tabs":[{"url":"file:///private","title":"Private"},{"url":"https://example.com","title":"Example"}],"selected":99}""",
        )
        assertTrue(store.session().pages.size == 1 && store.session().selected == 0)
        store.put("session", "broken json")
        assertTrue(store.session().pages.isEmpty())
    }

    @Test
    fun bookmarkAndHistoryPersistence() = withStore { isolated, store ->
        val page = BrowserStore.Page("https://example.com", "Example")
        store.savePage(true, page.url, page.title)
        store.savePage(false, page.url, page.title)
        BrowserStore(isolated).use { reopened ->
            assertTrue(reopened.pages(true) == listOf(page))
            assertTrue(reopened.pages(false) == listOf(page))
        }
    }

    @Test
    fun sitePermissionPersistence() = withStore { _, store ->
        store.permission("https://example.com", "camera", true)
        assertTrue(store.permission("https://example.com", "camera") == true)
        assertTrue(store.permission("https://other.example", "camera") == null)
        store.clearPermissions()
        assertTrue(store.permission("https://example.com", "camera") == null)
    }

    @Test
    fun encryptedProxyCredentials() = withStore { isolated, store ->
        assertTrue(ProxySettings.load(isolated, store) == ProxySettings())
        val config =
            ProxySettings(
                true,
                ProxySettings.Type.HTTP,
                "proxy.example",
                8080,
                "unit",
                "fixture-password",
                listOf("localhost", "*.example.com", "192.168.0.0/16"),
            )
        config.save(isolated, store)
        assertTrue(ProxySettings.load(isolated, store) == config)
        assertTrue(!store.get("proxy", "").contains(config.password))
    }

    @Test
    fun downloadProgressAndStaleCallbacks() = withStore { _, store ->
        val row =
            store.addDownload(
                "https://example.com/file",
                "file.bin",
                false,
            )
        val progress =
            row.copy(
                state = DownloadState.RUNNING,
                bytes = 512,
                total = 1024,
            )
        store.updateDownload(progress)
        assertTrue(store.download(row.id) == progress)
        store.deleteDownload(row.id)
        val next =
            store.addDownload("https://example.com/next", "next.bin", false)
        store.updateDownload(progress)
        assertTrue(
            store.download(row.id) == null &&
                next.id > row.id &&
                store.download(next.id) == next
        )
    }

    private fun withStore(verify: (Context, BrowserStore) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = Files.createTempDirectory(context.cacheDir.toPath(), "unit-core-").toFile()
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this

            override fun getPackageName() = "${context.packageName}.${folder.name}"

            override fun getDatabasePath(name: String) = File(folder, name)

            override fun openOrCreateDatabase(
                name: String,
                mode: Int,
                factory: SQLiteDatabase.CursorFactory?,
                handler: DatabaseErrorHandler?,
            ): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)

            override fun openOrCreateDatabase(
                name: String,
                mode: Int,
                factory: SQLiteDatabase.CursorFactory?,
            ): SQLiteDatabase = openOrCreateDatabase(name, mode, factory, null)
        }
        try {
            BrowserStore(isolated).use { verify(isolated, it) }
        } finally {
            try {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    .deleteEntry("${isolated.packageName}.proxy")
            } finally {
                folder.deleteRecursively()
            }
        }
    }
}
