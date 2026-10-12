package net.conw.unit

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.conw.unit.data.BrowserStore
import net.conw.unit.download.Download
import net.conw.unit.download.DownloadRequest
import net.conw.unit.download.DownloadService
import net.conw.unit.download.DownloadSession
import net.conw.unit.download.DownloadState
import net.conw.unit.proxy.BrowserProxy
import net.conw.unit.proxy.ProxySettings
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadTest {
    private val runner = InstrumentationRegistry.getInstrumentation()
    private val context = runner.targetContext
    private lateinit var store: BrowserStore
    private lateinit var proxy: BrowserProxy
    private lateinit var saved: ProxySettings
    private lateinit var activity: Activity
    private val created = mutableListOf<Long>()

    @Before
    fun prepare() {
        assumeTrue(
            "Start the local fixtures with python3 tests/run.py --suite downloads",
            InstrumentationRegistry.getArguments().getString("fixtures") == "true",
        )
        proxy = (context.applicationContext as UnitApplication).proxy
        saved = proxy.settings()
        store = BrowserStore(context)
        activity = runner.startActivitySync(
            Intent(context, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    @After
    fun cleanup() {
        if (!::store.isInitialized) return
        try {
            for (id in created) store.download(id)?.let { row ->
                runner.runOnMainSync { DownloadService.cancel(context, store, row) }
                awaitRemoval(id)
            }
        } finally {
            try {
                apply(saved)
            } finally {
                store.close()
                if (::activity.isInitialized) runner.runOnMainSync { activity.finish() }
            }
        }
    }

    @Test
    fun httpProxyRetainsBrowserCredentials() = verifyContents(http(), HTTP)

    @Test
    fun socksProxyDownloadsFile() = verifyContents(
        ProxySettings(true, ProxySettings.Type.SOCKS5, "127.0.0.1", 8882), HTTP,
    )

    @Test
    fun redirectDoesNotForwardCredentials() {
        val row = start(http().copy(bypass = listOf("localhost")), "http://proxy-target.test:8780/redirect-other")
        val complete = await(row.id) { !it.state.isActive }
        assertEquals(complete.error, DownloadState.COMPLETED, complete.state)
    }

    @Test
    fun failedAuthenticationRemovesPartialFile() {
        val row = start(http().copy(password = "wrong"), "http://proxy-target.test:8780/file.bin")
        val failed = await(row.id) { !it.state.isActive }
        assertEquals(DownloadState.FAILED, failed.state)
        assertNull(failed.uri)
    }

    @Test
    fun cancellationRemovesTaskAndFile() {
        val row = start(http(), "http://proxy-target.test:8780/slow.bin")
        val downloading = await(row.id) { it.bytes > 0 }
        val uri = Uri.parse(requireNotNull(downloading.uri))
        runner.runOnMainSync { DownloadService.cancel(context, store, requireNotNull(store.download(row.id))) }
        awaitRemoval(row.id, uri)
    }

    @Test
    fun backgroundTransferKeepsOriginalProxy() {
        val row = start(http(), "http://proxy-target.test:8780/slow.bin")
        await(row.id) { it.bytes > 0 }
        apply(http().copy(enabled = false))
        runner.runOnMainSync { activity.moveTaskToBack(true) }
        val complete = await(row.id) { !it.state.isActive }
        assertEquals(complete.error, DownloadState.COMPLETED, complete.state)
    }

    private fun verifyContents(settings: ProxySettings, url: String) {
        val row = start(settings, url)
        val complete = await(row.id) { !it.state.isActive }
        assertEquals(complete.error, DownloadState.COMPLETED, complete.state)
        val bytes = context.contentResolver.openInputStream(Uri.parse(complete.uri))!!.use { it.readBytes() }
        assertArrayEquals("unit proxy download\n".repeat(8192).toByteArray(), bytes)
    }

    private fun start(settings: ProxySettings, url: String): Download {
        val row = store.addDownload(url, "unit-proxy-test.bin", "application/octet-stream", false)
        created.add(row.id)
        runner.runOnMainSync {
            DownloadService.enqueue(
                context, row,
                DownloadSession(
                    settings,
                    DownloadRequest.Headers("unit-fixture", "session=unit", "http://proxy-target.test:8780/page"),
                ),
            )
        }
        return row
    }

    private fun await(id: Long, ready: (Download) -> Boolean): Download {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            val row = requireNotNull(store.download(id))
            if (ready(row)) return row
            Thread.sleep(50)
        }
        error("Download $id timed out: ${store.download(id)}")
    }

    private fun apply(config: ProxySettings) {
        val done = CountDownLatch(1)
        var success = false
        runner.runOnMainSync { proxy.save(config) { success = it; done.countDown() } }
        check(done.await(10, TimeUnit.SECONDS) && success) { "Unable to restore proxy settings" }
    }

    private fun awaitRemoval(id: Long, uri: Uri? = null) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while ((store.download(id) != null || (uri != null && canRead(uri))) && System.nanoTime() < deadline)
            Thread.sleep(50)
        assertNull("Cancelled download $id remains", store.download(id))
        if (uri != null) assertFalse("The cancelled download file remains", canRead(uri))
    }

    private fun canRead(uri: Uri): Boolean =
        try {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        } catch (_: FileNotFoundException) {
            false
        }

    private fun http() =
        ProxySettings(true, ProxySettings.Type.HTTP, "127.0.0.1", 8880, "unit", "proxy-pass")

    companion object {
        private const val HTTP = "http://proxy-target.test:8780/cookie-file.bin"
    }
}
