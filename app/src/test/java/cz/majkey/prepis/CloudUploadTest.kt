package cz.majkey.prepis

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CloudUploadTest {
    @Test
    fun `provider runtime failure becomes an upload IOException`(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor =
                throw IllegalArgumentException("Provider rejected the document")
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?,
                selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = "recordings" })
        ShadowContentResolver.registerProviderInternal("recordings", provider)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val releaseServer = CountDownLatch(1)
            val serving = thread(isDaemon = true) {
                server.accept().use { releaseServer.await(5, TimeUnit.SECONDS) }
            }
            try {
                val body = CloudClient(context.contentResolver).audioBody(Uri.parse("content://recordings/one"), 10)
                val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/").post(body).build()
                val failure = assertFailsWith<IOException> {
                    HttpDeadline(3_000).execute(request) { it.code }
                }
                assertEquals("The recording cannot be read", failure.message)
                val cause = generateSequence<Throwable>(failure) { it.cause }.last()
                assertIs<IllegalArgumentException>(cause, cause.toString())
            } finally {
                releaseServer.countDown()
                serving.join(2_000)
            }
        }
    }
}
