package cz.majkey.prepis

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LocalAudioTestProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String = "audio/wav"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        opens.incrementAndGet()
        if (uri.lastPathSegment == "blocked-open") gate.await(30, TimeUnit.SECONDS)
        if (uri.lastPathSegment == "blocked-read") {
            val pipe = ParcelFileDescriptor.createPipe()
            pipeWriter = pipe[1]
            return AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }
        if (uri.lastPathSegment == "oversize-stream") {
            val pipe = ParcelFileDescriptor.createPipe()
            Thread {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(ByteArray(4096)) }
                } catch (_: IOException) {
                    // The bounded reader may close its pipe before all fixture bytes are written.
                }
            }.start()
            return AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }
        val file = File(requireNotNull(context).cacheDir, "local-audio-fixture.wav")
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(
            descriptor,
            if (uri.lastPathSegment == "offset") 16 else 0,
            if (uri.lastPathSegment == "offset") file.length() - 16 else AssetFileDescriptor.UNKNOWN_LENGTH,
        )
    }

    companion object {
        val opens = AtomicInteger()
        var gate = CountDownLatch(1)
        var pipeWriter: ParcelFileDescriptor? = null

        fun reset() {
            gate.countDown()
            pipeWriter?.close()
            pipeWriter = null
            gate = CountDownLatch(1)
            opens.set(0)
        }
    }
}
